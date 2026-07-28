package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.TabSplitMode
import ai.rever.boss.plugin.api.TerminalTabPluginAPI
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Where a launched terminal tab should be placed. */
enum class OpenLocation { NEW_TAB, SPLIT_RIGHT, SPLIT_DOWN }

/** A command awaiting delivery to the plugin's terminal tab, with its resolved cwd. */
private data class TerminalCommand(val command: String, val workingDir: String?)

/**
 * Cluster mutations and the interactive commands.
 *
 * Split the same way as the Docker plugin: things that are long, interactive or
 * worth reading in full go to a **BossTerm terminal tab** (`apply`, `diff`,
 * `exec`), while short non-interactive calls run in-plugin and toast (`delete`,
 * `scale`, `rollout restart`).
 *
 * Every mutating entry point takes the caller through a confirmation that names
 * the context and namespace — see [describeTarget]. There are deliberately no
 * bulk or cascade helpers (no delete-all, no prune, no delete-namespace): a
 * sidebar is the wrong place for an irreversible sweep.
 */
class KubeActions(private val services: KubeServices) {

    private val engine get() = services.engine

    /**
     * The terminal tab this plugin owns, and whether anything has been typed into it.
     *
     * Plain `var`s, not `@Volatile`: [runTerminalCommands] is the only reader or writer
     * and there is exactly one of it, so there is no cross-thread access to make
     * visible. Keeping them here rather than on [KubeServices] is what makes that
     * structural — a public field on the shared services object is one any future call
     * site can write without going through the queue.
     *
     * Session-scoped and never persisted; tab ids don't survive a restart.
     */
    private var ownedTerminal: CommandTerminal? = null
    private var hasSentCommand = false

    /** One command per send, drained in order by [runTerminalCommands]. */
    private val terminalCommands = Channel<TerminalCommand>(Channel.UNLIMITED)

    init {
        services.scope.launch { runTerminalCommands() }
    }

    /** `context / namespace`, for confirmation dialogs and tool output. */
    fun describeTarget(): String = engine.target.value.display

    // ------------------------------------------------------- terminal-backed

    /** `kubectl apply` a manifest or kustomization in a terminal tab. */
    fun apply(
        artifact: ManifestArtifact,
        dryRun: Boolean = false,
        location: OpenLocation = OpenLocation.NEW_TAB,
    ): Boolean {
        val command = buildString {
            append("kubectl apply ")
            append(artifact.applyArgs.joinToString(" ") { q(it) })
            append(' ')
            append(targetFlags())
            if (dryRun) append(" --dry-run=server")
        }
        return openTerminal(
            id = "kube-apply-${artifact.file.nameWithoutExtension}-${System.currentTimeMillis()}",
            title = "${if (dryRun) "Diff-apply" else "Apply"}: ${artifact.file.name}",
            command = command,
            workingDir = artifact.file.parentFile?.absolutePath,
            location = location,
        )
    }

    /** `kubectl diff` — shows what an apply would change, without changing it. */
    fun diff(artifact: ManifestArtifact, location: OpenLocation = OpenLocation.NEW_TAB): Boolean {
        val command = "kubectl diff ${artifact.applyArgs.joinToString(" ") { q(it) }} ${targetFlags()}"
        return openTerminal(
            id = "kube-diff-${artifact.file.nameWithoutExtension}-${System.currentTimeMillis()}",
            title = "Diff: ${artifact.file.name}",
            command = command,
            workingDir = artifact.file.parentFile?.absolutePath,
            location = location,
        )
    }

    /**
     * Interactive shell in a pod. Must be a real terminal — `exec -it` needs a TTY,
     * which is exactly what a Compose pane cannot provide.
     */
    fun exec(
        pod: PodInfo,
        container: String? = null,
        shell: String = "sh",
        location: OpenLocation = OpenLocation.NEW_TAB,
    ): Boolean {
        val containerFlag = container?.let { " -c ${q(it)}" }.orEmpty()
        val command = "kubectl exec -it ${q(pod.name)}$containerFlag ${targetFlags()} -- $shell"
        return openTerminal(
            id = "kube-exec-${pod.name}-${System.currentTimeMillis()}",
            title = "exec: ${pod.name}",
            command = command,
            workingDir = services.context.projectPath,
            location = location,
        )
    }

