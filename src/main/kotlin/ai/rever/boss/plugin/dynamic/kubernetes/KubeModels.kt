package ai.rever.boss.plugin.dynamic.kubernetes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Lenient decoder for `kubectl ... -o json`. Kubernetes objects carry far more
 * than we model and grow between versions, so unknown keys must never fail a list.
 */
internal val KubeJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * Whether the cluster behind the selected context can be used.
 *
 * `Unreachable` and `Forbidden` are ordinary states with their own UI, not errors
 * to swallow: an unreachable cluster is the normal condition when a local
 * Kubernetes is switched off or a VPN is down.
 */
sealed interface ClusterState {
    data object Unknown : ClusterState

    /** No `kubectl` binary anywhere on PATH or in the usual install dirs. */
    data object KubectlMissing : ClusterState

    /** kubectl present, but the kubeconfig has no usable current context. */
    data object NoContext : ClusterState

    data class Unreachable(val message: String) : ClusterState

    data class Forbidden(val message: String) : ClusterState

    data class Ready(val serverVersion: String) : ClusterState

    data class Error(val message: String) : ClusterState
}

/** What the sidebar is pointed at. Never written back to the kubeconfig. */
data class KubeTarget(
    val context: String?,
    val namespace: String,
) {
    companion object {
        const val ALL_NAMESPACES = "*"
        val DEFAULT = KubeTarget(context = null, namespace = "default")
    }

    val isAllNamespaces: Boolean get() = namespace == ALL_NAMESPACES

    val display: String get() = "${context ?: "no context"} / ${if (isAllNamespaces) "all namespaces" else namespace}"
}

// ------------------------------------------------------------------ envelopes

@Serializable
internal data class KubeList<T>(val items: List<T> = emptyList())

@Serializable
internal data class ObjectMeta(
    val name: String = "",
    val namespace: String = "",
    val creationTimestamp: String = "",
    val labels: Map<String, String> = emptyMap(),
)

// ---------------------------------------------------------------------- pods

data class PodInfo(
    val name: String,
    val namespace: String,
    val phase: String,
    val readyContainers: Int,
    val totalContainers: Int,
    val restarts: Int,
    val node: String,
    val createdAt: String,
    val containers: List<String>,
    val initContainers: List<String>,
) {
    val ready: String get() = "$readyContainers/$totalContainers"

    val isRunning: Boolean get() = phase.equals("Running", ignoreCase = true) && readyContainers == totalContainers

    val isFailing: Boolean
        get() = phase.equals("Failed", ignoreCase = true) ||
            phase.equals("CrashLoopBackOff", ignoreCase = true) ||
            (phase.equals("Running", ignoreCase = true) && readyContainers < totalContainers)

    /** Multi-container pods need a picker before `kubectl logs` will work. */
    val needsContainerChoice: Boolean get() = containers.size > 1
}

@Serializable
internal data class RawPod(
    val metadata: ObjectMeta = ObjectMeta(),
    val spec: Spec = Spec(),
    val status: Status = Status(),
) {
    @Serializable
    data class Spec(
        val nodeName: String = "",
        val containers: List<Named> = emptyList(),
        val initContainers: List<Named> = emptyList(),
    )

    @Serializable
    data class Named(val name: String = "")

    @Serializable
    data class Status(
        val phase: String = "",
        val containerStatuses: List<ContainerStatus> = emptyList(),
    )

    @Serializable
    data class ContainerStatus(
        val name: String = "",
        val ready: Boolean = false,
        val restartCount: Int = 0,
        val state: State = State(),
    )

    @Serializable
    data class State(val waiting: Waiting? = null)

    @Serializable
    data class Waiting(val reason: String = "")

    fun toInfo(): PodInfo {
        // A pod stuck in CrashLoopBackOff still reports phase=Running; the waiting
        // reason is what a human actually wants to see in the row.
        val waitingReason = status.containerStatuses.firstNotNullOfOrNull { it.state.waiting?.reason?.ifBlank { null } }
        return PodInfo(
            name = metadata.name,
            namespace = metadata.namespace,
            phase = waitingReason ?: status.phase,
            readyContainers = status.containerStatuses.count { it.ready },
            totalContainers = status.containerStatuses.size.takeIf { it > 0 } ?: spec.containers.size,
            restarts = status.containerStatuses.sumOf { it.restartCount },
            node = spec.nodeName,
            createdAt = metadata.creationTimestamp,
            containers = spec.containers.map { it.name },
            initContainers = spec.initContainers.map { it.name },
        )
    }
}

// ----------------------------------------------------------------- workloads

/** Deployments, StatefulSets and DaemonSets share one row shape. */
data class WorkloadInfo(
    val kind: String,
    val name: String,
    val namespace: String,
    val ready: Int,
    val desired: Int,
    val images: List<String>,
    val createdAt: String,
) {
    val isHealthy: Boolean get() = desired > 0 && ready == desired

    val readyDisplay: String get() = "$ready/$desired"

    /** `kubectl` type prefix, e.g. `deployment/api`. */
    val ref: String get() = "${kind.lowercase()}/$name"

    val isScalable: Boolean get() = !kind.equals("DaemonSet", ignoreCase = true)
}

