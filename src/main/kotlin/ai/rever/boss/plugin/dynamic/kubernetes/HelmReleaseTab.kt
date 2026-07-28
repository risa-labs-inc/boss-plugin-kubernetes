package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.SimpleIcons
import compose.icons.simpleicons.Helm

/** The Helm wheel, for release tabs and the Helm sections. */
val HelmIcon: ImageVector get() = SimpleIcons.Helm

object HelmReleaseTabType : TabTypeInfo {
    override val typeId = TabTypeId("helm-release", KubeServices.PLUGIN_ID)
    override val displayName = "Helm Release"
    override val icon: ImageVector = HelmIcon
}

/**
 * One release tab, keyed by context + namespace + release so the same release name
 * in two namespaces gets two tabs while reopening one focuses it.
 */
data class HelmReleaseTabInfo(
    val contextName: String,
    val namespace: String,
    val releaseName: String,
    override val id: String = "helm-$contextName-$namespace-$releaseName",
    override val typeId: TabTypeId = HelmReleaseTabType.typeId,
    override val title: String = releaseName,
    override val icon: ImageVector = HelmIcon,
) : TabInfo

/** Bodies a release tab can show. */
enum class ReleaseSection(val label: String) {
    STATUS("Status"),
    VALUES("Values"),
    MANIFEST("Manifest"),
    HISTORY("History"),
    NOTES("Notes"),
}
