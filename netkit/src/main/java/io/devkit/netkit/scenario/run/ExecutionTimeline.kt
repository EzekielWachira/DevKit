package io.devkit.netkit.scenario.run

import io.devkit.netkit.config.NetKitLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What kind of thing happened during a run.
 *
 * The timeline records **scenario decisions**, which is a different question from
 * the one history answers. History says "`GET /bookings` returned 503 in 2140ms".
 * The timeline says "the chaos evaluator drew 0.07 against a 15% failure rate and
 * chose HTTP 503". When a scenario is not behaving as expected, the second is the
 * one that explains it.
 */
enum class ExecutionEventType {
    /** A run began. */
    RUN_STARTED,

    /** A run was restarted, on the same seed or a new one. */
    RUN_RESTARTED,

    /** Runtime counters and cursors were cleared without a new run. */
    RUN_RESET,

    /** A rule matched a request's method and path. */
    RULE_MATCHED,

    /** A rule matched but a condition ruled it out. */
    RULE_SKIPPED_CONDITION,

    /** A rule matched and its conditions passed, but the probability did not. */
    RULE_SKIPPED_PROBABILITY,

    /** A rule produced a decision. */
    ACTION_EXECUTED,

    /** A response sequence moved to its next step. */
    SEQUENCE_ADVANCED,

    /** Chaos mode acted on a request. */
    CHAOS_ACTION,

    /** Chaos considered a request and let it through untouched. */
    CHAOS_PASS_THROUGH,
    ;

    val label: String get() = when (this) {
        RUN_STARTED -> "Run started"
        RUN_RESTARTED -> "Run restarted"
        RUN_RESET -> "Run reset"
        RULE_MATCHED -> "Rule matched"
        RULE_SKIPPED_CONDITION -> "Condition failed"
        RULE_SKIPPED_PROBABILITY -> "Probability failed"
        ACTION_EXECUTED -> "Action executed"
        SEQUENCE_ADVANCED -> "Sequence advanced"
        CHAOS_ACTION -> "Chaos"
        CHAOS_PASS_THROUGH -> "Chaos pass through"
    }

    /** True when this event represents something being simulated. */
    val isSimulation: Boolean
        get() = this == ACTION_EXECUTED || this == CHAOS_ACTION
}

/**
 * One decision, recorded for the timeline.
 *
 * ### What is deliberately not here
 *
 * A timeline event is designed to be **copied into a bug ticket**, so it carries
 * only what is safe to paste: the method, a masked path, which rule was involved,
 * what was decided, and the numbers behind that decision. It never carries
 * headers, cookies, tokens, request bodies or response bodies. That is not a
 * default that can be turned up — there is no setting that puts a credential into
 * a trace, because the trace's whole value is that it can be attached to a ticket
 * without anyone having to check it first.
 *
 * @param evaluationIndex the run-scoped evaluation number this belongs to; `0`
 *   for lifecycle events that belong to no request.
 * @param atMillis wall clock, for the timestamp column.
 * @param type what kind of event this is.
 * @param method the request's verb, or `null` for a lifecycle event.
 * @param path the request path, already masked.
 * @param ruleLabel the rule or chaos responsible.
 * @param detail the decision in a few words, e.g. `HTTP 503` or `Delay 2140ms`.
 * @param reason why a rule was skipped, or the draw behind a decision.
 */
data class ExecutionEvent(
    val evaluationIndex: Long,
    val atMillis: Long,
    val type: ExecutionEventType,
    val method: String? = null,
    val path: String? = null,
    val ruleLabel: String? = null,
    val detail: String? = null,
    val reason: String? = null,
) {
    /** `#17 GET /api/v1/bookings` */
    val requestSummary: String?
        get() = if (method == null || path == null) null else "$method $path"

    /** One line, as the trace export writes it. */
    val traceLine: String
        get() = buildString {
            if (evaluationIndex > 0) append("#$evaluationIndex ")
            requestSummary?.let { append(it).append(' ') }
            append(type.label.lowercase())
            ruleLabel?.let { append(" · ").append(it) }
            detail?.let { append(" · ").append(it) }
            reason?.let { append(" · ").append(it) }
        }
}

/**
 * A bounded, in-memory record of a run's decisions.
 *
 * ### Bounded, and not persisted
 *
 * The timeline keeps the most recent [NetKitLimits.MAX_EXECUTION_EVENTS] events
 * and nothing else. It does not survive process death, and it is not written to
 * disk: a debug tool that quietly accumulated a log of every request an app made
 * would be a liability, and the workflow it would serve — "the bug happened
 * yesterday" — is served properly by exporting a reproduction while the run is
 * still live.
 *
 * ### Cost when nobody is looking
 *
 * Recording is skipped entirely when the timeline is disabled, and the check is
 * the first thing [record] does. When enabled, each event costs one append and
 * one list copy bounded by the cap.
 *
 * Thread-safe: written from OkHttp's threads, read from Compose.
 */
class ExecutionTimeline(
    private val maxEvents: Int = NetKitLimits.MAX_EXECUTION_EVENTS,
    enabled: Boolean = true,
) {
    init {
        require(maxEvents > 0) { "NetKit timeline limit must be positive (was $maxEvents)" }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<ExecutionEvent>()
    private val _events = MutableStateFlow<List<ExecutionEvent>>(emptyList())

    /** Whether events are recorded at all. */
    @Volatile
    var enabled: Boolean = enabled

    /** Oldest first, so the timeline reads top to bottom like a log. */
    val events: StateFlow<List<ExecutionEvent>> = _events.asStateFlow()

    /** Appends [event], evicting the oldest once the cap is reached. */
    fun record(event: ExecutionEvent) {
        if (!enabled) return
        synchronized(lock) {
            buffer.addLast(event)
            while (buffer.size > maxEvents) buffer.removeFirst()
            // Published inside the lock for the same reason the history store
            // does it: two threads that each built a snapshot and then raced to
            // publish would otherwise drop the newer one.
            _events.value = buffer.toList()
        }
    }

    /** Drops every event. Called whenever a run starts or restarts. */
    fun clear() {
        synchronized(lock) {
            if (buffer.isEmpty() && _events.value.isEmpty()) return
            buffer.clear()
            _events.value = emptyList()
        }
    }

    /** The events as of now, oldest first. */
    fun snapshot(): List<ExecutionEvent> = _events.value
}
