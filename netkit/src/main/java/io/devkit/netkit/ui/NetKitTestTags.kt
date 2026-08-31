package io.devkit.netkit.ui

/**
 * Stable `testTag` values for the NetKit debug UI.
 *
 * Published so applications can assert against NetKit in their own Compose UI
 * tests without depending on user-visible strings.
 */
object NetKitTestTags {
    const val SCREEN = "netkit:screen"
    const val ENABLED_SWITCH = "netkit:enabled"
    const val TAB_SCENARIOS = "netkit:tab:scenarios"
    const val TAB_HISTORY = "netkit:tab:history"

    const val GLOBAL_MODE_PREFIX = "netkit:global:mode:"
    const val LATENCY_PRESET_PREFIX = "netkit:global:latency:"
    const val LATENCY_CUSTOM_FIELD = "netkit:global:latency:field"

    const val ADD_RULE = "netkit:rule:add"
    const val RULE_LIST = "netkit:rule:list"
    const val RULE_ROW_PREFIX = "netkit:rule:row:"
    const val RULE_TOGGLE_PREFIX = "netkit:rule:toggle:"

    const val EDITOR = "netkit:editor"
    const val EDITOR_PATH = "netkit:editor:path"
    const val EDITOR_DELAY = "netkit:editor:delay"
    const val EDITOR_STATUS = "netkit:editor:status"
    const val EDITOR_BODY = "netkit:editor:body"
    const val EDITOR_SAVE = "netkit:editor:save"
    const val EDITOR_DELETE = "netkit:editor:delete"
    const val EDITOR_METHOD_PREFIX = "netkit:editor:method:"
    const val EDITOR_BEHAVIOR_PREFIX = "netkit:editor:behavior:"

    const val HISTORY_LIST = "netkit:history:list"
    const val HISTORY_ROW_PREFIX = "netkit:history:row:"
    const val HISTORY_CLEAR = "netkit:history:clear"

    const val DETAIL = "netkit:detail"
    const val DETAIL_COPY = "netkit:detail:copy"

    const val RESET = "netkit:reset"
}
