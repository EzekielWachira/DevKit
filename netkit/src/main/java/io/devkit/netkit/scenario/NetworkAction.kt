package io.devkit.netkit.scenario

import io.devkit.netkit.config.NetKitLimits

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
 * One header attached to a simulated response.
 *
 * Modelled as an ordered list rather than a map because HTTP allows repeated
 * header names, and a scenario that returns two `Set-Cookie` headers is a
 * legitimate thing to test.
 *
 * Credential-bearing headers are **stripped on export**; see
 * [io.devkit.netkit.scenario.serialization.ScenarioSerializer].
 */
data class ResponseHeader(val name: String, val value: String) {
    init {
        require(name.isNotBlank()) { "NetKit response header name cannot be blank" }
        require(name.none { it == ':' || it == '\n' || it == '\r' }) {
            "NetKit response header name cannot contain ':' or a line break (was '$name')"
        }
        require(value.none { it == '\n' || it == '\r' }) {
            "NetKit response header value cannot contain a line break"
        }
    }

    /** `Retry-After: 60` */
    val display: String get() = "$name: $value"
}

/**
 * A response body that is deliberately wrong, for testing client parsing.
 *
 * Each type carries the exact bytes and content type NetKit will return, so the
 * behaviour is reproducible rather than "some invalid JSON". The interface is
 * open-ended: later releases can add types without touching the engine, and a
 * consumer can supply [Custom] for a payload NetKit does not model.
 */
sealed interface MalformedResponseType {

    /** Label shown in the rule editor and in history. */
    val label: String

    /** The bytes returned to the caller. */
    val body: String

    /** The `Content-Type` sent with [body]. */
    val contentType: String

    /** A JSON object that stops mid-key: the parser fails on an unexpected end of input. */
    data object InvalidJson : MalformedResponseType {
        override val label: String get() = "Invalid JSON"
        override val body: String get() = "{\n  \"user\":"
        override val contentType: String get() = JSON
    }

    /** `200` with a JSON content type and zero bytes. */
    data object EmptyBody : MalformedResponseType {
        override val label: String get() = "Empty body"
        override val body: String get() = ""
        override val contentType: String get() = JSON
    }

    /** Valid JSON that has been cut short mid-array, as a dropped connection would. */
    data object TruncatedJson : MalformedResponseType {
        override val label: String get() = "Truncated JSON"
        override val body: String
            get() = """{"bookings":[{"id":"b-1","service":"Plumbing"},{"id":"b-2","serv"""
        override val contentType: String get() = JSON
    }

    /** An HTML error page where JSON was expected — the classic proxy or WAF failure. */
    data object HtmlInsteadOfJson : MalformedResponseType {
        override val label: String get() = "HTML instead of JSON"
        override val body: String
            get() = "<html>\n  <body>Gateway Error</body>\n</html>"
        override val contentType: String get() = "text/html"
    }

    /** Valid JSON sent with the wrong content type, which trips strict converters. */
    data object WrongContentType : MalformedResponseType {
        override val label: String get() = "Wrong content type"
        override val body: String get() = """{"ok":true}"""
        override val contentType: String get() = "text/plain"
    }

    /** A bare JSON primitive where an object was expected. */
    data object UnexpectedPrimitive : MalformedResponseType {
        override val label: String get() = "Unexpected primitive"
        override val body: String get() = "\"ok\""
        override val contentType: String get() = JSON
    }

    /** Any other malformed payload a consumer wants to model. */
    data class Custom(
        override val label: String,
        override val body: String,
        override val contentType: String = JSON,
    ) : MalformedResponseType {
        init {
            require(label.isNotBlank()) { "NetKit malformed response label cannot be blank" }
            require(contentType.isNotBlank()) { "NetKit content type cannot be blank" }
        }
    }

    companion object {
        private const val JSON = "application/json"

        /** The built-in types, in the order the rule editor shows them. */
        val builtIn: List<MalformedResponseType> = listOf(
            InvalidJson,
            EmptyBody,
            TruncatedJson,
            HtmlInsteadOfJson,
            WrongContentType,
            UnexpectedPrimitive,
        )
    }
}