    /**
     * Run [command] in a terminal.
     *
     * **Prefers adding a tab inside a terminal you already have open** rather than
     * opening another BOSS tab. Every apply/diff/exec/helm command spawning a
     * top-level tab clutters the tab bar fast, and BossTerm already has its own tab
     * strip built for exactly this.
     *
     * Falls back to a new BOSS terminal tab when there is no live terminal in this
     * window to join, or when the terminal-tab plugin isn't loaded.
     */
    fun openTerminal(
        id: String,
        title: String,
        command: String,
        workingDir: String?,
        location: OpenLocation = OpenLocation.NEW_TAB,
    ): Boolean {
        if (location == OpenLocation.NEW_TAB && runInExistingTerminal(command, workingDir)) {
            return true
        }

        val ops = services.context.splitViewOperations ?: run {
            services.toastError("No terminal available — run manually: $command")
            return false
        }
        val tabInfo = TerminalTabInfo(
            id = id,
            title = title,
            initialCommand = command,
            workingDirectory = workingDir,
        )
        when (location) {
            OpenLocation.NEW_TAB -> ops.openTab(tabInfo)
            OpenLocation.SPLIT_RIGHT -> ops.openTabInSplit(tabInfo, TabSplitMode.VERTICAL_SPLIT)
            OpenLocation.SPLIT_DOWN -> ops.openTabInSplit(tabInfo, TabSplitMode.HORIZONTAL_SPLIT)
        }
        return true
    }

    /**
     * Hand [command] to the terminal tab this plugin owns.
     *
     * Two things this must not do. Create a tab per command — that is the bug being
     * fixed (the tab strip fills up within minutes of ordinary use). And type into a
     * tab the plugin doesn't own: `sendCommand` writes to the terminal's *active* tab,
     * so reusing "whatever is focused" would inject a
     * `helm upgrade` into whatever the user happens to be running there.
     *
     * Only the *decision* happens here, and only cheaply: is there a terminal to join
     * at all? Everything that touches the terminal is queued to [terminalCommands] and
     * performed by one consumer, because the delivery is a multi-step sequence
     * (interrupt, wait, type) that must not interleave with another command's — see
     * [runTerminalCommands]. A `true` return therefore means "accepted for delivery",
     * which is why the consumer, not this function, is what reports a failure.
     *
     * Wrapped whole rather than per call. A host whose terminal-tab plugin is absent or
     * older than the interface fails at *linkage* — `NoClassDefFoundError` on the
     * `TerminalTabPluginAPI::class.java` literal, `NoSuchMethodError` on a call — and
     * those are `Error`s thrown on entry, which a `runCatching` inside the method never
     * runs to catch. `runCatching` here catches `Throwable`, so a missing terminal
     * degrades to the BOSS-tab path instead of taking down build/run.
     */
    private fun runInExistingTerminal(command: String, workingDir: String?): Boolean =
        runCatching {
            // No lock: these are reads, and the consumer owns every write.
            val api = services.context.getPluginAPI(TerminalTabPluginAPI::class.java) ?: return false
            val tabs = services.context.activeTabsProvider?.activeTabs?.value ?: return false
            if (ownedTerminal == null && findTerminalHost(api, tabs) == null) return false
            // Never leave the directory implicit. On reuse the tab sits wherever the
            // last command left it, so a null workingDir would run this command in some
            // other project's directory — which for `helm install ./chart` and
            // `kubectl apply -f ./manifest.yaml` means applying the wrong file, not just
            // running in the wrong place.
            val dir = workingDir?.takeIf { it.isNotBlank() }
                ?: services.context.projectPath?.takeIf { it.isNotBlank() }
            terminalCommands.trySend(TerminalCommand(command, dir)).isSuccess
        }.getOrDefault(false)

