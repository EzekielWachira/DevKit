package io.devkit.netkit.config

/**
 * Bounds that keep a hand-written or imported scenario from destabilising the
 * host application.
 *
 * These exist for stability, not policy: they are generous enough that no
 * legitimate debugging scenario runs into them, and strict enough that a
 * malformed or hostile `.netkit.json` cannot allocate a gigabyte of response
 * body or a million-step sequence.
 *
 * Every limit is enforced in two places — [io.devkit.netkit.scenario.model]
 * validation for scenarios built in the app, and import validation for files
 * arriving from elsewhere.
 */
object NetKitLimits {

    /** Largest custom response body a scenario may carry, in UTF-8 bytes. */
    const val MAX_BODY_BYTES: Int = 1024 * 1024

    /** Largest `.netkit.json` NetKit will parse, in UTF-8 bytes. */
    const val MAX_IMPORT_BYTES: Int = 4 * 1024 * 1024

    /** Steps allowed in one [io.devkit.netkit.scenario.NetworkAction.Sequence]. */
    const val MAX_SEQUENCE_STEPS: Int = 100

    /** Endpoint rules allowed in one scenario. */
    const val MAX_RULES_PER_SCENARIO: Int = 200

    /** Scenarios allowed in one pack. */
    const val MAX_SCENARIOS_PER_PACK: Int = 200

    /** Response headers allowed on one simulated response. */
    const val MAX_RESPONSE_HEADERS: Int = 50

    /** Characters allowed in a scenario or pack name. */
    const val MAX_NAME_LENGTH: Int = 120

    /** Characters allowed in a scenario or pack description. */
    const val MAX_DESCRIPTION_LENGTH: Int = 1_000

    /** Replay snapshots kept in memory at once. */
    const val MAX_REPLAY_SNAPSHOTS: Int = 50

    // ---- 0.3 ---------------------------------------------------------------

    /** Outcomes allowed in one [io.devkit.netkit.scenario.NetworkAction.Weighted] or chaos mix. */
    const val MAX_WEIGHTED_OUTCOMES: Int = 20

    /** Conditions allowed on one [io.devkit.netkit.scenario.EndpointRule]. */
    const val MAX_CONDITIONS_PER_RULE: Int = 10

    /** Characters allowed in a condition's comparison value. */
    const val MAX_CONDITION_TEXT_LENGTH: Int = 512

    /**
     * Largest request body NetKit will buffer to evaluate a body condition.
     *
     * Small on purpose. A body condition is a convenience for matching a small
     * JSON payload; an upload past this size is skipped rather than copied into
     * memory on the request path, and the condition reports itself unavailable.
     */
    const val MAX_CONDITION_BODY_BYTES: Long = 64L * 1024

    /** Events the execution timeline keeps before evicting the oldest. */
    const val MAX_EXECUTION_EVENTS: Int = 500

    /** Events carried by an exported reproduction trace. */
    const val MAX_TRACE_EVENTS: Int = 200

    /** Path prefixes allowed in a chaos scope or exclusion list. */
    const val MAX_CHAOS_PREFIXES: Int = 50
}
