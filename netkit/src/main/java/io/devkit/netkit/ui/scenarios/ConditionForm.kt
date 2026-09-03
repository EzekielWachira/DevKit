package io.devkit.netkit.ui.scenarios

import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.masking.DefaultSensitiveDataMasker
import io.devkit.netkit.scenario.condition.BodyCondition
import io.devkit.netkit.scenario.condition.HeaderCondition
import io.devkit.netkit.scenario.condition.PreviousResult
import io.devkit.netkit.scenario.condition.PreviousResultCondition
import io.devkit.netkit.scenario.condition.QueryParameterCondition
import io.devkit.netkit.scenario.condition.RequestCountCondition
import io.devkit.netkit.scenario.condition.RuleCondition
import io.devkit.netkit.scenario.condition.StringMatch

/** The kinds of condition the editor can build, in display order. */
internal enum class ConditionKind(val label: String, val hint: String) {
    REQUEST_COUNT("Request count", "Fire only on certain attempts"),
    QUERY("Query", "Match a query parameter, e.g. page = 2"),
    HEADER("Header", "Match a request header"),
    BODY("Body", "Match text in the request body"),
    PREVIOUS_RESULT("Previous result", "Depend on what already happened this run"),
    ;

    companion object {
        fun of(condition: RuleCondition): ConditionKind? = when (condition) {
            is RequestCountCondition -> REQUEST_COUNT
            is QueryParameterCondition -> QUERY
            is HeaderCondition -> HEADER
            is BodyCondition -> BODY
            is PreviousResultCondition -> PREVIOUS_RESULT
            else -> null
        }
    }
}

/** Which shape of request-count condition is being edited. */
internal enum class CountMode(val label: String) {
    EXACTLY("Exactly"),
    AT_LEAST("At least"),
    RANGE("Range"),
    EVERY("Every Nth"),
}

/**
 * One editable rule condition.
 *
 * A single flat form covering every condition kind, rather than five forms behind
 * a sealed hierarchy. That is a deliberate trade: the union carries a few fields
 * that are irrelevant for any given kind, and in exchange switching a condition
 * from "query" to "header" in the UI keeps what you already typed, the state is
 * trivially copyable for Compose, and there is one place where validation lives.
 *
 * Immutable and free of Compose, so every rule here is unit-testable.
 *
 * @param kind which condition this is.
 * @param name the header or query-parameter name.
 * @param match how [value] is compared.
 * @param value the expected value.
 * @param countMode which shape of request-count condition.
 * @param fromText the index, count, or range start.
 * @param toText the range end.
 * @param intervalText the "every Nth" interval.
 * @param jsonField an optional top-level JSON field for a body condition.
 * @param previousResult the run-wide requirement for a previous-result condition.
 */
