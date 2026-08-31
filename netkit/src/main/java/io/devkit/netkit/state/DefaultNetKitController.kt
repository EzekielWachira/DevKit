package io.devkit.netkit.state

import io.devkit.netkit.history.NetworkHistoryStore
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.NetworkScenario
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The controller NetKit installs.
 *
 * State lives in a single [MutableStateFlow] of immutable snapshots and every
 * mutation goes through `update`, which is a compare-and-set loop. That gives
 * lock-free atomic updates from any thread and, just as importantly, means the
 * interceptor always reads one coherent scenario rather than a half-applied
 * change.
 *
 * @param historyStore the store this controller clears and exposes.
 * @param initialScenario the state [reset] does *not* restore — reset always
 *   returns to [NetworkScenario.Default], so "reset" means the same thing to
 *   every QA engineer regardless of how the app was configured.
 */
class DefaultNetKitController(
    private val historyStore: NetworkHistoryStore,
    initialScenario: NetworkScenario = NetworkScenario.Default,
) : NetKitController {

    private val _state = MutableStateFlow(NetKitState(initialScenario))

    override val state: StateFlow<NetKitState> = _state.asStateFlow()

    override val history: StateFlow<List<NetworkRecord>> = historyStore.records

    /** The snapshot the engine reads. Cheap and allocation-free. */
    val scenario: NetworkScenario get() = _state.value.scenario

    override fun setEnabled(enabled: Boolean) = updateScenario { it.copy(enabled = enabled) }

    override fun setGlobalMode(mode: GlobalNetworkMode) = updateScenario {
        it.copy(global = it.global.copy(mode = mode))
    }

    override fun setGlobalLatency(milliseconds: Long) {
        val safe = milliseconds.coerceAtLeast(0)
        updateScenario { it.copy(global = it.global.copy(latencyMillis = safe)) }
    }

    override fun addRule(rule: EndpointRule): String {
        updateScenario { current ->
            // Adding a rule whose id already exists replaces it, so a caller that
            // re-applies the same QA setup does not accumulate duplicates.
            val existing = current.rules.indexOfFirst { it.id == rule.id }
            if (existing >= 0) {
                current.copy(rules = current.rules.toMutableList().apply { set(existing, rule) })
            } else {
                current.copy(rules = current.rules + rule)
            }
        }
        return rule.id
    }

    override fun updateRule(rule: EndpointRule) = updateScenario { current ->
        val index = current.rules.indexOfFirst { it.id == rule.id }
        if (index < 0) {
            current
        } else {
            current.copy(rules = current.rules.toMutableList().apply { set(index, rule) })
        }
    }

    override fun removeRule(id: String) = updateScenario { current ->
        val remaining = current.rules.filterNot { it.id == id }
        if (remaining.size == current.rules.size) current else current.copy(rules = remaining)
    }

    override fun setRuleEnabled(id: String, enabled: Boolean) = updateScenario { current ->
        val index = current.rules.indexOfFirst { it.id == id }
        if (index < 0 || current.rules[index].enabled == enabled) {
            current
        } else {
            current.copy(
                rules = current.rules.toMutableList().apply {
                    set(index, this[index].copy(enabled = enabled))
                },
            )
        }
    }

    override fun applyScenario(scenario: NetworkScenario) = updateScenario { scenario }

    override fun reset() = updateScenario { NetworkScenario.Default }

    override fun clearHistory() = historyStore.clear()

    private inline fun updateScenario(crossinline transform: (NetworkScenario) -> NetworkScenario) {
        _state.update { current ->
            val next = transform(current.scenario)
            if (next == current.scenario) current else NetKitState(next)
        }
    }
}
