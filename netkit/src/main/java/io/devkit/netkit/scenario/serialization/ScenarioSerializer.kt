package io.devkit.netkit.scenario.serialization

import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.masking.DefaultSensitiveDataMasker
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioPack
import io.devkit.netkit.scenario.model.ValidationError
import java.nio.charset.StandardCharsets

/**
 * A `.netkit.json` ready to be written to disk or shared.
 *
 * @param content the file's exact bytes, as UTF-8 text.
 * @param suggestedFileName e.g. `checkout-retry-bug.netkit.json`.
 * @param warnings anything removed or worth telling the exporter about, such as
 *   a credential-bearing response header that was stripped.
 */
data class ScenarioExport(
    val content: String,
    val suggestedFileName: String,
    val warnings: List<String> = emptyList(),
)

/** What an imported file turned out to contain. */
data class ImportSummary(
    val name: String,
    val description: String?,
    val schemaVersion: Int,
    val scenarioCount: Int,
    val ruleCount: Int,
    val hasResponseOverrides: Boolean,
    val hasMalformedResponses: Boolean,
    val hasSequences: Boolean,
    val hasTimeouts: Boolean,
    val hasOfflineRules: Boolean,
    val globalSummary: String?,
) {
    /** The checklist the import preview renders. */
    val contents: List<String> = buildList {
        if (hasResponseOverrides) add("HTTP response overrides")
        if (hasMalformedResponses) add("Malformed responses")
        if (hasSequences) add("Response sequences")
        if (hasTimeouts) add("Timeouts")
        if (hasOfflineRules) add("Offline failures")
    }
}

/**
 * The outcome of reading a `.netkit.json`.
 *
 * A sealed result rather than an exception: every one of these is a normal thing
 * for a QA engineer to hit while picking a file, and every one has a different
 * sentence the UI should show.
 */
sealed interface ScenarioImportResult {

    /** One scenario, ready for the import preview. */
    data class Scenario(
        val scenario: NetworkScenario,
        val summary: ImportSummary,
    ) : ScenarioImportResult

    /** A whole pack and its scenarios. */
    data class Pack(
        val pack: ScenarioPack,
        val scenarios: List<NetworkScenario>,
        val summary: ImportSummary,
    ) : ScenarioImportResult

    /** Not a NetKit file, or structurally broken. */
    data class InvalidFile(val reason: String) : ScenarioImportResult

    /** A NetKit file, but from a version this build cannot read. */
    data class UnsupportedVersion(val found: Int, val supported: Int) : ScenarioImportResult {
        val reason: String
            get() = "Unsupported NetKit schema version: $found\n" +
                "This version supports schema version $supported."
    }

    /** A NetKit file, but structurally valid content that fails validation. */
    data class InvalidScenario(val errors: List<ValidationError>) : ScenarioImportResult {
        val reason: String get() = errors.joinToString("\n") { it.message }
    }

    /** Larger than [NetKitLimits.MAX_IMPORT_BYTES]. */
    data class TooLarge(val bytes: Int, val limit: Int) : ScenarioImportResult {
        val reason: String
            get() = "This file is ${bytes / 1024} KB. NetKit imports files up to " +
                "${limit / 1024} KB."
    }

    /** A human-readable explanation of a failure, or `null` on success. */
    val failureReason: String?
        get() = when (this) {
            is Scenario, is Pack -> null
            is InvalidFile -> reason
            is UnsupportedVersion -> reason
            is InvalidScenario -> reason
            is TooLarge -> reason
        }
}

/**
 * Reads and writes the NetKit scenario file format.
 *
 * Kept free of file systems, `Uri`s and Android intents on purpose: this
 * interface turns strings into scenarios and back, which makes every rule about
 * validation, versioning and export safety testable as a plain unit test. The
 * Android layer that picks a file and shares it is a thin wrapper on top.
 */
interface ScenarioSerializer {

    /** Writes one scenario as a shareable `.netkit.json`. */
    fun exportScenario(scenario: NetworkScenario): ScenarioExport

    /** Writes a pack and its scenarios as one shareable `.netkit.json`. */
    fun exportPack(pack: ScenarioPack, scenarios: List<NetworkScenario>): ScenarioExport