@Serializable
internal data class RawWorkload(
    val kind: String = "",
    val metadata: ObjectMeta = ObjectMeta(),
    val spec: Spec = Spec(),
    val status: Status = Status(),
) {
    @Serializable
    data class Spec(val replicas: Int? = null, val template: Template = Template())

    @Serializable
    data class Template(val spec: PodSpec = PodSpec())

    @Serializable
    data class PodSpec(val containers: List<Container> = emptyList())

    @Serializable
    data class Container(val image: String = "")

    @Serializable
    data class Status(
        val readyReplicas: Int = 0,
        val replicas: Int = 0,
        // DaemonSets use a different vocabulary for the same two numbers.
        val numberReady: Int = 0,
        val desiredNumberScheduled: Int = 0,
    )

    fun toInfo(kindOverride: String): WorkloadInfo {
        val isDaemonSet = kindOverride.equals("DaemonSet", ignoreCase = true)
        return WorkloadInfo(
            kind = kindOverride,
            name = metadata.name,
            namespace = metadata.namespace,
            ready = if (isDaemonSet) status.numberReady else status.readyReplicas,
            desired = if (isDaemonSet) status.desiredNumberScheduled else (spec.replicas ?: status.replicas),
            images = spec.template.spec.containers.map { it.image }.filter { it.isNotBlank() },
            createdAt = metadata.creationTimestamp,
        )
    }
}

// ------------------------------------------------------------------ services

data class ServicePort(
    val name: String,
    val port: Int,
    val targetPort: String,
    val protocol: String,
) {
    val display: String get() = if (name.isBlank()) "$port/$protocol" else "$name:$port/$protocol"
}

data class ServiceInfo(
    val name: String,
    val namespace: String,
    val type: String,
    val clusterIp: String,
    val ports: List<ServicePort>,
    val createdAt: String,
) {
    val ref: String get() = "service/$name"

    /** The port a preview should forward to: the lowest TCP port. */
    val primaryPort: ServicePort?
        get() = ports.filter { it.protocol.equals("TCP", ignoreCase = true) }.minByOrNull { it.port }

    val isHeadless: Boolean get() = clusterIp == "None"
}

@Serializable
internal data class RawService(
    val metadata: ObjectMeta = ObjectMeta(),
    val spec: Spec = Spec(),
) {
    @Serializable
    data class Spec(
        val type: String = "",
        val clusterIP: String = "",
        val ports: List<Port> = emptyList(),
    )

    @Serializable
    data class Port(
        val name: String = "",
        val port: Int = 0,
        // targetPort is an int OR a named port string, so it can only be modeled
        // as a raw element.
        val targetPort: JsonElement? = null,
        val protocol: String = "TCP",
    )

    fun toInfo() = ServiceInfo(
        name = metadata.name,
        namespace = metadata.namespace,
        type = spec.type.ifBlank { "ClusterIP" },
        clusterIp = spec.clusterIP,
        ports = spec.ports.map { p ->
            ServicePort(
                name = p.name,
                port = p.port,
                targetPort = p.targetPort.renderScalar().ifBlank { p.port.toString() },
                protocol = p.protocol.ifBlank { "TCP" },
            )
        },
        createdAt = metadata.creationTimestamp,
    )
}

/** `targetPort` may be `8080` or `"http"`; render either as a plain string. */
private fun JsonElement?.renderScalar(): String = when (this) {
    null -> ""
    is JsonPrimitive -> content
    else -> toString()
}

// ------------------------------------------------------- other listed kinds

data class IngressInfo(
    val name: String,
    val namespace: String,
    val className: String,
    val hosts: List<String>,
    val createdAt: String,
)

@Serializable
internal data class RawIngress(
    val metadata: ObjectMeta = ObjectMeta(),
    val spec: Spec = Spec(),
) {
    @Serializable
    data class Spec(val ingressClassName: String = "", val rules: List<Rule> = emptyList())

    @Serializable
    data class Rule(val host: String = "")

    fun toInfo() = IngressInfo(
        name = metadata.name,
        namespace = metadata.namespace,
        className = spec.ingressClassName,
        hosts = spec.rules.map { it.host }.filter { it.isNotBlank() },
        createdAt = metadata.creationTimestamp,
    )
}

data class JobInfo(
    val name: String,
    val namespace: String,
    val kind: String,
    val detail: String,
    val createdAt: String,
) {
    val ref: String get() = "${kind.lowercase()}/$name"
}

