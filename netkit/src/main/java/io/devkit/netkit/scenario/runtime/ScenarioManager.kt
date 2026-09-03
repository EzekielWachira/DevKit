package io.devkit.netkit.scenario.runtime

import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioId
import io.devkit.netkit.scenario.model.ScenarioPack
import io.devkit.netkit.scenario.model.ScenarioPackContents
import io.devkit.netkit.scenario.model.ScenarioPackId
import io.devkit.netkit.scenario.model.DefaultScenarioValidator
import io.devkit.netkit.scenario.model.ScenarioValidator
import io.devkit.netkit.scenario.model.ValidationResult
import io.devkit.netkit.scenario.model.ScenarioSource
import io.devkit.netkit.scenario.pack.BuiltInScenarioPack
import io.devkit.netkit.scenario.run.RunStartReason
import io.devkit.netkit.scenario.run.ScenarioRun
import io.devkit.netkit.scenario.run.ScenarioRunManager
import io.devkit.netkit.scenario.persistence.JsonScenarioRepository
import io.devkit.netkit.scenario.persistence.ScenarioPersistenceError
import io.devkit.netkit.scenario.persistence.ScenarioRepository
import io.devkit.netkit.scenario.persistence.ScenarioWriteResult
import io.devkit.netkit.scenario.serialization.ImportSummary
import io.devkit.netkit.scenario.serialization.ScenarioExport
import io.devkit.netkit.scenario.serialization.ScenarioImportResult
import io.devkit.netkit.scenario.serialization.ScenarioSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How an imported scenario whose id already exists should be handled. */
enum class ImportCollisionPolicy {
    /**
     * Give the incoming scenario a fresh id and a `(Imported)` suffix, keeping
     * both. The default: an import must never destroy work already on the
     * device.
     */
    IMPORT_AS_COPY,

    /** Overwrite the existing scenario, keeping its id. */
    REPLACE_EXISTING,
}

/** What happened when a validated import was saved. */
sealed interface ScenarioImportOutcome {

    data class Imported(
        val scenarios: List<NetworkScenario>,
        val pack: ScenarioPack?,
        val renamed: List<String> = emptyList(),
        val replaced: Int = 0,
    ) : ScenarioImportOutcome {
        val summary: String
            get() = buildString {
                append(scenarios.size)
                append(if (scenarios.size == 1) " scenario" else " scenarios")
                pack?.let { append(" into '${it.name}'") }
                if (replaced > 0) append(" · $replaced replaced")
                if (renamed.isNotEmpty()) append(" · ${renamed.size} renamed")
            }
    }

    /**
     * A reproduction was imported: the scenario was saved and the seed it was
     * recorded with is ready to activate with.
     *
     * Kept distinct from [Imported] so the console can offer the one action that
     * makes a reproduction worth importing — "activate it with seed 843921773" —
     * rather than dropping the seed on the floor and leaving a developer to
     * retype it.
     */
    data class ImportedReproduction(
        val scenario: NetworkScenario,
        val seed: Long,
        val runId: String?,
    ) : ScenarioImportOutcome {
        val summary: String get() = "\"${scenario.name}\" with seed $seed"
    }

    data class Rejected(val result: ScenarioImportResult) : ScenarioImportOutcome {
        val reason: String get() = result.failureReason ?: "The file could not be imported."
    }

    data class Failed(val error: ScenarioPersistenceError) : ScenarioImportOutcome
}

/**
 * Owns saved scenarios: what exists, what is active, and how far each response
 * sequence has run.
 *
 * The manager is the piece that keeps the controller from becoming a God class.
 * It sits between three collaborators that never talk to each other directly:
 *
 * ```text
 * ScenarioRepository ─┐
 *                     ├─→ ScenarioManager ─→ ActiveScenarioSnapshot ─→ engine
 * built-in packs     ─┘         │
 *                               └─→ ScenarioExecutionState (sequence cursors)
 * ```
 *
 * Everything it publishes is derived state: [scenarios] merges saved and
 * code-declared definitions, and [activeSnapshot] is the flattened form the
 * engine reads. The controller subscribes to [activeSnapshot] and does nothing
 * else with scenarios.
 *
 * ### Editing the active scenario
 *
 * Saving a scenario that is currently active applies the change immediately.
 * Live updating is the behaviour QA expects — the alternative, silently running
 * an old definition until someone reactivates, is the kind of thing that costs
 * an afternoon.
 *
 * Sequence progress resets when, and only when, the active **rule set** changes:
 * on activation, on deactivation, and on an edit that touches a rule. Editing a
 * scenario's name or description mid-reproduction leaves a running sequence
 * exactly where it was.
 *
 * ### Threading
 *
 * Reads are `StateFlow`s and are safe from any thread. Writes are `suspend` and
 * serialised by the repository. The engine never touches this class.
 */
