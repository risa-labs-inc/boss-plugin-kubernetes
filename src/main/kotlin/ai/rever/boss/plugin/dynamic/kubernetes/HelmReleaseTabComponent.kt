package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.ui.BossSecondaryButton
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy

class HelmReleaseTabComponent(
    ctx: ComponentContext,
    override val config: TabInfo,
    private val services: KubeServices,
) : TabComponentWithUI, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = HelmReleaseTabType

    private val viewModel = HelmReleaseTabViewModel(
        services = services,
        tabInfo = config as? HelmReleaseTabInfo
            ?: HelmReleaseTabInfo(contextName = "", namespace = "default", releaseName = config.title),
    )

    init {
        lifecycle.doOnDestroy { viewModel.dispose() }
    }

    @Composable
    override fun Content() {
        BossTheme {
            ReleaseTabScreen(viewModel, services)
        }
    }
}

@Composable
private fun ReleaseTabScreen(viewModel: HelmReleaseTabViewModel, services: KubeServices) {
    val section by viewModel.section.collectAsState()

    Column(Modifier.fillMaxSize().background(BossThemeColors.BackgroundColor)) {
        ReleaseHeader(viewModel)
        Divider(color = BossThemeColors.BorderColor)
        SectionTabs(section, viewModel::selectSection)
        Divider(color = BossThemeColors.BorderColor)

        Box(Modifier.fillMaxSize()) {
            when (section) {
                ReleaseSection.STATUS -> StatusPane(viewModel)
                ReleaseSection.VALUES -> ValuesPane(viewModel, services)
                ReleaseSection.MANIFEST -> ManifestPane(viewModel, services)
                ReleaseSection.HISTORY -> HistoryPane(viewModel)
                ReleaseSection.NOTES -> MonoPane(viewModel.notes.collectAsState().value) { viewModel.refreshNotes() }
            }
        }
    }
}

@Composable
private fun ReleaseHeader(viewModel: HelmReleaseTabViewModel) {
    val release by viewModel.release.collectAsState()
    val busy by viewModel.busy.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BossThemeColors.SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(release)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = viewModel.name,
                color = BossThemeColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildList {
                add("${viewModel.tabInfo.contextName.ifBlank { "no context" }} / ${viewModel.tabInfo.namespace}")
                release?.let {
                    add("rev ${it.revision}")
                    add(it.status)
                    add(it.chart)
                } ?: add("release not found")
            }.joinToString("  ·  ")
            Text(
                text = subtitle,
                color = if (release == null) BossThemeColors.ErrorColor else BossThemeColors.TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = BossThemeColors.TextMuted,
                strokeWidth = 1.5.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (release != null) {
            BossSecondaryButton(text = "Test", onClick = viewModel::test)
        }
    }
}

@Composable
private fun StatusDot(release: ReleaseInfo?) {
    val color = when {
        release == null -> BossThemeColors.TextMuted
        release.isFailed -> BossThemeColors.ErrorColor
        release.isPending -> BossThemeColors.WarningColor
        release.isDeployed -> BossThemeColors.SuccessColor
        else -> BossThemeColors.WarningColor
    }
    Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
}

