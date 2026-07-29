package ai.rever.boss.plugin.dynamic.kubernetes

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A destructive or state-changing action awaiting confirmation. */
data class ConfirmRequest(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit,
)

/** A pending Helm install the user is filling in. */
data class InstallRequest(
    val chart: ChartArtifact,
    val releaseName: String,
    val valuesFile: String?,
) {
    val isValid: Boolean get() = releaseName.isNotBlank() && RELEASE_NAME.matches(releaseName)

    private companion object {
        /** Helm release names are DNS-1123 labels. */
        val RELEASE_NAME = Regex("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")
    }
}

/** A pending chart repository addition. */
data class RepoAddRequest(val name: String, val url: String) {
    val isValid: Boolean get() = name.isNotBlank() && (url.startsWith("http") || url.startsWith("oci://"))
}

/**
 * Read-only text to show in a dialog — lint results, and rendered output that must
 * not be piped to a terminal because it can contain Secret payloads.
 */
data class OutputRequest(val title: String, val body: String, val redacted: Boolean)

/** A pending scale, with the replica count the user is editing. */
data class ScaleRequest(
    val workload: WorkloadInfo,
    val replicas: String,
) {
    val value: Int? get() = replicas.toIntOrNull()?.takeIf { it in 0..MAX_REPLICAS }
    val isValid: Boolean get() = value != null

    private companion object {
        const val MAX_REPLICAS = 500
    }
}

class KubePanelViewModel(private val services: KubeServices) {

    private val engine = services.engine
    private val scope = services.scope

    val cluster: StateFlow<ClusterState> = engine.cluster
    val target: StateFlow<KubeTarget> = engine.target
    val contexts: StateFlow<List<ContextInfo>> = engine.contexts
    val namespaces: StateFlow<List<NamespaceInfo>> = engine.namespaces
    val workloads: StateFlow<List<WorkloadInfo>> = engine.workloads
    val pods: StateFlow<List<PodInfo>> = engine.pods
    val services_: StateFlow<List<ServiceInfo>> = engine.services
    val ingresses: StateFlow<List<IngressInfo>> = engine.ingresses
    val jobs: StateFlow<List<JobInfo>> = engine.jobs
    val configMaps: StateFlow<List<ConfigMapInfo>> = engine.configMaps
    val secrets: StateFlow<List<SecretInfo>> = engine.secrets
    val pvcs: StateFlow<List<PvcInfo>> = engine.pvcs
    val manifests: StateFlow<List<ManifestArtifact>> = engine.manifests
    val apiResources: StateFlow<List<ApiResourceInfo>> = engine.apiResources
    val pinnedCustom: StateFlow<List<String>> = engine.pinnedCustom
    val customRows: StateFlow<Map<String, List<String>>> = engine.customRows
    val busy: StateFlow<Boolean> = engine.busy
    val forwards: StateFlow<Map<ForwardKey, ForwardInfo>> = services.forwards.forwards

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _expanded = MutableStateFlow(setOf(KubeSection.WORKLOADS, KubeSection.PODS))
    val expanded: StateFlow<Set<KubeSection>> = _expanded.asStateFlow()

    private val _confirm = MutableStateFlow<ConfirmRequest?>(null)
    val confirm: StateFlow<ConfirmRequest?> = _confirm.asStateFlow()

    private val _scale = MutableStateFlow<ScaleRequest?>(null)
    val scale: StateFlow<ScaleRequest?> = _scale.asStateFlow()

    private val _crdPickerOpen = MutableStateFlow(false)
    val crdPickerOpen: StateFlow<Boolean> = _crdPickerOpen.asStateFlow()

    // ------------------------------------------------------------------ helm

    val helmState: StateFlow<HelmState> = services.helm.helm
    val releases: StateFlow<List<ReleaseInfo>> = services.helm.releases
    val repos: StateFlow<List<RepoInfo>> = services.helm.repos
    val charts: StateFlow<List<ChartArtifact>> = services.helm.charts

    private val _install = MutableStateFlow<InstallRequest?>(null)
    val install: StateFlow<InstallRequest?> = _install.asStateFlow()

