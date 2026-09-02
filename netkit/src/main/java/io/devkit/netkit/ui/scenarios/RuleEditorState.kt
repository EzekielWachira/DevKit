package io.devkit.netkit.ui.scenarios

import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.scenario.EndpointMatcher
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.MalformedResponseType
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.ResponseHeader
import io.devkit.netkit.scenario.SequenceCompletionBehavior
import io.devkit.netkit.scenario.SequenceStep
import io.devkit.netkit.scenario.TimeoutType

/** The behaviours the rule editor can build, in display order. */
internal enum class RuleBehavior(val label: String) {
    PASS_THROUGH("Normal"),
    DELAY("Delay"),
    RESPONSE("Response"),
    MALFORMED("Malformed"),
    SEQUENCE("Sequence"),
    OFFLINE("Offline"),
    TIMEOUT("Timeout"),
    ;

    companion object {
        fun of(action: NetworkAction): RuleBehavior = when (action) {
            NetworkAction.PassThrough -> PASS_THROUGH
            is NetworkAction.Delay -> DELAY
            is NetworkAction.ReturnResponse -> RESPONSE
            is NetworkAction.Malformed -> MALFORMED
            is NetworkAction.Sequence -> SEQUENCE
            NetworkAction.Offline -> OFFLINE
            is NetworkAction.Timeout -> TIMEOUT
        }
    }
}

/**
 * One editable step of a response sequence.
 *
 * A sequence step is a whole behaviour minus the ones that make no sense inside
 * a sequence, so it reuses the same fields as the outer form rather than
 * introducing a second, subtly different editor.
 */
internal data class SequenceStepForm(
    val behavior: RuleBehavior = RuleBehavior.RESPONSE,
    val statusText: String = "500",
    val bodyText: String = "",
    val contentType: String = NetworkAction.ReturnResponse.DEFAULT_CONTENT_TYPE,
    val delayText: String = "0",
    val timeoutType: TimeoutType = TimeoutType.READ,
    val malformedType: MalformedResponseType = MalformedResponseType.InvalidJson,
) {
    /** `HTTP 500` — the label shown next to the step number. */
    val label: String get() = when (behavior) {
        RuleBehavior.PASS_THROUGH -> "Pass through"
        RuleBehavior.DELAY -> "Delay ${delayText.trim().ifEmpty { "0" }}ms"
        RuleBehavior.RESPONSE -> "HTTP ${statusText.trim().ifEmpty { "?" }}"
        RuleBehavior.MALFORMED -> malformedType.label
        RuleBehavior.OFFLINE -> "Offline"
        RuleBehavior.TIMEOUT -> timeoutType.label
        // A nested sequence is not offered by the UI; guard anyway.
        RuleBehavior.SEQUENCE -> "Unsupported"
    }

    val statusError: String?
        get() = if (behavior == RuleBehavior.RESPONSE) statusErrorFor(statusText) else null

    val isValid: Boolean get() = statusError == null && behavior != RuleBehavior.SEQUENCE

    fun toAction(): NetworkAction? {
        if (!isValid) return null
        val delay = delayText.trim().toLongOrNull()?.coerceAtLeast(0) ?: 0
        return when (behavior) {
            RuleBehavior.PASS_THROUGH -> NetworkAction.PassThrough
            RuleBehavior.DELAY -> NetworkAction.Delay(delay)
            RuleBehavior.RESPONSE -> NetworkAction.ReturnResponse(
                statusCode = statusText.trim().toInt(),
                body = bodyText.takeIf { it.isNotBlank() },
                contentType = contentType.ifBlank {
                    NetworkAction.ReturnResponse.DEFAULT_CONTENT_TYPE
                },
                delayMillis = delay,
            )

            RuleBehavior.MALFORMED -> NetworkAction.Malformed(
                type = malformedType,
                statusCode = statusText.trim().toIntOrNull()?.takeIf { it in STATUS_RANGE } ?: 200,
                delayMillis = delay,
            )

            RuleBehavior.OFFLINE -> NetworkAction.Offline
            RuleBehavior.TIMEOUT -> NetworkAction.Timeout(timeoutType)
            RuleBehavior.SEQUENCE -> null
        }
    }

    companion object {
        /** The behaviours a sequence step may use — everything but a sequence. */
        val allowed: List<RuleBehavior> = RuleBehavior.entries - RuleBehavior.SEQUENCE

        fun from(action: NetworkAction): SequenceStepForm = SequenceStepForm(
            behavior = RuleBehavior.of(action),
            statusText = when (action) {
                is NetworkAction.ReturnResponse -> action.statusCode.toString()
                is NetworkAction.Malformed -> action.statusCode.toString()
                else -> "500"
            },
            bodyText = (action as? NetworkAction.ReturnResponse)?.body.orEmpty(),
            contentType = (action as? NetworkAction.ReturnResponse)?.contentType
                ?: NetworkAction.ReturnResponse.DEFAULT_CONTENT_TYPE,
            delayText = when (action) {
                is NetworkAction.Delay -> action.delayMillis.toString()
                is NetworkAction.ReturnResponse -> action.delayMillis.toString()
                is NetworkAction.Malformed -> action.delayMillis.toString()
                else -> "0"
            },
            timeoutType = (action as? NetworkAction.Timeout)?.type ?: TimeoutType.READ,
            malformedType = (action as? NetworkAction.Malformed)?.type
                ?: MalformedResponseType.InvalidJson,
        )
    }
}

