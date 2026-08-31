package io.devkit.netkit.scenario

import java.util.concurrent.atomic.AtomicLong

/**
 * A runtime override scoped to one endpoint.
 *
 * Rules are immutable value objects. The debug UI and the controller replace
 * them wholesale rather than mutating them, which is what makes the scenario
 * snapshot safe to read from OkHttp's background threads.
 *
 * @param id stable identity used by [io.devkit.netkit.state.NetKitController]
 *   update/remove calls. Generated when omitted.
 * @param name optional human label, surfaced in history as the responsible scenario.
 * @param enabled disabled rules are skipped by the engine but kept in the list.
 * @param method the verb this rule is scoped to; [HttpMethod.ANY] matches all.
 * @param matcher decides which requests this rule claims.
 * @param action what happens to a claimed request.
 */
data class EndpointRule(
    val id: String = nextId(),
    val name: String? = null,
    val enabled: Boolean = true,
    val method: HttpMethod = HttpMethod.ANY,
    val matcher: EndpointMatcher,
    val action: NetworkAction,
) {
    init {
        require(id.isNotBlank()) { "NetKit rule id cannot be blank" }
    }

    /** `GET /api/v1/bookings` — the identity shown in list rows. */
    val displayTarget: String get() = "${method.label} ${matcher.label}"

    /** Name if the author gave one, otherwise the target. */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: displayTarget

    /** True when this rule claims [target]; ignores [enabled] on purpose. */
    fun matches(target: RequestTarget): Boolean =
        method.matches(target.method) && matcher.matches(target)

    companion object {
        private val counter = AtomicLong(0)

        /** Process-unique rule id. Not persisted — NetKit 0.1 keeps rules in memory. */
        fun nextId(): String = "netkit-rule-${counter.incrementAndGet()}"

        /**
         * Convenience factory for the common "one exact path" rule.
         *
         * ```kotlin
         * EndpointRule.forPath("/api/v1/bookings", HttpMethod.GET, NetworkAction.HttpError(500))
         * ```
         */
        fun forPath(
            path: String,
            method: HttpMethod = HttpMethod.ANY,
            action: NetworkAction,
            name: String? = null,
            enabled: Boolean = true,
        ): EndpointRule = EndpointRule(
            name = name,
            enabled = enabled,
            method = method,
            matcher = EndpointMatcher.ExactPath(path),
            action = action,
        )
    }
}
