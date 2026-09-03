package io.devkit.netkit.scenario.condition

import io.devkit.netkit.config.NetKitLimits

/**
 * An extra requirement a request must meet before a rule may act on it.
 *
 * A NetKit 0.2 rule was `matcher + action`. A 0.3 rule is
 * `matcher + conditions + probability + action`, and this is the middle term.
 *
 * Conditions are **deterministic predicates**: given the same request and the
 * same run state they always answer the same way, and they never touch the random
 * stream. That separation is what keeps the engine explainable — when a rule does
 * not fire, the diagnostics can say whether a *fact about the request* ruled it
 * out or whether a *dice roll* did, and those are very different bugs.
 *
 * The interface is open rather than sealed so consumers and later releases can
 * add conditions without a breaking change. Implementations must be immutable and
 * thread-safe: [matches] runs on OkHttp's threads.
 */
interface RuleCondition {

    /** Short description shown in the rule editor and the timeline, e.g. `page = 2`. */
    val label: String

    /**
     * True when [context] satisfies this condition.
     *
     * Must never throw. A condition that cannot be evaluated — a body that is not
     * safely readable, a header list that was not captured — returns `false`
     * rather than guessing; see [ConditionContext.body].
     */
    fun matches(context: ConditionContext): Boolean

    /** True when this condition needs request headers to be captured. */
    val needsHeaders: Boolean get() = false

    /** True when this condition needs the request body to be peeked. */
    val needsBody: Boolean get() = false
}

// ---- request count ---------------------------------------------------------

/**
 * Fires based on how many times **this rule** has already been reached in the
 * current scenario run.
 *
 * The count is per rule and per run, not global: `/feed` being called ten times
 * does not advance the counter of a rule scoped to `/checkout`. It resets when
 * the run restarts, when the seed changes, and when the scenario is reactivated —
 * the same points everything else deterministic resets at.
 *
 * The index passed to a condition is **1-based and counts this reach**, so
 * `Exactly(1)` is "the first request" and reads the way it sounds.
 *
 * ### A rule counts the requests that reached *it*
 *
 * The counter advances when a rule's method and path claim a request, which means
 * a rule only ever counts requests that got as far as it. A rule sitting below
 * one that already handled request 1 never saw request 1, so its own first
 * request is the endpoint's second.
 *
 * The practical consequence: pairing `Exactly(1)` on one rule with `AtLeast(2)`
 * on the next does **not** partition the traffic — it leaves request 2 handled by
 * neither. To say "the first one fails and the rest work", leave the second rule
 * unconditional and let rule order do the work:
 *
 * ```text
 * GET /bookings   first request only  → HTTP 401
 * GET /bookings                       → HTTP 200
 * ```
 */
sealed interface RequestCountCondition : RuleCondition {

    /** Only the [index]-th request. `Exactly(1)` is "first request only". */
    data class Exactly(val index: Long) : RequestCountCondition {
        init {
            require(index >= 1) { "NetKit request index starts at 1 (was $index)" }
        }

        override val label: String get() = if (index == 1L) "first request only" else "request #$index"

        override fun matches(context: ConditionContext): Boolean = context.ruleHitIndex == index
    }

    /** Every request from the [count]-th onwards. */
    data class AtLeast(val count: Long) : RequestCountCondition {
        init {
            require(count >= 1) { "NetKit request count starts at 1 (was $count)" }
        }

        override val label: String get() = "request #$count onwards"

        override fun matches(context: ConditionContext): Boolean = context.ruleHitIndex >= count
    }

    /** Requests [from]..[to] inclusive. */
    data class Range(val from: Long, val to: Long) : RequestCountCondition {
        init {
            require(from >= 1) { "NetKit request index starts at 1 (was $from)" }
            require(to >= from) { "NetKit request range is inverted ($from > $to)" }
        }

        override val label: String get() = "requests $from–$to"

        override fun matches(context: ConditionContext): Boolean =
            context.ruleHitIndex in from..to
    }

