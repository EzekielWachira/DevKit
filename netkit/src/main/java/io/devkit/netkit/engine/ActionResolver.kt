package io.devkit.netkit.engine

import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.random.RandomPurpose
import io.devkit.netkit.scenario.pick
import io.devkit.netkit.scenario.runtime.RuleSource
import io.devkit.netkit.scenario.run.ExecutionEventType

/**
 * Everything a resolved action needs to be attributed, other than the action.
 *
 * Passed as one value rather than five parameters because the resolver is called
 * from three places — a temporary rule, a scenario rule and chaos — and each
 * wants a different label while producing the same shape of decision.
 *
 * @param origin which layer produced the decision.
 * @param label the human description stamped on history.
 * @param ruleId the responsible rule, or `null` for chaos and global.
 * @param source the attribution captured at request time.
 * @param randomKey what random draws inside the action key on, so two rules with
 *   identical weighted outcomes still decide independently.
 */
internal data class Attribution(
    val origin: DecisionOrigin,
    val label: String,
    val ruleId: String?,
    val source: RuleSource?,
    val randomKey: String,
)

/**
 * Turns one [NetworkAction] into one [ScenarioDecision].
 *
 * The only stage allowed to draw from the random stream for the *content* of a
 * decision — a weighted choice or a latency inside a range. Keeping that in one
 * function is what makes the "every stochastic feature flows through the seeded
 * engine" rule checkable by reading a single file rather than auditing the whole
 * engine.
 *
 * Resolution is otherwise pure: given the same action, attribution and random
 * stream it always produces the same decision.
 */
internal object ActionResolver {

    /**
     * Resolves [action] under [attribution].
     *
     * @param sequence the step info to stamp, when a response sequence produced
     *   this action.
     * @param depth guards against an action that resolves to another action; only
     *   [NetworkAction.Weighted] can do so, and only one level deep.
     */
    fun resolve(
        action: NetworkAction,
        attribution: Attribution,
        context: ScenarioExecutionContext,
        sequence: SequenceStepInfo? = null,
        depth: Int = 0,
    ): ScenarioDecision {
        val evaluation = EvaluationInfo(context.evaluationIndex, context.seed)
        return when (action) {
            NetworkAction.PassThrough -> ScenarioDecision.PassThrough(
                origin = attribution.origin,
                scenarioLabel = attribution.label,
                ruleId = attribution.ruleId,
                source = attribution.source,
                sequence = sequence,
                evaluation = evaluation,
            )

            is NetworkAction.Delay -> delayOrPassThrough(
                delayMillis = action.delayMillis,
                attribution = attribution,
                sequence = sequence,
                evaluation = evaluation,
            )

            is NetworkAction.RandomDelay -> {
                val picked = action.latency.pick(
                    context.random(RandomPurpose.LATENCY, attribution.randomKey),
                )
                delayOrPassThrough(
                    delayMillis = picked,
                    attribution = attribution,
                    sequence = sequence,
                    evaluation = evaluation,
                )
            }

            is NetworkAction.ReturnResponse -> ScenarioDecision.RespondWith(
                statusCode = action.statusCode,
                body = action.body ?: defaultBodyFor(action.statusCode),
                contentType = action.contentType,
                delayMillis = action.delayMillis,
                origin = attribution.origin,
                scenarioLabel = attribution.label,
                ruleId = attribution.ruleId,
                headers = action.headers,
                source = attribution.source,
                sequence = sequence,
                evaluation = evaluation,
            )

            is NetworkAction.Malformed -> ScenarioDecision.RespondWith(
                statusCode = action.statusCode,
                body = action.type.body,
                contentType = action.type.contentType,
                delayMillis = action.delayMillis,
                origin = attribution.origin,
                scenarioLabel = "${attribution.label} · ${action.type.label}",
                ruleId = attribution.ruleId,
                malformed = true,
                source = attribution.source,
                sequence = sequence,
                evaluation = evaluation,
            )

            NetworkAction.Offline -> ScenarioDecision.FailOffline(
                origin = attribution.origin,
                scenarioLabel = attribution.label,
                ruleId = attribution.ruleId,
                source = attribution.source,
                sequence = sequence,
                evaluation = evaluation,
            )

            NetworkAction.Disconnect -> ScenarioDecision.FailDisconnect(
                origin = attribution.origin,
                scenarioLabel = attribution.label,
                ruleId = attribution.ruleId,
                source = attribution.source,
                sequence = sequence,
                evaluation = evaluation,
            )

            is NetworkAction.Timeout -> ScenarioDecision.FailTimeout(
                type = action.type,
                origin = attribution.origin,
                scenarioLabel = attribution.label,
                ruleId = attribution.ruleId,
                source = attribution.source,
                sequence = sequence,
                evaluation = evaluation,
            )

            is NetworkAction.Weighted -> {
                // Nesting is rejected by WeightedOutcome's own constructor, so
                // reaching depth 1 would be a NetKit bug. Degrading to
                // pass-through rather than recursing is the only safe response on
                // the request path.
                if (depth > 0) {
                    return ScenarioDecision.PassThrough(
                        origin = attribution.origin,
                        scenarioLabel = attribution.label,
                        ruleId = attribution.ruleId,
                        source = attribution.source,
                        sequence = sequence,
                        evaluation = evaluation,
                    )
                }
                val random = context.random(RandomPurpose.OUTCOME, attribution.randomKey)
                val chosen = action.outcomes.pick(random)
                    ?: return ScenarioDecision.PassThrough(
                        origin = attribution.origin,
                        scenarioLabel = attribution.label,
                        ruleId = attribution.ruleId,
                        source = attribution.source,
                        sequence = sequence,
                        evaluation = evaluation,
                    )
                context.record(
                    type = ExecutionEventType.ACTION_EXECUTED,
                    ruleLabel = attribution.label,
                    detail = chosen.label,
                    reason = "weighted ${chosen.weight}/${action.outcomes.sumOf { it.weight }}",
                )
                resolve(
                    action = chosen.action,
                    attribution = attribution,
                    context = context,
                    sequence = sequence,
                    depth = depth + 1,
                )
            }

            // Guarded by SequenceStep's own constructor and by the validator; the
            // evaluator unrolls sequences before calling here, so reaching this
            // branch would be a NetKit bug.
            is NetworkAction.Sequence -> ScenarioDecision.PassThrough(
                origin = attribution.origin,
                scenarioLabel = attribution.label,
                ruleId = attribution.ruleId,
                source = attribution.source,
                sequence = sequence,
                evaluation = evaluation,
            )
        }
    }

