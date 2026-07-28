package ai.rever.boss.plugin.dynamic.kubernetes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Outcome of one `kubectl` invocation. */
data class KubectlExec(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0

    /**
     * stderr with kubectl's retry noise stripped. An unreachable cluster emits a
     * wall of `E0728 ... memcache.go:265] "Unhandled Error"` lines that say the
     * same thing five times — never show those to a user.
     */
    val cleanError: String
        get() = stderr.lineSequence()
            .filterNot { it.contains("memcache.go") }
            .filterNot { it.startsWith("E0") && it.contains("Unhandled Error") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .ifBlank { stdout.trim() }

    val message: String get() = cleanError

    /** The API server could not be reached at all. */
    val unreachable: Boolean
        get() = !ok && UNREACHABLE_MARKERS.any { stderr.contains(it, ignoreCase = true) }

    /** Reached the server, but this identity may not do that. */
    val forbidden: Boolean
        get() = !ok && FORBIDDEN_MARKERS.any { stderr.contains(it, ignoreCase = true) }

    /** The kubeconfig names a context/cluster that isn't usable. */
    val badContext: Boolean
        get() = !ok && BAD_CONTEXT_MARKERS.any { stderr.contains(it, ignoreCase = true) }

    companion object {
        const val EXIT_CLI_MISSING = -1
        const val EXIT_TIMEOUT = -2

        /**
         * kubectl phrases "cannot reach the API server" at least two different
         * ways depending on the subcommand, and they share no common substring:
         *
         * - `get`:     `... dial tcp 127.0.0.1:6443: connect: connection refused`
         * - `version`: `The connection to the server 127.0.0.1:1 was refused ...`
         *
         * Matching only "connection refused" silently misses the second form and
         * mislabels a plainly-down cluster as an unknown error, so both phrasings
         * are listed explicitly. Verified against kubectl v1.35.
         */
        private val UNREACHABLE_MARKERS = listOf(
            "connection refused",
            "connection to the server",
            "was refused",
            "did you specify the right host or port",
            "Unable to connect to the server",
            "couldn't get current server API group list",
            "dial tcp",
            "no such host",
            "i/o timeout",
            "TLS handshake timeout",
            "context deadline exceeded",
        )
        private val FORBIDDEN_MARKERS = listOf(
            "is forbidden",
            "Unauthorized",
            "error: You must be logged in",
            "Forbidden",
        )
        private val BAD_CONTEXT_MARKERS = listOf(
            "context was not found",
            "no context exists",
            "current-context is not set",
            "no configuration has been provided",
        )
    }
}

/**
 * The single place this plugin shells out to `kubectl`.
 *
 * Three rules hold throughout:
 *
 * 1. **Absolute binary + widened child PATH.** `ProcessBuilder` resolves bare names
 *    against the *parent's* PATH, which is nearly empty when the packaged host
 *    launches from Finder. On this machine kubectl is in `/opt/homebrew/bin`
 *    (Docker was in `/usr/local/bin`), so both must be searched. The child PATH is
 *    widened too, because cloud kubeconfigs shell out to credential plugins
 *    (`gke-gcloud-auth-plugin`, `aws`) that must themselves be findable.
 * 2. **argv lists, never a shell string.** Names, namespaces and labels come from
 *    cluster data and user input; a `List<String>` means there is no shell.
 * 3. **Always a request timeout.** Without `--request-timeout` an unreachable
 *    cluster makes every call hang for the better part of a minute.
 */
object KubectlCli {

    /** Where kubectl and credential plugins live, beyond PATH. */
    private val extraDirs: List<String> by lazy {
        val home = System.getProperty("user.home").orEmpty()
        listOf(
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "$home/.local/bin",
            "$home/bin",
            // Docker Desktop bundles its own kubectl; last resort, since it can
            // be a different minor version than the daemon.
            "/Applications/Docker.app/Contents/Resources/bin",
            // Credential plugins for cloud clusters.
            "$home/google-cloud-sdk/bin",
            "/opt/homebrew/share/google-cloud-sdk/bin",
            "/usr/bin",
            "/bin",
        ).filter { it.isNotBlank() }
    }

    @Volatile
    private var cached: File? = null

    /** Absolute path of `kubectl`, or null when it isn't installed. */
    fun resolve(binary: String = "kubectl"): File? {
        if (binary == "kubectl") {
            cached?.let { if (it.isFile && it.canExecute()) return it }
        }
        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        val found = (pathDirs + extraDirs).asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, binary) }
            .firstOrNull { it.isFile && it.canExecute() }
        if (binary == "kubectl") cached = found
        return found
    }

    fun isInstalled(): Boolean = resolve() != null

    private fun ProcessBuilder.withResolvedPath(): ProcessBuilder = apply {
        val current = environment()["PATH"].orEmpty()
        environment()["PATH"] = (extraDirs + current)
            .filter { it.isNotBlank() }
            .joinToString(File.pathSeparator)
    }

    /**
     * Run `kubectl <args>` to completion.
     *
     * Cancelling the caller destroys the child immediately via a job completion
     * handler — closing its streams is what unblocks the reads below. Without it a
     * cancelled call leaks a live kubectl.
     */
    suspend fun exec(
        args: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): KubectlExec = withContext(Dispatchers.IO) {
        val exe = resolve() ?: return@withContext KubectlExec(
            KubectlExec.EXIT_CLI_MISSING,
            "",
            "kubectl was not found on this machine.",
        )

        val process = ProcessBuilder(listOf(exe.absolutePath) + args)
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
                KubectlExec(KubectlExec.EXIT_TIMEOUT, outText, "kubectl ${args.firstOrNull().orEmpty()} timed out")
            } else {
                KubectlExec(process.exitValue(), outText, err)
            }
        } finally {
            killer.dispose()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /**
     * Run a long-lived streaming command (`logs -f`, `get --watch`,
     * `port-forward`) delivering stdout+stderr line by line until it ends or the
     * caller is cancelled. Suspends for the stream's lifetime; launch it yourself.
     */
    suspend fun stream(
        args: List<String>,
        onProcess: ((Process) -> Unit)? = null,
        onLine: suspend (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val exe = resolve() ?: return@withContext KubectlExec.EXIT_CLI_MISSING

        val process = ProcessBuilder(listOf(exe.absolutePath) + args)
            .redirectErrorStream(true)
            .withResolvedPath()
            .start()

        // Hand the handle to the caller so long-lived streams can be reaped
        // deterministically. Coroutine cancellation alone proved insufficient for
        // port-forwards: a surviving kubectl kept serving a port after its
        // supervisor job was cancelled, so callers that must guarantee teardown
        // kill the process directly rather than trusting the completion handler.
        onProcess?.invoke(process)

        val killer = currentCoroutineContext().job.invokeOnCompletion { process.destroyForcibly() }
        try {
            process.outputStream.close()
            val reader = process.inputStream.bufferedReader()
            while (true) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                onLine(line)
            }
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
            if (process.isAlive) KubectlExec.EXIT_TIMEOUT else process.exitValue()
        } finally {
            killer.dispose()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    const val DEFAULT_TIMEOUT_MS = 15_000L

    /** Short timeout for liveness probes, so an unreachable cluster fails fast. */
    const val PROBE_TIMEOUT_MS = 6_000L

    /** Appended to every server-touching call. */
    fun requestTimeout(seconds: Int = 5): String = "--request-timeout=${seconds}s"
}
