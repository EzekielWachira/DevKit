package io.devkit.netkit.scenario.serialization

import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.scenario.EndpointMatcher
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.LatencyRange
import io.devkit.netkit.scenario.Probability
import io.devkit.netkit.scenario.WeightedOutcome
import io.devkit.netkit.scenario.chaos.ChaosConfig
import io.devkit.netkit.scenario.chaos.ChaosExclusions
import io.devkit.netkit.scenario.chaos.ChaosScope
import io.devkit.netkit.scenario.condition.AllOf
import io.devkit.netkit.scenario.condition.AnyOf
import io.devkit.netkit.scenario.condition.BodyCondition
import io.devkit.netkit.scenario.condition.HeaderCondition
import io.devkit.netkit.scenario.condition.PreviousResult
import io.devkit.netkit.scenario.condition.PreviousResultCondition
import io.devkit.netkit.scenario.condition.QueryParameterCondition
import io.devkit.netkit.scenario.condition.RequestCountCondition
import io.devkit.netkit.scenario.condition.RuleCondition
import io.devkit.netkit.scenario.condition.StringMatch
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.MalformedResponseType
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.ResponseHeader
import io.devkit.netkit.scenario.SequenceCompletionBehavior
import io.devkit.netkit.scenario.SequenceStep
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioId
import io.devkit.netkit.scenario.model.ScenarioMetadata
import io.devkit.netkit.scenario.model.ScenarioPack
import io.devkit.netkit.scenario.model.ScenarioPackId
import io.devkit.netkit.scenario.model.ScenarioPresetOrigin
import io.devkit.netkit.scenario.model.ScenarioSource

/** Raised when stored data cannot become a valid domain object. */
internal class ScenarioMappingException(message: String) : IllegalArgumentException(message)

/**
 * Translates between the domain model and the storage model.
 *
 * All the knowledge about *how a scenario is written down* lives here. The
 * domain model never mentions a `SerialName`, and the storage model never
 * mentions a value class — so a schema change is a change to this file plus a
 * migration, and nothing else has to move.
 *
 * Mapping **out** cannot fail: a valid domain object always has a
 * representation. Mapping **in** can, and throws [ScenarioMappingException] with
 * a message written for the import error dialog.
 */
internal object ScenarioStorageMapper {

    // ---- domain → storage --------------------------------------------------

    fun toStored(scenario: NetworkScenario): StoredScenario = StoredScenario(
        id = scenario.id.value,
        name = scenario.name,
        description = scenario.description,
        enabled = scenario.enabled,
        global = scenario.globalConfig?.let(::toStored),
        rules = scenario.rules.map(::toStored),
        createdAt = scenario.metadata.createdAtMillis,
        updatedAt = scenario.metadata.updatedAtMillis,
        source = toStored(scenario.metadata.source),
        packId = scenario.metadata.packId?.value,
        chaos = scenario.chaos?.let(::toStored),
        preset = scenario.preset?.let {
            StoredPresetOrigin(it.presetId, it.presetName, it.configuration)
        },
    )

    private fun toStored(chaos: ChaosConfig): StoredChaos = StoredChaos(
        enabled = chaos.enabled,
        failureProbability = chaos.failureProbability.value,
        minLatencyMillis = chaos.latency.minMillis,
        maxLatencyMillis = chaos.latency.maxMillis,
        failures = chaos.failures.map { StoredWeightedOutcome(it.weight, toStored(it.action)) },
        hosts = chaos.scope.hosts,
        pathPrefixes = chaos.scope.pathPrefixes,
        methods = chaos.scope.methods.map { it.name },
        excludedPathPrefixes = chaos.exclusions.prefixes,
    )

