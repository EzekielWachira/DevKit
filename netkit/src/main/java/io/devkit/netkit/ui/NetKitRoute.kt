package io.devkit.netkit.ui

import androidx.compose.runtime.Immutable
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.ui.scenarios.RuleEditorState

/** The two top-level surfaces of the NetKit console. */
internal enum class NetKitTab(val label: String, val testTag: String) {
    SCENARIOS("Scenarios", NetKitTestTags.TAB_SCENARIOS),
    HISTORY("History", NetKitTestTags.TAB_HISTORY),
}

/**
 * Navigation state local to the NetKit screen.
 *
 * Kept separate from [io.devkit.netkit.state.NetKitState] because it is pure
 * presentation: which tab is showing and which sheet is open has nothing to do
 * with what the interceptor does, and an IDE bridge driving the same controller
 * would have no use for it.
 */
@Immutable
internal data class NetKitRoute(
    val tab: NetKitTab = NetKitTab.SCENARIOS,
    val editor: RuleEditorState? = null,
    val detail: NetworkRecord? = null,
)
