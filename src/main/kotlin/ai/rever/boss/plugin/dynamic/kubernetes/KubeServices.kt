package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared brain for the Kubernetes plugin: one instance per activation, handed to
 * the sidebar, every resource tab, and the MCP tools.
 *
 * Every host provider is nullable and each call site degrades rather than crashes.
 */
class KubeServices(val context: PluginContext) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine = KubeEngine(scope) { context.projectPath }
    val forwards = PortForwardManager(scope, engine)
    val actions = KubeActions(this)
    val helm = HelmEngine(scope, engine)
    val helmActions = HelmActions(this)


    private val storage: PluginStorageProvider? by lazy {
        runCatching { context.pluginStorageFactory?.createStorage(PLUGIN_ID) }.getOrNull()
    }

    /**
     * Nudge the Helm release list after a command that a terminal tab owns.
     *
     * Helm has no watch API, so there is nothing to push us an update; a short delay
     * lets the command land before we look, and the periodic reconcile catches
     * anything slower.
     */
    fun scheduleHelmRefresh() {
        scope.launch {
            delay(HELM_SETTLE_MS)
            helm.refreshReleases()
        }
    }

    fun start() {
        engine.start()
        helm.start()
        actions.start()
        scope.launch {
            // Restore the selection and pinned CRDs before the first render, so the
            // panel doesn't flash the wrong namespace.
            // cleanTemplateValue also screens a `<no value>` that an older build may
            // have persisted — restoring it would target a namespace that matches
            // nothing, and the panel would look empty for no visible reason.
            val savedContext = getPref(KEY_CONTEXT, "").cleanTemplateValue()
            val savedNamespace = getPref(KEY_NAMESPACE, "").cleanTemplateValue()
            if (savedContext.isNotBlank()) engine.selectContext(savedContext)
            if (savedNamespace.isNotBlank()) engine.selectNamespace(savedNamespace)
            val pinned = getPref(KEY_PINNED, "").split(',').map { it.trim() }.filter { it.isNotBlank() }
            if (pinned.isNotEmpty()) engine.restorePinned(pinned)
        }
    }

    fun dispose() {
        forwards.dispose()
        actions.dispose()
        helm.dispose()
        engine.dispose()
        scope.cancel()
    }

    /** Open (or focus) the Helm release tab for [release]. */
    suspend fun openReleaseTabVerified(release: String): TabOpenOutcome {
        val target = engine.target.value
        val tabInfo = HelmReleaseTabInfo(
            contextName = target.context.orEmpty(),
            namespace = target.namespace,
            releaseName = release,
        )
        val tabs = context.activeTabsProvider
        tabs?.activeTabs?.value?.firstOrNull { it.tabId == tabInfo.id }?.let { existing ->
            tabs.selectTab(existing.tabId, existing.panelId)
            return TabOpenOutcome.Focused
        }
        val ops = context.splitViewOperations ?: return TabOpenOutcome.NoSplitViewOperations
        ops.openTab(tabInfo)

        if (tabs == null) return TabOpenOutcome.Unverifiable
        repeat(TAB_POLL_ATTEMPTS) {
            delay(TAB_POLL_INTERVAL_MS)
            if (tabs.activeTabs.value.any { it.tabId == tabInfo.id }) return TabOpenOutcome.Opened
        }
        return TabOpenOutcome.Dropped
    }

    fun openReleaseTab(release: String) {
        scope.launch {
            when (openReleaseTabVerified(release)) {
                TabOpenOutcome.Dropped -> toastError("The host dropped the tab — reload the Kubernetes plugin")
                TabOpenOutcome.NoSplitViewOperations -> toastError("This host cannot open tabs")
                else -> Unit
            }
        }
    }

    fun rememberTarget() {
        val target = engine.target.value
        scope.launch {
            setPref(KEY_CONTEXT, target.context.orEmpty())
            setPref(KEY_NAMESPACE, target.namespace)
        }
    }

    fun rememberPinned() {
        scope.launch { setPref(KEY_PINNED, engine.pinnedCustom.value.joinToString(",")) }
    }

    // ------------------------------------------------------------------ tabs

    /**
     * Open (or focus) the resource tab for [kind]/[name].
     *
     * `openTab` is fire-and-forget: the host silently drops a tab whose type has no
     * registered factory, and its warning goes to the structured logger rather than
     * stdout. Anything that reports success to a user must confirm first — this is
     * the same guard the Docker plugin needed.
     */
    suspend fun openResourceTabVerified(kind: String, name: String): TabOpenOutcome {
        val target = engine.target.value
        val tabInfo = KubeResourceTabInfo(
            contextName = target.context.orEmpty(),
            namespace = target.namespace,
            kind = kind,
            resourceName = name,
        )
        val tabs = context.activeTabsProvider
        tabs?.activeTabs?.value?.firstOrNull { it.tabId == tabInfo.id }?.let { existing ->
            tabs.selectTab(existing.tabId, existing.panelId)
            return TabOpenOutcome.Focused
        }
        val ops = context.splitViewOperations ?: return TabOpenOutcome.NoSplitViewOperations
        ops.openTab(tabInfo)

        if (tabs == null) return TabOpenOutcome.Unverifiable
        repeat(TAB_POLL_ATTEMPTS) {
            delay(TAB_POLL_INTERVAL_MS)
            if (tabs.activeTabs.value.any { it.tabId == tabInfo.id }) return TabOpenOutcome.Opened
        }
        return TabOpenOutcome.Dropped
    }

    /** Fire-and-forget variant for UI click handlers. */
    fun openResourceTab(kind: String, name: String) {
        scope.launch {
            when (openResourceTabVerified(kind, name)) {
                TabOpenOutcome.Dropped -> toastError("The host dropped the tab — reload the Kubernetes plugin")
                TabOpenOutcome.NoSplitViewOperations -> toastError("This host cannot open tabs")
                else -> Unit
            }
        }
    }

    fun openUrl(url: String, title: String) {
        val ops = context.splitViewOperations ?: run {
            toastError("Cannot open $url — this host does not expose split view operations")
            return
        }
        ops.openUrlInActivePanel(url, title)
    }

    // ---------------------------------------------------------------- toasts

    fun toastSuccess(message: String) = toast(message, NotificationType.SUCCESS)
    fun toastError(message: String) = toast(message, NotificationType.ERROR)
    fun toastInfo(message: String) = toast(message, NotificationType.INFO)

    private fun toast(message: String, type: NotificationType) {
        context.notificationProvider?.showToast(message, type, title = "Kubernetes")
    }

    // --------------------------------------------------------------- storage

    suspend fun getPref(key: String, default: String): String =
        runCatching { storage?.getString(key, default) }.getOrNull() ?: default

    suspend fun setPref(key: String, value: String) {
        runCatching { storage?.putString(key, value) }
    }

    companion object {
        const val PLUGIN_ID = "ai.rever.boss.plugin.dynamic.kubernetes"
        const val KEY_CONTEXT = "selectedContext"
        const val KEY_NAMESPACE = "selectedNamespace"
        const val KEY_PINNED = "pinnedCustomResources"

        private const val TAB_POLL_ATTEMPTS = 25
        private const val TAB_POLL_INTERVAL_MS = 100L
        private const val HELM_SETTLE_MS = 2_500L
    }
}

/** Identifies the terminal tab the plugin runs its commands in. */
data class CommandTerminal(
    val windowId: String,
    val terminalId: String,
    val tabId: String,
)

/** What [KubeServices.openResourceTabVerified] observed. */
enum class TabOpenOutcome {
    Focused,
    Opened,
    Dropped,
    Unverifiable,
    NoSplitViewOperations,
}