    private fun toStored(condition: RuleCondition): StoredCondition = when (condition) {
        is RequestCountCondition.Exactly ->
            StoredCondition.RequestCount("exactly", from = condition.index)

        is RequestCountCondition.AtLeast ->
            StoredCondition.RequestCount("atLeast", from = condition.count)

        is RequestCountCondition.Range ->
            StoredCondition.RequestCount("range", from = condition.from, to = condition.to)

        is RequestCountCondition.Every ->
            StoredCondition.RequestCount("every", interval = condition.interval)

        is HeaderCondition -> StoredCondition.Header(
            name = condition.name,
            match = toStored(condition.match),
            value = condition.value,
        )

        is QueryParameterCondition -> StoredCondition.Query(
            name = condition.name,
            match = toStored(condition.match),
            value = condition.value,
        )

        is BodyCondition -> StoredCondition.Body(condition.text, condition.jsonField)

        is PreviousResultCondition -> StoredCondition.PreviousResult(
            requirement = condition.requirement?.let(::toStored),
            ruleId = condition.ruleId,
            ruleLabel = condition.ruleLabel,
            minimumHits = condition.minimumHits,
        )

        is AllOf -> StoredCondition.AllOf(condition.conditions.map(::toStored))
        is AnyOf -> StoredCondition.AnyOf(condition.conditions.map(::toStored))

        // A consumer-supplied condition cannot be written down. Rather than drop
        // it — which would silently widen the rule from "page 2 only" to "every
        // page" — it becomes a condition that never matches, so an exported
        // scenario is at worst inert and never wrong in the dangerous direction.
        else -> StoredCondition.Header(
            name = UNSUPPORTED_CONDITION_HEADER,
            match = "exists",
        )
    }

    private fun toStored(match: StringMatch): String = when (match) {
        StringMatch.EXISTS -> "exists"
        StringMatch.MISSING -> "missing"
        StringMatch.EQUALS -> "equals"
        StringMatch.CONTAINS -> "contains"
    }

    private fun toStored(result: PreviousResult): String = when (result) {
        PreviousResult.AFTER_SIMULATED_FAILURE -> "afterFailure"
        PreviousResult.BEFORE_ANY_FAILURE -> "beforeAnyFailure"
    }

    fun toStored(pack: ScenarioPack): StoredPack = StoredPack(
        id = pack.id.value,
        name = pack.name,
        description = pack.description,
        source = toStored(pack.source),
    )

    private fun toStored(source: ScenarioSource): String = when (source) {
        ScenarioSource.CREATED_IN_APP -> "created"
        ScenarioSource.IMPORTED -> "imported"
        ScenarioSource.BUILT_IN -> "builtIn"
    }

    private fun toStored(global: GlobalNetworkConfig): StoredGlobal = when (val mode = global.mode) {
        GlobalNetworkMode.Normal -> StoredGlobal("normal", null, global.latencyMillis)
        GlobalNetworkMode.Offline -> StoredGlobal("offline", null, global.latencyMillis)
        is GlobalNetworkMode.Timeout ->
            StoredGlobal("timeout", toStored(mode.type), global.latencyMillis)
    }

    private fun toStored(type: TimeoutType): String =
        if (type == TimeoutType.CONNECT) "connect" else "read"

    private fun toStored(rule: EndpointRule): StoredRule = StoredRule(
        id = rule.id,
        name = rule.name,
        enabled = rule.enabled,
        method = rule.method.name,
        matcher = toStored(rule.matcher),
        action = toStored(rule.action),
        conditions = rule.conditions.map(::toStored),
        // Written only when it is not the default, so a 0.2-shaped scenario
        // exported by 0.3 still looks exactly like a 0.2 file.
        probability = rule.probability.value.takeIf { !rule.probability.isAlways },
    )

    private fun toStored(matcher: EndpointMatcher): StoredMatcher = when (matcher) {
        is EndpointMatcher.ExactPath -> StoredMatcher.ExactPath(matcher.path)
        is EndpointMatcher.PathPrefix -> StoredMatcher.PathPrefix(matcher.prefix)
        // A consumer-supplied matcher cannot be written down, but its label is
        // always a path-like string, so an exact match on it is the closest
        // faithful representation rather than dropping the rule entirely.
        else -> StoredMatcher.ExactPath(matcher.label)
    }

