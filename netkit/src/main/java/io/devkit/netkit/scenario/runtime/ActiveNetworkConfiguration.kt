package io.devkit.netkit.scenario.runtime

import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.RequestTarget
import io.devkit.netkit.scenario.chaos.ChaosConfig
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioId

/**
 * Where a decision came from, captured at request time.
 *
 * History stores this rather than a scenario id it would have to look up later:
 * a scenario can be edited, renamed or deleted after a request ran, and an old
 * history row must keep saying what was actually responsible.
 */
sealed interface RuleSource {

    /** Short label for the history badge. */
    val label: String

    /** A runtime override created in the console and never saved. */
    data object Temporary : RuleSource {
        override val label: String get() = "Temporary"
    }

    /** A saved scenario that was active when the request ran. */
    data class Scenario(
        val scenarioId: ScenarioId,
        val scenarioName: String,
    ) : RuleSource {
        override val label: String get() = scenarioName
    }

    /** Chaos mode, which belongs to no single rule. */
    data object Chaos : RuleSource {
        override val label: String get() = "Chaos"
    }
}

/**
 * What the engine needs captured from each request before it can evaluate.
 *
 * Precomputed once per configuration change rather than derived per request, and
 * consulted by the transport binding before it does any work. A scenario with no
 * header condition never pays for building a header list; one with no body
 * condition never hands the interceptor a body source to buffer from. That keeps
 * the 0.1-shaped scenario — still the overwhelmingly common case — costing what
 * it did in 0.1.
 */
data class RequestInspection(
    val headers: Boolean = false,
    val body: Boolean = false,
) {
    /** True when nothing beyond the URL and method is needed. */
    val isMinimal: Boolean get() = !headers && !body

    companion object {
        /** Only the URL and method. */
        val Minimal: RequestInspection = RequestInspection()

        internal fun of(rules: List<EndpointRule>): RequestInspection {
            var headers = false
            var body = false
            for (index in rules.indices) {
                val rule = rules[index]
                if (!rule.enabled || rule.conditions.isEmpty()) continue
                if (rule.needsHeaders) headers = true
                if (rule.needsBody) body = true
                if (headers && body) break
            }
            return RequestInspection(headers, body)
        }
    }
}

/**
 * The active scenario, flattened into exactly what the engine needs.
 *
 * The engine never touches [NetworkScenario], the repository or a `Flow`: it
 * reads this immutable snapshot, which the manager rebuilds only when the
 * activation or the definition changes. That is what keeps a request from
 * paying for persistence.
 */
data class ActiveScenarioSnapshot(
    val id: ScenarioId,
    val name: String,
    val enabled: Boolean,
    val global: GlobalNetworkConfig?,
    val rules: List<EndpointRule>,
    val chaos: ChaosConfig? = null,
) {
    /** The attribution stamped on any decision this scenario produces. */
    val source: RuleSource.Scenario = RuleSource.Scenario(id, name)

    /** True when this scenario cannot change any request. */
    val isIdle: Boolean
        get() = !enabled ||
            (
                rules.none(EndpointRule::enabled) &&
                    (global == null || global.isNormal) &&
                    (chaos == null || chaos.isIdle)
                )

    /** True when this scenario needs a seed to be reproducible. */
    val isStochastic: Boolean
        get() = chaos?.isIdle == false || rules.any { it.enabled && it.isStochastic }

    /** The first enabled rule claiming [target], or `null`. */
    fun findRule(target: RequestTarget): EndpointRule? {
        if (!enabled) return null
        for (index in rules.indices) {
            val rule = rules[index]
            if (rule.enabled && rule.matches(target)) return rule
        }
        return null
    }

    /** Every rule that uses a response sequence, for the progress display. */
    val sequenceRules: List<EndpointRule>
        get() = rules.filter { it.enabled && it.action is NetworkAction.Sequence }

    companion object {
        /** Flattens a saved scenario into the form the engine reads. */
        fun of(scenario: NetworkScenario): ActiveScenarioSnapshot = ActiveScenarioSnapshot(
            id = scenario.id,
            name = scenario.name,
            enabled = scenario.enabled,
            global = scenario.globalConfig,
            rules = scenario.rules,
            chaos = scenario.chaos,
        )
    }
}

