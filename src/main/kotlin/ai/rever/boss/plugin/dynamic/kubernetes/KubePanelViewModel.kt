package ai.rever.boss.plugin.dynamic.kubernetes

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
    }

    fun refresh() {
        engine.rescanProject()
        engine.requestRefresh()
        scope.launch { engine.refreshContexts(); engine.refreshNamespaces() }
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
}
