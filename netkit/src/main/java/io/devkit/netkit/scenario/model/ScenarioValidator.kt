package io.devkit.netkit.scenario.model

import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.SequenceStep
import java.nio.charset.StandardCharsets

/** One thing wrong with a scenario, in terms a person can act on. */
data class ValidationError(
    /** Where the problem is, e.g. `name`, `rules[2].path`. */
    val field: String,
    /** What is wrong, phrased for the debug UI. */
    val message: String,
) {
    override fun toString(): String = "$field: $message"
}

/** The outcome of validating a scenario or pack. */
sealed interface ValidationResult {

    /** True when the subject can be saved, activated or imported. */
    val isValid: Boolean

    /** Every problem found, empty when [Valid]. */
    val errors: List<ValidationError>

    data object Valid : ValidationResult {
        override val isValid: Boolean get() = true
        override val errors: List<ValidationError> get() = emptyList()
    }

    data class Invalid(override val errors: List<ValidationError>) : ValidationResult {
        init {
            require(errors.isNotEmpty()) { "An invalid result needs at least one error" }
        }

        override val isValid: Boolean get() = false

        /** All problems on one line, for a snackbar or a log. */
        val message: String get() = errors.joinToString("; ") { it.message }
    }

    companion object {
        /** [Valid] when [errors] is empty, otherwise [Invalid]. */
        fun of(errors: List<ValidationError>): ValidationResult =
            if (errors.isEmpty()) Valid else Invalid(errors)
    }
}

/**
 * Checks that a scenario is something NetKit can safely run.
 *
 * One validator serves all three entry points — the scenario editor, file
 * import, and programmatic registration — so a scenario that the UI refuses to
 * save cannot arrive through a `.netkit.json` instead.
 */
interface ScenarioValidator {

    fun validate(scenario: NetworkScenario): ValidationResult

    fun validate(pack: ScenarioPack, scenarios: List<NetworkScenario>): ValidationResult
}

/**
 * The validator NetKit installs.
 *
 * Deliberately strict about the things that would misbehave at runtime
 * (duplicate rule ids, empty sequences, oversized bodies) and permissive about
 * taste (a scenario with no rules at all is valid — it is how you start one).
 */
class DefaultScenarioValidator : ScenarioValidator {

    override fun validate(scenario: NetworkScenario): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        if (scenario.name.isBlank()) {
            errors += ValidationError("name", "A scenario needs a name.")
        } else if (scenario.name.length > NetKitLimits.MAX_NAME_LENGTH) {
            errors += ValidationError(
                "name",
                "A scenario name cannot be longer than ${NetKitLimits.MAX_NAME_LENGTH} characters.",
            )
        }

        scenario.description?.let { description ->
            if (description.length > NetKitLimits.MAX_DESCRIPTION_LENGTH) {
                errors += ValidationError(
                    "description",
                    "A description cannot be longer than " +
                        "${NetKitLimits.MAX_DESCRIPTION_LENGTH} characters.",
                )
            }
        }

        scenario.globalConfig?.let { global ->
            if (global.latencyMillis < 0) {
                errors += ValidationError("globalConfig.latency", "Latency cannot be negative.")
            }
        }

        if (scenario.rules.size > NetKitLimits.MAX_RULES_PER_SCENARIO) {
            errors += ValidationError(
                "rules",
                "A scenario cannot hold more than " +
                    "${NetKitLimits.MAX_RULES_PER_SCENARIO} rules (has ${scenario.rules.size}).",
            )
        }

        val seenIds = mutableSetOf<String>()
        scenario.rules.forEachIndexed { index, rule ->
            if (!seenIds.add(rule.id)) {
                errors += ValidationError(
                    "rules[$index].id",
                    "Two rules share the id '${rule.id}'. Rule ids must be unique inside a scenario.",
                )
            }
            errors += validateRule(rule, "rules[$index]")
        }

