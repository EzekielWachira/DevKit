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

    // ---- 0.3 ---------------------------------------------------------------

    const val TAB_CHAOS = "netkit:tab:chaos"
    const val TAB_RUN = "netkit:tab:run"

    const val EDITOR_ADVANCED_TOGGLE = "netkit:editor:advanced"
    const val EDITOR_PROBABILITY = "netkit:editor:probability"
    const val EDITOR_LATENCY_MIN = "netkit:editor:latency:min"
    const val EDITOR_LATENCY_MAX = "netkit:editor:latency:max"
    const val EDITOR_PREFIX_MATCH = "netkit:editor:prefix"

    const val CONDITION_ADD_PREFIX = "netkit:condition:add:"
    const val CONDITION_ROW_PREFIX = "netkit:condition:row:"
    const val CONDITION_NAME_PREFIX = "netkit:condition:name:"
    const val CONDITION_VALUE_PREFIX = "netkit:condition:value:"
    const val CONDITION_DELETE_PREFIX = "netkit:condition:delete:"

    const val OUTCOME_ADD = "netkit:outcome:add"
    const val OUTCOME_ROW_PREFIX = "netkit:outcome:row:"
    const val OUTCOME_WEIGHT_PREFIX = "netkit:outcome:weight:"
    const val OUTCOME_DELETE_PREFIX = "netkit:outcome:delete:"

    const val CHAOS_SCREEN = "netkit:chaos"
    const val CHAOS_ENABLED = "netkit:chaos:enabled"
    const val CHAOS_FAILURE_RATE = "netkit:chaos:failurerate"
    const val CHAOS_LATENCY_MIN = "netkit:chaos:latency:min"
    const val CHAOS_LATENCY_MAX = "netkit:chaos:latency:max"
    const val CHAOS_HOSTS = "netkit:chaos:hosts"
    const val CHAOS_PATHS = "netkit:chaos:paths"
    const val CHAOS_EXCLUSIONS = "netkit:chaos:exclusions"
    const val CHAOS_PRESET_PREFIX = "netkit:chaos:preset:"
    const val CHAOS_OUTCOME_WEIGHT_PREFIX = "netkit:chaos:outcome:weight:"
    const val CHAOS_OUTCOME_ADD = "netkit:chaos:outcome:add"
    const val CHAOS_OUTCOME_DELETE_PREFIX = "netkit:chaos:outcome:delete:"

    const val RUN_SCREEN = "netkit:run"
    const val RUN_SEED = "netkit:run:seed"
    const val RUN_COPY_TRACE = "netkit:run:trace:copy"
    const val RUN_RESTART_SAME = "netkit:run:restart:same"
    const val RUN_RESTART_NEW = "netkit:run:restart:new"
    const val RUN_SEED_FIELD = "netkit:run:seed:field"
    const val RUN_SEED_APPLY = "netkit:run:seed:apply"
    const val RUN_COPY_REPRODUCTION = "netkit:run:reproduction:copy"
    const val RUN_EXPORT_REPRODUCTION = "netkit:run:reproduction:export"
    const val RUN_TIMELINE = "netkit:run:timeline"
    const val RUN_TIMELINE_TOGGLE = "netkit:run:timeline:toggle"
    const val RUN_TIMELINE_ROW_PREFIX = "netkit:run:timeline:row:"
    const val RUN_STATS = "netkit:run:stats"
    const val RUN_RULE_STAT_PREFIX = "netkit:run:stat:"

    const val PRESET_PICKER = "netkit:preset"
    const val PRESET_ROW_PREFIX = "netkit:preset:row:"
    const val PRESET_FIELD_PREFIX = "netkit:preset:field:"
    const val PRESET_CREATE = "netkit:preset:create"
    const val PRESET_CANCEL = "netkit:preset:cancel"
    const val SCENARIO_FROM_PRESET = "netkit:scenario:preset"
}
