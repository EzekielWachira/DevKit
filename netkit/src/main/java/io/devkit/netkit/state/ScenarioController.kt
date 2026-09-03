package io.devkit.netkit.state

import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioId
import io.devkit.netkit.scenario.model.ScenarioPack
import io.devkit.netkit.scenario.model.ScenarioPackContents
import io.devkit.netkit.scenario.model.ScenarioPackId
import io.devkit.netkit.scenario.persistence.ScenarioPersistenceError
import io.devkit.netkit.scenario.persistence.ScenarioWriteResult
import io.devkit.netkit.scenario.runtime.ImportCollisionPolicy
import io.devkit.netkit.scenario.runtime.ScenarioImportOutcome
import io.devkit.netkit.scenario.runtime.SequenceProgress
import io.devkit.netkit.scenario.serialization.ScenarioExport
import io.devkit.netkit.scenario.serialization.ScenarioImportResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything a UI does with saved scenarios.
 *
 * Split out of [NetKitController] on purpose. The 0.1 controller is about the
 * *network right now*; this is about *definitions on disk*. Keeping them apart
 * stops one interface from growing into the class that knows everything, and
 * gives the scenario screens a dependency that is exactly their size.
 *
 * Free of Compose, Android and OkHttp types, so the same commands can later come
 * from an IDE bridge or an instrumentation API.
 *
 * **Thread safety.** Reads are `StateFlow`s and are safe from any thread; writes
 * suspend and are serialised.
 */
interface ScenarioController {

    /** Every scenario, code-declared first then saved, newest-updated first. */
    val scenarios: StateFlow<List<NetworkScenario>>

    /** Every pack, code-declared first. */
    val packs: StateFlow<List<ScenarioPack>>

    /** Packs with their scenarios attached, ready for a grouped list. */
    val packContents: StateFlow<List<ScenarioPackContents>>

    /** Scenarios belonging to no pack. */
    val looseScenarios: StateFlow<List<NetworkScenario>>

    /** The active scenario's id, or `null`. */
    val activeScenarioId: StateFlow<ScenarioId?>

    /** The active scenario definition, or `null`. */
    val activeScenario: StateFlow<NetworkScenario?>

    /** Live progress of every response sequence, keyed on rule id. */
    val sequenceProgress: StateFlow<Map<String, SequenceProgress>>

    /** True once saved scenarios have been read from storage. */
    val isLoaded: StateFlow<Boolean>

    /** The most recent persistence failure, for a banner. */
    val lastError: StateFlow<ScenarioPersistenceError?>

    // ---- activation --------------------------------------------------------

    /** Makes [id] the one active scenario. Returns false when the id is unknown. */
    suspend fun activate(id: ScenarioId): Boolean

    /** Clears the active scenario. Temporary overrides are untouched. */
    suspend fun deactivate()

    /** Activates [id], or deactivates it when it is already active. */
    suspend fun toggleActive(id: ScenarioId): Boolean

    /**
     * Activates [id] on [seed] — the developer's side of the reproduction
     * workflow.
     *
     * Equivalent to activating and then restarting on that seed, but atomic, so
     * no request can slip through against a freshly generated seed first.
     *
     * @return true when a scenario with that id exists.
     */
    suspend fun reproduce(id: ScenarioId, seed: Long): Boolean

    /** Pauses or resumes a scenario without losing its activation. */
    suspend fun setScenarioEnabled(id: ScenarioId, enabled: Boolean)

    // ---- editing -----------------------------------------------------------

    /** Creates or updates a scenario. Applies immediately when it is active. */
    suspend fun save(scenario: NetworkScenario): ScenarioWriteResult

    /** Deletes a scenario, deactivating it first when it is active. */
    suspend fun delete(id: ScenarioId)

    /** Saves an editable copy, with fresh ids for the scenario and every rule. */
    suspend fun duplicate(id: ScenarioId, name: String? = null): ScenarioWriteResult

    /**
     * Turns the console's current temporary setup into a saved scenario.
     *
     * The QA workflow this exists for: fiddle with switches until the bug
     * reproduces, then keep what you found instead of writing it down.
     */
    suspend fun saveCurrentSetupAsScenario(
        name: String,
        description: String? = null,
        packId: ScenarioPackId? = null,
        clearTemporary: Boolean = true,
    ): ScenarioWriteResult

    // ---- packs -------------------------------------------------------------

    /** Creates or renames a pack. */
    suspend fun savePack(pack: ScenarioPack)

    /** Deletes a pack. Its scenarios are kept and become loose scenarios. */
    suspend fun deletePack(id: ScenarioPackId)

    /** Moves a scenario into a pack, or out of any pack when [packId] is `null`. */
    suspend fun setPack(scenarioId: ScenarioId, packId: ScenarioPackId?)

    // ---- import / export ---------------------------------------------------

    /** Writes [id] as a shareable `.netkit.json`, or `null` when it is unknown. */
    fun export(id: ScenarioId): ScenarioExport?

    /** Writes a whole pack as a shareable `.netkit.json`. */
    fun exportPack(id: ScenarioPackId): ScenarioExport?

    /**
     * Parses and validates a file **without importing it**, for the preview
     * step. Nothing is written by this call.
     */
    fun preview(content: String): ScenarioImportResult

    /** Saves an already-previewed import. */
    suspend fun commitImport(
        result: ScenarioImportResult,
        policy: ImportCollisionPolicy = ImportCollisionPolicy.IMPORT_AS_COPY,
    ): ScenarioImportOutcome

    // ---- sequences ---------------------------------------------------------

    /** Restarts one rule's response sequence. */
    fun resetSequence(ruleId: String)

    /** Restarts every response sequence. */
    fun resetAllSequences()
}