/** What happens once a [NetworkAction.Sequence] has run out of steps. */
enum class SequenceCompletionBehavior {
    /** Every later request repeats the final step. The default. */
    REPEAT_LAST,

    /** Every later request goes to the real server. */
    PASS_THROUGH,

    /** The sequence starts again from step 1. */
    LOOP,
    ;

    val label: String get() = when (this) {
        REPEAT_LAST -> "Repeat last"
        PASS_THROUGH -> "Pass through"
        LOOP -> "Loop"
    }
}

/** One entry in a [NetworkAction.Sequence]. */
data class SequenceStep(val action: NetworkAction) {
    init {
        require(action !is NetworkAction.Sequence) {
            "NetKit sequence steps cannot themselves be sequences"
        }
        // A weighted step would make "request 2 of this sequence" mean something
        // different on every run, which is the opposite of what a sequence is
        // for: a sequence is the *deterministic* way to script retry behaviour.
        // Randomness belongs on the rule, where it is visible as such.
        require(action !is NetworkAction.Weighted) {
            "NetKit sequence steps cannot be weighted outcomes — put the randomness " +
                "on the rule instead, so the sequence stays a fixed script"
        }
    }

    /** `HTTP 500` — the label shown next to the step number. */
    val label: String get() = action.label
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
     * Any status in `100..599` is allowed: this action models a *response*, not
     * an error, so `200` with a hand-written payload, `204` with no body and
     * `503` with a backend-shaped error envelope are all the same thing.
     *
     * @param statusCode any status in `100..599`.
     * @param body the response body; `null` falls back to a minimal JSON envelope
     *   for error statuses and to an empty body for `204`/`304`.
     * @param contentType the `Content-Type` header of the synthetic response.
     * @param headers extra response headers, e.g. `Retry-After: 60`.
     * @param delayMillis optional delay applied before the response is returned.
     */
    data class ReturnResponse(
        val statusCode: Int,
        val body: String? = null,
        val contentType: String = DEFAULT_CONTENT_TYPE,
        val headers: List<ResponseHeader> = emptyList(),
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

    /**
     * Return a deliberately malformed response, to exercise parsing and
     * resilience paths that a well-formed error status never reaches.
     *
     * @param type the payload to return.
     * @param statusCode the status sent with it; `200` by default, because the
     *   interesting case is a *successful* response the client cannot parse.
     */
    data class Malformed(
        val type: MalformedResponseType,
        val statusCode: Int = 200,
        val delayMillis: Long = 0,
    ) : NetworkAction {
        init {
            require(statusCode in ReturnResponse.MIN_STATUS..ReturnResponse.MAX_STATUS) {
                "NetKit HTTP status must be in ${ReturnResponse.MIN_STATUS}.." +
                    "${ReturnResponse.MAX_STATUS} (was $statusCode)"
            }
            require(delayMillis >= 0) { "NetKit delay cannot be negative (was $delayMillis)" }
        }

        override val label: String get() = "Malformed · ${type.label}"
    }

    /**
     * Behave differently on each successive request to the same rule.
     *
     * This is how retry logic gets tested: `500 → 500 → 200` proves the client
     * retries twice and then succeeds, without a backend that can be asked to
     * fail exactly twice.
     *
     * Progress is **runtime state**, held by
     * [io.devkit.netkit.scenario.runtime.ScenarioExecutionState] and keyed on the
     * rule id — the action itself stays immutable, so the same scenario can be
     * saved, exported and re-imported without carrying a cursor with it.
     *
     * @param steps the behaviours to run through, in order. Must not be empty.
     * @param completion what happens after the last step.
     */
    data class Sequence(
        val steps: List<SequenceStep>,
        val completion: SequenceCompletionBehavior = SequenceCompletionBehavior.REPEAT_LAST,
    ) : NetworkAction {
        init {
            require(steps.isNotEmpty()) { "NetKit response sequence needs at least one step" }
            require(steps.size <= NetKitLimits.MAX_SEQUENCE_STEPS) {
                "NetKit response sequence cannot exceed ${NetKitLimits.MAX_SEQUENCE_STEPS} " +
                    "steps (was ${steps.size})"
            }
        }

        override val label: String get() = "Sequence of ${steps.size}"
    }

    /** Fail the request the way a device with no connectivity would. */
    data object Offline : NetworkAction {
        override val label: String get() = "Offline"
    }

    /** Fail the request with a realistic `SocketTimeoutException`. */
    data class Timeout(val type: TimeoutType) : NetworkAction {
        override val label: String get() = type.label
    }

    /**
     * Fail the request as a connection dropped mid-flight would.
     *
     * Distinct from [Offline], which imitates a device that cannot resolve a host
     * at all (`UnknownHostException`). This is the other common shape: the host
     * was reachable, the call was in progress, and the connection went away
     * (`IOException: Connection reset`). Clients frequently treat the two
     * differently — one looks like "no network", the other like "the server hung
     * up" — so a scenario needs to be able to produce each.
     *
     * This is an **application-layer simulation**. NetKit runs inside an OkHttp
     * interceptor and throws the exception a reset would produce; it does not and
     * cannot drop packets, reset a TCP connection, or affect the radio. See the
     * module README's limitations section.
     */
    data object Disconnect : NetworkAction {
        override val label: String get() = "Disconnect"
    }

    /**
     * Sleep for a value drawn from [latency], then let the request reach the real
     * server.
     *
     * The random draw goes through the run's seeded stream, so the same seed and
     * the same evaluation index always produce the same delay. A range whose ends
     * agree ([LatencyRange.isFixed]) draws nothing and behaves exactly like
     * [Delay].
     */
    data class RandomDelay(val latency: LatencyRange) : NetworkAction {
        override val label: String get() = "Delay ${latency.label}"
    }

    /**
     * Choose one of several behaviours at random, by relative weight.
     *
     * This is how "mostly fine, sometimes broken" is expressed for a single
     * endpoint:
     *
     * ```kotlin
     * NetworkAction.Weighted(
     *     listOf(
     *         WeightedOutcome(60, NetworkAction.PassThrough),
     *         WeightedOutcome(15, NetworkAction.ReturnResponse(500)),
     *         WeightedOutcome(10, NetworkAction.ReturnResponse(503)),
     *         WeightedOutcome(10, NetworkAction.Timeout(TimeoutType.READ)),
     *         WeightedOutcome(5, NetworkAction.Disconnect),
     *     ),
     * )
     * ```
     *
     * Weights are relative and normalised internally, so they need not sum to
     * 100. [PassThrough] is a legitimate outcome and is how "and the rest of the
     * time it works" is written down.
     *
     * The choice is drawn from the run's seeded stream keyed on the rule id, so
     * it is reproducible and independent of the other rules in the scenario.
     */
    data class Weighted(val outcomes: List<WeightedOutcome>) : NetworkAction {
        init {
            require(outcomes.isNotEmpty()) {
                "NetKit weighted action needs at least one outcome"
            }
            require(outcomes.size <= NetKitLimits.MAX_WEIGHTED_OUTCOMES) {
                "NetKit weighted action cannot exceed ${NetKitLimits.MAX_WEIGHTED_OUTCOMES} " +
                    "outcomes (was ${outcomes.size})"
            }
        }

        override val label: String get() = "Random of ${outcomes.size}"
    }

    companion object {
        /**
         * NetKit 0.1 name for [ReturnResponse].
         *
         * Kept as a factory so existing `NetworkAction.HttpError(500)` call sites
         * keep compiling. `is NetworkAction.HttpError` checks do not — see the
         * migration notes in the module README.
         */
        @Deprecated(
            message = "Renamed to ReturnResponse: the action models any HTTP response, " +
                "not only an error.",
            replaceWith = ReplaceWith(
                "NetworkAction.ReturnResponse(statusCode, body, contentType, " +
                    "delayMillis = delayMillis)",
            ),
        )
        fun HttpError(
            statusCode: Int,
            body: String? = null,
            contentType: String = ReturnResponse.DEFAULT_CONTENT_TYPE,
            delayMillis: Long = 0,
        ): ReturnResponse = ReturnResponse(
            statusCode = statusCode,
            body = body,
            contentType = contentType,
            delayMillis = delayMillis,
        )
    }
}
