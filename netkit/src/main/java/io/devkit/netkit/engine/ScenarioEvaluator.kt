package io.devkit.netkit.engine

import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.RequestTarget
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import io.devkit.netkit.scenario.runtime.RuleSource
import io.devkit.netkit.scenario.runtime.ScenarioExecutionState
import io.devkit.netkit.scenario.run.ExecutionEventType
import io.devkit.netkit.scenario.run.ExecutionTimeline
import io.devkit.netkit.scenario.run.ScenarioRunManager

/**
 * Turns an [ActiveNetworkConfiguration] plus one request into a
 * [ScenarioDecision].
 *
 * ### The pipeline
 *
 * 0.3 split what was one function into named stages, because a single `when` over
 * matching, conditions, probability, chaos, weighted outcomes and eight action
 * types would have been unreadable and — more to the point — untestable stage by
 * stage.
 *
 * ```text
 * request
 *    ↓  rule candidate matching        EndpointRule.matches
 *    ↓  condition evaluation           RuleGate  → RuleCondition
 *    ↓  probability evaluation         RuleGate  → Probability
 *    ↓  chaos                          ChaosEvaluator
 *    ↓  action resolution              ActionResolver
 *    ↓  deterministic random           NetKitRandom, seeded per evaluation
 * ScenarioDecision
 * ```
 *
 * Each stage is an object with one entry point and no state of its own; all
 * mutable runtime state lives in [io.devkit.netkit.scenario.run.ScenarioRunManager]
 * and is reached through [ScenarioExecutionContext].
 *
 * ### Precedence
 *
 * ```text
 * 1. NetKit disabled                → PassThrough
 * 2. temporary endpoint override    → its action, attributed to Temporary
 * 3. active scenario endpoint rule  → its action, attributed to the scenario
 * 4. chaos (scenario's, else the console's)
 * 5. active scenario global config  → when the scenario sets one
 * 6. temporary global configuration → offline / timeout / latency
 * 7. otherwise                      → PassThrough
 * ```
 *
 * ### Rule evaluation continues past a rule that declines
 *
 * A rule whose conditions or probability fail does **not** end evaluation: the
 * next rule is tried. That is what makes layering work. Given
 *
 * ```text
 * GET /bookings   30%  → HTTP 500
 * GET /bookings        → Delay 2000ms
 * ```
 *
 * 30% of calls fail and the other 70% are slow, which is what someone writing
 * those two rules in that order plainly meant. The 0.2 behaviour — first
 * *matching* rule wins — is unchanged for rules with no conditions and no
 * probability, because such a rule never declines.
 *
 * Within each layer rules are tried in list order, so the console's ordering is
 * the precedence order and a QA engineer can predict which override wins.
 */
object ScenarioEvaluator {

    /**
     * Evaluates [target] against [configuration] without a run in progress.
     *
     * A convenience for callers that only want the decision and do not care about
     * seeds, counters or the timeline — chiefly tests, and anything driving the
     * evaluator directly. It spins up a throwaway run so stochastic rules still
     * work, which makes their results **reproducible only within one call**.
     * Anything that needs decisions to be reproducible across a session must go
     * through [io.devkit.netkit.engine.DefaultNetworkScenarioEngine], which owns a
     * real run.
     *
     * @param runManager an existing run to evaluate against; a throwaway is used
     *   when omitted.
     */
    fun evaluate(
        configuration: ActiveNetworkConfiguration,
        target: RequestTarget,
        executionState: ScenarioExecutionState,
        runManager: ScenarioRunManager = ScenarioRunManager(
            executionState = executionState,
            timeline = ExecutionTimeline(enabled = false),
        ),
    ): ScenarioDecision = evaluate(
        configuration = configuration,
        context = ScenarioExecutionContext(
            target = target,
            evaluationIndex = runManager.beginEvaluation(),
            runManager = runManager,
            timelineEnabled = runManager.timeline.enabled,
        ),
        executionState = executionState,
    )

    /**
     * Evaluates the request in [context] against [configuration]. Never throws.
     *
     * @param executionState the cursor layer response sequences advance.
     */
    fun evaluate(
        configuration: ActiveNetworkConfiguration,
        context: ScenarioExecutionContext,
        executionState: ScenarioExecutionState,
    ): ScenarioDecision {
        if (!configuration.enabled) return PASS_THROUGH

        firstDecision(
            rules = configuration.rules,
            source = RuleSource.Temporary,
            context = context,
            executionState = executionState,
        )?.let { return it }

        val scenario = configuration.scenario?.takeIf { it.enabled }
        if (scenario != null) {
            firstDecision(
                rules = scenario.rules,
                source = scenario.source,
                context = context,
                executionState = executionState,
            )?.let { return it }
        }

        ChaosEvaluator.evaluate(
            config = configuration.effectiveChaos,
            context = context,
            fromScenario = configuration.chaosFromScenario,
            scenarioSource = scenario?.source,
        )?.let { return it }

        scenario?.global?.let { global ->
            return fromGlobal(global, scenario.source, scenario.name, context)
        }

        return fromGlobal(configuration.global, RuleSource.Temporary, null, context)
    }

