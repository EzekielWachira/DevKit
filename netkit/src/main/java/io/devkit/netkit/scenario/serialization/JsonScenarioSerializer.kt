package io.devkit.netkit.scenario.serialization

import io.devkit.netkit.NetKitVersion
import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.scenario.model.DefaultScenarioValidator
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioPack
import io.devkit.netkit.scenario.model.ScenarioSource
import io.devkit.netkit.scenario.model.ScenarioValidator
import io.devkit.netkit.scenario.model.ValidationResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The `.netkit.json` reader and writer NetKit installs.
 *
 * Import is deliberately paranoid, in this order:
 *
 * ```text
 * size  →  JSON  →  format identifier  →  schema version  →  migration
 *       →  typed model  →  domain model  →  validation
 * ```
 *
 * Each stage produces a distinct [ScenarioImportResult] so the UI can say
 * exactly what is wrong. Nothing is imported partially: a pack whose third
 * scenario is invalid is rejected whole, because half a reproduction is worse
 * than none.
 *
 * Export runs every scenario through [ScenarioExportSanitizer] first, so a
 * credential pasted into a custom response header never reaches a file that
 * gets attached to a ticket.
 */
class JsonScenarioSerializer(
    private val validator: ScenarioValidator = DefaultScenarioValidator(),
    private val migrator: ScenarioMigrator = ScenarioMigrator(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ScenarioSerializer {

    override fun exportScenario(scenario: NetworkScenario): ScenarioExport {
        val (sanitized, warnings) = ScenarioExportSanitizer.sanitize(scenario)
        val envelope = StoredExportEnvelope(
            type = ScenarioSchema.TYPE_SCENARIO,
            exportedAt = timestamp(),
            generator = NetKitVersion.GENERATOR,
            scenario = ScenarioStorageMapper.toStored(sanitized),
        )
        return ScenarioExport(
            content = prettyJson.encodeToString(envelope),
            suggestedFileName = exportFileName(scenario.name),
            warnings = warnings,
        )
    }

    override fun exportPack(pack: ScenarioPack, scenarios: List<NetworkScenario>): ScenarioExport {
        val warnings = mutableListOf<String>()
        val sanitized = scenarios.map { scenario ->
            val (clean, scenarioWarnings) = ScenarioExportSanitizer.sanitize(scenario)
            warnings += scenarioWarnings
            // Membership travels with the pack, so a re-import lands in the
            // right place even if the pack id is remapped on the way in.
            ScenarioStorageMapper.toStored(
                clean.copy(metadata = clean.metadata.copy(packId = pack.id)),
            )
        }
        val envelope = StoredExportEnvelope(
            type = ScenarioSchema.TYPE_PACK,
            exportedAt = timestamp(),
            generator = NetKitVersion.GENERATOR,
            pack = ScenarioStorageMapper.toStored(pack),
            scenarios = sanitized,
        )
        return ScenarioExport(
            content = prettyJson.encodeToString(envelope),
            suggestedFileName = exportFileName(pack.name),
            warnings = warnings,
        )
    }

    override fun import(content: String): ScenarioImportResult {
        val size = content.utf8Size()
        if (size > NetKitLimits.MAX_IMPORT_BYTES) {
            return ScenarioImportResult.TooLarge(size, NetKitLimits.MAX_IMPORT_BYTES)
        }
        if (content.isBlank()) {
            return ScenarioImportResult.InvalidFile("The file is empty.")
        }

        val root = try {
            leniently.parseToJsonElement(content).jsonObject
        } catch (error: SerializationException) {
            return ScenarioImportResult.InvalidFile(
                "This is not valid JSON: ${error.message?.lines()?.firstOrNull() ?: "parse failed"}",
            )
        } catch (error: IllegalArgumentException) {
            return ScenarioImportResult.InvalidFile("This file is not a JSON object.")
        }

        val format = (root[FIELD_FORMAT] as? JsonPrimitive)?.contentOrNull
        if (format != ScenarioSchema.FORMAT) {
            return ScenarioImportResult.InvalidFile(
                if (format == null) {
                    "This is not a NetKit scenario file — it has no \"format\" field."
                } else {
                    "This is not a NetKit scenario file (format was \"$format\")."
                },
            )
        }

        val declaredVersion = (root[FIELD_VERSION] as? JsonPrimitive)?.intOrNull
            ?: return ScenarioImportResult.InvalidFile(
                "This NetKit file has no schema version.",
            )

        val migrated = when (val result = migrator.migrate(root, declaredVersion)) {
            is MigrationResult.UpToDate -> result.document
            is MigrationResult.Migrated -> result.document
            is MigrationResult.TooNew ->
                return ScenarioImportResult.UnsupportedVersion(result.found, result.supported)

            is MigrationResult.TooOld ->
                return ScenarioImportResult.UnsupportedVersion(result.found, result.oldestSupported)

            is MigrationResult.NoPath ->
                return ScenarioImportResult.UnsupportedVersion(
                    result.found,
                    ScenarioSchema.CURRENT_VERSION,
                )
        }

        val envelope = try {
            leniently.decodeFromJsonElement(StoredExportEnvelope.serializer(), migrated)
        } catch (error: SerializationException) {
            return ScenarioImportResult.InvalidFile(describe(error))
        }

        return when (envelope.type) {
            ScenarioSchema.TYPE_SCENARIO -> importScenario(envelope, declaredVersion)
            ScenarioSchema.TYPE_PACK -> importPack(envelope, declaredVersion)
            ScenarioSchema.TYPE_REPRODUCTION -> importReproduction(envelope, declaredVersion)
            else -> ScenarioImportResult.InvalidFile(
                "Unknown NetKit file type \"${envelope.type}\". This version reads " +
                    "\"${ScenarioSchema.TYPE_SCENARIO}\", \"${ScenarioSchema.TYPE_PACK}\" and " +
                    "\"${ScenarioSchema.TYPE_REPRODUCTION}\".",
            )
        }
    }

    /**
     * Reads a `.netkit-run.json`.
     *
     * The scenario inside is validated exactly like a standalone one — a
     * reproduction is not a trusted channel just because it came from a
     * colleague. What it adds is the seed, which the console offers to activate
     * with, and the trace, which is read purely as display text.
     */
    private fun importReproduction(
        envelope: StoredExportEnvelope,
        version: Int,
    ): ScenarioImportResult {
        val run = envelope.run
            ?: return ScenarioImportResult.InvalidFile(
                "This file says it holds a reproduction but carries no run metadata.",
            )
        val stored = envelope.scenario
            ?: return ScenarioImportResult.InvalidFile(
                "This reproduction carries no scenario, so there is nothing to reproduce.",
            )
        val scenario = try {
            ScenarioStorageMapper.toDomain(stored).asImported()
        } catch (error: IllegalArgumentException) {
            return ScenarioImportResult.InvalidFile(error.message ?: "The scenario is malformed.")
        }
        val validation = validator.validate(scenario)
        if (validation is ValidationResult.Invalid) {
            return ScenarioImportResult.InvalidScenario(validation.errors)
        }
        return ScenarioImportResult.Reproduction(
            scenario = scenario,
            seed = run.seed,
            runId = run.runId.takeIf { it.isNotBlank() },
            trace = envelope.trace.orEmpty().map(::traceLine),
            summary = summarize(
                name = scenario.name,
                description = scenario.description,
                schemaVersion = version,
                scenarios = listOf(scenario),
                seed = run.seed,
            ),
        )
    }

    /** `#17 GET /api/v1/bookings · Chaos · HTTP 503` */
    private fun traceLine(event: StoredTraceEvent): String = buildString {
        append('#').append(event.index)
        event.method?.let { append(' ').append(it) }
        event.path?.let { append(' ').append(it) }
        event.rule?.let { append(" · ").append(it) }
        event.detail?.let { append(" · ").append(it) }
    }

    private fun importScenario(
        envelope: StoredExportEnvelope,
        version: Int,
    ): ScenarioImportResult {
        val stored = envelope.scenario
            ?: return ScenarioImportResult.InvalidFile(
                "This file says it holds a scenario but does not contain one.",
            )
        val scenario = try {
            ScenarioStorageMapper.toDomain(stored).asImported()
        } catch (error: IllegalArgumentException) {
            return ScenarioImportResult.InvalidFile(error.message ?: "The scenario is malformed.")
        }
        val validation = validator.validate(scenario)
        if (validation is ValidationResult.Invalid) {
            return ScenarioImportResult.InvalidScenario(validation.errors)
        }
        return ScenarioImportResult.Scenario(
            scenario = scenario,
            summary = summarize(scenario.name, scenario.description, version, listOf(scenario)),
        )
    }

    private fun importPack(envelope: StoredExportEnvelope, version: Int): ScenarioImportResult {
        val storedPack = envelope.pack
            ?: return ScenarioImportResult.InvalidFile(
                "This file says it holds a scenario pack but does not contain one.",
            )
        val pack: ScenarioPack
        val scenarios: List<NetworkScenario>
        try {
            pack = ScenarioStorageMapper.toDomain(storedPack)
                .copy(source = ScenarioSource.IMPORTED)
            scenarios = envelope.scenarios.orEmpty().map { stored ->
                ScenarioStorageMapper.toDomain(stored).asImported()
            }
        } catch (error: IllegalArgumentException) {
            return ScenarioImportResult.InvalidFile(error.message ?: "The pack is malformed.")
        }
        val validation = validator.validate(pack, scenarios)
        if (validation is ValidationResult.Invalid) {
            return ScenarioImportResult.InvalidScenario(validation.errors)
        }
        return ScenarioImportResult.Pack(
            pack = pack,
            scenarios = scenarios,
            summary = summarize(pack.name, pack.description, version, scenarios),
        )
    }

    /**
     * An imported scenario is never "built-in", whatever the file claims:
     * otherwise a hand-edited file could make itself uneditable in the console.
     */
    private fun NetworkScenario.asImported(): NetworkScenario =
        copy(metadata = metadata.copy(source = ScenarioSource.IMPORTED))

    private fun describe(error: SerializationException): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("polymorphic", ignoreCase = true) ||
                message.contains("class discriminator", ignoreCase = true) ||
                message.contains("is not registered", ignoreCase = true) ->
                "This file uses a rule or action type this NetKit version does not know."

            message.contains("Field", ignoreCase = true) && message.contains("is required") ->
                "This file is missing a required field: ${message.substringAfter("Field ")
                    .substringBefore(" is required")}"

            else -> "This NetKit file could not be read: ${message.lines().firstOrNull().orEmpty()}"
        }
    }

    private fun timestamp(): String = timestampOf(nowMillis())

    @OptIn(ExperimentalSerializationApi::class)
    internal companion object {
        private const val FIELD_FORMAT = "format"
        private const val FIELD_VERSION = "schemaVersion"

        /**
         * Pretty-printed so an exported file is reviewable in a diff and
         * readable in a bug ticket. `explicitNulls = false` keeps optional
         * fields out of the file entirely rather than writing `null`.
         */
        internal val prettyJson: Json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }

        /**
         * Reading is lenient about *unknown* fields — a file from a newer patch
         * release that added an optional field should still import — but never
         * about unknown polymorphic types, which stay a hard error.
         */
        internal val leniently: Json = Json {
            ignoreUnknownKeys = true
            isLenient = false
            explicitNulls = false
            classDiscriminator = "type"
        }

        /** `SimpleDateFormat` is not thread-safe; one instance per thread is. */
        private val iso8601 = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
        }

        /**
         * `2026-09-03T09:44:12Z` — the timestamp format every NetKit export uses.
         *
         * Shared with [ReproductionExporter] so a reproduction and the scenario
         * export beside it in a ticket agree on how a time is written.
         */
        internal fun timestampOf(millis: Long): String = iso8601.get()!!.format(Date(millis))
    }
}

/** Reads `schemaVersion` from a raw document without decoding the whole thing. */
internal fun JsonObject.schemaVersionOrNull(): Int? =
    (this["schemaVersion"] as? JsonPrimitive)?.intOrNull
