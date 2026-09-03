package io.devkit.netkit.scenario.run

import io.devkit.netkit.scenario.model.ScenarioId
import java.util.UUID

/** Identity of one execution of a scenario. */
@JvmInline
value class ScenarioRunId(val value: String) {
    init {
        require(value.isNotBlank()) { "NetKit scenario run id cannot be blank" }
    }

    override fun toString(): String = value

    companion object {
        /** A new run id, short enough to paste into a ticket. */
        fun random(): ScenarioRunId =
            ScenarioRunId("run-" + UUID.randomUUID().toString().take(8))
    }
}

/** Why the current run was started. Shown as the first event on the timeline. */
enum class RunStartReason {
    /** A scenario was activated. */
    ACTIVATED,

    /** The same scenario was restarted on the same seed. */
    RESTARTED,

    /** The seed was changed, deliberately or by generating a new one. */
    SEED_CHANGED,

    /** The active scenario's rules were edited underneath a running scenario. */
    DEFINITION_CHANGED,

    /** Chaos mode was switched on outside any scenario. */
    CHAOS_ENABLED,

    /** Runtime state was reset without changing the definition. */
    RESET,
    ;

    val label: String get() = when (this) {
        ACTIVATED -> "Scenario activated"
        RESTARTED -> "Restarted with the same seed"
        SEED_CHANGED -> "Restarted with a new seed"
        DEFINITION_CHANGED -> "Scenario edited"
        CHAOS_ENABLED -> "Chaos enabled"
        RESET -> "Run reset"
    }
}

/**
 * One execution of a scenario, from activation to whatever ends it.
 *
 * This is the concept 0.3 adds, and the whole release turns on the distinction it
 * draws:
 *
 * ```text
 * NetworkScenario   the definition   — saved, exported, shared, immutable
 * ScenarioRun       the execution    — seeded, counted, restartable, disposable
 * ```
 *
 * A definition says "20% of calls to `/bookings` fail". A run says "with seed
 * 843921773, on this device, since 09:44, evaluation #17 was the one that got the
 * 503". A bug report needs both, and before 0.3 only the first existed — which is
 * precisely why "it fails sometimes on a bad network" was not a reproducible
 * report.
 *
 * The type is an immutable snapshot. Counters advance by replacing it, so a UI
 * reading one always sees a coherent set of numbers rather than a torn read
 * across four atomics.
 *
 * @param id identity of this execution, distinct from the scenario's own id.
 * @param scenarioId the definition being run, or `null` for a chaos-only run
 *   driven from the console with no scenario active.
 * @param scenarioName captured at start, so the run stays readable after a rename.
 * @param seed the value every stochastic decision in this run derives from. This
 *   is the number a QA engineer copies into a ticket.
 * @param startedAtMillis wall clock at the start of the run.
 * @param startReason what began this run.
 * @param evaluationCount how many requests the engine has evaluated.
 * @param simulatedCount how many of those NetKit answered itself.
 * @param passThroughCount how many reached the real backend.
 * @param latencyInjectedCount how many had artificial delay added.
 * @param totalInjectedLatencyMillis the sum of that delay, for the average.
 */
data class ScenarioRun(
    val id: ScenarioRunId,
    val scenarioId: ScenarioId?,
    val scenarioName: String?,
    val seed: Long,
    val startedAtMillis: Long,
    val startReason: RunStartReason,
    val evaluationCount: Long = 0,
    val simulatedCount: Long = 0,
    val passThroughCount: Long = 0,
    val latencyInjectedCount: Long = 0,
    val totalInjectedLatencyMillis: Long = 0,
) {
    /** Mean artificial delay across the requests that got any, in milliseconds. */
    val averageInjectedLatencyMillis: Long
        get() = if (latencyInjectedCount == 0L) 0 else
            totalInjectedLatencyMillis / latencyInjectedCount

    /** `1.8s` — the average rendered for the run dashboard. */
    val averageLatencyLabel: String
        get() = averageInjectedLatencyMillis.let { millis ->
            when {
                millis == 0L -> "—"
                millis < 1_000 -> "${millis}ms"
                else -> String.format(java.util.Locale.US, "%.1fs", millis / 1000.0)
            }
        }

    /** What fraction of evaluated requests NetKit answered. */
    val simulatedPercent: Int
        get() = if (evaluationCount == 0L) 0 else
            Math.round(simulatedCount * 100.0 / evaluationCount).toInt()

    /** `Poor Mobile Network · seed 843921773` */
    val label: String get() = "${scenarioName ?: "Chaos"} · seed $seed"
}

