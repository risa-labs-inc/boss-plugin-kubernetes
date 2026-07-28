package ai.rever.boss.plugin.dynamic.kubernetes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Trim a go-template column, mapping Go's `<no value>` placeholder for an absent
 * field to an empty string. Without this the sentinel gets treated as real data
 * and ends up as a namespace name that matches nothing.
 */
internal fun String?.cleanTemplateValue(): String {
    val trimmed = this?.trim().orEmpty()
    return if (trimmed == "<no value>" || trimmed == "<nil>") "" else trimmed
}

/** The resource groups the sidebar can show, each backed by its own watch. */
enum class KubeSection(val label: String, val resource: String) {
    PROJECT("Project", ""),
    WORKLOADS("Workloads", "deployments,statefulsets,daemonsets"),
    PODS("Pods", "pods"),
    SERVICES("Services", "services"),
    INGRESSES("Ingresses", "ingresses"),
    JOBS("Jobs", "jobs,cronjobs"),
    CONFIGMAPS("ConfigMaps", "configmaps"),
    SECRETS("Secrets", "secrets"),
    PVCS("Volume claims", "persistentvolumeclaims"),
    CUSTOM("Custom resources", ""),

    /**
     * Helm sections. They carry no kubectl resource because Helm has no watch API —
     * releases refresh on the reconcile tick and after each Helm command instead.
     */
    RELEASES("Helm releases", ""),
    REPOS("Chart repos", ""),
    ;

    /** Sections with a blank resource are handled specially, not by `get <resource>`. */
    val isPlainResource: Boolean get() = resource.isNotBlank()
}

/**
 * Owns all cluster state for one selected context + namespace.
 *
 * **The plugin never writes to the kubeconfig.** `kubectl config use-context` and
 * `set-context --namespace` mutate global state shared by every other terminal on
 * the machine — picking a context in a sidebar must not silently retarget the
 * user's shell. The selection lives here and is passed as `--context` / `-n` on
 * every single invocation.
 *
 * Freshness mirrors the Docker plugin: a watch stream is the change *notification*
 * and a full `get -o json` is the state. Watches run only for expanded sections,
 * because ten permanent subprocesses per window is not acceptable.
 */
