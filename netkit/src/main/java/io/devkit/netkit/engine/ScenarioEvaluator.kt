package io.devkit.netkit.engine

import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.RequestTarget
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import io.devkit.netkit.scenario.runtime.RuleSource
import io.devkit.netkit.scenario.runtime.ScenarioExecutionState

/**
 * Turns an [ActiveNetworkConfiguration] plus a [RequestTarget] into a
 * [ScenarioDecision].
 *
 * Pure apart from one deliberate exception: a [NetworkAction.Sequence] has to
 * ask [ScenarioExecutionState] which step is next, and that call advances a
 * cursor. Everything else — matching, precedence, action-to-decision mapping —
 * is a function of its inputs, which is what makes the whole layer testable
 * without OkHttp, Android or coroutines.
 *
 * ### Precedence
 *
 * ```text
 * 1. NetKit disabled                → PassThrough
 * 2. temporary endpoint override    → its action, attributed to Temporary
 * 3. active scenario endpoint rule  → its action, attributed to the scenario
 * 4. active scenario global config  → when the scenario sets one
 * 5. temporary global configuration → offline / timeout / latency
 * 6. otherwise                      → PassThrough
 * ```
 *
 * A matching rule **replaces** the global behaviour for that request; global
 * latency is not added on top. A rule with [NetworkAction.PassThrough] is
 * therefore an allow-list entry: that endpoint keeps reaching the backend even
 * while the rest of the app is offline.
 *
 * Rules are evaluated in list order within each layer, so the console's ordering
 * is the precedence order and a QA engineer can predict which override wins.
 */
object ScenarioEvaluator {

    /** Evaluates [target] against [configuration]. Never throws. */
    fun evaluate(
        configuration: ActiveNetworkConfiguration,
        target: RequestTarget,
        executionState: ScenarioExecutionState,
    ): ScenarioDecision {
        if (!configuration.enabled) return PASS_THROUGH

        configuration.findTemporaryRule(target)?.let { rule ->
            return fromRule(rule, RuleSource.Temporary, executionState)
        }

        val scenario = configuration.scenario?.takeIf { it.enabled }
        if (scenario != null) {
            scenario.findRule(target)?.let { rule ->
                return fromRule(rule, scenario.source, executionState)
            }
            scenario.global?.let { global ->
                return fromGlobal(global, scenario.source, scenario.name)
            }
        }

        return fromGlobal(configuration.global, RuleSource.Temporary, null)
    }

    /**
     * Resolves one rule's action into a decision, unrolling a response sequence
     * into whichever step is next for that rule.
     */
    private fun fromRule(
        rule: EndpointRule,
        source: RuleSource,
        executionState: ScenarioExecutionState,
    ): ScenarioDecision {
        val action = rule.action
        if (action !is NetworkAction.Sequence) {
            return fromAction(action, rule, source, sequence = null)
        }

        val index = executionState.advance(rule.id, action.steps.size, action.completion)
        if (index < 0) {
            // A PASS_THROUGH sequence that has run out of steps: the endpoint
            // goes back to the real backend, and history still says which rule
            // let it through.
            return ScenarioDecision.PassThrough(
                origin = DecisionOrigin.ENDPOINT_RULE,
                scenarioLabel = "${rule.displayName} · sequence complete",
                ruleId = rule.id,
                source = source,
                sequence = SequenceStepInfo(action.steps.size, action.steps.size),
            )
        }
        return fromAction(
            action = action.steps[index].action,
            rule = rule,
            source = source,
            sequence = SequenceStepInfo(step = index + 1, stepCount = action.steps.size),
        )
    }