    fun toStored(action: NetworkAction): StoredAction = when (action) {
        NetworkAction.PassThrough -> StoredAction.PassThrough

        is NetworkAction.Delay -> StoredAction.Delay(action.delayMillis)

        is NetworkAction.ReturnResponse -> StoredAction.Respond(
            statusCode = action.statusCode,
            body = action.body,
            contentType = action.contentType,
            headers = action.headers.map { StoredHeader(it.name, it.value) },
            delayMillis = action.delayMillis,
        )

        is NetworkAction.Malformed -> toStored(action)

        NetworkAction.Offline -> StoredAction.Offline

        NetworkAction.Disconnect -> StoredAction.Disconnect

        is NetworkAction.RandomDelay -> StoredAction.RandomDelay(
            minMillis = action.latency.minMillis,
            maxMillis = action.latency.maxMillis,
        )

        is NetworkAction.Weighted -> StoredAction.Weighted(
            outcomes = action.outcomes.map { StoredWeightedOutcome(it.weight, toStored(it.action)) },
        )

        is NetworkAction.Timeout -> StoredAction.Timeout(toStored(action.type))

        is NetworkAction.Sequence -> StoredAction.Sequence(
            steps = action.steps.map { toStored(it.action) },
            completion = when (action.completion) {
                SequenceCompletionBehavior.REPEAT_LAST -> "repeatLast"
                SequenceCompletionBehavior.PASS_THROUGH -> "passThrough"
                SequenceCompletionBehavior.LOOP -> "loop"
            },
        )
    }

    private fun toStored(action: NetworkAction.Malformed): StoredAction.Malformed {
        val type = action.type
        return if (type is MalformedResponseType.Custom) {
            StoredAction.Malformed(
                kind = "custom",
                statusCode = action.statusCode,
                delayMillis = action.delayMillis,
                customLabel = type.label,
                customBody = type.body,
                customContentType = type.contentType,
            )
        } else {
            StoredAction.Malformed(
                kind = malformedKey(type),
                statusCode = action.statusCode,
                delayMillis = action.delayMillis,
            )
        }
    }

    private fun malformedKey(type: MalformedResponseType): String = when (type) {
        MalformedResponseType.InvalidJson -> "invalidJson"
        MalformedResponseType.EmptyBody -> "emptyBody"
        MalformedResponseType.TruncatedJson -> "truncatedJson"
        MalformedResponseType.HtmlInsteadOfJson -> "htmlInsteadOfJson"
        MalformedResponseType.WrongContentType -> "wrongContentType"
        MalformedResponseType.UnexpectedPrimitive -> "unexpectedPrimitive"
        is MalformedResponseType.Custom -> "custom"
    }

    // ---- storage → domain --------------------------------------------------

    fun toDomain(stored: StoredScenario): NetworkScenario {
        if (stored.id.isBlank()) throw ScenarioMappingException("A scenario is missing its id.")
        if (stored.name.isBlank()) throw ScenarioMappingException("A scenario is missing its name.")
        val created = stored.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        return NetworkScenario(
            id = ScenarioId(stored.id),
            name = stored.name,
            description = stored.description?.takeIf { it.isNotBlank() },
            enabled = stored.enabled,
            globalConfig = stored.global?.let(::toDomain),
            rules = stored.rules.map(::toDomain),
            chaos = stored.chaos?.let(::toDomain),
            preset = stored.preset?.let {
                ScenarioPresetOrigin(it.id, it.name, it.configuration)
            },
            metadata = ScenarioMetadata(
                createdAtMillis = created,
                updatedAtMillis = stored.updatedAt.takeIf { it > 0 } ?: created,
                source = sourceFrom(stored.source),
                packId = stored.packId?.takeIf { it.isNotBlank() }?.let(::ScenarioPackId),
            ),
        )
    }

    fun toDomain(stored: StoredPack): ScenarioPack {
        if (stored.id.isBlank()) throw ScenarioMappingException("A pack is missing its id.")
        if (stored.name.isBlank()) throw ScenarioMappingException("A pack is missing its name.")
        return ScenarioPack(
            id = ScenarioPackId(stored.id),
            name = stored.name,
            description = stored.description?.takeIf { it.isNotBlank() },
            source = sourceFrom(stored.source),
        )
    }

    private fun sourceFrom(raw: String): ScenarioSource = when (raw) {
        "imported" -> ScenarioSource.IMPORTED
        "builtIn" -> ScenarioSource.BUILT_IN
        // An unknown source is not worth failing an import over — the scenario
        // still runs. Treat it as locally created, which is the safe default
        // because it is the only one that is fully editable.
        else -> ScenarioSource.CREATED_IN_APP
    }

