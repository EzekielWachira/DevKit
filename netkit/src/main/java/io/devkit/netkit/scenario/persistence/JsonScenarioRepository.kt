package io.devkit.netkit.scenario.persistence

import io.devkit.netkit.scenario.model.DefaultScenarioValidator
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioId
import io.devkit.netkit.scenario.model.ScenarioPack
import io.devkit.netkit.scenario.model.ScenarioPackId
import io.devkit.netkit.scenario.model.ScenarioValidator
import io.devkit.netkit.scenario.model.ValidationResult
import io.devkit.netkit.scenario.serialization.MigrationResult
import io.devkit.netkit.scenario.serialization.ScenarioMigrator
import io.devkit.netkit.scenario.serialization.ScenarioSchema
import io.devkit.netkit.scenario.serialization.ScenarioStorageMapper
import io.devkit.netkit.scenario.serialization.StoredScenarioDocument
import io.devkit.netkit.scenario.serialization.schemaVersionOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The repository NetKit installs: domain models in, one versioned JSON document
 * out.
 *
 * ### Why a whole document rather than a row per scenario
 *
 * Scenarios are small, are always read together, and change only when a person
 * presses Save. Rewriting one document makes every write atomic — the store is
 * never half-updated — and makes the migration story a single version number
 * instead of one per row.
 *
 * ### Reads never touch storage
 *
 * The in-memory [scenarios] and [packs] flows are the source of truth for
 * readers; storage is written behind them. That is what lets the console and the
 * runtime snapshot read scenarios synchronously while a save is still in flight,
 * and it is why a slow disk can never stall the interceptor.
 *
 * @param storage where the document lives.
 * @param activeScenarioId the id to restore on the next launch; the manager
 *   writes it here so activation survives a restart alongside the scenarios.
 */
