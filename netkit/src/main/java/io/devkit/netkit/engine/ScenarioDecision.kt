package io.devkit.netkit.engine

import io.devkit.netkit.scenario.ResponseHeader
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.scenario.runtime.RuleSource

/**
 * What the interceptor must do with one request.
 *
 * This is the boundary between "deciding" and "doing". The engine produces a
 * decision from pure data; the interceptor is the only component that knows how
 * to carry one out with OkHttp. A future Ktor or WebSocket binding reuses every
 * decision by writing a new executor.
 *
 * @property origin which layer of the configuration produced the decision.
 * @property source whether a temporary override or a saved scenario was
 *   responsible, captured now because the scenario may be edited or deleted
 *   before anyone reads the history row.
 * @property scenarioLabel human description of the responsible behaviour.
 * @property ruleId id of the responsible endpoint rule, when there was one.
 * @property sequence which step of a response sequence ran, when one did.
 */
sealed interface ScenarioDecision {

    val origin: DecisionOrigin
    val source: RuleSource?
    val scenarioLabel: String?
    val ruleId: String?
    val sequence: SequenceStepInfo?

    /** True when the application will see something NetKit invented. */
    val isSimulated: Boolean get() = false

    /** Send the request to the real server, untouched. */
    data class PassThrough(
        override val origin: DecisionOrigin = DecisionOrigin.NONE,
        override val scenarioLabel: String? = null,
        override val ruleId: String? = null,
        override val source: RuleSource? = null,
        override val sequence: SequenceStepInfo? = null,
    ) : ScenarioDecision

    /** Sleep [delayMillis], then send the request to the real server. */
    data class Delay(
        val delayMillis: Long,
        override val origin: DecisionOrigin,
        override val scenarioLabel: String?,
        override val ruleId: String? = null,
        override val source: RuleSource? = null,
        override val sequence: SequenceStepInfo? = null,
    ) : ScenarioDecision {
        init {
            require(delayMillis >= 0) { "NetKit delay cannot be negative (was $delayMillis)" }
        }
    }

    /**
     * Sleep [delayMillis], then return a synthetic response. The request never
     * leaves the device.
     *
     * @param malformed true when the payload is deliberately unparseable, so the
     *   history row can say so instead of reading as an ordinary `200`.
     */
    data class RespondWith(
        val statusCode: Int,
        val body: String,
        val contentType: String,
        val delayMillis: Long,
        override val origin: DecisionOrigin,
        override val scenarioLabel: String?,
        override val ruleId: String? = null,
        val headers: List<ResponseHeader> = emptyList(),
        val malformed: Boolean = false,
        override val source: RuleSource? = null,
        override val sequence: SequenceStepInfo? = null,
    ) : ScenarioDecision {
        override val isSimulated: Boolean get() = true
    }

    /** Fail the request as an unreachable network would. */
    data class FailOffline(
        override val origin: DecisionOrigin,
        override val scenarioLabel: String?,
        override val ruleId: String? = null,
        override val source: RuleSource? = null,
        override val sequence: SequenceStepInfo? = null,
    ) : ScenarioDecision {
        override val isSimulated: Boolean get() = true
    }

    /** Block for the client's timeout, then throw a `SocketTimeoutException`. */
    data class FailTimeout(
        val type: TimeoutType,
        override val origin: DecisionOrigin,
        override val scenarioLabel: String?,
        override val ruleId: String? = null,
        override val source: RuleSource? = null,
        override val sequence: SequenceStepInfo? = null,
    ) : ScenarioDecision {
        override val isSimulated: Boolean get() = true
    }
}

/** Which step of a response sequence produced a decision. */
data class SequenceStepInfo(
    /** 1-based step number. */
    val step: Int,
    /** How many steps the sequence has. */
    val stepCount: Int,
) {
    /** `2 / 3` */
    val display: String get() = "$step / $stepCount"
}

/** Which layer of the configuration produced a [ScenarioDecision]. */
enum class DecisionOrigin {
    /** Nothing applied — NetKit is disabled or the configuration is idle. */
    NONE,

    /** A matching [io.devkit.netkit.scenario.EndpointRule] applied. */
    ENDPOINT_RULE,

    /** A global network configuration applied. */
    GLOBAL,
    ;

    val label: String get() = when (this) {
        NONE -> "None"
        ENDPOINT_RULE -> "Endpoint rule"
        GLOBAL -> "Global"
    }
}