/**
 * Everything the scenario engine reads for one request.
 *
 * This is NetKit 0.1's `NetworkScenario` grown two layers: the console's own
 * temporary settings, the active saved scenario flattened into
 * [ActiveScenarioSnapshot], and — new in 0.3 — chaos, which can come from either.
 * It is rebuilt, never mutated, so a request in flight always evaluates against
 * one coherent picture even while the debug UI is being used on another thread.
 *
 * ### Precedence
 *
 * ```text
 * 1. NetKit disabled                → pass through
 * 2. temporary endpoint override    → wins
 * 3. active scenario endpoint rule  → next
 * 4. chaos (scenario's, else the console's)
 * 5. active scenario global config  → next, when the scenario sets one
 * 6. temporary global configuration → next
 * 7. otherwise                      → pass through
 * ```
 *
 * Chaos deliberately sits **below** explicit rules. A rule is a statement about
 * one endpoint that someone wrote down on purpose; chaos is background weather.
 * If chaos could override a rule, a scenario that says "`/checkout` returns 402"
 * would sometimes return 503 instead, and the endpoint you were trying to pin
 * down would be the one you could not.
 *
 * A scenario that leaves [ActiveScenarioSnapshot.global] `null` does not touch
 * the global layer at all, so the console's own offline switch still works while
 * a rules-only scenario is active. The same is true of chaos: a scenario with no
 * chaos of its own leaves the console's chaos in force.
 *
 * @param enabled master switch. When false NetKit passes every request through.
 * @param global the console's own global behaviour — a temporary setting.
 * @param rules the console's own endpoint overrides — temporary settings.
 * @param chaos the console's own chaos mode — a temporary setting.
 * @param scenario the active saved scenario, or `null` when none is active.
 */
data class ActiveNetworkConfiguration(
    val enabled: Boolean = true,
    val global: GlobalNetworkConfig = GlobalNetworkConfig(),
    val rules: List<EndpointRule> = emptyList(),
    val chaos: ChaosConfig = ChaosConfig.Disabled,
    val scenario: ActiveScenarioSnapshot? = null,
) {
    /** Temporary rules currently eligible for matching. */
    val activeRules: List<EndpointRule> get() = rules.filter(EndpointRule::enabled)

    /**
     * The chaos actually in force: the active scenario's when it declares one,
     * otherwise the console's own.
     */
    val effectiveChaos: ChaosConfig
        get() = scenario?.takeIf { it.enabled }?.chaos ?: chaos

    /** True when the effective chaos came from the scenario rather than the console. */
    val chaosFromScenario: Boolean
        get() = scenario?.takeIf { it.enabled }?.chaos != null

    /**
     * True when nothing configured can change any request. The engine
     * short-circuits on this, so an enabled-but-empty NetKit costs one boolean
     * per request.
     */
    val isIdle: Boolean
        get() = !enabled ||
            (
                global.isNormal &&
                    rules.none(EndpointRule::enabled) &&
                    effectiveChaos.isIdle &&
                    (scenario == null || !scenario.enabled || scenarioIsIdle(scenario))
                )

    /** True when any active layer can decide differently on identical requests. */
    val isStochastic: Boolean
        get() = enabled &&
            (
                !effectiveChaos.isIdle ||
                    rules.any { it.enabled && it.isStochastic } ||
                    scenario?.takeIf { it.enabled }?.isStochastic == true
                )

    /**
     * What the transport must capture per request. Computed lazily and once per
     * configuration, then read by the interceptor on every request.
     */
    val inspection: RequestInspection by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val temporary = RequestInspection.of(rules)
        val fromScenario = scenario?.takeIf { it.enabled }
            ?.let { RequestInspection.of(it.rules) }
            ?: RequestInspection.Minimal
        RequestInspection(
            headers = temporary.headers || fromScenario.headers,
            body = temporary.body || fromScenario.body,
        )
    }

    /** The first enabled temporary rule claiming [target], or `null`. */
    fun findTemporaryRule(target: RequestTarget): EndpointRule? {
        // Indexed loop: this runs on every request and must not allocate an iterator.
        for (index in rules.indices) {
            val rule = rules[index]
            if (rule.enabled && rule.matches(target)) return rule
        }
        return null
    }

    /** All rules that could match, temporary first, in precedence order. */
    val allActiveRules: List<EndpointRule>
        get() = activeRules + (scenario?.takeIf { it.enabled }?.rules?.filter(EndpointRule::enabled)
            ?: emptyList())

    private fun scenarioIsIdle(snapshot: ActiveScenarioSnapshot): Boolean =
        snapshot.rules.none(EndpointRule::enabled) &&
            (snapshot.global == null || snapshot.global.isNormal) &&
            (snapshot.chaos == null || snapshot.chaos.isIdle)

    companion object {
        /** NetKit enabled, nothing simulated — the state a full reset restores. */
        val Default: ActiveNetworkConfiguration = ActiveNetworkConfiguration()
    }
}
