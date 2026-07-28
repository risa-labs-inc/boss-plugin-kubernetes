package ai.rever.boss.plugin.dynamic.kubernetes

import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.ui.BossPrimaryButton
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

class KubeResourceTabComponent(
    ctx: ComponentContext,
    override val config: TabInfo,
    private val services: KubeServices,
) : TabComponentWithUI, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = KubeResourceTabType

    private val viewModel = KubeResourceTabViewModel(
        services = services,
        tabInfo = config as? KubeResourceTabInfo
            ?: KubeResourceTabInfo(
                contextName = "",
                namespace = "default",
                kind = "Pod",
                resourceName = config.title,
            ),
    )

    init {
        lifecycle.doOnDestroy { viewModel.dispose() }
    }

    @Composable
    override fun Content() {
        BossTheme {
            ResourceTabScreen(viewModel, services)
        }
    }
}

@Composable
private fun ResourceTabScreen(viewModel: KubeResourceTabViewModel, services: KubeServices) {
    val section by viewModel.section.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().background(BossThemeColors.BackgroundColor),
    ) {
        ResourceHeader(viewModel)
        Divider(color = BossThemeColors.BorderColor)
        SectionTabs(viewModel.availableSections, section, viewModel::selectSection)
        Divider(color = BossThemeColors.BorderColor)

        Box(Modifier.fillMaxSize()) {
            when (section) {
                ResourceSection.LOGS -> LogsPane(viewModel, services)
                ResourceSection.PREVIEW -> PreviewPane(viewModel, services)
                ResourceSection.DESCRIBE -> TextPane(viewModel.describe.collectAsState().value) {
                    viewModel.refreshDescribe()
                }
                ResourceSection.YAML -> TextPane(viewModel.yaml.collectAsState().value) { viewModel.refreshYaml() }
                ResourceSection.EVENTS -> EventsPane(viewModel)
            }
        }
    }
}

