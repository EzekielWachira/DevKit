package io.devkit.netkit.scenario

/** Which side of the connection a simulated timeout imitates. */
enum class TimeoutType {
    /** The socket never connects — `SocketTimeoutException("connect timed out")`. */
    CONNECT,

    /** The connection is established but no bytes arrive — `SocketTimeoutException("timeout")`. */
    READ,
    ;

    val label: String get() = if (this == CONNECT) "Connect timeout" else "Read timeout"
}

/**
 * What NetKit does with a request that an [EndpointRule] matched.
 *
 * A matching rule **replaces** the global network behaviour for that request;
 * global latency is not added on top. See `NetKit` documentation for the full
 * precedence table.
 */
sealed interface NetworkAction {

    /** One-line description used by the debug UI and by history records. */
    val label: String

    /**
     * Let the request reach the real server untouched.
     *
     * Because a matching rule wins over the global state, `PassThrough` doubles
     * as an allow-list: an endpoint pinned to `PassThrough` keeps talking to the
     * backend even while the rest of the app is offline or delayed.
     */
    data object PassThrough : NetworkAction {
        override val label: String get() = "Pass through"
    }

    /** Sleep [delayMillis] and then let the request reach the real server. */
    data class Delay(val delayMillis: Long) : NetworkAction {
        init {
            require(delayMillis >= 0) { "NetKit delay cannot be negative (was $delayMillis)" }
        }

        override val label: String get() = "Delay ${delayMillis}ms"
    }

    /**
     * Return a synthetic HTTP response without contacting the server.
     *
     * @param statusCode any status in `100..599`.
     * @param body the response body; `null` falls back to a minimal JSON envelope.
     * @param contentType the `Content-Type` header of the synthetic response.
     * @param delayMillis optional delay applied before the response is returned.
     */
    data class HttpError(
        val statusCode: Int,
        val body: String? = null,
        val contentType: String = DEFAULT_CONTENT_TYPE,
        val delayMillis: Long = 0,
    ) : NetworkAction {
        init {
            require(statusCode in MIN_STATUS..MAX_STATUS) {
                "NetKit HTTP status must be in $MIN_STATUS..$MAX_STATUS (was $statusCode)"
            }
            require(delayMillis >= 0) { "NetKit delay cannot be negative (was $delayMillis)" }
            require(contentType.isNotBlank()) { "NetKit content type cannot be blank" }
        }

        override val label: String get() = "HTTP $statusCode"

        companion object {
            const val DEFAULT_CONTENT_TYPE: String = "application/json"
            const val MIN_STATUS: Int = 100
            const val MAX_STATUS: Int = 599
        }
    }

    /** Fail the request the way a device with no connectivity would. */
    data object Offline : NetworkAction {
        override val label: String get() = "Offline"
    }

    /** Fail the request with a realistic `SocketTimeoutException`. */
    data class Timeout(val type: TimeoutType) : NetworkAction {
        override val label: String get() = type.label
    }
}
