package io.devkit.netkit.scenario.serialization

import kotlinx.serialization.json.JsonObject

/**
 * One step forward in the scenario schema.
 *
 * NetKit 0.2 ships no migrations — it defines version 1 — but the pipeline
 * exists now because the alternative is discovering in 0.3 that every scenario
 * a QA team saved is unreadable. Migrations operate on the parsed JSON tree
 * rather than on typed models, so a migration can add, rename and reshape fields
 * that no current data class knows about.
 *
 * Implementations must be pure and must not throw on data they do not
 * recognise: an unknown field is a field for a later migration to handle.
 */
interface ScenarioMigration {

    /** The version this migration reads. */
    val fromVersion: Int

    /** The version this migration produces. Must be `fromVersion + 1`. */
    val toVersion: Int

    /** Rewrites one document. */
    fun migrate(document: JsonObject): JsonObject
}

/** The outcome of running a document through the migration pipeline. */
sealed interface MigrationResult {

    /** The document is now at [ScenarioSchema.CURRENT_VERSION]. */
    data class Migrated(val document: JsonObject, val fromVersion: Int) : MigrationResult

    /** The document was already current. */
    data class UpToDate(val document: JsonObject) : MigrationResult

    /**
     * The document comes from a newer NetKit. Importing part of it would be
     * worse than refusing it: an unknown action type silently dropped is a
     * scenario that reproduces the wrong bug.
     */
    data class TooNew(val found: Int, val supported: Int) : MigrationResult

    /** The document predates the oldest version this NetKit can read. */
    data class TooOld(val found: Int, val oldestSupported: Int) : MigrationResult

    /** A migration step is missing, so the chain cannot be completed. */
    data class NoPath(val found: Int, val stuckAt: Int) : MigrationResult
}

/**
 * Runs a stored or imported document forward to the current schema version.
 *
 * @param migrations the steps available, in any order.
 */
class ScenarioMigrator(
    migrations: List<ScenarioMigration> = DEFAULT_MIGRATIONS,
) {
    private val byFromVersion: Map<Int, ScenarioMigration> = migrations.associateBy { migration ->
        require(migration.toVersion == migration.fromVersion + 1) {
            "A NetKit scenario migration must move exactly one version " +
                "(${migration.fromVersion} → ${migration.toVersion})"
        }
        migration.fromVersion
    }

    /** Migrates [document], which declares schema version [version]. */
    fun migrate(document: JsonObject, version: Int): MigrationResult {
        if (version > ScenarioSchema.CURRENT_VERSION) {
            return MigrationResult.TooNew(version, ScenarioSchema.CURRENT_VERSION)
        }
        if (version < ScenarioSchema.MIN_SUPPORTED_VERSION) {
            return MigrationResult.TooOld(version, ScenarioSchema.MIN_SUPPORTED_VERSION)
        }
        if (version == ScenarioSchema.CURRENT_VERSION) return MigrationResult.UpToDate(document)

        var current = document
        var at = version
        while (at < ScenarioSchema.CURRENT_VERSION) {
            val step = byFromVersion[at] ?: return MigrationResult.NoPath(version, at)
            current = step.migrate(current)
            at = step.toVersion
        }
        return MigrationResult.Migrated(current, version)
    }

    companion object {
        /**
         * Every migration NetKit ships. Empty in 0.2: version 1 is the first
         * schema, so there is nothing yet to migrate *from*.
         */
        val DEFAULT_MIGRATIONS: List<ScenarioMigration> = emptyList()
    }
}
