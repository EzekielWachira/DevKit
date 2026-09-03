package io.devkit.netkit.engine

import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.RequestTarget
import io.devkit.netkit.scenario.condition.ConditionContext
import io.devkit.netkit.scenario.random.NetKitRandom
import io.devkit.netkit.scenario.random.RandomPurpose
import io.devkit.netkit.scenario.run.ChaosStatistics
import io.devkit.netkit.scenario.run.ExecutionEventType
import io.devkit.netkit.scenario.run.RuleStatDelta
import io.devkit.netkit.scenario.run.ScenarioRunManager

/**
 * Everything one evaluation needs from the run it belongs to.
 *
 * ### Why the mutable state is behind a manager rather than in this object
 *
 * A context is created per request and read by four collaborators
 * ([RuleGate], [ChaosEvaluator], [ActionResolver] and the evaluator itself).
 * Putting counters *in* it would mean either copying them — and losing the
 * increments — or making the context itself mutable and shared, which is a data
 * race waiting to happen across OkHttp's thread pool.
 *
 * So the context is a per-request value holding one thing that is genuinely
 * per-request — [evaluationIndex] — plus a reference to the [ScenarioRunManager]
 * that owns the atomics.
 *
 * ### Statistics are batched
 *
 * Rule statistics are accumulated into a small per-request list and published
 * **once**, by [flush], when the evaluation ends. Publishing each stage
 * separately would mean up to five copy-on-write updates of a shared map per
 * rule per request, which for a scenario with many rules is real work on the
 * request path — and buys nothing, because nobody can observe an intermediate
 * state of one request's funnel.
 *
 * An instance is confined to one thread and is not safe to share.
 *
 * @param target the request being evaluated.
 * @param evaluationIndex this request's run-scoped, 1-based number. Every random
 *   draw derives from it, which is what makes decisions reproducible regardless
 *   of how many other requests are in flight.
 * @param runManager the owner of the run's counters, seeds and timeline.
 * @param timelineEnabled cached so the hot path can skip building event strings.
 */
class ScenarioExecutionContext(
    val target: RequestTarget,
    val evaluationIndex: Long,
    private val runManager: ScenarioRunManager,
    val timelineEnabled: Boolean,
) {

    /** Sized for the common case: a request is considered by very few rules. */
    private val pendingStats = ArrayList<RuleStatDelta>(4)

    /** The seed this run is using, for diagnostics and history attribution. */
    val seed: Long get() = runManager.seed

    /** A stream for one purpose in this evaluation. */
    fun random(purpose: RandomPurpose): NetKitRandom =
        runManager.randomFor(evaluationIndex, purpose)

    /** A stream for one purpose in this evaluation, keyed on a rule. */
    fun random(purpose: RandomPurpose, key: String): NetKitRandom =
        runManager.randomFor(evaluationIndex, purpose, key)

    /** Claims the next 1-based hit index for [ruleId]. */
    fun nextRuleHit(ruleId: String): Long = runManager.nextRuleHit(ruleId)

    /**
     * The condition context for a rule, given the hit index already claimed.
     *
     * Built per rule rather than per request because [ConditionContext.ruleHitIndex]
     * is a property of the rule, and because the memoised body then lives exactly
     * as long as the rule that might read it.
     */
    fun conditionContext(hitIndex: Long): ConditionContext = ConditionContext(
        target = target,
        ruleHitIndex = hitIndex,
        evaluationIndex = evaluationIndex,
        hasSimulatedFailure = runManager.hasSimulatedFailure,
        executionsOf = runManager::executionsOf,
        bodySource = target.bodySource,
        maxBodyBytes = NetKitLimits.MAX_CONDITION_BODY_BYTES,
    )

    // ---- statistics ---------------------------------------------------------

    /**
     * Records that [rule] was considered.
     *
     * Every rule the evaluator looked at, including ones whose method or path did
     * not match. That is the point of the number: a rule with `evaluated = 23,
     * matched = 0` is telling you the path is wrong, and a rule that never
     * appeared at all is telling you it is disabled.
     */
    fun recordEvaluated(rule: EndpointRule) = stage(rule) { it.copy(evaluated = true) }

    /** Records that [rule] matched a request's method and path. */
    fun recordMatched(rule: EndpointRule) = stage(rule) { it.copy(matched = true) }

    /** Records that every condition on [rule] passed. */
    fun recordConditionPassed(rule: EndpointRule) =
        stage(rule) { it.copy(conditionPassed = true) }

    /** Records that [rule]'s probability gate passed. */
    fun recordProbabilityPassed(rule: EndpointRule) =
        stage(rule) { it.copy(probabilityPassed = true) }

    /** Records that [rule] produced a decision. */
    fun recordExecuted(rule: EndpointRule) = stage(rule) { it.copy(executed = true) }

    private inline fun stage(rule: EndpointRule, transform: (RuleStatDelta) -> RuleStatDelta) {
        val index = pendingStats.indexOfFirst { it.ruleId == rule.id }
        if (index >= 0) {
            pendingStats[index] = transform(pendingStats[index])
        } else {
            pendingStats += transform(RuleStatDelta(rule.id, rule.displayName))
        }
    }

    /**
     * Publishes this evaluation's statistics and its outcome.
     *
     * Called exactly once, by the engine, whatever the decision was.
     */
    fun flush(decision: ScenarioDecision) {
        runManager.applyRuleDeltas(pendingStats)
        runManager.recordOutcome(
            simulated = decision.isSimulated,
            injectedLatencyMillis = decision.injectedLatencyMillis,
        )
    }

    fun updateChaos(transform: (ChaosStatistics) -> ChaosStatistics) =
        runManager.updateChaos(transform)

    /** Appends a timeline event for this evaluation. */
    fun record(
        type: ExecutionEventType,
        ruleLabel: String? = null,
        detail: String? = null,
        reason: String? = null,
    ) {
        if (!timelineEnabled) return
        runManager.record(
            evaluationIndex = evaluationIndex,
            type = type,
            method = target.method,
            path = target.path,
            ruleLabel = ruleLabel,
            detail = detail,
            reason = reason,
        )
    }
}