@Composable
private fun SectionTabs(selected: ReleaseSection, onSelect: (ReleaseSection) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BossThemeColors.SurfaceColor)
            .padding(horizontal = 8.dp),
    ) {
        ReleaseSection.entries.forEach { entry ->
            val isSelected = entry == selected
            Box(
                modifier = Modifier
                    .padding(vertical = 4.dp, horizontal = 2.dp)
                    .background(
                        if (isSelected) BossThemeColors.AccentColor.copy(alpha = 0.18f) else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    ),
            ) {
                Text(
                    text = entry.label,
                    color = if (isSelected) BossThemeColors.TextPrimary else BossThemeColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier
                        .clickable { onSelect(entry) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------- status

@Composable
private fun StatusPane(viewModel: HelmReleaseTabViewModel) {
    val status by viewModel.status.collectAsState()
    val error by viewModel.statusError.collectAsState()

    if (status == null && error == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconTool(icon = Icons.Outlined.Refresh, description = "Refresh") { viewModel.refreshStatus() }
        }
        Divider(color = BossThemeColors.BorderColor)
        error?.let {
            Text(it, color = BossThemeColors.ErrorColor, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            return@Column
        }
        val s = status ?: return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
            InfoLine("Status", s.status)
            InfoLine("Revision", s.revision.toString())
            InfoLine("Namespace", s.namespace)
            InfoLine("Description", s.description)
            InfoLine("First deployed", s.firstDeployed)
            InfoLine("Last deployed", s.lastDeployed)
            if (s.applyMethod.isNotBlank()) InfoLine("Apply method", s.applyMethod)
            Spacer(Modifier.height(12.dp))
            // helm's own `status -o json` embeds the whole rendered manifest,
            // secrets included; it is deliberately not shown here. The Manifest tab
            // fetches it separately and redacts.
            Text(
                text = "The rendered manifest is on the Manifest tab, with Secret values redacted.",
                color = BossThemeColors.TextMuted,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = BossThemeColors.TextMuted, fontSize = 12.sp, modifier = Modifier.width(120.dp))
        Text(value, color = BossThemeColors.TextPrimary, fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------- values

@Composable
private fun ValuesPane(viewModel: HelmReleaseTabViewModel, services: KubeServices) {
    val values by viewModel.values.collectAsState()
    val all by viewModel.allValues.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (all) "all values (merged)" else "user-supplied values",
                color = BossThemeColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (all) "show overrides only" else "show all",
                color = BossThemeColors.AccentColor,
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable { viewModel.setAllValues(!all) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            IconTool(icon = Icons.Outlined.ContentCopy, description = "Copy") {
                services.context.clipboardProvider?.setText(viewModel.valuesText())
                services.toastInfo("Values copied")
            }
            IconTool(icon = Icons.Outlined.Refresh, description = "Refresh") { viewModel.refreshValues() }
        }
        Divider(color = BossThemeColors.BorderColor)
        MonoBody(values) { viewModel.refreshValues() }
    }
}

// ----------------------------------------------------------------- manifest

@Composable
private fun ManifestPane(viewModel: HelmReleaseTabViewModel, services: KubeServices) {
    val manifest by viewModel.manifest.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Secret values are redacted",
                color = BossThemeColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            IconTool(icon = Icons.Outlined.ContentCopy, description = "Copy") {
                services.context.clipboardProvider?.setText(viewModel.manifestText())
                services.toastInfo("Manifest copied (redacted)")
            }
            IconTool(icon = Icons.Outlined.Refresh, description = "Refresh") { viewModel.refreshManifest() }
        }
        Divider(color = BossThemeColors.BorderColor)
        MonoBody(manifest) { viewModel.refreshManifest() }
    }
}

// ------------------------------------------------------------------ history

@Composable
private fun HistoryPane(viewModel: HelmReleaseTabViewModel) {
    val history by viewModel.history.collectAsState()
    val release by viewModel.release.collectAsState()

    if (history == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconTool(icon = Icons.Outlined.Refresh, description = "Refresh") { viewModel.refreshHistory() }
        }
        Divider(color = BossThemeColors.BorderColor)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
            items(history.orEmpty()) { revision ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "rev ${revision.revision}",
                                color = BossThemeColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = revision.status,
                                color = if (revision.isDeployed) {
                                    BossThemeColors.SuccessColor
                                } else {
                                    BossThemeColors.TextMuted
                                },
                                fontSize = 11.sp,
                            )
                        }
                        Text(
                            text = listOfNotNull(
                                revision.description.takeIf { it.isNotBlank() },
                                revision.chart.takeIf { it.isNotBlank() },
                                revision.updated.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            color = BossThemeColors.TextMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Rolling back to the revision that is already deployed is a
                    // no-op that still burns a revision, so it isn't offered.
                    if (revision.revision != release?.revision) {
                        IconTool(
                            icon = Icons.Outlined.Undo,
                            description = "Roll back to ${revision.revision}",
                            tint = BossThemeColors.WarningColor,
                        ) { viewModel.rollbackTo(revision.revision) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- shared

@Composable
private fun MonoPane(content: String?, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconTool(icon = Icons.Outlined.Refresh, description = "Refresh") { onRefresh() }
        }
        Divider(color = BossThemeColors.BorderColor)
        MonoBody(content, onRefresh)
    }
}

@Composable
private fun MonoBody(content: String?, onRefresh: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { if (content == null) onRefresh() }

    if (content == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        return
    }
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            text = content,
            color = BossThemeColors.TextSecondary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
