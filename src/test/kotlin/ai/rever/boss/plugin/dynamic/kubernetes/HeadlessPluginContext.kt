package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Enough [PluginContext] to construct [KubeServices] outside a running host.
 *
 * Only three members of the interface are abstract; every provider is a `default`
 * returning null, which is the API's convention precisely so plugins keep compiling as
 * it grows. The registries are never touched by the tests that use this — building tool
 * definitions registers no panel and no tab, and no handler is invoked — so reaching for
 * one is a bug in the test rather than something to stub out.
 */
internal object HeadlessPluginContext : PluginContext {
    override val pluginScope: CoroutineScope = CoroutineScope(SupervisorJob())
    override val panelRegistry: PanelRegistry get() = unavailable("panelRegistry")
    override val tabRegistry: TabRegistry get() = unavailable("tabRegistry")

    private fun unavailable(name: String): Nothing =
        throw UnsupportedOperationException("HeadlessPluginContext has no $name — this test must not need one")
}