    private val _repoAdd = MutableStateFlow<RepoAddRequest?>(null)
    val repoAdd: StateFlow<RepoAddRequest?> = _repoAdd.asStateFlow()

    private val _output = MutableStateFlow<OutputRequest?>(null)
    val output: StateFlow<OutputRequest?> = _output.asStateFlow()

    init {
        // Start the two default-open sections watching immediately.
        _expanded.value.forEach { engine.setSectionActive(it, true) }
    }

    // ------------------------------------------------------------------ view

    fun setQuery(value: String) {
        _query.value = value
    }

    fun toggleSection(section: KubeSection) {
        val nowExpanded = section !in _expanded.value
        _expanded.update { if (nowExpanded) it + section else it - section }
        engine.setSectionActive(section, nowExpanded)
        // Helm has no watch API, so expanding a Helm section is the cue to fetch.
        if (nowExpanded) {
            when (section) {
                KubeSection.RELEASES -> scope.launch { services.helm.refreshReleases() }
                KubeSection.REPOS -> scope.launch { services.helm.refreshRepos() }
                KubeSection.PROJECT -> services.helm.rescanCharts()
                else -> Unit
            }
        }
    }

    fun refresh() {
        engine.rescanProject()
        engine.requestRefresh()
        services.helm.rescanCharts()
        scope.launch {
            engine.refreshContexts()
            engine.refreshNamespaces()
            services.helm.refreshReleases()
            services.helm.refreshRepos()
        }
    }

    // ------------------------------------------------------- installing tools

    /** True when a terminal install can be offered; otherwise we link to docs. */
    val canInstallTools: Boolean get() = ToolInstaller.canInstallWithBrew()

    /**
     * Offer to install [tool] in the plugin's terminal tab.
     *
     * Nothing is installed without an explicit confirmation naming the exact
     * command, and where there is no safe suggestion the docs open instead of a
     * guessed package manager.
     */
    fun installTool(tool: InstallableTool) {
        val command = ToolInstaller.installCommand(tool)
        if (command == null) {
            services.openUrl(tool.docsUrl, "Install ${tool.label}")
            return
        }
        askConfirm(
            title = "Install ${tool.label}?",
            message = "Runs `$command` in its own terminal tab, where you can watch it and stop " +
                "it. Nothing else on your system is touched.",
            confirmLabel = "Install",
        ) {
            services.actions.openTerminal(
                id = "install-${tool.binary}-${System.currentTimeMillis()}",
                title = "Install ${tool.label}",
                command = command,
                workingDir = services.context.projectPath,
                // Its own tab, not the shared one. A package install must not be
                // interrupted and typed over by the next kubectl or helm command: a
                // half-applied `brew install` is exactly what the confirmation above
                // promises will not happen, and brew can prompt (for sudo, among other
                // things) — a command typed at a password prompt is a worse outcome
                // than a lost build.
                kind = TerminalCommandKind.Interactive,
                // Fired on delivery, not acceptance: watchForTool polls for two minutes
                // and openTerminal now returns as soon as the command is queued, so
                // timing from its return starts the clock before the install runs.
                onDelivered = {
                    services.toastInfo("Installing ${tool.label}…")
                    watchForTool(tool)
                },
            )
        }
    }

    /**
     * Poll for the binary appearing after an install, then re-probe so the panel
     * switches out of its not-installed state on its own.
     */
    private fun watchForTool(tool: InstallableTool) {
        scope.launch {
            repeat(INSTALL_POLL_ATTEMPTS) {
                delay(INSTALL_POLL_INTERVAL_MS)
                if (!tool.isPresent()) return@repeat
                when (tool) {
                    InstallableTool.KUBECTL -> {
                        engine.probeCluster()
                        engine.refreshContexts()
                    }
                    InstallableTool.HELM -> {
                        services.helm.probe()
                        services.helm.refreshRepos()
                        services.helm.refreshReleases()
                    }
                }
                services.toastSuccess("${tool.label} is available now")
                return@launch
            }
        }
    }

    // ------------------------------------------------------------ helm: charts

