package io.devkit.netkit.state

import io.devkit.netkit.history.NetworkHistoryStore
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.masking.DefaultSensitiveDataMasker
import io.devkit.netkit.masking.SensitiveDataMasker
import io.devkit.netkit.replay.ReplaySnapshotStore
import io.devkit.netkit.replay.RequestReplayer
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.chaos.ChaosConfig
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioId
import io.devkit.netkit.scenario.model.ScenarioMetadata
import io.devkit.netkit.scenario.model.ScenarioPack
import io.devkit.netkit.scenario.model.ScenarioPackContents
import io.devkit.netkit.scenario.model.ScenarioPackId
import io.devkit.netkit.scenario.persistence.ScenarioPersistenceError
import io.devkit.netkit.scenario.persistence.ScenarioWriteResult
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import io.devkit.netkit.scenario.runtime.ImportCollisionPolicy
import io.devkit.netkit.scenario.runtime.ScenarioImportOutcome
import io.devkit.netkit.scenario.runtime.ScenarioManager
import io.devkit.netkit.scenario.runtime.SequenceProgress
import io.devkit.netkit.scenario.run.ChaosStatistics
import io.devkit.netkit.scenario.run.ExecutionEvent
import io.devkit.netkit.scenario.run.RuleStatistics
import io.devkit.netkit.scenario.run.RunStartReason
import io.devkit.netkit.scenario.run.ScenarioRun
import io.devkit.netkit.scenario.serialization.ReproductionExport
import io.devkit.netkit.scenario.serialization.ReproductionExporter
import io.devkit.netkit.scenario.serialization.ScenarioExport
import io.devkit.netkit.scenario.serialization.ScenarioImportResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The controller NetKit installs.
 *
 * Runtime state lives in a single [MutableStateFlow] of immutable snapshots and
 * every mutation goes through `update`, which is a compare-and-set loop. That
 * gives lock-free atomic updates from any thread and, just as importantly, means
 * the interceptor always reads one coherent configuration rather than a
 * half-applied change.
 *
 * Saved scenarios are **not** stored here. The controller subscribes to
 * [ScenarioManager.activeSnapshot] and folds the result into the configuration,
 * which is the only coupling between the two: a scenario change becomes one more
 * atomic snapshot swap, indistinguishable to the engine from a temporary rule
 * being toggled.
 *
 * @param historyStore the store this controller clears and exposes.
 * @param manager owns saved scenarios and sequence cursors.
 * @param replayer replays recorded requests.
 * @param replaySnapshots cleared alongside history, so credentials do not
 *   outlive the records that referenced them.
 * @param scope where the active-scenario subscription runs.
 * @param masker redacts credential-bearing query parameters out of an exported
 *   reproduction trace. Defaults to NetKit's own; pass the instance from
 *   `NetKitConfig` so a custom masker applies here too.
 */
