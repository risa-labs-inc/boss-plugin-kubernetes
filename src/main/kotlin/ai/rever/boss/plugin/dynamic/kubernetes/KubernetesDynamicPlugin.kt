package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Kubernetes — manage a cluster from BOSS.
 *
 * Registers:
 * - a sidebar panel scoped to a context + namespace, listing the project's
 *   manifests alongside workloads, pods, services and the rest of the namespace,
 * - a main-panel resource tab per object (logs, port-forwarded preview, describe,
 *   YAML, events),
 * - `k8s_*` MCP tools so in-terminal agents can drive the same operations.
 *
 * The plugin never writes to the kubeconfig: the selected context and namespace
 * are passed per invocation, so the user's other shells are unaffected.
 */
class KubernetesDynamicPlugin : DynamicPlugin {

    override val pluginId = KubeServices.PLUGIN_ID
    override val displayName = "Kubernetes"
    override val version = "1.0.0"
    override val description =
        "Manage Kubernetes from the sidebar — workloads, pods, services and the rest of a namespace, with " +
            "live logs, supervised port-forwards and an inline preview of what a service serves"
    override val author = "Risa Labs"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-kubernetes"

    private var services: KubeServices? = null

    override fun register(context: PluginContext) {
        val services = KubeServices(context).also { this.services = it }
        services.start()

        context.panelRegistry.registerPanel(KubePanelInfo) { ctx, panelInfo ->
            KubePanelComponent(ctx, panelInfo, services)
        }
        context.tabRegistry.registerTabType(KubeResourceTabType) { tabInfo, ctx ->
            KubeResourceTabComponent(ctx, tabInfo, services)
        }
        context.registerMcpToolProvider(KubeMcpToolProvider(pluginId, services))
    }

    override fun dispose() {
        services?.dispose()
        services = null
    }
}