/**
 * Per-rule counters for one run.
 *
 * These answer the question a QA engineer asks most often about a scenario that
 * "isn't working": did the rule not match, did its condition rule it out, or did
 * its probability simply not come up? Three very different fixes, and without
 * these numbers all three look identical from the outside.
 *
 * The funnel narrows in evaluation order, so `executed <= probabilityPassed <=
 * conditionPassed <= matched <= evaluated` always holds.
 *
 * @param evaluated how many requests this rule was considered for.
 * @param matched how many matched its method and path.
 * @param conditionPassed how many of those also satisfied every condition.
 * @param probabilityPassed how many of those also passed the probability gate.
 * @param executed how many actually produced a decision.
 */
data class RuleStatistics(
    val ruleId: String,
    val ruleLabel: String,
    val evaluated: Long = 0,
    val matched: Long = 0,
    val conditionPassed: Long = 0,
    val probabilityPassed: Long = 0,
    val executed: Long = 0,
) {
    /** True when nothing about this rule has been exercised yet. */
    val isUntouched: Boolean get() = evaluated == 0L

    /**
     * The most useful sentence about why this rule is not firing, or `null` when
     * it is firing or has not been reached.
     */
    val diagnosis: String?
        get() = when {
            executed > 0 -> null
            evaluated == 0L -> null
            matched == 0L -> "Matched no request — check the method and path."
            conditionPassed == 0L -> "Matched $matched, but a condition ruled every one out."
            probabilityPassed == 0L -> "Conditions passed $conditionPassed times; the " +
                "probability never came up."

            else -> null
        }
}

/**
 * One evaluation's contribution to a rule's funnel.
 *
 * A rule can only reach each stage once per request, so the stages are booleans
 * rather than counts. Collected during the evaluation and applied in one batch by
 * [ScenarioRunManager.applyRuleDeltas], which is what keeps the request path from
 * doing five copy-on-write map updates per rule.
 */
data class RuleStatDelta(
    val ruleId: String,
    val ruleLabel: String,
    val evaluated: Boolean = false,
    val matched: Boolean = false,
    val conditionPassed: Boolean = false,
    val probabilityPassed: Boolean = false,
    val executed: Boolean = false,
) {
    /** Folds this evaluation into [statistics]. */
    internal fun applyTo(statistics: RuleStatistics): RuleStatistics = statistics.copy(
        // The label can change under an edit; the most recent one is the useful one.
        ruleLabel = ruleLabel,
        evaluated = statistics.evaluated + evaluated.toLong(),
        matched = statistics.matched + matched.toLong(),
        conditionPassed = statistics.conditionPassed + conditionPassed.toLong(),
        probabilityPassed = statistics.probabilityPassed + probabilityPassed.toLong(),
        executed = statistics.executed + executed.toLong(),
    )

    private fun Boolean.toLong(): Long = if (this) 1L else 0L
}

/** Run-scoped counters for chaos mode. */
data class ChaosStatistics(
    val evaluated: Long = 0,
    val inScope: Long = 0,
    val excluded: Long = 0,
    val failed: Long = 0,
    val passedThrough: Long = 0,
    val latencyInjected: Long = 0,
    val timeouts: Long = 0,
    val httpFailures: Long = 0,
    val disconnects: Long = 0,
    val offline: Long = 0,
) {
    val isUntouched: Boolean get() = evaluated == 0L

    /** `18 in scope · 3 failed · 2 excluded` */
    val summary: String
        get() = "$inScope in scope · $failed failed · $excluded excluded"
}