class DefaultNetKitController(
    private val historyStore: NetworkHistoryStore,
    private val manager: ScenarioManager,
    override val replayer: RequestReplayer,
    private val replaySnapshots: ReplaySnapshotStore?,
    scope: CoroutineScope,
    initialConfiguration: ActiveNetworkConfiguration = ActiveNetworkConfiguration.Default,
    private val masker: SensitiveDataMasker = DefaultSensitiveDataMasker(),
) : NetKitController {

    private val _state = MutableStateFlow(NetKitState(initialConfiguration))

    override val state: StateFlow<NetKitState> = _state.asStateFlow()

    override val history: StateFlow<List<NetworkRecord>> = historyStore.records

    override val scenarios: ScenarioController = ManagerBackedScenarioController()

    override val runs: RunController = ManagerBackedRunController()

    /**
     * Writes the reproduction files this controller's [runs] produces.
     *
     * Constructed with the same masker history uses, so an organisation that
     * added its own sensitive parameter names has them respected in a trace too.
     */
    private val reproductionExporter = ReproductionExporter(masker)

    /** The snapshot the engine reads. Cheap and allocation-free. */
    val configuration: ActiveNetworkConfiguration get() = _state.value.configuration

    init {
        // The one place saved scenarios reach the runtime. Everything else the
        // manager publishes is for the UI.
        scope.launch {
            manager.activeSnapshot.collect { snapshot ->
                updateConfiguration { it.copy(scenario = snapshot) }
                // Deactivating a scenario ends the run the manager started. When
                // the console's own chaos is still on, the run has to pass back to
                // chaos rather than simply stopping — otherwise chaos would keep
                // failing requests with no seed anybody could quote.
                syncChaosRun(configuration.effectiveChaos)
            }
        }
    }

    override fun setEnabled(enabled: Boolean) = updateConfiguration { it.copy(enabled = enabled) }

    override fun setGlobalMode(mode: GlobalNetworkMode) = updateConfiguration {
        it.copy(global = it.global.copy(mode = mode))
    }

    override fun setGlobalLatency(milliseconds: Long) {
        val safe = milliseconds.coerceAtLeast(0)
        updateConfiguration { it.copy(global = it.global.copy(latencyMillis = safe)) }
    }

    override fun setChaos(config: ChaosConfig) {
        val previous = configuration.effectiveChaos
        updateConfiguration { it.copy(chaos = config) }
        syncChaosRun(previous)
    }

    override fun setChaosEnabled(enabled: Boolean) {
        if (configuration.chaos.enabled == enabled) return
        setChaos(configuration.chaos.copy(enabled = enabled))
    }

    /**
     * Starts or stops a chaos-only run as the console's chaos is switched.
     *
     * Only relevant when **no scenario is active**: a scenario owns its run, and
     * `ScenarioManager` starts and stops it synchronously with activation. This
     * fills the other case — someone turns chaos on from the console with nothing
     * saved — so a seed and a timeline exist for that too, and a bug found under
     * ad-hoc chaos is just as reproducible as one found under a saved scenario.
     *
     * Changing a *running* chaos configuration also restarts, on a fresh seed:
     * the decisions a seed produced were a function of the old failure rate, so
     * continuing to quote that seed after changing the rate would be a lie.
     */
    private fun syncChaosRun(previousChaos: ChaosConfig) {
        val current = configuration
        // A scenario is active: its run is the manager's business, not ours.
        if (current.scenario?.enabled == true) return

        val chaos = current.chaos
        val running = manager.runManager.run.value
        when {
            !chaos.enabled || !current.enabled -> if (running?.scenarioId == null) {
                manager.runManager.stopRun()
            }

            running == null -> manager.runManager.startRun(
                scenarioId = null,
                scenarioName = "Chaos",
                reason = RunStartReason.CHAOS_ENABLED,
            )

            previousChaos != chaos -> manager.runManager.startRun(
                scenarioId = null,
                scenarioName = "Chaos",
                reason = RunStartReason.SEED_CHANGED,
            )
        }
    }

    override fun addRule(rule: EndpointRule): String {
        updateConfiguration { current ->
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

    override fun updateRule(rule: EndpointRule) = updateConfiguration { current ->
        val index = current.rules.indexOfFirst { it.id == rule.id }
        if (index < 0) {
            current
        } else {
            current.copy(rules = current.rules.toMutableList().apply { set(index, rule) })
        }
    }

    override fun removeRule(id: String) {
        updateConfiguration { current ->
            val remaining = current.rules.filterNot { it.id == id }
            if (remaining.size == current.rules.size) current else current.copy(rules = remaining)
        }
        // Outside the update: `MutableStateFlow.update` re-runs its transform on
        // a lost compare-and-set, and a transform with a side effect would then
        // reset the cursor more than once.
        manager.resetSequence(id)
    }

    override fun setRuleEnabled(id: String, enabled: Boolean) = updateConfiguration { current ->
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

    override fun applyConfiguration(configuration: ActiveNetworkConfiguration) =
        updateConfiguration { current ->
            // The active scenario is the manager's business, not the caller's.
            configuration.copy(scenario = current.scenario)
        }

    override fun reset() {
        updateConfiguration { current ->
            ActiveNetworkConfiguration.Default.copy(scenario = current.scenario)
        }
        manager.resetAllSequences()
    }

    override fun resetEverything() {
        reset()
        replaySnapshots?.clear()
        // Deactivation is suspending; the manager's own scope owns the write.
        manager.requestDeactivate()
    }

    override fun resetAllSequences() = manager.resetAllSequences()

    override fun clearHistory() {
        historyStore.clear()
        // Replay snapshots exist only to serve history rows; keeping them once
        // the rows are gone would keep credentials alive for nothing.
        replaySnapshots?.clear()
    }

    private inline fun updateConfiguration(
        crossinline transform: (ActiveNetworkConfiguration) -> ActiveNetworkConfiguration,
    ) {
        _state.update { current ->
            val next = transform(current.configuration)
            if (next == current.configuration) current else NetKitState(next)
        }
    }

    /**
     * Adapts [ScenarioManager] to the [ScenarioController] the UI depends on.
     *
     * A delegating inner class rather than making the manager implement the
     * interface directly, for two reasons: the manager holds runtime-only members
     * (the execution state, the engine snapshot) that no UI should reach, and
     * `saveCurrentSetupAsScenario` needs the controller's own temporary
     * configuration, which the manager deliberately does not know about.
     */
    private inner class ManagerBackedScenarioController : ScenarioController {

        override val scenarios: StateFlow<List<NetworkScenario>> get() = manager.scenarios
        override val packs: StateFlow<List<ScenarioPack>> get() = manager.packs
        override val packContents: StateFlow<List<ScenarioPackContents>>
            get() = manager.packContents
        override val looseScenarios: StateFlow<List<NetworkScenario>> get() = manager.looseScenarios
        override val activeScenarioId: StateFlow<ScenarioId?> get() = manager.activeScenarioId
        override val activeScenario: StateFlow<NetworkScenario?> get() = manager.activeScenario
        override val isLoaded: StateFlow<Boolean> get() = manager.isLoaded
        override val lastError: StateFlow<ScenarioPersistenceError?> get() = manager.lastError

        override val sequenceProgress: StateFlow<Map<String, SequenceProgress>>
            get() = manager.executionState.progress

        override suspend fun activate(id: ScenarioId): Boolean = manager.activate(id)

        override suspend fun deactivate() = manager.deactivate()

        override suspend fun toggleActive(id: ScenarioId): Boolean = manager.toggleActive(id)

        override suspend fun reproduce(id: ScenarioId, seed: Long): Boolean =
            manager.reproduce(id, seed)

        override suspend fun setScenarioEnabled(id: ScenarioId, enabled: Boolean) {
            manager.setScenarioEnabled(id, enabled)
        }

        override suspend fun save(scenario: NetworkScenario): ScenarioWriteResult =
            manager.save(scenario)

        override suspend fun delete(id: ScenarioId) = manager.delete(id)

        override suspend fun duplicate(id: ScenarioId, name: String?): ScenarioWriteResult =
            manager.duplicate(id, name)

        /**
         * Freezes the console's current temporary setup into a saved scenario.
         *
         * The rules are copied with **fresh ids** so the saved scenario never
         * shares a sequence cursor with the temporary rules it came from, and the
         * temporary layer is cleared afterwards by default — leaving both active
         * would double every override the moment the scenario is activated.
         */
        override suspend fun saveCurrentSetupAsScenario(
            name: String,
            description: String?,
            packId: ScenarioPackId?,
            clearTemporary: Boolean,
        ): ScenarioWriteResult {
            val scenario = configuration.toScenario(
                name = name,
                description = description,
                packId = packId,
                nowMillis = System.currentTimeMillis(),
            )
            val result = manager.save(scenario)
            if (result is ScenarioWriteResult.Success && clearTemporary) {
                updateConfiguration { current ->
                    current.copy(
                        global = GlobalNetworkConfig(),
                        rules = emptyList(),
                    )
                }
                manager.resetAllSequences()
            }
            return result
        }

        override suspend fun savePack(pack: ScenarioPack) = manager.savePack(pack)

        override suspend fun deletePack(id: ScenarioPackId) = manager.deletePack(id)

        override suspend fun setPack(scenarioId: ScenarioId, packId: ScenarioPackId?) =
            manager.setPack(scenarioId, packId)

        override fun export(id: ScenarioId): ScenarioExport? = manager.export(id)

        override fun exportPack(id: ScenarioPackId): ScenarioExport? = manager.exportPack(id)

        override fun preview(content: String): ScenarioImportResult = manager.preview(content)

        override suspend fun commitImport(
            result: ScenarioImportResult,
            policy: ImportCollisionPolicy,
        ): ScenarioImportOutcome = manager.commitImport(result, policy)

        override fun resetSequence(ruleId: String) = manager.resetSequence(ruleId)

        override fun resetAllSequences() = manager.resetAllSequences()
    }

    /**
     * Adapts [ScenarioRunManager] to the [RunController] the UI depends on.
     *
     * A thin delegating layer for the same reason the scenario one is: the run
     * manager holds the atomics the engine hammers on every request, and no UI
     * should be able to reach those. What the UI gets is the published state and
     * five verbs.
     */
    private inner class ManagerBackedRunController : RunController {

        override val current: StateFlow<ScenarioRun?> get() = manager.runManager.run

        override val timeline: StateFlow<List<ExecutionEvent>>
            get() = manager.runManager.timeline.events

        override val ruleStatistics: StateFlow<Map<String, RuleStatistics>>
            get() = manager.runManager.ruleStatistics

        override val chaosStatistics: StateFlow<ChaosStatistics>
            get() = manager.runManager.chaosStatistics

        override val seed: Long? get() = current.value?.seed

        override fun restartWithSameSeed(): ScenarioRun? = manager.restartRun()

        override fun restartWithNewSeed(): ScenarioRun? = manager.restartRunWithNewSeed()

        override fun restartWithSeed(seed: Long): ScenarioRun? = manager.restartRunWithSeed(seed)

        override fun resetRuntimeState() = manager.runManager.resetRuntimeState()

        override fun reproductionSummary(): String? {
            val run = current.value ?: return null
            return reproductionExporter.summary(run, manager.activeScenario.value)
        }

        override fun traceSummary(): String? {
            val run = current.value ?: return null
            return reproductionExporter.trace(run, timeline.value)
        }

        override fun exportReproduction(includeTrace: Boolean): ReproductionExport? {
            val run = current.value ?: return null
            return reproductionExporter.export(
                run = run,
                scenario = manager.activeScenario.value,
                events = if (includeTrace) timeline.value else emptyList(),
                includeTrace = includeTrace,
            )
        }

        override fun setTimelineEnabled(enabled: Boolean) {
            manager.runManager.timeline.enabled = enabled
        }

        override val isTimelineEnabled: Boolean get() = manager.runManager.timeline.enabled
    }
}

/**
 * Builds a scenario out of the console's current temporary setup.
 *
 * Extracted so both the controller and tests can produce the same thing: the
 * rules keep fresh ids, because a saved copy must not share a sequence cursor
 * with the temporary rule it was made from.
 */
internal fun ActiveNetworkConfiguration.toScenario(
    name: String,
    description: String?,
    packId: ScenarioPackId?,
    nowMillis: Long,
): NetworkScenario = NetworkScenario(
    id = ScenarioId.random(),
    name = name,
    description = description,
    globalConfig = global.takeIf { !it.isNormal },
    rules = rules.map { it.copy(id = EndpointRule.nextId()) },
    metadata = ScenarioMetadata(
        createdAtMillis = nowMillis,
        updatedAtMillis = nowMillis,
        packId = packId,
    ),
)