    private fun fromAction(
        action: NetworkAction,
        rule: EndpointRule,
        source: RuleSource,
        sequence: SequenceStepInfo?,
    ): ScenarioDecision {
        // The step number travels as structured data on the decision, so the
        // label stays the rule's identity and history renders the two once each.
        val label = rule.displayName
        return when (action) {
            NetworkAction.PassThrough -> ScenarioDecision.PassThrough(
                origin = DecisionOrigin.ENDPOINT_RULE,
                scenarioLabel = label,
                ruleId = rule.id,
                source = source,
                sequence = sequence,
            )

            is NetworkAction.Delay -> if (action.delayMillis == 0L) {
                ScenarioDecision.PassThrough(
                    origin = DecisionOrigin.ENDPOINT_RULE,
                    scenarioLabel = label,
                    ruleId = rule.id,
                    source = source,
                    sequence = sequence,
                )
            } else {
                ScenarioDecision.Delay(
                    delayMillis = action.delayMillis,
                    origin = DecisionOrigin.ENDPOINT_RULE,
                    scenarioLabel = label,
                    ruleId = rule.id,
                    source = source,
                    sequence = sequence,
                )
            }

            is NetworkAction.ReturnResponse -> ScenarioDecision.RespondWith(
                statusCode = action.statusCode,
                body = action.body ?: defaultBodyFor(action.statusCode),
                contentType = action.contentType,
                delayMillis = action.delayMillis,
                origin = DecisionOrigin.ENDPOINT_RULE,
                scenarioLabel = label,
                ruleId = rule.id,
                headers = action.headers,
                source = source,
                sequence = sequence,
            )

            is NetworkAction.Malformed -> ScenarioDecision.RespondWith(
                statusCode = action.statusCode,
                body = action.type.body,
                contentType = action.type.contentType,
                delayMillis = action.delayMillis,
                origin = DecisionOrigin.ENDPOINT_RULE,
                scenarioLabel = "$label · ${action.type.label}",
                ruleId = rule.id,
                malformed = true,
                source = source,
                sequence = sequence,
            )

            NetworkAction.Offline -> ScenarioDecision.FailOffline(
                origin = DecisionOrigin.ENDPOINT_RULE,
                scenarioLabel = label,
                ruleId = rule.id,
                source = source,
                sequence = sequence,
            )

            is NetworkAction.Timeout -> ScenarioDecision.FailTimeout(
                type = action.type,
                origin = DecisionOrigin.ENDPOINT_RULE,
                scenarioLabel = label,
                ruleId = rule.id,
                source = source,
                sequence = sequence,
            )

            // Guarded by SequenceStep's own constructor and by the validator; a
            // nested sequence would be a NetKit bug, and degrading to
            // pass-through is the only safe thing to do on the request path.
            is NetworkAction.Sequence -> ScenarioDecision.PassThrough(
                origin = DecisionOrigin.ENDPOINT_RULE,
                scenarioLabel = label,
                ruleId = rule.id,
                source = source,
                sequence = sequence,
            )
        }
    }

    private fun fromGlobal(
        global: GlobalNetworkConfig,
        source: RuleSource,
        scenarioName: String?,
    ): ScenarioDecision {
        val prefix = scenarioName?.let { "$it · " }.orEmpty()
        return when (val mode = global.mode) {
            GlobalNetworkMode.Offline -> ScenarioDecision.FailOffline(
                origin = DecisionOrigin.GLOBAL,
                scenarioLabel = "${prefix}Global offline",
                source = source,
            )

            is GlobalNetworkMode.Timeout -> ScenarioDecision.FailTimeout(
                type = mode.type,
                origin = DecisionOrigin.GLOBAL,
                scenarioLabel = "${prefix}Global ${mode.label.lowercase()}",
                source = source,
            )

            GlobalNetworkMode.Normal -> if (global.latencyMillis > 0) {
                ScenarioDecision.Delay(
                    delayMillis = global.latencyMillis,
                    origin = DecisionOrigin.GLOBAL,
                    scenarioLabel = "${prefix}Global latency ${global.latencyMillis}ms",
                    source = source,
                )
            } else {
                PASS_THROUGH
            }
        }
    }

    /**
     * `204` and `304` must not carry a body, and inventing an error envelope for
     * a `200` would be misleading; everything else gets NetKit's marker payload
     * so a developer reading a response can tell where it came from.
     */
    private fun defaultBodyFor(statusCode: Int): String = when {
        statusCode == 204 || statusCode == 304 -> ""
        statusCode in 200..299 -> NetKitDefaults.DEFAULT_SUCCESS_BODY
        else -> NetKitDefaults.DEFAULT_ERROR_BODY
    }

    /** Shared instance: the overwhelmingly common decision should not allocate. */
    private val PASS_THROUGH = ScenarioDecision.PassThrough()
}
