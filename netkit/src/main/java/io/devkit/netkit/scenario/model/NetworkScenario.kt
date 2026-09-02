package io.devkit.netkit.scenario.model

import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkConfig
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Identity of a saved scenario.
 *
 * A value class rather than a bare `String`: scenario ids, pack ids and rule ids
 * all travel together through the repository, the serializer and the UI, and
 * mixing two of them up would silently activate the wrong thing.
 */
@JvmInline
value class ScenarioId(val value: String) {
    init {
        require(value.isNotBlank()) { "NetKit scenario id cannot be blank" }
    }

    override fun toString(): String = value

    companion object {
        /** A new globally unique id. */
        fun random(): ScenarioId = ScenarioId("scn-${UUID.randomUUID()}")

        /**
         * A readable, deterministic id for a code-defined scenario.
         *
         * Built-in scenarios are re-declared on every app launch, so they must
         * keep the same id across builds — otherwise "the active scenario" would
         * disappear on every restart.
         */
        fun builtIn(packKey: String, scenarioKey: String): ScenarioId =
            ScenarioId("builtin:${packKey.netKitSlug()}/${scenarioKey.netKitSlug()}")
    }
}

/** Identity of a scenario pack. */
@JvmInline
value class ScenarioPackId(val value: String) {
    init {
        require(value.isNotBlank()) { "NetKit scenario pack id cannot be blank" }
    }

    override fun toString(): String = value

    companion object {
        fun random(): ScenarioPackId = ScenarioPackId("pack-${UUID.randomUUID()}")

        fun builtIn(packKey: String): ScenarioPackId = ScenarioPackId("builtin:${packKey.netKitSlug()}")
    }
}

/**
 * A conservative id fragment: lowercase, letters/digits/`-`/`_` only.
 *
 * Ids reach an HTTP response header (`X-NetKit-Rule`) and a JSON file, so they
 * stay boring on purpose.
 */
internal fun String.netKitSlug(): String =
    trim().lowercase().map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
        .joinToString("")
        .trim('-')
        .ifEmpty { "unnamed" }

/** Where a scenario came from. The UI shows this so QA can tell them apart. */
enum class ScenarioSource {
    /** Built in the debug console by a developer or QA engineer. */
    CREATED_IN_APP,

    /** Read from a `.netkit.json` file. */
    IMPORTED,

    /** Declared in application code and registered at startup. Not editable. */
    BUILT_IN,
    ;

    val label: String get() = when (this) {
        CREATED_IN_APP -> "Saved"
        IMPORTED -> "Imported"
        BUILT_IN -> "Built-in"
    }
}

/**
 * Provenance and housekeeping for a saved scenario.
 *
 * Kept separate from the behaviour so that editing a rule and re-saving does not
 * have to reason about timestamps, and so the serializer can round-trip
 * behaviour independently of when it was written.
 *
 * @param createdAtMillis wall clock at creation.
 * @param updatedAtMillis wall clock at the last save.
 * @param source where the scenario came from.
 * @param packId the pack this scenario belongs to, or `null` for a loose scenario.
 */
data class ScenarioMetadata(
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
    val source: ScenarioSource = ScenarioSource.CREATED_IN_APP,
    val packId: ScenarioPackId? = null,
) {
    /** True when NetKit must not let the debug UI overwrite this definition. */
    val isReadOnly: Boolean get() = source == ScenarioSource.BUILT_IN
}

/**
 * A named, reusable collection of network behaviour.
 *
 * This is the unit a developer saves, a QA engineer activates, and either of
 * them exports and attaches to a bug report. It is a **definition**: nothing
 * inside it changes as requests run. Sequence cursors, activation and history
 * all live elsewhere, which is what makes a scenario safe to serialise, copy
 * and compare.
 *
 * ```kotlin
 * val scenario = NetworkScenario(
 *     name = "Checkout gateway failure",
 *     globalConfig = null,
 *     rules = listOf(
 *         EndpointRule.forPath(
 *             path = "/api/v1/checkout",
 *             method = HttpMethod.POST,
 *             action = NetworkAction.ReturnResponse(503),
 *         ),
 *     ),
 * )
 * ```
 *
 * @param id stable identity; survives edits, export and import.
 * @param name what QA sees in the scenario list. Required and non-blank.
 * @param description optional longer explanation, e.g. the bug being reproduced.
 * @param enabled a per-scenario pause switch. An activated scenario with
 *   `enabled = false` stays selected but stops affecting traffic, which is how
 *   the console's "Active" card toggles a scenario off and on without losing it.
 * @param globalConfig the global behaviour this scenario imposes, or `null` to
 *   leave the console's own global setting in charge.
 * @param rules endpoint overrides, evaluated in order; the first match wins.
 * @param metadata provenance and timestamps.
 */
