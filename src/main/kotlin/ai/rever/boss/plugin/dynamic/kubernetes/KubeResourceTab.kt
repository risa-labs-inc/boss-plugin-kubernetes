package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.ui.graphics.vector.ImageVector

object KubeResourceTabType : TabTypeInfo {
    override val typeId = TabTypeId("kube-resource", KubeServices.PLUGIN_ID)
    override val displayName = "Kubernetes Resource"
    override val icon: ImageVector = KubeIcon
}

/**
 * One resource tab. The id is stable per context + namespace + kind + name, so the
 * same object in two namespaces gets two tabs while reopening one focuses it.
 */
data class KubeResourceTabInfo(
    val contextName: String,
    val namespace: String,
    val kind: String,
    val resourceName: String,
    override val id: String = "kube-${contextName}-${namespace}-${kind.lowercase()}-$resourceName",
    override val typeId: TabTypeId = KubeResourceTabType.typeId,
    override val title: String = resourceName,
    override val icon: ImageVector = KubeIcon,
) : TabInfo

/** Bodies a resource tab can show. Which ones apply depends on the kind. */
enum class ResourceSection(val label: String) {
    LOGS("Logs"),
    PREVIEW("Preview"),
    DESCRIBE("Describe"),
    YAML("YAML"),
    EVENTS("Events"),
}

/**
 * Which sections make sense for [kind].
 *
 * Logs need a pod behind them, preview needs something port-forwardable, and YAML
 * is withheld from Secrets because it *is* the values.
 */
fun sectionsFor(kind: String): List<ResourceSection> {
    val k = kind.lowercase().trimEnd('s')
    return when {
        k == "pod" -> listOf(
            ResourceSection.LOGS,
            ResourceSection.PREVIEW,
            ResourceSection.DESCRIBE,
            ResourceSection.YAML,
            ResourceSection.EVENTS,
        )
        k == "service" -> listOf(
            ResourceSection.PREVIEW,
            ResourceSection.DESCRIBE,
            ResourceSection.YAML,
            ResourceSection.EVENTS,
        )
        k in setOf("deployment", "statefulset", "daemonset", "job", "cronjob") -> listOf(
            ResourceSection.LOGS,
            ResourceSection.DESCRIBE,
            ResourceSection.YAML,
            ResourceSection.EVENTS,
        )
        KubeActions.isSecretKind(kind) -> listOf(ResourceSection.DESCRIBE, ResourceSection.EVENTS)
        else -> listOf(ResourceSection.DESCRIBE, ResourceSection.YAML, ResourceSection.EVENTS)
    }
}
