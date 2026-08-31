package io.devkit.netkit.engine

import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.NetworkScenario
import io.devkit.netkit.scenario.RequestTarget

/**
 * Turns a [NetworkScenario] plus a [RequestTarget] into a [ScenarioDecision].
 *
 * Stateless and pure — the same inputs always produce the same decision, which
 * is what makes the whole matching layer unit-testable without OkHttp, Android
 * or coroutines.
 *
 * ### Precedence
 *
 * 1. NetKit disabled → [ScenarioDecision.PassThrough].
 * 2. First **enabled** rule whose method and matcher claim the request wins.
 *    Its action fully **replaces** global behaviour — global latency is *not*
 *    added on top. A rule with [NetworkAction.PassThrough] is therefore an
 *    allow-list entry: that endpoint keeps reaching the backend even while the
 *    rest of the app is offline.
 * 3. Otherwise the global configuration applies: offline and timeout fail the
 *    request; global latency delays it.
 * 4. Otherwise pass through.
 *
 * Rules are evaluated in list order, so the debug UI's ordering is the
 * precedence order and a QA engineer can predict which override wins.
 */
object ScenarioEvaluator {

    /** Evaluates [target] against [scenario]. Never throws. */
    fun evaluate(scenario: NetworkScenario, target: RequestTarget): ScenarioDecision {
        if (!scenario.enabled) return PASS_THROUGH
        val rule = scenario.findRule(target)
        if (rule != null) return fromRule(rule)
        return fromGlobal(scenario.global)
    }

    private fun fromRule(rule: EndpointRule): ScenarioDecision {
        val label = rule.displayName
        return when (val action = rule.action) {
            NetworkAction.PassThrough -> ScenarioDecision.PassThrough(
                origin = DecisionOrigin.ENDPOINT_RULE,
                scenarioLabel = label,
                ruleId = rule.id,
            )

            is NetworkAction.Delay -> if (action.delayMillis == 0L) {
                ScenarioDecision.PassThrough(DecisionOrigin.ENDPOINT_RULE, label, rule.id)
            } else {
                ScenarioDecision.Delay(action.delayMillis, DecisionOrigin.ENDPOINT_RULE, label, rule.id)
            }

            is NetworkAction.HttpError -> ScenarioDecision.RespondWith(
                statusCode = action.statusCode,
                body = action.body ?: NetKitDefaults.DEFAULT_ERROR_BODY,
                contentType = action.contentType,
                delayMillis = action.delayMillis,
                origin = DecisionOrigin.ENDPOINT_RULE,
                scenarioLabel = label,
                ruleId = rule.id,
            )

            NetworkAction.Offline ->
                ScenarioDecision.FailOffline(DecisionOrigin.ENDPOINT_RULE, label, rule.id)

            is NetworkAction.Timeout ->
                ScenarioDecision.FailTimeout(action.type, DecisionOrigin.ENDPOINT_RULE, label, rule.id)
        }
    }

    private fun fromGlobal(global: GlobalNetworkConfig): ScenarioDecision = when (val mode = global.mode) {
        GlobalNetworkMode.Offline ->
            ScenarioDecision.FailOffline(DecisionOrigin.GLOBAL, GLOBAL_OFFLINE_LABEL)

        is GlobalNetworkMode.Timeout ->
            ScenarioDecision.FailTimeout(mode.type, DecisionOrigin.GLOBAL, "Global ${mode.label.lowercase()}")

        GlobalNetworkMode.Normal -> if (global.latencyMillis > 0) {
            ScenarioDecision.Delay(
                delayMillis = global.latencyMillis,
                origin = DecisionOrigin.GLOBAL,
                scenarioLabel = "Global latency ${global.latencyMillis}ms",
            )
        } else {
            PASS_THROUGH
        }
    }

    private const val GLOBAL_OFFLINE_LABEL = "Global offline"

    /** Shared instance: the overwhelmingly common decision should not allocate. */
    private val PASS_THROUGH = ScenarioDecision.PassThrough()
}