    private fun toDomain(stored: StoredGlobal): GlobalNetworkConfig {
        if (stored.latencyMillis < 0) {
            throw ScenarioMappingException(
                "Global latency cannot be negative (was ${stored.latencyMillis}).",
            )
        }
        val mode = when (stored.mode) {
            "normal" -> GlobalNetworkMode.Normal
            "offline" -> GlobalNetworkMode.Offline
            "timeout" -> GlobalNetworkMode.Timeout(timeoutFrom(stored.timeoutType))
            else -> throw ScenarioMappingException("Unknown global mode '${stored.mode}'.")
        }
        return GlobalNetworkConfig(mode = mode, latencyMillis = stored.latencyMillis)
    }

    private fun timeoutFrom(raw: String?): TimeoutType = when (raw) {
        "connect" -> TimeoutType.CONNECT
        "read", null -> TimeoutType.READ
        else -> throw ScenarioMappingException("Unknown timeout type '$raw'.")
    }

    private fun toDomain(stored: StoredRule): EndpointRule {
        if (stored.id.isBlank()) throw ScenarioMappingException("A rule is missing its id.")
        val method = HttpMethod.entries.firstOrNull { it.name == stored.method.uppercase() }
            ?: throw ScenarioMappingException("Unknown HTTP method '${stored.method}'.")
        val probability = stored.probability?.let { raw ->
            if (raw.isNaN() || raw !in 0.0..1.0) {
                throw ScenarioMappingException(
                    "A rule probability must be between 0.0 and 1.0 (was $raw).",
                )
            }
            Probability(raw)
        } ?: Probability.ALWAYS
        if (stored.conditions.size > NetKitLimits.MAX_CONDITIONS_PER_RULE) {
            throw ScenarioMappingException(
                "A rule cannot have more than ${NetKitLimits.MAX_CONDITIONS_PER_RULE} " +
                    "conditions (had ${stored.conditions.size}).",
            )
        }
        return EndpointRule(
            id = stored.id,
            name = stored.name?.takeIf { it.isNotBlank() },
            enabled = stored.enabled,
            method = method,
            matcher = toDomain(stored.matcher),
            conditions = stored.conditions.map { toDomain(it, depth = 0) },
            probability = probability,
            action = toDomain(stored.action, nested = false),
        )
    }

    private fun toDomain(stored: StoredMatcher): EndpointMatcher = when (stored) {
        is StoredMatcher.ExactPath -> {
            if (stored.path.isBlank()) throw ScenarioMappingException("A rule is missing its path.")
            if (stored.path.contains(' ')) {
                throw ScenarioMappingException("A path cannot contain spaces (was '${stored.path}').")
            }
            EndpointMatcher.ExactPath(stored.path)
        }

        is StoredMatcher.PathPrefix -> {
            if (stored.prefix.isBlank()) {
                throw ScenarioMappingException("A rule is missing its path prefix.")
            }
            if (stored.prefix.contains(' ')) {
                throw ScenarioMappingException(
                    "A path prefix cannot contain spaces (was '${stored.prefix}').",
                )
            }
            EndpointMatcher.PathPrefix(stored.prefix)
        }
    }

    // ---- conditions ---------------------------------------------------------

