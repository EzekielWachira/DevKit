package io.devkit.netkit.scenario.serialization

import io.devkit.netkit.scenario.EndpointMatcher
import io.devkit.netkit.scenario.EndpointRule
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
    )

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
    )

    private fun toStored(matcher: EndpointMatcher): StoredMatcher = when (matcher) {
        is EndpointMatcher.ExactPath -> StoredMatcher.ExactPath(matcher.path)
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
        return EndpointRule(
            id = stored.id,
            name = stored.name?.takeIf { it.isNotBlank() },
            enabled = stored.enabled,
            method = method,
            matcher = toDomain(stored.matcher),
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
}