    /** Reads a `.netkit.json`. Never throws on malformed input. */
    fun import(content: String): ScenarioImportResult
}

/** File-name-safe slug, e.g. `Checkout Retry Bug` → `checkout-retry-bug`. */
internal fun String.toFileSlug(): String = trim()
    .lowercase()
    .map { if (it.isLetterOrDigit()) it else '-' }
    .joinToString("")
    .split('-')
    .filter { it.isNotEmpty() }
    .joinToString("-")
    .take(60)
    .ifEmpty { "scenario" }

/**
 * Removes anything from a scenario that must never leave the device.
 *
 * A scenario is authored data, so it should not contain secrets — but a custom
 * response header is free text, and "paste the real `Set-Cookie` so the app
 * behaves" is exactly the shortcut someone takes at 6pm. Stripping is not
 * advisory: a `.netkit.json` gets attached to bug tickets, pasted into chat and
 * committed to repositories.
 */
internal object ScenarioExportSanitizer {

    private val sensitiveHeaderNames: Set<String> =
        DefaultSensitiveDataMasker.DEFAULT_HEADER_NAMES.mapTo(mutableSetOf()) { it.lowercase() }

    /** The sanitised scenario plus one warning per removed header. */
    fun sanitize(scenario: NetworkScenario): Pair<NetworkScenario, List<String>> {
        val warnings = mutableListOf<String>()
        val rules = scenario.rules.map { rule ->
            val cleaned = sanitize(rule.action, rule.displayTarget, warnings)
            if (cleaned === rule.action) rule else rule.copy(action = cleaned)
        }
        val sanitized = if (rules == scenario.rules) scenario else scenario.copy(rules = rules)
        return sanitized to warnings
    }

    private fun sanitize(
        action: NetworkAction,
        ruleLabel: String,
        warnings: MutableList<String>,
    ): NetworkAction = when (action) {
        is NetworkAction.ReturnResponse -> {
            val kept = action.headers.filterNot { it.name.lowercase() in sensitiveHeaderNames }
            if (kept.size == action.headers.size) {
                action
            } else {
                action.headers.filter { it.name.lowercase() in sensitiveHeaderNames }
                    .forEach { header ->
                        warnings += "Removed the '${header.name}' response header from " +
                            "$ruleLabel — NetKit never exports credential-bearing headers."
                    }
                action.copy(headers = kept)
            }
        }

        is NetworkAction.Sequence -> {
            val steps = action.steps.map { step ->
                val cleaned = sanitize(step.action, ruleLabel, warnings)
                if (cleaned === step.action) step else step.copy(action = cleaned)
            }
            if (steps == action.steps) action else action.copy(steps = steps)
        }

        else -> action
    }
}

/** Builds the summary the import preview renders, without importing anything. */
internal fun summarize(
    name: String,
    description: String?,
    schemaVersion: Int,
    scenarios: List<NetworkScenario>,
): ImportSummary {
    val actions = scenarios.flatMap { scenario -> scenario.rules.map { it.action } }
    val flattened = actions.flatMap { action ->
        if (action is NetworkAction.Sequence) listOf(action) + action.steps.map { it.action } else listOf(action)
    }
    return ImportSummary(
        name = name,
        description = description,
        schemaVersion = schemaVersion,
        scenarioCount = scenarios.size,
        ruleCount = scenarios.sumOf { it.rules.size },
        hasResponseOverrides = flattened.any { it is NetworkAction.ReturnResponse },
        hasMalformedResponses = flattened.any { it is NetworkAction.Malformed },
        hasSequences = actions.any { it is NetworkAction.Sequence },
        hasTimeouts = flattened.any { it is NetworkAction.Timeout },
        hasOfflineRules = flattened.any { it == NetworkAction.Offline },
        globalSummary = scenarios.firstNotNullOfOrNull { it.globalConfig }
            ?.takeIf { !it.isNormal }
            ?.summary,
    )
}

/** Bytes of [this] as UTF-8, for the size checks import and validation share. */
internal fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

/** `checkout-retry-bug.netkit.json` */
internal fun exportFileName(name: String): String =
    name.toFileSlug() + NetKitDefaults.EXPORT_FILE_EXTENSION