    /**
     * Every [interval]-th request: `Every(4)` fires on 4, 8, 12…
     *
     * Note that `Every(1)` fires on every request, which is the same as having no
     * condition at all; it is allowed because it is a natural thing to type while
     * adjusting the number.
     */
    data class Every(val interval: Long) : RequestCountCondition {
        init {
            require(interval >= 1) {
                "NetKit 'every Nth request' interval must be at least 1 (was $interval)"
            }
        }

        override val label: String get() = "every ${interval.ordinalSuffix()} request"

        override fun matches(context: ConditionContext): Boolean =
            context.ruleHitIndex % interval == 0L
    }
}

/** `4` → `4th`, for the "every Nth request" label. */
private fun Long.ordinalSuffix(): String {
    if (this % 100L in 11L..13L) return "${this}th"
    return when (this % 10L) {
        1L -> "${this}st"
        2L -> "${this}nd"
        3L -> "${this}rd"
        else -> "${this}th"
    }
}

// ---- string comparison shared by header and query --------------------------

/** How a header or query-parameter condition compares its value. */
enum class StringMatch {
    /** The name is present, whatever its value. */
    EXISTS,

    /** The name is absent. */
    MISSING,

    /** The value equals the expected one, case-insensitively. */
    EQUALS,

    /** The value contains the expected one, case-insensitively. */
    CONTAINS,
    ;

    /** True when this comparison needs an expected value to compare against. */
    val needsValue: Boolean get() = this == EQUALS || this == CONTAINS

    val label: String get() = when (this) {
        EXISTS -> "exists"
        MISSING -> "is missing"
        EQUALS -> "equals"
        CONTAINS -> "contains"
    }

    internal fun test(actual: String?, expected: String): Boolean = when (this) {
        EXISTS -> actual != null
        MISSING -> actual == null
        EQUALS -> actual != null && actual.equals(expected, ignoreCase = true)
        CONTAINS -> actual != null && actual.contains(expected, ignoreCase = true)
    }
}

// ---- header ----------------------------------------------------------------

/**
 * Fires based on a request header.
 *
 * ### On matching credentials
 *
 * NetKit deliberately makes it awkward to key a scenario on a *literal* token
 * value. `Authorization exists` is the useful condition — "this is a
 * authenticated call" — and it is safe to save, export and paste into a ticket.
 * Pasting a real bearer token into an `EQUALS` condition would put a credential
 * into a `.netkit.json`, so [io.devkit.netkit.scenario.serialization.ScenarioExportSanitizer]
 * strips the value on export and the editor warns before you save one.
 *
 * @param name the header name, matched case-insensitively.
 * @param match how [value] is compared.
 * @param value the expected value; ignored for [StringMatch.EXISTS] and
 *   [StringMatch.MISSING].
 */
data class HeaderCondition(
    val name: String,
    val match: StringMatch = StringMatch.EXISTS,
    val value: String = "",
) : RuleCondition {

    init {
        require(name.isNotBlank()) { "NetKit header condition needs a header name" }
        require(!match.needsValue || value.isNotEmpty()) {
            "NetKit header condition '$name ${match.label}' needs a value to compare against"
        }
    }

    override val needsHeaders: Boolean get() = true

    override val label: String
        get() = if (match.needsValue) "$name ${match.label} $value" else "$name ${match.label}"

    override fun matches(context: ConditionContext): Boolean =
        match.test(context.header(name), value)
}

// ---- query parameter -------------------------------------------------------

/**
 * Fires based on a query parameter.
 *
 * The workhorse of the pagination presets: `page = 2` is how "fail the second
 * page" is expressed, without the interceptor needing to know anything about
 * pagination.
 *
 * Names are matched case-sensitively — unlike headers, query parameters are, and
 * an API with both `id` and `ID` is rare but real. Values are compared
 * case-insensitively, which is the forgiving choice for hand-typed input.
 *
 * @param name the parameter name.
 * @param match how [value] is compared.
 * @param value the expected value; ignored for [StringMatch.EXISTS] and
 *   [StringMatch.MISSING].
 */
