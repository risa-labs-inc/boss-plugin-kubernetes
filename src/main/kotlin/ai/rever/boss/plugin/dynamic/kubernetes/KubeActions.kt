package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.TabSplitMode
import ai.rever.boss.plugin.api.TerminalTabPluginAPI
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Where a launched terminal tab should be placed. */
enum class OpenLocation { NEW_TAB, SPLIT_RIGHT, SPLIT_DOWN }

/**
 * How a command occupies the terminal, which decides whether it may share the tab.
 *
 * The distinction exists because Ctrl-C does not mean the same thing to all three, and
 * treating them alike is unsafe rather than merely untidy.
 */
enum class TerminalCommandKind {
    /**
     * Shares the plugin's tab, and may be interrupted by the next command.
     *
     * That is survivable for a read, and costly for a `helm upgrade --wait` — Ctrl-C
     * stops the client but leaves the release in `pending-upgrade`, so the next upgrade
     * fails with "another operation in progress". The `helm_*` tools say so up front
     * rather than the plugin discovering it afterwards.
     */
    Batch,

    /**
     * Holds the pty and cannot be interrupted out of. **Never shares the tab.**
     *
     * `kubectl exec -it` puts the local terminal in raw mode and *forwards* 0x03 to the
     * remote process: the container's shell takes the SIGINT and prints a fresh prompt,
     * while kubectl itself keeps running and keeps owning the pty. So the interrupt that
     * makes reuse safe for everything else does nothing here, and the next command would
     * be typed **into the container's shell** — in a pod that has kubectl and a mounted
     * service-account token, that runs against the cluster with the *pod's* credentials
     * instead of the operator's kubeconfig, and the sidebar would report success.
     */
    Interactive,
}

/**
 * Identifies the terminal tab the plugin runs its commands in.
 *
 * File-private and held only by [KubeActions], so the encapsulation the consumer design
 * depends on is structural: nothing outside this file can retarget the tab without
 * going through the queue.
 */
private data class CommandTerminal(
    val windowId: String,
    val terminalId: String,
    val tabId: String,
)

/**
 * A command awaiting delivery to the plugin's terminal tab.
 *
 * Carries [id] and [title] as well as the command so the consumer can still open a
 * BOSS terminal tab if the terminal it was queued for has gone away — the fallback
 * [KubeActions.openTerminal] documents is otherwise unreachable once that function has
 * returned true.
 *
 * [onDelivered] runs once the command has actually been typed. Callers that follow a
 * command with a refresh need this: `openTerminal` now returns at *acceptance*, so
 * anything timed from its return starts counting before the command has run.
 */
