package io.devkit.netkit.scenario

/**
 * Decides whether an [EndpointRule] applies to a given [RequestTarget].
 *
 * The interface is intentionally open rather than sealed so that consumers — and
 * later NetKit releases — can add matchers (prefix, regex, host, query, header,
 * body, GraphQL operation) without a breaking change. Implementations must be
 * **immutable and thread-safe**: [matches] is called from OkHttp's background
 * threads while the debug UI mutates rules on the main thread.
 *
 * NetKit 0.1 ships [ExactPath] only.
 */
interface EndpointMatcher {

    /** Short human description rendered in the debug UI, e.g. `/api/v1/bookings`. */
    val label: String

    /** Returns true when [target] should be handled by the owning rule. */
    fun matches(target: RequestTarget): Boolean

    /**
     * Matches one encoded path exactly, ignoring scheme, host, port and query.
     *
     * The path is normalised on construction: a missing leading `/` is added and
     * a trailing `/` is dropped (except for the root path), so `api/v1/bookings`,
     * `/api/v1/bookings` and `/api/v1/bookings/` all describe the same endpoint.
     */
    data class ExactPath(val path: String) : EndpointMatcher {

        private val normalized: String = normalize(path)

        override val label: String get() = normalized

        override fun matches(target: RequestTarget): Boolean =
            normalize(target.path) == normalized

        companion object {
            internal fun normalize(raw: String): String {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return "/"
                val withLeadingSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
                return if (withLeadingSlash.length > 1 && withLeadingSlash.endsWith("/")) {
                    withLeadingSlash.dropLast(1)
                } else {
                    withLeadingSlash
                }
            }
        }
    }
}