@Serializable
internal data class RawJob(
    val metadata: ObjectMeta = ObjectMeta(),
    val spec: Spec = Spec(),
    val status: Status = Status(),
) {
    @Serializable
    data class Spec(val completions: Int? = null, val schedule: String = "", val suspend: Boolean = false)

    @Serializable
    data class Status(val succeeded: Int = 0, val active: Int = 0, val failed: Int = 0)

    fun toJob() = JobInfo(
        name = metadata.name,
        namespace = metadata.namespace,
        kind = "Job",
        detail = buildList {
            add("${status.succeeded}/${spec.completions ?: 1} complete")
            if (status.active > 0) add("${status.active} active")
            if (status.failed > 0) add("${status.failed} failed")
        }.joinToString(" · "),
        createdAt = metadata.creationTimestamp,
    )

    fun toCronJob() = JobInfo(
        name = metadata.name,
        namespace = metadata.namespace,
        kind = "CronJob",
        detail = buildList {
            add(spec.schedule.ifBlank { "no schedule" })
            if (spec.suspend) add("suspended")
        }.joinToString(" · "),
        createdAt = metadata.creationTimestamp,
    )
}

data class ConfigMapInfo(
    val name: String,
    val namespace: String,
    val createdAt: String,
)

data class PvcInfo(
    val name: String,
    val namespace: String,
    val phase: String,
    val capacity: String,
    val storageClass: String,
)

@Serializable
internal data class RawPvc(
    val metadata: ObjectMeta = ObjectMeta(),
    val spec: Spec = Spec(),
    val status: Status = Status(),
) {
    @Serializable
    data class Spec(val storageClassName: String = "")

    @Serializable
    data class Status(val phase: String = "", val capacity: Map<String, String> = emptyMap())

    fun toInfo() = PvcInfo(
        name = metadata.name,
        namespace = metadata.namespace,
        phase = status.phase,
        capacity = status.capacity["storage"].orEmpty(),
        storageClass = spec.storageClassName,
    )
}

/**
 * A Secret, deliberately without any value-bearing field.
 *
 * There is no `data` property here and the list query never asks the server for
 * one — you cannot leak what you never deserialize. Key names and sizes, when
 * wanted, come from `kubectl describe secret`, which prints
 * `key: <n> bytes` and never the value itself.
 */
data class SecretInfo(
    val name: String,
    val namespace: String,
    val type: String,
    val createdAt: String,
)

data class NamespaceInfo(val name: String, val phase: String)

@Serializable
internal data class RawNamespace(
    val metadata: ObjectMeta = ObjectMeta(),
    val status: Status = Status(),
) {
    @Serializable
    data class Status(val phase: String = "")

    fun toInfo() = NamespaceInfo(metadata.name, status.phase)
}

data class EventInfo(
    val type: String,
    val reason: String,
    val message: String,
    val objectRef: String,
    val count: Int,
    val lastSeen: String,
) {
    val isWarning: Boolean get() = type.equals("Warning", ignoreCase = true)
}

@Serializable
internal data class RawEvent(
    val type: String = "",
    val reason: String = "",
    val message: String = "",
    val count: Int = 1,
    val lastTimestamp: String = "",
    val eventTime: String = "",
    val involvedObject: Involved = Involved(),
) {
    @Serializable
    data class Involved(val kind: String = "", val name: String = "")

    fun toInfo() = EventInfo(
        type = type,
        reason = reason,
        message = message.replace('\n', ' ').trim(),
        objectRef = listOf(involvedObject.kind, involvedObject.name)
            .filter { it.isNotBlank() }
            .joinToString("/"),
        count = count,
        lastSeen = lastTimestamp.ifBlank { eventTime },
    )
}

/** One row of `kubectl api-resources`, used to populate the CRD picker. */
data class ApiResourceInfo(
    val name: String,
    val shortNames: List<String>,
    val apiVersion: String,
    val namespaced: Boolean,
    val kind: String,
) {
    val isCustom: Boolean get() = apiVersion.contains('.') && !apiVersion.startsWith("v1")
}

/** A kubeconfig context. Selecting one never writes to the kubeconfig. */
data class ContextInfo(
    val name: String,
    val cluster: String,
    val namespace: String,
    val isCurrent: Boolean,
)

/** A Kubernetes manifest or kustomization found in the open project. */
data class ManifestArtifact(
    val file: File,
    val kind: Kind,
    val relativePath: String,
) {
    enum class Kind { MANIFEST, KUSTOMIZATION }

    /** `-f file` for a plain manifest, `-k dir` for a kustomization. */
    val applyArgs: List<String>
        get() = when (kind) {
            Kind.MANIFEST -> listOf("-f", file.absolutePath)
            Kind.KUSTOMIZATION -> listOf("-k", file.parentFile?.absolutePath ?: file.absolutePath)
        }
}

// ---------------------------------------------------------------- parsing

/** Decode a `kubectl get -o json` list into its typed items. */
internal inline fun <reified T> parseItems(stdout: String): List<T> {
    val text = stdout.trim()
    if (!text.startsWith("{")) return emptyList()
    return runCatching { KubeJson.decodeFromString<KubeList<T>>(text).items }.getOrDefault(emptyList())
}
