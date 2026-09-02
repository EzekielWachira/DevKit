package io.devkit.netkit.ui

import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.history.NetworkOutcome
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.runtime.RuleSource
import java.util.Calendar
import java.util.Locale

/**
 * Presentation-only formatting for the NetKit UI.
 *
 * Kept out of the composables so list rows stay declarative and so the same
 * strings can be reused by the "copy details" action.
 */
internal object NetKitFormat {

    /** `10:48:23` in the device's local time zone. */
    fun clockTime(epochMillis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = epochMillis }
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
        )
    }

    /** `12 Mar, 10:48` — the "last updated" line on a scenario. */
    fun dateTime(epochMillis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = epochMillis }
        return String.format(
            Locale.US,
            "%d %s, %02d:%02d",
            calendar.get(Calendar.DAY_OF_MONTH),
            MONTHS[calendar.get(Calendar.MONTH)],
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
        )
    }

    /** `182ms` below a second, `2.5s` above it. */
    fun duration(millis: Long): String = when {
        millis < 1_000 -> "${millis}ms"
        else -> String.format(Locale.US, "%.1fs", millis / 1_000.0)
    }

    /** `0 ms`, `500 ms`, `2500 ms` — the latency chip label. */
    fun latency(millis: Long): String = if (millis == 0L) "None" else "$millis ms"

    /** Bytes as a compact size, e.g. `312 B`, `4.1 KB`. */
    fun size(bytes: Long): String = when {
        bytes < 0 -> "unknown size"
        bytes < 1_024 -> "$bytes B"
        else -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
    }

    /** Full-sentence description of a rule action, used in list rows. */
    fun actionSummary(action: NetworkAction): String = when (action) {
        NetworkAction.PassThrough -> "Pass through to the real server"

        is NetworkAction.Delay -> "Delay ${action.delayMillis} ms, then pass through"

        is NetworkAction.ReturnResponse -> buildString {
            append(NetKitDefaults.statusLabel(action.statusCode))
            if (action.body != null) append(" with a custom body")
            if (action.headers.isNotEmpty()) {
                append(" · ${action.headers.size} header")
                if (action.headers.size != 1) append("s")
            }
            if (action.delayMillis > 0) append(" after ${action.delayMillis} ms")
        }

        is NetworkAction.Malformed ->
            "${action.type.label} · HTTP ${action.statusCode}"

        is NetworkAction.Sequence -> buildString {
            append("Sequence: ")
            append(action.steps.take(4).joinToString(" → ") { it.action.label })
            if (action.steps.size > 4) append(" → …")
            append(" · ${action.completion.label.lowercase()} after")
        }

        NetworkAction.Offline -> "Fail as offline"

        is NetworkAction.Timeout -> action.type.label
    }

    /** The compact outcome badge, e.g. `200`, `500`, `TIMEOUT`, `OFFLINE`. */
    fun outcomeLabel(record: NetworkRecord): String = record.outcome.label

    /** `Saved · 3 rules · updated 12 Mar, 10:48` */
    fun scenarioSubtitle(scenario: NetworkScenario): String = buildString {
        append(scenario.metadata.source.label)
        append(" · ")
        append(scenario.summary)
        append(" · updated ")
        append(dateTime(scenario.metadata.updatedAtMillis))
    }

    /** `Checkout Failure` or `Temporary override`, for the history detail. */
    fun ruleSourceLabel(source: RuleSource?): String = when (source) {
        null -> "None"
        RuleSource.Temporary -> "Temporary override"
        is RuleSource.Scenario -> "Scenario \"${source.scenarioName}\""
    }

    /**
     * Plain-text dump of a record for the clipboard.
     *
     * Built from the stored record, which is already masked, so a copied trace
     * can never contain a credential.
     */
    fun recordAsText(record: NetworkRecord): String = buildString {
        appendLine("${record.method} ${record.url}")
        appendLine("Time      ${clockTime(record.startedAtMillis)}")
        appendLine("Duration  ${duration(record.durationMillis)}")
        appendLine("Source    ${if (record.isSimulated) "SIMULATED by NetKit" else "Real server"}")
        appendLine("Kind      ${record.kind.label}")
        record.replayOfRecordId?.let { appendLine("Replay of request #$it") }
        record.scenarioLabel?.let { appendLine("Rule      $it") }
        record.ruleSource?.let { appendLine("Applied   ${ruleSourceLabel(it)}") }
        record.sequenceDisplay?.let { appendLine("Sequence  step $it") }
        when (val outcome = record.outcome) {
            is NetworkOutcome.Completed -> appendLine("Status    ${outcome.statusCode} ${outcome.message}")
            is NetworkOutcome.Failed -> appendLine("Failure   ${outcome.kind}: ${outcome.message ?: "no message"}")
            NetworkOutcome.InFlight -> appendLine("Status    in flight")
        }
        appendLine()
        appendLine("--- Request headers (masked) ---")
        if (record.requestHeaders.isEmpty()) appendLine("(none)")
        record.requestHeaders.forEach { appendLine("${it.name}: ${it.value}") }
        record.requestBody?.let {
            appendLine()
            appendLine("--- Request body ---")
            appendLine(it.text)
        }
        appendLine()
        appendLine("--- Response headers (masked) ---")
        if (record.responseHeaders.isEmpty()) appendLine("(none)")
        record.responseHeaders.forEach { appendLine("${it.name}: ${it.value}") }
        record.responseBody?.let {
            appendLine()
            appendLine("--- Response body ---")
            appendLine(it.text)
        }
    }

    private val MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
}
