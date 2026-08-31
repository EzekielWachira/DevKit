package io.devkit.netkit.config

/** Constants shared by the runtime and the debug UI. */
object NetKitDefaults {

    /** Requests kept in history before the oldest are evicted. */
    const val MAX_HISTORY_ENTRIES: Int = 150

    /** Largest request/response body preview captured, in bytes. */
    const val MAX_BODY_PREVIEW_BYTES: Long = 8 * 1024

    /**
     * Fallback wait before a simulated timeout throws, used when the OkHttp
     * chain reports no timeout at all (`0`, i.e. "wait forever").
     */
    const val FALLBACK_TIMEOUT_MILLIS: Long = 10_000

    /** Upper bound on how long a simulated timeout blocks a caller. */
    const val MAX_SIMULATED_TIMEOUT_MILLIS: Long = 60_000

    /** Body returned by an [io.devkit.netkit.scenario.NetworkAction.HttpError] with no body. */
    const val DEFAULT_ERROR_BODY: String =
        """{"error":"Simulated by NetKit","message":"This response did not come from your backend."}"""

    /** Header stamped on every synthetic response so proxies and logs can spot it. */
    const val SIMULATED_HEADER: String = "X-NetKit-Simulated"

    /** Header carrying the id of the rule that produced a synthetic response. */
    const val SIMULATED_RULE_HEADER: String = "X-NetKit-Rule"

    /** Latency presets offered by the debug UI, in milliseconds. */
    val LATENCY_PRESETS: List<Long> = listOf(0, 500, 1_000, 2_500, 5_000)

    /**
     * Statuses offered as one-tap choices in the rule editor. Any status in
     * `100..599` can still be typed by hand.
     */
    val COMMON_STATUS_CODES: List<Int> =
        listOf(400, 401, 403, 404, 409, 422, 429, 500, 502, 503, 504)

    /** Content types offered as one-tap choices for a simulated error body. */
    val COMMON_CONTENT_TYPES: List<String> = listOf(
        "application/json",
        "text/plain",
        "application/xml",
        "text/html",
    )

    /**
     * Reason phrase for a status code. OkHttp requires a non-empty message on
     * every [okhttp3.Response]; unknown codes fall back to a generic phrase.
     */
    fun statusMessage(code: Int): String = STATUS_MESSAGES[code] ?: when (code / 100) {
        1 -> "Informational"
        2 -> "OK"
        3 -> "Redirection"
        4 -> "Client Error"
        5 -> "Server Error"
        else -> "Unknown"
    }

    /** `500 Internal Server Error` — used by chips and rule summaries. */
    fun statusLabel(code: Int): String = "$code ${statusMessage(code)}"

    private val STATUS_MESSAGES: Map<Int, String> = mapOf(
        200 to "OK",
        201 to "Created",
        204 to "No Content",
        301 to "Moved Permanently",
        302 to "Found",
        304 to "Not Modified",
        400 to "Bad Request",
        401 to "Unauthorized",
        403 to "Forbidden",
        404 to "Not Found",
        405 to "Method Not Allowed",
        408 to "Request Timeout",
        409 to "Conflict",
        410 to "Gone",
        418 to "I'm a teapot",
        422 to "Unprocessable Entity",
        429 to "Too Many Requests",
        500 to "Internal Server Error",
        501 to "Not Implemented",
        502 to "Bad Gateway",
        503 to "Service Unavailable",
        504 to "Gateway Timeout",
    )
}
