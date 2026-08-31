package io.devkit.netkit.state

import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.NetworkScenario
import io.devkit.netkit.scenario.TimeoutType
import kotlinx.coroutines.flow.StateFlow

/**
 * The single entry point for changing NetKit at runtime.
 *
 * Everything that can drive NetKit goes through this interface:
 *
 * ```text
 * Compose UI ───────┐
 *                   │
 * Android Studio ───┼──→ NetKitController ──→ scenario state ──→ engine ──→ interceptor
 *                   │
 * Automation API ───┘
 * ```
 *
 * Deliberately free of Compose, Android and OkHttp types so a future IDE bridge
 * or instrumentation API can issue the same commands remotely. The UI never
 * touches the interceptor, and the interceptor never knows a UI exists.
 *
 * **Thread safety.** Implementations must accept calls from any thread and apply
 * them atomically; the debug UI mutates state on the main thread while OkHttp
 * reads it from its dispatcher pool.
 *
 * **Lifetime.** NetKit 0.1 keeps state in memory only. It resets when the
 * process dies; persistent scenario packs are 0.2 work.
 */
interface NetKitController {

    /** The current runtime state. Collect it to drive a UI. */
    val state: StateFlow<NetKitState>

    /** Captured requests, newest first. */
    val history: StateFlow<List<NetworkRecord>>

    // ---- master switch ----------------------------------------------------

    /** Turns the whole toolkit on or off without discarding the configuration. */
    fun setEnabled(enabled: Boolean)

    /** Shorthand for `setEnabled(true)`. */
    fun enable() = setEnabled(true)

    /** Shorthand for `setEnabled(false)`. */
    fun disable() = setEnabled(false)

    // ---- global network ---------------------------------------------------

    /** Replaces the global connectivity mode. */
    fun setGlobalMode(mode: GlobalNetworkMode)

    /** Switches between [GlobalNetworkMode.Offline] and [GlobalNetworkMode.Normal]. */
    fun setOffline(offline: Boolean) =
        setGlobalMode(if (offline) GlobalNetworkMode.Offline else GlobalNetworkMode.Normal)

    /** Applies a global timeout, or clears it when [type] is `null`. */
    fun setTimeout(type: TimeoutType?) = setGlobalMode(
        if (type == null) GlobalNetworkMode.Normal else GlobalNetworkMode.Timeout(type),
    )

    /**
     * Sets the artificial delay applied to requests that still reach the server.
     * Negative values are rejected — implementations coerce to `0`.
     */
    fun setGlobalLatency(milliseconds: Long)

    // ---- endpoint rules ---------------------------------------------------

    /** Appends [rule] and returns its id. Rules are matched in insertion order. */
    fun addRule(rule: EndpointRule): String

    /** Replaces the rule with the same id. No-op when the id is unknown. */
    fun updateRule(rule: EndpointRule)

    /** Removes the rule with [id]. No-op when the id is unknown. */
    fun removeRule(id: String)

    /** Enables or disables one rule, keeping it in the list. */
    fun setRuleEnabled(id: String, enabled: Boolean)

    /** Shorthand for `setRuleEnabled(id, true)`. */
    fun enableRule(id: String) = setRuleEnabled(id, true)

    /** Shorthand for `setRuleEnabled(id, false)`. */
    fun disableRule(id: String) = setRuleEnabled(id, false)

    /** Replaces the entire scenario in one atomic update. */
    fun applyScenario(scenario: NetworkScenario)

    // ---- lifecycle --------------------------------------------------------

    /**
     * Restores normal networking: NetKit enabled, global mode normal, latency
     * `0` and **all endpoint rules removed**.
     *
     * Rules are removed rather than disabled because 0.1 rules are temporary
     * runtime state with no way to save them; leaving a list of disabled rules
     * behind would only be clutter. History is deliberately untouched — use
     * [clearHistory] for that.
     */
    fun reset()

    /** Drops every history record. Scenario configuration is untouched. */
    fun clearHistory()
}