@Composable
private fun ResourceHeader(viewModel: KubeResourceTabViewModel) {
    val pod by viewModel.pod.collectAsState()
    val service by viewModel.service.collectAsState()
    val status by viewModel.status.collectAsState()
    val exists by viewModel.exists.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BossThemeColors.SurfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "${viewModel.kind.lowercase()}/${viewModel.name}",
                color = BossThemeColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildList {
                add("${viewModel.tabInfo.contextName.ifBlank { "no context" }} / ${viewModel.tabInfo.namespace}")
                pod?.let { add(it.phase); add(it.ready) }
                service?.let { svc -> add(svc.type); svc.primaryPort?.let { add(it.display) } }
                if (!exists) add("not found")
            }.joinToString("  ·  ")
            Text(
                text = subtitle,
                color = if (exists) BossThemeColors.TextMuted else BossThemeColors.ErrorColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        status?.let {
            Text(it, color = BossThemeColors.WarningColor, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
        }

        if (pod != null) {
            BossSecondaryButton(text = "Shell", onClick = viewModel::openExec)
        }
        if (viewModel.kind.lowercase().trimEnd('s') in setOf("deployment", "statefulset", "daemonset")) {
            Spacer(Modifier.width(6.dp))
            BossSecondaryButton(text = "Restart", onClick = viewModel::restartWorkload)
        }
    }
}

@Composable
private fun SectionTabs(
    sections: List<ResourceSection>,
    selected: ResourceSection,
    onSelect: (ResourceSection) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BossThemeColors.SurfaceColor)
            .padding(horizontal = 8.dp),
    ) {
        sections.forEach { entry ->
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

// -------------------------------------------------------------------- logs

@Composable
private fun LogsPane(viewModel: KubeResourceTabViewModel, services: KubeServices) {
    val logs by viewModel.logs.collectAsState()
    val paused by viewModel.paused.collectAsState()
    val autoScroll by viewModel.autoScroll.collectAsState()
    val pod by viewModel.pod.collectAsState()
    val selectedContainer by viewModel.selectedContainer.collectAsState()
    val previous by viewModel.previous.collectAsState()
    val listState = rememberLazyListState()
    var containerMenu by remember { mutableStateOf(false) }

    LaunchedEffect(logs.size, autoScroll) {
        if (autoScroll && logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Multi-container pods need an explicit choice: `kubectl logs` errors
            // out rather than guessing.
            if (pod?.needsContainerChoice == true) {
                Box {
                    Row(
                        modifier = Modifier
                            .background(BossThemeColors.SurfaceColor, RoundedCornerShape(4.dp))
                            .clickable { containerMenu = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = selectedContainer ?: "container",
                            color = BossThemeColors.TextPrimary,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Outlined.UnfoldMore,
                            contentDescription = null,
                            tint = BossThemeColors.TextMuted,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                    DropdownMenu(expanded = containerMenu, onDismissRequest = { containerMenu = false }) {
                        pod?.containers?.forEach { name ->
                            DropdownMenuItem(
                                onClick = {
                                    containerMenu = false
                                    viewModel.selectContainer(name)
                                },
                            ) {
                                Text(name, fontSize = 12.sp, color = BossThemeColors.TextPrimary)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
            }

            Text(
                text = "${logs.size} lines",
                color = BossThemeColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )

            // `--previous` is the only way to see why a crash-looping pod died.
            Text(
                text = if (previous) "previous ✓" else "previous",
                color = if (previous) BossThemeColors.AccentColor else BossThemeColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable { viewModel.setPrevious(!previous) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            IconTool(
                icon = if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                description = if (paused) "Resume" else "Pause",
            ) { viewModel.setPaused(!paused) }
            IconTool(
                icon = Icons.Outlined.VerticalAlignBottom,
                description = "Auto-scroll",
                tint = if (autoScroll) BossThemeColors.AccentColor else BossThemeColors.TextMuted,
            ) { viewModel.setAutoScroll(!autoScroll) }
            IconTool(icon = Icons.Outlined.ContentCopy, description = "Copy") {
                services.context.clipboardProvider?.setText(viewModel.logText())
                services.toastInfo("Logs copied")
            }
            IconTool(icon = Icons.Outlined.Delete, description = "Clear") { viewModel.clearLogs() }
        }
        Divider(color = BossThemeColors.BorderColor)

        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Waiting for output…", color = BossThemeColors.TextMuted, fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(BossThemeColors.BackgroundColor)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                items(logs) { line ->
                    Text(
                        text = line,
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------- preview

/**
 * Live preview of what the resource serves, reached through a supervised
 * port-forward.
 *
 * Degrades in steps: nothing forwardable → explain; forward still coming up →
 * show its status; no embedded browser → offer a host browser tab. A dead pane is
 * never the outcome.
 */
@Composable
private fun PreviewPane(viewModel: KubeResourceTabViewModel, services: KubeServices) {
    val ports = viewModel.forwardablePorts()
    val forwards by viewModel.forwards.collectAsState()

    if (ports.isEmpty()) {
        CenteredNotice(
            "Nothing to forward",
            "This resource exposes no TCP port, so there is nothing to preview.",
        )
        return
    }

    val remotePort = ports.first()
    val forward = forwards.values.firstOrNull { it.key.ref == viewModel.ref() && it.key.remotePort == remotePort }

    // Starting the forward is what makes the preview possible, so do it on first
    // view rather than making the user find a button.
    LaunchedEffect(remotePort) {
        if (forward == null) viewModel.startForward(remotePort)
    }

    if (forward == null || forward.status == ForwardStatus.STARTING) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(10.dp))
                Text("Starting port-forward to :$remotePort…", color = BossThemeColors.TextMuted, fontSize = 12.sp)
            }
        }
        return
    }

    if (forward.status == ForwardStatus.FAILED) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Port-forward failed", color = BossThemeColors.ErrorColor, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(forward.message.ifBlank { "kubectl exited" }, color = BossThemeColors.TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            BossPrimaryButton(text = "Try again", onClick = { viewModel.startForward(remotePort) })
        }
        return
    }

    val browserService = services.context.browserService
    val url = forward.localUrl

    if (browserService == null || !browserService.isAvailable()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Embedded preview unavailable",
                color = BossThemeColors.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text("Forwarding on $url", color = BossThemeColors.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            BossPrimaryButton(
                text = "Open in browser tab",
                onClick = { services.openUrl(url, viewModel.name) },
                icon = Icons.Outlined.OpenInBrowser,
            )
        }
        return
    }

    var handle by remember(url) { mutableStateOf<BrowserHandle?>(null) }

    LaunchedEffect(url) {
        handle?.dispose()
        handle = browserService.createBrowser(BrowserConfig(url = url))
    }
    DisposableEffect(url) {
        onDispose {
            handle?.dispose()
            handle = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$url → :$remotePort" +
                    if (forward.restarts > 0) "  (restarted ${forward.restarts}×)" else "",
                color = BossThemeColors.TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            if (forward.status == ForwardStatus.RETRYING) {
                Text("reconnecting…", color = BossThemeColors.WarningColor, fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
            }
            IconTool(icon = Icons.Outlined.Refresh, description = "Reload") { handle?.reload() }
            IconTool(icon = Icons.Outlined.OpenInBrowser, description = "Open in browser tab") {
                services.openUrl(url, viewModel.name)
            }
            IconTool(icon = Icons.Outlined.Delete, description = "Stop forwarding") {
                viewModel.stopForward(remotePort)
            }
        }
        Divider(color = BossThemeColors.BorderColor)
        Box(Modifier.fillMaxSize()) {
            val current = handle
            if (current != null && current.isValid) {
                current.Content()
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

// --------------------------------------------------------- describe / yaml

@Composable
private fun TextPane(content: String?, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) { if (content == null) onRefresh() }

    if (content == null) {
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
            IconTool(icon = Icons.Outlined.Refresh, description = "Refresh") { onRefresh() }
        }
        Divider(color = BossThemeColors.BorderColor)
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
}

// ------------------------------------------------------------------ events

@Composable
private fun EventsPane(viewModel: KubeResourceTabViewModel) {
    val events by viewModel.events.collectAsState()

    LaunchedEffect(Unit) { if (events == null) viewModel.refreshEvents() }

    if (events == null) {
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
            IconTool(icon = Icons.Outlined.Refresh, description = "Refresh") { viewModel.refreshEvents() }
        }
        Divider(color = BossThemeColors.BorderColor)
        if (events!!.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No events for this object", color = BossThemeColors.TextMuted, fontSize = 12.sp)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
            items(events!!) { event ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = event.reason,
                            color = if (event.isWarning) BossThemeColors.WarningColor else BossThemeColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = listOfNotNull(
                                event.lastSeen.takeIf { it.isNotBlank() },
                                "×${event.count}".takeIf { event.count > 1 },
                            ).joinToString(" · "),
                            color = BossThemeColors.TextMuted,
                            fontSize = 10.sp,
                        )
                    }
                    Text(text = event.message, color = BossThemeColors.TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun CenteredNotice(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = KubeIcon,
            contentDescription = null,
            tint = BossThemeColors.TextMuted.copy(alpha = 0.5f),
            modifier = Modifier.size(38.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(title, color = BossThemeColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(detail, color = BossThemeColors.TextMuted, fontSize = 12.sp)
    }
}