data class NetworkScenario(
    val id: ScenarioId = ScenarioId.random(),
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val globalConfig: GlobalNetworkConfig? = null,
    val rules: List<EndpointRule> = emptyList(),
    val metadata: ScenarioMetadata = ScenarioMetadata(),
) {
    /** Rules currently eligible for matching. */
    val activeRules: List<EndpointRule> get() = rules.filter(EndpointRule::enabled)

    /** True when activating this scenario would change nothing. */
    val isIdle: Boolean
        get() = !enabled ||
            (rules.none(EndpointRule::enabled) && (globalConfig == null || globalConfig.isNormal))

    /** True when any rule uses a [io.devkit.netkit.scenario.NetworkAction.Sequence]. */
    val hasSequence: Boolean
        get() = rules.any { it.action is io.devkit.netkit.scenario.NetworkAction.Sequence }

    /** `3 rules · Global 2500ms` — the subtitle in the scenario list. */
    val summary: String
        get() = buildString {
            append(rules.size)
            append(if (rules.size == 1) " rule" else " rules")
            globalConfig?.takeIf { !it.isNormal }?.let {
                append(" · ")
                append(it.summary)
            }
        }

    /**
     * A copy under a new identity, with fresh ids for every rule.
     *
     * Rule ids are regenerated on purpose: sequence progress is keyed on rule
     * id, so a copy that reused them would share a cursor with its original.
     *
     * @param name the copy's name; defaults to `"<name> Copy"`.
     */
    fun duplicated(
        name: String = "${this.name} Copy",
        nowMillis: Long = System.currentTimeMillis(),
    ): NetworkScenario = copy(
        id = ScenarioId.random(),
        name = name,
        rules = rules.map { it.copy(id = EndpointRule.nextId()) },
        metadata = metadata.copy(
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            // A copy of a built-in scenario is an ordinary editable scenario;
            // that is the whole point of duplicating one.
            source = if (metadata.source == ScenarioSource.BUILT_IN) {
                ScenarioSource.CREATED_IN_APP
            } else {
                metadata.source
            },
        ),
    )
}

/**
 * A reusable collection of related scenarios.
 *
 * Packs are organisational, not behavioural: activating a pack is not a thing,
 * activating one scenario *from* a pack is. Membership is stored on the
 * scenario ([ScenarioMetadata.packId]) so a scenario is never in two places at
 * once and a pack rename cannot orphan anything.
 *
 * @param id stable identity.
 * @param name what the scenario list groups under.
 * @param description optional explanation of what the pack covers.
 * @param source where the pack came from.
 */
data class ScenarioPack(
    val id: ScenarioPackId = ScenarioPackId.random(),
    val name: String,
    val description: String? = null,
    val source: ScenarioSource = ScenarioSource.CREATED_IN_APP,
) {
    /** True when the pack is declared in code and must not be edited in the UI. */
    val isReadOnly: Boolean get() = source == ScenarioSource.BUILT_IN
}

/** A pack together with the scenarios that belong to it, as the UI renders it. */
data class ScenarioPackContents(
    val pack: ScenarioPack,
    val scenarios: List<NetworkScenario>,
) {
    val summary: String
        get() = "${scenarios.size} " + if (scenarios.size == 1) "scenario" else "scenarios"
}

/** Sequential names for new scenarios, so "New scenario" never collides. */
internal object ScenarioNaming {
    private val counter = AtomicLong(0)

    fun nextDefaultName(): String {
        val n = counter.incrementAndGet()
        return if (n == 1L) "New scenario" else "New scenario $n"
    }

    /** `Checkout Failure` → `Checkout Failure (Imported)`, avoiding collisions. */
    fun uniqueName(desired: String, taken: Set<String>, suffix: String): String {
        if (desired !in taken) return desired
        val withSuffix = "$desired ($suffix)"
        if (withSuffix !in taken) return withSuffix
        var index = 2
        while ("$withSuffix $index" in taken) index++
        return "$withSuffix $index"
    }
}
