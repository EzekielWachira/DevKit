package io.devkit.netkit.engine

import io.devkit.netkit.scenario.TimeoutType

/**
 * What the interceptor must do with one request.
 *
 * This is the boundary between "deciding" and "doing". The engine produces a
 * decision from pure data; the interceptor is the only component that knows how
 * to carry one out with OkHttp. A future Ktor or WebSocket binding reuses every
 * decision by writing a new executor.
 *
 * @property origin who produced the decision, used for the history badge.
 * @property scenarioLabel human description of the responsible scenario.
 * @property ruleId id of the responsible endpoint rule, when there was one.
 */
sealed interface ScenarioDecision {

    val origin: DecisionOrigin
    val scenarioLabel: String?
    val ruleId: String?

    /** True when the application will see something NetKit invented. */
    val isSimulated: Boolean get() = false

    /** Send the request to the real server, untouched. */
    data class PassThrough(
        override val origin: DecisionOrigin = DecisionOrigin.NONE,
        override val scenarioLabel: String? = null,
        override val ruleId: String? = null,
    ) : ScenarioDecision

    /** Sleep [delayMillis], then send the request to the real server. */
    data class Delay(
        val delayMillis: Long,
        override val origin: DecisionOrigin,
        override val scenarioLabel: String?,
        override val ruleId: String? = null,
    ) : ScenarioDecision {
        init {
            require(delayMillis >= 0) { "NetKit delay cannot be negative (was $delayMillis)" }
        }
    }

    /**
     * Sleep [delayMillis], then return a synthetic response. The request never
     * leaves the device.
     */
    data class RespondWith(
        val statusCode: Int,
        val body: String,
        val contentType: String,
        val delayMillis: Long,
        override val origin: DecisionOrigin,
        override val scenarioLabel: String?,
        override val ruleId: String? = null,
    ) : ScenarioDecision {
        override val isSimulated: Boolean get() = true
    }

    /** Fail the request as an unreachable network would. */
    data class FailOffline(
        override val origin: DecisionOrigin,
        override val scenarioLabel: String?,
        override val ruleId: String? = null,
    ) : ScenarioDecision {
        override val isSimulated: Boolean get() = true
    }

    /** Block for the client's timeout, then throw a `SocketTimeoutException`. */
    data class FailTimeout(
        val type: TimeoutType,
        override val origin: DecisionOrigin,
        override val scenarioLabel: String?,
        override val ruleId: String? = null,
    ) : ScenarioDecision {
        override val isSimulated: Boolean get() = true
    }
}

/** Which layer of the scenario produced a [ScenarioDecision]. */
enum class DecisionOrigin {
    /** Nothing applied — NetKit is disabled or the scenario is idle. */
    NONE,

    /** A matching [io.devkit.netkit.scenario.EndpointRule] applied. */
    ENDPOINT_RULE,

    /** The global network configuration applied. */
    GLOBAL,
}
