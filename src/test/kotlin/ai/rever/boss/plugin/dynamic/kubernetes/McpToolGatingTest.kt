package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.McpToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate on the gates.
 *
 * This plugin's MCP surface can restart workloads, apply arbitrary manifests, open a
 * shell inside a pod, forward a port out of the cluster and uninstall Helm releases.
 * Which of those are permission-gated used to live only in the shape of the code — and
 * in prose in two repos, one of them BossConsole's README, which cannot see a change
 * here because this plugin releases on its own (boss-plugin-kubernetes#3). So the answer
 * lives here instead, as an assertion that fails the build.
 *
 * **Every tool must appear in exactly one of [GATED_TOOLS], [UNGATED_BY_DESIGN] or
 * [READ_ONLY_TOOLS].** The inventory check is the load-bearing one, and it is deliberately
 * stricter than "every mutating tool is gated". `readOnly` **defaults to `true`** in
 * `McpToolDefinition`, so the likeliest way to ship an ungated mutation is to forget the
 * `readOnly = false` line as well as the permission — a tool that runs `kubectl cordon`
 * and says nothing about itself. Keying only on `readOnly == false` catches the honest
 * mistake and misses the careless one. Pinning the whole set means *any* new tool forces
 * a decision, whatever it claims about itself.
 *
 * Two things about how it looks:
 *
 * - It reflects over the **real [McpToolDefinition] objects** from [mcpToolProviders],
 *   not over source text, and not over a provider list retyped here. Source-scanning is
 *   how the audit in #3 nearly went wrong: tools are built by two different factories,
 *   `McpToolDefinition(...)` and `McpToolDefinition.withRbac(...)`, so a scan keyed on
 *   the first spelling silently skips exactly the gated set and concludes nothing is
 *   gated. Reading the objects cannot make that mistake — and it also catches a gate
 *   lost to `.copy()`, which returns a definition with `requiredPermissions` reset
 *   because they are not constructor parameters.
 * - [GATED_TOOLS] pins the permission each gated tool requires. That is deliberately
 *   redundant with the code: it is the table other docs can point at, and it fails on a
 *   *downgrade* (a gate quietly moved to a weaker permission) which the
 *   gated-or-allow-listed check alone would accept.
 *
 * What it does not do: check the markdown. README.md and AGENTS.md restate this split in
 * prose and nothing verifies them, so treat the tables below as the source of truth and
 * the docs as something to update by hand. Nor is a green build a review — widening
 * [UNGATED_BY_DESIGN] is a one-line edit that passes, and this repo has no CODEOWNERS,
 * while the Claude review workflow runs on pull requests only and releases fire on push
 * to `main`. The tables are here to make the decision *visible in the diff*, not to make
 * it impossible.
 */
class McpToolGatingTest {

    // ------------------------------------------------------------- the surface

    private val services = KubeServices(HeadlessPluginContext)

    /** Every tool the host would register, from the same list `register()` uses. */
    private val allTools: List<McpToolDefinition> by lazy {
        mcpToolProviders("test.kubernetes", services).flatMap { it.tools() }
    }

    private val mutatingTools: List<McpToolDefinition> get() = allTools.filterNot { it.readOnly }

    @AfterEach
    fun tearDown() {
        // Closes the command channel and cancels the scope. Nothing here started them, but
        // a test that constructs the real services object should also put it down.
        services.dispose()
    }

    // ------------------------------------------------------------- the asserts

    @Test
    fun `every tool is classified in exactly one table`() {
        val classified = GATED_TOOLS.keys + UNGATED_BY_DESIGN.keys + READ_ONLY_TOOLS
        val actual = allTools.map { it.name }.toSortedSet()

        val unclassified = actual.filterNot { it in classified }
        assertTrue(
            unclassified.isEmpty(),
            "MCP tools missing from every table in McpToolGatingTest: $unclassified.\n" +
                "Decide what each one is and add it to GATED_TOOLS (with its permissions), " +
                "UNGATED_BY_DESIGN (with the reason a mutation is safe for any caller), or " +
                "READ_ONLY_TOOLS. Note `readOnly` defaults to TRUE, so a mutating tool that " +
                "omits the flag lands here rather than in the gating check — which is exactly " +
                "why this assertion pins the whole inventory. " +
                "See https://github.com/risa-labs-inc/boss-plugin-kubernetes/issues/3",
        )

        val phantom = classified.filterNot { it in actual }.sorted()
        assertTrue(
            phantom.isEmpty(),
            "The tables name tools that no longer exist: $phantom. Remove them — a table that " +
                "lists absent tools stops being an inventory.",
        )
    }

    @Test
    fun `the tables are disjoint`() {
        val overlaps = listOf(
            "GATED_TOOLS/UNGATED_BY_DESIGN" to (GATED_TOOLS.keys intersect UNGATED_BY_DESIGN.keys),
            "GATED_TOOLS/READ_ONLY_TOOLS" to (GATED_TOOLS.keys intersect READ_ONLY_TOOLS),
            "UNGATED_BY_DESIGN/READ_ONLY_TOOLS" to (UNGATED_BY_DESIGN.keys intersect READ_ONLY_TOOLS),
        ).filter { it.second.isNotEmpty() }

        assertTrue(
            overlaps.isEmpty(),
            "A tool is in two tables at once: $overlaps. Each tool has one classification; " +
                "double-listing would let the inventory check pass while saying two things.",
        )
    }

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
    fun `the ungated allow-list and the read-only table describe live facts`() {
        val byName = allTools.associateBy { it.name }

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
            "UNGATED_BY_DESIGN exempts read-only tools: $nowReadOnly. Move them to READ_ONLY_TOOLS; " +
                "they need no exemption.",
        )

        val notActuallyReadOnly = READ_ONLY_TOOLS
            .mapNotNull { byName[it] }
            .filterNot { it.readOnly }
            .map { it.name }
            .sorted()
        assertTrue(
            notActuallyReadOnly.isEmpty(),
            "READ_ONLY_TOOLS lists tools that declare readOnly = false: $notActuallyReadOnly. " +
                "They mutate something — gate them or justify them, do not file them as reads.",
        )
    }

    @Test
    fun `the gated set and its permissions are exactly as documented`() {
        // Sorted values: *which* permissions are required is the policy, the order they
        // were typed in is not (the host checks with containsAll).
        val actual = allTools
            .filter { it.requiredPermissions.isNotEmpty() }
            .associate { it.name to it.requiredPermissions.sorted() }
            .toSortedMap()

        assertEquals(
            GATED_TOOLS.mapValues { it.value.sorted() }.toSortedMap(),
            actual,
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
    fun `permission names are shaped the way the catalog accepts`() {
        // register_plugin_permission() rejects anything that isn't domain.action in
        // [a-z][a-z0-9_], 2..31 chars per part, and blocks reserved domains. A name it
        // rejects is never registered, which lands in the same place as a dangling
        // permission: grantable by nobody, so admin-only forever.
        val shape = Regex("^[a-z][a-z0-9_]{1,30}\\.[a-z][a-z0-9_]{1,30}$")
        val reserved = setOf("role", "user", "api_key", "rpa", "secret", "plugins")
        val referenced = allTools.flatMap { it.requiredPermissions }.toSortedSet()

        val malformed = referenced.filterNot { shape.matches(it) }
        assertTrue(malformed.isEmpty(), "Permission names the RBAC catalog would reject: $malformed")

        val inReserved = referenced.filter { it.substringBefore('.') in reserved }
        assertTrue(inReserved.isEmpty(), "Permission names in a reserved domain: $inReserved")
    }

    @Test
    fun `tool names are unique across every provider`() {
        val duplicates = allTools.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys.sorted()
        assertTrue(
            duplicates.isEmpty(),
            "Duplicate MCP tool names: $duplicates. The host's registry keeps the first registration " +
                "and skips later ones with only a log line, so the losing tool — possibly the gated " +
                "one — just never appears.",
        )
    }

    // ----------------------------------------------------------------- helpers

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
        const val PORT_FORWARD = "kubernetes.portforward"
        const val REPO = "helm.repo"
        const val PUBLISH = "helm.publish"

        /**
         * Tool -> the permissions it requires. The documented gated surface.
         *
         * Note what is *not* done here: `kubernetes.manage` is never widened by editing
         * its description, because `register_plugin_permission` writes a description only
         * on first insert — later edits are a no-op against an already-registered
         * catalog. New authority gets a new name, or admins grant it from text that can no
         * longer be corrected.
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
            // A shell in a pod: arbitrary in-cluster execution with the pod's credentials,
            // reading its mounted Secrets around this plugin's redaction. Separately
            // grantable on purpose — though not a containment boundary against a
            // kubernetes.manage holder, who can apply a Job that prints a Secret into its
            // logs and read it back with the ungated k8s_logs.
            "k8s_exec" to listOf(EXEC),
            // An unmediated socket into the cluster, over whatever protocol the target
            // speaks. Distinct from MANAGE because it changes no state, and distinct from
            // the read tools because those can only read Kubernetes objects — a forward to
            // an internal database can write to it.
            "k8s_port_forward" to listOf(PORT_FORWARD),
            // Machine-wide helm config, shared with the user's own shells.
            "helm_repo_add" to listOf(REPO),
            "helm_repo_remove" to listOf(REPO),
            // Leaves the machine.
            "helm_push" to listOf(PUBLISH),
        )

        /**
         * Mutating tools that are ungated on purpose, each with the reason.
         *
         * The bar: *cannot change cluster state, cannot write state outside BOSS, and
         * opens no new channel into the cluster.* `k8s_port_forward` used to sit here and
         * failed that third clause — a forward can write to an internal service, which no
         * read tool can — so it moved to [GATED_TOOLS] rather than having the bar bent
         * around it.
         *
         * Several of these open a plugin-owned terminal tab (`KubeActions.openTerminal`)
         * to run their helm command, which an agent with terminal access could then type
         * into. That grants nothing new — `mcp__boss__run_command` is itself ungated, so
         * such an agent already has a shell — but it is why none of this is a sandbox.
         */
        val UNGATED_BY_DESIGN: Map<String, String> = mapOf(
            // Selection only: KubeEngine never writes the kubeconfig, so no other shell is
            // retargeted, and the MCP path no longer calls rememberTarget() either, so an
            // agent's choice does not outlive the session. It does move the shared
            // selection the sidebar shows, and it decides which cluster later calls hit —
            // survivable only because every reply names its context and namespace
            // (including the YAML-bodied ones, as a comment). Gating it would stop a
            // read-only agent from looking at a second namespace, which is most of the
            // read surface's value, and would not close the retarget-then-mutate path,
            // since each mutating tool is gated on its own.
            "k8s_use_context" to "session-local target selection; never writes the kubeconfig, never persisted, and every reply names the target",

            // Strictly de-escalating: it can only remove a forward that already exists.
            // Gating a teardown would be backwards — and starting one is gated.
            "k8s_port_forward_stop" to "only ever reduces reach; starting a forward is what needs kubernetes.portforward",

            // UI only: open or focus a BOSS tab. readOnly = false because opening a tab is
            // a side effect, not because anything is written. What the tabs then show is
            // what the read tools return, redaction included.
            "k8s_open_resource" to "opens a BOSS tab; shows only what the read tools already return",
            "helm_open_release" to "opens a BOSS tab; the manifest view is redacted like helm_manifest",

            // Local filesystem, inside the project the user already has open, with no
            // cluster contact: ordinary chart-development build steps, run in the plugin's
            // terminal tab. Gating them would make working on a chart require
            // cluster-mutation rights, which is the wrong mapping. helm_dependency_update
            // does fetch from already-registered repos — registering one is gated
            // (helm_repo_add, helm.repo), which is where that trust decision belongs.
            "helm_package" to "writes a .tgz locally via the plugin's terminal tab; publishes nothing",
            "helm_dependency_update" to "fetches declared dependencies from already-registered repos into the project, via the plugin's terminal tab",

            // Refreshes the local index cache for repos that are already registered. Adds
            // no trust, removes nothing, touches no cluster — and helm_search is useless
            // without it.
            "helm_repo_update" to "refreshes the local cache of already-registered repos via the plugin's terminal tab; adds no new source",
        )

        /**
         * Tools that declare `readOnly = true`, pinned so adding one is still a visible
         * decision — and so a tool that quietly starts mutating cannot hide in the default.
         *
         * Read does not mean harmless: `k8s_logs` returns whatever an app logged and
         * `helm_values` returns chart values verbatim, which is why those replies now name
         * their target. What the read surface will not do is render a Kubernetes Secret's
         * payload — `k8s_get` refuses any `kind` that is not a single resource type and
         * substitutes name/type/age columns for Secrets, and `KubeActions.yaml` refuses
         * them outright (see `SecretKindGuardTest`).
         */
        val READ_ONLY_TOOLS: Set<String> = setOf(
            "k8s_contexts",
            "k8s_namespaces",
            "k8s_pods",
            "k8s_get",
            "k8s_logs",
            "k8s_describe",
            "k8s_yaml",
            "k8s_events",
            "k8s_api_resources",
            "k8s_forwards",
            "k8s_manifests",
            "helm_releases",
            "helm_status",
            "helm_values",
            "helm_manifest",
            "helm_history",
            "helm_notes",
            "helm_charts",
            "helm_lint",
            "helm_template",
            "helm_repos",
            "helm_search",
        )
    }
}
