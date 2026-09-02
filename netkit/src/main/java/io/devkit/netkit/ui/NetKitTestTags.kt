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
    const val TAB_CONSOLE = "netkit:tab:console"
    const val TAB_SCENARIOS = "netkit:tab:scenarios"
    const val TAB_HISTORY = "netkit:tab:history"

    const val GLOBAL_MODE_PREFIX = "netkit:global:mode:"
    const val LATENCY_PRESET_PREFIX = "netkit:global:latency:"
    const val LATENCY_CUSTOM_FIELD = "netkit:global:latency:field"

    const val ADD_RULE = "netkit:rule:add"
    const val RULE_LIST = "netkit:rule:list"
    const val RULE_ROW_PREFIX = "netkit:rule:row:"
    const val RULE_TOGGLE_PREFIX = "netkit:rule:toggle:"
    const val RULE_SEQUENCE_RESET_PREFIX = "netkit:rule:sequence:reset:"

    const val EDITOR = "netkit:editor"
    const val EDITOR_PATH = "netkit:editor:path"
    const val EDITOR_DELAY = "netkit:editor:delay"
    const val EDITOR_STATUS = "netkit:editor:status"
    const val EDITOR_BODY = "netkit:editor:body"
    const val EDITOR_SAVE = "netkit:editor:save"
    const val EDITOR_DELETE = "netkit:editor:delete"
    const val EDITOR_METHOD_PREFIX = "netkit:editor:method:"
    const val EDITOR_BEHAVIOR_PREFIX = "netkit:editor:behavior:"
    const val EDITOR_HEADER_ADD = "netkit:editor:header:add"
    const val EDITOR_HEADER_NAME_PREFIX = "netkit:editor:header:name:"
    const val EDITOR_HEADER_VALUE_PREFIX = "netkit:editor:header:value:"
    const val EDITOR_MALFORMED_PREFIX = "netkit:editor:malformed:"

    const val SEQUENCE_EDITOR = "netkit:sequence"
    const val SEQUENCE_ADD_STEP = "netkit:sequence:add"
    const val SEQUENCE_STEP_PREFIX = "netkit:sequence:step:"
    const val SEQUENCE_STEP_UP_PREFIX = "netkit:sequence:step:up:"
    const val SEQUENCE_STEP_DOWN_PREFIX = "netkit:sequence:step:down:"
    const val SEQUENCE_STEP_DELETE_PREFIX = "netkit:sequence:step:delete:"
    const val SEQUENCE_COMPLETION_PREFIX = "netkit:sequence:completion:"

    const val HISTORY_LIST = "netkit:history:list"
    const val HISTORY_ROW_PREFIX = "netkit:history:row:"
    const val HISTORY_CLEAR = "netkit:history:clear"
    const val HISTORY_FILTER_PREFIX = "netkit:history:filter:"

    const val DETAIL = "netkit:detail"
    const val DETAIL_COPY = "netkit:detail:copy"
    const val DETAIL_REPLAY = "netkit:detail:replay"

    const val REPLAY_SHEET = "netkit:replay"
    const val REPLAY_CONFIRM = "netkit:replay:confirm"
    const val REPLAY_CANCEL = "netkit:replay:cancel"
    const val REPLAY_BODY = "netkit:replay:body"
    const val REPLAY_URL = "netkit:replay:url"
    const val REPLAY_BYPASS = "netkit:replay:bypass"

    const val SCENARIO_LIST = "netkit:scenario:list"
    const val SCENARIO_ROW_PREFIX = "netkit:scenario:row:"
    const val SCENARIO_ACTIVE_CARD = "netkit:scenario:active"
    const val SCENARIO_DEACTIVATE = "netkit:scenario:deactivate"
    const val SCENARIO_NEW = "netkit:scenario:new"
    const val SCENARIO_IMPORT = "netkit:scenario:import"
    const val SCENARIO_SAVE_CURRENT = "netkit:scenario:savecurrent"
    const val SCENARIO_PACK_ROW_PREFIX = "netkit:scenario:pack:"
    const val SCENARIO_SEARCH = "netkit:scenario:search"

    const val SCENARIO_DETAIL = "netkit:scenario:detail"
    const val SCENARIO_DETAIL_ACTIVATE = "netkit:scenario:detail:activate"
    const val SCENARIO_DETAIL_EDIT = "netkit:scenario:detail:edit"
    const val SCENARIO_DETAIL_DUPLICATE = "netkit:scenario:detail:duplicate"
    const val SCENARIO_DETAIL_EXPORT = "netkit:scenario:detail:export"
    const val SCENARIO_DETAIL_DELETE = "netkit:scenario:detail:delete"
    const val SCENARIO_DETAIL_RESET_SEQUENCES = "netkit:scenario:detail:resetsequences"

    const val SCENARIO_EDITOR = "netkit:scenario:editor"
    const val SCENARIO_EDITOR_NAME = "netkit:scenario:editor:name"
    const val SCENARIO_EDITOR_DESCRIPTION = "netkit:scenario:editor:description"
    const val SCENARIO_EDITOR_SAVE = "netkit:scenario:editor:save"
    const val SCENARIO_EDITOR_ADD_RULE = "netkit:scenario:editor:addrule"

    const val IMPORT_PREVIEW = "netkit:import:preview"
    const val IMPORT_CONFIRM = "netkit:import:confirm"
    const val IMPORT_CANCEL = "netkit:import:cancel"

    const val CONFIRM_DIALOG = "netkit:confirm"
    const val CONFIRM_ACCEPT = "netkit:confirm:accept"
    const val CONFIRM_DISMISS = "netkit:confirm:dismiss"

    const val RESET = "netkit:reset"
    const val RESET_EVERYTHING = "netkit:reset:everything"
    const val RESET_SEQUENCES = "netkit:reset:sequences"
}
