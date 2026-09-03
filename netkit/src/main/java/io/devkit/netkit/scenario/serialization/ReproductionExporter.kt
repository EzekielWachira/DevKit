package io.devkit.netkit.scenario.serialization

import io.devkit.netkit.NetKitVersion
import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.masking.SensitiveDataMasker
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.run.ExecutionEvent
import io.devkit.netkit.scenario.run.ScenarioRun

/**
 * A `.netkit-run.json` ready to be attached to a ticket.
 *
 * @param content the file's exact bytes, as UTF-8 text.
 * @param suggestedFileName e.g. `poor-mobile-network-843921773.netkit-run.json`.
 * @param warnings anything removed on the way out.
 */
data class ReproductionExport(
    override val content: String,
    override val suggestedFileName: String,
    override val warnings: List<String> = emptyList(),
) : NetKitExport

/**
 * Turns a run into something a developer can act on.
 *
 * ### Why this is a separate exporter
 *
 * A scenario export answers "what behaviour is configured". A reproduction export
 * answers "what actually happened, and how do I make it happen again". They carry
 * different data, are attached to different things, and — most importantly — have
 * different security properties: a scenario is authored, while a trace is derived
 * from **real traffic**, and derived-from-real-traffic is where credentials leak.
 *
 * ### What a reproduction contains
 *
 * ```text
 * the scenario definition   (already sanitised by ScenarioExportSanitizer)
 * the seed
 * the run id and start time
 * NetKit and schema versions
 * optionally, a trace: evaluation index, method, masked path, rule, decision
 * ```
 *
 * ### What it never contains, at any setting
 *
 * ```text
 * Authorization or any other credential-bearing header
 * cookies
 * API keys, tokens or signatures in a query string   (masked)
 * request bodies
 * response bodies
 * replay snapshots
 * ```
 *
 * There is no option that turns those back on. A file whose value depends on
 * being attachable to a public bug tracker without review has to be safe by
 * construction, not safe by default — a "include full details" checkbox would be
 * ticked exactly once, by someone in a hurry, on the day it mattered.
 *
 * @param masker used to redact credential-bearing query parameters from traced
 *   paths. The same masker history uses, so an organisation that added its own
 *   parameter names gets them respected here too.
 * @param nowMillis the clock, injected for tests.
 */
class ReproductionExporter(
    private val masker: SensitiveDataMasker,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /**
     * The short summary the "Copy reproduction" button puts on the clipboard.
     *
     * Plain text on purpose: it is pasted into a ticket description, a chat
     * message or a commit body, and every one of those renders JSON badly.
     */
    fun summary(run: ScenarioRun, scenario: NetworkScenario?): String = buildString {
        appendLine("NetKit Reproduction")
        appendLine()
        appendLine("Scenario:")
        appendLine(run.scenarioName ?: scenario?.name ?: "Chaos (no saved scenario)")
        appendLine()
        (run.scenarioId ?: scenario?.id)?.let {
            appendLine("Scenario ID:")
            appendLine(it.value)
            appendLine()
        }
        appendLine("Seed:")
        appendLine(run.seed.toString())
        appendLine()
        appendLine("Run ID:")
        appendLine(run.id.value)
        appendLine()
        appendLine("Requests:")
        appendLine("${run.evaluationCount} evaluated · ${run.simulatedCount} simulated")
        appendLine()
        appendLine("Schema:")
        appendLine(ScenarioSchema.CURRENT_VERSION.toString())
        appendLine()
        appendLine("NetKit:")
        append(NetKitVersion.NAME)
    }

    /**
     * The human-readable trace, for pasting under the summary.
     *
     * Capped at [NetKitLimits.MAX_TRACE_EVENTS] and truncated from the **front**:
     * the events immediately before the failure are the ones worth having, and a
     * trace that dropped them to preserve the first two hundred requests of a
     * session would be useless.
     */
    fun trace(run: ScenarioRun, events: List<ExecutionEvent>): String {
        val kept = events.takeLast(NetKitLimits.MAX_TRACE_EVENTS)
        return buildString {
            appendLine("Scenario: ${run.scenarioName ?: "Chaos"}")
            appendLine("Seed: ${run.seed}")
            if (kept.size < events.size) {
                appendLine("(showing the last ${kept.size} of ${events.size} events)")
            }
            appendLine()
            kept.forEach { event -> appendLine(sanitize(event).traceLine) }
        }.trimEnd()
    }

    /** The portable file a developer imports and replays. */
    fun export(
        run: ScenarioRun,
        scenario: NetworkScenario?,
        events: List<ExecutionEvent>,
        includeTrace: Boolean,
    ): ReproductionExport {
        val warnings = mutableListOf<String>()
        val storedScenario = scenario?.let {
            // A reproduction embeds the definition so the recipient does not have
            // to be sent two files, and it goes through exactly the same
            // sanitiser a plain scenario export does.
            val (sanitized, scenarioWarnings) = ScenarioExportSanitizer.sanitize(it)
            warnings += scenarioWarnings
            ScenarioStorageMapper.toStored(sanitized)
        }
        val envelope = StoredExportEnvelope(
            type = ScenarioSchema.TYPE_REPRODUCTION,
            exportedAt = JsonScenarioSerializer.timestampOf(nowMillis()),
            generator = NetKitVersion.GENERATOR,
            scenario = storedScenario,
            run = StoredRun(
                runId = run.id.value,
                seed = run.seed,
                startedAt = JsonScenarioSerializer.timestampOf(run.startedAtMillis),
                scenarioName = run.scenarioName,
                evaluationCount = run.evaluationCount,
                simulatedCount = run.simulatedCount,
            ),
            trace = if (includeTrace) {
                events.takeLast(NetKitLimits.MAX_TRACE_EVENTS).map(::toStored)
            } else {
                null
            },
        )
        return ReproductionExport(
            content = JsonScenarioSerializer.prettyJson.encodeToString(envelope),
            suggestedFileName = fileName(run),
            warnings = warnings,
        )
    }

    /** `poor-mobile-network-843921773.netkit-run.json` */
    private fun fileName(run: ScenarioRun): String =
        (run.scenarioName ?: "chaos").toFileSlug() + "-" + run.seed + REPRODUCTION_EXTENSION

    /**
     * Redacts a traced event.
     *
     * Only the path can carry anything sensitive — every other field is either a
     * NetKit label or a number — and the masker handles the query string. The
     * event's own [ExecutionEvent.detail] and [ExecutionEvent.reason] are written
     * by NetKit itself and never contain request data.
     */
    private fun sanitize(event: ExecutionEvent): ExecutionEvent {
        val path = event.path ?: return event
        val masked = masker.maskUrl(path)
        return if (masked == path) event else event.copy(path = masked)
    }

    private fun toStored(event: ExecutionEvent): StoredTraceEvent {
        val safe = sanitize(event)
        return StoredTraceEvent(
            index = safe.evaluationIndex,
            type = safe.type.name,
            method = safe.method,
            path = safe.path,
            rule = safe.ruleLabel,
            detail = safe.detail,
            reason = safe.reason,
        )
    }

    companion object {
        /** File extension for an exported reproduction. */
        const val REPRODUCTION_EXTENSION: String = ".netkit-run.json"
    }
}
