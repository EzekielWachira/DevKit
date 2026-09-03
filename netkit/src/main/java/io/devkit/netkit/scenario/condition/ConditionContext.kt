package io.devkit.netkit.scenario.condition

import io.devkit.netkit.scenario.RequestTarget

/**
 * The result of asking the transport for a peek at the request body.
 *
 * A sealed result rather than a nullable `String` because "there was no body" and
 * "there was a body but reading it would have broken the request" lead to the
 * same non-match but very different diagnostics, and a QA engineer wondering why
 * a body condition never fires deserves to be told which one happened.
 */
sealed interface RequestBodyPeek {

    /** The body was read safely and completely enough to match against. */
    data class Text(val text: String) : RequestBodyPeek

    /** The request carries no body at all. */
    data object Absent : RequestBodyPeek

    /**
     * A body exists but NetKit will not read it.
     *
     * Never an error: it is the safe answer for a duplex or one-shot body that
     * can only be consumed once, a payload over
     * [io.devkit.netkit.config.NetKitLimits.MAX_CONDITION_BODY_BYTES], a binary
     * content type, or a writer that threw. Breaking an upload to evaluate a
     * debug condition would be a far worse outcome than the condition not firing.
     */
    data class Unavailable(val reason: Reason) : RequestBodyPeek {

        enum class Reason {
            /** A duplex or one-shot body that can only be written once. */
            NOT_REPEATABLE,

            /** Larger than the condition body limit. */
            TOO_LARGE,

            /** Not a textual content type. */
            BINARY,

            /** The body's own writer threw while NetKit was buffering it. */
            UNREADABLE,
            ;

            val label: String get() = when (this) {
                NOT_REPEATABLE -> "body not repeatable"
                TOO_LARGE -> "body too large to inspect"
                BINARY -> "body is not text"
                UNREADABLE -> "body could not be read"
            }
        }
    }

    /** Short description for the rule-evaluation diagnostics. */
    val diagnostic: String get() = when (this) {
        is Text -> "body read"
        Absent -> "no body"
        is Unavailable -> reason.label
    }
}

/**
 * Supplies the request body to a [BodyCondition], lazily and at most once.
 *
 * Lazy because the overwhelming majority of requests are evaluated by scenarios
 * with no body condition at all, and buffering every upload to serve the rare one
 * would be an unacceptable tax on the untouched path. At most once because
 * buffering is the expensive part and two body conditions on the same rule should
 * cost what one does — [ConditionContext] memoises the result.
 */
fun interface RequestBodySource {

    /** Reads up to [maxBytes] of the body, or explains why it will not. */
    fun peek(maxBytes: Long): RequestBodyPeek

    companion object {
        /** The source used when the transport offers no body access at all. */
        val Absent: RequestBodySource = RequestBodySource { RequestBodyPeek.Absent }
    }
}

/**
 * Everything a [RuleCondition] is allowed to look at.
 *
 * Bundled into one object rather than passed as five parameters so that adding a
 * condition kind in 0.4 does not change the signature every existing condition
 * implements. Consumers implementing their own conditions get a stable surface.
 *
 * Instances are per-request and are **not** shared across threads; the memoised
 * body is therefore a plain field rather than something synchronised.
 *
 * @param target the request being evaluated.
 * @param ruleHitIndex 1-based count of how many times the rule under evaluation
 *   has been reached in this run, *including this request*.
 * @param evaluationIndex the run-scoped evaluation number, for diagnostics.
 * @param hasSimulatedFailure whether any rule has already simulated a failure in
 *   this run.
 * @param executionsOf how many times a given rule id has executed in this run.
 * @param maxBodyBytes the ceiling [body] will buffer to.
 */
class ConditionContext(
    val target: RequestTarget,
    val ruleHitIndex: Long,
    val evaluationIndex: Long,
    val hasSimulatedFailure: Boolean,
    private val executionsOf: (String) -> Long,
    private val bodySource: RequestBodySource,
    private val maxBodyBytes: Long,
) {

    /** How many times the rule with [ruleId] has executed in this run. */
    fun executionsOf(ruleId: String): Long = executionsOf.invoke(ruleId)

    /** The value of [name], case-insensitively, or `null` when absent. */
    fun header(name: String): String? = target.header(name)

    /** The value of query parameter [name], or `null` when absent. */
    fun queryParameter(name: String): String? = target.queryParameters[name]

    /**
     * The request body, buffered at most once.
     *
     * The first condition that asks pays for the peek; every later one reads the
     * memoised answer.
     */
    val body: RequestBodyPeek by lazy(LazyThreadSafetyMode.NONE) {
        try {
            bodySource.peek(maxBodyBytes)
        } catch (error: Throwable) {
            // A body writer is application code and can throw anything. A failed
            // peek must cost a condition match, never the request.
            RequestBodyPeek.Unavailable(RequestBodyPeek.Unavailable.Reason.UNREADABLE)
        }
    }
}
