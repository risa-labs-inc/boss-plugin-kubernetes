package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.TabSplitMode
import ai.rever.boss.plugin.api.TerminalTabPluginAPI
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import kotlinx.coroutines.launch
import java.io.File

/** Where a launched terminal tab should be placed. */
enum class OpenLocation { NEW_TAB, SPLIT_RIGHT, SPLIT_DOWN }

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

    /** Guards the read-act-write of [KubeServices.commandTerminal]. */
    private val terminalLock = Any()

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
     * Falls back to a new BOSS terminal tab when there is no live terminal to join
     * (or [forceNewTab] is set, or the terminal-tab plugin isn't loaded).
     */
    fun openTerminal(
        id: String,
        title: String,
        command: String,
        workingDir: String?,
        location: OpenLocation = OpenLocation.NEW_TAB,
        forceNewTab: Boolean = false,
    ): Boolean {
        if (!forceNewTab && location == OpenLocation.NEW_TAB && runInExistingTerminal(command, workingDir)) {
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
     * Run [command] in the single terminal tab this plugin owns, creating that tab
     * only the first time.
     *
     * Two things this must not do: create a tab per command (the tab strip fills up
     * within minutes of normal use), and type into a tab the plugin doesn't own —
     * `sendCommand` goes to the terminal's *active* tab, so blindly sending would
     * inject a `helm upgrade` into whatever the user happens to be running.
     *
     * @return true if the command was delivered to a terminal.
     */
    private fun runInExistingTerminal(command: String, workingDir: String?): Boolean =
        // Wrapped whole, not per call. A host whose terminal-tab plugin is absent or
        // older than the interface fails at *linkage* — NoClassDefFoundError on the
        // TerminalTabPluginAPI class literal, NoSuchMethodError on a call — and those
        // are Errors thrown on entry, which a runCatching inside the method never runs
        // to catch. runCatching catches Throwable, so a missing terminal degrades to
        // opening a BOSS tab instead of taking the whole action down.
        runCatching { reuseOwnedTerminal(command, workingDir) }.getOrDefault(false)

    /**
     * The reuse itself. Serialized: `commandTerminal` is read, acted on and written
     * here, and the callers are an MCP handler and a panel click on different
     * dispatchers. `@Volatile` gives visibility, not atomicity — two callers could both
     * see null, both create a tab, and both write, leaving an orphaned tab and two
     * commands racing into one pty. The lock also makes the interrupt-then-send
     * sequence below indivisible.
     */
    private fun reuseOwnedTerminal(command: String, workingDir: String?): Boolean =
        synchronized(terminalLock) {
            // Resolved per call: cross-plugin APIs can appear after our register().
            val api = services.context.getPluginAPI(TerminalTabPluginAPI::class.java) ?: return false
            val tabs = services.context.activeTabsProvider?.activeTabs?.value ?: return false
            // Never leave the directory implicit. On reuse the tab sits wherever the last
            // command left it, so a null workingDir would run this one in some other
            // project's directory — which matters most for `helm install ./chart` and
            // `kubectl apply -f`, where a relative path resolves against it.
            val dir = workingDir?.takeIf { it.isNotBlank() }
                ?: services.context.projectPath?.takeIf { it.isNotBlank() }

            // Reuse our own tab when it's still there.
            services.commandTerminal?.let { owned ->
                val stillHosted = tabs.any { it.tabId == owned.terminalId && it.windowId == owned.windowId }
                val stillOpen = stillHosted && runCatching {
                    api.listTabs(owned.windowId, owned.terminalId).any { it.id == owned.tabId }
                }.getOrDefault(false)
                if (stillOpen) {
                    // Switch first: sendCommand targets the active tab, so this is what
                    // guarantees the command lands in ours.
                    val switched = runCatching { api.switchToTab(owned.windowId, owned.terminalId, owned.tabId) }
                        .getOrDefault(false)
                    if (switched && sendToOwnedTab(api, owned, command, dir)) {
                        focusHostTab(tabs, owned.terminalId, owned.windowId)
                        return true
                    }
                }
                // Gone or unusable — forget it and make a fresh one below.
                services.commandTerminal = null
            }

            // Probe every tab rather than filtering on a typeId string: hasTerminalState
            // is a registry lookup and the authoritative answer to "is this a tabbed
            // terminal?", so it can't drift if the type id is renamed. Prefer this
            // window's terminals over another window's.
            val myWindow = services.context.windowId
            val candidate = tabs.asSequence()
                .sortedByDescending { it.windowId == myWindow }
                .firstOrNull { runCatching { api.hasTerminalState(it.windowId, it.tabId) }.getOrDefault(false) }
                ?: return false

            val newTabId = runCatching {
                api.createTab(
                    windowId = candidate.windowId,
                    terminalId = candidate.tabId,
                    workingDirectory = dir,
                    initialCommand = command,
                )
            }.getOrNull() ?: return false

            services.commandTerminal = CommandTerminal(candidate.windowId, candidate.tabId, newTabId)
            runCatching { api.switchToTab(candidate.windowId, candidate.tabId, newTabId) }
            focusHostTab(tabs, candidate.tabId, candidate.windowId)
            return true
        }

    /**
     * Deliver [command] to the tab we own, interrupting whatever is running there first.
     *
     * The interrupt is the load-bearing part. `sendCommand` writes to the tab's pty, so
     * with a foreground process still running — a `helm install --wait`, a `kubectl apply`
     * against a slow cluster, an `exec -it` session — the text goes to *that process's
     * stdin* and is never queued and never run. Returning true there would have the tool
     * report a release upgraded that was never attempted. Ctrl-C first means the shell is
     * the one reading.
     *
     * Same switch → Ctrl-C → delay → send sequence terminal-tab uses for a re-run,
     * including the 500 ms it waits for the shell to regain the line, and it is why this
     * sits under [terminalLock]: two commands interleaving here would interrupt each
     * other's send.
     *
     * Interrupting is the direct consequence of "reuse one tab": this tab exists only for
     * the plugin's own commands, so what gets interrupted is always an earlier one.
     */
    private fun sendToOwnedTab(
        api: TerminalTabPluginAPI,
        owned: CommandTerminal,
        command: String,
        dir: String?,
    ): Boolean {
        val full = if (dir == null) command else "cd ${q(dir)} && $command"
        runCatching { api.sendInterrupt(owned.windowId, owned.terminalId) }
        // Off the caller's thread: the caller may be the UI thread, and blocking it for
        // half a second to type a command is a visible stall.
        services.scope.launch {
            kotlinx.coroutines.delay(SHELL_REGAIN_LINE_MS)
            runCatching { api.sendCommand(owned.windowId, owned.terminalId, full) }
        }
        return true
    }

    /**
     * Bring the BOSS tab hosting the terminal forward so the output is visible.
     *
     * Matched on window as well as tab: the candidate search deliberately spans windows,
     * so a tab id alone can name a different window's tab.
     */
    private fun focusHostTab(
        tabs: List<ai.rever.boss.plugin.api.ActiveTabData>,
        terminalId: String,
        windowId: String,
    ) {
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
