package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import java.io.File

/**
 * `helm_*` tools, surfacing as `mcp__boss__helm_*`.
 *
 * Registered as a second provider alongside [KubeMcpToolProvider] so Helm can be
 * absent without affecting the Kubernetes tools — every handler starts by checking
 * that helm actually exists.
 *
 * Same contracts as the k8s side: replies name the context and namespace, mutating
 * tools are RBAC-gated via `withRbac` (never `.copy()`), and rendered output has
 * Secret payloads redacted.
 */
class HelmMcpToolProvider(
    override val providerId: String,
    private val services: KubeServices,
) : McpToolProvider {

    private val helm get() = services.helm
    private val kube get() = services.engine
    private val actions get() = services.helmActions

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "helm_releases",
            description = "List Helm releases in the selected namespace, including failed and pending ones.",
            handler = McpToolHandler {
                requireReleaseAccess()?.let { return@McpToolHandler it }
                helm.refreshReleases()
                val rows = helm.releases.value
                if (rows.isEmpty()) return@McpToolHandler McpToolResult("${target()}\nNo Helm releases.")
                McpToolResult(
                    "${target()}\n" + rows.joinToString("\n") { r ->
                        "${r.name}  rev=${r.revision}  ${r.status}  chart=${r.chart}  app=${r.appVersion}"
                    },
                )
            },
        ),

        McpToolDefinition(
            name = "helm_status",
            description = "Status of one release: state, revision, description and deploy times. " +
                "The rendered manifest is deliberately not included — use helm_manifest.",
            inputSchema = releaseSchema,
            handler = McpToolHandler { args ->
                requireReleaseAccess()?.let { return@McpToolHandler it }
                val name = args.string("release") ?: return@McpToolHandler missingRelease()
                val (status, error) = helm.status(name)
                if (status == null) return@McpToolHandler McpToolResult(error.ifBlank { "not found" }, isError = true)
                McpToolResult(
                    """
                    ${target()}
                    name:           ${status.name}
                    status:         ${status.status}
                    revision:       ${status.revision}
                    description:    ${status.description}
                    first deployed: ${status.firstDeployed}
                    last deployed:  ${status.lastDeployed}
                    """.trimIndent(),
                )
            },
        ),

        McpToolDefinition(
            name = "helm_values",
            description = "Values of a release. By default only what was supplied; all=true returns the " +
                "fully merged values. These are your own chart values and are NOT redacted — " +
                "only rendered Kubernetes Secret objects are.",
            inputSchema = """
                {"type":"object","properties":{
                  "release":{"type":"string","description":"Release name"},
                  "all":{"type":"boolean","description":"Return merged/computed values (default false)"}
                },"required":["release"]}
            """.trimIndent(),
            handler = McpToolHandler { args ->
                requireReleaseAccess()?.let { return@McpToolHandler it }
                val name = args.string("release") ?: return@McpToolHandler missingRelease()
                McpToolResult(helm.values(name, args.boolean("all") ?: false))
            },
        ),

        McpToolDefinition(
            name = "helm_manifest",
            description = "Rendered manifest of a release, with Kubernetes Secret values redacted.",
            inputSchema = releaseSchema,
            handler = McpToolHandler { args ->
                requireReleaseAccess()?.let { return@McpToolHandler it }
                val name = args.string("release") ?: return@McpToolHandler missingRelease()
                McpToolResult(helm.manifest(name))
            },
        ),

        McpToolDefinition(
            name = "helm_history",
            description = "Revision history of a release, newest first.",
            inputSchema = releaseSchema,
            handler = McpToolHandler { args ->
                requireReleaseAccess()?.let { return@McpToolHandler it }
                val name = args.string("release") ?: return@McpToolHandler missingRelease()
                val rows = helm.history(name)
                if (rows.isEmpty()) return@McpToolHandler McpToolResult("No history for $name.", isError = true)
                McpToolResult(
                    "${target()}\n" + rows.joinToString("\n") { r ->
                        "rev ${r.revision}  ${r.status}  ${r.chart}  ${r.description}  ${r.updated}"
                    },
                )
            },
        ),

        McpToolDefinition(
            name = "helm_notes",
            description = "The NOTES.txt a release rendered on install.",
            inputSchema = releaseSchema,
            handler = McpToolHandler { args ->
                requireReleaseAccess()?.let { return@McpToolHandler it }
                val name = args.string("release") ?: return@McpToolHandler missingRelease()
                McpToolResult(helm.notes(name))
            },
        ),

        McpToolDefinition(
            name = "helm_charts",
            description = "List Helm charts found in the open BOSS project, with their values files.",
            handler = McpToolHandler {
                helm.rescanCharts()
                val rows = helm.charts.value
                if (rows.isEmpty()) return@McpToolHandler McpToolResult("No Helm charts in this project.")
                McpToolResult(
                    rows.joinToString("\n") { c ->
                        "${c.relativePath}  name=${c.name}  version=${c.version}  " +
                            "values=[${c.valuesFiles.joinToString(",") { it.name }}]"
                    },
                )
            },
        ),

        McpToolDefinition(
            name = "helm_lint",
            description = "Lint a chart. Needs no cluster.",
            inputSchema = chartSchema,
            handler = McpToolHandler { args ->
                requireHelm() ?: return@McpToolHandler helmError()
                val chart = resolveChart(args) ?: return@McpToolHandler chartNotFound(args)
                val result = actions.lint(chart, chart.valuesFileNamed(args.string("values")))
                McpToolResult(result.stdout.ifBlank { result.cleanError }, isError = !result.ok)
            },
        ),

        McpToolDefinition(
            name = "helm_template",
            description = "Render a chart locally without touching the cluster. Kubernetes Secret values in " +
                "the output are redacted.",
            inputSchema = """
                {"type":"object","properties":{
                  "chart":{"type":"string","description":"Chart dir or Chart.yaml path (absolute, or relative to the project)"},
                  "values":{"type":"string","description":"Values file name beside the chart, e.g. values-dev.yaml"},
                  "release":{"type":"string","description":"Release name to render with (default: derived from the dir)"},
                  "kinds_only":{"type":"boolean","description":"Return just a count per kind instead of full YAML"}
                },"required":["chart"]}
            """.trimIndent(),
            handler = McpToolHandler { args ->
                requireHelm() ?: return@McpToolHandler helmError()
                val chart = resolveChart(args) ?: return@McpToolHandler chartNotFound(args)
                val releaseName = args.string("release") ?: chart.suggestedRelease
                val (ok, output) = actions.template(chart, releaseName, chart.valuesFileNamed(args.string("values")))
                if (!ok) return@McpToolHandler McpToolResult(output, isError = true)
                if (args.boolean("kinds_only") == true) {
                    val counts = Regex("(?m)^kind:\\s*(\\S+)").findAll(output)
                        .map { it.groupValues[1] }
                        .groupingBy { it }.eachCount()
                        .entries.sortedBy { it.key }
                        .joinToString("\n") { "${it.value}  ${it.key}" }
                    McpToolResult(counts.ifBlank { "(rendered nothing)" })
                } else {
                    McpToolResult(output)
                }
            },
        ),

        McpToolDefinition(
            name = "helm_repos",
            description = "List configured chart repositories.",
            handler = McpToolHandler {
                requireHelm() ?: return@McpToolHandler helmError()
                helm.refreshRepos()
                val rows = helm.repos.value
                if (rows.isEmpty()) return@McpToolHandler McpToolResult("No chart repositories configured.")
                McpToolResult(rows.joinToString("\n") { "${it.name}  ${it.url}" })
            },
        ),

        McpToolDefinition(
            name = "helm_search",
            description = "Search configured repositories for charts.",
            inputSchema = """
                {"type":"object","properties":{
                  "query":{"type":"string","description":"Search text"}
                },"required":["query"]}
            """.trimIndent(),
            handler = McpToolHandler { args ->
                requireHelm() ?: return@McpToolHandler helmError()
                val query = args.string("query")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: query", isError = true)
                val hits = helm.search(query)
                if (hits.isEmpty()) return@McpToolHandler McpToolResult("Nothing matched '$query'.")
                McpToolResult(
                    hits.take(40).joinToString("\n") { "${it.name}  ${it.version}  app=${it.appVersion}  ${it.description.take(80)}" },
                )
            },
        ),

        McpToolDefinition(
            name = "helm_open_release",
            description = "Open the BOSS release tab — status, values, redacted manifest, history and notes.",
            inputSchema = releaseSchema,
            readOnly = false,
            handler = McpToolHandler { args ->
                val name = args.string("release") ?: return@McpToolHandler missingRelease()
                when (services.openReleaseTabVerified(name)) {
                    TabOpenOutcome.Opened -> McpToolResult("Opened the $name release tab (${target()}).")
                    TabOpenOutcome.Focused -> McpToolResult("Focused the existing $name release tab.")
                    TabOpenOutcome.Unverifiable -> McpToolResult("Requested the tab (couldn't confirm it opened).")
                    TabOpenOutcome.Dropped -> McpToolResult(
                        "The host dropped the tab — no factory for '${HelmReleaseTabType.typeId.typeId}'. " +
                            "Reload the Kubernetes plugin from Toolbox.",
                        isError = true,
                    )
                    TabOpenOutcome.NoSplitViewOperations ->
                        McpToolResult("This host exposes no split-view operations.", isError = true)
                }
            },
        ),

        McpToolDefinition(
            name = "helm_package",
            description = "Package a chart into a .tgz in a BOSS terminal tab. Local only; publishes nothing.",
            inputSchema = """
                {"type":"object","properties":{
                  "chart":{"type":"string","description":"Chart dir or Chart.yaml path"},
                  "destination":{"type":"string","description":"Output directory (default: the chart's parent)"}
                },"required":["chart"]}
            """.trimIndent(),
            readOnly = false,
            handler = McpToolHandler { args ->
                requireHelm() ?: return@McpToolHandler helmError()
                val chart = resolveChart(args) ?: return@McpToolHandler chartNotFound(args)
                val dest = args.string("destination") ?: chart.directory.parent ?: chart.directory.absolutePath
                if (actions.packageChart(chart, dest)) {
                    McpToolResult("Packaging ${chart.name} into $dest in a terminal tab.")
                } else {
                    McpToolResult("Couldn't open a terminal tab.", isError = true)
                }
            },
        ),

        McpToolDefinition(
            name = "helm_dependency_update",
            description = "Fetch a chart's declared dependencies into charts/, in a terminal tab.",
            inputSchema = chartSchema,
            readOnly = false,
            handler = McpToolHandler { args ->
                requireHelm() ?: return@McpToolHandler helmError()
                val chart = resolveChart(args) ?: return@McpToolHandler chartNotFound(args)
                if (actions.dependencyUpdate(chart)) {
                    McpToolResult("Updating dependencies for ${chart.name} in a terminal tab.")
                } else {
                    McpToolResult("Couldn't open a terminal tab.", isError = true)
                }
            },
        ),

        McpToolDefinition(
            name = "helm_repo_add",
            description = "Add a chart repository. NOTE: this writes ~/.config/helm/repositories.yaml, which is " +
                "shared with your shells — the repo will exist outside BOSS too.",
            inputSchema = """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"Local name for the repo"},
                  "url":{"type":"string","description":"Repository URL"}
                },"required":["name","url"]}
            """.trimIndent(),
            readOnly = false,
            handler = McpToolHandler { args ->
                requireHelm() ?: return@McpToolHandler helmError()
                val name = args.string("name")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: name", isError = true)
                val url = args.string("url")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: url", isError = true)
                if (actions.repoAdd(name, url)) {
                    McpToolResult("Adding repo $name ($url) in a terminal tab. This affects the whole machine.")
                } else {
                    McpToolResult("Couldn't open a terminal tab.", isError = true)
                }
            },
        ),

        McpToolDefinition(
            name = "helm_repo_update",
            description = "Refresh the local cache of all configured chart repositories.",
            readOnly = false,
            handler = McpToolHandler {
                requireHelm() ?: return@McpToolHandler helmError()
                if (actions.repoUpdate()) {
                    McpToolResult("Updating chart repositories in a terminal tab.")
                } else {
                    McpToolResult("Couldn't open a terminal tab.", isError = true)
                }
            },
        ),

        McpToolDefinition(
            name = "helm_repo_remove",
            description = "Remove a chart repository. Also machine-wide.",
            inputSchema = """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"Repo name to remove"}
                },"required":["name"]}
            """.trimIndent(),
            readOnly = false,
            handler = McpToolHandler { args ->
                requireHelm() ?: return@McpToolHandler helmError()
                val name = args.string("name")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: name", isError = true)
                val result = actions.repoRemove(name)
                helm.refreshRepos()
                if (result.ok) {
                    McpToolResult("Removed repo $name (machine-wide).")
                } else {
                    McpToolResult(result.cleanError, isError = true)
                }
            },
        ),

        // -------------------------------------------------------- gated tools

        McpToolDefinition.withRbac(
            name = "helm_install",
            description = "Install a chart as a new release, in a terminal tab. Use dry_run=true first.",
            inputSchema = """
                {"type":"object","properties":{
                  "chart":{"type":"string","description":"Chart dir or Chart.yaml path"},
                  "release":{"type":"string","description":"Release name"},
                  "values":{"type":"string","description":"Values file name beside the chart"},
                  "dry_run":{"type":"boolean","description":"Server-side dry run (default false)"}
                },"required":["chart","release"]}
            """.trimIndent(),
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler { args ->
                requireHelm() ?: return@McpToolHandler helmError()
                requireCluster() ?: return@McpToolHandler clusterNotReady()
                val chart = resolveChart(args) ?: return@McpToolHandler chartNotFound(args)
                val release = args.string("release") ?: return@McpToolHandler missingRelease()
                val valuesFile = chart.valuesFileNamed(args.string("values"))

                // A dry run renders the manifest, so it is captured and redacted here
                // rather than printed into a terminal.
                if (args.boolean("dry_run") == true) {
                    val (ok, output) = actions.installDryRun(chart, release, valuesFile)
                    return@McpToolHandler McpToolResult(
                        if (ok) "${target()} — dry run, Secret values redacted\n\n$output" else output,
                        isError = !ok,
                    )
                }
                if (actions.install(chart, release, valuesFile)) {
                    McpToolResult("Installing ${chart.name} as $release into ${target()} in a terminal tab.")
                } else {
                    McpToolResult("Couldn't open a terminal tab.", isError = true)
                }
            },
        ),

        McpToolDefinition.withRbac(
            name = "helm_upgrade",
            description = "Upgrade a release, in a terminal tab. chart may be a local path or a repo ref " +
                "like metrics-server/metrics-server.",
            inputSchema = """
                {"type":"object","properties":{
                  "release":{"type":"string","description":"Release name"},
                  "chart":{"type":"string","description":"Chart dir/Chart.yaml path, or a repo chart reference"},
                  "values":{"type":"string","description":"Values file name beside a local chart"},
                  "install":{"type":"boolean","description":"--install if the release doesn't exist yet"},
                  "rollback_on_failure":{"type":"boolean","description":"Roll back automatically if the upgrade fails"}
                },"required":["release","chart"]}
            """.trimIndent(),
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler { args ->
                requireHelm() ?: return@McpToolHandler helmError()
                requireCluster() ?: return@McpToolHandler clusterNotReady()
                val release = args.string("release") ?: return@McpToolHandler missingRelease()
                val chartArg = args.string("chart")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: chart", isError = true)
                val local = resolveChart(args)
                val launched = actions.upgrade(
                    chart = local,
                    releaseName = release,
                    chartRef = if (local == null) chartArg else null,
                    valuesFile = local?.valuesFileNamed(args.string("values")),
                    install = args.boolean("install") ?: false,
                    rollbackOnFailure = args.boolean("rollback_on_failure") ?: false,
                )
                if (launched) {
                    McpToolResult("Upgrading $release in ${target()} in a terminal tab.")
                } else {
                    McpToolResult("Couldn't open a terminal tab.", isError = true)
                }
            },
        ),

        McpToolDefinition.withRbac(
            name = "helm_rollback",
            description = "Roll a release back to an earlier revision (see helm_history), in a terminal tab.",
            inputSchema = """
                {"type":"object","properties":{
                  "release":{"type":"string","description":"Release name"},
                  "revision":{"type":"integer","description":"Revision to roll back to"}
                },"required":["release","revision"]}
            """.trimIndent(),
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler { args ->
                requireHelm() ?: return@McpToolHandler helmError()
                val name = args.string("release") ?: return@McpToolHandler missingRelease()
                val revision = args.int("revision")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: revision", isError = true)
                helm.refreshReleases()
                val release = helm.findRelease(name)
                    ?: return@McpToolHandler McpToolResult("No release named '$name' in ${target()}.", isError = true)
                if (actions.rollback(release, revision)) {
                    McpToolResult("Rolling $name back to revision $revision in ${target()}.")
                } else {
                    McpToolResult("Couldn't open a terminal tab.", isError = true)
                }
            },
        ),

        McpToolDefinition.withRbac(
            name = "helm_uninstall",
            description = "Uninstall a release. Destructive: deletes every object the release owns.",
            inputSchema = """
                {"type":"object","properties":{
                  "release":{"type":"string","description":"Release name"},
                  "keep_history":{"type":"boolean","description":"Keep the release history for later rollback"}
                },"required":["release"]}
            """.trimIndent(),
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler { args ->
                requireHelm() ?: return@McpToolHandler helmError()
                val name = args.string("release") ?: return@McpToolHandler missingRelease()
                helm.refreshReleases()
                val release = helm.findRelease(name)
                    ?: return@McpToolHandler McpToolResult("No release named '$name' in ${target()}.", isError = true)
                if (actions.uninstall(release, args.boolean("keep_history") ?: false)) {
                    McpToolResult("Uninstalling $name from ${target()} in a terminal tab.")
                } else {
                    McpToolResult("Couldn't open a terminal tab.", isError = true)
                }
            },
        ),

        McpToolDefinition.withRbac(
            name = "helm_test",
            description = "Run a release's test hooks. Reports cleanly when the chart defines none.",
            inputSchema = releaseSchema,
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_MANAGE),
            handler = McpToolHandler { args ->
                requireHelm() ?: return@McpToolHandler helmError()
                val name = args.string("release") ?: return@McpToolHandler missingRelease()
                helm.refreshReleases()
                val release = helm.findRelease(name)
                    ?: return@McpToolHandler McpToolResult("No release named '$name' in ${target()}.", isError = true)
                if (actions.test(release)) {
                    McpToolResult("Running tests for $name in ${target()} in a terminal tab.")
                } else {
                    McpToolResult("Couldn't open a terminal tab.", isError = true)
                }
            },
        ),

        McpToolDefinition.withRbac(
            name = "helm_push",
            description = "Push a packaged chart (.tgz) to an OCI registry. This publishes outside your machine, " +
                "so it is gated separately and the reply names the destination.",
            inputSchema = """
                {"type":"object","properties":{
                  "package":{"type":"string","description":"Path to the packaged .tgz"},
                  "target":{"type":"string","description":"OCI target, e.g. oci://registry.example.com/charts"},
                  "plain_http":{"type":"boolean","description":"Use HTTP instead of HTTPS — needed for local or internal registries that don't serve TLS"}
                },"required":["package","target"]}
            """.trimIndent(),
            readOnly = false,
            requiredPermissions = listOf(PERMISSION_PUBLISH),
            handler = McpToolHandler { args ->
                requireHelm() ?: return@McpToolHandler helmError()
                val pkg = args.string("package")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: package", isError = true)
                val dest = args.string("target")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: target", isError = true)
                val file = File(pkg)
                if (!file.isFile) return@McpToolHandler McpToolResult("Not a file: $pkg", isError = true)
                if (!dest.startsWith("oci://")) {
                    return@McpToolHandler McpToolResult("Target must be an oci:// URL.", isError = true)
                }
                val plainHttp = args.boolean("plain_http") ?: false
                if (actions.push(file.absolutePath, dest, plainHttp)) {
                    McpToolResult(
                        "Pushing ${file.name} to $dest${if (plainHttp) " over plain HTTP" else ""} in a terminal tab.",
                    )
                } else {
                    McpToolResult("Couldn't open a terminal tab.", isError = true)
                }
            },
        ),
    )

    // --------------------------------------------------------------- helpers

    private suspend fun requireHelm(): HelmState.Ready? = helm.probe() as? HelmState.Ready

    private suspend fun requireCluster(): ClusterState.Ready? = kube.probeCluster() as? ClusterState.Ready

    /**
     * Guard for anything that reads *release* state, which needs both helm and a
     * reachable cluster.
     *
     * Checking helm alone is not enough: with helm installed but the cluster down,
     * `helm list` returns nothing and an unguarded handler answers "No Helm
     * releases" — which reads as "nothing is installed" when the truth is "I can't
     * see". Returns null when everything is usable, else the error to send back.
     */
    private suspend fun requireReleaseAccess(): McpToolResult? {
        requireHelm() ?: return helmError()
        requireCluster() ?: return clusterNotReady()
        return null
    }

    private fun helmError(): McpToolResult = when (val state = helm.helm.value) {
        is HelmState.Missing -> McpToolResult(
            "helm is not installed. Install it (e.g. `brew install helm`) and retry.",
            isError = true,
        )
        is HelmState.Error -> McpToolResult("helm error: ${state.message}", isError = true)
        else -> McpToolResult("helm is not ready.", isError = true)
    }

    private fun clusterNotReady(): McpToolResult =
        McpToolResult("The cluster for ${target()} isn't reachable.", isError = true)

    private fun missingRelease() = McpToolResult("Missing required argument: release", isError = true)

    private fun chartNotFound(args: McpToolArgs) =
        McpToolResult("No chart found at '${args.string("chart")}'.", isError = true)

    private fun target(): String = kube.target.value.display

    /**
     * Resolve a `chart` argument to a [ChartArtifact].
     *
     * Accepts a chart directory, a `Chart.yaml` path, an absolute or
     * project-relative path, or the relative path of an already-discovered chart —
     * agents supply all of these.
     */
    private fun resolveChart(args: McpToolArgs): ChartArtifact? {
        val raw = args.string("chart") ?: return null
        helm.charts.value.firstOrNull { it.relativePath == raw || it.directory.absolutePath == raw }?.let { return it }

        val candidates = buildList {
            add(File(raw))
            services.context.projectPath?.let { add(File(it, raw)) }
        }
        for (candidate in candidates) {
            val chartFile = when {
                candidate.isDirectory -> listOf("Chart.yaml", "Chart.yml")
                    .map { File(candidate, it) }
                    .firstOrNull { it.isFile }
                candidate.isFile && candidate.name.startsWith("Chart.") -> candidate
                else -> null
            } ?: continue
            val root = services.engine.projectRoot() ?: chartFile.parentFile
            return helm.charts.value.firstOrNull { it.chartFile == chartFile }
                ?: ChartArtifact(
                    chartFile = chartFile,
                    relativePath = chartFile.relativeToOrNull(root)?.path ?: chartFile.name,
                    name = chartFile.parentFile?.name.orEmpty(),
                    version = "",
                    appVersion = "",
                    valuesFiles = chartFile.parentFile?.listFiles()
                        ?.filter { it.isFile && it.name.startsWith("values") && it.extension in setOf("yaml", "yml") }
                        ?.sortedBy { it.name }
                        .orEmpty(),
                )
        }
        return null
    }

    private companion object {
        const val PERMISSION_MANAGE = "kubernetes.manage"
        const val PERMISSION_PUBLISH = "helm.publish"

        val releaseSchema = """
            {"type":"object","properties":{
              "release":{"type":"string","description":"Release name"}
            },"required":["release"]}
        """.trimIndent()

        val chartSchema = """
            {"type":"object","properties":{
              "chart":{"type":"string","description":"Chart dir or Chart.yaml path (absolute, or relative to the project)"},
              "values":{"type":"string","description":"Values file name beside the chart, e.g. values-dev.yaml"}
            },"required":["chart"]}
        """.trimIndent()
    }
}