data class QueryParameterCondition(
    val name: String,
    val match: StringMatch = StringMatch.EQUALS,
    val value: String = "",
) : RuleCondition {

    init {
        require(name.isNotBlank()) { "NetKit query condition needs a parameter name" }
        require(!match.needsValue || value.isNotEmpty()) {
            "NetKit query condition '$name ${match.label}' needs a value to compare against"
        }
    }

    override val label: String get() = when (match) {
        StringMatch.EQUALS -> "$name = $value"
        StringMatch.CONTAINS -> "$name contains $value"
        else -> "$name ${match.label}"
    }

    override fun matches(context: ConditionContext): Boolean =
        match.test(context.queryParameter(name), value)
}

// ---- body ------------------------------------------------------------------

/**
 * Fires based on the request body.
 *
 * ### Why this is the most restricted condition
 *
 * Reading a request body is the one inspection that can *break* the request being
 * inspected. A one-shot body can only be written once; a duplex body is a live
 * stream; a multi-megabyte upload should not be copied into memory to check
 * whether it says `"coupon"`. NetKit therefore never consumes the real body: the
 * interceptor offers a bounded, re-buffered peek and only for bodies that are
 * textual, non-duplex, non-one-shot and under
 * [NetKitLimits.MAX_CONDITION_BODY_BYTES].
 *
 * When the body cannot be read safely, this condition evaluates to **false** —
 * the rule does not fire — and the diagnostics record it as
 * `body unavailable` rather than `body did not match`, so a scenario that
 * silently stopped working is distinguishable from one that correctly did not
 * apply.
 *
 * @param text the substring to look for.
 * @param jsonField when set, the body is parsed as a flat JSON object and
 *   [text] is compared against that field's value instead of the whole payload.
 */
data class BodyCondition(
    val text: String,
    val jsonField: String? = null,
) : RuleCondition {

    init {
        require(text.isNotEmpty()) { "NetKit body condition needs something to look for" }
        require(text.length <= NetKitLimits.MAX_CONDITION_TEXT_LENGTH) {
            "NetKit body condition text cannot exceed " +
                "${NetKitLimits.MAX_CONDITION_TEXT_LENGTH} characters"
        }
        require(jsonField == null || jsonField.isNotBlank()) {
            "NetKit body condition JSON field cannot be blank"
        }
    }

    override val needsBody: Boolean get() = true

    override val label: String
        get() = if (jsonField == null) "body contains \"$text\"" else "body.$jsonField = $text"

    override fun matches(context: ConditionContext): Boolean {
        val body = (context.body as? RequestBodyPeek.Text)?.text ?: return false
        val field = jsonField ?: return body.contains(text, ignoreCase = true)
        return jsonFieldValue(body, field)?.equals(text, ignoreCase = true) == true
    }

    /**
     * Reads one top-level field out of a JSON object.
     *
     * Hand-rolled rather than pulling in a parser on the request path: this runs
     * per request, only ever needs a scalar at the top level, and must not throw
     * on input that is not JSON at all. Anything it cannot understand is a
     * non-match, which is the same answer a strict parser would give.
     */
    private fun jsonFieldValue(body: String, field: String): String? {
        val key = "\"$field\""
        var searchFrom = 0
        while (true) {
            val keyAt = body.indexOf(key, searchFrom)
            if (keyAt < 0) return null
            var cursor = keyAt + key.length
            while (cursor < body.length && body[cursor].isWhitespace()) cursor++
            if (cursor >= body.length || body[cursor] != ':') {
                searchFrom = keyAt + key.length
                continue
            }
            cursor++
            while (cursor < body.length && body[cursor].isWhitespace()) cursor++
            if (cursor >= body.length) return null
            return if (body[cursor] == '"') {
                readJsonString(body, cursor)
            } else {
                body.substring(cursor).takeWhile { it != ',' && it != '}' && !it.isWhitespace() }
                    .takeIf { it.isNotEmpty() }
            }
        }
    }

    /** Reads a quoted JSON string starting at [openQuote], honouring escapes. */
    private fun readJsonString(body: String, openQuote: Int): String? {
        val out = StringBuilder()
        var cursor = openQuote + 1
        while (cursor < body.length) {
            when (val char = body[cursor]) {
                '"' -> return out.toString()
                '\\' -> {
                    cursor++
                    if (cursor >= body.length) return null
                    when (val escaped = body[cursor]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            if (cursor + 4 >= body.length) return null
                            val hex = body.substring(cursor + 1, cursor + 5)
                            out.append(hex.toIntOrNull(16)?.toChar() ?: return null)
                            cursor += 4
                        }

                        else -> out.append(escaped)
                    }
                }

                else -> out.append(char)
            }
            cursor++
        }
        return null
    }
}

