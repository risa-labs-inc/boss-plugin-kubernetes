package ai.rever.boss.plugin.dynamic.kubernetes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Outcome of one `helm` invocation. */
data class HelmExec(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0

    val cleanError: String
        get() = stderr.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("WARNING: Kubernetes configuration file is") }
            .distinct()
            .joinToString("\n")
            .ifBlank { stdout.trim() }

    val message: String get() = cleanError

    /** helm couldn't reach the cluster (it surfaces kubectl-style connection errors). */
    val unreachable: Boolean
        get() = !ok && listOf(
            "connection refused",
            "connection to the server",
            "was refused",
            "Kubernetes cluster unreachable",
            "dial tcp",
            "no such host",
            "i/o timeout",
        ).any { stderr.contains(it, ignoreCase = true) }

    val notFound: Boolean
        get() = !ok && listOf("release: not found", "not found").any { stderr.contains(it, ignoreCase = true) }

    companion object {
        const val EXIT_CLI_MISSING = -1
        const val EXIT_TIMEOUT = -2
    }
}

/** Whether helm is usable, mirroring [ClusterState] for the kubectl side. */
sealed interface HelmState {
    data object Unknown : HelmState

    /** No `helm` binary anywhere on PATH or in the usual install dirs. */
    data object Missing : HelmState

    data class Ready(val version: String, val major: Int) : HelmState

    data class Error(val message: String) : HelmState
}

/**
 * The single place this plugin shells out to `helm`.
 *
 * Same discipline as [KubectlCli]: absolute binary, widened child PATH (cloud
 * kubeconfigs shell out to credential plugins), argv lists rather than shell
 * strings.
 *
 * **helm's target flags differ from kubectl's**: it is `--kube-context`, not
 * `--context`. Getting that wrong fails with a bare "unknown flag", so target
 * flags are built here rather than reusing `KubeEngine.args`.
 */
object HelmCli {

    private val extraDirs: List<String> by lazy {
        val home = System.getProperty("user.home").orEmpty()
        listOf(
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "$home/.local/bin",
            "$home/bin",
            "$home/google-cloud-sdk/bin",
            "/opt/homebrew/share/google-cloud-sdk/bin",
            "/usr/bin",
            "/bin",
        ).filter { it.isNotBlank() }
    }

    @Volatile
    private var cached: File? = null

    fun resolve(): File? {
        cached?.let { if (it.isFile && it.canExecute()) return it }
        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        val found = (pathDirs + extraDirs).asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, "helm") }
            .firstOrNull { it.isFile && it.canExecute() }
        cached = found
        return found
    }

    fun isInstalled(): Boolean = resolve() != null

    private fun ProcessBuilder.withResolvedPath(): ProcessBuilder = apply {
        val current = environment()["PATH"].orEmpty()
        environment()["PATH"] = (extraDirs + current)
            .filter { it.isNotBlank() }
            .joinToString(File.pathSeparator)
    }

    suspend fun exec(
        args: List<String>,
        workingDir: File? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): HelmExec = withContext(Dispatchers.IO) {
        val exe = resolve() ?: return@withContext HelmExec(
            HelmExec.EXIT_CLI_MISSING,
            "",
            "helm was not found on this machine.",
        )

        val process = ProcessBuilder(listOf(exe.absolutePath) + args)
            .directory(workingDir)
            .withResolvedPath()
            .start()

        val killer = currentCoroutineContext().job.invokeOnCompletion { process.destroyForcibly() }
        try {
            process.outputStream.close()
            val errText = async { runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("") }
            val outText = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
            val err = errText.await()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                HelmExec(HelmExec.EXIT_TIMEOUT, outText, "helm ${args.firstOrNull().orEmpty()} timed out")
            } else {
                HelmExec(process.exitValue(), outText, err)
            }
        } finally {
            killer.dispose()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /**
     * Probe helm and remember its major version.
     *
     * `helm version --client` was **removed in Helm 4** (verified: "Error: unknown
     * flag: --client"), so the probe uses `--template`, which works on both 3 and 4.
     */
    suspend fun probe(): HelmState {
        if (!isInstalled()) {
            state = HelmState.Missing
            return HelmState.Missing
        }
        val result = exec(listOf("version", "--template", "{{.Version}}"), timeoutMs = PROBE_TIMEOUT_MS)
        val next = if (result.ok) {
            val version = result.stdout.trim().ifBlank { "unknown" }
            HelmState.Ready(version, majorOf(version))
        } else {
            HelmState.Error(result.cleanError.take(300))
        }
        state = next
        return next
    }

    @Volatile
    var state: HelmState = HelmState.Unknown
        private set

    private fun majorOf(version: String): Int =
        Regex("^v?(\\d+)").find(version.trim())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 3

    /** Major version, defaulting to 4 before the first probe completes. */
    private val major: Int get() = (state as? HelmState.Ready)?.major ?: 4

    /**
     * Flag dialect, because Helm 4 renamed these and the plugin must work against
     * whichever binary the user has:
     *
     * - `--atomic` → `--rollback-on-failure` (verified present in 4.2.3; `--atomic`
     *   is still accepted there as a deprecated alias, but not guaranteed in 5)
     * - `--force` → `--force-replace`
     *
     * `--wait` also changed meaning in 4: it is now a WaitStrategy
     * (`watcher|hookOnly|legacy`, defaulting to `hookOnly` when the flag is absent)
     * rather than a boolean, and the `watcher` strategy needs the `watch` RBAC verb.
     * Bare `--wait` is valid in both, so it is used as-is.
     */
    fun rollbackOnFailureFlag(): String = if (major >= 4) "--rollback-on-failure" else "--atomic"

    fun forceReplaceFlag(): String = if (major >= 4) "--force-replace" else "--force"

    /**
     * Target flags for a cluster-touching helm command.
     *
     * helm spells the context flag `--kube-context`, unlike kubectl's `--context`.
     * As on the kubectl side, nothing is written back to the kubeconfig.
     */
    fun targetArgs(target: KubeTarget, namespaced: Boolean = true): List<String> = buildList {
        target.context?.let { addAll(listOf("--kube-context", it)) }
        if (namespaced && !target.isAllNamespaces) addAll(listOf("-n", target.namespace))
    }

    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val PROBE_TIMEOUT_MS = 8_000L
    const val LONG_TIMEOUT_MS = 120_000L
}
