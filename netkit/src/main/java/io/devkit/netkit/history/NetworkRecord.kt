package io.devkit.netkit.history

import io.devkit.netkit.masking.MaskedHeader
import io.devkit.netkit.replay.ReplayUnavailableReason
import io.devkit.netkit.scenario.runtime.RuleSource

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

/**
 * Whether the application made this call, or NetKit replayed it.
 *
 * Independent of [NetworkRecordSource] on purpose: a replay can be answered by
 * the real backend or by a scenario, and QA needs to be able to tell all four
 * combinations apart.
 *
 * ```text
 * REAL   · LIVE     the app called the backend
 * SIM    · LIVE     the app was answered by a scenario
 * REAL   · REPLAY   you replayed it and the backend answered
 * SIM    · REPLAY   you replayed it and a scenario answered
 * ```
 */
enum class NetworkRecordKind {
    /** The application made this request. */
    LIVE,

    /** NetKit re-sent a recorded request. */
    REPLAY,
    ;

    val label: String get() = if (this == LIVE) "LIVE" else "REPLAY"
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
 * constructed, so a record is always safe to display, copy or export. The
 * unmasked request needed for replay is kept separately and only in memory —
 * see [io.devkit.netkit.replay.ReplaySnapshot].
 *
 * Attribution ([ruleSource], [scenarioLabel], [sequenceStep]) is a **snapshot
 * taken at request time**, never a lookup performed at display time: a scenario
 * can be renamed, edited or deleted after a request ran, and an old row must
 * keep saying what was actually responsible for it.
 *
 * @param id monotonically increasing, unique for the process.
 * @param startedAtMillis wall-clock start, used for the timestamp column.
 * @param durationMillis measured wall-clock duration including simulated delays.
 * @param source whether the result was real or simulated.
 * @param kind whether the application or a replay made this request.
 * @param scenarioLabel the rule or global mode responsible, `null` when untouched.
 * @param ruleId the id of the responsible [io.devkit.netkit.scenario.EndpointRule], if any.
 * @param ruleSource whether a temporary override or a saved scenario applied.
 * @param sequenceStep the 1-based step of a response sequence that ran, if any.
 * @param sequenceStepCount how many steps that sequence has.
 * @param replayOfRecordId the record this one is a replay of.
 * @param replayUnavailableReason why this row cannot be replayed, when it cannot.
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
    val kind: NetworkRecordKind = NetworkRecordKind.LIVE,
    val scenarioLabel: String? = null,
    val ruleId: String? = null,
    val ruleSource: RuleSource? = null,
    val sequenceStep: Int? = null,
    val sequenceStepCount: Int? = null,
    val malformed: Boolean = false,
    val replayOfRecordId: Long? = null,
    val replayUnavailableReason: ReplayUnavailableReason? = null,
) {
    /** True when NetKit, not the server, produced this result. */
    val isSimulated: Boolean get() = source == NetworkRecordSource.SIMULATED

    /** True when NetKit re-sent this request rather than the application making it. */
    val isReplay: Boolean get() = kind == NetworkRecordKind.REPLAY

    /** True when the request failed rather than returning a status. */
    val isFailure: Boolean
        get() = outcome is NetworkOutcome.Failed ||
            (outcome as? NetworkOutcome.Completed)?.isSuccessful == false

    /** `GET /api/v1/bookings` */
    val summary: String get() = "$method $path"

    /** `2 / 3`, or `null` when no response sequence was involved. */
    val sequenceDisplay: String?
        get() {
            val step = sequenceStep ?: return null
            val count = sequenceStepCount ?: return null
            return "$step / $count"
        }

    /** The saved scenario responsible, or `null` for temporary or untouched traffic. */
    val scenarioName: String? get() = (ruleSource as? RuleSource.Scenario)?.scenarioName

    /** The badges the history row renders, in order. */
    val badges: List<String>
        get() = buildList {
            if (isReplay) add("REPLAY")
            if (isSimulated) add("SIMULATED")
            if (malformed) add("MALFORMED")
        }
}