// ---- previous result -------------------------------------------------------

/** What earlier behaviour in this run a [PreviousResultCondition] looks for. */
enum class PreviousResult {
    /** Any rule in this run has already produced a simulated failure. */
    AFTER_SIMULATED_FAILURE,

    /** No rule in this run has produced a simulated failure yet. */
    BEFORE_ANY_FAILURE,
    ;

    val label: String get() = when (this) {
        AFTER_SIMULATED_FAILURE -> "after a simulated failure"
        BEFORE_ANY_FAILURE -> "before any failure"
    }
}

/**
 * Fires based on what has already happened in this run.
 *
 * Intentionally tiny. The useful cases are "the second refresh fails after the
 * first succeeded" and "only start misbehaving once something else has already
 * broken", and both are expressible with a rule id and a failure flag. NetKit
 * does not have, and is not going to grow, a scripting language: anything more
 * expressive than this belongs in a test, not in a debug console.
 *
 * @param requirement a run-wide failure condition, or `null` to only check [ruleId].
 * @param ruleId when set, requires that rule to have fired at least [minimumHits]
 *   times already.
 * @param minimumHits how many times [ruleId] must have fired.
 */
data class PreviousResultCondition(
    val requirement: PreviousResult? = null,
    val ruleId: String? = null,
    val ruleLabel: String? = null,
    val minimumHits: Long = 1,
) : RuleCondition {

    init {
        require(requirement != null || ruleId != null) {
            "NetKit previous-result condition needs either a requirement or a rule to watch"
        }
        require(minimumHits >= 1) { "NetKit minimum hits must be at least 1 (was $minimumHits)" }
    }

    override val label: String get() = buildList {
        requirement?.let { add(it.label) }
        if (ruleId != null) {
            val name = ruleLabel ?: "another rule"
            add(if (minimumHits == 1L) "$name has fired" else "$name has fired $minimumHits times")
        }
    }.joinToString(" and ")

    override fun matches(context: ConditionContext): Boolean {
        requirement?.let { required ->
            val satisfied = when (required) {
                PreviousResult.AFTER_SIMULATED_FAILURE -> context.hasSimulatedFailure
                PreviousResult.BEFORE_ANY_FAILURE -> !context.hasSimulatedFailure
            }
            if (!satisfied) return false
        }
        ruleId?.let { watched ->
            if (context.executionsOf(watched) < minimumHits) return false
        }
        return true
    }
}

// ---- composition -----------------------------------------------------------

/**
 * True when **every** nested condition is true.
 *
 * `AND` is the composition a rule's condition list already implies, so this type
 * exists mainly so an `OR` can contain a group. Empty means true, which keeps
 * "no conditions" and "an empty `AND`" from meaning different things.
 */
data class AllOf(val conditions: List<RuleCondition>) : RuleCondition {

    override val needsHeaders: Boolean get() = conditions.any { it.needsHeaders }
    override val needsBody: Boolean get() = conditions.any { it.needsBody }

    override val label: String get() = conditions.joinToString(" and ") { it.label }

    override fun matches(context: ConditionContext): Boolean =
        conditions.all { it.matches(context) }
}

/**
 * True when **any** nested condition is true.
 *
 * Offered because it was cheap and because "page 2 or page 3 fails" is a real
 * request. Deliberately not generalised into a boolean expression language with
 * negation and precedence — a debug console that needs a grammar reference has
 * lost the plot. Empty means false.
 */
data class AnyOf(val conditions: List<RuleCondition>) : RuleCondition {

    override val needsHeaders: Boolean get() = conditions.any { it.needsHeaders }
    override val needsBody: Boolean get() = conditions.any { it.needsBody }

    override val label: String get() = conditions.joinToString(" or ") { it.label }

    override fun matches(context: ConditionContext): Boolean =
        conditions.isNotEmpty() && conditions.any { it.matches(context) }
}
