package io.devkit.netkit.scenario

import java.util.UUID

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

        /**
         * A globally unique rule id.
         *
         * Deliberately a UUID rather than a counter. Rule ids are **persisted**
         * inside saved scenarios and are the key sequence cursors are stored
         * under, so a counter that restarts at 1 with each process would let a
         * rule created today collide with one saved yesterday — and two rules
         * sharing a cursor means a `500 → 500 → 200` sequence firing on the
         * wrong request.
         */
        fun nextId(): String = "netkit-rule-${UUID.randomUUID()}"

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
