package io.devkit.netkit.engine

import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.chaos.ChaosConfig
import io.devkit.netkit.scenario.pick
import io.devkit.netkit.scenario.random.RandomPurpose
import io.devkit.netkit.scenario.runtime.RuleSource
import io.devkit.netkit.scenario.run.ChaosStatistics
import io.devkit.netkit.scenario.run.ExecutionEventType

/**
 * Applies chaos mode to a request no explicit rule claimed.
 *
 * ### The shape of one chaos evaluation
 *
 * ```text
 * in scope?         no  → nothing; the request continues to the global layer
 * excluded?         yes → nothing, and counted as excluded
 * failure draw      hit → pick a weighted failure and return it
 *                   miss→ apply the latency range, if any
 * ```
 *
 * Two draws at most, both from the run's seeded stream keyed on the evaluation
 * index, and neither taken unless it can change the answer: a config with a zero
 * failure rate never draws, and a fixed latency range never draws. That keeps the
 * stream position a function of what the configuration *does* rather than of how
 * it happens to be written, so tightening a latency range from `500–3000` to a
 * flat `500` does not shift every subsequent decision.
 *
 * Chaos never claims a request an endpoint rule already claimed — see the
 * precedence table on
 * [io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration].
 */
internal object ChaosEvaluator {

    /**
     * Evaluates [config] against the request in [context].
     *
     * @param fromScenario whether the config came from the active scenario or the
     *   console, which only affects the label.
     * @param scenarioSource the attribution to stamp when chaos belongs to a
     *   scenario, so history still names the responsible scenario.
     * @return a decision, or `null` when chaos had nothing to say and evaluation
     *   should continue to the global layer.
     */
    fun evaluate(
        config: ChaosConfig,
        context: ScenarioExecutionContext,
        fromScenario: Boolean,
        scenarioSource: RuleSource?,
    ): ScenarioDecision? {
        if (config.isIdle) return null

        context.updateChaos { it.copy(evaluated = it.evaluated + 1) }

        if (!config.scope.matches(context.target)) return null

        if (config.exclusions.excludes(context.target)) {
            context.updateChaos { it.copy(excluded = it.excluded + 1) }
            context.record(
                type = ExecutionEventType.CHAOS_PASS_THROUGH,
                ruleLabel = CHAOS_LABEL,
                detail = "Excluded",
                reason = "matches an exclusion",
            )
            return null
        }

        context.updateChaos { it.copy(inScope = it.inScope + 1) }

        val attribution = Attribution(
            origin = DecisionOrigin.CHAOS,
            label = if (fromScenario && scenarioSource is RuleSource.Scenario) {
                "${scenarioSource.scenarioName} · $CHAOS_LABEL"
            } else {
                CHAOS_LABEL
            },
            ruleId = null,
            source = if (fromScenario) scenarioSource else RuleSource.Chaos,
            randomKey = CHAOS_KEY,
        )

        if (config.canFail) {
            val gate = context.random(RandomPurpose.PROBABILITY, CHAOS_KEY)
            if (config.failureProbability.draw(gate)) {
                val chosen = config.failures.pick(
                    context.random(RandomPurpose.CHAOS_ACTION, CHAOS_KEY),
                )
                if (chosen != null) {
                    context.updateChaos { stats ->
                        stats.copy(failed = stats.failed + 1).countingAction(chosen.action)
                    }
                    context.record(
                        type = ExecutionEventType.CHAOS_ACTION,
                        ruleLabel = attribution.label,
                        detail = chosen.action.label,
                        reason = "${config.failureProbability.percentLabel} failure rate",
                    )
                    return ActionResolver.resolve(chosen.action, attribution, context)
                }
            }
        }

        // Survived the failure draw: an in-scope request still gets the latency.
        if (!config.latency.isZero) {
            val delay = config.latency.pick(context.random(RandomPurpose.LATENCY, CHAOS_KEY))
            if (delay > 0) {
                context.updateChaos { stats ->
                    stats.copy(
                        passedThrough = stats.passedThrough + 1,
                        latencyInjected = stats.latencyInjected + 1,
                    )
                }
                context.record(
                    type = ExecutionEventType.CHAOS_ACTION,
                    ruleLabel = attribution.label,
                    detail = "Delay ${delay}ms",
                    reason = config.latency.label,
                )
                return ScenarioDecision.Delay(
                    delayMillis = delay,
                    origin = DecisionOrigin.CHAOS,
                    scenarioLabel = attribution.label,
                    source = attribution.source,
                    evaluation = EvaluationInfo(context.evaluationIndex, context.seed),
                )
            }
        }

        context.updateChaos { it.copy(passedThrough = it.passedThrough + 1) }
        context.record(
            type = ExecutionEventType.CHAOS_PASS_THROUGH,
            ruleLabel = attribution.label,
            detail = "Pass through",
        )
        return null
    }

    /** Adds one to whichever per-kind counter [action] belongs to. */
    private fun ChaosStatistics.countingAction(action: NetworkAction): ChaosStatistics =
        when (action) {
            is NetworkAction.Timeout -> copy(timeouts = timeouts + 1)
            is NetworkAction.ReturnResponse, is NetworkAction.Malformed ->
                copy(httpFailures = httpFailures + 1)

            NetworkAction.Disconnect -> copy(disconnects = disconnects + 1)
            NetworkAction.Offline -> copy(offline = offline + 1)
            else -> this
        }

    private const val CHAOS_LABEL = "Chaos"

    /**
     * The key every chaos draw uses.
     *
     * A constant rather than the path, so that chaos decisions depend only on the
     * evaluation index. Keying on the path would make "the 17th request fails"
     * depend on *which endpoint* the 17th request happened to hit, which is
     * exactly the kind of hidden coupling that makes a reproduction fail on
     * someone else's device.
     */
    private const val CHAOS_KEY = "netkit:chaos"
}
