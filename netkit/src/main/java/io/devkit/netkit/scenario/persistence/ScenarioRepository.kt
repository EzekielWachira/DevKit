package io.devkit.netkit.scenario.persistence

import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioId
import io.devkit.netkit.scenario.model.ScenarioPack
import io.devkit.netkit.scenario.model.ScenarioPackId
import kotlinx.coroutines.flow.StateFlow

/** Why a scenario could not be stored. */
sealed interface ScenarioPersistenceError {

    /** Message for a snackbar or a log. */
    val message: String

    /** The scenario failed validation and was not written. */
    data class Invalid(val errors: List<String>) : ScenarioPersistenceError {
        override val message: String get() = errors.joinToString("; ")
    }

    /** The id does not exist. */
    data class NotFound(val id: String) : ScenarioPersistenceError {
        override val message: String get() = "No scenario or pack with id '$id'."
    }

    /** The definition is code-declared and cannot be edited or deleted. */
    data class ReadOnly(val name: String) : ScenarioPersistenceError {
        override val message: String
            get() = "'$name' is declared in application code. Duplicate it to make an editable copy."
    }

    /** The underlying store failed. */
    data class StorageFailed(val cause: Throwable) : ScenarioPersistenceError {
        override val message: String
            get() = "Could not write scenarios: ${cause.message ?: cause.javaClass.simpleName}"
    }
}

/** The outcome of a repository write. */
sealed interface ScenarioWriteResult {
    data class Success(val scenario: NetworkScenario) : ScenarioWriteResult
    data class Failure(val error: ScenarioPersistenceError) : ScenarioWriteResult

    /** The scenario when the write succeeded, `null` otherwise. */
    val scenarioOrNull: NetworkScenario? get() = (this as? Success)?.scenario
}

/**
 * Where saved scenarios and packs live.
 *
 * Everything above this interface — the manager, the console, the serializer —
 * works in domain types and never learns whether the backing store is DataStore,
 * a file or a map. That is what lets the whole scenario system be unit-tested
 * without Android, and what will let 0.3 change the storage format without
 * touching a single caller.
 *
 * Implementations must be safe to call from any thread. Reads are a `StateFlow`
 * so the console and the runtime snapshot both stay current without polling.
 */
interface ScenarioRepository {

    /** Every saved scenario, newest-updated first. Never includes built-ins. */
    val scenarios: StateFlow<List<NetworkScenario>>

    /** Every saved pack. Never includes built-ins. */
    val packs: StateFlow<List<ScenarioPack>>

    /** True once the first load from the backing store has completed. */
    val isLoaded: StateFlow<Boolean>

    /** The scenario with [id], or `null`. */
    fun get(id: ScenarioId): NetworkScenario?

    /** The pack with [id], or `null`. */
    fun getPack(id: ScenarioPackId): ScenarioPack?

    /** Creates or replaces [scenario], stamping `updatedAt`. */
    suspend fun save(scenario: NetworkScenario): ScenarioWriteResult

    /** Removes the scenario with [id]. No-op when unknown. Returns any write failure. */
    suspend fun delete(id: ScenarioId): ScenarioPersistenceError?

    /** Saves a copy of [id] under a new identity, with fresh rule ids. */
    suspend fun duplicate(id: ScenarioId, name: String? = null): ScenarioWriteResult

    /** Creates or replaces [pack]. Returns any write failure. */
    suspend fun savePack(pack: ScenarioPack): ScenarioPersistenceError?

    /**
     * Removes [id]. Scenarios in the pack are kept and become loose scenarios,
     * because deleting a folder should never delete the work inside it.
     */
    suspend fun deletePack(id: ScenarioPackId): ScenarioPersistenceError?

    /** Moves [scenarioId] into [packId], or out of any pack when `null`. */
    suspend fun setPack(scenarioId: ScenarioId, packId: ScenarioPackId?): ScenarioPersistenceError?

    /** Removes every saved scenario and pack. Returns any write failure. */
    suspend fun clear(): ScenarioPersistenceError?
}
