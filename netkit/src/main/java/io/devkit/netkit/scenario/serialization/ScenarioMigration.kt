package io.devkit.netkit.scenario.serialization

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One step forward in the scenario schema.
 *
 * NetKit 0.2 defined version 1 and shipped this pipeline empty, on the reasoning
 * that the alternative was discovering in 0.3 that every scenario a QA team saved
 * was unreadable. 0.3 added [ScenarioMigration1To2] and the reasoning held.
 *
 * Migrations operate on the parsed JSON tree rather than on typed models, so a
 * migration can add, rename and reshape fields that no current data class knows
 * about.
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
        /** Every migration NetKit ships, in any order. */
        val DEFAULT_MIGRATIONS: List<ScenarioMigration> = listOf(ScenarioMigration1To2)
    }
}

/**
 * Schema 1 (NetKit 0.1 / 0.2) → schema 2 (NetKit 0.3).
 *
 * ### Why this rewrites nothing
 *
 * Every field version 2 introduced — rule conditions, rule probability, scenario
 * chaos, preset provenance, the three new action types — is **optional with a
 * neutral default**. A schema-1 rule with no `probability` field deserialises to
 * [io.devkit.netkit.scenario.Probability.ALWAYS], which is precisely what a
 * schema-1 rule did; a scenario with no `chaos` field gets `null`, which means
 * "leave the console's chaos in charge", which is what happened before chaos
 * existed. So the migration's whole job is to stamp the new version number.
 *
 * That is not an accident, it is the design constraint 0.3 was built under: a
 * migration that had to *interpret* old data would be a migration that could get
 * it wrong, and getting it wrong means a QA team's saved reproductions quietly
 * start reproducing something else. The step exists as a real, tested migration
 * rather than a version bump in the reader so that 0.4 — which may well have data
 * to rewrite — has a working, exercised pipeline to add to rather than one that
 * has never run.
 */
internal object ScenarioMigration1To2 : ScenarioMigration {

    override val fromVersion: Int = 1
    override val toVersion: Int = 2

    override fun migrate(document: JsonObject): JsonObject =
        JsonObject(document + ("schemaVersion" to JsonPrimitive(toVersion)))
}