    /**
     * A zero delay is a pass-through, not a `Delay(0)`.
     *
     * Matters for more than tidiness: the interceptor's fast path and the run's
     * latency average both key on whether any delay was injected, and a stream of
     * `Delay(0)` decisions would report an average latency of zero across
     * thousands of requests that were never delayed at all.
     */
    private fun delayOrPassThrough(
        delayMillis: Long,
        attribution: Attribution,
        sequence: SequenceStepInfo?,
        evaluation: EvaluationInfo,
    ): ScenarioDecision = if (delayMillis <= 0) {
        ScenarioDecision.PassThrough(
            origin = attribution.origin,
            scenarioLabel = attribution.label,
            ruleId = attribution.ruleId,
            source = attribution.source,
            sequence = sequence,
            evaluation = evaluation,
        )
    } else {
        ScenarioDecision.Delay(
            delayMillis = delayMillis,
            origin = attribution.origin,
            scenarioLabel = attribution.label,
            ruleId = attribution.ruleId,
            source = attribution.source,
            sequence = sequence,
            evaluation = evaluation,
        )
    }

    /**
     * `204` and `304` must not carry a body, and inventing an error envelope for
     * a `200` would be misleading; everything else gets NetKit's marker payload
     * so a developer reading a response can tell where it came from.
     */
    internal fun defaultBodyFor(statusCode: Int): String = when {
        statusCode == 204 || statusCode == 304 -> ""
        statusCode in 200..299 -> NetKitDefaults.DEFAULT_SUCCESS_BODY
        else -> NetKitDefaults.DEFAULT_ERROR_BODY
    }
}