/** One editable custom response header. */
internal data class HeaderForm(val name: String = "", val value: String = "") {
    val isBlank: Boolean get() = name.isBlank() && value.isBlank()

    val error: String?
        get() = when {
            isBlank -> null
            name.isBlank() -> "A header needs a name"
            name.any { it == ':' || it == '\n' || it == '\r' } -> "A header name cannot contain ':'"
            value.any { it == '\n' || it == '\r' } -> "A header value cannot span lines"
            else -> null
        }

    fun toHeader(): ResponseHeader? =
        if (isBlank || error != null) null else ResponseHeader(name.trim(), value)
}

/**
 * The editable form behind the "add / edit endpoint rule" sheet.
 *
 * A plain immutable value class rather than a ViewModel: it holds only text and
 * selections, validates itself, and knows how to become an [EndpointRule]. That
 * keeps every validation rule unit-testable without Compose, and keeps the
 * composable a pure rendering of this state.
 *
 * The same form serves temporary overrides and rules inside a saved scenario —
 * a rule is a rule, and duplicating the editor for the two contexts would mean
 * two places to fix every validation bug.
 *
 * @param ruleId non-null when editing an existing rule.
 * @param pathText raw user input; validated by [pathError].
 * @param matcherOverride preserved when editing a rule built with a matcher the
 *   editor cannot express, so saving does not silently downgrade it.
 */
