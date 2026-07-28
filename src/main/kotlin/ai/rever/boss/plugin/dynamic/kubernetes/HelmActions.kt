package ai.rever.boss.plugin.dynamic.kubernetes

import java.io.File

/**
 * Helm mutations and the long-running commands.
 *
 * Same split as the rest of the plugin: anything long, chatty or worth reading in
 * full goes to a **BossTerm terminal tab** (`install`, `upgrade`, `rollback`,
 * `uninstall`, `test`, `dependency update`, `package`, `push`, `repo update`),
 * while quick reads stay in-plugin.
 *
 * Flag names come from [HelmCli]'s version dialect, because Helm 4 renamed
 * `--atomic`/`--force` and the plugin must work against either major.
 */
class HelmActions(private val services: KubeServices) {

    private val kube get() = services.engine

    /** `context / namespace`, for confirmations and tool replies. */
    fun describeTarget(): String = kube.target.value.display

    // ------------------------------------------------------------- releases

    /**
     * Install a chart as a new release, in a terminal tab.
     *
     * Only for real installs. `helm install` prints status and NOTES, no manifest —
     * unlike a dry run, which is why [installDryRun] is a separate, in-plugin path.
     */
    fun install(
        chart: ChartArtifact,
        releaseName: String,
        valuesFile: File?,
        location: OpenLocation = OpenLocation.NEW_TAB,
    ): Boolean = runHelmTerminal(
        id = "helm-install-$releaseName-${System.currentTimeMillis()}",
        title = "Install: $releaseName",
        parts = buildList {
            add("helm install")
            add(q(releaseName))
            add(q(chart.directory.absolutePath))
            valuesFile?.let { add("-f ${q(it.absolutePath)}") }
            addAll(targetFlagStrings())
        },
        workingDir = chart.directory.absolutePath,
        location = location,
    )

    /**
     * Server-side dry-run install, run **in-plugin rather than in a terminal**.
     *
     * `helm install --dry-run` echoes the fully rendered manifest, Secrets included.
     * Sending that to a terminal would put secret values on screen and in scrollback,
     * quietly defeating the redaction the Manifest view applies — so the output is
     * captured here and filtered before anyone sees it.
     */
    suspend fun installDryRun(
        chart: ChartArtifact,
        releaseName: String,
        valuesFile: File?,
    ): Pair<Boolean, String> {
        val result = HelmCli.exec(
            buildList {
                add("install")
                add(releaseName)
                add(chart.directory.absolutePath)
                valuesFile?.let { addAll(listOf("-f", it.absolutePath)) }
                addAll(HelmCli.targetArgs(kube.target.value))
                add("--dry-run=server")
            },
            workingDir = chart.directory,
            timeoutMs = HelmCli.LONG_TIMEOUT_MS,
        )
        return if (result.ok) true to redactRenderedYaml(result.stdout) else false to result.cleanError
    }

    fun upgrade(
        chart: ChartArtifact?,
        releaseName: String,
        chartRef: String? = null,
        valuesFile: File?,
        install: Boolean = false,
        rollbackOnFailure: Boolean = false,
        location: OpenLocation = OpenLocation.NEW_TAB,
    ): Boolean {
        val target = chartRef ?: chart?.directory?.absolutePath ?: return false
        return runHelmTerminal(
            id = "helm-upgrade-$releaseName-${System.currentTimeMillis()}",
            title = "Upgrade: $releaseName",
            parts = buildList {
                add("helm upgrade")
                add(q(releaseName))
                add(q(target))
                valuesFile?.let { add("-f ${q(it.absolutePath)}") }
                if (install) add("--install")
                // Renamed in Helm 4; HelmCli picks the right spelling.
                if (rollbackOnFailure) add(HelmCli.rollbackOnFailureFlag())
                addAll(targetFlagStrings())
            },
            workingDir = chart?.directory?.absolutePath ?: services.context.projectPath,
            location = location,
        )
    }

    fun rollback(
        release: ReleaseInfo,
        revision: Int,
        location: OpenLocation = OpenLocation.NEW_TAB,
    ): Boolean = runHelmTerminal(
        id = "helm-rollback-${release.name}-${System.currentTimeMillis()}",
        title = "Rollback: ${release.name} → $revision",
        parts = buildList {
            add("helm rollback")
            add(q(release.name))
            add(revision.toString())
            addAll(targetFlagStrings())
        },
        workingDir = services.context.projectPath,
        location = location,
    )

    fun uninstall(
        release: ReleaseInfo,
        keepHistory: Boolean = false,
        location: OpenLocation = OpenLocation.NEW_TAB,
    ): Boolean = runHelmTerminal(
        id = "helm-uninstall-${release.name}-${System.currentTimeMillis()}",
        title = "Uninstall: ${release.name}",
        parts = buildList {
            add("helm uninstall")
            add(q(release.name))
            if (keepHistory) add("--keep-history")
            addAll(targetFlagStrings())
        },
        workingDir = services.context.projectPath,
        location = location,
    )

    fun test(release: ReleaseInfo, location: OpenLocation = OpenLocation.NEW_TAB): Boolean = runHelmTerminal(
        id = "helm-test-${release.name}-${System.currentTimeMillis()}",
        title = "Test: ${release.name}",
        parts = buildList {
            add("helm test")
            add(q(release.name))
            addAll(targetFlagStrings())
        },
        workingDir = services.context.projectPath,
        location = location,
    )

    // --------------------------------------------------------------- charts

