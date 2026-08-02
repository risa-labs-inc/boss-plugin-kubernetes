package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import java.io.File

/**
 * `k8s_*` tools for in-terminal agents, surfacing as `mcp__boss__k8s_*`.
 *
 * Two rules hold across all of them:
 *
 * - **Every answer states the context and namespace it came from.** An agent
 *   reading "3 pods" without knowing which cluster is a hazard, not a help.
 * - **Mutating tools are permission-gated** via `withRbac` (never `.copy()`, which
 *   silently drops the gate): `kubernetes.manage` for cluster mutation, and
 *   `kubernetes.exec` for `k8s_exec` alone, which is a shell rather than an
 *   operation. Admins hold every permission, so a signed-in admin sees them
 *   immediately; with nobody signed in, a gated tool is not exposed at all.
 *
 * A handful of `readOnly = false` tools are deliberately ungated — they select a
 * target, open a tab, or move bytes locally rather than changing the cluster. That
 * set is not a matter of taste: `McpToolGatingTest` enumerates the real tool
 * objects and fails unless every mutating tool is either gated or on its
 * explicitly-justified allow-list. Add a mutating tool without deciding and the
 * build breaks.
 */
class KubeMcpToolProvider(
    override val providerId: String,
    private val services: KubeServices,
) : McpToolProvider {

    private val engine get() = services.engine

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "k8s_contexts",
            description = "List kubeconfig contexts and which one this plugin is pointed at. " +
                "Selecting a context here never modifies the kubeconfig.",
            handler = McpToolHandler {
                engine.refreshContexts()
                val target = engine.target.value
                val rows = engine.contexts.value
                if (rows.isEmpty()) return@McpToolHandler McpToolResult("No contexts in the kubeconfig.")
                McpToolResult(
                    buildString {
                        appendLine("selected: ${target.display}")
                        rows.forEach { c ->
                            appendLine(
                                "${if (c.name == target.context) "*" else " "} ${c.name}  cluster=${c.cluster}" +
                                    if (c.namespace.isNotBlank()) "  ns=${c.namespace}" else "",
                            )
                        }
                    }.trim(),
                )
            },
        ),

        McpToolDefinition(
            name = "k8s_use_context",
            description = "Point this plugin at a different context and/or namespace. Local to BOSS — " +
                "your shells' kubectl default is left alone.",
            inputSchema = """
                {"type":"object","properties":{
                  "context":{"type":"string","description":"Context name (see k8s_contexts)"},
                  "namespace":{"type":"string","description":"Namespace, or * for all namespaces"}
                }}
            """.trimIndent(),
            readOnly = false,
            handler = McpToolHandler { args ->
                args.string("context")?.let { engine.selectContext(it) }
                args.string("namespace")?.let { engine.selectNamespace(it) }
                services.rememberTarget()
                val state = engine.probeCluster()
                McpToolResult("Now pointed at ${engine.target.value.display} (${stateLabel(state)}).")
            },
        ),

        McpToolDefinition(
            name = "k8s_namespaces",
            description = "List namespaces in the selected cluster.",
            handler = McpToolHandler {
                requireReady() ?: return@McpToolHandler clusterError()
                engine.refreshNamespaces()
                val rows = engine.namespaces.value
                McpToolResult(
                    "${engine.target.context()}\n" +
                        rows.joinToString("\n") { "${it.name}  ${it.phase}" }.ifBlank { "No namespaces." },
                )
            },
        ),

        McpToolDefinition(
            name = "k8s_pods",
            description = "List pods in the selected namespace with phase, ready count and restarts.",
            handler = McpToolHandler {
                requireReady() ?: return@McpToolHandler clusterError()
                engine.refreshSection(KubeSection.PODS)
                val rows = engine.pods.value
                if (rows.isEmpty()) return@McpToolHandler McpToolResult("${engine.target.display()}\nNo pods.")
                McpToolResult(
                    "${engine.target.display()}\n" +
                        rows.joinToString("\n") { p ->
                            "${p.name}  ${p.phase}  ready=${p.ready}  restarts=${p.restarts}  node=${p.node}"
                        },
                )
            },
        ),

        McpToolDefinition(
            name = "k8s_get",
            description = "List objects of any kind in the selected namespace (kubectl get). " +
                "Secret values are never returned — only names, types and ages.",
            inputSchema = """
                {"type":"object","properties":{
                  "kind":{"type":"string","description":"Resource kind or plural, e.g. deployments, svc, ingress, mycrds.example.com"},
                  "output":{"type":"string","description":"'wide' (default) or 'yaml'; yaml is refused for secrets"}
                },"required":["kind"]}
            """.trimIndent(),
            handler = McpToolHandler { args ->
                requireReady() ?: return@McpToolHandler clusterError()
                val kind = args.string("kind")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: kind", isError = true)
                val output = args.string("output")?.lowercase() ?: "wide"

                if (KubeActions.isSecretKind(kind)) {
                    // Structural refusal: this path cannot be talked into printing
                    // secret values, whatever output is asked for.
                    val result = KubectlCli.exec(
                        engine.args(
                            listOf(
                                "get", "secrets",
                                "-o", "custom-columns=NAME:.metadata.name,TYPE:.type,AGE:.metadata.creationTimestamp",
                            ),
                        ),
                    )
                    return@McpToolHandler if (result.ok) {
                        McpToolResult("${engine.target.display()}\n${result.stdout.trim()}")
                    } else {
                        McpToolResult(result.cleanError, isError = true)
                    }
                }

                val outArgs = if (output == "yaml") listOf("-o", "yaml") else listOf("-o", "wide")
                val result = KubectlCli.exec(engine.args(listOf("get", kind) + outArgs), timeoutMs = 20_000)
                if (result.ok) {
                    McpToolResult("${engine.target.display()}\n${result.stdout.trim().ifBlank { "No resources." }}")
                } else {
                    McpToolResult(result.cleanError, isError = true)
                }
            },
        ),

        McpToolDefinition(
            name = "k8s_logs",
            description = "Recent logs of a pod or workload. Use container for multi-container pods, " +
                "and previous=true to see why a crash-looping pod died.",
            inputSchema = """
                {"type":"object","properties":{
                  "target":{"type":"string","description":"Pod name, or kind/name such as deployment/api"},
                  "container":{"type":"string","description":"Container name (multi-container pods)"},
                  "tail":{"type":"integer","description":"Trailing lines (default 200, max 2000)"},
                  "previous":{"type":"boolean","description":"Logs from the previous terminated container"}
                },"required":["target"]}
            """.trimIndent(),
            handler = McpToolHandler { args ->
                requireReady() ?: return@McpToolHandler clusterError()
                val target = args.string("target")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: target", isError = true)
                val tail = (args.int("tail") ?: 200).coerceIn(1, 2000)
                val cmd = buildList {
                    add("logs")
                    add(target)
                    add("--tail=$tail")
                    args.string("container")?.let { addAll(listOf("-c", it)) }
                    if (args.boolean("previous") == true) add("--previous")
                }
                val result = KubectlCli.exec(engine.args(cmd), timeoutMs = 25_000)
                if (result.ok) {
                    McpToolResult(result.stdout.ifBlank { "(no output)" })
                } else {
                    McpToolResult(result.cleanError, isError = true)
                }
            },
        ),

        McpToolDefinition(
            name = "k8s_describe",
            description = "kubectl describe for one object — the fastest way to see why it isn't working. " +
                "For secrets this shows key names and byte counts, never values.",
            inputSchema = kindNameSchema,
            handler = McpToolHandler { args ->
                requireReady() ?: return@McpToolHandler clusterError()
                val (kind, name) = args.kindAndName() ?: return@McpToolHandler missingArgs()
                val result = services.actions.describe(kind, name)
                if (result.ok) {
                    McpToolResult("${engine.target.display()}\n${result.stdout}")
                } else {
                    McpToolResult(result.cleanError, isError = true)
                }
            },
        ),

        McpToolDefinition(
            name = "k8s_yaml",
            description = "Full YAML of one object. Refused for secrets, whose YAML is the values themselves.",
            inputSchema = kindNameSchema,
            handler = McpToolHandler { args ->
                requireReady() ?: return@McpToolHandler clusterError()
                val (kind, name) = args.kindAndName() ?: return@McpToolHandler missingArgs()
                val result = services.actions.yaml(kind, name)
                if (result.ok) McpToolResult(result.stdout) else McpToolResult(result.cleanError, isError = true)
            },
        ),

        McpToolDefinition(
            name = "k8s_events",
            description = "Recent events in the namespace, newest first, optionally filtered to one object.",
            inputSchema = """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"Only events for this object name"}
                }}
            """.trimIndent(),
            handler = McpToolHandler { args ->
                requireReady() ?: return@McpToolHandler clusterError()
                val rows = engine.events(objectName = args.string("name"))
                if (rows.isEmpty()) return@McpToolHandler McpToolResult("${engine.target.display()}\nNo events.")
                McpToolResult(
                    "${engine.target.display()}\n" +
                        rows.take(60).joinToString("\n") { e ->
                            "${e.type}  ${e.reason}  ${e.objectRef}  ×${e.count}  ${e.message.take(160)}"
                        },
                )
            },
        ),

        McpToolDefinition(
            name = "k8s_api_resources",
            description = "List resource types available in the cluster, including CRDs.",
            handler = McpToolHandler {
                requireReady() ?: return@McpToolHandler clusterError()
                val result = KubectlCli.exec(
                    engine.args(listOf("api-resources", "--verbs=list"), namespaced = false),
                    timeoutMs = 25_000,
                )
                if (result.ok) McpToolResult(result.stdout.trim()) else McpToolResult(result.cleanError, isError = true)
            },
        ),

        McpToolDefinition(
            name = "k8s_port_forward",
            description = "Start a supervised port-forward and return the local URL. It restarts itself if " +
                "the connection drops, and is torn down when the plugin unloads.",
            inputSchema = """
                {"type":"object","properties":{
                  "target":{"type":"string","description":"kind/name, e.g. service/api or pod/api-abc123"},
                  "remote_port":{"type":"integer","description":"Port on the target"},
                  "local_port":{"type":"integer","description":"Preferred local port (default: a free one)"}
                },"required":["target","remote_port"]}
            """.trimIndent(),
            readOnly = false,
            handler = McpToolHandler { args ->
                requireReady() ?: return@McpToolHandler clusterError()
                val target = args.string("target")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: target", isError = true)
                val remote = args.int("remote_port")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: remote_port", isError = true)
                val local = services.forwards.start(target, remote, args.int("local_port"))
                McpToolResult("Forwarding $target :$remote → http://localhost:$local (${engine.target.display()})")
            },
        ),

        McpToolDefinition(
            name = "k8s_port_forward_stop",
            description = "Stop a port-forward started by this plugin, or all of them.",
            inputSchema = """
                {"type":"object","properties":{
                  "target":{"type":"string","description":"kind/name; omit with all=true"},
                  "remote_port":{"type":"integer","description":"Port on the target"},
                  "all":{"type":"boolean","description":"Stop every forward"}
                }}
            """.trimIndent(),
            readOnly = false,
            handler = McpToolHandler { args ->
                if (args.boolean("all") == true) {
                    services.forwards.stopAll()
                    return@McpToolHandler McpToolResult("Stopped all port-forwards.")
                }
                val target = args.string("target")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: target", isError = true)
                val remote = args.int("remote_port")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: remote_port", isError = true)
                val t = engine.target.value
                services.forwards.stop(ForwardKey(t.context, t.namespace, target, remote))
                McpToolResult("Stopped forwarding $target :$remote")
            },
        ),

        McpToolDefinition(
            name = "k8s_forwards",
            description = "List port-forwards this plugin is currently supervising, with their status.",
            handler = McpToolHandler {
                val rows = services.forwards.forwards.value.values
                if (rows.isEmpty()) return@McpToolHandler McpToolResult("No active port-forwards.")
                McpToolResult(
                    rows.joinToString("\n") { f ->
                        "${f.key.ref} :${f.key.remotePort} → localhost:${f.localPort}  " +
                            "${f.status.name.lowercase()}  restarts=${f.restarts}  ns=${f.key.namespace}"
                    },
                )
            },
        ),

        McpToolDefinition(
            name = "k8s_manifests",
            description = "List Kubernetes manifests and kustomizations found in the open BOSS project.",
            handler = McpToolHandler {
                engine.rescanProject()
                val rows = engine.manifests.value
                if (rows.isEmpty()) return@McpToolHandler McpToolResult("No manifests in this project.")
                McpToolResult(rows.joinToString("\n") { "${it.kind.name.lowercase()}  ${it.relativePath}" })
            },
        ),

        McpToolDefinition(
            name = "k8s_open_resource",
            description = "Open the BOSS resource tab for an object — logs, preview, describe, YAML and events.",
            inputSchema = kindNameSchema,
            readOnly = false,
            handler = McpToolHandler { args ->
                val (kind, name) = args.kindAndName() ?: return@McpToolHandler missingArgs()
                when (services.openResourceTabVerified(kind, name)) {
                    TabOpenOutcome.Opened -> McpToolResult("Opened the $kind/$name tab (${engine.target.display()}).")
                    TabOpenOutcome.Focused -> McpToolResult("Focused the existing $kind/$name tab.")
                    TabOpenOutcome.Unverifiable ->
                        McpToolResult("Requested the $kind/$name tab (couldn't confirm it opened).")
                    TabOpenOutcome.Dropped -> McpToolResult(
                        "The host dropped the tab — no factory registered for " +
                            "'${KubeResourceTabType.typeId.typeId}'. Reload the Kubernetes plugin from Toolbox.",
                        isError = true,
                    )
                    TabOpenOutcome.NoSplitViewOperations ->
                        McpToolResult("This host exposes no split-view operations.", isError = true)
                }
            },
        ),

        // ------------------------------------------------------- gated tools

        McpToolDefinition.withRbac(
            name = "k8s_scale",
            description = "Scale a Deployment or StatefulSet.",
            inputSchema = """
                {"type":"object","properties":{
                  "kind":{"type":"string","description":"deployment or statefulset"},
                  "name":{"type":"string","description":"Workload name"},
                  "replicas":{"type":"integer","description":"Desired replica count"}
                },"required":["kind","name","replicas"]}
            """.trimIndent(),
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler { args ->
                requireReady() ?: return@McpToolHandler clusterError()
                val (kind, name) = args.kindAndName() ?: return@McpToolHandler missingArgs()
                val replicas = args.int("replicas")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: replicas", isError = true)
                val workload = WorkloadInfo(kind, name, engine.target.value.namespace, 0, 0, emptyList(), "")
                val result = services.actions.scale(workload, replicas)
                if (result.ok) {
                    McpToolResult("Scaled $kind/$name to $replicas in ${engine.target.display()}.")
                } else {
                    McpToolResult(result.cleanError, isError = true)
                }
            },
        ),

        McpToolDefinition.withRbac(
            name = "k8s_rollout_restart",
            description = "Restart a workload's pods (kubectl rollout restart).",
            inputSchema = kindNameSchema,
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler { args ->
                requireReady() ?: return@McpToolHandler clusterError()
                val (kind, name) = args.kindAndName() ?: return@McpToolHandler missingArgs()
                val workload = WorkloadInfo(kind, name, engine.target.value.namespace, 0, 0, emptyList(), "")
                val result = services.actions.rolloutRestart(workload)
                if (result.ok) {
                    McpToolResult("Restarted $kind/$name in ${engine.target.display()}.")
                } else {
                    McpToolResult(result.cleanError, isError = true)
                }
            },
        ),

        McpToolDefinition.withRbac(
            name = "k8s_delete",
            description = "Delete one object. Destructive and not undoable; the reply names the context and " +
                "namespace it acted on. Deletes exactly one object — there is no bulk form.",
            inputSchema = kindNameSchema,
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler { args ->
                requireReady() ?: return@McpToolHandler clusterError()
                val (kind, name) = args.kindAndName() ?: return@McpToolHandler missingArgs()
                val result = services.actions.delete(kind, name)
                if (result.ok) {
                    McpToolResult("Deleted $kind/$name from ${engine.target.display()}.")
                } else {
                    McpToolResult(result.cleanError, isError = true)
                }
            },
        ),

        McpToolDefinition.withRbac(
            name = "k8s_apply",
            description = "Apply a manifest or kustomization in the plugin's shared terminal tab. " +
                "Another terminal-routed command (apply, diff, or a helm mutation) interrupts it; " +
                "the read tools do not, so confirming with k8s_get is safe. Use dry_run=true first " +
                "to see what would change without changing it.",
            inputSchema = """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"Manifest file or kustomization dir (absolute, or relative to the project)"},
                  "dry_run":{"type":"boolean","description":"Server-side dry run (default false)"}
                },"required":["path"]}
            """.trimIndent(),
            readOnly = false,
            // Unrestricted object creation, including cluster-scoped RBAC objects, so it
            // reaches at least as far as k8s_delete. `kubernetes.manage` rather than a
            // permission of its own because `helm_install` already creates arbitrary
            // objects from a chart under this same permission: a separate `kubernetes.apply`
            // would gate the manifest spelling of a capability that stays reachable via
            // Helm, which is a distinction the permission model does not actually make.
            // dry_run is not a lesser case — it is an argument the caller chooses, so
            // gating on it would be a gate the caller controls.
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler { args ->
                requireReady() ?: return@McpToolHandler clusterError()
                val path = args.string("path")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: path", isError = true)
                val file = resolveProjectFile(path)
                    ?: return@McpToolHandler McpToolResult("Not found: $path", isError = true)
                val kind = if (file.isDirectory || file.name.startsWith("kustomization")) {
                    ManifestArtifact.Kind.KUSTOMIZATION
                } else {
                    ManifestArtifact.Kind.MANIFEST
                }
                val artifact = ManifestArtifact(file, kind, file.name)
                val dryRun = args.boolean("dry_run") ?: false
                if (services.actions.apply(artifact, dryRun = dryRun)) {
                    McpToolResult(
                        "${if (dryRun) "Dry-run applying" else "Applying"} ${file.absolutePath} " +
                            "to ${engine.target.display()} in the plugin terminal tab — queued, so check the tab for the result.",
                    )
                } else {
                    McpToolResult("Couldn't start the command.", isError = true)
                }
            },
        ),

        McpToolDefinition.withRbac(
            name = "k8s_exec",
            description = "Open an interactive shell in a pod, in its own terminal tab (exec -it needs " +
                "a real TTY, and Ctrl-C is forwarded into the pod rather than freeing the tab, so it " +
                "never shares the plugin's shared one). Gated on kubernetes.exec, separately from " +
                "the other mutations.",
            inputSchema = """
                {"type":"object","properties":{
                  "pod":{"type":"string","description":"Pod name"},
                  "container":{"type":"string","description":"Container name"},
                  "shell":{"type":"string","description":"Shell to run (default sh)"}
                },"required":["pod"]}
            """.trimIndent(),
            readOnly = false,
            // Its own permission, not `kubernetes.manage`, because a shell is a different
            // kind of thing from "scale this deployment": it is arbitrary command execution
            // inside the cluster with the pod's own service-account credentials, and it is
            // the one path that walks around this plugin's Secret protections — `k8s_yaml`
            // refuses Secrets and `redactRenderedYaml` masks rendered ones, but a shell
            // reads a mounted Secret or /var/run/secrets/.../token straight off the
            // filesystem. Whoever may restart a workload should not thereby get that.
            // Not `requiresAdmin`: "may shell into a pod" is a role, not a rank, and a
            // named permission is what an admin can grant and revoke on its own.
            requiredPermissions = listOf(PERMISSION_EXEC),
            handler = McpToolHandler { args ->
                requireReady() ?: return@McpToolHandler clusterError()
                val podName = args.string("pod")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: pod", isError = true)
                val pod = PodInfo(
                    name = podName,
                    namespace = engine.target.value.namespace,
                    phase = "", readyContainers = 0, totalContainers = 0, restarts = 0,
                    node = "", createdAt = "", containers = emptyList(), initContainers = emptyList(),
                )
                val opened = services.actions.exec(
                    pod = pod,
                    container = args.string("container"),
                    shell = args.string("shell") ?: "sh",
                )
                if (opened) {
                    McpToolResult("Opened a shell in $podName (${engine.target.display()}).")
                } else {
                    McpToolResult("Couldn't start the command.", isError = true)
                }
            },
        ),
    )

    // --------------------------------------------------------------- helpers

    private suspend fun requireReady(): ClusterState.Ready? = engine.probeCluster() as? ClusterState.Ready

    private fun clusterError(): McpToolResult = when (val state = engine.cluster.value) {
        is ClusterState.KubectlMissing -> McpToolResult("kubectl is not installed on this machine.", isError = true)
        is ClusterState.NoContext -> McpToolResult("The kubeconfig has no usable context.", isError = true)
        is ClusterState.Unreachable -> McpToolResult(
            "Can't reach ${engine.target.value.context ?: "the cluster"}: ${state.message}",
            isError = true,
        )
        is ClusterState.Forbidden -> McpToolResult("Not permitted on this cluster: ${state.message}", isError = true)
        is ClusterState.Error -> McpToolResult("kubectl error: ${state.message}", isError = true)
        else -> McpToolResult("The cluster is not ready.", isError = true)
    }

    private fun missingArgs() = McpToolResult("Missing required arguments: kind and name", isError = true)

    private fun McpToolArgs.kindAndName(): Pair<String, String>? {
        val kind = string("kind") ?: return null
        val name = string("name") ?: return null
        return kind to name
    }

    private fun resolveProjectFile(path: String): File? {
        val direct = File(path)
        if (direct.isAbsolute) return direct.takeIf { it.exists() }
        val root = services.context.projectPath ?: return direct.takeIf { it.exists() }
        return File(root, path).takeIf { it.exists() } ?: direct.takeIf { it.exists() }
    }

    private fun stateLabel(state: ClusterState): String = when (state) {
        is ClusterState.Ready -> "reachable, server ${state.serverVersion}"
        is ClusterState.Unreachable -> "unreachable"
        is ClusterState.Forbidden -> "forbidden"
        is ClusterState.NoContext -> "no context"
        is ClusterState.KubectlMissing -> "kubectl missing"
        is ClusterState.Error -> "error"
        is ClusterState.Unknown -> "unknown"
    }

    private companion object {
        const val PERMISSION_MANAGE = "kubernetes.manage"
        const val PERMISSION_EXEC = "kubernetes.exec"

        val kindNameSchema = """
            {"type":"object","properties":{
              "kind":{"type":"string","description":"Resource kind, e.g. pod, deployment, service"},
              "name":{"type":"string","description":"Object name"}
            },"required":["kind","name"]}
        """.trimIndent()
    }
}

/** `context / namespace` header line for tool output. */
private fun kotlinx.coroutines.flow.StateFlow<KubeTarget>.display(): String = value.display

/** Just the context name, for terser replies. */
private fun kotlinx.coroutines.flow.StateFlow<KubeTarget>.context(): String = value.context ?: "no context"
