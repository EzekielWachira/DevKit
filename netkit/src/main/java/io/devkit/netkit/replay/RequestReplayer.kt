package io.devkit.netkit.replay

import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.history.NetworkHistoryStore
import io.devkit.netkit.history.NetworkRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Modifications applied to a request before it is replayed.
 *
 * Only fields a developer would reasonably retype are offered. Notably absent is
 * any way to *read* an existing credential: the replay sheet shows the masked
 * record, so setting an `Authorization` header means typing a new one, never
 * revealing the old.
 *
 * @param url a replacement absolute URL, or `null` to keep the original.
 * @param headers headers to set, replacing any existing value of the same name.
 * @param removedHeaders header names to drop entirely.
 * @param body a replacement request body, or `null` to keep the original.
 * @param contentType the content type for [body]; ignored when [body] is `null`.
 */
data class ReplayOverride(
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val removedHeaders: Set<String> = emptySet(),
    val body: String? = null,
    val contentType: String? = null,
) {
    /** True when this override changes nothing. */
    val isEmpty: Boolean
        get() = url == null && headers.isEmpty() && removedHeaders.isEmpty() && body == null

    companion object {
        /** No modifications — send the request exactly as it was. */
        val None: ReplayOverride = ReplayOverride()
    }
}

/** Why a replay could not be carried out. */
sealed interface ReplayError {

    val message: String

    /** The URL override could not be parsed. */
    data class InvalidUrl(val url: String) : ReplayError {
        override val message: String get() = "'$url' is not a valid absolute URL."
    }

    /** An override could not be applied — an illegal header value, for example. */
    data class InvalidOverride(val detail: String) : ReplayError {
        override val message: String get() = "This replay override is not valid: $detail"
    }

    /** The request went out and the transport failed — often the point of the exercise. */
    data class Transport(val kind: String, val detail: String?) : ReplayError {
        override val message: String get() = detail?.let { "$kind: $it" } ?: kind
    }

    /** Something unexpected inside NetKit. */
    data class Unexpected(val detail: String) : ReplayError {
        override val message: String get() = detail
    }
}

/** The outcome of a replay. */
sealed interface ReplayResult {

    /**
     * The request was sent and a response came back — including an error status,
     * which is a successful replay of a failing call.
     *
     * @param recordId the new history row this replay produced.
     * @param simulated true when an active NetKit rule answered instead of the server.
     */
    data class Success(
        val recordId: Long,
        val statusCode: Int,
        val simulated: Boolean,
        val durationMillis: Long,
    ) : ReplayResult

    /** The request was sent and the transport threw. Still recorded in history. */
    data class Failed(
        val error: ReplayError,
        val recordId: Long?,
    ) : ReplayResult

    /** The request was never sent. */
    data class Unavailable(val reason: ReplayUnavailableReason) : ReplayResult
}

/**
 * Sends a recorded request again.
 *
 * ### Replay is a debugging action, not an application action
 *
 * A replayed response is **not** delivered back into the ViewModel or flow that
 * made the original call. It runs on its own client, its result is recorded in
 * history, and that is all. Feeding it back would make replay a way to corrupt
 * application state from a debug menu.
 *
 * ### Replay can reach the real backend
 *
 * A replayed `POST`, `PUT`, `PATCH` or `DELETE` may create or modify real data.
 * NetKit surfaces [ReplaySnapshot.isSideEffectful] so callers can confirm first;
 * this interface does **not** confirm on its own, because the confirmation
 * belongs to the UI layer that has a user to ask.
 */
interface RequestReplayer {

    /** Whether [recordId] can be replayed right now. */
    fun eligibility(recordId: Long): ReplayEligibility

    /**
     * Replays [recordId].
     *
     * @param override modifications to apply first.
     * @param bypassNetKit send the request straight to the server, ignoring
     *   active rules. Useful for comparing "what the scenario does" with "what
     *   the backend actually does".
     */
    suspend fun replay(
        recordId: Long,
        override: ReplayOverride = ReplayOverride.None,
        bypassNetKit: Boolean = false,
    ): ReplayResult

    /** Convenience overload for a record already in hand. */
    suspend fun replay(
        record: NetworkRecord,
        override: ReplayOverride = ReplayOverride.None,
        bypassNetKit: Boolean = false,
    ): ReplayResult = replay(record.id, override, bypassNetKit)
}

/**
 * Marks a request as a replay so the interceptor can record it correctly.
 *
 * Carried as an OkHttp tag rather than a header, so it cannot leak to the
 * server and cannot be confused with application data. The interceptor reads it
 * to stamp `REPLAY` on the history row and to honour [bypass].
 */
internal data class ReplayTag(
    val sourceRecordId: Long,
    val bypass: Boolean,
)

/**
 * Distinguishes a rejected URL override from every other rejected override.
 *
 * OkHttp throws `IllegalArgumentException` for an illegal header value too, and
 * reporting one of those as "not a valid URL" sends a developer looking in the
 * wrong field.
 */
