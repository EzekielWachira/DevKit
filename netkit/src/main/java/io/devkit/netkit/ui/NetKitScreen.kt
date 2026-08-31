package io.devkit.netkit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.state.NetKitController
import io.devkit.netkit.state.NetKitState
import io.devkit.netkit.ui.components.NetKitBadge
import io.devkit.netkit.ui.components.NetKitDivider
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.details.RecordDetailSheet
import io.devkit.netkit.ui.history.HistoryTab
import io.devkit.netkit.ui.scenarios.RuleEditorSheet
import io.devkit.netkit.ui.scenarios.RuleEditorState
import io.devkit.netkit.ui.scenarios.ScenariosTab

/**
 * The NetKit debugging console.
 *
 * Drop it wherever your app keeps developer entry points — a debug drawer, a
 * hidden screen, a shake handler, a dialog. NetKit deliberately does not choose
 * an activation mechanism for you; see `NetKitDebugButton` for one ready-made
 * option.
 *
 * ```kotlin
 * if (showNetKit) {
 *     NetKitScreen(controller = netKit.controller, onClose = { showNetKit = false })
 * }
 * ```
 *
 * The screen talks only to [NetKitController]; it never touches the interceptor.
 * That boundary is what lets a future Android Studio bridge drive the same
 * runtime.
 *
 * Layout adapts to width: compact screens get tabs, wide ones show scenarios and
 * history side by side.
 *
 * @param controller the runtime this console drives.
 * @param onClose invoked when the user dismisses the console. Pass `null` when
 *   the host already provides a way back (a bottom sheet, a nav destination).
 */
@Composable
fun NetKitScreen(
    controller: NetKitController,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val state by controller.state.collectAsState()
    val history by controller.history.collectAsState()
    var route by remember { mutableStateOf(NetKitRoute()) }

    NetKitScaffold(
        state = state,
        history = history,
        route = route,
        onRouteChange = { route = it },
        onClose = onClose,
        controller = controller,
        modifier = modifier,
    )

    route.editor?.let { editor ->
        RuleEditorSheet(
            initial = editor,
            onSave = { rule ->
                if (editor.isEditing) controller.updateRule(rule) else controller.addRule(rule)
                route = route.copy(editor = null)
            },
            onDelete = { id ->
                controller.removeRule(id)
                route = route.copy(editor = null)
            },
            onDismiss = { route = route.copy(editor = null) },
        )
    }

    route.detail?.let { record ->
        RecordDetailSheet(
            record = record,
            onDismiss = { route = route.copy(detail = null) },
        )
    }
}

@Composable
private fun NetKitScaffold(
    state: NetKitState,
    history: List<NetworkRecord>,
    route: NetKitRoute,
    onRouteChange: (NetKitRoute) -> Unit,
    onClose: (() -> Unit)?,
    controller: NetKitController,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(NetKitTestTags.SCREEN),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            NetKitHeader(
                state = state,
                onEnabledChange = controller::setEnabled,
                onClose = onClose,
            )
            NetKitDivider()

            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Two panes above this width: on a tablet or a foldable there is
                // room to watch history while changing a scenario, which is
                // exactly how this tool gets used.
                val twoPane = maxWidth >= TwoPaneBreakpoint

                if (twoPane) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            Scenarios(state, controller, onRouteChange, route)
                        }
                        VerticalDivider(Modifier.fillMaxHeight())
                        Box(Modifier.weight(1f)) {
                            History(history, onRouteChange, route)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        PrimaryTabRow(selectedTabIndex = route.tab.ordinal) {
                            NetKitTab.entries.forEach { tab ->
                                Tab(
                                    selected = route.tab == tab,
                                    onClick = { onRouteChange(route.copy(tab = tab)) },
                                    modifier = Modifier.testTag(tab.testTag),
                                    text = {
                                        Text(
                                            text = when (tab) {
                                                NetKitTab.SCENARIOS -> tab.label
                                                NetKitTab.HISTORY -> if (history.isEmpty()) {
                                                    tab.label
                                                } else {
                                                    "${tab.label} (${history.size})"
                                                }
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        when (route.tab) {
                            NetKitTab.SCENARIOS -> Scenarios(state, controller, onRouteChange, route)
                            NetKitTab.HISTORY -> History(history, onRouteChange, route)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Scenarios(
    state: NetKitState,
    controller: NetKitController,
    onRouteChange: (NetKitRoute) -> Unit,
    route: NetKitRoute,
) {
    ScenariosTab(
        state = state,
        onModeChange = controller::setGlobalMode,
        onLatencyChange = controller::setGlobalLatency,
        onRuleToggle = controller::setRuleEnabled,
        onEditRule = { rule -> onRouteChange(route.copy(editor = RuleEditorState.from(rule))) },
        onAddRule = { onRouteChange(route.copy(editor = RuleEditorState.new())) },
        onReset = controller::reset,
        onClearHistory = controller::clearHistory,
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
    )
}

@Composable
private fun History(
    history: List<NetworkRecord>,
    onRouteChange: (NetKitRoute) -> Unit,
    route: NetKitRoute,
) {
    HistoryTab(
        records = history,
        onSelect = { record -> onRouteChange(route.copy(detail = record)) },
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
    )
}

/**
 * Title, master switch and a live "simulating" indicator.
 *
 * The indicator is the most important pixel on the screen: it tells a QA
 * engineer whether what they are looking at came from the backend or from
 * NetKit, and it is a word rather than a colour.
 */
@Composable
private fun NetKitHeader(
    state: NetKitState,
    onEnabledChange: (Boolean) -> Unit,
    onClose: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .statusBarsPadding()
            .padding(horizontal = NetKitGutter, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "NetKit",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (state.enabled && state.isSimulating) {
                NetKitBadge(
                    text = "SIMULATING",
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Box(Modifier.weight(1f))
            Switch(
                checked = state.enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier
                    .semantics { contentDescription = "NetKit enabled" }
                    .testTag(NetKitTestTags.ENABLED_SWITCH),
            )
            if (onClose != null) {
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
        Text(
            text = when {
                !state.enabled -> "Disabled — every request goes to the real backend."
                state.isSimulating ->
                    "${state.activeSummary} — what you see may not come from your backend."
                else -> "Normal — no scenario is changing your network."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Width at which the console switches from tabs to a two-pane layout. */
private val TwoPaneBreakpoint = 720.dp