class KubeEngine(
    private val scope: CoroutineScope,
    private val getProjectPath: () -> String?,
) {
    private val _cluster = MutableStateFlow<ClusterState>(ClusterState.Unknown)
    val cluster: StateFlow<ClusterState> = _cluster.asStateFlow()

    private val _target = MutableStateFlow(KubeTarget.DEFAULT)
    val target: StateFlow<KubeTarget> = _target.asStateFlow()

    private val _contexts = MutableStateFlow<List<ContextInfo>>(emptyList())
    val contexts: StateFlow<List<ContextInfo>> = _contexts.asStateFlow()

    private val _namespaces = MutableStateFlow<List<NamespaceInfo>>(emptyList())
    val namespaces: StateFlow<List<NamespaceInfo>> = _namespaces.asStateFlow()

    private val _workloads = MutableStateFlow<List<WorkloadInfo>>(emptyList())
    val workloads: StateFlow<List<WorkloadInfo>> = _workloads.asStateFlow()

    private val _pods = MutableStateFlow<List<PodInfo>>(emptyList())
    val pods: StateFlow<List<PodInfo>> = _pods.asStateFlow()

    private val _services = MutableStateFlow<List<ServiceInfo>>(emptyList())
    val services: StateFlow<List<ServiceInfo>> = _services.asStateFlow()

    private val _ingresses = MutableStateFlow<List<IngressInfo>>(emptyList())
    val ingresses: StateFlow<List<IngressInfo>> = _ingresses.asStateFlow()

    private val _jobs = MutableStateFlow<List<JobInfo>>(emptyList())
    val jobs: StateFlow<List<JobInfo>> = _jobs.asStateFlow()

    private val _configMaps = MutableStateFlow<List<ConfigMapInfo>>(emptyList())
    val configMaps: StateFlow<List<ConfigMapInfo>> = _configMaps.asStateFlow()

    private val _secrets = MutableStateFlow<List<SecretInfo>>(emptyList())
    val secrets: StateFlow<List<SecretInfo>> = _secrets.asStateFlow()

    private val _pvcs = MutableStateFlow<List<PvcInfo>>(emptyList())
    val pvcs: StateFlow<List<PvcInfo>> = _pvcs.asStateFlow()

    private val _apiResources = MutableStateFlow<List<ApiResourceInfo>>(emptyList())
    val apiResources: StateFlow<List<ApiResourceInfo>> = _apiResources.asStateFlow()

    private val _pinnedCustom = MutableStateFlow<List<String>>(emptyList())
    val pinnedCustom: StateFlow<List<String>> = _pinnedCustom.asStateFlow()

    /** Rows for pinned custom resources, keyed by api-resource name. */
    private val _customRows = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val customRows: StateFlow<Map<String, List<String>>> = _customRows.asStateFlow()

    private val _manifests = MutableStateFlow<List<ManifestArtifact>>(emptyList())
    val manifests: StateFlow<List<ManifestArtifact>> = _manifests.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Sections currently expanded; only these are watched and refreshed. */
    private val _active = MutableStateFlow<Set<KubeSection>>(emptySet())

    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val watchJobs = mutableMapOf<KubeSection, Job>()

    private var supervisor: Job? = null
    private var refresher: Job? = null
    private var reconciler: Job? = null
    private var scanner: Job? = null

    fun start() {
        if (supervisor != null) return
        refresher = scope.launch {
            for (ignored in refreshRequests) {
                delay(DEBOUNCE_MS)
                refreshActive()
            }
        }
        reconciler = scope.launch {
            while (isActive) {
                delay(RECONCILE_MS)
                if (_cluster.value is ClusterState.Ready) requestRefresh()
            }
        }
        supervisor = scope.launch { superviseCluster() }
        rescanProject()
    }

    fun dispose() {
        watchJobs.values.forEach { it.cancel() }
        watchJobs.clear()
        supervisor?.cancel()
        refresher?.cancel()
        reconciler?.cancel()
        scanner?.cancel()
        supervisor = null
        refresher = null
        reconciler = null
        scanner = null
        refreshRequests.close()
    }

    fun requestRefresh() {
        refreshRequests.trySend(Unit)
    }

    // ------------------------------------------------------------- selection

    /**
     * Point the panel at a context. Purely local — the kubeconfig is untouched, so
     * the user's other shells keep whatever they had.
     */
    fun selectContext(context: String?) {
        if (_target.value.context == context) return
        _target.value = _target.value.copy(context = context)
        onTargetChanged()
    }

    fun selectNamespace(namespace: String) {
        if (_target.value.namespace == namespace) return
        _target.value = _target.value.copy(namespace = namespace)
        onTargetChanged()
    }

    private fun onTargetChanged() {
        clearResources()
        // Watches are bound to a context+namespace; restart every live one.
        val sections = watchJobs.keys.toList()
        sections.forEach { stopWatch(it) }
        scope.launch {
            probeCluster()
            refreshNamespaces()
            refreshActive()
            sections.forEach { startWatch(it) }
        }
    }

    fun setSectionActive(section: KubeSection, active: Boolean) {
        _active.update { if (active) it + section else it - section }
        if (active) {
            startWatch(section)
            scope.launch { refreshSection(section) }
        } else {
            stopWatch(section)
        }
    }

    fun pinCustomResource(resource: String) {
        _pinnedCustom.update { if (resource in it) it else it + resource }
        scope.launch { refreshSection(KubeSection.CUSTOM) }
    }

    fun unpinCustomResource(resource: String) {
        _pinnedCustom.update { it - resource }
        _customRows.update { it - resource }
    }

    fun restorePinned(resources: List<String>) {
        _pinnedCustom.value = resources
    }

    // ------------------------------------------------------------ supervision

    private suspend fun superviseCluster() {
        var backoffMs = MIN_BACKOFF_MS
        while (scope.isActive) {
            refreshContexts()
            when (probeCluster()) {
                is ClusterState.Ready -> {
                    backoffMs = MIN_BACKOFF_MS
                    refreshNamespaces()
                    refreshApiResources()
                    refreshActive()
                    delay(READY_POLL_MS)
                }

                is ClusterState.KubectlMissing -> {
                    clearResources()
                    delay(CLI_RECHECK_MS)
                }

                else -> {
                    clearResources()
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    /** Cheap liveness probe; also the source of the server version we display. */
    suspend fun probeCluster(): ClusterState {
        if (!KubectlCli.isInstalled()) {
            _cluster.value = ClusterState.KubectlMissing
            return ClusterState.KubectlMissing
        }
        if (_contexts.value.isEmpty() && _target.value.context == null) {
            refreshContexts()
        }
        val result = KubectlCli.exec(
            args(listOf("version", "-o", "json"), namespaced = false),
            timeoutMs = KubectlCli.PROBE_TIMEOUT_MS,
        )
        val next = when {
            result.ok -> ClusterState.Ready(serverVersionOf(result.stdout))
            result.exitCode == KubectlExec.EXIT_CLI_MISSING -> ClusterState.KubectlMissing
            result.badContext -> ClusterState.NoContext
            result.unreachable || result.exitCode == KubectlExec.EXIT_TIMEOUT ->
                ClusterState.Unreachable(result.cleanError.take(300))
            result.forbidden -> ClusterState.Forbidden(result.cleanError.take(300))
            else -> ClusterState.Error(result.cleanError.take(300))
        }
        _cluster.value = next
        return next
    }

    /**
     * Pull `serverVersion.gitVersion` out of `kubectl version -o json`. Read with
     * a regex rather than a model because the payload also carries `clientVersion`
     * and we only ever want the one field.
     */
    private fun serverVersionOf(stdout: String): String =
        SERVER_VERSION_REGEX.find(stdout)?.groupValues?.getOrNull(1) ?: "unknown"

    /**
     * Watch a section: one compact line per change, which only triggers a
     * debounced full refresh. `--output-watch-events` replays existing objects as
     * ADDED, so the first burst is absorbed by the debounce.
     */
    private fun startWatch(section: KubeSection) {
        if (!section.isPlainResource) return
        if (watchJobs[section]?.isActive == true) return
        watchJobs[section] = scope.launch {
            while (isActive) {
                if (_cluster.value !is ClusterState.Ready) {
                    delay(MIN_BACKOFF_MS)
                    continue
                }
                KubectlCli.stream(
                    args(
                        listOf(
                            "get", section.resource,
                            "--watch", "--output-watch-events",
                            "-o", WATCH_TEMPLATE,
                        ),
                    ),
                ) { line ->
                    if (line.isNotBlank() && !line.startsWith("error")) requestRefresh()
                }
                // Stream ended (cluster went away, token expired, apiserver
                // restarted). Pause, then reattach.
                delay(WATCH_REATTACH_MS)
            }
        }
    }

    private fun stopWatch(section: KubeSection) {
        watchJobs.remove(section)?.cancel()
    }

    // -------------------------------------------------------------- refreshing

    suspend fun refreshActive() {
        if (_cluster.value !is ClusterState.Ready) return
        _busy.value = true
        try {
            _active.value.forEach { refreshSection(it) }
        } finally {
            _busy.value = false
        }
    }

    suspend fun refreshSection(section: KubeSection) {
        if (section == KubeSection.PROJECT) {
            rescanProject()
            return
        }
        if (_cluster.value !is ClusterState.Ready) return
        when (section) {
            KubeSection.WORKLOADS -> refreshWorkloads()
            KubeSection.PODS -> _pods.value = list<RawPod>("pods").map { it.toInfo() }
                .sortedWith(compareByDescending<PodInfo> { it.isFailing }.thenBy { it.name })

            KubeSection.SERVICES -> _services.value = list<RawService>("services").map { it.toInfo() }
                .sortedBy { it.name }

            KubeSection.INGRESSES -> _ingresses.value = list<RawIngress>("ingresses").map { it.toInfo() }
                .sortedBy { it.name }

            KubeSection.JOBS -> {
                val jobs = list<RawJob>("jobs").map { it.toJob() }
                val crons = list<RawJob>("cronjobs").map { it.toCronJob() }
                _jobs.value = (crons + jobs).sortedWith(compareBy({ it.kind }, { it.name }))
            }

            KubeSection.CONFIGMAPS -> _configMaps.value = configMapRows()
            KubeSection.SECRETS -> _secrets.value = secretRows()
            KubeSection.PVCS -> _pvcs.value = list<RawPvc>("persistentvolumeclaims").map { it.toInfo() }
                .sortedBy { it.name }

            KubeSection.CUSTOM -> refreshCustom()
            // Helm sections are owned by HelmEngine — it has its own refresh path,
            // driven by the reconcile tick and by Helm commands completing.
            KubeSection.RELEASES, KubeSection.REPOS, KubeSection.PROJECT -> Unit
        }
    }

    private suspend fun refreshWorkloads() {
        val out = buildList {
            addAll(list<RawWorkload>("deployments").map { it.toInfo("Deployment") })
            addAll(list<RawWorkload>("statefulsets").map { it.toInfo("StatefulSet") })
            addAll(list<RawWorkload>("daemonsets").map { it.toInfo("DaemonSet") })
        }
        _workloads.value = out.sortedWith(compareByDescending<WorkloadInfo> { !it.isHealthy }.thenBy { it.name })
    }

    /**
     * ConfigMaps are listed with custom columns rather than `-o json`: their `data`
     * can be megabytes of embedded files, and the list only needs names.
     */
    private suspend fun configMapRows(): List<ConfigMapInfo> {
        val result = KubectlCli.exec(
            args(
                listOf(
                    "get", "configmaps",
                    "-o", "custom-columns=NAME:.metadata.name,NS:.metadata.namespace,AGE:.metadata.creationTimestamp",
                    "--no-headers",
                ),
            ),
        )
        if (!result.ok) return emptyList()
        return result.stdout.lineSequence().mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 3) return@mapNotNull null
            ConfigMapInfo(name = cols[0], namespace = cols[1], createdAt = cols[2])
        }.sortedBy { it.name }.toList()
    }

    /**
     * Secrets are listed with custom columns naming only metadata and type — the
     * `data` map is never requested and never parsed. This is the structural half
     * of the promise that this plugin cannot leak secret values.
     */
    private suspend fun secretRows(): List<SecretInfo> {
        val result = KubectlCli.exec(
            args(
                listOf(
                    "get", "secrets",
                    "-o", "custom-columns=NAME:.metadata.name,NS:.metadata.namespace,TYPE:.type,AGE:.metadata.creationTimestamp",
                    "--no-headers",
                ),
            ),
        )
        if (!result.ok) return emptyList()
        return result.stdout.lineSequence().mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 4) return@mapNotNull null
            SecretInfo(name = cols[0], namespace = cols[1], type = cols[2], createdAt = cols[3])
        }.sortedBy { it.name }.toList()
    }

    private suspend fun refreshCustom() {
        val rows = mutableMapOf<String, List<String>>()
        for (resource in _pinnedCustom.value) {
            val result = KubectlCli.exec(
                args(listOf("get", resource, "-o", "custom-columns=NAME:.metadata.name", "--no-headers")),
            )
            rows[resource] = if (result.ok) {
                result.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }.take(MAX_CUSTOM_ROWS)
            } else {
                emptyList()
            }
        }
        _customRows.value = rows
    }

    suspend fun refreshContexts() {
        // `{{.context.namespace}}` renders the literal string `<no value>` when a
        // context sets no namespace (docker-desktop doesn't), which would then be
        // passed to `kubectl -n` verbatim and match nothing. Guard it in the
        // template, and again in cleanTemplateValue below.
        val result = KubectlCli.exec(
            listOf(
                "config", "view", "-o",
                "go-template={{range .contexts}}{{.name}}\t{{.context.cluster}}\t" +
                    "{{if .context.namespace}}{{.context.namespace}}{{end}}\n{{end}}",
            ),
            timeoutMs = KubectlCli.PROBE_TIMEOUT_MS,
        )
        val current = KubectlCli.exec(listOf("config", "current-context"), timeoutMs = KubectlCli.PROBE_TIMEOUT_MS)
            .takeIf { it.ok }?.stdout?.trim().orEmpty()

        if (!result.ok) return
        val parsed = result.stdout.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val cols = line.split('\t')
            ContextInfo(
                name = cols.getOrNull(0).cleanTemplateValue(),
                cluster = cols.getOrNull(1).cleanTemplateValue(),
                namespace = cols.getOrNull(2).cleanTemplateValue(),
                isCurrent = cols.getOrNull(0).cleanTemplateValue() == current,
            )
        }.filter { it.name.isNotBlank() }.toList()

        _contexts.value = parsed
        // First run: adopt the kubeconfig's current context and its namespace as
        // the starting selection (read, never written).
        if (_target.value.context == null) {
            val start = parsed.firstOrNull { it.isCurrent } ?: parsed.firstOrNull()
            if (start != null) {
                _target.value = KubeTarget(
                    context = start.name,
                    namespace = start.namespace.ifBlank { "default" },
                )
            }
        }
    }

    suspend fun refreshNamespaces() {
        if (_cluster.value !is ClusterState.Ready) return
        val result = KubectlCli.exec(args(listOf("get", "namespaces", "-o", "json"), namespaced = false))
        if (!result.ok) return
        _namespaces.value = parseItems<RawNamespace>(result.stdout).map { it.toInfo() }.sortedBy { it.name }
    }

    private suspend fun refreshApiResources() {
        if (_apiResources.value.isNotEmpty()) return // static per cluster
        val result = KubectlCli.exec(
            args(listOf("api-resources", "--no-headers", "--verbs=list"), namespaced = false),
            timeoutMs = 20_000,
        )
        if (!result.ok) return
        _apiResources.value = result.stdout.lineSequence().mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 3) return@mapNotNull null
            // NAME [SHORTNAMES] APIVERSION NAMESPACED KIND
            val kind = cols.last()
            val namespaced = cols.getOrNull(cols.size - 2)?.equals("true", ignoreCase = true) ?: false
            val apiVersion = cols.getOrNull(cols.size - 3).orEmpty()
            ApiResourceInfo(
                name = cols[0],
                shortNames = if (cols.size >= 5) cols[1].split(",").filter { it.isNotBlank() } else emptyList(),
                apiVersion = apiVersion,
                namespaced = namespaced,
                kind = kind,
            )
        }.toList()
    }

    /** Namespace-scoped events, newest first — the fastest way to see why a pod won't start. */
    suspend fun events(objectName: String? = null): List<EventInfo> {
        val result = KubectlCli.exec(args(listOf("get", "events", "-o", "json")))
        if (!result.ok) return emptyList()
        val all = parseItems<RawEvent>(result.stdout).map { it.toInfo() }
        val filtered = if (objectName == null) all else all.filter { it.objectRef.endsWith("/$objectName") }
        return filtered.sortedByDescending { it.lastSeen }
    }

    private suspend inline fun <reified T> list(resource: String): List<T> {
        val result = KubectlCli.exec(args(listOf("get", resource, "-o", "json")))
        if (!result.ok) {
            if (result.unreachable) _cluster.value = ClusterState.Unreachable(result.cleanError.take(300))
            return emptyList()
        }
        return parseItems<T>(result.stdout)
    }

    private fun clearResources() {
        _workloads.value = emptyList()
        _pods.value = emptyList()
        _services.value = emptyList()
        _ingresses.value = emptyList()
        _jobs.value = emptyList()
        _configMaps.value = emptyList()
        _secrets.value = emptyList()
        _pvcs.value = emptyList()
        _customRows.value = emptyMap()
    }

    // ------------------------------------------------------- project scanning

    /** The open project's root, or null when there is no usable project path. */
    fun projectRoot(): File? = getProjectPath()?.let(::File)?.takeIf { it.isDirectory }

    fun rescanProject() {
        scanner?.cancel()
        scanner = scope.launch {
            val root = getProjectPath()?.let(::File)
            if (root == null || !root.isDirectory) {
                _manifests.value = emptyList()
                return@launch
            }
            _manifests.value = scanManifests(root)
        }
    }

    private fun scanManifests(root: File): List<ManifestArtifact> = buildList {
        root.walkTopDown()
            .maxDepth(SCAN_DEPTH)
            .onEnter { dir -> (dir.name !in SKIP_DIRS && !dir.name.startsWith(".")) || dir == root }
            .filter { it.isFile }
            .forEach { file ->
                val kind = classify(file) ?: return@forEach
                add(
                    ManifestArtifact(
                        file = file,
                        kind = kind,
                        relativePath = file.relativeToOrNull(root)?.path ?: file.name,
                    ),
                )
                if (size >= MAX_MANIFESTS) return@buildList
            }
    }.sortedWith(compareBy({ it.kind != ManifestArtifact.Kind.KUSTOMIZATION }, { it.relativePath }))

    /**
     * A YAML file counts as a manifest only if it actually looks like one — a repo
     * is full of CI configs and lockfiles that would otherwise flood the section.
     */
    private fun classify(file: File): ManifestArtifact.Kind? {
        val lower = file.name.lowercase()
        if (lower == "kustomization.yaml" || lower == "kustomization.yml") {
            return ManifestArtifact.Kind.KUSTOMIZATION
        }
        if (!lower.endsWith(".yaml") && !lower.endsWith(".yml")) return null
        val inK8sDir = file.parentFile?.name?.lowercase() in K8S_DIR_NAMES
        val head = runCatching {
            file.bufferedReader().useLines { lines -> lines.take(HEAD_LINES).joinToString("\n") }
        }.getOrDefault("")
        val looksLikeManifest = head.contains(Regex("^apiVersion:\\s*\\S", RegexOption.MULTILINE)) &&
            head.contains(Regex("^kind:\\s*\\S", RegexOption.MULTILINE))
        return if (looksLikeManifest || (inK8sDir && head.contains("kind:"))) {
            ManifestArtifact.Kind.MANIFEST
        } else {
            null
        }
    }

    // --------------------------------------------------------------- helpers

    /**
     * Prefix every invocation with the selected context and namespace. This is
     * what keeps the kubeconfig untouched.
     */
    fun args(command: List<String>, namespaced: Boolean = true): List<String> = buildList {
        addAll(command)
        _target.value.context?.let { addAll(listOf("--context", it)) }
        if (namespaced) {
            if (_target.value.isAllNamespaces) add("--all-namespaces") else addAll(listOf("-n", _target.value.namespace))
        }
        if (command.firstOrNull() != "config") add(KubectlCli.requestTimeout())
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val RECONCILE_MS = 30_000L
        const val READY_POLL_MS = 15_000L
        const val MIN_BACKOFF_MS = 3_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val CLI_RECHECK_MS = 60_000L
        const val WATCH_REATTACH_MS = 2_000L
        const val SCAN_DEPTH = 5
        const val MAX_MANIFESTS = 80
        const val MAX_CUSTOM_ROWS = 200
        const val HEAD_LINES = 30

        /** One line per watch event: `<TYPE> <name>`. Only used as a trigger. */
        const val WATCH_TEMPLATE = """go-template={{.type}} {{.object.metadata.name}}{{"\n"}}"""

        /** `serverVersion.gitVersion` inside `kubectl version -o json`. */
        val SERVER_VERSION_REGEX =
            Regex("\"serverVersion\"\\s*:\\s*\\{[^}]*?\"gitVersion\"\\s*:\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)

        val SKIP_DIRS = setOf(
            "node_modules", "build", "dist", "out", "target", "vendor",
            "venv", "__pycache__", "Pods", "DerivedData", "tmp",
        )
        val K8S_DIR_NAMES = setOf("k8s", "kubernetes", "manifests", "deploy", "deployment", "chart", "charts")
    }
}