    /**
     * @param depth guards nested [StoredCondition.AllOf] / [StoredCondition.AnyOf].
     *   Composition is useful one level deep and a denial-of-service vector at a
     *   thousand, and nobody has ever needed the thousand.
     */
    private fun toDomain(stored: StoredCondition, depth: Int): RuleCondition {
        if (depth > MAX_CONDITION_DEPTH) {
            throw ScenarioMappingException("Rule conditions are nested too deeply.")
        }
        return when (stored) {
            is StoredCondition.RequestCount -> requestCountFrom(stored)

            is StoredCondition.Header -> {
                if (stored.name.isBlank()) {
                    throw ScenarioMappingException("A header condition is missing its name.")
                }
                val match = matchFrom(stored.match)
                requireConditionValue(match, stored.value, "header '${stored.name}'")
                HeaderCondition(stored.name, match, stored.value)
            }

            is StoredCondition.Query -> {
                if (stored.name.isBlank()) {
                    throw ScenarioMappingException(
                        "A query condition is missing its parameter name.",
                    )
                }
                val match = matchFrom(stored.match)
                requireConditionValue(match, stored.value, "query parameter '${stored.name}'")
                QueryParameterCondition(stored.name, match, stored.value)
            }

            is StoredCondition.Body -> {
                if (stored.text.isEmpty()) {
                    throw ScenarioMappingException(
                        "A body condition needs something to look for.",
                    )
                }
                if (stored.text.length > NetKitLimits.MAX_CONDITION_TEXT_LENGTH) {
                    throw ScenarioMappingException(
                        "A body condition cannot exceed " +
                            "${NetKitLimits.MAX_CONDITION_TEXT_LENGTH} characters.",
                    )
                }
                BodyCondition(stored.text, stored.jsonField?.takeIf { it.isNotBlank() })
            }

            is StoredCondition.PreviousResult -> {
                val requirement = stored.requirement?.let { raw ->
                    when (raw) {
                        "afterFailure" -> PreviousResult.AFTER_SIMULATED_FAILURE
                        "beforeAnyFailure" -> PreviousResult.BEFORE_ANY_FAILURE
                        else -> throw ScenarioMappingException(
                            "Unknown previous-result requirement '$raw'.",
                        )
                    }
                }
                val ruleId = stored.ruleId?.takeIf { it.isNotBlank() }
                if (requirement == null && ruleId == null) {
                    throw ScenarioMappingException(
                        "A previous-result condition needs a requirement or a rule to watch.",
                    )
                }
                if (stored.minimumHits < 1) {
                    throw ScenarioMappingException(
                        "A previous-result condition needs at least one hit " +
                            "(was ${stored.minimumHits}).",
                    )
                }
                PreviousResultCondition(
                    requirement = requirement,
                    ruleId = ruleId,
                    ruleLabel = stored.ruleLabel?.takeIf { it.isNotBlank() },
                    minimumHits = stored.minimumHits,
                )
            }

            is StoredCondition.AllOf -> AllOf(stored.conditions.map { toDomain(it, depth + 1) })

            is StoredCondition.AnyOf -> {
                if (stored.conditions.isEmpty()) {
                    // An empty AnyOf never matches, which would silently disable
                    // the rule. Refusing is honest; importing an inert rule is not.
                    throw ScenarioMappingException("An 'any of' condition needs at least one entry.")
                }
                AnyOf(stored.conditions.map { toDomain(it, depth + 1) })
            }
        }
    }

    private fun requestCountFrom(stored: StoredCondition.RequestCount): RuleCondition =
        when (stored.kind) {
            "exactly" -> {
                requirePositiveIndex(stored.from, "request index")
                RequestCountCondition.Exactly(stored.from)
            }

            "atLeast" -> {
                requirePositiveIndex(stored.from, "request count")
                RequestCountCondition.AtLeast(stored.from)
            }

            "range" -> {
                requirePositiveIndex(stored.from, "request index")
                if (stored.to < stored.from) {
                    throw ScenarioMappingException(
                        "A request range is inverted (${stored.from} > ${stored.to}).",
                    )
                }
                RequestCountCondition.Range(stored.from, stored.to)
            }

            "every" -> {
                requirePositiveIndex(stored.interval, "'every Nth request' interval")
                RequestCountCondition.Every(stored.interval)
            }

            else -> throw ScenarioMappingException(
                "Unknown request-count condition '${stored.kind}'.",
            )
        }

    private fun requirePositiveIndex(value: Long, what: String) {
        if (value < 1) throw ScenarioMappingException("A $what must be at least 1 (was $value).")
    }

    private fun matchFrom(raw: String): StringMatch = when (raw) {
        "exists" -> StringMatch.EXISTS
        "missing" -> StringMatch.MISSING
        "equals" -> StringMatch.EQUALS
        "contains" -> StringMatch.CONTAINS
        else -> throw ScenarioMappingException("Unknown condition comparison '$raw'.")
    }

    private fun requireConditionValue(match: StringMatch, value: String, what: String) {
        if (match.needsValue && value.isEmpty()) {
            throw ScenarioMappingException(
                "The $what condition uses '${match.label}' but has no value to compare against.",
            )
        }
    }

    // ---- chaos --------------------------------------------------------------

