package io.devkit.netkit.ui

import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.history.NetworkOutcome
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.scenario.NetworkAction
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
        is NetworkAction.HttpError -> buildString {
            append(NetKitDefaults.statusLabel(action.statusCode))
            if (action.delayMillis > 0) append(" after ${action.delayMillis} ms")
        }

        NetworkAction.Offline -> "Fail as offline"
        is NetworkAction.Timeout -> action.type.label
    }

    /** The compact outcome badge, e.g. `200`, `500`, `TIMEOUT`, `OFFLINE`. */
    fun outcomeLabel(record: NetworkRecord): String = record.outcome.label

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
        record.scenarioLabel?.let { appendLine("Scenario  $it") }
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
}
