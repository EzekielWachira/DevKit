package io.devkit.netkit.scenario.model

import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.SequenceStep
import io.devkit.netkit.scenario.WeightedOutcome
import io.devkit.netkit.scenario.chaos.ChaosConfig
import io.devkit.netkit.scenario.condition.AllOf
import io.devkit.netkit.scenario.condition.AnyOf
import io.devkit.netkit.scenario.condition.BodyCondition
import io.devkit.netkit.scenario.condition.HeaderCondition
import io.devkit.netkit.scenario.condition.PreviousResultCondition
import io.devkit.netkit.scenario.condition.QueryParameterCondition
import io.devkit.netkit.scenario.condition.RequestCountCondition
import io.devkit.netkit.scenario.condition.RuleCondition
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

        scenario.chaos?.let { errors += validateChaos(it, "chaos") }

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
        if (rule.probability.value.isNaN() || rule.probability.value !in 0.0..1.0) {
            errors += ValidationError(
                "$path.probability",
                "A probability must be between 0% and 100%.",
            )
        }
        if (rule.conditions.size > NetKitLimits.MAX_CONDITIONS_PER_RULE) {
            errors += ValidationError(
                "$path.conditions",
                "A rule cannot have more than ${NetKitLimits.MAX_CONDITIONS_PER_RULE} " +
                    "conditions (has ${rule.conditions.size}).",
            )
        }
        rule.conditions.forEachIndexed { index, condition ->
            errors += validateCondition(condition, "$path.conditions[$index]")
        }
        errors += validateAction(rule.action, "$path.action", allowSequence = true)
        return errors
    }

    /**
     * Checks a condition's own invariants.
     *
     * The condition types enforce most of this in their constructors, so a
     * condition built in Kotlin cannot be wrong. This exists for the other two
     * doors: a hand-edited `.netkit.json`, and a consumer-supplied condition
     * whose constructor NetKit does not control.
     */
    private fun validateCondition(
        condition: RuleCondition,
        path: String,
        depth: Int = 0,
    ): List<ValidationError> = buildList {
        if (depth > MAX_CONDITION_DEPTH) {
            add(ValidationError(path, "Conditions are nested too deeply."))
            return@buildList
        }
        when (condition) {
            is RequestCountCondition.Exactly -> if (condition.index < 1) {
                add(ValidationError(path, "A request index starts at 1."))
            }

            is RequestCountCondition.AtLeast -> if (condition.count < 1) {
                add(ValidationError(path, "A request count starts at 1."))
            }

            is RequestCountCondition.Range -> {
                if (condition.from < 1) add(ValidationError(path, "A request index starts at 1."))
                if (condition.to < condition.from) {
                    add(ValidationError(path, "A request range cannot end before it starts."))
                }
            }

            is RequestCountCondition.Every -> if (condition.interval < 1) {
                add(ValidationError(path, "An 'every Nth request' interval must be at least 1."))
            }

            is HeaderCondition -> {
                if (condition.name.isBlank()) {
                    add(ValidationError(path, "A header condition needs a header name."))
                }
                if (condition.match.needsValue && condition.value.isEmpty()) {
                    add(ValidationError(path, "This header condition needs a value to compare."))
                }
            }

            is QueryParameterCondition -> {
                if (condition.name.isBlank()) {
                    add(ValidationError(path, "A query condition needs a parameter name."))
                }
                if (condition.match.needsValue && condition.value.isEmpty()) {
                    add(ValidationError(path, "This query condition needs a value to compare."))
                }
            }

            is BodyCondition -> {
                if (condition.text.isEmpty()) {
                    add(ValidationError(path, "A body condition needs something to look for."))
                } else if (condition.text.length > NetKitLimits.MAX_CONDITION_TEXT_LENGTH) {
                    add(
                        ValidationError(
                            path,
                            "A body condition cannot exceed " +
                                "${NetKitLimits.MAX_CONDITION_TEXT_LENGTH} characters.",
                        ),
                    )
                }
            }

            is PreviousResultCondition -> {
                if (condition.requirement == null && condition.ruleId == null) {
                    add(
                        ValidationError(
                            path,
                            "A previous-result condition needs a requirement or a rule to watch.",
                        ),
                    )
                }
                if (condition.minimumHits < 1) {
                    add(ValidationError(path, "A previous-result condition needs at least one hit."))
                }
            }

            is AllOf -> condition.conditions.forEachIndexed { index, nested ->
                addAll(validateCondition(nested, "$path[$index]", depth + 1))
            }

            is AnyOf -> {
                if (condition.conditions.isEmpty()) {
                    add(ValidationError(path, "An 'any of' condition needs at least one entry."))
                }
                condition.conditions.forEachIndexed { index, nested ->
                    addAll(validateCondition(nested, "$path[$index]", depth + 1))
                }
            }

            // A consumer-supplied condition. NetKit cannot check its invariants,
            // only that it says something a person can read.
            else -> if (condition.label.isBlank()) {
                add(ValidationError(path, "A condition needs a label."))
            }
        }
    }

    private fun validateAction(
        action: NetworkAction,
        path: String,
        allowSequence: Boolean,
        allowWeighted: Boolean = true,
    ): List<ValidationError> = when (action) {
        NetworkAction.PassThrough, NetworkAction.Offline, NetworkAction.Disconnect -> emptyList()

        is NetworkAction.Timeout -> emptyList()

        is NetworkAction.Delay -> if (action.delayMillis < 0) {
            listOf(ValidationError("$path.delay", "Delay cannot be negative."))
        } else {
            emptyList()
        }

        is NetworkAction.RandomDelay -> buildList {
            if (action.latency.minMillis < 0) {
                add(ValidationError("$path.latency", "Latency cannot be negative."))
            }
            if (action.latency.maxMillis < action.latency.minMillis) {
                add(
                    ValidationError(
                        "$path.latency",
                        "The shortest latency cannot be longer than the longest.",
                    ),
                )
            }
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

        is NetworkAction.Weighted -> validateWeighted(action.outcomes, path, allowWeighted)

        is NetworkAction.Sequence -> validateSequence(action, path, allowSequence)
    }

    /**
     * Checks a set of weighted outcomes.
     *
     * Shared by [NetworkAction.Weighted] and chaos, because a distribution is a
     * distribution and the two should never disagree about what a valid one is.
     */
    private fun validateWeighted(
        outcomes: List<WeightedOutcome>,
        path: String,
        allowed: Boolean,
    ): List<ValidationError> = buildList {
        if (!allowed) {
            add(ValidationError(path, "Random outcomes cannot be nested."))
            return@buildList
        }
        if (outcomes.isEmpty()) {
            add(ValidationError("$path.outcomes", "A random action needs at least one outcome."))
        }
        if (outcomes.size > NetKitLimits.MAX_WEIGHTED_OUTCOMES) {
            add(
                ValidationError(
                    "$path.outcomes",
                    "A random action cannot exceed ${NetKitLimits.MAX_WEIGHTED_OUTCOMES} " +
                        "outcomes (has ${outcomes.size}).",
                ),
            )
        }
        outcomes.forEachIndexed { index, outcome ->
            if (outcome.weight <= 0) {
                add(
                    ValidationError(
                        "$path.outcomes[$index].weight",
                        "An outcome weight must be greater than zero.",
                    ),
                )
            }
            addAll(
                validateAction(
                    action = outcome.action,
                    path = "$path.outcomes[$index]",
                    allowSequence = false,
                    allowWeighted = false,
                ),
            )
        }
    }

    /** Checks a scenario's chaos configuration. */
    private fun validateChaos(chaos: ChaosConfig, path: String): List<ValidationError> = buildList {
        if (chaos.failureProbability.value.isNaN() ||
            chaos.failureProbability.value !in 0.0..1.0
        ) {
            add(ValidationError("$path.failureRate", "A failure rate must be between 0% and 100%."))
        }
        if (chaos.latency.minMillis < 0) {
            add(ValidationError("$path.latency", "Latency cannot be negative."))
        }
        if (chaos.latency.maxMillis < chaos.latency.minMillis) {
            add(
                ValidationError(
                    "$path.latency",
                    "The shortest latency cannot be longer than the longest.",
                ),
            )
        }
        // Only an error when chaos could actually fire: a disabled config, or one
        // configured purely for latency, has no need of a failure mix.
        if (chaos.enabled && !chaos.failureProbability.isNever && chaos.failures.isEmpty()) {
            add(
                ValidationError(
                    "$path.failures",
                    "Chaos has a failure rate but nothing to fail with. Add an outcome, or " +
                        "set the failure rate to 0%.",
                ),
            )
        }
        addAll(validateWeighted(chaos.failures, path, allowed = true))
        val prefixes = chaos.scope.pathPrefixes.size + chaos.exclusions.prefixes.size
        if (prefixes > NetKitLimits.MAX_CHAOS_PREFIXES) {
            add(
                ValidationError(
                    "$path.scope",
                    "Chaos cannot use more than ${NetKitLimits.MAX_CHAOS_PREFIXES} path prefixes.",
                ),
            )
        }
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

    /** How deep `allOf` / `anyOf` may nest. Matches the import mapper's limit. */
    private val MAX_CONDITION_DEPTH = 3

    private fun statusMessage(code: Int) =
        "HTTP status must be between ${NetworkAction.ReturnResponse.MIN_STATUS} and " +
            "${NetworkAction.ReturnResponse.MAX_STATUS} (was $code)."
}