private data class TerminalCommand(
    val id: String,
    val title: String,
    val command: String,
    val workingDir: String?,
    val kind: TerminalCommandKind,
    val onDelivered: (() -> Unit)?,
)

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
     * The terminal tab this plugin owns.
     *
     * Confined to the consumer: [runTerminalCommands] is the only reader and writer, and
     * there is exactly one of it, so no atomicity or visibility is needed today. (The
     * accept path used to read it too; that went away when it stopped inspecting
     * anything.) `@Volatile` is kept as belt-and-braces in case a caller-side read is
     * ever reintroduced — not because one exists.
     *
     * Kept here rather than on [KubeServices] so that guarantee is structural — a public
     * field on the shared services object is one any future call site can write without
     * going through the queue.
     *
     * Session-scoped and never persisted; tab ids don't survive a restart.
     */
    @Volatile
    private var ownedTerminal: CommandTerminal? = null

    /** Commands accepted but not yet taken by the consumer. */
    private val pending = AtomicInteger(0)

    /** The plugin has no host logger; this keeps the one prefix in one place. */
    private fun log(message: String) = System.err.println("[Kubernetes] $message")

    /**
     * One command per send, drained in order by [runTerminalCommands].
     *
     * Unbounded, which is what makes a dead consumer dangerous rather than merely
     * broken: `trySend` keeps succeeding, so [runInExistingTerminal] keeps returning
     * true and every later command is reported as launched while nothing is ever typed.
     * Hence the guard in the consumer loop and in [fallBackToBossTab], and [dispose]
     * closing the channel.
     */
    private val terminalCommands = Channel<TerminalCommand>(Channel.UNLIMITED)

    /**
     * Start draining [terminalCommands]. Called from [KubeServices.start].
     *
     * Not an `init` block: this class is constructed from a `KubeServices` property
     * initializer, so launching there would depend on `scope` happening to be declared
     * above `actions` — reorder those two lines and the plugin fails to activate with an
     * NPE and no obvious cause. An explicit call makes the ordering a fact rather than
     * an accident.
     */
    fun start() {
        services.scope.launch { runTerminalCommands() }
    }

    /**
     * Stop accepting commands.
     *
     * Closing matters as much as cancelling the scope: on a closed channel `trySend`
     * fails, so [runInExistingTerminal] returns false and a command issued during
     * teardown falls through to the BOSS-tab path instead of being accepted by a
     * consumer that will never run and reported as a success.
     */
    fun dispose() {
        terminalCommands.close()
        // Named rather than dropped in silence. Anything still queued was already
        // reported as launched, so a note is the difference between a debuggable
        // teardown and a mystery.
        val dropped = generateSequence { terminalCommands.tryReceive().getOrNull() }.toList()
        if (dropped.isNotEmpty()) {
            log("Dropped ${dropped.size} queued command(s) on dispose: " + dropped.joinToString("; ") { it.command })
        }
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
            // Never shares the plugin's tab: Ctrl-C is forwarded into the pod, so the
            // session keeps the pty and a later command would be typed at the
            // container's shell. See [TerminalCommandKind.Interactive].
            kind = TerminalCommandKind.Interactive,
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
     * Falls back to a new BOSS terminal tab when there is no live terminal to join at
     * all, or when the terminal-tab plugin isn't loaded. A terminal in this window is
     * preferred but not required — see [findTerminalHost].
     *
     * **Every `NEW_TAB` caller shares one tab and interrupts the others** unless its kind
     * is [TerminalCommandKind.Interactive]. The `k8s_*` and `helm_*` tool text enumerates
     * which commands those are; nothing checks that enumeration, so adding a caller means
     * updating it too.
     */
    fun openTerminal(
        id: String,
        title: String,
        command: String,
        workingDir: String?,
        location: OpenLocation = OpenLocation.NEW_TAB,
        kind: TerminalCommandKind = TerminalCommandKind.Batch,
        onDelivered: (() -> Unit)? = null,
    ): Boolean {
        if (location == OpenLocation.NEW_TAB &&
            runInExistingTerminal(id, title, command, workingDir, kind, onDelivered)
        ) {
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
        runCatching { onDelivered?.invoke() }
        return true
    }

    /**
     * Queue [command] for the terminal tab this plugin owns.
     *
     * Two things this must not do. Create a tab per command — that is the bug being
     * fixed (the tab strip fills up within minutes of ordinary use). And type into a
     * tab the plugin doesn't own: `sendCommand` writes to the terminal's *active* tab,
     * so reusing "whatever is focused" would inject a `helm upgrade` into whatever the
     * user happens to be running there.
     *
     * Nothing is inspected here, deliberately. Deciding whether a terminal exists meant
     * a `getPluginAPI` plus a `hasTerminalState` registry lookup **per open tab**, on
     * the caller's thread — which for a panel click is the UI thread, and scales with
     * tab count. The consumer already re-checks all of it and already handles "no
     * terminal to join" by opening a BOSS tab, so the work was duplicated as well as
     * misplaced. The only thing given up is a synchronous false, and on this path the
     * return was already advisory: it means "accepted for delivery", not "running".
     *
     * `trySend` fails only on a closed channel, i.e. after [dispose], which is the one
     * case where the caller should fall through to opening a tab itself.
     */
    private fun runInExistingTerminal(
        id: String,
        title: String,
        command: String,
        workingDir: String?,
        kind: TerminalCommandKind,
        onDelivered: (() -> Unit)?,
    ): Boolean {
        // Never leave the directory implicit. On reuse the tab sits wherever the last
        // command left it, so a null workingDir would run this one in some other
        // project's directory — which for `helm install ./chart` and
        // `kubectl apply -f ./manifest.yaml` means applying the wrong file, not just
        // running in the wrong place.
        val dir = workingDir?.takeIf { it.isNotBlank() }
            ?: services.context.projectPath?.takeIf { it.isNotBlank() }
        val accepted = terminalCommands
            .trySend(TerminalCommand(id, title, command, dir, kind, onDelivered))
            .isSuccess
        if (accepted) pending.incrementAndGet()
        return accepted
    }

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
        for (queued in terminalCommands) {
            // One bad command must not cost the feature: a consumer that dies takes
            // every later command with it, silently (see [terminalCommands]). Both
            // `getPluginAPI` can fail at linkage and the fallback's toast is a
            // cross-plugin call, so the guard catches Throwable rather than Exception.
            pending.decrementAndGet()
            try {
                deliver(queued)
            } catch (cancel: CancellationException) {
                // Named for the same reason dispose() names what it drops: this command
                // was already reported as launched, and it is the one most likely to
                // matter, because it was mid-delivery rather than still queued.
                log("Cancelled mid-delivery: ${queued.command}")
                throw cancel // plugin is being disposed; not a delivery failure
            } catch (t: Throwable) {
                log("Terminal delivery failed: $t")
                fallBackToBossTab(queued)
            }
        }
    }

    /** Deliver one queued command, reusing our tab or creating it. */
    private suspend fun deliver(queued: TerminalCommand) {
        val api = services.context.getPluginAPI(TerminalTabPluginAPI::class.java)
        val tabs = services.context.activeTabsProvider?.activeTabs?.value
        if (api == null || tabs == null) {
            // Said out loud rather than swallowed: this degrades to a BOSS tab per
            // command — the clutter this path exists to remove — so the reason needs to
            // be findable. Both causes are real: terminal-tab not loaded, or a host that
            // supplies no activeTabsProvider. The message names which.
            val missing = if (api == null) "terminal-tab API" else "activeTabsProvider"
            log("No $missing; opening a BOSS tab for: ${queued.command}")
            fallBackToBossTab(queued)
            return
        }
        val full = if (queued.workingDir == null) queued.command else "cd ${q(queued.workingDir)} && ${queued.command}"
        var hadOwnTab = false
        val delivered = when {
            // An interactive session gets its own tab and is never recorded as the
            // owned one: it cannot be interrupted out of, so sharing would strand
            // every later command inside it. See [TerminalCommandKind.Interactive].
            queued.kind == TerminalCommandKind.Interactive ->
                createSideTab(api, tabs, queued.command, queued.workingDir)

            else -> {
                val owned = liveOwnedTerminal(api, tabs)
                hadOwnTab = owned != null
                if (owned != null) {
                    deliverToOwnedTab(api, owned, full, queued.kind)
                } else {
                    // `full`, not the bare command: both paths then derive the directory
                    // the same way. Relying on createTab's workingDirectory here instead
                    // would mean that if it is ever not honoured for the initial command,
                    // the *first* command runs in the wrong place and every later one is
                    // right — miserable to debug, and for `kubectl apply -f ./x.yaml` it
                    // is the wrong file, not just the wrong directory.
                    createOwnedTab(api, tabs, full, queued.kind)
                }
            }
        }
        if (delivered) {
            runCatching { queued.onDelivered?.invoke() }
        } else {
            // Two different failures, and conflating them is what you would be
            // debugging from: no tabbed terminal open anywhere, versus our own tab
            // refusing a write.
            if (hadOwnTab) {
                // Forget it. `liveOwnedTerminal` only drops a tab that is *gone*, so one
                // that exists but refuses writes would stay owned forever: every later
                // command paying two Ctrl-Cs and 1.2 s, stopping whatever is in it, and
                // opening a BOSS tab anyway — on a loop, in silence.
                log("Our terminal tab refused the command; dropping it")
                ownedTerminal = null
            } else {
                log("No tabbed terminal to join; opening a BOSS tab")
            }
            fallBackToBossTab(queued)
        }
    }

    /**
     * Open a BOSS terminal tab for a command we couldn't hand to the owned one.
     *
     * This is the fallback [openTerminal]'s KDoc promises, reached late. By the time a
     * command drains, the terminal that existed when it was accepted may be gone — and
     * without this the only remaining option was telling the operator to run it by hand,
     * which is a worse outcome than the pre-reuse behaviour.
     */
    private fun fallBackToBossTab(queued: TerminalCommand) {
        // Guarded as a whole, including the toasts: this runs inside the consumer's catch
        // block, so anything escaping here kills the consumer (see [terminalCommands]).
        // `toastError` reaches the host's notification provider, so it is not exempt.
        runCatching {
            val ops = services.context.splitViewOperations
            if (ops == null) {
                services.toastError("No terminal available — run manually: ${queued.command}")
                return@runCatching
            }
            ops.openTab(
                TerminalTabInfo(
                    id = queued.id,
                    title = queued.title,
                    initialCommand = queued.command,
                    workingDirectory = queued.workingDir,
                ),
            )
            // The command does run here, just in its own BOSS tab.
            queued.onDelivered?.invoke()
        }.onFailure { failure ->
            log("Terminal fallback failed: $failure")
            runCatching {
                services.toastError("Couldn't reach the terminal — run manually: ${queued.command}")
            }
        }
    }

    /** Our tab, if it is still open; forgets it and returns null otherwise. */
    private fun liveOwnedTerminal(api: TerminalTabPluginAPI, tabs: List<ActiveTabData>): CommandTerminal? {
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
     * Reusing one tab makes these commands mutually exclusive, which is a real cost:
     * stopping a `helm upgrade --wait` does not just lose output, it leaves the release
     * in `pending-upgrade`. That is said prospectively by the `helm_*` tools, at the
     * moment a caller can still act on it, rather than by a toast after the fact — there
     * is no liveness signal to gate one on, so it fired on every command and became
     * noise (boss-plugins#11).
     *
     * **The wait before typing is a heuristic, and the known weak point here.**
     * `sendCommand` writes to the pty, so the shell has to be the one reading by the
     * time it lands; the API exposes no prompt or liveness signal to wait *for*, only a
     * sleep. What holds this plugin's pty is `helm install/upgrade --wait` and `kubectl
     * apply` against a slow cluster, neither of which dies instantly on SIGINT, so the
     * interrupt is sent twice with the wait split around it — clients generally escalate
     * on a second SIGINT, and a second Ctrl-C costs an idle shell nothing but a fresh
     * prompt line. Retrying the *command* instead would risk running it twice, which for
     * a `helm upgrade` is worse than losing it. A liveness query on the terminal API is
     * the real fix (boss-plugins#11).
     *
     * `kubectl exec -it` is **not** in that list, and cannot be: it forwards the
     * interrupt into the pod rather than dying. Those never reach this function — see
     * [TerminalCommandKind.Interactive].
     *
     * @return true if the command was typed.
     */
    private suspend fun deliverToOwnedTab(
        api: TerminalTabPluginAPI,
        owned: CommandTerminal,
        full: String,
        kind: TerminalCommandKind,
    ): Boolean {
        // Switch first, and this is what makes the interrupt safe: sendInterrupt and
        // sendCommand take no tabId, so both act on the terminal's *active* tab, and
        // without the switch a Ctrl-C could land on the user's own tab.
        //
        // Re-asserted before *every* write, not once up front.
        //
        // sendInterrupt and sendCommand take no tabId — they hit the terminal's *active*
        // tab — so the switch is what keeps them off another tab. Asserting it once and
        // then sleeping 1.2 s would mean acting on a state verified before the sleep, and
        // the active tab can change inside that window: the user clicking a sub-tab, made
        // *more* likely because the plugin has just pulled focus to the terminal
        // mid-work. Here that is worse than a stray build — the neighbouring tab may be
        // an `exec -it` session, so the command would be typed at a container's shell.
        //
        // There is no atomic switch-and-write in the API, so the window cannot be closed;
        // this shrinks it from 1.2 s to the gap between two adjacent calls, and is free on
        // the happy path because switchToTabById returns true when the tab is already
        // active and false only when the id is unknown — the correct bail-out.
        //
        // Two things verified against BossTerm rather than assumed (compose-ui
        // TabController, as of bossterm-compose 1.2.129):
        //
        // Safe from this background thread because BossTerm resolves the target by
        // *reading state*, not by waiting for recomposition: `activeTabIndex` is
        // `by mutableStateOf`, so a write from any thread lands in the global snapshot at
        // once, and `activeTab` is a plain getter over it.
        //
        // And `false` really does mean "no such tab": the already-active no-op early
        // return lives in the index-based overload switchToTabById delegates to.
        suspend fun focusOurTab(): Boolean =
            runCatching { api.switchToTab(owned.windowId, owned.terminalId, owned.tabId) }
                .getOrDefault(false)

        if (!focusOurTab()) return false
        runCatching { api.sendInterrupt(owned.windowId, owned.terminalId) }
        delay(INTERRUPT_ESCALATE_MS)
        if (!focusOurTab()) return false
        runCatching { api.sendInterrupt(owned.windowId, owned.terminalId) }
        delay(SHELL_REGAIN_LINE_MS)
        if (!focusOurTab()) return false

        // A command already waiting means this one is superseded the moment it is typed:
        // the consumer returns as soon as sendCommand lands, so the next item's interrupt
        // fires ~0 ms later and this gets no runway at all.
        //
        // Logged rather than skipped, deliberately. Dropping looks like the obvious win
        // and is not, because it is only safe for some commands: a superseded `helm
        // template` is redundant, but a superseded `helm uninstall` or `kubectl apply`
        // would be a state change silently skipped, which is worse than a noisy one that
        // at least starts. Acting on this needs that per-command distinction.
        if (pending.get() > 0) {
            log("Another command is already queued; this one will be interrupted almost immediately")
        }
        val sent = runCatching { api.sendCommand(owned.windowId, owned.terminalId, full) }.getOrDefault(false)
        if (sent) {
            // Deliberately not phrased as "interrupting the previous command": nothing
            // tells us whether one was still running, and a toast that cries wolf every
            // time is the one that gets tuned out on the occasion it matters.
            // Re-read rather than reusing the snapshot taken before ~1.2 s of delay:
            // focusing a tab that has since moved is benign but wrong, and this is free.
            focusHostTab(owned.terminalId, owned.windowId)
        }
        return sent
    }

    /**
     * A tab of its own for a command that must not share one, left unowned.
     *
     * Interactive sessions are inherently one per session, so a tab each is the right
     * shape rather than a regression toward tab-per-command — and it stays inside the
     * same BOSS tab, which is what the reuse was for.
     */
    private fun createSideTab(
        api: TerminalTabPluginAPI,
        tabs: List<ActiveTabData>,
        command: String,
        dir: String?,
    ): Boolean {
        val host = findTerminalHost(api, tabs) ?: return false
        val tabId = runCatching {
            api.createTab(
                windowId = host.windowId,
                terminalId = host.tabId,
                workingDirectory = dir,
                initialCommand = command,
            )
        }.getOrNull() ?: return false
        runCatching { api.switchToTab(host.windowId, host.tabId, tabId) }
        focusHostTab(host.tabId, host.windowId)
        return true
    }

    /** Create the tab we will own from now on, running [command] as it starts. */
    private fun createOwnedTab(
        api: TerminalTabPluginAPI,
        tabs: List<ActiveTabData>,
        command: String,
        kind: TerminalCommandKind,
    ): Boolean {
        val host = findTerminalHost(api, tabs) ?: return false
        val newTabId = runCatching {
            api.createTab(
                windowId = host.windowId,
                terminalId = host.tabId,
                // No workingDirectory: `command` carries its own cd, so this path and the
                // reuse path agree on where a command runs.
                initialCommand = command,
            )
        }.getOrNull() ?: return false

        ownedTerminal = CommandTerminal(host.windowId, host.tabId, newTabId)
        runCatching { api.switchToTab(host.windowId, host.tabId, newTabId) }
        focusHostTab(host.tabId, host.windowId)
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
    private fun findTerminalHost(api: TerminalTabPluginAPI, tabs: List<ActiveTabData>): ai.rever.boss.plugin.api.ActiveTabData? {
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
    private fun focusHostTab(terminalId: String, windowId: String) {
        val tabs = services.context.activeTabsProvider?.activeTabs?.value ?: return
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
         * How long to let the shell regain the line after the last Ctrl-C before typing.
         * terminal-tab's own re-run delay defaults to 500 ms, but that is for an *idle*
         * tab; here a live process may still be tearing down, so the total wait is
         * longer and split around a second interrupt.
         */
        private const val SHELL_REGAIN_LINE_MS = 800L

        /** Gap before the second Ctrl-C, which is what forces a stubborn client to quit. */
        private const val INTERRUPT_ESCALATE_MS = 400L

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