class JsonScenarioRepository(
    private val storage: ScenarioStorage,
    private val validator: ScenarioValidator = DefaultScenarioValidator(),
    private val migrator: ScenarioMigrator = ScenarioMigrator(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ScenarioRepository {

    private val writeMutex = Mutex()

    private val _scenarios = MutableStateFlow<List<NetworkScenario>>(emptyList())
    private val _packs = MutableStateFlow<List<ScenarioPack>>(emptyList())
    private val _isLoaded = MutableStateFlow(false)
    private val _activeScenarioId = MutableStateFlow<ScenarioId?>(null)

    override val scenarios: StateFlow<List<NetworkScenario>> = _scenarios.asStateFlow()
    override val packs: StateFlow<List<ScenarioPack>> = _packs.asStateFlow()
    override val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    /** The scenario that was active when the process last exited, if any. */
    val activeScenarioId: StateFlow<ScenarioId?> = _activeScenarioId.asStateFlow()

    /**
     * Loads the document.
     *
     * A store that cannot be parsed is **not** thrown away: it is left on disk
     * and the repository starts empty, so a NetKit bug cannot silently destroy a
     * QA engineer's saved scenarios. The next successful save overwrites it.
     */
    suspend fun load(): ScenarioPersistenceError? = writeMutex.withLock {
        val raw = try {
            storage.read()
        } catch (error: Exception) {
            _isLoaded.value = true
            return@withLock ScenarioPersistenceError.StorageFailed(error)
        }
        if (raw.isNullOrBlank()) {
            _isLoaded.value = true
            return@withLock null
        }

        val parsed = try {
            parse(raw)
        } catch (error: SerializationException) {
            _isLoaded.value = true
            return@withLock ScenarioPersistenceError.StorageFailed(error)
        } catch (error: IllegalArgumentException) {
            _isLoaded.value = true
            return@withLock ScenarioPersistenceError.StorageFailed(error)
        }
        val document = when (parsed) {
            is ParsedDocument.Ok -> parsed.document
            is ParsedDocument.UnreadableVersion -> {
                _isLoaded.value = true
                return@withLock ScenarioPersistenceError.StorageFailed(
                    IllegalStateException(
                        "Saved scenarios use schema version ${parsed.version}, which this " +
                            "NetKit build cannot read. They have been left untouched on disk.",
                    ),
                )
            }
        }

        // A single unreadable scenario must not cost the user the other nine.
        _scenarios.value = document.scenarios.mapNotNull { stored ->
            runCatching { ScenarioStorageMapper.toDomain(stored) }.getOrNull()
        }
        _packs.value = document.packs.mapNotNull { stored ->
            runCatching { ScenarioStorageMapper.toDomain(stored) }.getOrNull()
        }
        _activeScenarioId.value = document.activeScenarioId
            ?.takeIf { it.isNotBlank() }
            ?.let(::ScenarioId)
        _isLoaded.value = true
        null
    }

    override fun get(id: ScenarioId): NetworkScenario? = _scenarios.value.firstOrNull { it.id == id }

    override fun getPack(id: ScenarioPackId): ScenarioPack? = _packs.value.firstOrNull { it.id == id }

    override suspend fun save(scenario: NetworkScenario): ScenarioWriteResult {
        val existing = get(scenario.id)
        if (existing?.metadata?.isReadOnly == true) {
            return ScenarioWriteResult.Failure(ScenarioPersistenceError.ReadOnly(existing.name))
        }
        val validation = validator.validate(scenario)
        if (validation is ValidationResult.Invalid) {
            return ScenarioWriteResult.Failure(
                ScenarioPersistenceError.Invalid(validation.errors.map { it.message }),
            )
        }

        val stamped = scenario.copy(
            metadata = scenario.metadata.copy(updatedAtMillis = nowMillis()),
        )
        return writeMutex.withLock {
            val before = _scenarios.value
            val index = before.indexOfFirst { it.id == stamped.id }
            _scenarios.value = if (index >= 0) {
                before.toMutableList().apply { set(index, stamped) }
            } else {
                before + stamped
            }
            val failure = persist()
            if (failure != null) {
                // A write that did not reach storage must not be left showing in
                // the list: the console would report success and the scenario
                // would be gone after the next launch.
                _scenarios.value = before
                return@withLock ScenarioWriteResult.Failure(failure)
            }
            ScenarioWriteResult.Success(stamped)
        }
    }

    override suspend fun delete(id: ScenarioId): ScenarioPersistenceError? = writeMutex.withLock {
        val remaining = _scenarios.value.filterNot { it.id == id }
        if (remaining.size == _scenarios.value.size) return@withLock null
        _scenarios.value = remaining
        if (_activeScenarioId.value == id) _activeScenarioId.value = null
        persist()
    }

    override suspend fun duplicate(id: ScenarioId, name: String?): ScenarioWriteResult {
        val source = get(id)
            ?: return ScenarioWriteResult.Failure(ScenarioPersistenceError.NotFound(id.value))
        val taken = _scenarios.value.mapTo(mutableSetOf()) { it.name }
        val desired = name ?: "${source.name} Copy"
        val unique = generateSequence(1) { it + 1 }
            .map { attempt -> if (attempt == 1) desired else "$desired $attempt" }
            .first { it !in taken }
        return save(source.duplicated(name = unique, nowMillis = nowMillis()))
    }

    override suspend fun savePack(pack: ScenarioPack): ScenarioPersistenceError? =
        writeMutex.withLock {
            val current = _packs.value
            val index = current.indexOfFirst { it.id == pack.id }
            if (index >= 0 && current[index].isReadOnly) {
                return@withLock ScenarioPersistenceError.ReadOnly(current[index].name)
            }
            _packs.value = if (index >= 0) {
                current.toMutableList().apply { set(index, pack) }
            } else {
                current + pack
            }
            persist()
        }

    override suspend fun deletePack(id: ScenarioPackId): ScenarioPersistenceError? =
        writeMutex.withLock {
            val remaining = _packs.value.filterNot { it.id == id }
            if (remaining.size == _packs.value.size) return@withLock null
            _packs.value = remaining
            // Scenarios outlive their pack: deleting a folder must not delete
            // the reproductions inside it.
            _scenarios.value = _scenarios.value.map { scenario ->
                if (scenario.metadata.packId == id) {
                    scenario.copy(metadata = scenario.metadata.copy(packId = null))
                } else {
                    scenario
                }
            }
            persist()
        }

    override suspend fun setPack(
        scenarioId: ScenarioId,
        packId: ScenarioPackId?,
    ): ScenarioPersistenceError? = writeMutex.withLock {
        val current = _scenarios.value
        val index = current.indexOfFirst { it.id == scenarioId }
        if (index < 0 || current[index].metadata.packId == packId) return@withLock null
        _scenarios.value = current.toMutableList().apply {
            val scenario = this[index]
            set(
                index,
                scenario.copy(
                    metadata = scenario.metadata.copy(
                        packId = packId,
                        updatedAtMillis = nowMillis(),
                    ),
                ),
            )
        }
        persist()
    }

    override suspend fun clear(): ScenarioPersistenceError? = writeMutex.withLock {
        _scenarios.value = emptyList()
        _packs.value = emptyList()
        _activeScenarioId.value = null
        try {
            storage.clear()
            null
        } catch (error: Exception) {
            ScenarioPersistenceError.StorageFailed(error)
        }
    }

    /** Remembers which scenario is active, so activation survives a restart. */
    suspend fun setActiveScenarioId(id: ScenarioId?): ScenarioPersistenceError? =
        writeMutex.withLock {
            if (_activeScenarioId.value == id) return@withLock null
            _activeScenarioId.value = id
            persist()
        }

    /** Caller must hold [writeMutex]. */
    private suspend fun persist(): ScenarioPersistenceError? {
        val document = StoredScenarioDocument(
            schemaVersion = ScenarioSchema.CURRENT_VERSION,
            scenarios = _scenarios.value.map(ScenarioStorageMapper::toStored),
            packs = _packs.value.map(ScenarioStorageMapper::toStored),
            activeScenarioId = _activeScenarioId.value?.value,
        )
        return try {
            storage.write(json.encodeToString(document))
            null
        } catch (error: Exception) {
            ScenarioPersistenceError.StorageFailed(error)
        }
    }

    /** A stored document that was read, or the version that made it unreadable. */
    private sealed interface ParsedDocument {
        data class Ok(val document: StoredScenarioDocument) : ParsedDocument
        data class UnreadableVersion(val version: Int) : ParsedDocument
    }

    private fun parse(raw: String): ParsedDocument {
        val root: JsonObject = json.parseToJsonElement(raw).jsonObject
        val version = root.schemaVersionOrNull() ?: ScenarioSchema.CURRENT_VERSION
        val migrated = when (val result = migrator.migrate(root, version)) {
            is MigrationResult.UpToDate -> result.document
            is MigrationResult.Migrated -> result.document
            is MigrationResult.TooNew,
            is MigrationResult.TooOld,
            is MigrationResult.NoPath,
            -> return ParsedDocument.UnreadableVersion(version)
        }
        return ParsedDocument.Ok(
            json.decodeFromJsonElement(StoredScenarioDocument.serializer(), migrated),
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private companion object {
        /**
         * Compact rather than pretty: this document is machine-written on every
         * save and never read by a person. Unknown keys are tolerated so a
         * downgrade after a patch release does not wipe the store.
         */
        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }
    }
}