    fun beginInstall(chart: ChartArtifact) {
        _install.value = InstallRequest(
            chart = chart,
            releaseName = chart.suggestedRelease,
            valuesFile = chart.valuesFiles.firstOrNull()?.name,
        )
    }

    fun updateInstall(releaseName: String? = null, valuesFile: String? = null) {
        _install.update { current ->
            current?.copy(
                releaseName = releaseName ?: current.releaseName,
                valuesFile = valuesFile ?: current.valuesFile,
            )
        }
    }

    fun cancelInstall() {
        _install.value = null
    }

    fun confirmInstall() {
        val request = _install.value ?: return
        if (!request.isValid) return
        _install.value = null
        askConfirm(
            title = "Install ${request.releaseName}?",
            message = "Installs ${request.chart.name} into ${services.helmActions.describeTarget()}" +
                (request.valuesFile?.let { " using $it" } ?: "") + ".",
            confirmLabel = "Install",
        ) {
            val ok = services.helmActions.install(
                chart = request.chart,
                releaseName = request.releaseName,
                valuesFile = request.chart.valuesFileNamed(request.valuesFile),
            )
            if (ok) services.toastInfo("Installing ${request.releaseName}…")
        }
    }

    /** Dry run stays in-plugin: its output is a rendered manifest. */
    fun dryRunInstall() {
        val request = _install.value ?: return
        _install.value = null
        scope.launch {
            val (ok, body) = services.helmActions.installDryRun(
                chart = request.chart,
                releaseName = request.releaseName,
                valuesFile = request.chart.valuesFileNamed(request.valuesFile),
            )
            _output.value = OutputRequest("Dry run: ${request.releaseName}", body, redacted = ok)
        }
    }

    fun lint(chart: ChartArtifact, valuesFile: String? = null) {
        scope.launch {
            val result = services.helmActions.lint(chart, chart.valuesFileNamed(valuesFile))
            _output.value = OutputRequest(
                title = "Lint: ${chart.name}",
                body = result.stdout.ifBlank { result.cleanError },
                redacted = false,
            )
        }
    }

    fun template(chart: ChartArtifact, valuesFile: String? = null) {
        scope.launch {
            val (ok, body) = services.helmActions.template(
                chart,
                chart.suggestedRelease,
                chart.valuesFileNamed(valuesFile),
            )
            _output.value = OutputRequest("Template: ${chart.name}", body, redacted = ok)
        }
    }

    fun dependencyUpdate(chart: ChartArtifact) {
        if (services.helmActions.dependencyUpdate(chart)) {
            services.toastInfo("Updating dependencies for ${chart.name}…")
        }
    }

    fun packageChart(chart: ChartArtifact) {
        val dest = chart.directory.parent ?: chart.directory.absolutePath
        if (services.helmActions.packageChart(chart, dest)) {
            services.toastInfo("Packaging ${chart.name} into $dest…")
        }
    }

    fun dismissOutput() {
        _output.value = null
    }

    fun outputText(): String = _output.value?.body.orEmpty()

    // ---------------------------------------------------------- helm: releases

    fun openRelease(release: ReleaseInfo) = services.openReleaseTab(release.name)

    fun upgradeRelease(release: ReleaseInfo) {
        // Upgrading needs a chart; use the project chart whose name matches, else
        // ask the user to drive it from the chart row instead of guessing.
        val chart = charts.value.firstOrNull { it.name == release.chartName }
        if (chart == null) {
            services.toastError("No project chart named '${release.chartName}' — upgrade from the chart row.")
            return
        }
        askConfirm(
            title = "Upgrade ${release.name}?",
            message = "Upgrades ${release.name} in ${services.helmActions.describeTarget()} from ${chart.relativePath}.",
            confirmLabel = "Upgrade",
        ) {
            if (services.helmActions.upgrade(
                    chart = chart,
                    releaseName = release.name,
                    valuesFile = chart.valuesFiles.firstOrNull(),
                    rollbackOnFailure = true,
                )
            ) {
                services.toastInfo("Upgrading ${release.name}…")
            }
        }
    }

