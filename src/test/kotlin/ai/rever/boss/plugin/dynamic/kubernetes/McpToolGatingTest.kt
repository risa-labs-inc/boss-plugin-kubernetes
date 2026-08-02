package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate on the gates.
 *
 * This plugin's MCP surface can restart workloads, apply arbitrary manifests, open a
 * shell inside a pod and uninstall Helm releases. Which of those are permission-gated
 * used to live only in the shape of the code — and in prose in two repos, one of them
 * BossConsole's README, which cannot see a change here because this plugin releases on
 * its own (boss-plugin-kubernetes#3). So the answer lives here instead, as an assertion
 * that fails the build.
 *
 * **Every `readOnly = false` tool must be either permission-gated or listed in
 * [UNGATED_BY_DESIGN] with a reason.** Add a mutating tool and forget to decide, and
 * this test names it and fails.
 *
 * Two things about how it looks:
 *
 * - It reflects over the **real [McpToolDefinition] objects** returned by the real
 *   providers, not over source text. Source-scanning is how the audit in #3 nearly went
 *   wrong: tools are built by two different factories, `McpToolDefinition(...)` and
 *   `McpToolDefinition.withRbac(...)`, so a scan keyed on the first spelling silently
 *   skips exactly the gated set and concludes nothing is gated. Reading the objects
 *   cannot make that mistake — and it also catches a gate lost to `.copy()`, which
 *   returns a definition with `requiredPermissions` reset because they are not
 *   constructor parameters.
 * - [GATED_TOOLS] pins the permission each gated tool requires. That is deliberately
 *   redundant with the code: it is the table other docs can point at, and it fails on a
 *   *downgrade* (a gate quietly moved to a weaker permission) which the
 *   gated-or-allow-listed check alone would accept.
 *
 * Both maps are declarations, not test scaffolding. Changing gating means changing them
 * on purpose, in the same commit.
 */
class McpToolGatingTest {

    // ------------------------------------------------------------- the surface

    private val allTools: List<McpToolDefinition> by lazy {
        val services = KubeServices(HeadlessPluginContext)
        KubeMcpToolProvider("test.k8s", services).tools() +
            HelmMcpToolProvider("test.helm", services).tools()
    }

    private val mutatingTools: List<McpToolDefinition> get() = allTools.filterNot { it.readOnly }

    // ------------------------------------------------------------ the asserts

    @Test
    fun `every mutating tool is permission-gated or ungated on purpose`() {
        val undecided = mutatingTools
            .filter { it.requiredPermissions.isEmpty() && !it.requiresAdmin }
            .map { it.name }
            .filterNot { it in UNGATED_BY_DESIGN }
            .sorted()

        assertTrue(
            undecided.isEmpty(),
            "Mutating MCP tools with no permission gate and no allow-list entry: $undecided.\n" +
                "Either gate them with McpToolDefinition.withRbac(requiredPermissions = ...) and add " +
                "them to GATED_TOOLS, or add them to UNGATED_BY_DESIGN with the reason they are safe " +
                "for any caller. Do not silently leave a mutating tool ungated — see " +
                "https://github.com/risa-labs-inc/boss-plugin-kubernetes/issues/3",
        )
    }

    @Test
    fun `the ungated allow-list has no stale entries`() {
        val byName = allTools.associateBy { it.name }

        val vanished = UNGATED_BY_DESIGN.keys.filterNot { it in byName }.sorted()
        assertTrue(
            vanished.isEmpty(),
            "UNGATED_BY_DESIGN names tools that no longer exist: $vanished. Remove them, so the " +
                "allow-list stays a list of live decisions rather than a place exemptions accumulate.",
        )

        val nowGated = UNGATED_BY_DESIGN.keys
            .mapNotNull { byName[it] }
            .filter { it.requiredPermissions.isNotEmpty() || it.requiresAdmin }
            .map { it.name }
            .sorted()
        assertTrue(
            nowGated.isEmpty(),
            "UNGATED_BY_DESIGN exempts tools that are in fact gated now: $nowGated. Move them to " +
                "GATED_TOOLS — an exemption that no longer applies reads as a decision that was made.",
        )

        val nowReadOnly = UNGATED_BY_DESIGN.keys
            .mapNotNull { byName[it] }
            .filter { it.readOnly }
            .map { it.name }
            .sorted()
        assertTrue(
            nowReadOnly.isEmpty(),
            "UNGATED_BY_DESIGN exempts read-only tools: $nowReadOnly. They need no exemption; drop them.",
        )
    }

    @Test
    fun `the gated set and its permissions are exactly as documented`() {
        val actual = allTools
            .filter { it.requiredPermissions.isNotEmpty() }
            .associate { it.name to it.requiredPermissions }

        assertEquals(
            GATED_TOOLS.toSortedMap().toString(),
            actual.toSortedMap().toString(),
            "The gated MCP tools no longer match GATED_TOOLS. A gate was added, removed, or moved to " +
                "a different permission. If that was intended, update GATED_TOOLS in the same commit — " +
                "it is the table this repo's README and BossConsole's guardrails section refer to. " +
                "A gate that disappears here is a mutating tool anyone can call.",
        )
    }

    @Test
    fun `every permission a tool requires is declared in the manifest`() {
        val declared = declaredPermissions()
        val referenced = allTools.flatMap { it.requiredPermissions }.toSortedSet()
        val dangling = referenced.filterNot { it in declared }

        assertTrue(
            dangling.isEmpty(),
            "MCP tools require permissions the plugin manifest does not define: $dangling " +
                "(declared: $declared). definedPermissions in plugin.json is what the plugin store " +
                "auto-registers into the RBAC catalog at publish; a permission missing from there can " +
                "never be granted, so the tool is invisible to everyone except admins, forever.",
        )
    }

    @Test
    fun `tool names are unique across both providers`() {
        val duplicates = allTools.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys.sorted()
        assertTrue(
            duplicates.isEmpty(),
            "Duplicate MCP tool names: $duplicates. The host's registry keeps the first registration " +
                "and skips later ones with only a log line, so the losing tool — possibly the gated " +
                "one — just never appears.",
        )
    }

    // ------------------------------------------------------------------ helpers

    /** `definedPermissions` from the packaged manifest, read the way the host reads it. */
    private fun declaredPermissions(): Set<String> {
        val text = checkNotNull(javaClass.getResourceAsStream(MANIFEST_RESOURCE)) {
            "$MANIFEST_RESOURCE is not on the test classpath"
        }.bufferedReader().use { it.readText() }

        return Json.parseToJsonElement(text).jsonObject["definedPermissions"]
            ?.jsonArray
            ?.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            ?.toSortedSet()
            .orEmpty()
    }

    private companion object {
        const val MANIFEST_RESOURCE = "/META-INF/boss-plugin/plugin.json"

        const val MANAGE = "kubernetes.manage"
        const val EXEC = "kubernetes.exec"
        const val PUBLISH = "helm.publish"

        /**
         * Tool -> the permissions it requires. The documented gated surface; keep it
         * sorted by permission then name so a diff reads as a change in policy.
         */
        val GATED_TOOLS: Map<String, List<String>> = mapOf(
            // Cluster and release state.
            "k8s_apply" to listOf(MANAGE),
            "k8s_delete" to listOf(MANAGE),
            "k8s_rollout_restart" to listOf(MANAGE),
            "k8s_scale" to listOf(MANAGE),
            "helm_install" to listOf(MANAGE),
            "helm_rollback" to listOf(MANAGE),
            "helm_test" to listOf(MANAGE),
            "helm_uninstall" to listOf(MANAGE),
            "helm_upgrade" to listOf(MANAGE),
            // Machine-wide helm config, shared with the user's own shells.
            "helm_repo_add" to listOf(MANAGE),
            "helm_repo_remove" to listOf(MANAGE),
            // A shell in a pod: arbitrary in-cluster execution with the pod's
            // credentials, and the one path around this plugin's Secret protections.
            // Separately grantable on purpose.
            "k8s_exec" to listOf(EXEC),
            // Leaves the machine.
            "helm_push" to listOf(PUBLISH),
        )

        /**
         * Mutating tools that are ungated on purpose, each with the reason.
         *
         * The bar is not "harmless" — it is *"cannot change the cluster, cannot write
         * state outside BOSS, and grants no authority the ungated read tools don't
         * already carry"*. Anything that fails that bar belongs in [GATED_TOOLS].
         */
        val UNGATED_BY_DESIGN: Map<String, String> = mapOf(
            // Flips an in-memory selection only — KubeEngine never writes the kubeconfig,
            // so no other shell is retargeted. It does decide which cluster every later
            // k8s_*/helm_* call hits, but the tools that can *do* something there are each
            // gated on their own, and every reply in this plugin names the context and
            // namespace it acted on, so a retarget cannot be silent. Gating it would stop
            // a read-only agent from looking at a second namespace, which is most of the
            // read surface's value, in exchange for nothing a gated tool doesn't cover.
            "k8s_use_context" to "in-memory target selection; never writes the kubeconfig, and every reply names the target",

            // Supervised `kubectl port-forward`. Creates no cluster state; it changes the
            // transport, not the authority — the same kubeconfig credentials the ungated
            // read tools already use. Every forward is visible via k8s_forwards and in the
            // sidebar, bounded by MAX_RESTARTS, and torn down when the plugin unloads. The
            // honest residual is that a service whose only protection was network position
            // becomes reachable from localhost; if that needs closing it wants a permission
            // of its own rather than being folded into kubernetes.manage, since it is not a
            // mutation.
            "k8s_port_forward" to "local socket over existing read credentials; listed by k8s_forwards, no cluster state",
            "k8s_port_forward_stop" to "only ever reduces reach — gating a teardown would be backwards",

            // UI only: open or focus a BOSS tab. readOnly = false because opening a tab is
            // a side effect, not because anything is written. What the tabs then show is
            // what the read tools return, redaction included.
            "k8s_open_resource" to "opens a BOSS tab; shows only what the read tools already return",
            "helm_open_release" to "opens a BOSS tab; the manifest view is redacted like helm_manifest",

            // Local filesystem, inside the project the user already has open, with no
            // cluster contact: ordinary chart-development build steps. Gating them would
            // make working on a chart require cluster-mutation rights, which is the wrong
            // mapping. helm_dependency_update does fetch from already-registered repos —
            // registering one is gated (helm_repo_add), which is where that trust decision
            // belongs.
            "helm_package" to "writes a .tgz locally; publishes nothing",
            "helm_dependency_update" to "fetches declared dependencies from already-registered repos into the project",

            // Refreshes the local index cache for repos that are already registered. Adds
            // no trust, removes nothing, touches no cluster — and helm_search is useless
            // without it.
            "helm_repo_update" to "refreshes the local cache of already-registered repos; adds no new source",
        )
    }
}

/**
 * Enough [PluginContext] to construct [KubeServices] outside a running host.
 *
 * Only three members of the interface are abstract; every provider is a `default`
 * returning null, which is the API's convention precisely so plugins keep compiling as
 * it grows. The registries are never touched here — building tool definitions does not
 * register a panel or a tab, and no handler is invoked — so reaching for one is a bug in
 * the test rather than something to stub out.
 */
private object HeadlessPluginContext : PluginContext {
    override val pluginScope: CoroutineScope = CoroutineScope(SupervisorJob())
    override val panelRegistry: PanelRegistry get() = unavailable("panelRegistry")
    override val tabRegistry: TabRegistry get() = unavailable("tabRegistry")

    private fun unavailable(name: String): Nothing =
        throw UnsupportedOperationException("HeadlessPluginContext has no $name — enumerating MCP tools must not need one")
}
