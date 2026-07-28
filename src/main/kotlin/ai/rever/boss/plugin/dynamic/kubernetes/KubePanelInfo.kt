package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo

/**
 * The Kubernetes sidebar. Sits next to Docker in the lower-left slot (order 56 to
 * Docker's 55) so the two container tools are neighbours.
 */
object KubePanelInfo : PanelInfo {
    override val id = PanelId("kubernetes", 56, KubeServices.PLUGIN_ID)
    override val displayName = "Kubernetes"
    override val icon = KubeIcon
    override val defaultSlotPosition = left.bottom
}