    /**
     * The first rule in [rules] that both matches and consents to act.
     *
     * @return its decision, or `null` when no rule claimed the request.
     */
    private fun firstDecision(
        rules: List<EndpointRule>,
        source: RuleSource,
        context: ScenarioExecutionContext,
        executionState: ScenarioExecutionState,
    ): ScenarioDecision? {
        // Indexed loop: this runs on every request and must not allocate an iterator.
        for (index in rules.indices) {
            val rule = rules[index]
            // A disabled rule is skipped entirely and never appears in the
            // statistics — its absence is the answer to "why is it not firing".
            if (!rule.enabled) continue
            when (RuleGate.evaluate(rule, context)) {
                RuleGateResult.Passed -> return fromRule(rule, source, context, executionState)
                // Declined for a reason the gate has already recorded and
                // explained; keep looking so layered rules compose.
                is RuleGateResult.ConditionFailed,
                is RuleGateResult.ProbabilityFailed,
                RuleGateResult.NotMatched,
                -> continue
            }
        }
        return null
    }

    /**
     * Resolves one rule's action, unrolling a response sequence into whichever
     * step is next for that rule.
     */
    private fun fromRule(
        rule: EndpointRule,
        source: RuleSource,
        context: ScenarioExecutionContext,
        executionState: ScenarioExecutionState,
    ): ScenarioDecision {
        context.recordExecuted(rule)
        val attribution = Attribution(
            origin = DecisionOrigin.ENDPOINT_RULE,
            label = rule.displayName,
            ruleId = rule.id,
            source = source,
            randomKey = rule.id,
        )

        val action = rule.action
        if (action !is NetworkAction.Sequence) {
            context.record(
                type = ExecutionEventType.ACTION_EXECUTED,
                ruleLabel = rule.displayName,
                detail = action.label,
            )
            return ActionResolver.resolve(action, attribution, context)
        }

        val index = executionState.advance(rule.id, action.steps.size, action.completion)
        if (index < 0) {
            // A PASS_THROUGH sequence that has run out of steps: the endpoint
            // goes back to the real backend, and history still says which rule
            // let it through.
            context.record(
                type = ExecutionEventType.SEQUENCE_ADVANCED,
                ruleLabel = rule.displayName,
                detail = "Sequence complete",
            )
            return ScenarioDecision.PassThrough(
                origin = DecisionOrigin.ENDPOINT_RULE,
                scenarioLabel = "${rule.displayName} · sequence complete",
                ruleId = rule.id,
                source = source,
                sequence = SequenceStepInfo(action.steps.size, action.steps.size),
                evaluation = EvaluationInfo(context.evaluationIndex, context.seed),
            )
        }
        val step = SequenceStepInfo(step = index + 1, stepCount = action.steps.size)
        context.record(
            type = ExecutionEventType.SEQUENCE_ADVANCED,
            ruleLabel = rule.displayName,
            detail = action.steps[index].label,
            reason = "step ${step.display}",
        )
        return ActionResolver.resolve(
            action = action.steps[index].action,
            attribution = attribution,
            context = context,
            sequence = step,
        )
    }

    private fun fromGlobal(
        global: GlobalNetworkConfig,
        source: RuleSource,
        scenarioName: String?,
        context: ScenarioExecutionContext,
    ): ScenarioDecision {
        val prefix = scenarioName?.let { "$it · " }.orEmpty()
        val evaluation = EvaluationInfo(context.evaluationIndex, context.seed)
        return when (val mode = global.mode) {
            GlobalNetworkMode.Offline -> ScenarioDecision.FailOffline(
                origin = DecisionOrigin.GLOBAL,
                scenarioLabel = "${prefix}Global offline",
                source = source,
                evaluation = evaluation,
            )

            is GlobalNetworkMode.Timeout -> ScenarioDecision.FailTimeout(
                type = mode.type,
                origin = DecisionOrigin.GLOBAL,
                scenarioLabel = "${prefix}Global ${mode.label.lowercase()}",
                source = source,
                evaluation = evaluation,
            )

            GlobalNetworkMode.Normal -> if (global.latencyMillis > 0) {
                ScenarioDecision.Delay(
                    delayMillis = global.latencyMillis,
                    origin = DecisionOrigin.GLOBAL,
                    scenarioLabel = "${prefix}Global latency ${global.latencyMillis}ms",
                    source = source,
                    evaluation = evaluation,
                )
            } else {
                PASS_THROUGH
            }
        }
    }

    /** Shared instance: the overwhelmingly common decision should not allocate. */
    private val PASS_THROUGH = ScenarioDecision.PassThrough()
}
