package io.devkit.netkit.replay

import io.devkit.netkit.config.NetKitLimits
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.ConcurrentHashMap

/** Why a recorded request cannot be replayed. */
enum class ReplayUnavailableReason {
    /** No snapshot exists — history outlived the process, or capture was off. */
    NO_SNAPSHOT,

    /** The body can only be written once, so it cannot be sent again. */
    ONE_SHOT_BODY,

    /** The body is a duplex stream; re-sending it is not meaningful. */
    DUPLEX_BODY,

    /** Replay is switched off in `NetKitConfig`. */
    DISABLED,
    ;

    val message: String get() = when (this) {
        NO_SNAPSHOT ->
            "Replay unavailable for this historical request. NetKit keeps replay data in " +
                "memory only, so it does not survive a process restart."

        ONE_SHOT_BODY ->
            "Replay unavailable — this request has a one-shot body that can only be sent once."

        DUPLEX_BODY ->
            "Replay unavailable — this request uses a duplex (streaming) body."

        DISABLED -> "Replay is disabled in this NetKit configuration."
    }
}

/** Whether a history row can be replayed, and why not when it cannot. */
sealed interface ReplayEligibility {

    data class Eligible(val snapshot: ReplaySnapshot) : ReplayEligibility

    data class Unavailable(val reason: ReplayUnavailableReason) : ReplayEligibility

    val isEligible: Boolean get() = this is Eligible
}

/**
 * The original request, kept **in memory only**, so a recorded call can be sent
 * again.
 *
 * This is the security-critical half of replay. A [io.devkit.netkit.history.NetworkRecord]
 * is masked on the way in, which makes it safe to display, copy, persist and
 * export — and useless for replay, because a masked `Authorization` header is
 * not a credential. Rather than weaken the record, NetKit keeps the real request
 * here, under three rules:
 *
 * ```text
 * never persisted   — dies with the process
 * never exported    — the serializer cannot see this package
 * never displayed   — the UI reads the masked record, not the snapshot
 * ```
 *
 * The consequence, accepted deliberately, is that replay does not survive a
 * process restart. Weakening any of the three rules to fix that would put real
 * tokens on disk, which is a far worse trade for a debug tool.
 *
 * @param recordId the history row this snapshot belongs to.
 * @param request the original OkHttp request, headers and all.
 * @param hasReplaceableBody whether this request carries a textual body the
 *   replay sheet can offer to *replace*. Note what is absent: the body itself.
 *   NetKit does not mask request bodies, and a body is exactly where a password
 *   or a refresh token lives, so the UI is told only that one exists.
 */
class ReplaySnapshot internal constructor(
    val recordId: Long,
    internal val request: Request,
    val hasReplaceableBody: Boolean,
) {
    /** `POST` */
    val method: String get() = request.method

    /** The full URL. Shown only after the record's own masking. */
    internal val url: String get() = request.url.toString()

    /**
     * True for methods that can create or change server state.
     *
     * NetKit asks for confirmation before replaying one of these. `GET` and
     * `HEAD` are defined as safe by RFC 9110; everything else is treated as
     * potentially destructive, including methods NetKit does not model.
     */
    val isSideEffectful: Boolean get() = !SAFE_METHODS.contains(method.uppercase())

    /** Header names the replay editor may show and override. Never credentials. */
    val overridableHeaderNames: List<String> get() = request.headers.names().sorted()

    private companion object {
        val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")
    }
}

/**
 * A bounded, in-memory store of replay snapshots.
 *
 * Bounded because a snapshot pins the original request — including its
 * credentials — in memory, and an unbounded map would keep every token seen in a
 * debugging session alive for the life of the process. The oldest snapshots are
 * dropped first; those history rows then report [ReplayUnavailableReason.NO_SNAPSHOT],
 * which is exactly the honest answer.
 *
 * @param maxEntries how many recent requests stay replayable.
 */
class ReplaySnapshotStore(
    private val maxEntries: Int = NetKitLimits.MAX_REPLAY_SNAPSHOTS,
) {
    init {
        require(maxEntries > 0) { "NetKit replay snapshot limit must be positive" }
    }

    private val lock = Any()
    private val snapshots = ConcurrentHashMap<Long, ReplaySnapshot>()
    private val order = ArrayDeque<Long>()

    /**
     * Captures [request] against [recordId], if it can be replayed at all.
     *
     * @return the reason capture was skipped, or `null` when a snapshot was
     *   stored. The interceptor stamps that reason onto the history record so
     *   the detail sheet can explain the greyed-out Replay button.
     */
    fun capture(recordId: Long, request: Request): ReplayUnavailableReason? {
        val body = request.body
        if (body != null) {
            if (body.isDuplex()) return ReplayUnavailableReason.DUPLEX_BODY
            if (body.isOneShot()) return ReplayUnavailableReason.ONE_SHOT_BODY
        }
        val snapshot = ReplaySnapshot(
            recordId = recordId,
            request = request,
            hasReplaceableBody = body != null && isTextual(body),
        )
        synchronized(lock) {
            if (snapshots.put(recordId, snapshot) == null) order.addLast(recordId)
            while (order.size > maxEntries) {
                snapshots.remove(order.removeFirst())
            }
        }
        return null
    }

    /** The snapshot for [recordId], or `null` when it was never stored or was evicted. */
    fun get(recordId: Long): ReplaySnapshot? = snapshots[recordId]

    /** How many snapshots are held. */
    val size: Int get() = snapshots.size

    /** Drops every snapshot. Called by history clearing and by a full reset. */
    fun clear() {
        synchronized(lock) {
            snapshots.clear()
            order.clear()
        }
    }

    /**
     * Whether the body is text a person could reasonably retype.
     *
     * Decided from the content type alone: the body is never read here, so this
     * check cannot throw, cannot buffer a large upload, and cannot put a
     * credential anywhere.
     */
    private fun isTextual(body: RequestBody): Boolean {
        val contentType = body.contentType() ?: return false
        val type = contentType.type.lowercase()
        val subtype = contentType.subtype.lowercase()
        return type == "text" ||
            subtype == "json" ||
            subtype == "xml" ||
            subtype == "x-www-form-urlencoded" ||
            subtype.endsWith("+json") ||
            subtype.endsWith("+xml")
    }
}