    /** `helm lint` — quick enough to run in-plugin. */
    suspend fun lint(chart: ChartArtifact, valuesFile: File?): HelmExec = HelmCli.exec(
        buildList {
            add("lint")
            add(chart.directory.absolutePath)
            valuesFile?.let { addAll(listOf("-f", it.absolutePath)) }
        },
        workingDir = chart.directory,
        timeoutMs = HelmCli.LONG_TIMEOUT_MS,
    )

    /**
     * `helm template` — rendered locally, then Secret payloads redacted before the
     * text ever reaches a panel or the clipboard.
     */
    suspend fun template(chart: ChartArtifact, releaseName: String, valuesFile: File?): Pair<Boolean, String> {
        val result = HelmCli.exec(
            buildList {
                add("template")
                add(releaseName)
                add(chart.directory.absolutePath)
                valuesFile?.let { addAll(listOf("-f", it.absolutePath)) }
            },
            workingDir = chart.directory,
            timeoutMs = HelmCli.LONG_TIMEOUT_MS,
        )
        return if (result.ok) true to redactRenderedYaml(result.stdout) else false to result.cleanError
    }

    fun dependencyUpdate(chart: ChartArtifact, location: OpenLocation = OpenLocation.NEW_TAB): Boolean =
        runHelmTerminal(
            id = "helm-dep-${chart.name}-${System.currentTimeMillis()}",
            title = "Dependencies: ${chart.name}",
            parts = listOf("helm dependency update", q(chart.directory.absolutePath)),
            workingDir = chart.directory.absolutePath,
            location = location,
        )

    fun packageChart(
        chart: ChartArtifact,
        destination: String,
        location: OpenLocation = OpenLocation.NEW_TAB,
    ): Boolean = runHelmTerminal(
        id = "helm-package-${chart.name}-${System.currentTimeMillis()}",
        title = "Package: ${chart.name}",
        parts = listOf("helm package", q(chart.directory.absolutePath), "-d ${q(destination)}"),
        workingDir = chart.directory.absolutePath,
        location = location,
    )

    /**
     * `helm push` to an OCI registry — the only outward-facing action in the plugin.
     *
     * Note Helm 4.1+ rejects `https://` in `helm registry login`; the OCI target
     * here is `oci://host/path`, which is unchanged, but the login caveat is worth
     * remembering if auth fails.
     */
    fun push(
        packagedChart: String,
        ociTarget: String,
        plainHttp: Boolean = false,
        location: OpenLocation = OpenLocation.NEW_TAB,
    ): Boolean = runHelmTerminal(
        id = "helm-push-${System.currentTimeMillis()}",
        title = "Push: ${File(packagedChart).name}",
        parts = buildList {
            add("helm push")
            add(q(packagedChart))
            add(q(ociTarget))
            // helm speaks HTTPS to OCI registries by default, so a plain-HTTP
            // registry (local mirrors, in-cluster ones) fails with
            // "server gave HTTP response to HTTPS client" unless this is opted in.
            if (plainHttp) add("--plain-http")
        },
        workingDir = File(packagedChart).parent,
        location = location,
    )

    // ---------------------------------------------------------------- repos

    /**
     * `helm repo add` writes `~/.config/helm/repositories.yaml`, which is shared
     * with the user's shells.
     *
     * That is deliberate and different from the kubeconfig stance: a context
     * selection is transient and must not retarget other terminals, whereas a chart
     * repo is a persistent registration you *want* everywhere. Callers confirm
     * first, and the confirmation says it affects the machine.
     */
    fun repoAdd(name: String, url: String, location: OpenLocation = OpenLocation.NEW_TAB): Boolean =
        runHelmTerminal(
            id = "helm-repo-add-$name-${System.currentTimeMillis()}",
            title = "Add repo: $name",
            parts = listOf("helm repo add", q(name), q(url)),
            workingDir = services.context.projectPath,
            location = location,
        )

    fun repoUpdate(location: OpenLocation = OpenLocation.NEW_TAB): Boolean = runHelmTerminal(
        id = "helm-repo-update-${System.currentTimeMillis()}",
        title = "Update repos",
        parts = listOf("helm repo update"),
        workingDir = services.context.projectPath,
        location = location,
    )

    suspend fun repoRemove(name: String): HelmExec = HelmCli.exec(listOf("repo", "remove", name))

    // -------------------------------------------------------------- helpers

    /**
     * Open a terminal tab for a helm command and refresh afterwards.
     *
     * Helm has no watch API, so the release list is nudged on a delay once the
     * command has had time to land — the terminal owns the command, we just want
     * the sidebar to catch up without the user pressing refresh.
     */
    private fun runHelmTerminal(
        id: String,
        title: String,
        parts: List<String>,
        workingDir: String?,
        location: OpenLocation,
    ): Boolean {
        val launched = services.actions.openTerminal(
            id = id,
            title = title,
            command = parts.joinToString(" "),
            workingDir = workingDir,
            location = location,
        )
        if (launched) services.scheduleHelmRefresh()
        return launched
    }

    /** helm's target flags as shell strings, for the terminal-tab commands. */
    private fun targetFlagStrings(): List<String> {
        val target = kube.target.value
        return buildList {
            target.context?.let { add("--kube-context ${q(it)}") }
            if (!target.isAllNamespaces) add("-n ${q(target.namespace)}")
        }
    }

    companion object {
        /** Single-quote for the shell; terminal tabs take a command string. */
        fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
