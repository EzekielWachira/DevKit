package io.devkit.netkit.history

import io.devkit.netkit.masking.MaskedHeader

/**
 * Whether the application saw a real server result or something NetKit invented.
 *
 * QA reads this badge before filing a bug, so it must never be inferred from
 * colour alone; the UI renders it as an explicit `SIMULATED` label.
 */
enum class NetworkRecordSource {
    /** The bytes came from the real server. */
    REAL,

    /** NetKit produced the response or the failure. */
    SIMULATED,
}

/** How a request ended. */
sealed interface NetworkOutcome {

    /** Short label for list rows, e.g. `200`, `TIMEOUT`, `OFFLINE`. */
    val label: String

    /** The transport completed and returned a status. */
    data class Completed(val statusCode: Int, val message: String) : NetworkOutcome {
        override val label: String get() = statusCode.toString()

        /** True for `2xx`. Used only for presentation. */
        val isSuccessful: Boolean get() = statusCode in 200..299
    }

    /** The request threw. [kind] is the simple exception name, e.g. `SocketTimeoutException`. */
    data class Failed(val kind: String, val message: String?) : NetworkOutcome {
        override val label: String get() = when {
            kind.contains("Timeout", ignoreCase = true) -> "TIMEOUT"
            kind.contains("UnknownHost", ignoreCase = true) -> "OFFLINE"
            else -> "FAILED"
        }
    }

    /** The request is still running. Present only briefly, never persisted. */
    data object InFlight : NetworkOutcome {
        override val label: String get() = "…"
    }
}

/** A captured request/response body preview, already truncated to a safe size. */
data class BodyPreview(
    val text: String,
    val truncated: Boolean,
    val byteCount: Long,
)

/**
 * One request as it passed through the NetKit interceptor.
 *
 * Records are immutable. Everything credential-bearing has already been run
 * through [io.devkit.netkit.masking.SensitiveDataMasker] before the record is
 * constructed, so a record is always safe to display, copy or export.
 *
 * @param id monotonically increasing, unique for the process.
 * @param startedAtMillis wall-clock start, used for the timestamp column.
 * @param durationMillis measured wall-clock duration including simulated delays.
 * @param source whether the result was real or simulated.
 * @param scenarioLabel the rule or global mode responsible, `null` when untouched.
 * @param ruleId the id of the responsible [io.devkit.netkit.scenario.EndpointRule], if any.
 */
data class NetworkRecord(
    val id: Long,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val method: String,
    val scheme: String,
    val host: String,
    val path: String,
    val url: String,
    val requestHeaders: List<MaskedHeader> = emptyList(),
    val requestBody: BodyPreview? = null,
    val outcome: NetworkOutcome = NetworkOutcome.InFlight,
    val responseHeaders: List<MaskedHeader> = emptyList(),
    val responseBody: BodyPreview? = null,
    val source: NetworkRecordSource = NetworkRecordSource.REAL,
    val scenarioLabel: String? = null,
    val ruleId: String? = null,
) {
    /** True when NetKit, not the server, produced this result. */
    val isSimulated: Boolean get() = source == NetworkRecordSource.SIMULATED

    /** `GET /api/v1/bookings` */
    val summary: String get() = "$method $path"
}