        return ValidationResult.of(errors)
    }

    override fun validate(pack: ScenarioPack, scenarios: List<NetworkScenario>): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        if (pack.name.isBlank()) {
            errors += ValidationError("name", "A scenario pack needs a name.")
        } else if (pack.name.length > NetKitLimits.MAX_NAME_LENGTH) {
            errors += ValidationError(
                "name",
                "A pack name cannot be longer than ${NetKitLimits.MAX_NAME_LENGTH} characters.",
            )
        }
        if (scenarios.size > NetKitLimits.MAX_SCENARIOS_PER_PACK) {
            errors += ValidationError(
                "scenarios",
                "A pack cannot hold more than ${NetKitLimits.MAX_SCENARIOS_PER_PACK} scenarios " +
                    "(has ${scenarios.size}).",
            )
        }
        val seen = mutableSetOf<ScenarioId>()
        scenarios.forEachIndexed { index, scenario ->
            if (!seen.add(scenario.id)) {
                errors += ValidationError(
                    "scenarios[$index].id",
                    "Two scenarios share the id '${scenario.id}'.",
                )
            }
            val result = validate(scenario)
            if (result is ValidationResult.Invalid) {
                errors += result.errors.map {
                    ValidationError("scenarios[$index].${it.field}", it.message)
                }
            }
        }
        return ValidationResult.of(errors)
    }

    private fun validateRule(rule: EndpointRule, path: String): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        if (rule.id.isBlank()) {
            errors += ValidationError("$path.id", "A rule needs an id.")
        }
        if (rule.matcher.label.isBlank()) {
            errors += ValidationError("$path.path", "A rule needs a path to match.")
        } else if (rule.matcher.label.contains(' ')) {
            errors += ValidationError("$path.path", "A path cannot contain spaces.")
        }
        errors += validateAction(rule.action, "$path.action", allowSequence = true)
        return errors
    }

    private fun validateAction(
        action: NetworkAction,
        path: String,
        allowSequence: Boolean,
    ): List<ValidationError> = when (action) {
        NetworkAction.PassThrough, NetworkAction.Offline -> emptyList()

        is NetworkAction.Timeout -> emptyList()

        is NetworkAction.Delay -> if (action.delayMillis < 0) {
            listOf(ValidationError("$path.delay", "Delay cannot be negative."))
        } else {
            emptyList()
        }

        is NetworkAction.ReturnResponse -> validateResponse(action, path)

        is NetworkAction.Malformed -> buildList {
            if (action.statusCode !in
                NetworkAction.ReturnResponse.MIN_STATUS..NetworkAction.ReturnResponse.MAX_STATUS
            ) {
                add(ValidationError("$path.status", statusMessage(action.statusCode)))
            }
            if (action.delayMillis < 0) {
                add(ValidationError("$path.delay", "Delay cannot be negative."))
            }
            addAll(validateBodySize(action.type.body, "$path.body"))
        }

        is NetworkAction.Sequence -> validateSequence(action, path, allowSequence)
    }

    private fun validateResponse(
        action: NetworkAction.ReturnResponse,
        path: String,
    ): List<ValidationError> = buildList {
        if (action.statusCode !in
            NetworkAction.ReturnResponse.MIN_STATUS..NetworkAction.ReturnResponse.MAX_STATUS
        ) {
            add(ValidationError("$path.status", statusMessage(action.statusCode)))
        }
        if (action.delayMillis < 0) {
            add(ValidationError("$path.delay", "Delay cannot be negative."))
        }
        if (action.contentType.isBlank()) {
            add(ValidationError("$path.contentType", "A response needs a content type."))
        }
        if (action.headers.size > NetKitLimits.MAX_RESPONSE_HEADERS) {
            add(
                ValidationError(
                    "$path.headers",
                    "A response cannot carry more than " +
                        "${NetKitLimits.MAX_RESPONSE_HEADERS} headers.",
                ),
            )
        }
        action.body?.let { addAll(validateBodySize(it, "$path.body")) }
    }

    private fun validateSequence(
        action: NetworkAction.Sequence,
        path: String,
        allowSequence: Boolean,
    ): List<ValidationError> = buildList {
        if (!allowSequence) {
            add(ValidationError(path, "A sequence step cannot itself be a sequence."))
            return@buildList
        }
        if (action.steps.isEmpty()) {
            add(ValidationError("$path.steps", "A response sequence needs at least one step."))
        }
        if (action.steps.size > NetKitLimits.MAX_SEQUENCE_STEPS) {
            add(
                ValidationError(
                    "$path.steps",
                    "A response sequence cannot exceed ${NetKitLimits.MAX_SEQUENCE_STEPS} steps " +
                        "(has ${action.steps.size}).",
                ),
            )
        }
        action.steps.forEachIndexed { index: Int, step: SequenceStep ->
            addAll(validateAction(step.action, "$path.steps[$index]", allowSequence = false))
        }
    }

    private fun validateBodySize(body: String, field: String): List<ValidationError> {
        val bytes = body.toByteArray(StandardCharsets.UTF_8).size
        return if (bytes > NetKitLimits.MAX_BODY_BYTES) {
            listOf(
                ValidationError(
                    field,
                    "A response body cannot exceed " +
                        "${NetKitLimits.MAX_BODY_BYTES / 1024} KB (was ${bytes / 1024} KB).",
                ),
            )
        } else {
            emptyList()
        }
    }

    private fun statusMessage(code: Int) =
        "HTTP status must be between ${NetworkAction.ReturnResponse.MIN_STATUS} and " +
            "${NetworkAction.ReturnResponse.MAX_STATUS} (was $code)."
}