    fun rollbackRelease(release: ReleaseInfo) {
        val previous = (release.revision - 1).coerceAtLeast(1)
        askConfirm(
            title = "Roll ${release.name} back to $previous?",
            message = "Rolls ${release.name} in ${services.helmActions.describeTarget()} back to revision " +
                "$previous. Open the release tab to pick a different revision.",
            confirmLabel = "Roll back",
        ) {
            if (services.helmActions.rollback(release, previous)) {
                services.toastInfo("Rolling ${release.name} back…")
            }
        }
    }

    fun uninstallRelease(release: ReleaseInfo) {
        askConfirm(
            title = "Uninstall ${release.name}?",
            message = "Deletes every object ${release.name} owns from " +
                "${services.helmActions.describeTarget()}. This cannot be undone.",
            confirmLabel = "Uninstall",
        ) {
            if (services.helmActions.uninstall(release)) {
                services.toastInfo("Uninstalling ${release.name}…")
            }
        }
    }

    fun testRelease(release: ReleaseInfo) {
        if (services.helmActions.test(release)) services.toastInfo("Running tests for ${release.name}…")
    }

    // ------------------------------------------------------------- helm: repos

    fun beginRepoAdd() {
        _repoAdd.value = RepoAddRequest("", "")
    }

    fun updateRepoAdd(name: String? = null, url: String? = null) {
        _repoAdd.update { it?.copy(name = name ?: it.name, url = url ?: it.url) }
    }

    fun cancelRepoAdd() {
        _repoAdd.value = null
    }

    fun confirmRepoAdd() {
        val request = _repoAdd.value ?: return
        if (!request.isValid) return
        _repoAdd.value = null
        askConfirm(
            title = "Add repo ${request.name}?",
            // Unlike context selection, this writes shared config — say so.
            message = "Adds ${request.url} to ~/.config/helm/repositories.yaml. This affects the whole " +
                "machine, including your shells — not just BOSS.",
            confirmLabel = "Add",
        ) {
            if (services.helmActions.repoAdd(request.name, request.url)) {
                services.toastInfo("Adding repo ${request.name}…")
            }
        }
    }

    fun updateRepos() {
        if (services.helmActions.repoUpdate()) services.toastInfo("Updating chart repositories…")
    }

    fun removeRepo(repo: RepoInfo) {
        askConfirm(
            title = "Remove repo ${repo.name}?",
            message = "Removes ${repo.name} from ~/.config/helm/repositories.yaml — machine-wide, not just BOSS.",
            confirmLabel = "Remove",
        ) {
            scope.launch {
                val result = services.helmActions.repoRemove(repo.name)
                services.helm.refreshRepos()
                if (result.ok) {
                    services.toastSuccess("Removed repo ${repo.name}")
                } else {
                    services.toastError(result.cleanError.take(200))
                }
            }
        }
    }

    fun matches(text: String): Boolean {
        val q = _query.value.trim()
        return q.isEmpty() || text.contains(q, ignoreCase = true)
    }

    // -------------------------------------------------------------- selection

    fun selectContext(name: String) {
        engine.selectContext(name)
        services.rememberTarget()
    }

    fun selectNamespace(name: String) {
        engine.selectNamespace(name)
        services.rememberTarget()
    }

    fun openCrdPicker() {
        _crdPickerOpen.value = true
    }

    fun closeCrdPicker() {
        _crdPickerOpen.value = false
    }

    fun pinCustom(resource: String) {
        engine.pinCustomResource(resource)
        services.rememberPinned()
        _crdPickerOpen.value = false
        if (KubeSection.CUSTOM !in _expanded.value) toggleSection(KubeSection.CUSTOM)
    }

    fun unpinCustom(resource: String) {
        engine.unpinCustomResource(resource)
        services.rememberPinned()
    }

    // --------------------------------------------------------------- manifests

    fun apply(artifact: ManifestArtifact) {
        askConfirm(
            title = "Apply ${artifact.file.name}?",
            message = "This applies ${artifact.relativePath} to ${services.actions.describeTarget()}.",
            confirmLabel = "Apply",
        ) {
            if (services.actions.apply(artifact)) {
                services.toastInfo("Applying ${artifact.file.name} to ${services.actions.describeTarget()}")
            }
        }
    }

