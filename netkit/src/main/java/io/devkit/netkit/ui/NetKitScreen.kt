package io.devkit.netkit.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.devkit.netkit.android.ScenarioFileResult
import io.devkit.netkit.android.ScenarioFiles
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.replay.ReplayResult
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioMetadata
import io.devkit.netkit.scenario.persistence.ScenarioWriteResult
import io.devkit.netkit.scenario.runtime.ScenarioImportOutcome
import io.devkit.netkit.scenario.preset.ScenarioPresetRegistry
import io.devkit.netkit.state.NetKitController
import io.devkit.netkit.state.NetKitState
import io.devkit.netkit.ui.components.NetKitBadge
import io.devkit.netkit.ui.components.NetKitDivider
import io.devkit.netkit.ui.components.NetKitGutter
import io.devkit.netkit.ui.chaos.ChaosTab
import io.devkit.netkit.ui.console.ConsoleTab
import io.devkit.netkit.ui.details.RecordDetailSheet
import io.devkit.netkit.ui.history.HistoryTab
import io.devkit.netkit.ui.preset.PresetSheet
import io.devkit.netkit.ui.replay.ReplaySheet
import io.devkit.netkit.ui.run.RunTab
import io.devkit.netkit.ui.run.RunTabState
import io.devkit.netkit.ui.scenarioeditor.ScenarioEditorSheet
import io.devkit.netkit.ui.scenariolist.ImportPreviewSheet
import io.devkit.netkit.ui.scenariolist.ScenarioDetailSheet
import io.devkit.netkit.ui.scenariolist.ScenarioListTab
import io.devkit.netkit.ui.scenarios.RuleEditorSheet
import io.devkit.netkit.ui.scenarios.RuleEditorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * Three surfaces, in the order a session uses them: **Console** is the network
 * right now, **Scenarios** is what you have saved, **History** is what happened.
 *
 * The screen talks only to [NetKitController]; it never touches the interceptor.
 * That boundary is what lets a future Android Studio bridge drive the same
 * runtime.
 *
 * Layout adapts to width: compact screens get tabs, wide ones show the console
 * and history side by side.
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
    presets: ScenarioPresetRegistry = ScenarioPresetRegistry(),
) {
    val state by controller.state.collectAsState()
    val history by controller.history.collectAsState()
    val scenarios by controller.scenarios.scenarios.collectAsState()
    val packs by controller.scenarios.packContents.collectAsState()
    val activeId by controller.scenarios.activeScenarioId.collectAsState()
    val activeScenario by controller.scenarios.activeScenario.collectAsState()
    val sequenceProgress by controller.scenarios.sequenceProgress.collectAsState()
    val persistenceError by controller.scenarios.lastError.collectAsState()
    val run by controller.runs.current.collectAsState()
    val timeline by controller.runs.timeline.collectAsState()
    val ruleStatistics by controller.runs.ruleStatistics.collectAsState()
    val chaosStatistics by controller.runs.chaosStatistics.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf(NetKitRoute()) }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Off the main thread: the picked document can be several megabytes and
        // may come from a cloud provider, and reading plus parsing it is more
        // than a frame's worth of work.
        scope.launch {
            val preview = withContext(Dispatchers.IO) {
                ScenarioFiles.read(context, uri)?.let(controller.scenarios::preview)
            }
            route = if (preview == null) {
                route.copy(message = "Could not read that file.")
            } else {
                // Parsed and validated, but not saved: the preview sheet is the
                // confirmation step, so a picked file never changes anything by itself.
                route.copy(
                    pendingImport = PendingImport(
                        result = preview,
                        fileName = uri.lastPathSegment?.substringAfterLast('/'),
                    ),
                )
            }
        }
    }

    // A failed write is not something to discover later by noticing a scenario
    // did not stick.
    LaunchedEffect(persistenceError) {
        persistenceError?.let { route = route.copy(message = it.message) }
    }

    NetKitScaffold(
        state = state,
        history = history,
        scenarios = scenarios,
        packs = packs,
        activeScenario = activeScenario,
        sequenceProgress = sequenceProgress,
        runState = RunTabState(
            run = run,
            timeline = timeline,
            ruleStatistics = ruleStatistics,
            chaosStatistics = chaosStatistics,
            timelineEnabled = controller.runs.isTimelineEnabled,
            isStochastic = state.configuration.isStochastic,
        ),
        route = route,
        onRouteChange = { route = it },
        onClose = onClose,
        controller = controller,
        onImport = { importPicker.launch(ScenarioFiles.IMPORT_MIME_TYPES) },
        onCopy = { label, text ->
            // The platform clipboard rather than Compose's, matching the request
            // detail sheet: NetKit copies plain text and nothing else, and the
            // Compose wrapper is deprecated in favour of a suspending API this
            // one-liner has no use for.
            copyPlainText(context, label, text)
            route = route.copy(message = "$label copied")
        },
        onExportRun = {
            scope.launch {
                val export = controller.runs.exportReproduction()
                route = route.copy(
                    message = if (export == null) {
                        "There is no run to export."
                    } else {
                        when (val written = ScenarioFiles.share(context, export)) {
                            is ScenarioFileResult.Ready -> "Exported ${export.suggestedFileName}"
                            is ScenarioFileResult.Failed -> written.message
                        }
                    },
                )
            }
        },
        modifier = modifier,
    )

    NetKitSheets(
        controller = controller,
        context = context,
        route = route,
        onRouteChange = { route = it },
        activeId = activeId,
        scenarios = scenarios,
        packs = packs,
        sequenceProgress = sequenceProgress,
        presets = presets,
        scope = { block -> scope.launch { block() } },
    )

    route.message?.let { message ->
        // Dismissed on its own as well as by hand: an import result is worth
        // reading, and worth getting out of the way of the list afterwards.
        LaunchedEffect(message) {
            delay(MessageDurationMillis)
            if (route.message == message) route = route.copy(message = null)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Snackbar(
                modifier = Modifier.padding(NetKitGutter),
                action = {
                    TextButton(onClick = { route = route.copy(message = null) }) { Text("Dismiss") }
                },
            ) {
                Text(message)
            }
        }
    }
}