    /**
     * The single consumer of [terminalCommands].
     *
     * One consumer is the whole design. Delivering a command is
     * switch → interrupt → wait → type, and the wait is unavoidable: `sendCommand`
     * writes to the tab's pty, so while a foreground process is running the text goes
     * to *that process's stdin* — never queued, never run — and Ctrl-C is what makes
     * the shell the reader again. Nothing may interleave with that sequence. An earlier
     * attempt held a `synchronized` block across the interrupt but `launch`ed the send,
     * which let a second command's interrupt land *before* the first command had been
     * typed, so the first ran and the second was swallowed — the exact bug the
     * interrupt exists to prevent, reintroduced one step later. Sequencing it through
     * a channel makes the ordering structural rather than something the comments claim.
     *
     * Running here also keeps all of it off the UI thread: panel clicks call
     * [openTerminal] directly, and this body makes cross-plugin calls whose threading
     * contract the plugin does not control.
     */
    private suspend fun runTerminalCommands() {
        for ((command, dir) in terminalCommands) {
            val api = services.context.getPluginAPI(TerminalTabPluginAPI::class.java)
            val tabs = services.context.activeTabsProvider?.activeTabs?.value
            if (api == null || tabs == null) {
                services.toastError("No terminal available — run manually: $command")
                continue
            }
            val full = if (dir == null) command else "cd ${q(dir)} && $command"
            val owned = liveOwnedTerminal(api, tabs)
            if (owned != null) {
                deliverToOwnedTab(api, owned, full, tabs)
            } else if (!createOwnedTab(api, tabs, command, dir)) {
                // The terminal that existed when the command was accepted is gone.
                services.toastError("Couldn't reach the terminal — run manually: $command")
            }
        }
    }

    /** Our tab, if it is still open; forgets it and returns null otherwise. */
    private fun liveOwnedTerminal(api: TerminalTabPluginAPI, tabs: List<ai.rever.boss.plugin.api.ActiveTabData>): CommandTerminal? {
        val owned = ownedTerminal ?: return null
        val stillHosted = tabs.any { it.tabId == owned.terminalId && it.windowId == owned.windowId }
        val stillOpen = stillHosted && runCatching {
            api.listTabs(owned.windowId, owned.terminalId).any { it.id == owned.tabId }
        }.getOrDefault(false)
        if (stillOpen) return owned
        ownedTerminal = null
        return null
    }

    /**
     * Type [full] into the tab we own, interrupting whatever is running there first.
     *
     * Reusing one tab makes these commands mutually exclusive, and that is a real cost
     * rather than a free win: a `helm install --wait` still going when the operator
     * applies a manifest gets stopped. So it is announced. Silently interrupting a
     * release that is mid-rollout is the kind of thing that reads as a bug in helm.
     */
    private suspend fun deliverToOwnedTab(
        api: TerminalTabPluginAPI,
        owned: CommandTerminal,
        full: String,
        tabs: List<ai.rever.boss.plugin.api.ActiveTabData>,
    ) {
        // Switch first: sendCommand targets the active tab, so this is what guarantees
        // the command lands in ours.
        if (!runCatching { api.switchToTab(owned.windowId, owned.terminalId, owned.tabId) }.getOrDefault(false)) {
            services.toastError("Couldn't reach the terminal — run manually: $full")
            return
        }
        if (hasSentCommand) {
            services.toastInfo("Interrupting the previous command in the terminal")
        }
        runCatching { api.sendInterrupt(owned.windowId, owned.terminalId) }
        delay(SHELL_REGAIN_LINE_MS)
        val sent = runCatching { api.sendCommand(owned.windowId, owned.terminalId, full) }.getOrDefault(false)
        if (sent) {
            hasSentCommand = true
            focusHostTab(tabs, owned.terminalId, owned.windowId)
        } else {
            services.toastError("Couldn't reach the terminal — run manually: $full")
        }
    }

    /** Create the tab we will own from now on, running [command] as it starts. */
    private fun createOwnedTab(
        api: TerminalTabPluginAPI,
        tabs: List<ai.rever.boss.plugin.api.ActiveTabData>,
        command: String,
        dir: String?,
    ): Boolean {
        val host = findTerminalHost(api, tabs) ?: return false
        val newTabId = runCatching {
            api.createTab(
                windowId = host.windowId,
                terminalId = host.tabId,
                workingDirectory = dir,
                initialCommand = command,
            )
        }.getOrNull() ?: return false

        ownedTerminal = CommandTerminal(host.windowId, host.tabId, newTabId)
        // A fresh tab starts idle, so the next command is the first that could
        // interrupt anything.
        hasSentCommand = false
        runCatching { api.switchToTab(host.windowId, host.tabId, newTabId) }
        focusHostTab(tabs, host.tabId, host.windowId)
        return true
    }