internal data class RuleEditorState(
    val ruleId: String? = null,
    val name: String = "",
    val method: HttpMethod = HttpMethod.ANY,
    val pathText: String = "",
    val behavior: RuleBehavior = RuleBehavior.RESPONSE,
    val statusText: String = "500",
    val bodyText: String = "",
    val contentType: String = NetworkAction.ReturnResponse.DEFAULT_CONTENT_TYPE,
    val headers: List<HeaderForm> = emptyList(),
    val delayText: String = "0",
    val timeoutType: TimeoutType = TimeoutType.READ,
    val malformedType: MalformedResponseType = MalformedResponseType.InvalidJson,
    val steps: List<SequenceStepForm> = emptyList(),
    val completion: SequenceCompletionBehavior = SequenceCompletionBehavior.REPEAT_LAST,
    val enabled: Boolean = true,
    val matcherOverride: EndpointMatcher? = null,
) {
    val isEditing: Boolean get() = ruleId != null

    /** Validation message for the path field, or `null` when it is acceptable. */
    val pathError: String?
        get() {
            if (matcherOverride != null) return null
            val trimmed = pathText.trim()
            return when {
                trimmed.isEmpty() -> "Enter a path, for example /api/v1/bookings"
                trimmed.contains(' ') -> "A path cannot contain spaces"
                trimmed.contains('?') -> "Enter the path only, without a query string"
                else -> null
            }
        }

    /** Validation message for the status field, or `null`. */
    val statusError: String?
        get() = when (behavior) {
            RuleBehavior.RESPONSE, RuleBehavior.MALFORMED -> statusErrorFor(statusText)
            else -> null
        }

    /** Validation message for the delay field, or `null`. */
    val delayError: String?
        get() {
            if (behavior !in DELAY_BEHAVIORS) return null
            val raw = delayText.trim()
            if (raw.isEmpty()) return null
            val parsed = raw.toLongOrNull() ?: return "Enter a whole number of milliseconds"
            return if (parsed < 0) "Delay cannot be negative" else null
        }

    /** Validation message for the response body, or `null`. */
    val bodyError: String?
        get() {
            if (behavior != RuleBehavior.RESPONSE) return null
            val bytes = bodyText.toByteArray(Charsets.UTF_8).size
            return if (bytes > NetKitLimits.MAX_BODY_BYTES) {
                "A response body cannot exceed ${NetKitLimits.MAX_BODY_BYTES / 1024} KB"
            } else {
                null
            }
        }

    /** Validation message for the sequence, or `null`. */
    val sequenceError: String?
        get() {
            if (behavior != RuleBehavior.SEQUENCE) return null
            return when {
                steps.isEmpty() -> "Add at least one step"
                steps.size > NetKitLimits.MAX_SEQUENCE_STEPS ->
                    "A sequence cannot exceed ${NetKitLimits.MAX_SEQUENCE_STEPS} steps"

                steps.any { !it.isValid } -> "One of the steps has an invalid status"
                else -> null
            }
        }

    /** Validation message for the custom headers, or `null`. */
    val headerError: String?
        get() {
            if (behavior != RuleBehavior.RESPONSE) return null
            if (headers.count { !it.isBlank } > NetKitLimits.MAX_RESPONSE_HEADERS) {
                return "A response cannot carry more than " +
                    "${NetKitLimits.MAX_RESPONSE_HEADERS} headers"
            }
            return headers.firstNotNullOfOrNull { it.error }
        }

    /** True when [toRule] will succeed. */
    val isValid: Boolean
        get() = pathError == null &&
            statusError == null &&
            delayError == null &&
            bodyError == null &&
            sequenceError == null &&
            headerError == null

    private val delayMillis: Long get() = delayText.trim().toLongOrNull()?.coerceAtLeast(0) ?: 0

    /** Builds the rule, or returns `null` when the form is not valid. */
    fun toRule(): EndpointRule? {
        if (!isValid) return null
        val action = when (behavior) {
            RuleBehavior.PASS_THROUGH -> NetworkAction.PassThrough

            RuleBehavior.DELAY -> NetworkAction.Delay(delayMillis)

            RuleBehavior.RESPONSE -> NetworkAction.ReturnResponse(
                statusCode = statusText.trim().toInt(),
                body = bodyText.takeIf { it.isNotBlank() },
                contentType = contentType.ifBlank {
                    NetworkAction.ReturnResponse.DEFAULT_CONTENT_TYPE
                },
                headers = headers.mapNotNull(HeaderForm::toHeader),
                delayMillis = delayMillis,
            )

            RuleBehavior.MALFORMED -> NetworkAction.Malformed(
                type = malformedType,
                statusCode = statusText.trim().toIntOrNull()?.takeIf { it in STATUS_RANGE } ?: 200,
                delayMillis = delayMillis,
            )

            RuleBehavior.SEQUENCE -> NetworkAction.Sequence(
                steps = steps.mapNotNull { form -> form.toAction()?.let(::SequenceStep) },
                completion = completion,
            )

            RuleBehavior.OFFLINE -> NetworkAction.Offline

            RuleBehavior.TIMEOUT -> NetworkAction.Timeout(timeoutType)
        }
        return EndpointRule(
            id = ruleId ?: EndpointRule.nextId(),
            name = name.trim().takeIf { it.isNotBlank() },
            enabled = enabled,
            method = method,
            matcher = matcherOverride ?: EndpointMatcher.ExactPath(pathText.trim()),
            action = action,
        )
    }

    /** Appends a step pre-filled with a sensible next behaviour. */
    fun withNewStep(): RuleEditorState = copy(
        steps = steps + (steps.lastOrNull() ?: SequenceStepForm()),
    )

    /** Moves the step at [index] one place towards the front. */
    fun withStepMovedUp(index: Int): RuleEditorState {
        if (index <= 0 || index >= steps.size) return this
        return copy(
            steps = steps.toMutableList().apply { add(index - 1, removeAt(index)) },
        )
    }

    /** Moves the step at [index] one place towards the back. */
    fun withStepMovedDown(index: Int): RuleEditorState {
        if (index < 0 || index >= steps.size - 1) return this
        return copy(
            steps = steps.toMutableList().apply { add(index + 1, removeAt(index)) },
        )
    }

    fun withStepRemoved(index: Int): RuleEditorState =
        if (index !in steps.indices) this else copy(steps = steps.filterIndexed { i, _ -> i != index })

    fun withStep(index: Int, step: SequenceStepForm): RuleEditorState =
        if (index !in steps.indices) {
            this
        } else {
            copy(steps = steps.toMutableList().apply { set(index, step) })
        }

    companion object {
        /** A blank form for the "add rule" flow. */
        fun new(): RuleEditorState = RuleEditorState()

        /** Loads an existing rule into the form. */
        fun from(rule: EndpointRule): RuleEditorState {
            val action = rule.action
            val exactPath = rule.matcher as? EndpointMatcher.ExactPath
            val response = action as? NetworkAction.ReturnResponse
            val sequence = action as? NetworkAction.Sequence
            return RuleEditorState(
                ruleId = rule.id,
                name = rule.name.orEmpty(),
                method = rule.method,
                pathText = exactPath?.path ?: rule.matcher.label,
                behavior = RuleBehavior.of(action),
                statusText = when (action) {
                    is NetworkAction.ReturnResponse -> action.statusCode.toString()
                    is NetworkAction.Malformed -> action.statusCode.toString()
                    else -> "500"
                },
                bodyText = response?.body.orEmpty(),
                contentType = response?.contentType
                    ?: NetworkAction.ReturnResponse.DEFAULT_CONTENT_TYPE,
                headers = response?.headers.orEmpty().map { HeaderForm(it.name, it.value) },
                delayText = when (action) {
                    is NetworkAction.Delay -> action.delayMillis.toString()
                    is NetworkAction.ReturnResponse -> action.delayMillis.toString()
                    is NetworkAction.Malformed -> action.delayMillis.toString()
                    else -> "0"
                },
                timeoutType = (action as? NetworkAction.Timeout)?.type ?: TimeoutType.READ,
                malformedType = (action as? NetworkAction.Malformed)?.type
                    ?: MalformedResponseType.InvalidJson,
                steps = sequence?.steps?.map { SequenceStepForm.from(it.action) }.orEmpty(),
                completion = sequence?.completion ?: SequenceCompletionBehavior.REPEAT_LAST,
                enabled = rule.enabled,
                matcherOverride = if (exactPath == null) rule.matcher else null,
            )
        }

        /** Status codes offered as one-tap chips. */
        val statusChips: List<Int> =
            NetKitDefaults.COMMON_SUCCESS_CODES + NetKitDefaults.COMMON_STATUS_CODES
    }
}

private val STATUS_RANGE =
    NetworkAction.ReturnResponse.MIN_STATUS..NetworkAction.ReturnResponse.MAX_STATUS

private val DELAY_BEHAVIORS = setOf(
    RuleBehavior.DELAY,
    RuleBehavior.RESPONSE,
    RuleBehavior.MALFORMED,
)

/** Shared status validation, so the rule form and a sequence step agree. */
private fun statusErrorFor(text: String): String? {
    val parsed = text.trim().toIntOrNull() ?: return "Enter a number between " +
        "${NetworkAction.ReturnResponse.MIN_STATUS} and ${NetworkAction.ReturnResponse.MAX_STATUS}"
    return if (parsed in STATUS_RANGE) {
        null
    } else {
        "HTTP status must be between ${NetworkAction.ReturnResponse.MIN_STATUS} and " +
            "${NetworkAction.ReturnResponse.MAX_STATUS}"
    }
}