    private fun toDomain(stored: StoredChaos): ChaosConfig {
        if (stored.failureProbability.isNaN() || stored.failureProbability !in 0.0..1.0) {
            throw ScenarioMappingException(
                "A chaos failure rate must be between 0.0 and 1.0 " +
                    "(was ${stored.failureProbability}).",
            )
        }
        if (stored.minLatencyMillis < 0) {
            throw ScenarioMappingException(
                "Chaos latency cannot be negative (was ${stored.minLatencyMillis}).",
            )
        }
        if (stored.maxLatencyMillis < stored.minLatencyMillis) {
            throw ScenarioMappingException(
                "A chaos latency range is inverted (${stored.minLatencyMillis}ms > " +
                    "${stored.maxLatencyMillis}ms).",
            )
        }
        if (stored.failures.size > NetKitLimits.MAX_WEIGHTED_OUTCOMES) {
            throw ScenarioMappingException(
                "Chaos cannot have more than ${NetKitLimits.MAX_WEIGHTED_OUTCOMES} " +
                    "failure outcomes (had ${stored.failures.size}).",
            )
        }
        val failures = stored.failures.map { toDomain(it) }
        if (failures.any { it.action == NetworkAction.PassThrough }) {
            throw ScenarioMappingException(
                "A chaos failure outcome cannot be pass-through — the failure rate already " +
                    "decides how often a request is spared.",
            )
        }
        val prefixes = stored.pathPrefixes + stored.excludedPathPrefixes
        if (prefixes.size > NetKitLimits.MAX_CHAOS_PREFIXES) {
            throw ScenarioMappingException(
                "Chaos cannot use more than ${NetKitLimits.MAX_CHAOS_PREFIXES} path prefixes.",
            )
        }
        return ChaosConfig(
            enabled = stored.enabled,
            failureProbability = Probability(stored.failureProbability),
            latency = LatencyRange(stored.minLatencyMillis, stored.maxLatencyMillis),
            failures = failures,
            scope = ChaosScope(
                hosts = stored.hosts,
                pathPrefixes = stored.pathPrefixes,
                methods = stored.methods.map { raw ->
                    HttpMethod.entries.firstOrNull { it.name == raw.uppercase() }
                        ?: throw ScenarioMappingException("Unknown HTTP method '$raw'.")
                },
            ),
            exclusions = ChaosExclusions(stored.excludedPathPrefixes),
        )
    }

    private fun toDomain(stored: StoredWeightedOutcome): WeightedOutcome {
        if (stored.weight <= 0) {
            throw ScenarioMappingException(
                "An outcome weight must be positive (was ${stored.weight}).",
            )
        }
        return WeightedOutcome(stored.weight, toDomain(stored.action, nested = true))
    }

    fun toDomain(stored: StoredAction, nested: Boolean): NetworkAction = when (stored) {
        StoredAction.PassThrough -> NetworkAction.PassThrough

        is StoredAction.Delay -> {
            requireNonNegative(stored.delayMillis, "delay")
            NetworkAction.Delay(stored.delayMillis)
        }

        is StoredAction.Respond -> {
            requireStatus(stored.statusCode)
            requireNonNegative(stored.delayMillis, "delay")
            if (stored.contentType.isBlank()) {
                throw ScenarioMappingException("A simulated response is missing its content type.")
            }
            NetworkAction.ReturnResponse(
                statusCode = stored.statusCode,
                body = stored.body,
                contentType = stored.contentType,
                headers = stored.headers.map(::toDomain),
                delayMillis = stored.delayMillis,
            )
        }

        is StoredAction.Malformed -> {
            requireStatus(stored.statusCode)
            requireNonNegative(stored.delayMillis, "delay")
            NetworkAction.Malformed(
                type = malformedFrom(stored),
                statusCode = stored.statusCode,
                delayMillis = stored.delayMillis,
            )
        }

        StoredAction.Offline -> NetworkAction.Offline

        StoredAction.Disconnect -> NetworkAction.Disconnect

        is StoredAction.RandomDelay -> {
            requireNonNegative(stored.minMillis, "delay")
            if (stored.maxMillis < stored.minMillis) {
                throw ScenarioMappingException(
                    "A latency range is inverted (${stored.minMillis}ms > ${stored.maxMillis}ms).",
                )
            }
            NetworkAction.RandomDelay(LatencyRange(stored.minMillis, stored.maxMillis))
        }

        is StoredAction.Weighted -> {
            if (nested) {
                throw ScenarioMappingException(
                    "Weighted outcomes cannot be nested inside another random or sequenced action.",
                )
            }
            if (stored.outcomes.isEmpty()) {
                throw ScenarioMappingException("A random action needs at least one outcome.")
            }
            if (stored.outcomes.size > NetKitLimits.MAX_WEIGHTED_OUTCOMES) {
                throw ScenarioMappingException(
                    "A random action cannot exceed ${NetKitLimits.MAX_WEIGHTED_OUTCOMES} " +
                        "outcomes (had ${stored.outcomes.size}).",
                )
            }
            NetworkAction.Weighted(stored.outcomes.map(::toDomain))
        }

        is StoredAction.Timeout -> NetworkAction.Timeout(timeoutFrom(stored.timeoutType))

        is StoredAction.Sequence -> {
            if (nested) throw ScenarioMappingException("A sequence step cannot be a sequence.")
            if (stored.steps.isEmpty()) {
                throw ScenarioMappingException("A response sequence needs at least one step.")
            }
            NetworkAction.Sequence(
                steps = stored.steps.map { SequenceStep(toDomain(it, nested = true)) },
                completion = completionFrom(stored.completion),
            )
        }
    }

