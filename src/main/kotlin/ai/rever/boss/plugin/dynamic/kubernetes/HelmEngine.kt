package ai.rever.boss.plugin.dynamic.kubernetes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Helm state for the context + namespace the [KubeEngine] is pointed at.
 *
 * Deliberately a companion to `KubeEngine` rather than a parallel engine: the
 * target selection, cluster reachability and refresh cadence all belong to the
 * Kubernetes side, and a release is just another thing living in that namespace.
 *
 * Helm has no watch API, so releases refresh on the Kubernetes engine's existing
 * reconcile trigger and immediately after any Helm mutation — no extra polling.
 */
class HelmEngine(
    private val scope: CoroutineScope,
    private val kube: KubeEngine,
) {
    private val _helm = MutableStateFlow<HelmState>(HelmState.Unknown)
    val helm: StateFlow<HelmState> = _helm.asStateFlow()

    private val _releases = MutableStateFlow<List<ReleaseInfo>>(emptyList())
    val releases: StateFlow<List<ReleaseInfo>> = _releases.asStateFlow()

    private val _repos = MutableStateFlow<List<RepoInfo>>(emptyList())
    val repos: StateFlow<List<RepoInfo>> = _repos.asStateFlow()

    private val _charts = MutableStateFlow<List<ChartArtifact>>(emptyList())
    val charts: StateFlow<List<ChartArtifact>> = _charts.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var scanner: Job? = null

    /** True once helm is known to be usable. */
    val isReady: Boolean get() = _helm.value is HelmState.Ready

    suspend fun probe(): HelmState {
        val state = HelmCli.probe()
        _helm.value = state
        return state
    }

    fun start() {
        scope.launch {
            probe()
            if (isReady) {
                refreshRepos()
                refreshReleases()
            }
        }
        rescanCharts()
    }

    fun dispose() {
        scanner?.cancel()
        scanner = null
    }

    // -------------------------------------------------------------- releases

    suspend fun refreshReleases() {
        if (!isReady) {
            if (probe() !is HelmState.Ready) return
        }
        if (kube.cluster.value !is ClusterState.Ready) {
            _releases.value = emptyList()
            return
        }
        _busy.value = true
        try {
            val result = HelmCli.exec(
                listOf("list", "-o", "json") + HelmCli.targetArgs(kube.target.value) + LIST_STATE_FLAGS,
            )
            if (!result.ok) {
                if (result.unreachable) _releases.value = emptyList()
                return
            }
            _releases.value = parseJsonArray<RawRelease>(result.stdout)
                .map { it.toInfo() }
                .sortedWith(compareByDescending<ReleaseInfo> { it.isFailed }.thenBy { it.name })
        } finally {
            _busy.value = false
        }
    }

    suspend fun history(release: String): List<RevisionInfo> {
        if (!isReady) return emptyList()
        val result = HelmCli.exec(
            listOf("history", release, "-o", "json") + HelmCli.targetArgs(kube.target.value),
        )
        if (!result.ok) return emptyList()
        return parseJsonArray<RawRevision>(result.stdout).map { it.toInfo() }.sortedByDescending { it.revision }
    }

    suspend fun status(release: String): Pair<ReleaseStatus?, String> {
        if (!isReady) return null to "helm is not installed"
        val result = HelmCli.exec(
            listOf("status", release, "-o", "json") + HelmCli.targetArgs(kube.target.value),
        )
        if (!result.ok) return null to result.cleanError
        val parsed = runCatching { KubeJson.decodeFromString<RawStatus>(result.stdout).toInfo() }.getOrNull()
        return parsed to ""
    }

    /**
     * `helm get values`. With [all] it returns the fully merged (computed) values.
     *
     * Values are the user's own input, not a Kubernetes Secret, so they are shown
     * as-is — a chart value *can* hold a credential, and hiding what you configured
     * would break the view's purpose. Only rendered `kind: Secret` objects are
     * redacted (see [redactRenderedYaml]); this distinction is documented for users.
     */
    suspend fun values(release: String, all: Boolean): String {
        val result = HelmCli.exec(
            buildList {
                addAll(listOf("get", "values", release))
                if (all) add("--all")
                addAll(HelmCli.targetArgs(kube.target.value))
            },
        )
        if (!result.ok) return result.cleanError
        // helm prints the literal `null` when nothing was overridden.
        return result.stdout.trim().let { if (it == "null" || it.isBlank()) "(no user-supplied values)" else it }
    }

    /** Rendered manifest of a release, with Secret payloads redacted. */
    suspend fun manifest(release: String): String {
        val result = HelmCli.exec(
            listOf("get", "manifest", release) + HelmCli.targetArgs(kube.target.value),
            timeoutMs = HelmCli.LONG_TIMEOUT_MS,
        )
        if (!result.ok) return result.cleanError
        return redactRenderedYaml(result.stdout)
    }

    suspend fun notes(release: String): String {
        val result = HelmCli.exec(
            listOf("get", "notes", release) + HelmCli.targetArgs(kube.target.value),
        )
        return if (result.ok) result.stdout.trim().ifBlank { "(this chart ships no NOTES.txt)" } else result.cleanError
    }

    fun findRelease(name: String): ReleaseInfo? = _releases.value.firstOrNull { it.name == name }

    // ----------------------------------------------------------------- repos

    suspend fun refreshRepos() {
        if (!isReady) return
        val result = HelmCli.exec(listOf("repo", "list", "-o", "json"))
        // No repos configured is an error exit in helm, not an empty list.
        _repos.value = if (result.ok) parseJsonArray<RepoInfo>(result.stdout) else emptyList()
    }

    suspend fun search(query: String): List<SearchHit> {
        if (!isReady) return emptyList()
        val result = HelmCli.exec(listOf("search", "repo", query, "-o", "json"), timeoutMs = 20_000)
        if (!result.ok) return emptyList()
        return parseJsonArray<RawSearchHit>(result.stdout).map { it.toInfo() }
    }

    // ---------------------------------------------------------------- charts

    /** Re-scan the open project for `Chart.yaml` files. */
    fun rescanCharts() {
        scanner?.cancel()
        scanner = scope.launch {
            val root = kube.projectRoot()
            if (root == null) {
                _charts.value = emptyList()
                return@launch
            }
            _charts.value = scanCharts(root)
        }
    }

    private fun scanCharts(root: File): List<ChartArtifact> = buildList {
        root.walkTopDown()
            .maxDepth(SCAN_DEPTH)
            .onEnter { dir -> (dir.name !in SKIP_DIRS && !dir.name.startsWith(".")) || dir == root }
            .filter { it.isFile && (it.name == "Chart.yaml" || it.name == "Chart.yml") }
            .forEach { file ->
                add(toArtifact(file, root))
                if (size >= MAX_CHARTS) return@buildList
            }
    }.sortedBy { it.relativePath }

    private fun toArtifact(chartFile: File, root: File): ChartArtifact {
        // Read only the few scalars we display; a Chart.yaml can carry dependencies
        // and maintainers we have no use for.
        val head = runCatching {
            chartFile.bufferedReader().useLines { lines -> lines.take(HEAD_LINES).toList() }
        }.getOrDefault(emptyList())
        fun scalar(key: String): String = head
            .firstOrNull { it.trimStart().startsWith("$key:") }
            ?.substringAfter(':')?.trim()?.trim('"', '\'')
            .orEmpty()

        val dir = chartFile.parentFile
        val values = dir?.listFiles()
            ?.filter { it.isFile && it.name.startsWith("values") && (it.extension == "yaml" || it.extension == "yml") }
            ?.sortedBy { it.name }
            .orEmpty()

        return ChartArtifact(
            chartFile = chartFile,
            relativePath = chartFile.relativeToOrNull(root)?.path ?: chartFile.name,
            name = scalar("name").ifBlank { dir?.name.orEmpty() },
            version = scalar("version"),
            appVersion = scalar("appVersion"),
            valuesFiles = values,
        )
    }

    private companion object {
        /**
         * Show broken releases, not just healthy ones — a release you can't see is
         * one you can't fix.
         *
         * `helm list --all` was **removed in Helm 4** ("Error: unknown flag: --all",
         * verified on 4.2.3) in favour of per-state flags. Those same flags also
         * exist in Helm 3, so listing them explicitly works on both majors and needs
         * no version dialect. `--superseded` is left out deliberately: it returns
         * every historical revision and would bury the current ones.
         */
        val LIST_STATE_FLAGS = listOf("--deployed", "--failed", "--pending", "--uninstalling")

        const val SCAN_DEPTH = 6
        const val MAX_CHARTS = 40
        const val HEAD_LINES = 25

        val SKIP_DIRS = setOf(
            "node_modules", "build", "dist", "out", "target", "vendor",
            "venv", "__pycache__", "Pods", "DerivedData", "tmp", "charts",
        )
    }
}

/**
 * Decode a helm `-o json` payload, which is a **bare JSON array** (unlike
 * kubectl's `{"items":[…]}`).
 */
internal inline fun <reified T> parseJsonArray(stdout: String): List<T> {
    val text = stdout.trim()
    if (!text.startsWith("[")) return emptyList()
    return runCatching { KubeJson.decodeFromString<List<T>>(text) }.getOrDefault(emptyList())
}
