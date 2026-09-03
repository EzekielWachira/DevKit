package io.devkit.netkit.scenario

import io.devkit.netkit.scenario.condition.RequestBodySource

/**
 * A transport-independent description of the request being evaluated.
 *
 * The scenario engine deliberately knows nothing about OkHttp: everything it
 * needs to make a decision is captured here. A future Ktor or Apollo binding
 * only has to produce this type to reuse the whole engine.
 *
 * ### Cost
 *
 * 0.3 added the three fields conditions need, and each is opt-in so an
 * 0.1-shaped scenario still costs what it did. [headers] is only populated when
 * the active configuration actually contains a header condition; [bodySource]
 * never reads anything until a body condition asks; and [queryParameters] parses
 * lazily and at most once per request. See
 * [io.devkit.netkit.scenario.runtime.RequestInspection].
 *
 * @param method the raw wire method, e.g. `"GET"`.
 * @param scheme `http` or `https`.
 * @param host the request host, without port.
 * @param port the resolved port.
 * @param path the encoded path, always starting with `/`.
 * @param encodedQuery the encoded query string without `?`, or `null`.
 * @param headers the request headers, in wire order. Empty when the active
 *   configuration has no header condition, which is not the same as the request
 *   having no headers — do not treat an empty list as proof of absence outside
 *   condition evaluation.
 * @param bodySource lazy, bounded access to the request body.
 */
data class RequestTarget(
    val method: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val encodedQuery: String? = null,
    val headers: List<Pair<String, String>> = emptyList(),
    val bodySource: RequestBodySource = RequestBodySource.Absent,
) {
    /** `GET /api/v1/bookings` — the compact form used in history rows and logs. */
    val summary: String get() = "$method $path"

    /**
     * The decoded query parameters, parsed once on first use.
     *
     * `LazyThreadSafetyMode.PUBLICATION` rather than the synchronised default: a
     * `RequestTarget` is normally confined to one thread, parsing twice in the
     * rare race is harmless and idempotent, and a lock on the request path for
     * something this small would not pay for itself.
     *
     * Repeated parameters keep the **first** value, matching how servers
     * overwhelmingly read `?page=1&page=2`.
     */
    val queryParameters: Map<String, String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        parseQuery(encodedQuery)
    }

    /** The value of header [name], case-insensitively, or `null`. */
    fun header(name: String): String? {
        for (index in headers.indices) {
            val (headerName, value) = headers[index]
            if (headerName.equals(name, ignoreCase = true)) return value
        }
        return null
    }

    private companion object {

        fun parseQuery(encoded: String?): Map<String, String> {
            if (encoded.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in encoded.split('&')) {
                if (pair.isEmpty()) continue
                val separator = pair.indexOf('=')
                val name: String
                val value: String
                if (separator < 0) {
                    name = pair
                    // A bare `?debug` is present-with-an-empty-value, which is
                    // what makes an `exists` condition match it.
                    value = ""
                } else {
                    name = pair.substring(0, separator)
                    value = pair.substring(separator + 1)
                }
                val decodedName = decode(name)
                if (decodedName.isEmpty() || decodedName in out) continue
                out[decodedName] = decode(value)
            }
            return out
        }

        /**
         * Percent-decoding, hand-rolled to keep the engine free of
         * `java.net.URLDecoder` — which throws on a malformed escape and would
         * turn a hostile URL into an exception on the request path.
         */
        fun decode(raw: String): String {
            if (raw.indexOf('%') < 0 && raw.indexOf('+') < 0) return raw
            val out = StringBuilder(raw.length)
            var index = 0
            while (index < raw.length) {
                when (val char = raw[index]) {
                    '+' -> out.append(' ')
                    '%' -> {
                        val hex = if (index + 2 < raw.length) {
                            raw.substring(index + 1, index + 3).toIntOrNull(16)
                        } else {
                            null
                        }
                        if (hex == null) {
                            out.append(char)
                        } else {
                            out.append(hex.toChar())
                            index += 2
                        }
                    }

                    else -> out.append(char)
                }
                index++
            }
            return out.toString()
        }
    }
}