private class InvalidUrlException(val url: String) : IllegalArgumentException("Not a URL: $url")

/**
 * The replayer NetKit installs.
 *
 * Runs on [dispatcher] — `Dispatchers.IO` by default — because an OkHttp call is
 * blocking, and a replay triggered from a Compose button must not block the
 * main thread.
 *
 * @param client the client replays go through. NetKit builds one with its own
 *   interceptor installed, so an active scenario applies to a replay exactly as
 *   it applies to the application's own traffic.
 */
class DefaultRequestReplayer internal constructor(
    private val snapshots: ReplaySnapshotStore,
    private val history: NetworkHistoryStore,
    private val clientProvider: () -> OkHttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val enabled: Boolean = true,
) : RequestReplayer {

    override fun eligibility(recordId: Long): ReplayEligibility {
        if (!enabled) return ReplayEligibility.Unavailable(ReplayUnavailableReason.DISABLED)
        snapshots.get(recordId)?.let { return ReplayEligibility.Eligible(it) }

        // The interceptor already worked out *why* capture was skipped and put
        // it on the record. Preferring that to the generic "no snapshot" is the
        // difference between "replay unavailable" and "this body can only be
        // sent once" in the detail sheet.
        val recorded = history.records.value
            .firstOrNull { it.id == recordId }
            ?.replayUnavailableReason
        return ReplayEligibility.Unavailable(recorded ?: ReplayUnavailableReason.NO_SNAPSHOT)
    }

    override suspend fun replay(
        recordId: Long,
        override: ReplayOverride,
        bypassNetKit: Boolean,
    ): ReplayResult {
        val snapshot = when (val eligibility = eligibility(recordId)) {
            is ReplayEligibility.Eligible -> eligibility.snapshot
            is ReplayEligibility.Unavailable -> return ReplayResult.Unavailable(eligibility.reason)
        }

        val request = try {
            build(snapshot, override, bypassNetKit)
        } catch (error: InvalidUrlException) {
            return ReplayResult.Failed(ReplayError.InvalidUrl(error.url), recordId = null)
        } catch (error: IllegalArgumentException) {
            // OkHttp also rejects an illegal header name or value here, and
            // reporting every one of those as a bad URL would send a developer
            // looking in the wrong field.
            return ReplayResult.Failed(
                error = ReplayError.InvalidOverride(error.message ?: "unknown"),
                recordId = null,
            )
        }

        val idBefore = history.records.value.firstOrNull()?.id
        return withContext(dispatcher) {
            try {
                clientProvider().newCall(request).execute().use { response ->
                    val record = newRecordSince(idBefore)
                    ReplayResult.Success(
                        recordId = record?.id ?: -1,
                        statusCode = response.code,
                        simulated = record?.isSimulated
                            ?: (response.header(NetKitDefaults.SIMULATED_HEADER) != null),
                        durationMillis = record?.durationMillis ?: 0,
                    )
                }
            } catch (error: IOException) {
                ReplayResult.Failed(
                    error = ReplayError.Transport(
                        kind = error.javaClass.simpleName,
                        detail = error.message,
                    ),
                    recordId = newRecordSince(idBefore)?.id,
                )
            } catch (error: RuntimeException) {
                // A misconfigured client must not crash the debug console.
                ReplayResult.Failed(
                    error = ReplayError.Unexpected(
                        error.message ?: error.javaClass.simpleName,
                    ),
                    recordId = null,
                )
            }
        }
    }

    /**
     * Builds the request to send.
     *
     * Starts from the original — headers included — so a replay reproduces the
     * call faithfully, then applies only what the caller explicitly changed.
     */
    private fun build(
        snapshot: ReplaySnapshot,
        override: ReplayOverride,
        bypassNetKit: Boolean,
    ): Request {
        val builder = snapshot.request.newBuilder()

        override.url?.takeIf { it.isNotBlank() }?.let { raw ->
            val url = raw.trim().toHttpUrlOrNull() ?: throw InvalidUrlException(raw)
            builder.url(url)
        }

        override.removedHeaders.forEach(builder::removeHeader)
        override.headers.forEach { (name, value) -> builder.header(name, value) }

        override.body?.let { text ->
            val mediaType = (
                override.contentType
                    ?: snapshot.request.body?.contentType()?.toString()
                    ?: "application/json"
                ).toMediaTypeOrNull()
            builder.method(snapshot.request.method, text.toRequestBody(mediaType))
        }

        // A visible marker for backend logs, plus the tag the interceptor reads.
        builder.header(NetKitDefaults.REPLAY_HEADER, snapshot.recordId.toString())
        builder.tag(ReplayTag::class.java, ReplayTag(snapshot.recordId, bypassNetKit))
        return builder.build()
    }

    /** The history row the replay just produced, if history is on. */
    private fun newRecordSince(previousNewestId: Long?): NetworkRecord? {
        val newest = history.records.value.firstOrNull() ?: return null
        return newest.takeIf { previousNewestId == null || it.id != previousNewestId }
    }
}
