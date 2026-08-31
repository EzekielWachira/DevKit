package io.devkit.netkit.scenario

/**
 * A transport-independent description of the request being evaluated.
 *
 * The scenario engine deliberately knows nothing about OkHttp: everything it
 * needs to make a decision is captured here. A future Ktor or Apollo binding
 * only has to produce this type to reuse the whole engine.
 *
 * @param method the raw wire method, e.g. `"GET"`.
 * @param scheme `http` or `https`.
 * @param host the request host, without port.
 * @param port the resolved port.
 * @param path the encoded path, always starting with `/`.
 * @param encodedQuery the encoded query string without `?`, or `null`.
 */
data class RequestTarget(
    val method: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val encodedQuery: String? = null,
) {
    /** `GET /api/v1/bookings` — the compact form used in history rows and logs. */
    val summary: String get() = "$method $path"
}
