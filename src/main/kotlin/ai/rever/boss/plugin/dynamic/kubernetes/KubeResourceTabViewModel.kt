package ai.rever.boss.plugin.dynamic.kubernetes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * State for one resource tab: its own copy of the object, a log stream, describe
 * and YAML output, events, and (for previewable kinds) a port-forward.
 *
 * The tab fetches its own object rather than reading the sidebar's lists — those
 * are only populated while the matching section is expanded, and a tab must keep
 * working after the user collapses it.
 */
class KubeResourceTabViewModel(
    private val services: KubeServices,
    val tabInfo: KubeResourceTabInfo,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val kind: String get() = tabInfo.kind
    val name: String get() = tabInfo.resourceName

    val availableSections: List<ResourceSection> = sectionsFor(tabInfo.kind)

    private val _section = MutableStateFlow(availableSections.first())
    val section: StateFlow<ResourceSection> = _section.asStateFlow()

    /** The pod behind this tab, when the kind is a pod. Drives the container picker. */
    private val _pod = MutableStateFlow<PodInfo?>(null)
    val pod: StateFlow<PodInfo?> = _pod.asStateFlow()

    /** The service behind this tab, when the kind is a service. Drives preview ports. */
    private val _service = MutableStateFlow<ServiceInfo?>(null)
    val service: StateFlow<ServiceInfo?> = _service.asStateFlow()

    private val _exists = MutableStateFlow(true)
    val exists: StateFlow<Boolean> = _exists.asStateFlow()

    private val _selectedContainer = MutableStateFlow<String?>(null)
    val selectedContainer: StateFlow<String?> = _selectedContainer.asStateFlow()

    private val _previous = MutableStateFlow(false)
    val previous: StateFlow<Boolean> = _previous.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _autoScroll = MutableStateFlow(true)
    val autoScroll: StateFlow<Boolean> = _autoScroll.asStateFlow()

    private val _describe = MutableStateFlow<String?>(null)
    val describe: StateFlow<String?> = _describe.asStateFlow()

    private val _yaml = MutableStateFlow<String?>(null)
    val yaml: StateFlow<String?> = _yaml.asStateFlow()

    private val _events = MutableStateFlow<List<EventInfo>?>(null)
    val events: StateFlow<List<EventInfo>?> = _events.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    val forwards: StateFlow<Map<ForwardKey, ForwardInfo>> get() = services.forwards.forwards

    /**
     * Log lines live in a deque, not the StateFlow: a chatty pod emits thousands of
     * lines a second and a new immutable list per line would melt the UI. The flow
     * is republished on a timer instead.
     */
    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val bufferLock = Mutex()
    private var dirty = false

    private var logJob: Job? = null

    init {
        scope.launch { refreshObject() }
        scope.launch {
            while (isActive) {
                delay(FLUSH_MS)
                if (!dirty || _paused.value) continue
                bufferLock.withLock {
                    _logs.value = buffer.toList()
                    dirty = false
                }
            }
        }
        // Keep the object fresh so status, ready counts and ports track reality.
        scope.launch {
            while (isActive) {
                delay(OBJECT_POLL_MS)
                refreshObject()
            }
        }
    }

    fun dispose() {
        logJob?.cancel()
        // A tab's forwards belong to that tab: closing it must not leave a kubectl
        // port-forward running for something nobody is looking at.
        currentForwardKeys().forEach { services.forwards.stop(it) }
        scope.cancel()
    }

    fun selectSection(section: ResourceSection) {
        _section.value = section
        when (section) {
            ResourceSection.DESCRIBE -> if (_describe.value == null) refreshDescribe()
            ResourceSection.YAML -> if (_yaml.value == null) refreshYaml()
            ResourceSection.EVENTS -> if (_events.value == null) refreshEvents()
            ResourceSection.LOGS -> if (logJob?.isActive != true) startLogStream()
            ResourceSection.PREVIEW -> Unit
        }
    }

    // ---------------------------------------------------------------- object

    private suspend fun refreshObject() {
        val isPod = kind.lowercase().trimEnd('s') == "pod"
        val isService = kind.lowercase().trimEnd('s') == "service"
        if (!isPod && !isService) return

        val result = KubectlCli.exec(
            services.engine.args(listOf("get", kind.lowercase(), name, "-o", "json")),
        )
        if (!result.ok) {
            if (result.stderr.contains("NotFound", ignoreCase = true) ||
                result.stderr.contains("not found", ignoreCase = true)
            ) {
                _exists.value = false
            }
            return
        }
        _exists.value = true
        if (isPod) {
            val parsed = runCatching { KubeJson.decodeFromString<RawPod>(result.stdout).toInfo() }.getOrNull()
            _pod.value = parsed
            if (_selectedContainer.value == null) _selectedContainer.value = parsed?.containers?.firstOrNull()
            if (parsed?.isRunning == true && _section.value == ResourceSection.LOGS && logJob?.isActive != true) {
                startLogStream()
            }
        } else {
            _service.value = runCatching { KubeJson.decodeFromString<RawService>(result.stdout).toInfo() }.getOrNull()
        }
    }

    // ------------------------------------------------------------------ logs

    fun selectContainer(container: String) {
        if (_selectedContainer.value == container) return
        _selectedContainer.value = container
        clearLogs()
        startLogStream()
    }

    fun setPrevious(enabled: Boolean) {
        if (_previous.value == enabled) return
        _previous.value = enabled
        clearLogs()
        startLogStream()
    }

    fun setPaused(paused: Boolean) {
        _paused.value = paused
    }

    fun setAutoScroll(enabled: Boolean) {
        _autoScroll.value = enabled
    }

    fun clearLogs() {
        scope.launch {
            bufferLock.withLock {
                buffer.clear()
                _logs.value = emptyList()
                dirty = false
            }
        }
    }

    fun logText(): String = _logs.value.joinToString("\n")

    /** `kubectl logs -f` for a pod, or for a workload's newest pod via `kind/name`. */
    private fun startLogStream() {
        logJob?.cancel()
        logJob = scope.launch {
            val ref = if (kind.lowercase().trimEnd('s') == "pod") name else "${kind.lowercase()}/$name"
            val args = buildList {
                add("logs")
                add(ref)
                add("-f")
                add("--tail=$TAIL_LINES")
                _selectedContainer.value?.takeIf { _pod.value?.needsContainerChoice == true }
                    ?.let { addAll(listOf("-c", it)) }
                if (_previous.value) add("--previous")
            }
            KubectlCli.stream(services.engine.args(args)) { line -> append(stripAnsi(line)) }
            // Stream ended: pod died, log rotated, or the cluster went away. The
            // object poll above restarts it if the pod comes back.
        }
    }

    private suspend fun append(line: String) {
        bufferLock.withLock {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            dirty = true
        }
    }

    // ------------------------------------------------------------- port-forward

    /** Ports this tab could forward: a service's ports, or 80/8080 guesses for a pod. */
    fun forwardablePorts(): List<Int> = when {
        _service.value != null -> _service.value!!.ports.filter { it.protocol.equals("TCP", true) }.map { it.port }
        else -> emptyList()
    }

    fun ref(): String = "${kind.lowercase().trimEnd('s')}/$name"

    fun startForward(remotePort: Int): Int = services.forwards.start(ref(), remotePort)

    fun stopForward(remotePort: Int) {
        val target = services.engine.target.value
        services.forwards.stop(ForwardKey(target.context, target.namespace, ref(), remotePort))
    }

    fun forwardFor(remotePort: Int): ForwardInfo? = services.forwards.forwardFor(ref(), remotePort)

    private fun currentForwardKeys(): List<ForwardKey> {
        val myRef = ref()
        return services.forwards.forwards.value.keys.filter { it.ref == myRef }
    }

    // ------------------------------------------------------- describe / yaml

    fun refreshDescribe() {
        scope.launch {
            val result = services.actions.describe(kind, name)
            _describe.value = if (result.ok) result.stdout else result.cleanError
        }
    }

    fun refreshYaml() {
        scope.launch {
            val result = services.actions.yaml(kind, name)
            _yaml.value = if (result.ok) result.stdout else result.cleanError
        }
    }

    fun refreshEvents() {
        scope.launch {
            _events.value = services.engine.events(objectName = name)
        }
    }

    // --------------------------------------------------------------- actions

    fun restartWorkload() {
        val workload = WorkloadInfo(kind, name, tabInfo.namespace, 0, 0, emptyList(), "")
        scope.launch {
            _status.value = "Restarting…"
            val result = services.actions.rolloutRestart(workload)
            _status.value = if (result.ok) null else result.cleanError.take(200)
            if (result.ok) {
                services.toastSuccess("Restarted $kind/$name in ${services.actions.describeTarget()}")
            } else {
                services.toastError(result.cleanError.take(200))
            }
        }
    }

    fun openExec() {
        val pod = _pod.value ?: return
        services.actions.exec(pod, _selectedContainer.value)
    }

    private companion object {
        const val MAX_LINES = 5_000
        const val TAIL_LINES = 500
        const val FLUSH_MS = 120L
        const val OBJECT_POLL_MS = 5_000L
    }
}

/**
 * Strip ANSI escape sequences. Container logs are full of colour codes and the log
 * view renders plain text, so leaving them in shows literal escape garbage.
 */
internal fun stripAnsi(line: String): String {
    if (line.indexOf(ESC) < 0) return line
    return line.replace(OSC_REGEX, "").replace(CSI_REGEX, "")
}

private const val ESC = '\u001B'

/** CSI: ESC `[` ... final byte — colour and cursor codes. */
private val CSI_REGEX = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

/** OSC: ESC `]` ... terminated by BEL or ESC `\` — title and hyperlink codes. */
private val OSC_REGEX = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