    /**
     * A tabbed terminal in *this* window to host our tab, or null.
     *
     * Probes every tab rather than filtering on a typeId string: `hasTerminalState` is a
     * registry lookup and the authoritative answer to "is this a tabbed terminal?", so
     * it can't drift if the type id is renamed.
     *
     * Confined to this window on purpose. Searching every window meant a click in
     * window A could create the tab in window B and pull focus there; falling back to a
     * new BOSS tab in the window the operator is actually looking at is the less
     * surprising outcome.
     */
    private fun findTerminalHost(api: TerminalTabPluginAPI, tabs: List<ai.rever.boss.plugin.api.ActiveTabData>): ai.rever.boss.plugin.api.ActiveTabData? {
        val myWindow = services.context.windowId
        return tabs.firstOrNull {
            it.windowId == myWindow && runCatching { api.hasTerminalState(it.windowId, it.tabId) }.getOrDefault(false)
        }
    }

    /**
     * Bring the BOSS tab hosting the terminal forward so the output is visible.
     *
     * Matched on window as well as tab: a tab id alone can name another window's tab.
     */
    private fun focusHostTab(tabs: List<ai.rever.boss.plugin.api.ActiveTabData>, terminalId: String, windowId: String) {
        val host = tabs.firstOrNull { it.tabId == terminalId && it.windowId == windowId } ?: return
        runCatching { services.context.activeTabsProvider?.selectTab(host.tabId, host.panelId) }
    }

    // ---------------------------------------------------------- in-plugin ops

    suspend fun delete(kind: String, name: String): KubectlExec =
        op(listOf("delete", kind.lowercase(), name), timeoutMs = 60_000)

    suspend fun scale(workload: WorkloadInfo, replicas: Int): KubectlExec =
        op(listOf("scale", workload.ref, "--replicas=$replicas"))

    suspend fun rolloutRestart(workload: WorkloadInfo): KubectlExec =
        op(listOf("rollout", "restart", workload.ref))

    suspend fun describe(kind: String, name: String): KubectlExec =
        KubectlCli.exec(engine.args(listOf("describe", kind.lowercase(), name)), timeoutMs = 20_000)

    /**
     * `get -o yaml`, refused for Secrets.
     *
     * A Secret's YAML is its base64 values in full. The list model never parses
     * them and this door stays shut too, so there is no path through this plugin
     * that renders a secret value.
     */
    suspend fun yaml(kind: String, name: String): KubectlExec {
        if (isSecretKind(kind)) {
            return KubectlExec(
                exitCode = 1,
                stdout = "",
                stderr = "YAML is disabled for Secrets — it would contain the values themselves. " +
                    "Use Describe to see key names and sizes.",
            )
        }
        return KubectlCli.exec(engine.args(listOf("get", kind.lowercase(), name, "-o", "yaml")), timeoutMs = 20_000)
    }

    private suspend fun op(command: List<String>, timeoutMs: Long = 30_000): KubectlExec =
        KubectlCli.exec(engine.args(command), timeoutMs = timeoutMs).also { engine.requestRefresh() }

    companion object {
        /**
         * How long to let the shell regain the line after Ctrl-C before typing.
         * Matches terminal-tab's own re-run delay, which defaults to the same 500 ms.
         */
        private const val SHELL_REGAIN_LINE_MS = 500L

        fun isSecretKind(kind: String): Boolean =
            kind.lowercase().trimEnd('s') == "secret"

        /**
         * Single-quote a value for the shell. Terminal tabs take a command *string*
         * (a real shell runs it), unlike the argv-only [KubectlCli] path — and
         * resource names and paths can contain characters a shell would eat.
         */
        fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }

    /** `--context X -n Y` for the shell-string commands. */
    private fun targetFlags(): String {
        val target = engine.target.value
        return buildList {
            target.context?.let { add("--context ${q(it)}") }
            if (target.isAllNamespaces) add("--all-namespaces") else add("-n ${q(target.namespace)}")
        }.joinToString(" ")
    }
}
