package io.devkit.netkit.scenario

import io.devkit.netkit.scenario.condition.ConditionContext
import io.devkit.netkit.scenario.condition.RuleCondition
import java.util.UUID

/**
 * A runtime override scoped to one endpoint.
 *
 * Rules are immutable value objects. The debug UI and the controller replace
 * them wholesale rather than mutating them, which is what makes the scenario
 * snapshot safe to read from OkHttp's background threads.
 *
 * ### How a rule is evaluated
 *
 * NetKit 0.3 widened a rule from `matcher + action` to four stages, checked in
 * this order and abandoned at the first failure:
 *
 * ```text
 * 1. enabled?       a disabled rule is skipped without being counted
 * 2. method + path  [matcher] and [method] must both claim the request
 * 3. conditions     every entry in [conditions] must hold (AND)
 * 4. probability    a seeded draw must come in under [probability]
 *    → action
 * ```
 *
 * A rule that fails any stage does **not** end evaluation: the engine continues
 * to the next rule. That is what makes layering work — a 30% "fail" rule above a
 * "delay" rule gives you a 30% failure and a 70% delay, rather than a 30% failure
 * and 70% nothing.
 *
 * The stages are deliberately ordered cheapest-first, and the two stages 0.3 added
 * cost nothing at all when unused: [conditions] is usually empty and
 * [probability] short-circuits at `1.0` without touching the random stream.
 *
 * @param id stable identity used by [io.devkit.netkit.state.NetKitController]
 *   update/remove calls. Generated when omitted.
 * @param name optional human label, surfaced in history as the responsible scenario.
 * @param enabled disabled rules are skipped by the engine but kept in the list.
 * @param method the verb this rule is scoped to; [HttpMethod.ANY] matches all.
 * @param matcher decides which requests this rule claims.
 * @param conditions extra requirements, all of which must hold. Empty means none.
 * @param probability the chance this rule acts once everything else has passed.
 *   [Probability.ALWAYS] — the default — makes the rule behave exactly as an 0.2
 *   rule did.
 * @param action what happens to a claimed request.
 */
data class EndpointRule(
    val id: String = nextId(),
    val name: String? = null,
    val enabled: Boolean = true,
    val method: HttpMethod = HttpMethod.ANY,
    val matcher: EndpointMatcher,
    val conditions: List<RuleCondition> = emptyList(),
    val probability: Probability = Probability.ALWAYS,
    val action: NetworkAction,
) {
    init {
        require(id.isNotBlank()) { "NetKit rule id cannot be blank" }
    }

    /** `GET /api/v1/bookings` — the identity shown in list rows. */
    val displayTarget: String get() = "${method.label} ${matcher.label}"

    /** Name if the author gave one, otherwise the target. */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: displayTarget

    /** True when this rule uses anything beyond 0.2's method-and-path matching. */
    val isConditional: Boolean get() = conditions.isNotEmpty() || !probability.isAlways

    /** True when this rule can decide differently on two identical requests. */
    val isStochastic: Boolean
        get() = !probability.isAlways ||
            action is NetworkAction.Weighted ||
            (action as? NetworkAction.RandomDelay)?.latency?.isFixed == false

    /** True when evaluating this rule needs request headers captured. */
    val needsHeaders: Boolean get() = conditions.any { it.needsHeaders }

    /** True when evaluating this rule needs the request body peeked. */
    val needsBody: Boolean get() = conditions.any { it.needsBody }

    /** `page = 2 · first request only · 50%` — the subtitle in a rule row. */
    val conditionSummary: String?
        get() {
            if (!isConditional) return null
            return buildList {
                conditions.forEach { add(it.label) }
                if (!probability.isAlways) add(probability.percentLabel)
            }.joinToString(" · ")
        }

    /** True when this rule claims [target]; ignores [enabled] and conditions on purpose. */
    fun matches(target: RequestTarget): Boolean =
        method.matches(target.method) && matcher.matches(target)

    /**
     * True when every condition holds for [context].
     *
     * A condition that throws is treated as not matching. Conditions are supposed
     * to be total, but one of them is consumer-supplied by design, and a debug
     * tool must not turn a badly written predicate into a failed request on an
     * OkHttp thread.
     */
    fun conditionsHold(context: ConditionContext): Boolean {
        for (index in conditions.indices) {
            val holds = try {
                conditions[index].matches(context)
            } catch (error: RuntimeException) {
                false
            }
            if (!holds) return false
        }
        return true
    }

    /** The first condition that does not hold, for the evaluation diagnostics. */
    fun firstFailingCondition(context: ConditionContext): RuleCondition? {
        for (index in conditions.indices) {
            val condition = conditions[index]
            val holds = try {
                condition.matches(context)
            } catch (error: RuntimeException) {
                false
            }
            if (!holds) return condition
        }
        return null
    }

    companion object {

        /**
         * A globally unique rule id.
         *
         * Deliberately a UUID rather than a counter. Rule ids are **persisted**
         * inside saved scenarios and are the key sequence cursors and rule hit
         * counters are stored under, so a counter that restarts at 1 with each
         * process would let a rule created today collide with one saved
         * yesterday — and two rules sharing a cursor means a `500 → 500 → 200`
         * sequence firing on the wrong request.
         */
        fun nextId(): String = "netkit-rule-${UUID.randomUUID()}"

        /**
         * Convenience factory for the common "one exact path" rule.
         *
         * ```kotlin
         * EndpointRule.forPath("/api/v1/bookings", HttpMethod.GET, NetworkAction.ReturnResponse(500))
         * ```
         */
        fun forPath(
            path: String,
            method: HttpMethod = HttpMethod.ANY,
            action: NetworkAction,
            name: String? = null,
            enabled: Boolean = true,
            conditions: List<RuleCondition> = emptyList(),
            probability: Probability = Probability.ALWAYS,
        ): EndpointRule = EndpointRule(
            name = name,
            enabled = enabled,
            method = method,
            matcher = EndpointMatcher.ExactPath(path),
            conditions = conditions,
            probability = probability,
            action = action,
        )
    }
}