/**
 * Every sheet and dialog the console can show.
 *
 * Split out of the scaffold so the layout code stays about layout: this function
 * is a flat list of "if this route field is set, show that sheet", which is the
 * easiest possible shape to check for a missing dismissal.
 */
@Composable
private fun NetKitSheets(
    controller: NetKitController,
    context: Context,
    route: NetKitRoute,
    onRouteChange: (NetKitRoute) -> Unit,
    activeId: io.devkit.netkit.scenario.model.ScenarioId?,
    scenarios: List<NetworkScenario>,
    packs: List<io.devkit.netkit.scenario.model.ScenarioPackContents>,
    sequenceProgress: Map<String, io.devkit.netkit.scenario.runtime.SequenceProgress>,
    presets: ScenarioPresetRegistry,
    scope: (suspend () -> Unit) -> Unit,
) {
    route.ruleEditor?.let { editor ->
        RuleEditorSheet(
            initial = editor,
            onSave = { rule ->
                if (editor.isEditing) controller.updateRule(rule) else controller.addRule(rule)
                onRouteChange(route.copy(ruleEditor = null))
            },
            onDelete = { id ->
                controller.removeRule(id)
                onRouteChange(route.copy(ruleEditor = null))
            },
            onDismiss = { onRouteChange(route.copy(ruleEditor = null)) },
        )
    }

    route.detail?.let { record ->
        RecordDetailSheet(
            record = record,
            replayEligibility = controller.replayer.eligibility(record.id),
            onReplay = { onRouteChange(route.copy(detail = null, replay = record)) },
            onDismiss = { onRouteChange(route.copy(detail = null)) },
        )
    }

    route.replay?.let { record ->
        ReplaySheet(
            record = record,
            eligibility = controller.replayer.eligibility(record.id),
            onReplay = { override, bypass ->
                onRouteChange(route.copy(replay = null))
                scope {
                    val message = when (val result =
                        controller.replayer.replay(record.id, override, bypass)) {
                        is ReplayResult.Success ->
                            "Replayed ${record.method} ${record.path} → ${result.statusCode}" +
                                if (result.simulated) " (simulated)" else ""

                        is ReplayResult.Failed -> "Replay failed: ${result.error.message}"
                        is ReplayResult.Unavailable -> result.reason.message
                    }
                    onRouteChange(route.copy(replay = null, message = message))
                }
            },
            onDismiss = { onRouteChange(route.copy(replay = null)) },
        )
    }

    route.scenarioDetail?.let { stale ->
        // Re-read from the live list so the sheet reflects an edit made underneath it.
        val scenario = scenarios.firstOrNull { it.id == stale.id } ?: stale
        ScenarioDetailSheet(
            scenario = scenario,
            isActive = scenario.id == activeId,
            packName = packs.firstOrNull { it.pack.id == scenario.metadata.packId }?.pack?.name,
            sequenceProgress = sequenceProgress,
            onActivate = {
                scope { controller.scenarios.activate(scenario.id) }
                onRouteChange(route.copy(scenarioDetail = null))
            },
            onDeactivate = {
                scope { controller.scenarios.deactivate() }
                onRouteChange(route.copy(scenarioDetail = null))
            },
            onEdit = {
                onRouteChange(route.copy(scenarioDetail = null, scenarioEditor = scenario))
            },
            onDuplicate = {
                scope {
                    val result = controller.scenarios.duplicate(scenario.id)
                    onRouteChange(
                        route.copy(
                            scenarioDetail = null,
                            message = when (result) {
                                is ScenarioWriteResult.Success ->
                                    "Saved \"${result.scenario.name}\""

                                is ScenarioWriteResult.Failure -> result.error.message
                            },
                        ),
                    )
                }
            },
            onExport = {
                onRouteChange(route.copy(scenarioDetail = null))
                scope {
                    // Serialising and writing the file is disk work; only the
                    // share sheet has to be started from the main thread.
                    val export = controller.scenarios.export(scenario.id)
                    val message = if (export == null) {
                        "Could not export \"${scenario.name}\"."
                    } else {
                        when (val written = ScenarioFiles.share(context, export)) {
                            is ScenarioFileResult.Ready -> buildString {
                                append("Exported ${export.suggestedFileName}")
                                if (export.warnings.isNotEmpty()) {
                                    append(" · ${export.warnings.size} header removed for safety")
                                }
                            }

                            is ScenarioFileResult.Failed -> written.message
                        }
                    }
                    onRouteChange(route.copy(scenarioDetail = null, message = message))
                }
            },
            onDelete = {
                scope { controller.scenarios.delete(scenario.id) }
                onRouteChange(
                    route.copy(scenarioDetail = null, message = "Deleted \"${scenario.name}\""),
                )
            },
            onResetSequence = controller.scenarios::resetSequence,
            onResetAllSequences = controller.scenarios::resetAllSequences,
            onDismiss = { onRouteChange(route.copy(scenarioDetail = null)) },
        )
    }

    route.scenarioEditor?.let { scenario ->
        ScenarioEditorSheet(
            initial = scenario,
            packs = packs.map { it.pack },
            onSave = { edited ->
                scope {
                    val result = controller.scenarios.save(edited)
                    onRouteChange(
                        route.copy(
                            scenarioEditor = null,
                            message = when (result) {
                                is ScenarioWriteResult.Success -> "Saved \"${edited.name}\""
                                is ScenarioWriteResult.Failure -> result.error.message
                            },
                        ),
                    )
                }
            },
            onDismiss = { onRouteChange(route.copy(scenarioEditor = null)) },
        )
    }

    route.pendingImport?.let { pending ->
        ImportPreviewSheet(
            result = pending.result,
            fileName = pending.fileName,
            onConfirm = {
                scope {
                    val message = when (val outcome =
                        controller.scenarios.commitImport(pending.result)) {
                        is ScenarioImportOutcome.Imported -> "Imported ${outcome.summary}"

                        // A reproduction is imported *and activated on its seed*.
                        // Stopping at "saved" would leave the developer to hunt
                        // for the scenario and retype the number, which is the
                        // step the whole format exists to remove.
                        is ScenarioImportOutcome.ImportedReproduction -> {
                            controller.scenarios.reproduce(outcome.scenario.id, outcome.seed)
                            "Reproducing ${outcome.summary}"
                        }

                        is ScenarioImportOutcome.Rejected -> outcome.reason
                        is ScenarioImportOutcome.Failed -> outcome.error.message
                    }
                    onRouteChange(route.copy(pendingImport = null, message = message))
                }
            },
            onDismiss = { onRouteChange(route.copy(pendingImport = null)) },
        )
    }

    if (route.presetPicker) {
        PresetSheet(
            registry = presets,
            onCreate = { scenario ->
                scope {
                    val result = controller.scenarios.save(scenario)
                    onRouteChange(
                        route.copy(
                            presetPicker = false,
                            // Straight into the editor: a template is a starting
                            // point, and the endpoints it guessed at are almost
                            // always the first thing to correct.
                            scenarioEditor = result.scenarioOrNull,
                            message = when (result) {
                                is ScenarioWriteResult.Success ->
                                    "Created \"${result.scenario.name}\" from a template"

                                is ScenarioWriteResult.Failure -> result.error.message
                            },
                        ),
                    )
                }
            },
            onDismiss = { onRouteChange(route.copy(presetPicker = false)) },
        )
    }

    route.saveSetup?.let { prompt ->
        SaveSetupDialog(
            prompt = prompt,
            onChange = { onRouteChange(route.copy(saveSetup = it)) },
            onConfirm = {
                scope {
                    val result = controller.scenarios.saveCurrentSetupAsScenario(
                        name = prompt.name.trim(),
                        description = prompt.description.trim().takeIf(String::isNotEmpty),
                    )
                    onRouteChange(
                        route.copy(
                            saveSetup = null,
                            message = when (result) {
                                is ScenarioWriteResult.Success ->
                                    "Saved \"${result.scenario.name}\" · overrides cleared"

                                is ScenarioWriteResult.Failure -> result.error.message
                            },
                        ),
                    )
                }
            },
            onDismiss = { onRouteChange(route.copy(saveSetup = null)) },
        )
    }
}