    private fun toDomain(header: StoredHeader): ResponseHeader {
        if (header.name.isBlank()) {
            throw ScenarioMappingException("A response header is missing its name.")
        }
        if (header.name.any { it == ':' || it == '\n' || it == '\r' } ||
            header.value.any { it == '\n' || it == '\r' }
        ) {
            throw ScenarioMappingException(
                "Response header '${header.name}' contains a character HTTP does not allow.",
            )
        }
        return ResponseHeader(header.name, header.value)
    }

    private fun malformedFrom(stored: StoredAction.Malformed): MalformedResponseType =
        when (stored.kind) {
            "invalidJson" -> MalformedResponseType.InvalidJson
            "emptyBody" -> MalformedResponseType.EmptyBody
            "truncatedJson" -> MalformedResponseType.TruncatedJson
            "htmlInsteadOfJson" -> MalformedResponseType.HtmlInsteadOfJson
            "wrongContentType" -> MalformedResponseType.WrongContentType
            "unexpectedPrimitive" -> MalformedResponseType.UnexpectedPrimitive
            "custom" -> MalformedResponseType.Custom(
                label = stored.customLabel?.takeIf { it.isNotBlank() } ?: "Custom",
                body = stored.customBody
                    ?: throw ScenarioMappingException(
                        "A custom malformed response is missing its body.",
                    ),
                contentType = stored.customContentType?.takeIf { it.isNotBlank() }
                    ?: "application/json",
            )

            else -> throw ScenarioMappingException(
                "Unknown malformed response kind '${stored.kind}'.",
            )
        }

    private fun completionFrom(raw: String): SequenceCompletionBehavior = when (raw) {
        "repeatLast" -> SequenceCompletionBehavior.REPEAT_LAST
        "passThrough" -> SequenceCompletionBehavior.PASS_THROUGH
        "loop" -> SequenceCompletionBehavior.LOOP
        else -> throw ScenarioMappingException("Unknown sequence completion behaviour '$raw'.")
    }

    private fun requireStatus(code: Int) {
        if (code !in NetworkAction.ReturnResponse.MIN_STATUS..NetworkAction.ReturnResponse.MAX_STATUS) {
            throw ScenarioMappingException(
                "HTTP status must be between ${NetworkAction.ReturnResponse.MIN_STATUS} and " +
                    "${NetworkAction.ReturnResponse.MAX_STATUS} (was $code).",
            )
        }
    }

    private fun requireNonNegative(value: Long, what: String) {
        if (value < 0) throw ScenarioMappingException("A $what cannot be negative (was $value).")
    }

    /** How deep `allOf` / `anyOf` may nest before an import is refused. */
    private const val MAX_CONDITION_DEPTH = 3

    /**
     * The header name a consumer-supplied condition degrades to on export.
     *
     * Chosen to be a header no request will ever carry, so the rule becomes inert
     * rather than silently losing its restriction.
     */
    private const val UNSUPPORTED_CONDITION_HEADER = "X-NetKit-Unsupported-Condition"
}
