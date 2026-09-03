package io.devkit.netkit.ui

import androidx.compose.runtime.Immutable
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.serialization.ScenarioImportResult
import io.devkit.netkit.ui.history.HistoryFilter
import io.devkit.netkit.ui.scenarios.RuleEditorState

/**
 * The top-level surfaces of the NetKit console.
 *
 * 0.3 added two, following the shape of the work rather than the shape of the
 * code: **Chaos** is a thing you configure once and forget, **Run** is a thing
 * you watch while reproducing, and neither belonged inside Console — which is
 * about the network *right now* — or Scenarios, which is about definitions.
 */
internal enum class NetKitTab(val label: String, val testTag: String) {
    CONSOLE("Console", NetKitTestTags.TAB_CONSOLE),
    SCENARIOS("Scenarios", NetKitTestTags.TAB_SCENARIOS),
    CHAOS("Chaos", NetKitTestTags.TAB_CHAOS),
    RUN("Run", NetKitTestTags.TAB_RUN),
    HISTORY("History", NetKitTestTags.TAB_HISTORY),
}

/** A file the user picked, held until they confirm or cancel the import. */
@Immutable
internal data class PendingImport(
    val result: ScenarioImportResult,
    val fileName: String?,
)

/** The "save the current setup as a scenario" prompt. */
@Immutable
internal data class SaveSetupPrompt(
    val name: String = "",
    val description: String = "",
) {
    val isValid: Boolean get() = name.isNotBlank()
}

/**
 * Navigation state local to the NetKit screen.
 *
 * Kept separate from [io.devkit.netkit.state.NetKitState] because it is pure
 * presentation: which tab is showing and which sheet is open has nothing to do
 * with what the interceptor does, and an IDE bridge driving the same controller
 * would have no use for it.
 *
 * One data class rather than a dozen `remember { mutableStateOf(...) }`
 * declarations, so "exactly one sheet is open" is a property of the type instead
 * of something the screen has to remember to enforce.
 */
@Immutable
internal data class NetKitRoute(
    val tab: NetKitTab = NetKitTab.CONSOLE,
    val ruleEditor: RuleEditorState? = null,
    val detail: NetworkRecord? = null,
    val replay: NetworkRecord? = null,
    val scenarioDetail: NetworkScenario? = null,
    val scenarioEditor: NetworkScenario? = null,
    val pendingImport: PendingImport? = null,
    val saveSetup: SaveSetupPrompt? = null,
    val presetPicker: Boolean = false,
    val historyFilter: HistoryFilter = HistoryFilter.ALL,
    val scenarioSearch: String = "",
    val message: String? = null,
) {
    /** Closes every sheet, keeping the tab, filters and search. */
    fun closedSheets(): NetKitRoute = copy(
        ruleEditor = null,
        detail = null,
        replay = null,
        scenarioDetail = null,
        scenarioEditor = null,
        pendingImport = null,
        saveSetup = null,
        presetPicker = false,
    )
}
