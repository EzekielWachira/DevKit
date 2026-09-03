package io.devkit.netkit.engine

import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.condition.BodyCondition
import io.devkit.netkit.scenario.condition.RequestBodyPeek
import io.devkit.netkit.scenario.random.RandomPurpose
import io.devkit.netkit.scenario.run.ExecutionEventType

/**
 * Why a rule did or did not get to act on a request.
 *
 * A three-state answer rather than a boolean, because "the path did not match"
 * and "the dice said no" are the two explanations a QA engineer confuses most
 * often, and telling them apart is the whole point of the evaluation diagnostics.
 */
sealed interface RuleGateResult {

    /** The rule may act. */
    data object Passed : RuleGateResult

    /** The rule's method or path did not claim this request. */
    data object NotMatched : RuleGateResult

    /** A condition ruled it out. [detail] names which one and why. */
    data class ConditionFailed(val detail: String) : RuleGateResult

    /** Conditions passed; the probability draw did not. */
    data class ProbabilityFailed(val chance: String) : RuleGateResult
}

/**
 * Decides whether one rule may act on one request.
 *
 * Extracted from the evaluator because it is the part of 0.3 with the most rules
 * about *ordering* — match before conditions, conditions before probability,
 * counters claimed at match time — and those rules are far easier to verify in
 * fifty lines than buried in a `when` over eight action types.
 *
 * ### The counter is claimed at match time, not at execution time
 *
 * A rule's hit index advances as soon as its method and path claim a request,
 * before its conditions or probability run. That is what makes
 * `Exactly(1)` ("first request only") and `AtLeast(2)` ("every one after")
 * partition the traffic between them rather than both waiting for the other. The
 * alternative — counting only executions — would make "first request only" mean
 * "the first request that also passed a 30% dice roll", which is not what anyone
 * types it to mean.
 */
internal object RuleGate {

    /**
     * Runs [rule] through the match → conditions → probability funnel, recording
     * statistics and timeline events as it goes.
     */
    fun evaluate(rule: EndpointRule, context: ScenarioExecutionContext): RuleGateResult {
        // Recorded for every rule the evaluator looked at, matched or not: a rule
        // showing `evaluated = 23, matched = 0` is telling you the path is wrong,
        // which is the single most common reason a scenario "does nothing".
        context.recordEvaluated(rule)

        if (!rule.matches(context.target)) return RuleGateResult.NotMatched
        context.recordMatched(rule)

        // Claimed here, once, whatever happens below — see the class docs.
        val hitIndex = context.nextRuleHit(rule.id)

        if (rule.conditions.isNotEmpty()) {
            val conditionContext = context.conditionContext(hitIndex)
            val failing = rule.firstFailingCondition(conditionContext)
            if (failing != null) {
                val detail = if (failing is BodyCondition &&
                    conditionContext.body !is RequestBodyPeek.Text
                ) {
                    // A body condition that could not read the body is a very
                    // different situation from one that read it and disagreed,
                    // and a scenario that silently stopped applying deserves to
                    // say so rather than looking like a correct non-match.
                    "${failing.label} — ${conditionContext.body.diagnostic}"
                } else {
                    failing.label
                }
                context.record(
                    type = ExecutionEventType.RULE_SKIPPED_CONDITION,
                    ruleLabel = rule.displayName,
                    detail = detail,
                    reason = "request #$hitIndex for this rule",
                )
                return RuleGateResult.ConditionFailed(detail)
            }
        }
        context.recordConditionPassed(rule)

        if (!rule.probability.isAlways) {
            // Keyed on the rule id so each rule's gate is independent: inserting
            // an unrelated rule above this one must not change whether it fires.
            val random = context.random(RandomPurpose.PROBABILITY, rule.id)
            if (!rule.probability.draw(random)) {
                context.record(
                    type = ExecutionEventType.RULE_SKIPPED_PROBABILITY,
                    ruleLabel = rule.displayName,
                    detail = rule.probability.percentLabel,
                    reason = "did not come up",
                )
                return RuleGateResult.ProbabilityFailed(rule.probability.percentLabel)
            }
        }
        context.recordProbabilityPassed(rule)

        return RuleGateResult.Passed
    }
}
