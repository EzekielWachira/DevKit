package io.devkit.netkit.ui.scenarios

import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.scenario.EndpointMatcher
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.TimeoutType

/** The behaviours the rule editor can build, in display order. */
internal enum class RuleBehavior(val label: String) {
    PASS_THROUGH("Normal"),
    DELAY("Delay"),
    HTTP_ERROR("HTTP error"),
    OFFLINE("Offline"),
    TIMEOUT("Timeout"),
    ;

    companion object {
        fun of(action: NetworkAction): RuleBehavior = when (action) {
            NetworkAction.PassThrough -> PASS_THROUGH
            is NetworkAction.Delay -> DELAY
            is NetworkAction.HttpError -> HTTP_ERROR
            NetworkAction.Offline -> OFFLINE
            is NetworkAction.Timeout -> TIMEOUT
        }
    }
}

/**
 * The editable form behind the "add / edit endpoint override" sheet.
 *
 * A plain immutable value class rather than a ViewModel: it holds only text and
 * selections, validates itself, and knows how to become an [EndpointRule]. That
 * keeps every validation rule unit-testable without Compose, and keeps the
 * composable a pure rendering of this state.
 *
 * @param ruleId non-null when editing an existing rule.
 * @param pathText raw user input; validated by [pathError].
 * @param matcherOverride preserved when editing a rule built with a matcher the
 *   0.1 editor cannot express, so saving does not silently downgrade it.
 */
internal data class RuleEditorState(
    val ruleId: String? = null,
    val name: String = "",
    val method: HttpMethod = HttpMethod.ANY,
    val pathText: String = "",
    val behavior: RuleBehavior = RuleBehavior.HTTP_ERROR,
    val statusText: String = "500",
    val bodyText: String = "",
    val contentType: String = NetworkAction.HttpError.DEFAULT_CONTENT_TYPE,
    val delayText: String = "0",
    val timeoutType: TimeoutType = TimeoutType.READ,
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
        get() {
            if (behavior != RuleBehavior.HTTP_ERROR) return null
            val parsed = statusText.trim().toIntOrNull()
                ?: return "Enter a number between ${NetworkAction.HttpError.MIN_STATUS} and " +
                    "${NetworkAction.HttpError.MAX_STATUS}"
            return if (parsed in NetworkAction.HttpError.MIN_STATUS..NetworkAction.HttpError.MAX_STATUS) {
                null
            } else {
                "HTTP status must be between ${NetworkAction.HttpError.MIN_STATUS} and " +
                    "${NetworkAction.HttpError.MAX_STATUS}"
            }
        }

    /** Validation message for the delay field, or `null`. */
    val delayError: String?
        get() {
            if (behavior != RuleBehavior.DELAY && behavior != RuleBehavior.HTTP_ERROR) return null
            val raw = delayText.trim()
            if (raw.isEmpty()) return null
            val parsed = raw.toLongOrNull() ?: return "Enter a whole number of milliseconds"
            return if (parsed < 0) "Delay cannot be negative" else null
        }

    /** True when [toRule] will succeed. */
    val isValid: Boolean get() = pathError == null && statusError == null && delayError == null

    private val delayMillis: Long get() = delayText.trim().toLongOrNull()?.coerceAtLeast(0) ?: 0

    /** Builds the rule, or returns `null` when the form is not valid. */
    fun toRule(): EndpointRule? {
        if (!isValid) return null
        val action = when (behavior) {
            RuleBehavior.PASS_THROUGH -> NetworkAction.PassThrough
            RuleBehavior.DELAY -> NetworkAction.Delay(delayMillis)
            RuleBehavior.HTTP_ERROR -> NetworkAction.HttpError(
                statusCode = statusText.trim().toInt(),
                body = bodyText.takeIf { it.isNotBlank() },
                contentType = contentType.ifBlank { NetworkAction.HttpError.DEFAULT_CONTENT_TYPE },
                delayMillis = delayMillis,
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

    companion object {
        /** A blank form for the "add override" flow. */
        fun new(): RuleEditorState = RuleEditorState()

        /** Loads an existing rule into the form. */
        fun from(rule: EndpointRule): RuleEditorState {
            val action = rule.action
            val exactPath = rule.matcher as? EndpointMatcher.ExactPath
            return RuleEditorState(
                ruleId = rule.id,
                name = rule.name.orEmpty(),
                method = rule.method,
                pathText = exactPath?.path ?: rule.matcher.label,
                behavior = RuleBehavior.of(action),
                statusText = (action as? NetworkAction.HttpError)?.statusCode?.toString() ?: "500",
                bodyText = (action as? NetworkAction.HttpError)?.body.orEmpty(),
                contentType = (action as? NetworkAction.HttpError)?.contentType
                    ?: NetworkAction.HttpError.DEFAULT_CONTENT_TYPE,
                delayText = when (action) {
                    is NetworkAction.Delay -> action.delayMillis.toString()
                    is NetworkAction.HttpError -> action.delayMillis.toString()
                    else -> "0"
                },
                timeoutType = (action as? NetworkAction.Timeout)?.type ?: TimeoutType.READ,
                enabled = rule.enabled,
                matcherOverride = if (exactPath == null) rule.matcher else null,
            )
        }

        /** Status codes offered as one-tap chips. */
        val statusChips: List<Int> = NetKitDefaults.COMMON_STATUS_CODES
    }
}
