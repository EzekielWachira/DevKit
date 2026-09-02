package io.devkit.netkit.state

import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import io.devkit.netkit.scenario.runtime.ActiveScenarioSnapshot

/**
 * The observable runtime state of one NetKit instance.
 *
 * This is what the Compose UI — and, later, an IDE bridge or automation API —
 * renders. It wraps the engine's [ActiveNetworkConfiguration] snapshot and adds
 * the derived values a UI needs, so no view has to recompute them during
 * composition.
 *
 * Saved scenarios themselves are **not** here: they live behind
 * [io.devkit.netkit.state.ScenarioController], because a list of definitions is
 * not runtime state and re-emitting it on every network change would make the
 * console recompose for no reason.
 */
data class NetKitState(
    val configuration: ActiveNetworkConfiguration = ActiveNetworkConfiguration.Default,
) {
    /** Master switch. */
    val enabled: Boolean get() = configuration.enabled

    /** The console's own global connectivity mode and latency. */
    val global: GlobalNetworkConfig get() = configuration.global

    /** The console's own temporary endpoint rules, in precedence order. */
    val rules: List<EndpointRule> get() = configuration.rules

    /** The active saved scenario, flattened, or `null`. */
    val activeScenario: ActiveScenarioSnapshot? get() = configuration.scenario

    /** How many temporary rules are currently eligible for matching. */
    val activeRuleCount: Int get() = configuration.rules.count(EndpointRule::enabled)

    /** How many of the active scenario's rules are eligible for matching. */
    val activeScenarioRuleCount: Int
        get() = activeScenario?.takeIf { it.enabled }?.rules?.count(EndpointRule::enabled) ?: 0

    /** Every rule that could match, temporary first — the precedence order. */
    val allActiveRules: List<EndpointRule> get() = configuration.allActiveRules

    /**
     * True when NetKit is actively changing network behaviour. The UI uses this
     * for the "simulating" banner, so a QA engineer can tell at a glance whether
     * what they are seeing is the backend or NetKit.
     */
    val isSimulating: Boolean get() = !configuration.isIdle

    /** One-line summary of the global configuration, e.g. `Offline`. */
    val globalSummary: String get() = configuration.global.summary

    /**
     * Which layer currently controls unmatched traffic.
     *
     * The console shows this verbatim, because "why is this request slow" has a
     * different answer depending on whether the scenario or the console's own
     * switch is in charge.
     */
    val effectiveGlobal: EffectiveGlobal
        get() {
            val scenarioGlobal = activeScenario?.takeIf { it.enabled }?.global
            return if (scenarioGlobal != null) {
                EffectiveGlobal(scenarioGlobal, activeScenario?.name)
            } else {
                EffectiveGlobal(configuration.global, null)
            }
        }

    /**
     * What NetKit is currently doing, in a few words.
     *
     * Accounts for the active scenario and every override, so a launcher chip
     * never reports "Normal" while something is rewriting an endpoint.
     */
    val activeSummary: String
        get() {
            if (!enabled) return "Off"
            if (!isSimulating) return "Normal"
            val scenarioName = activeScenario?.takeIf { it.enabled && !it.isIdle }?.name
            val overrides = activeRuleCount
            val effective = effectiveGlobal.config
            return when {
                scenarioName != null && overrides > 0 -> "$scenarioName +$overrides"
                scenarioName != null -> scenarioName
                effective.isNormal -> "$overrides override" + if (overrides == 1) "" else "s"
                overrides == 0 -> effective.summary
                else -> "${effective.summary} +$overrides"
            }
        }

    /** Convenience for the offline toggle. */
    val isOffline: Boolean get() = configuration.global.mode == GlobalNetworkMode.Offline
}

/**
 * The global behaviour actually in force, and which layer set it.
 *
 * @param config the configuration that applies to unmatched requests.
 * @param scenarioName the scenario imposing it, or `null` when it is the
 *   console's own temporary setting.
 */
data class EffectiveGlobal(
    val config: GlobalNetworkConfig,
    val scenarioName: String?,
) {
    /** `Offline — from scenario "Checkout Failure"` */
    val explanation: String
        get() = if (scenarioName == null) {
            "${config.summary} — set here in the console"
        } else {
            "${config.summary} — set by the scenario \"$scenarioName\""
        }
}
