package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.ui.BossSearchBar
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.AlertDialog
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext

class KubePanelComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val services: KubeServices,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = KubePanelViewModel(services)

    @Composable
    override fun Content() {
        BossTheme {
            KubePanelScreen(viewModel)
        }
    }
}

@Composable
private fun KubePanelScreen(viewModel: KubePanelViewModel) {
    val cluster by viewModel.cluster.collectAsState()
    val query by viewModel.query.collectAsState()
    val confirm by viewModel.confirm.collectAsState()
    val scale by viewModel.scale.collectAsState()
    val crdPicker by viewModel.crdPickerOpen.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BossThemeColors.SurfaceColor),
    ) {
        PanelHeader(viewModel)
        TargetBar(viewModel)
        Divider(color = BossThemeColors.BorderColor)

        when (cluster) {
            is ClusterState.KubectlMissing -> EmptyBody(
                "kubectl isn't installed",
                "Install kubectl (or Docker Desktop's bundled copy) and reopen this panel.",
            )

            is ClusterState.NoContext -> EmptyBody(
                "No kubeconfig context",
                "Add a cluster to ~/.kube/config, or run `gcloud container clusters get-credentials`.",
            )

            is ClusterState.Unreachable -> UnreachableBody(
                (cluster as ClusterState.Unreachable).message,
                viewModel,
            )

            is ClusterState.Forbidden -> EmptyBody(
                "Not allowed on this cluster",
                (cluster as ClusterState.Forbidden).message,
            )

            is ClusterState.Error -> UnreachableBody((cluster as ClusterState.Error).message, viewModel)
            is ClusterState.Unknown -> LoadingBody()
            is ClusterState.Ready -> {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Box(Modifier.height(28.dp).fillMaxWidth()) {
                        BossSearchBar(
                            query = query,
                            onQueryChange = viewModel::setQuery,
                            placeholder = "Filter…",
                        )
                    }
                }
                KubeLists(viewModel)
            }
        }
    }

    confirm?.let { ConfirmDialog(viewModel, it) }
    scale?.let { ScaleDialog(viewModel, it) }
    if (crdPicker) CrdPickerDialog(viewModel)

    val install by viewModel.install.collectAsState()
    val repoAdd by viewModel.repoAdd.collectAsState()
    val output by viewModel.output.collectAsState()
    install?.let { InstallDialog(viewModel, it) }
    repoAdd?.let { RepoAddDialog(viewModel, it) }
    output?.let { OutputDialog(viewModel, it) }
}

@Composable
private fun PanelHeader(viewModel: KubePanelViewModel) {
    val busy by viewModel.busy.collectAsState()
    val cluster by viewModel.cluster.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "KUBERNETES",
            color = BossThemeColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        (cluster as? ClusterState.Ready)?.let {
            Spacer(Modifier.width(6.dp))
            Text(it.serverVersion, color = BossThemeColors.TextMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.weight(1f))
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = BossThemeColors.TextMuted,
                strokeWidth = 1.5.dp,
            )
            Spacer(Modifier.width(6.dp))
        }
        IconTool(icon = Icons.Outlined.Refresh, description = "Refresh") { viewModel.refresh() }
    }
}

/**
 * Context and namespace, permanently visible.
 *
 * This is the safety anchor of the whole panel: every destructive action is one
 * click from here, so what they'd hit must never be a guess. Selecting either is
 * local to the plugin — the kubeconfig is not touched.
 */
