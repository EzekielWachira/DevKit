package io.devkit.netkit.state

import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.replay.RequestReplayer
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import kotlinx.coroutines.flow.StateFlow

/**
 * The single entry point for changing NetKit at runtime.
 *
 * Everything that can drive NetKit goes through this interface:
 *
 * ```text
 * Compose UI ───────┐
 *                   │
 * Android Studio ───┼──→ NetKitController ──→ configuration ──→ engine ──→ interceptor
 *                   │           │
 * Automation API ───┘           ├──→ ScenarioController  (saved scenarios, packs)
 *                               └──→ RequestReplayer     (replay from history)
 * ```
 *
 * Deliberately free of Compose, Android and OkHttp types so a future IDE bridge
 * or instrumentation API can issue the same commands remotely. The UI never
 * touches the interceptor, and the interceptor never knows a UI exists.
 *
 * The methods on this interface are the **temporary** layer: the global switch,
 * global connectivity and ad-hoc endpoint overrides, none of which are saved.
 * Saved scenarios live behind [scenarios]; the two compose according to the
 * precedence table on [ActiveNetworkConfiguration].
 *
 * **Thread safety.** Implementations must accept calls from any thread and apply
 * them atomically; the debug UI mutates state on the main thread while OkHttp
 * reads it from its dispatcher pool.
 */
interface NetKitController {

    /** The current runtime state. Collect it to drive a UI. */
    val state: StateFlow<NetKitState>

    /** Captured requests, newest first. */
    val history: StateFlow<List<NetworkRecord>>

    /** Saved scenarios, packs, activation, import and export. */
    val scenarios: ScenarioController

    /** Replays a recorded request. */
    val replayer: RequestReplayer

    // ---- master switch ----------------------------------------------------

    /** Turns the whole toolkit on or off without discarding the configuration. */
    fun setEnabled(enabled: Boolean)

    /** Shorthand for `setEnabled(true)`. */
    fun enable() = setEnabled(true)

    /** Shorthand for `setEnabled(false)`. */
    fun disable() = setEnabled(false)

    // ---- global network ---------------------------------------------------

    /**
     * Replaces the console's own global connectivity mode.
     *
     * An active scenario that sets its own global configuration takes
     * precedence; the console then shows which layer is in force via
     * [NetKitState.effectiveGlobal].
     */
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

    // ---- temporary endpoint overrides --------------------------------------

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

    /**
     * Replaces the entire temporary configuration in one atomic update.
     *
     * The active scenario is preserved: this is the temporary layer only.
     */
    fun applyConfiguration(configuration: ActiveNetworkConfiguration)

    // ---- reset -------------------------------------------------------------

    /**
     * Restores normal networking: NetKit enabled, global mode normal, latency
     * `0` and **all temporary endpoint overrides removed**.
     *
     * The active scenario and every saved scenario are untouched — use
     * [ScenarioController.deactivate] for the former. Response-sequence progress
     * is reset, because a half-run sequence after a reset is exactly the kind of
     * leftover state that makes a bug look intermittent. History is deliberately
     * kept; use [clearHistory] for that.
     */
    fun reset()

    /**
     * Everything [reset] does, plus deactivating the active scenario and
     * clearing replay snapshots.
     *
     * **Saved scenarios and history are kept.** A reset button that silently
     * deleted a QA engineer's saved reproductions would be unforgivable.
     */
    fun resetEverything()

    /** Restarts every response sequence without changing anything else. */
    fun resetAllSequences()

    /** Drops every history record and every replay snapshot. */
    fun clearHistory()
}