class ScenarioManager(
    private val repository: ScenarioRepository,
    private val serializer: ScenarioSerializer,
    private val scope: CoroutineScope,
    builtInPacks: List<BuiltInScenarioPack> = emptyList(),
    val executionState: ScenarioExecutionState = DefaultScenarioExecutionState(),
    private val validator: ScenarioValidator = DefaultScenarioValidator(),
    /**
     * The run layer, which owns seeds, counters and the timeline.
     *
     * Supplied rather than created here so one run manager can be shared with the
     * engine and the controller. Defaulted so every 0.2-era construction site —
     * including a good number of tests — keeps compiling.
     */
    val runManager: ScenarioRunManager = ScenarioRunManager(executionState),
) {

    private val builtInScenarios: List<NetworkScenario> = builtInPacks.flatMap { it.scenarios }
    private val builtInPackList: List<ScenarioPack> = builtInPacks.map { it.pack }

    private val _activeId = MutableStateFlow<ScenarioId?>(null)
    private val _lastError = MutableStateFlow<ScenarioPersistenceError?>(null)

    /** The active scenario's id, or `null` when none is active. */
    val activeScenarioId: StateFlow<ScenarioId?> = _activeId.asStateFlow()

    /** The most recent persistence failure, for a banner. Cleared on the next success. */
    val lastError: StateFlow<ScenarioPersistenceError?> = _lastError.asStateFlow()

    /** True once saved scenarios have been read from storage. */
    val isLoaded: StateFlow<Boolean> = repository.isLoaded

    /**
     * Every scenario the console can show: code-declared first, then saved ones
     * with the most recently updated at the top.
     */
    val scenarios: StateFlow<List<NetworkScenario>> = repository.scenarios
        .map { saved -> builtInScenarios + saved.sortedByDescending { it.metadata.updatedAtMillis } }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = builtInScenarios + repository.scenarios.value,
        )

    /** Every pack, code-declared first. */
    val packs: StateFlow<List<ScenarioPack>> = repository.packs
        .map { saved -> builtInPackList + saved }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = builtInPackList + repository.packs.value,
        )

    /** Packs with their scenarios attached, ready for the list UI. */
    val packContents: StateFlow<List<ScenarioPackContents>> =
        combine(packs, scenarios) { allPacks, allScenarios ->
            allPacks.map { pack ->
                ScenarioPackContents(
                    pack = pack,
                    scenarios = allScenarios.filter { it.metadata.packId == pack.id },
                )
            }
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = packContentsNow(),
        )

    /** Scenarios that belong to no pack. */
    val looseScenarios: StateFlow<List<NetworkScenario>> = scenarios
        .map { all -> all.filter { it.metadata.packId == null } }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = allScenariosNow().filter { it.metadata.packId == null },
        )

    /** Guards the read-then-write in [refreshActive]. */
    private val activeLock = Any()

    private val _activeSnapshot = MutableStateFlow<ActiveScenarioSnapshot?>(null)
    private val _activeScenario = MutableStateFlow<NetworkScenario?>(null)

    /**
     * The active scenario flattened for the engine, or `null`.
     *
     * Recomputed **synchronously** whenever the activation changes or a
     * definition is written, rather than through a `stateIn` that only becomes
     * correct once its coroutine has run. That matters: a request issued right
     * after `activate()` returns must already see the scenario, and a console
     * that renders in the same frame as an activation must already show it.
     */
    val activeSnapshot: StateFlow<ActiveScenarioSnapshot?> = _activeSnapshot.asStateFlow()

    /** The active scenario definition, for the console's detail view. */
    val activeScenario: StateFlow<NetworkScenario?> = _activeScenario.asStateFlow()

    init {
        // Definitions can also change from outside a manager call — another
        // repository client, or the initial load — so keep watching them.
        scope.launch { repository.scenarios.collect { refreshActive() } }
    }

    /**
     * Recomputes the active snapshot from the current definitions.
     *
     * Reads the repository directly rather than the derived [scenarios] flow,
     * because the repository is updated synchronously by a write while a
     * `stateIn` is not — and being one dispatch behind here would mean the
     * interceptor briefly running the previous scenario.
     *
     * Synchronised because it is a read-then-write over three pieces of state
     * and runs from two places at once: the [repository] subscription on one
     * dispatcher thread and `activate`/`save`/`delete`/`load` on the caller's.
     * Interleaving them loses updates — most visibly at launch, where the
     * subscription firing on the initial load could overwrite the activation
     * `load()` had just restored and leave the console showing an active
     * scenario the interceptor was not applying.
     */
    private fun refreshActive() = synchronized(activeLock) {
        val id = _activeId.value
        val scenario = id?.let { active -> allScenariosNow().firstOrNull { it.id == active } }
        val next = scenario?.let(ActiveScenarioSnapshot::of)
        val previous = _activeSnapshot.value

        _activeScenario.value = scenario

        // The run is settled **before** the snapshot is published, so that by the
        // time anything observes an activation the run belonging to it already
        // exists — and, on a deactivation, the scenario's run is already stopped.
        // The controller reacts to this publication by handing an ownerless run
        // back to chaos, and it can only get that right if the manager has
        // finished first.
        syncRun(previous, next)
        _activeSnapshot.value = next
    }

    /**
     * Keeps the scenario run in step with the activation.
     *
     * Done here, synchronously inside [refreshActive], rather than from a
     * subscription: a request issued immediately after `activate()` returns must
     * already be evaluating against the new run's seed and a clean set of
     * counters. A run started one dispatch later would leave the first few
     * requests of a reproduction drawing from the *previous* run's stream, which
     * is exactly the kind of off-by-a-few that makes a seed look unreliable.
     *
     * Callers hold [activeLock].
     */
    private fun syncRun(previous: ActiveScenarioSnapshot?, next: ActiveScenarioSnapshot?) {
        when {
            next == null -> {
                // Only stop a run this manager started. A chaos-only run belongs
                // to the console and must survive a scenario being deactivated.
                if (runManager.run.value?.scenarioId != null) runManager.stopRun()
                executionState.resetAll()
            }

            previous?.id != next.id -> runManager.startRun(
                scenarioId = next.id,
                scenarioName = next.name,
                reason = RunStartReason.ACTIVATED,
                seed = pendingSeed.getAndSet(null),
            )

            // Sequence cursors and rule counters are keyed on rule id, so they
            // are only meaningful while the rule set is the one they counted
            // against. An edit that touches a rule therefore restarts the run —
            // but on the *same seed*, so a QA engineer who adjusted a status code
            // mid-reproduction has not silently lost the seed they were using.
            !sameRules(previous, next) || previous.chaos != next.chaos -> runManager.startRun(
                scenarioId = next.id,
                scenarioName = next.name,
                reason = RunStartReason.DEFINITION_CHANGED,
                seed = runManager.seed,
            )

            // A rename or a description edit: nothing deterministic changed, so a
            // running reproduction is left exactly where it was.
            else -> Unit
        }
    }

    /**
     * A seed to use for the next activation, consumed once.
     *
     * How "import this reproduction and run it" works: the seed is known before
     * the scenario is activated, and activation must adopt it rather than
     * generate a fresh one and immediately throw it away.
     */
    private val pendingSeed = java.util.concurrent.atomic.AtomicReference<Long?>(null)

    /** Packs with their scenarios attached, as of right now. */
    private fun packContentsNow(): List<ScenarioPackContents> =
        (builtInPackList + repository.packs.value).map { pack ->
            ScenarioPackContents(
                pack = pack,
                scenarios = allScenariosNow().filter { it.metadata.packId == pack.id },
            )
        }

    /** Built-ins first, then saved scenarios, most recently updated first. */
    private fun allScenariosNow(): List<NetworkScenario> =
        builtInScenarios + repository.scenarios.value
            .sortedByDescending { it.metadata.updatedAtMillis }

    /** Reads saved scenarios and restores the previously active one. */
    suspend fun load() {
        if (repository is JsonScenarioRepository) {
            repository.load()?.let { _lastError.value = it }
            synchronized(activeLock) {
                repository.activeScenarioId.value
                    ?.takeIf { id -> allScenariosNow().any { it.id == id } }
                    ?.let { _activeId.value = it }
            }
            refreshActive()
        }
    }

    // ---- activation --------------------------------------------------------

    /**
     * Makes [id] the one active scenario, replacing whatever was active.
     *
     * Activating always starts a **new run**: fresh seed, zeroed counters, empty
     * timeline. That is the boundary QA relies on — "I activated it and then the
     * bug happened" has to mean the seed in the run card produced everything
     * since.
     *
     * @param seed the seed to run with, for replaying a reproduction. A fresh one
     *   is generated when `null`.
     * @return true when a scenario with that id exists.
     */
    suspend fun activate(id: ScenarioId, seed: Long? = null): Boolean {
        if (allScenariosNow().none { it.id == id }) return false
        pendingSeed.set(seed)
        val alreadyActive = _activeId.value == id
        synchronized(activeLock) { _activeId.value = id }
        refreshActive()
        // Re-activating the scenario that is already active is a deliberate
        // "start over": the id did not change, so `syncRun` would otherwise have
        // left the run alone.
        if (alreadyActive) {
            val snapshot = _activeSnapshot.value
            runManager.startRun(
                scenarioId = snapshot?.id,
                scenarioName = snapshot?.name,
                reason = RunStartReason.RESTARTED,
                seed = seed ?: pendingSeed.getAndSet(null),
            )
        }
        pendingSeed.set(null)
        persistActivation(id)
        return true
    }

    /** Clears the active scenario. Temporary overrides are untouched. */
    suspend fun deactivate() {
        if (_activeId.value == null) return
        synchronized(activeLock) { _activeId.value = null }
        refreshActive()
        persistActivation(null)
    }

    // ---- runs --------------------------------------------------------------

    /** The run in progress, or `null`. */
    val activeRun: StateFlow<ScenarioRun?> get() = runManager.run

    /**
     * Restarts the current run on the same seed.
     *
     * The developer half of the reproduction workflow. Everything deterministic
     * goes back to where it was at activation: the random stream, rule hit
     * counters, sequence cursors, previous-result state and the timeline.
     */
    fun restartRun(): ScenarioRun? = runManager.restartWithSameSeed()

    /** Restarts the current run on a freshly generated seed. */
    fun restartRunWithNewSeed(): ScenarioRun? = runManager.restartWithNewSeed()

    /** Restarts the current run on [seed], as typed or pasted by a person. */
    fun restartRunWithSeed(seed: Long): ScenarioRun? = runManager.restartWithSeed(seed)

    /**
     * Deactivates without suspending, for callers on a non-suspending path such
     * as `NetKitController.resetEverything()`. The write happens on the
     * manager's own scope.
     */
    fun requestDeactivate() {
        scope.launch { deactivate() }
    }

    /** Toggles [id] between active and not. */
    suspend fun toggleActive(id: ScenarioId): Boolean =
        if (_activeId.value == id) {
            deactivate()
            false
        } else {
            activate(id)
        }

    /** Turns the active scenario's own master switch on or off. */
    suspend fun setScenarioEnabled(id: ScenarioId, enabled: Boolean): ScenarioWriteResult? {
        val scenario = find(id) ?: return null
        if (scenario.enabled == enabled) return null
        if (scenario.metadata.isReadOnly) {
            // A built-in definition cannot be edited, but pausing it is a
            // runtime concern, so deactivating is the honest equivalent.
            if (!enabled) deactivate()
            return null
        }
        return save(scenario.copy(enabled = enabled))
    }

    private suspend fun persistActivation(id: ScenarioId?) {
        if (repository is JsonScenarioRepository) repository.setActiveScenarioId(id)
    }

    // ---- editing -----------------------------------------------------------

    /**
     * Creates or updates a scenario. Applies immediately when it is active.
     *
     * Sequence progress is *not* reset here: the [activeSnapshot] subscription
     * is the single place that decides, and it resets only when the active rule
     * set actually changed. Renaming a scenario mid-reproduction therefore does
     * not silently send a half-run sequence back to step 1.
     */
    suspend fun save(scenario: NetworkScenario): ScenarioWriteResult {
        val result = repository.save(scenario)
        _lastError.value = (result as? ScenarioWriteResult.Failure)?.error
        if (result is ScenarioWriteResult.Success) refreshActive()
        return result
    }

    /**
     * Deletes a scenario, deactivating it first when it is the active one so no
     * runtime state is left pointing at a definition that no longer exists.
     */
    suspend fun delete(id: ScenarioId) {
        val scenario = find(id)
        if (scenario?.metadata?.isReadOnly == true) {
            _lastError.value = ScenarioPersistenceError.ReadOnly(scenario.name)
            return
        }
        if (_activeId.value == id) deactivate()
        report(repository.delete(id))
        refreshActive()
    }

    /** Saves an editable copy of [id], including of a built-in scenario. */
    suspend fun duplicate(id: ScenarioId, name: String? = null): ScenarioWriteResult {
        val builtIn = builtInScenarios.firstOrNull { it.id == id }
        val result = if (builtIn != null) {
            // Built-ins are not in the repository, so duplicate them by value.
            val taken = allScenariosNow().mapTo(mutableSetOf()) { it.name }
            val desired = name ?: "${builtIn.name} Copy"
            val unique = generateSequence(1) { it + 1 }
                .map { attempt -> if (attempt == 1) desired else "$desired $attempt" }
                .first { it !in taken }
            repository.save(builtIn.duplicated(name = unique))
        } else {
            repository.duplicate(id, name)
        }
        _lastError.value = (result as? ScenarioWriteResult.Failure)?.error
        return result
    }

    /** Creates or renames a pack. */
    suspend fun savePack(pack: ScenarioPack) {
        report(repository.savePack(pack))
    }

    /** Deletes a pack. Its scenarios are kept and become loose scenarios. */
    suspend fun deletePack(id: ScenarioPackId) {
        report(repository.deletePack(id))
        refreshActive()
    }

    /** Moves a scenario into a pack, or out of any pack when [packId] is `null`. */
    suspend fun setPack(scenarioId: ScenarioId, packId: ScenarioPackId?) {
        report(repository.setPack(scenarioId, packId))
    }

    /**
     * Publishes a storage failure so the console can say so.
     *
     * A write that failed and reported nothing is the worst outcome available:
     * the UI says "Deleted", and the scenario is back after the next launch.
     */
    private fun report(error: ScenarioPersistenceError?) {
        if (error != null) _lastError.value = error
    }

    // ---- import / export ---------------------------------------------------

    /** Writes [id] as a shareable `.netkit.json`, or `null` when it is unknown. */
    fun export(id: ScenarioId): ScenarioExport? = find(id)?.let(serializer::exportScenario)

    /** Writes a whole pack as a shareable `.netkit.json`. */
    fun exportPack(id: ScenarioPackId): ScenarioExport? {
        val pack = (builtInPackList + repository.packs.value).firstOrNull { it.id == id }
            ?: return null
        val contents = allScenariosNow().filter { it.metadata.packId == id }
        return serializer.exportPack(pack, contents)
    }

    /**
     * Parses and validates a file **without importing it**, so the console can
     * show a preview first. Nothing is written by this call.
     */
    fun preview(content: String): ScenarioImportResult = serializer.import(content)

    /** The summary a preview should render, or `null` for a rejected file. */
    fun summaryOf(result: ScenarioImportResult): ImportSummary? = when (result) {
        is ScenarioImportResult.Scenario -> result.summary
        is ScenarioImportResult.Pack -> result.summary
        is ScenarioImportResult.Reproduction -> result.summary
        else -> null
    }

    /**
     * Saves an already-previewed import.
     *
     * ID collisions never overwrite silently: the default
     * [ImportCollisionPolicy.IMPORT_AS_COPY] gives the incoming scenario a new
     * id and an `(Imported)` name suffix, and the outcome names everything that
     * was renamed so the console can say so.
     */
    suspend fun commitImport(
        result: ScenarioImportResult,
        policy: ImportCollisionPolicy = ImportCollisionPolicy.IMPORT_AS_COPY,
    ): ScenarioImportOutcome = when (result) {
        is ScenarioImportResult.Scenario -> commit(listOf(result.scenario), null, policy)
        is ScenarioImportResult.Pack -> commit(result.scenarios, result.pack, policy)
        is ScenarioImportResult.Reproduction ->
            when (val saved = commit(listOf(result.scenario), null, policy)) {
                is ScenarioImportOutcome.Imported -> saved.scenarios.firstOrNull()
                    ?.let {
                        ScenarioImportOutcome.ImportedReproduction(it, result.seed, result.runId)
                    }
                    ?: saved

                else -> saved
            }

        else -> ScenarioImportOutcome.Rejected(result)
    }

    /**
     * Activates [id] with [seed] — the second half of importing a reproduction.
     *
     * @return true when the scenario exists.
     */
    suspend fun reproduce(id: ScenarioId, seed: Long): Boolean = activate(id, seed)

    /**
     * Writes an already-validated import.
     *
     * Every scenario is prepared and checked **before** anything is written, so
     * a pack whose seventh scenario is rejected leaves nothing behind. Half a
     * reproduction plus a stray pack is worse than a clean refusal, and it is
     * what the import preview promised would not happen.
     */
    private suspend fun commit(
        incoming: List<NetworkScenario>,
        pack: ScenarioPack?,
        policy: ImportCollisionPolicy,
    ): ScenarioImportOutcome {
        val builtInIds = builtInScenarios.mapTo(mutableSetOf()) { it.id }
        val existingIds = allScenariosNow().mapTo(mutableSetOf()) { it.id }
        val takenNames = allScenariosNow().mapTo(mutableSetOf()) { it.name }
        val renamed = mutableListOf<String>()
        var replaced = 0

        // A pack declared in code is not ours to overwrite, and a saved pack
        // sharing its id would be permanently unreachable behind it.
        val preparedPack = pack?.takeIf { it.id !in builtInPackList.map(ScenarioPack::id) }
            ?: pack?.copy(id = ScenarioPackId.random())

        val prepared = mutableListOf<NetworkScenario>()
        for (scenario in incoming) {
            val collides = scenario.id in existingIds
            // Replacing a code-declared scenario is impossible — it is not in the
            // store — and keeping its id would bury the import behind it, so a
            // built-in collision always becomes a copy whatever the policy says.
            val asCopy = collides &&
                (policy == ImportCollisionPolicy.IMPORT_AS_COPY || scenario.id in builtInIds)

            val candidate = when {
                !collides -> scenario
                !asCopy -> {
                    replaced++
                    scenario
                }

                else -> {
                    val name = uniqueName(scenario.name, takenNames)
                    if (name != scenario.name) renamed += scenario.name
                    scenario.duplicated(name = name).let { copy ->
                        copy.copy(
                            metadata = copy.metadata.copy(source = ScenarioSource.IMPORTED),
                        )
                    }
                }
            }.let { candidate ->
                if (preparedPack != null) {
                    candidate.copy(metadata = candidate.metadata.copy(packId = preparedPack.id))
                } else {
                    candidate
                }
            }

            val validation = validator.validate(candidate)
            if (validation is ValidationResult.Invalid) {
                return ScenarioImportOutcome.Failed(
                    ScenarioPersistenceError.Invalid(validation.errors.map { it.message }),
                )
            }
            prepared += candidate
            existingIds += candidate.id
            takenNames += candidate.name
        }

        preparedPack?.let { savePack(it) }

        val saved = mutableListOf<NetworkScenario>()
        for (candidate in prepared) {
            when (val write = save(candidate)) {
                is ScenarioWriteResult.Success -> saved += write.scenario
                is ScenarioWriteResult.Failure -> return ScenarioImportOutcome.Failed(write.error)
            }
        }
        return ScenarioImportOutcome.Imported(saved, preparedPack, renamed, replaced)
    }

    private fun uniqueName(desired: String, taken: Set<String>): String {
        if (desired !in taken) return desired
        val suffixed = "$desired (Imported)"
        if (suffixed !in taken) return suffixed
        return generateSequence(2) { it + 1 }.map { "$suffixed $it" }.first { it !in taken }
    }

    // ---- sequences ---------------------------------------------------------

    /** Restarts one rule's response sequence. */
    fun resetSequence(ruleId: String) = executionState.reset(ruleId)

    /** Restarts every response sequence. */
    fun resetAllSequences() = executionState.resetAll()

    /** Progress for every sequence rule in the active scenario. */
    /** The scenario with [id] from either source, or `null`. */
    fun find(id: ScenarioId): NetworkScenario? = allScenariosNow().firstOrNull { it.id == id }

    fun sequenceProgressFor(scenario: NetworkScenario): Map<String, SequenceProgress> =
        scenario.rules
            .mapNotNull { rule ->
                val action = rule.action as? NetworkAction.Sequence ?: return@mapNotNull null
                rule.id to executionState.peek(rule.id, action.steps.size)
            }
            .toMap()

    private fun sameRules(a: ActiveScenarioSnapshot?, b: ActiveScenarioSnapshot?): Boolean =
        a?.id == b?.id && a?.rules == b?.rules
}