internal data class ConditionForm(
    val kind: ConditionKind = ConditionKind.QUERY,
    val name: String = "",
    val match: StringMatch = StringMatch.EQUALS,
    val value: String = "",
    val countMode: CountMode = CountMode.EXACTLY,
    val fromText: String = "1",
    val toText: String = "3",
    val intervalText: String = "2",
    val jsonField: String = "",
    val previousResult: PreviousResult = PreviousResult.AFTER_SIMULATED_FAILURE,
) {

    /** Validation message, or `null` when this condition can be built. */
    val error: String?
        get() = when (kind) {
            ConditionKind.REQUEST_COUNT -> countError
            ConditionKind.QUERY, ConditionKind.HEADER -> namedError
            ConditionKind.BODY -> bodyError
            ConditionKind.PREVIOUS_RESULT -> null
        }

    val isValid: Boolean get() = error == null

    /**
     * A warning that does not block saving.
     *
     * The one case that matters: matching a credential-bearing header by literal
     * value puts that value into the scenario, and from there into any export.
     * NetKit strips it on the way out, but the honest thing is to say so before
     * someone pastes a production token into a debug tool.
     */
    val warning: String?
        get() {
            if (kind != ConditionKind.HEADER || !match.needsValue) return null
            if (name.lowercase() !in SENSITIVE_HEADERS) return null
            return "Matching the value of \"$name\" stores a credential in this scenario. " +
                "NetKit removes it on export, but \"exists\" is usually what you want."
        }

    private val countError: String?
        get() = when (countMode) {
            CountMode.EXACTLY, CountMode.AT_LEAST -> positive(fromText, "a request number")
            CountMode.EVERY -> positive(intervalText, "an interval")
            CountMode.RANGE -> {
                val from = fromText.trim().toLongOrNull()
                val to = toText.trim().toLongOrNull()
                when {
                    from == null || to == null -> "Enter whole numbers"
                    from < 1 -> "Request numbers start at 1"
                    to < from -> "The range cannot end before it starts"
                    else -> null
                }
            }
        }

    private val namedError: String?
        get() = when {
            name.isBlank() -> if (kind == ConditionKind.HEADER) {
                "Enter a header name"
            } else {
                "Enter a parameter name"
            }

            match.needsValue && value.isEmpty() -> "Enter a value to compare against"
            value.length > NetKitLimits.MAX_CONDITION_TEXT_LENGTH ->
                "That value is too long (max ${NetKitLimits.MAX_CONDITION_TEXT_LENGTH})"

            else -> null
        }

    private val bodyError: String?
        get() = when {
            value.isEmpty() -> "Enter the text to look for"
            value.length > NetKitLimits.MAX_CONDITION_TEXT_LENGTH ->
                "That text is too long (max ${NetKitLimits.MAX_CONDITION_TEXT_LENGTH})"

            else -> null
        }

    private fun positive(text: String, what: String): String? {
        val parsed = text.trim().toLongOrNull() ?: return "Enter a whole number"
        return if (parsed < 1) "Enter $what of 1 or more" else null
    }

    /** Builds the condition, or `null` when the form is not valid. */
    fun toCondition(): RuleCondition? {
        if (!isValid) return null
        return when (kind) {
            ConditionKind.REQUEST_COUNT -> {
                val from = fromText.trim().toLongOrNull()?.coerceAtLeast(1) ?: 1
                when (countMode) {
                    CountMode.EXACTLY -> RequestCountCondition.Exactly(from)
                    CountMode.AT_LEAST -> RequestCountCondition.AtLeast(from)
                    CountMode.RANGE -> RequestCountCondition.Range(
                        from = from,
                        to = (toText.trim().toLongOrNull() ?: from).coerceAtLeast(from),
                    )

                    CountMode.EVERY -> RequestCountCondition.Every(
                        intervalText.trim().toLongOrNull()?.coerceAtLeast(1) ?: 1,
                    )
                }
            }

            ConditionKind.QUERY -> QueryParameterCondition(name.trim(), match, value)
            ConditionKind.HEADER -> HeaderCondition(name.trim(), match, value)
            ConditionKind.BODY -> BodyCondition(value, jsonField.trim().takeIf { it.isNotBlank() })
            ConditionKind.PREVIOUS_RESULT -> PreviousResultCondition(requirement = previousResult)
        }
    }

    companion object {

        /** A blank form of [kind], pre-filled with the most common shape. */
        fun blank(kind: ConditionKind): ConditionForm = when (kind) {
            ConditionKind.QUERY -> ConditionForm(kind = kind, name = "page", value = "2")
            ConditionKind.HEADER -> ConditionForm(
                kind = kind,
                name = "Authorization",
                match = StringMatch.EXISTS,
            )

            ConditionKind.BODY -> ConditionForm(kind = kind, match = StringMatch.CONTAINS)
            else -> ConditionForm(kind = kind)
        }

        /**
         * Loads an existing condition, or `null` for one the editor cannot show.
         *
         * A consumer-supplied condition, or an `allOf`/`anyOf` group, comes back
         * `null` — see [RuleEditorState.unsupportedConditions] for how the editor
         * keeps those rather than silently dropping them on save.
         */
        fun from(condition: RuleCondition): ConditionForm? = when (condition) {
            is RequestCountCondition.Exactly -> ConditionForm(
                kind = ConditionKind.REQUEST_COUNT,
                countMode = CountMode.EXACTLY,
                fromText = condition.index.toString(),
            )

            is RequestCountCondition.AtLeast -> ConditionForm(
                kind = ConditionKind.REQUEST_COUNT,
                countMode = CountMode.AT_LEAST,
                fromText = condition.count.toString(),
            )

            is RequestCountCondition.Range -> ConditionForm(
                kind = ConditionKind.REQUEST_COUNT,
                countMode = CountMode.RANGE,
                fromText = condition.from.toString(),
                toText = condition.to.toString(),
            )

            is RequestCountCondition.Every -> ConditionForm(
                kind = ConditionKind.REQUEST_COUNT,
                countMode = CountMode.EVERY,
                intervalText = condition.interval.toString(),
            )

            is QueryParameterCondition -> ConditionForm(
                kind = ConditionKind.QUERY,
                name = condition.name,
                match = condition.match,
                value = condition.value,
            )

            is HeaderCondition -> ConditionForm(
                kind = ConditionKind.HEADER,
                name = condition.name,
                match = condition.match,
                value = condition.value,
            )

            is BodyCondition -> ConditionForm(
                kind = ConditionKind.BODY,
                match = StringMatch.CONTAINS,
                value = condition.text,
                jsonField = condition.jsonField.orEmpty(),
            )

            is PreviousResultCondition -> condition.requirement?.let {
                // A condition that also watches a specific rule cannot be shown
                // by this form; treating it as unsupported preserves it verbatim
                // rather than saving back a weaker version of it.
                if (condition.ruleId != null) {
                    null
                } else {
                    ConditionForm(kind = ConditionKind.PREVIOUS_RESULT, previousResult = it)
                }
            }

            else -> null
        }

        /** Comparisons a header condition offers. */
        val headerMatches: List<StringMatch> = StringMatch.entries

        /** Comparisons a query condition offers. */
        val queryMatches: List<StringMatch> = StringMatch.entries

        private val SENSITIVE_HEADERS: Set<String> =
            DefaultSensitiveDataMasker.DEFAULT_HEADER_NAMES.mapTo(mutableSetOf()) { it.lowercase() }
    }
}
