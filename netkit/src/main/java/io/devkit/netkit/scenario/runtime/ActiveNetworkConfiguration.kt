package io.devkit.netkit.scenario.runtime

import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.RequestTarget
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
) {
    /** The attribution stamped on any decision this scenario produces. */
    val source: RuleSource.Scenario = RuleSource.Scenario(id, name)

    /** True when this scenario cannot change any request. */
    val isIdle: Boolean
        get() = !enabled ||
            (rules.none(EndpointRule::enabled) && (global == null || global.isNormal))

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
        )
    }
}

/**
 * Everything the scenario engine reads for one request.
 *
 * This is NetKit 0.1's `NetworkScenario` grown a layer: the console's own
 * temporary settings, plus the active saved scenario flattened into
 * [ActiveScenarioSnapshot]. It is rebuilt — never mutated — so a request in
 * flight always evaluates against one coherent picture even while the debug UI
 * is being used on another thread.
 *
 * ### Precedence
 *
 * ```text
 * 1. NetKit disabled                → pass through
 * 2. temporary endpoint override    → wins
 * 3. active scenario endpoint rule  → next
 * 4. active scenario global config  → next, when the scenario sets one
 * 5. temporary global configuration → next
 * 6. otherwise                      → pass through
 * ```
 *
 * A scenario that leaves [ActiveScenarioSnapshot.global] `null` does not touch
 * the global layer at all, so the console's own offline switch still works while
 * a rules-only scenario is active. A scenario that sets it to a normal
 * configuration deliberately *pins* the network to normal.
 *
 * @param enabled master switch. When false NetKit passes every request through.
 * @param global the console's own global behaviour — a temporary setting.
 * @param rules the console's own endpoint overrides — temporary settings.
 * @param scenario the active saved scenario, or `null` when none is active.
 */
data class ActiveNetworkConfiguration(
    val enabled: Boolean = true,
    val global: GlobalNetworkConfig = GlobalNetworkConfig(),
    val rules: List<EndpointRule> = emptyList(),
    val scenario: ActiveScenarioSnapshot? = null,
) {
    /** Temporary rules currently eligible for matching. */
    val activeRules: List<EndpointRule> get() = rules.filter(EndpointRule::enabled)

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
                    (scenario == null || !scenario.enabled || scenarioIsIdle(scenario))
                )

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
            (snapshot.global == null || snapshot.global.isNormal)

    companion object {
        /** NetKit enabled, nothing simulated — the state a full reset restores. */
        val Default: ActiveNetworkConfiguration = ActiveNetworkConfiguration()
    }
}