@Composable
private fun SaveSetupDialog(
    prompt: SaveSetupPrompt,
    onChange: (SaveSetupPrompt) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save current setup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Turns the console's global setting and every temporary override " +
                        "into a reusable scenario, then clears them.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = prompt.name,
                    onValueChange = { onChange(prompt.copy(name = it)) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt.description,
                    onValueChange = { onChange(prompt.copy(description = it)) },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = prompt.isValid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetKitScaffold(
    state: NetKitState,
    history: List<NetworkRecord>,
    scenarios: List<NetworkScenario>,
    packs: List<io.devkit.netkit.scenario.model.ScenarioPackContents>,
    activeScenario: NetworkScenario?,
    sequenceProgress: Map<String, io.devkit.netkit.scenario.runtime.SequenceProgress>,
    runState: RunTabState,
    route: NetKitRoute,
    onRouteChange: (NetKitRoute) -> Unit,
    onClose: (() -> Unit)?,
    controller: NetKitController,
    onImport: () -> Unit,
    onCopy: (String, String) -> Unit,
    onExportRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

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
                            Column(Modifier.fillMaxSize()) {
                                PrimaryScrollableTabRow(
                                    selectedTabIndex = route.tab.leftPaneIndex,
                                    edgePadding = 0.dp,
                                ) {
                                    LeftPaneTabs.forEach { tab ->
                                        Tab(
                                            selected = route.tab == tab,
                                            onClick = { onRouteChange(route.copy(tab = tab)) },
                                            modifier = Modifier.testTag(tab.testTag),
                                            text = { Text(tab.label) },
                                        )
                                    }
                                }
                                LeftPane(
                                    tab = route.tab,
                                    state = state,
                                    scenarios = scenarios,
                                    packs = packs,
                                    activeScenario = activeScenario,
                                    sequenceProgress = sequenceProgress,
                                    runState = runState,
                                    route = route,
                                    onRouteChange = onRouteChange,
                                    controller = controller,
                                    onImport = onImport,
                                    onCopy = onCopy,
                                    onExportRun = onExportRun,
                                    launch = { block -> scope.launch { block() } },
                                )
                            }
                        }
                        VerticalDivider(Modifier.fillMaxHeight())
                        Box(Modifier.weight(1f)) {
                            History(history, route, onRouteChange)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        PrimaryScrollableTabRow(
                            selectedTabIndex = route.tab.ordinal,
                            edgePadding = 0.dp,
                        ) {
                            NetKitTab.entries.forEach { tab ->
                                Tab(
                                    selected = route.tab == tab,
                                    onClick = { onRouteChange(route.copy(tab = tab)) },
                                    modifier = Modifier.testTag(tab.testTag),
                                    text = {
                                        Text(
                                            text = when (tab) {
                                                NetKitTab.HISTORY -> if (history.isEmpty()) {
                                                    tab.label
                                                } else {
                                                    "${tab.label} (${history.size})"
                                                }

                                                else -> tab.label
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        if (route.tab == NetKitTab.HISTORY) {
                            History(history, route, onRouteChange)
                        } else {
                            LeftPane(
                                tab = route.tab,
                                state = state,
                                scenarios = scenarios,
                                packs = packs,
                                activeScenario = activeScenario,
                                sequenceProgress = sequenceProgress,
                                runState = runState,
                                route = route,
                                onRouteChange = onRouteChange,
                                controller = controller,
                                onImport = onImport,
                                onCopy = onCopy,
                                onExportRun = onExportRun,
                                launch = { block -> scope.launch { block() } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LeftPane(
    tab: NetKitTab,
    state: NetKitState,
    scenarios: List<NetworkScenario>,
    packs: List<io.devkit.netkit.scenario.model.ScenarioPackContents>,
    activeScenario: NetworkScenario?,
    sequenceProgress: Map<String, io.devkit.netkit.scenario.runtime.SequenceProgress>,
    runState: RunTabState,
    route: NetKitRoute,
    onRouteChange: (NetKitRoute) -> Unit,
    controller: NetKitController,
    onImport: () -> Unit,
    onCopy: (String, String) -> Unit,
    onExportRun: () -> Unit,
    launch: (suspend () -> Unit) -> Unit,
) {
    when (tab) {
        NetKitTab.CONSOLE -> ConsoleTab(
            state = state,
            activeScenario = activeScenario,
            sequenceProgress = sequenceProgress,
            onModeChange = controller::setGlobalMode,
            onLatencyChange = controller::setGlobalLatency,
            onRuleToggle = controller::setRuleEnabled,
            onEditRule = { rule ->
                onRouteChange(route.copy(ruleEditor = RuleEditorState.from(rule)))
            },
            onAddRule = { onRouteChange(route.copy(ruleEditor = RuleEditorState.new())) },
            onOpenActiveScenario = {
                activeScenario?.let { onRouteChange(route.copy(scenarioDetail = it)) }
            },
            onDeactivateScenario = { launch { controller.scenarios.deactivate() } },
            onReset = controller::reset,
            onResetEverything = controller::resetEverything,
            onResetSequences = controller::resetAllSequences,
            onClearHistory = controller::clearHistory,
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        )

        NetKitTab.SCENARIOS -> ScenarioListTab(
            scenarios = scenarios,
            packs = packs,
            activeId = activeScenario?.id,
            sequenceProgress = sequenceProgress,
            search = route.scenarioSearch,
            onSearchChange = { onRouteChange(route.copy(scenarioSearch = it)) },
            onOpen = { onRouteChange(route.copy(scenarioDetail = it)) },
            onToggleActive = { scenario -> launch { controller.scenarios.toggleActive(scenario.id) } },
            onNew = {
                onRouteChange(
                    route.copy(
                        scenarioEditor = NetworkScenario(
                            name = "",
                            metadata = ScenarioMetadata(),
                        ),
                    ),
                )
            },
            onNewFromPreset = { onRouteChange(route.copy(presetPicker = true)) },
            onImport = onImport,
            onSaveCurrentSetup = { onRouteChange(route.copy(saveSetup = SaveSetupPrompt())) },
            canSaveCurrentSetup = !state.global.isNormal || state.rules.isNotEmpty(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        )

        NetKitTab.CHAOS -> ChaosTab(
            chaos = state.configuration.chaos,
            scenarioChaos = state.configuration.scenario?.takeIf { it.enabled }?.chaos,
            scenarioName = state.activeScenario?.name,
            run = runState.run,
            statistics = runState.chaosStatistics,
            onChange = controller::setChaos,
            onRestartRun = { controller.runs.restartWithNewSeed() },
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        )

        NetKitTab.RUN -> RunTab(
            state = runState,
            onRestartSameSeed = { controller.runs.restartWithSameSeed() },
            onRestartNewSeed = { controller.runs.restartWithNewSeed() },
            onApplySeed = { seed -> controller.runs.restartWithSeed(seed) },
            onCopyReproduction = {
                controller.runs.reproductionSummary()?.let { onCopy("Reproduction", it) }
            },
            onCopyTrace = { controller.runs.traceSummary()?.let { onCopy("Trace", it) } },
            onExportReproduction = onExportRun,
            onTimelineEnabledChange = controller.runs::setTimelineEnabled,
            onStop = {
                launch { controller.scenarios.deactivate() }
                controller.setChaosEnabled(false)
            },
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        )

        NetKitTab.HISTORY -> Unit
    }
}

@Composable
private fun History(
    history: List<NetworkRecord>,
    route: NetKitRoute,
    onRouteChange: (NetKitRoute) -> Unit,
) {
    HistoryTab(
        records = history,
        filter = route.historyFilter,
        onFilterChange = { onRouteChange(route.copy(historyFilter = it)) },
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
@OptIn(ExperimentalLayoutApi::class)
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

                else -> "Normal — nothing is changing your network."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Puts [text] on the clipboard.
 *
 * Everything NetKit copies has already been through the masker, so a copied
 * reproduction or trace is safe to paste anywhere — which is the point of having
 * a copy button at all.
 */
private fun copyPlainText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager ?: return
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NetKit $label", text))
}

/** How long a result message stays up before dismissing itself. */
private const val MessageDurationMillis = 6_000L

/** In the two-pane layout history has its own column, so it is not a left tab. */
private val LeftPaneTabs =
    listOf(NetKitTab.CONSOLE, NetKitTab.SCENARIOS, NetKitTab.CHAOS, NetKitTab.RUN)

private val NetKitTab.leftPaneIndex: Int
    get() = LeftPaneTabs.indexOf(this).coerceAtLeast(0)

/** Width at which the console switches from tabs to a two-pane layout. */
private val TwoPaneBreakpoint = 720.dp
