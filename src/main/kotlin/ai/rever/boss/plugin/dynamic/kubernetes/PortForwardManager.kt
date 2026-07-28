package ai.rever.boss.plugin.dynamic.kubernetes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket

/** Identity of a forward: one per (context, namespace, target, remote port). */
data class ForwardKey(
    val context: String?,
    val namespace: String,
    val ref: String,
    val remotePort: Int,
)

/** Lifecycle of one supervised forward. */
enum class ForwardStatus { STARTING, ACTIVE, RETRYING, FAILED }

data class ForwardInfo(
    val key: ForwardKey,
    val localPort: Int,
    val status: ForwardStatus,
    val message: String = "",
    val restarts: Int = 0,
) {
    val localUrl: String get() = "http://localhost:$localPort"

    val display: String get() = "$localPort→${key.remotePort}"
}

/**
 * Supervises `kubectl port-forward` processes.
 *
 * Port-forwards are the only way to reach a ClusterIP service from a laptop, and
 * they are notoriously fragile: the connection drops when the target pod is
 * replaced, when the apiserver recycles the SPDY stream, or when the laptop
 * sleeps, and kubectl simply exits. Every user of kubectl has a dead forward they
 * didn't notice.
 *
 * So each forward gets a supervisor that re-runs kubectl on the *same local port*
 * (so a preview URL stays valid across restarts) with backoff, and publishes its
 * status so the UI can say "retrying" instead of quietly serving nothing.
 */
class PortForwardManager(
    private val scope: CoroutineScope,
    private val engine: KubeEngine,
) {
    private val _forwards = MutableStateFlow<Map<ForwardKey, ForwardInfo>>(emptyMap())
    val forwards: StateFlow<Map<ForwardKey, ForwardInfo>> = _forwards.asStateFlow()

    private val jobs = mutableMapOf<ForwardKey, Job>()

    /**
     * The live kubectl process per forward.
     *
     * Cancelling the supervisor job *should* reap its child through the completion
     * handler in [KubectlCli.stream], but in testing a `kubectl port-forward`
     * outlived its cancelled job and kept the port open. Keeping the handle lets
     * teardown be a direct kill, which is verifiable rather than hopeful.
     */
    private val processes = mutableMapOf<ForwardKey, Process>()

    /**
     * Start forwarding [ref] (e.g. `service/api`, `pod/api-abc`) and return the
     * chosen local port. Idempotent: an existing forward for the same key is
     * reused rather than duplicated.
     */
    fun start(ref: String, remotePort: Int, preferredLocalPort: Int? = null): Int {
        val target = engine.target.value
        val key = ForwardKey(target.context, target.namespace, ref, remotePort)
        _forwards.value[key]?.let { return it.localPort }

        val localPort = preferredLocalPort?.takeIf { isFree(it) } ?: freePort()
        _forwards.update { it + (key to ForwardInfo(key, localPort, ForwardStatus.STARTING)) }
        jobs[key] = scope.launch { supervise(key, localPort) }
        return localPort
    }

    fun stop(key: ForwardKey) {
        // Cancel first so the supervisor can't restart the process we're about to
        // kill, then kill it outright.
        jobs.remove(key)?.cancel()
        processes.remove(key)?.destroyForcibly()
        _forwards.update { it - key }
    }

    fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        processes.values.forEach { it.destroyForcibly() }
        processes.clear()
        _forwards.value = emptyMap()
    }

    fun forwardFor(ref: String, remotePort: Int): ForwardInfo? {
        val target = engine.target.value
        return _forwards.value[ForwardKey(target.context, target.namespace, ref, remotePort)]
    }

    fun dispose() = stopAll()

    private suspend fun supervise(key: ForwardKey, localPort: Int) {
        var backoffMs = MIN_BACKOFF_MS
        var restarts = 0
        while (scope.isActive) {
            update(key) { it.copy(status = if (restarts == 0) ForwardStatus.STARTING else ForwardStatus.RETRYING) }

            var sawReady = false
            val exit = KubectlCli.stream(
                args = engine.args(listOf("port-forward", key.ref, "$localPort:${key.remotePort}")),
                onProcess = { process -> processes[key] = process },
            ) { line ->
                // kubectl prints "Forwarding from 127.0.0.1:8080 -> 80" once the
                // listener is actually up; treat that as the readiness signal
                // rather than assuming the process starting means it works.
                if (!sawReady && line.contains("Forwarding from")) {
                    sawReady = true
                    update(key) { it.copy(status = ForwardStatus.ACTIVE, message = "") }
                }
                if (line.contains("error", ignoreCase = true) || line.contains("lost connection")) {
                    update(key) { it.copy(message = line.take(200)) }
                }
            }

            if (!scope.isActive) return
            if (exit == KubectlExec.EXIT_CLI_MISSING) {
                update(key) { it.copy(status = ForwardStatus.FAILED, message = "kubectl not found") }
                return
            }

            restarts++
            if (restarts > MAX_RESTARTS) {
                update(key) {
                    it.copy(
                        status = ForwardStatus.FAILED,
                        message = "gave up after $MAX_RESTARTS restarts",
                        restarts = restarts,
                    )
                }
                return
            }
            update(key) { it.copy(status = ForwardStatus.RETRYING, restarts = restarts) }
            delay(backoffMs)
            // A forward that came up and ran for a while deserves a fresh, short
            // backoff; one that never came up should back off harder.
            backoffMs = if (sawReady) MIN_BACKOFF_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun update(key: ForwardKey, transform: (ForwardInfo) -> ForwardInfo) {
        _forwards.update { current ->
            val existing = current[key] ?: return@update current
            current + (key to transform(existing))
        }
    }

    private companion object {
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 15_000L
        const val MAX_RESTARTS = 20

        /**
         * An unused local port. Racy by nature — something can take it between
         * here and kubectl binding — but kubectl fails loudly and the supervisor
         * retries.
         */
        fun freePort(): Int = runCatching { ServerSocket(0).use { it.localPort } }.getOrDefault(18080)

        fun isFree(port: Int): Boolean = runCatching { ServerSocket(port).use { true } }.getOrDefault(false)
    }
}