@Composable
private fun TargetBar(viewModel: KubePanelViewModel) {
    val target by viewModel.target.collectAsState()
    val contexts by viewModel.contexts.collectAsState()
    val namespaces by viewModel.namespaces.collectAsState()

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Selector(
            label = target.context ?: "no context",
            options = contexts.map { it.name },
            emptyHint = "no contexts",
            onSelect = viewModel::selectContext,
            modifier = Modifier.weight(1f),
        )
        Text("/", color = BossThemeColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
        Selector(
            label = if (target.isAllNamespaces) "all namespaces" else target.namespace,
            options = listOf(KubeTarget.ALL_NAMESPACES) + namespaces.map { it.name },
            optionLabel = { if (it == KubeTarget.ALL_NAMESPACES) "all namespaces" else it },
            emptyHint = "no namespaces",
            onSelect = viewModel::selectNamespace,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Selector(
    label: String,
    options: List<String>,
    emptyHint: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    optionLabel: (String) -> String = { it },
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                .clickable { open = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = BossThemeColors.TextPrimary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.UnfoldMore,
                contentDescription = null,
                tint = BossThemeColors.TextMuted,
                modifier = Modifier.size(12.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(onClick = { open = false }) {
                    Text(emptyHint, fontSize = 12.sp, color = BossThemeColors.TextMuted)
                }
            }
            options.forEach { option ->
                DropdownMenuItem(
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                ) {
                    Text(
                        text = optionLabel(option),
                        fontSize = 12.sp,
                        color = if (option == label) BossThemeColors.AccentColor else BossThemeColors.TextPrimary,
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------- cluster states

@Composable
private fun LoadingBody() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun EmptyBody(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = KubeIcon,
            contentDescription = null,
            tint = BossThemeColors.TextMuted.copy(alpha = 0.5f),
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(title, color = BossThemeColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(detail, color = BossThemeColors.TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun UnreachableBody(message: String, viewModel: KubePanelViewModel) {
    val target by viewModel.target.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = KubeIcon,
            contentDescription = null,
            tint = BossThemeColors.TextMuted.copy(alpha = 0.5f),
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Can't reach ${target.context ?: "the cluster"}",
            color = BossThemeColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message.ifBlank { "The API server did not respond." },
            color = BossThemeColors.TextMuted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = viewModel::refresh) {
            Text("Retry", color = BossThemeColors.AccentColor, fontSize = 12.sp)
        }
    }
}

// -------------------------------------------------------------------- lists

@Composable
private fun KubeLists(viewModel: KubePanelViewModel) {
    val expanded by viewModel.expanded.collectAsState()
    val manifests by viewModel.manifests.collectAsState()
    val workloads by viewModel.workloads.collectAsState()
    val pods by viewModel.pods.collectAsState()
    val svcs by viewModel.services_.collectAsState()
    val ingresses by viewModel.ingresses.collectAsState()
    val jobs by viewModel.jobs.collectAsState()
    val configMaps by viewModel.configMaps.collectAsState()
    val secrets by viewModel.secrets.collectAsState()
    val pvcs by viewModel.pvcs.collectAsState()
    val pinned by viewModel.pinnedCustom.collectAsState()
    val customRows by viewModel.customRows.collectAsState()
    val forwards by viewModel.forwards.collectAsState()

    val charts by viewModel.charts.collectAsState()
    val releases by viewModel.releases.collectAsState()
    val repos by viewModel.repos.collectAsState()
    val helmState by viewModel.helmState.collectAsState()

    val shownManifests = manifests.filter { viewModel.matches(it.relativePath) }
    val shownWorkloads = workloads.filter { viewModel.matches(it.name) }
    val shownPods = pods.filter { viewModel.matches(it.name) }
    val shownServices = svcs.filter { viewModel.matches(it.name) }

    LazyColumn(Modifier.fillMaxSize()) {
        section(KubeSection.PROJECT, shownManifests.size, expanded, viewModel) {
            if (shownManifests.isEmpty()) item { HintRow("No Kubernetes manifests in this project") }
            items(shownManifests, key = { it.file.absolutePath }) { artifact ->
                RowShell(
                    title = artifact.relativePath,
                    subtitle = if (artifact.kind == ManifestArtifact.Kind.KUSTOMIZATION) "kustomization" else "manifest",
                    trailing = {
                        IconTool(
                            icon = Icons.Outlined.PlayArrow,
                            description = "Apply",
                            tint = BossThemeColors.AccentColor,
                        ) { viewModel.apply(artifact) }
                    },
                    actions = listOf(
                        RowAction("Apply…") { viewModel.apply(artifact) },
                        RowAction("Dry-run apply") { viewModel.applyDryRun(artifact) },
                        RowAction("Diff") { viewModel.diff(artifact) },
                    ),
                )
            }
        }

        // Helm charts are project artifacts, so they live in Project rather than
        // earning a section of their own.
        val shownCharts = charts.filter { viewModel.matches(it.relativePath) || viewModel.matches(it.name) }
        if (KubeSection.PROJECT in expanded && shownCharts.isNotEmpty()) {
            items(shownCharts, key = { "chart-" + it.chartFile.absolutePath }) { chart ->
                RowShell(
                    title = chart.relativePath.removeSuffix("/Chart.yaml").removeSuffix("Chart.yaml").trimEnd('/'),
                    subtitle = listOfNotNull(
                        "helm chart",
                        chart.version.takeIf { it.isNotBlank() },
                        "${chart.valuesFiles.size} values files".takeIf { chart.valuesFiles.isNotEmpty() },
                    ).joinToString(" · "),
                    leading = {
                        Icon(
                            imageVector = HelmIcon,
                            contentDescription = null,
                            tint = BossThemeColors.TextMuted,
                            modifier = Modifier.size(10.dp),
                        )
                    },
                    trailing = {
                        IconTool(
                            icon = Icons.Outlined.PlayArrow,
                            description = "Install",
                            tint = BossThemeColors.AccentColor,
                        ) { viewModel.beginInstall(chart) }
                    },
                    actions = listOf(
                        RowAction("Install…") { viewModel.beginInstall(chart) },
                        RowAction("Lint") { viewModel.lint(chart) },
                        RowAction("Template (redacted)") { viewModel.template(chart) },
                        RowAction("Update dependencies") { viewModel.dependencyUpdate(chart) },
                        RowAction("Package") { viewModel.packageChart(chart) },
                    ),
                )
            }
        }

        section(KubeSection.WORKLOADS, shownWorkloads.size, expanded, viewModel) {
            if (shownWorkloads.isEmpty()) item { HintRow("No workloads") }
            items(shownWorkloads, key = { it.kind + "/" + it.name }) { workload ->
                RowShell(
                    title = workload.name,
                    subtitle = listOfNotNull(
                        workload.kind,
                        workload.readyDisplay,
                        workload.images.firstOrNull()?.substringAfterLast('/'),
                    ).joinToString(" · "),
                    onClick = { viewModel.openResource(workload.kind, workload.name) },
                    leading = { StatusDot(healthy = workload.isHealthy, warning = workload.desired == 0) },
                    actions = buildList {
                        add(RowAction("Open") { viewModel.openResource(workload.kind, workload.name) })
                        if (workload.isScalable) add(RowAction("Scale…") { viewModel.beginScale(workload) })
                        add(RowAction("Restart rollout") { viewModel.rolloutRestart(workload) })
                        add(RowAction("Delete", destructive = true) { viewModel.delete(workload.kind, workload.name) })
                    },
                )
            }
        }

        section(KubeSection.PODS, shownPods.size, expanded, viewModel) {
            if (shownPods.isEmpty()) item { HintRow("No pods") }
            items(shownPods, key = { it.name }) { pod ->
                RowShell(
                    title = pod.name,
                    subtitle = listOfNotNull(
                        pod.phase,
                        pod.ready,
                        "${pod.restarts} restarts".takeIf { pod.restarts > 0 },
                    ).joinToString(" · "),
                    onClick = { viewModel.openResource("Pod", pod.name) },
                    leading = { StatusDot(healthy = pod.isRunning, warning = !pod.isFailing) },
                    actions = listOf(
                        RowAction("Open logs") { viewModel.openResource("Pod", pod.name) },
                        RowAction("Shell") { viewModel.exec(pod) },
                        RowAction("Delete", destructive = true) { viewModel.delete("Pod", pod.name) },
                    ),
                )
            }
        }

        section(KubeSection.SERVICES, shownServices.size, expanded, viewModel) {
            if (shownServices.isEmpty()) item { HintRow("No services") }
            items(shownServices, key = { it.name }) { svc ->
                val forward = svc.primaryPort?.let { p ->
                    forwards.values.firstOrNull { it.key.ref == svc.ref && it.key.remotePort == p.port }
                }
                RowShell(
                    title = svc.name,
                    subtitle = listOfNotNull(
                        svc.type,
                        svc.ports.joinToString(",") { it.display }.takeIf { it.isNotBlank() },
                        forward?.let { "→ ${it.localPort} (${it.status.name.lowercase()})" },
                    ).joinToString(" · "),
                    onClick = { viewModel.openResource("Service", svc.name) },
                    leading = { StatusDot(healthy = forward?.status == ForwardStatus.ACTIVE, warning = forward != null) },
                    trailing = {
                        if (forward != null) {
                            IconTool(
                                icon = Icons.Outlined.OpenInBrowser,
                                description = "Open ${forward.localUrl}",
                            ) { viewModel.openForwardUrl(svc) }
                        }
                    },
                    actions = buildList {
                        add(RowAction("Open") { viewModel.openResource("Service", svc.name) })
                        add(
                            RowAction(if (forward != null) "Stop port-forward" else "Port-forward") {
                                viewModel.toggleForward(svc)
                            },
                        )
                        add(RowAction("Delete", destructive = true) { viewModel.delete("Service", svc.name) })
                    },
                )
            }
        }

        section(KubeSection.INGRESSES, ingresses.size, expanded, viewModel) {
            if (ingresses.isEmpty()) item { HintRow("No ingresses") }
            items(ingresses, key = { it.name }) { ing ->
                RowShell(
                    title = ing.name,
                    subtitle = listOfNotNull(
                        ing.className.ifBlank { null },
                        ing.hosts.joinToString(",").ifBlank { null },
                    ).joinToString(" · "),
                    onClick = { viewModel.openResource("Ingress", ing.name) },
                    actions = listOf(
                        RowAction("Open") { viewModel.openResource("Ingress", ing.name) },
                        RowAction("Delete", destructive = true) { viewModel.delete("Ingress", ing.name) },
                    ),
                )
            }
        }

        section(KubeSection.JOBS, jobs.size, expanded, viewModel) {
            if (jobs.isEmpty()) item { HintRow("No jobs or cronjobs") }
            items(jobs, key = { it.kind + "/" + it.name }) { job ->
                RowShell(
                    title = job.name,
                    subtitle = "${job.kind} · ${job.detail}",
                    onClick = { viewModel.openResource(job.kind, job.name) },
                    actions = listOf(
                        RowAction("Open") { viewModel.openResource(job.kind, job.name) },
                        RowAction("Delete", destructive = true) { viewModel.delete(job.kind, job.name) },
                    ),
                )
            }
        }

        section(KubeSection.CONFIGMAPS, configMaps.size, expanded, viewModel) {
            if (configMaps.isEmpty()) item { HintRow("No config maps") }
            items(configMaps.filter { viewModel.matches(it.name) }, key = { it.name }) { cm ->
                RowShell(
                    title = cm.name,
                    subtitle = "ConfigMap",
                    onClick = { viewModel.openResource("ConfigMap", cm.name) },
                    actions = listOf(
                        RowAction("Open") { viewModel.openResource("ConfigMap", cm.name) },
                        RowAction("Delete", destructive = true) { viewModel.delete("ConfigMap", cm.name) },
                    ),
                )
            }
        }

        section(KubeSection.SECRETS, secrets.size, expanded, viewModel) {
            if (secrets.isEmpty()) item { HintRow("No secrets") }
            items(secrets.filter { viewModel.matches(it.name) }, key = { it.name }) { secret ->
                RowShell(
                    // Names and types only. Values are never fetched, so there is
                    // nothing here that could render one.
                    title = secret.name,
                    subtitle = secret.type,
                    onClick = { viewModel.openResource("Secret", secret.name) },
                    actions = listOf(
                        RowAction("Open (no values)") { viewModel.openResource("Secret", secret.name) },
                        RowAction("Delete", destructive = true) { viewModel.delete("Secret", secret.name) },
                    ),
                )
            }
        }

        section(KubeSection.PVCS, pvcs.size, expanded, viewModel) {
            if (pvcs.isEmpty()) item { HintRow("No volume claims") }
            items(pvcs.filter { viewModel.matches(it.name) }, key = { it.name }) { pvc ->
                RowShell(
                    title = pvc.name,
                    subtitle = listOfNotNull(pvc.phase, pvc.capacity.ifBlank { null }, pvc.storageClass.ifBlank { null })
                        .joinToString(" · "),
                    onClick = { viewModel.openResource("PersistentVolumeClaim", pvc.name) },
                    actions = listOf(
                        RowAction("Open") { viewModel.openResource("PersistentVolumeClaim", pvc.name) },
                        RowAction("Delete", destructive = true) {
                            viewModel.delete("PersistentVolumeClaim", pvc.name)
                        },
                    ),
                )
            }
        }

        section(KubeSection.RELEASES, releases.size, expanded, viewModel) {
            if (helmState is HelmState.Missing) {
                item { HintRow("helm isn't installed — install it to manage releases") }
            } else if (releases.isEmpty()) {
                item { HintRow("No Helm releases in this namespace") }
            }
            items(releases.filter { viewModel.matches(it.name) || viewModel.matches(it.chart) }, key = { it.name }) { release ->
                RowShell(
                    title = release.name,
                    subtitle = listOf("rev ${release.revision}", release.status, release.chart).joinToString(" · "),
                    onClick = { viewModel.openRelease(release) },
                    leading = {
                        StatusDot(
                            healthy = release.isDeployed,
                            warning = release.isPending || !release.isFailed,
                        )
                    },
                    actions = listOf(
                        RowAction("Open") { viewModel.openRelease(release) },
                        RowAction("Upgrade") { viewModel.upgradeRelease(release) },
                        RowAction("Roll back") { viewModel.rollbackRelease(release) },
                        RowAction("Run tests") { viewModel.testRelease(release) },
                        RowAction("Uninstall", destructive = true) { viewModel.uninstallRelease(release) },
                    ),
                )
            }
        }

        section(KubeSection.REPOS, repos.size, expanded, viewModel) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = viewModel::beginRepoAdd) {
                        Text("Add repo…", color = BossThemeColors.AccentColor, fontSize = 11.sp)
                    }
                    TextButton(onClick = viewModel::updateRepos) {
                        Text("Update all", color = BossThemeColors.AccentColor, fontSize = 11.sp)
                    }
                }
            }
            if (helmState is HelmState.Missing) {
                item { HintRow("helm isn't installed") }
            } else if (repos.isEmpty()) {
                item { HintRow("No chart repositories configured") }
            }
            items(repos, key = { it.name }) { repo ->
                RowShell(
                    title = repo.name,
                    subtitle = repo.url,
                    actions = listOf(
                        RowAction("Remove", destructive = true) { viewModel.removeRepo(repo) },
                    ),
                )
            }
        }

        section(KubeSection.CUSTOM, pinned.size, expanded, viewModel) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = viewModel::openCrdPicker) {
                        Text("Pin a resource type…", color = BossThemeColors.AccentColor, fontSize = 11.sp)
                    }
                }
            }
            pinned.forEach { resource ->
                item(key = "crd-$resource") {
                    RowShell(
                        title = resource,
                        subtitle = "${customRows[resource]?.size ?: 0} objects",
                        actions = listOf(RowAction("Unpin") { viewModel.unpinCustom(resource) }),
                    )
                }
                items(customRows[resource].orEmpty(), key = { "$resource/$it" }) { name ->
                    RowShell(
                        title = name,
                        subtitle = "",
                        indent = 34.dp,
                        onClick = { viewModel.openResource(resource, name) },
                        actions = listOf(
                            RowAction("Open") { viewModel.openResource(resource, name) },
                            RowAction("Delete", destructive = true) { viewModel.delete(resource, name) },
                        ),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.section(
    section: KubeSection,
    count: Int,
    expanded: Set<KubeSection>,
    viewModel: KubePanelViewModel,
    body: LazyListScope.() -> Unit,
) {
    item(key = "header-${section.name}") {
        SectionHeader(section, count, section in expanded) { viewModel.toggleSection(section) }
    }
    if (section in expanded) body()
}

@Composable
private fun SectionHeader(section: KubeSection, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = BossThemeColors.TextMuted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = section.label.uppercase(),
            color = BossThemeColors.TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Text(text = count.toString(), color = BossThemeColors.TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun StatusDot(healthy: Boolean, warning: Boolean) {
    val color = when {
        healthy -> BossThemeColors.SuccessColor
        warning -> BossThemeColors.WarningColor
        else -> BossThemeColors.ErrorColor
    }
    Box(Modifier.size(6.dp).background(color, RoundedCornerShape(3.dp)))
}

@Composable
private fun HintRow(text: String) {
    Text(
        text = text,
        color = BossThemeColors.TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 26.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
    )
}

private data class RowAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun RowShell(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    indent: androidx.compose.ui.unit.Dp = 22.dp,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    actions: List<RowAction> = emptyList(),
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(start = indent, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = BossThemeColors.TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = BossThemeColors.TextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
        if (actions.isNotEmpty()) {
            Box {
                IconTool(icon = Icons.Outlined.MoreVert, description = "Actions") { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            onClick = {
                                menuOpen = false
                                action.onClick()
                            },
                        ) {
                            Text(
                                text = action.label,
                                fontSize = 12.sp,
                                color = if (action.destructive) {
                                    BossThemeColors.ErrorColor
                                } else {
                                    BossThemeColors.TextPrimary
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
private fun ConfirmDialog(viewModel: KubePanelViewModel, request: ConfirmRequest) {
    AlertDialog(
        onDismissRequest = viewModel::dismissConfirm,
        backgroundColor = BossThemeColors.SurfaceColor,
        title = { Text(request.title, color = BossThemeColors.TextPrimary, fontSize = 14.sp) },
        text = { Text(request.message, color = BossThemeColors.TextSecondary, fontSize = 12.sp) },
        confirmButton = {
            TextButton(onClick = viewModel::acceptConfirm) {
                Text(request.confirmLabel, color = BossThemeColors.ErrorColor, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissConfirm) {
                Text("Cancel", color = BossThemeColors.TextSecondary, fontSize = 12.sp)
            }
        },
    )
}

@Composable
private fun ScaleDialog(viewModel: KubePanelViewModel, request: ScaleRequest) {
    AlertDialog(
        onDismissRequest = viewModel::cancelScale,
        backgroundColor = BossThemeColors.SurfaceColor,
        title = { Text("Scale ${request.workload.ref}", color = BossThemeColors.TextPrimary, fontSize = 14.sp) },
        text = {
            Column {
                Text(
                    text = "Currently ${request.workload.readyDisplay} in ${request.workload.namespace}.",
                    color = BossThemeColors.TextMuted,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Replicas",
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.width(80.dp),
                    )
                    BasicTextField(
                        value = request.replicas,
                        onValueChange = { input -> viewModel.updateScale(input.filter { it.isDigit() }.take(3)) },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = BossThemeColors.TextPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(BossThemeColors.AccentColor),
                        modifier = Modifier
                            .width(70.dp)
                            .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmScale, enabled = request.isValid) {
                Text(
                    "Scale",
                    color = if (request.isValid) BossThemeColors.AccentColor else BossThemeColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelScale) {
                Text("Cancel", color = BossThemeColors.TextSecondary, fontSize = 12.sp)
            }
        },
    )
}

/**
 * CRD picker. Custom resources are pinned rather than listed wholesale: a real
 * cluster has dozens of CRDs and thousands of objects, and watching them all would
 * be absurd.
 */
@Composable
private fun CrdPickerDialog(viewModel: KubePanelViewModel) {
    val resources by viewModel.apiResources.collectAsState()
    val pinned by viewModel.pinnedCustom.collectAsState()
    var filter by remember { mutableStateOf("") }

    val candidates = resources
        .filter { it.namespaced && it.name !in pinned }
        .filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
        .sortedBy { it.name }

    AlertDialog(
        onDismissRequest = viewModel::closeCrdPicker,
        backgroundColor = BossThemeColors.SurfaceColor,
        title = { Text("Pin a resource type", color = BossThemeColors.TextPrimary, fontSize = 14.sp) },
        text = {
            Column {
                Box(Modifier.height(28.dp).fillMaxWidth()) {
                    BossSearchBar(query = filter, onQueryChange = { filter = it }, placeholder = "Filter types…")
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    if (candidates.isEmpty()) {
                        Text("Nothing matches", color = BossThemeColors.TextMuted, fontSize = 12.sp)
                    }
                    candidates.take(200).forEach { resource ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.pinCustom(resource.name) }
                                .padding(vertical = 5.dp),
                        ) {
                            Text(
                                resource.name,
                                color = BossThemeColors.TextPrimary,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text(resource.apiVersion, color = BossThemeColors.TextMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::closeCrdPicker) {
                Text("Close", color = BossThemeColors.TextSecondary, fontSize = 12.sp)
            }
        },
    )
}

@Composable
private fun InstallDialog(viewModel: KubePanelViewModel, request: InstallRequest) {
    AlertDialog(
        onDismissRequest = viewModel::cancelInstall,
        backgroundColor = BossThemeColors.SurfaceColor,
        title = { Text("Install ${request.chart.name}", color = BossThemeColors.TextPrimary, fontSize = 14.sp) },
        text = {
            Column {
                Text(
                    text = request.chart.relativePath,
                    color = BossThemeColors.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Release",
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.width(80.dp),
                    )
                    BasicTextField(
                        value = request.releaseName,
                        onValueChange = { viewModel.updateInstall(releaseName = it.lowercase().take(53)) },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = BossThemeColors.TextPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(BossThemeColors.AccentColor),
                        modifier = Modifier
                            .width(180.dp)
                            .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                if (request.chart.valuesFiles.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Values file", color = BossThemeColors.TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    request.chart.valuesFiles.forEach { file ->
                        val selected = file.name == request.valuesFile
                        Text(
                            text = (if (selected) "● " else "○ ") + file.name,
                            color = if (selected) BossThemeColors.AccentColor else BossThemeColors.TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clickable { viewModel.updateInstall(valuesFile = file.name) }
                                .padding(vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Installs into the selected context and namespace; the confirmation names both.",
                    color = BossThemeColors.TextMuted,
                    fontSize = 10.sp,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = viewModel::dryRunInstall) {
                    Text("Dry run", color = BossThemeColors.TextSecondary, fontSize = 12.sp)
                }
                TextButton(onClick = viewModel::confirmInstall, enabled = request.isValid) {
                    Text(
                        "Install",
                        color = if (request.isValid) BossThemeColors.AccentColor else BossThemeColors.TextMuted,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelInstall) {
                Text("Cancel", color = BossThemeColors.TextSecondary, fontSize = 12.sp)
            }
        },
    )
}

@Composable
private fun RepoAddDialog(viewModel: KubePanelViewModel, request: RepoAddRequest) {
    AlertDialog(
        onDismissRequest = viewModel::cancelRepoAdd,
        backgroundColor = BossThemeColors.SurfaceColor,
        title = { Text("Add chart repository", color = BossThemeColors.TextPrimary, fontSize = 14.sp) },
        text = {
            Column {
                LabelledField("Name", request.name) { viewModel.updateRepoAdd(name = it) }
                Spacer(Modifier.height(8.dp))
                LabelledField("URL", request.url) { viewModel.updateRepoAdd(url = it) }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Writes ~/.config/helm/repositories.yaml — shared with your shells, not just BOSS.",
                    color = BossThemeColors.TextMuted,
                    fontSize = 10.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmRepoAdd, enabled = request.isValid) {
                Text(
                    "Add",
                    color = if (request.isValid) BossThemeColors.AccentColor else BossThemeColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelRepoAdd) {
                Text("Cancel", color = BossThemeColors.TextSecondary, fontSize = 12.sp)
            }
        },
    )
}

@Composable
private fun LabelledField(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = BossThemeColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(56.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                color = BossThemeColors.TextPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(BossThemeColors.AccentColor),
            modifier = Modifier
                .width(260.dp)
                .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/**
 * Read-only output (lint, template, dry run).
 *
 * Rendered manifests deliberately land here rather than in a terminal: they can
 * contain Secret payloads, and this path has already had them redacted.
 */
@Composable
private fun OutputDialog(viewModel: KubePanelViewModel, request: OutputRequest) {
    AlertDialog(
        onDismissRequest = viewModel::dismissOutput,
        backgroundColor = BossThemeColors.SurfaceColor,
        title = {
            Column {
                Text(request.title, color = BossThemeColors.TextPrimary, fontSize = 14.sp)
                if (request.redacted) {
                    Text("Secret values redacted", color = BossThemeColors.TextMuted, fontSize = 10.sp)
                }
            }
        },
        text = {
            Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = request.body.ifBlank { "(no output)" },
                    color = BossThemeColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::dismissOutput) {
                Text("Close", color = BossThemeColors.TextSecondary, fontSize = 12.sp)
            }
        },
    )
}

@Composable
internal fun IconTool(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color = BossThemeColors.TextSecondary,
    onClick: () -> Unit,
) {
    androidx.compose.material.IconButton(onClick = onClick, modifier = Modifier.size(26.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
    }
}
