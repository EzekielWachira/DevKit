package io.devkit.netkit.state

import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.NetworkScenario

/**
 * The observable runtime state of one NetKit instance.
 *
 * This is what the Compose UI — and, later, an IDE bridge or automation API —
 * renders. It wraps the engine's [NetworkScenario] snapshot and adds the derived
 * values a UI needs, so no view has to recompute them during composition.
 */
data class NetKitState(
    val scenario: NetworkScenario = NetworkScenario.Default,
) {
    /** Master switch. */
    val enabled: Boolean get() = scenario.enabled

    /** Global connectivity mode and latency. */
    val global: GlobalNetworkConfig get() = scenario.global

    /** All endpoint rules, in precedence order. */
    val rules: List<EndpointRule> get() = scenario.rules

    /** How many rules are currently eligible for matching. */
    val activeRuleCount: Int get() = scenario.rules.count(EndpointRule::enabled)

    /**
     * True when NetKit is actively changing network behaviour. The UI uses this
     * for the "simulating" banner, so a QA engineer can tell at a glance whether
     * what they are seeing is the backend or NetKit.
     */
    val isSimulating: Boolean get() = !scenario.isIdle

    /** One-line summary of the global configuration, e.g. `Offline`. */
    val globalSummary: String get() = scenario.global.summary

    /**
     * What NetKit is currently doing, in a few words.
     *
     * Unlike [globalSummary] this accounts for endpoint overrides, so a launcher
     * chip never reports "Normal" while a rule is rewriting an endpoint.
     */
    val activeSummary: String
        get() = when {
            !enabled -> "Off"
            !isSimulating -> "Normal"
            global.isNormal -> "$activeRuleCount override" + if (activeRuleCount == 1) "" else "s"
            activeRuleCount == 0 -> globalSummary
            else -> "$globalSummary +$activeRuleCount"
        }

    /** Convenience for the offline toggle. */
    val isOffline: Boolean get() = scenario.global.mode == GlobalNetworkMode.Offline
}