    /** Server-side dry run — shows what would change without changing anything. */
    fun applyDryRun(artifact: ManifestArtifact) {
        if (services.actions.apply(artifact, dryRun = true)) {
            services.toastInfo("Dry-run apply of ${artifact.file.name}")
        }
    }

    fun diff(artifact: ManifestArtifact) {
        services.actions.diff(artifact)
    }

    // ---------------------------------------------------------------- actions

    fun openResource(kind: String, name: String) = services.openResourceTab(kind, name)

    fun beginScale(workload: WorkloadInfo) {
        _scale.value = ScaleRequest(workload, workload.desired.toString())
    }

    fun updateScale(replicas: String) {
        _scale.update { it?.copy(replicas = replicas) }
    }

    fun cancelScale() {
        _scale.value = null
    }

    fun confirmScale() {
        val request = _scale.value ?: return
        val replicas = request.value ?: return
        _scale.value = null
        run("Scaled ${request.workload.ref} to $replicas") {
            services.actions.scale(request.workload, replicas)
        }
    }

    fun rolloutRestart(workload: WorkloadInfo) {
        askConfirm(
            title = "Restart ${workload.ref}?",
            message = "Rolls every pod of ${workload.ref} in ${services.actions.describeTarget()}.",
            confirmLabel = "Restart",
        ) {
            run("Restarted ${workload.ref}") { services.actions.rolloutRestart(workload) }
        }
    }

    fun delete(kind: String, name: String) {
        askConfirm(
            title = "Delete $kind/$name?",
            // The context and namespace are spelled out because this is the one
            // place a wrong selection becomes an incident.
            message = "Deletes $kind/$name from ${services.actions.describeTarget()}. This cannot be undone.",
            confirmLabel = "Delete",
        ) {
            run("Deleted $kind/$name") { services.actions.delete(kind, name) }
        }
    }

    fun exec(pod: PodInfo) {
        services.actions.exec(pod, pod.containers.firstOrNull())
    }

    // ----------------------------------------------------------- port-forward

    fun toggleForward(service: ServiceInfo) {
        val port = service.primaryPort ?: run {
            services.toastError("${service.name} publishes no TCP port to forward")
            return
        }
        val existing = services.forwards.forwardFor(service.ref, port.port)
        if (existing != null) {
            services.forwards.stop(existing.key)
            services.toastInfo("Stopped forwarding ${service.name}")
        } else {
            val local = services.forwards.start(service.ref, port.port)
            services.toastSuccess("Forwarding ${service.name} on http://localhost:$local")
        }
    }

    fun openForwardUrl(service: ServiceInfo) {
        val port = service.primaryPort ?: return
        val forward = services.forwards.forwardFor(service.ref, port.port) ?: return
        services.openUrl(forward.localUrl, service.name)
    }

    fun forwardFor(service: ServiceInfo): ForwardInfo? =
        service.primaryPort?.let { services.forwards.forwardFor(service.ref, it.port) }

    // --------------------------------------------------------------- confirm

    /**
     * Every mutation routes through here. The dialog is rendered by the panel
     * rather than the host's `genericDialogProvider`, which is nullable — "the host
     * gave us no dialog" must never become "we deleted it without asking".
     */
    private fun askConfirm(title: String, message: String, confirmLabel: String, action: () -> Unit) {
        _confirm.value = ConfirmRequest(title, message, confirmLabel, action)
    }

    fun dismissConfirm() {
        _confirm.value = null
    }

    fun acceptConfirm() {
        val request = _confirm.value ?: return
        _confirm.value = null
        request.onConfirm()
    }

    private fun run(successMessage: String, block: suspend () -> KubectlExec) {
        scope.launch {
            val result = block()
            if (result.ok) {
                services.toastSuccess("$successMessage in ${services.actions.describeTarget()}")
            } else {
                services.toastError(result.cleanError.take(200))
            }
        }
    }

    private companion object {
        /** ~2 minutes total: a brew install of kubectl or helm is well under that. */
        const val INSTALL_POLL_ATTEMPTS = 40
        const val INSTALL_POLL_INTERVAL_MS = 3_000L
    }
}
