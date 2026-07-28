package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.TabSplitMode
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
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

    fun openTerminal(
        id: String,
        title: String,
        command: String,
        workingDir: String?,
        location: OpenLocation = OpenLocation.NEW_TAB,
    ): Boolean {
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
