package io.devkit.fillkit.debug.deeplink

import io.devkit.fillkit.FillActivationRequest
import io.devkit.fillkit.FillActivationSource
import io.devkit.fillkit.FillKitSeed
import io.devkit.fillkit.FillReproductionSpec
import io.devkit.fillkit.FillReproductionTokenCodec
import io.devkit.fillkit.FillReproductionTokenException
import io.devkit.fillkit.toActivationRequest

sealed interface FillDeepLinkResult {
    data class Activation(val request: FillActivationRequest) : FillDeepLinkResult
    data class Invalid(val reason: String) : FillDeepLinkResult
}

/**
 * Debug-only deep-link vocabulary.
 *
 * The scheme is derived from the application id so several debug apps can be
 * installed side by side, and links carry identifiers, a seed and a generation
 * only — never generated field values, which would end up in ADB and system logs.
 */
internal object FillKitDeepLink {
    const val HOST_REPRODUCE = "reproduce"
    const val HOST_SCENARIO = "scenario"
    const val MAX_URI_LENGTH = 2048

    private val KNOWN_PARAMETERS = setOf(
        "form", "scenario", "pack", "personaPack", "persona", "locale", "seed", "generation", "config", "token",
    )
    private val idPattern = Regex("[A-Za-z0-9._:-]{1,96}")
    private val localePattern = Regex("[A-Za-z0-9_-]{1,24}")

    fun scheme(applicationId: String): String = "$applicationId.fillkit"

    fun reproduceUri(scheme: String, token: String): String = "$scheme://$HOST_REPRODUCE/$token"

    fun reproduceUri(scheme: String, spec: FillReproductionSpec): String? =
        FillReproductionTokenCodec.encodeOrNull(spec)?.let { reproduceUri(scheme, it) }

    fun scenarioUri(scheme: String, request: FillActivationRequest): String {
        val query = buildList {
            add("form=${request.formId}")
            request.scenarioPackId?.let { add("pack=$it") }
            request.scenarioId?.let { add("scenario=$it") }
            request.personaPackId?.let { add("personaPack=$it") }
            request.personaId?.let { add("persona=$it") }
            request.locale?.let { add("locale=$it") }
            request.seed?.let { add("seed=$it") }
            if (request.generation != 0) add("generation=${request.generation}")
            request.configurationFingerprint?.let { add("config=$it") }
        }.joinToString("&")
        return "$scheme://$HOST_SCENARIO?$query"
    }

    /** Ready-to-run reproduction command for a bug report. */
    fun adbCommand(uri: String): String =
        "adb shell am start -a android.intent.action.VIEW -d \"$uri\""

    /**
     * Parses an incoming link. Deep links are treated as untrusted input even
     * though the entry point only exists in debug builds.
     */
    fun parse(
        uri: String?,
        expectedScheme: String,
        allowFieldOverrides: Boolean = false,
    ): FillDeepLinkResult {
        val raw = uri?.trim().orEmpty()
        if (raw.isEmpty()) return invalid("the link is empty")
        if (raw.length > MAX_URI_LENGTH) return invalid("the link exceeds $MAX_URI_LENGTH characters")

        val separator = raw.indexOf("://")
        if (separator <= 0) return invalid("the link has no scheme")
        val scheme = raw.take(separator)
        if (!scheme.equals(expectedScheme, ignoreCase = true)) {
            return invalid("unexpected scheme \"$scheme\"; this build answers \"$expectedScheme\"")
        }

        val remainder = raw.substring(separator + 3)
        val queryStart = remainder.indexOf('?')
        val path = if (queryStart >= 0) remainder.take(queryStart) else remainder
        val query = if (queryStart >= 0) remainder.substring(queryStart + 1) else ""
        val segments = path.split('/').filter(String::isNotEmpty)
        val host = segments.firstOrNull()?.lowercase() ?: return invalid("the link has no path")

        val parameters = linkedMapOf<String, String>()
        query.split('&').filter(String::isNotEmpty).forEach { entry ->
            val split = entry.indexOf('=')
            if (split <= 0) return invalid("malformed query parameter \"$entry\"")
            parameters[entry.take(split)] = decode(entry.substring(split + 1))
        }
        val unknown = parameters.keys - KNOWN_PARAMETERS
        if (unknown.isNotEmpty() && !allowFieldOverrides) {
            return invalid(
                "unsupported parameters ${unknown.joinToString()}; raw field injection is disabled " +
                    "(set allowDeepLinkFieldOverrides to opt in)",
            )
        }

        return when (host) {
            HOST_REPRODUCE -> reproduction(segments.getOrNull(1) ?: parameters["token"])
            HOST_SCENARIO -> scenario(parameters)
            else -> invalid("unsupported path \"$host\"; expected \"$HOST_REPRODUCE\" or \"$HOST_SCENARIO\"")
        }
    }

    private fun reproduction(token: String?): FillDeepLinkResult {
        if (token.isNullOrBlank()) return invalid("the reproduction link carries no token")
        return try {
            FillDeepLinkResult.Activation(
                FillReproductionTokenCodec.decode(token).toActivationRequest(FillActivationSource.DeepLink),
            )
        } catch (error: FillReproductionTokenException) {
            invalid(error.message ?: "the reproduction token is invalid")
        }
    }

    private fun scenario(parameters: Map<String, String>): FillDeepLinkResult {
        val form = parameters["form"] ?: return invalid("the scenario link has no \"form\" parameter")
        listOfNotNull(
            validate(form, idPattern, "form"),
            parameters["scenario"]?.let { validate(it, idPattern, "scenario") },
            parameters["pack"]?.let { validate(it, idPattern, "pack") },
            parameters["persona"]?.let { validate(it, idPattern, "persona") },
            parameters["personaPack"]?.let { validate(it, idPattern, "personaPack") },
            parameters["locale"]?.let { validate(it, localePattern, "locale") },
            parameters["config"]?.let { validate(it, idPattern, "config") },
        ).firstOrNull()?.let { return it }

        val seed = parameters["seed"]?.let { value ->
            value.toLongOrNull()?.takeIf(FillKitSeed::isValid)
                ?: return invalid("seed \"$value\" is not in ${FillKitSeed.MIN}..${FillKitSeed.MAX}")
        }
        val generation = parameters["generation"]?.let { value ->
            value.toIntOrNull()?.takeIf { it in 0..FillReproductionSpec.MAX_GENERATION }
                ?: return invalid("generation \"$value\" is not in 0..${FillReproductionSpec.MAX_GENERATION}")
        } ?: 0

        return FillDeepLinkResult.Activation(
            FillActivationRequest(
                formId = form,
                scenarioPackId = parameters["pack"],
                scenarioId = parameters["scenario"],
                personaPackId = parameters["personaPack"],
                personaId = parameters["persona"],
                locale = parameters["locale"],
                seed = seed,
                generation = generation,
                configurationFingerprint = parameters["config"],
                source = FillActivationSource.DeepLink,
            ),
        )
    }

    private fun validate(value: String, pattern: Regex, label: String): FillDeepLinkResult.Invalid? =
        if (pattern.matches(value)) null else invalid("$label \"$value\" is not a valid identifier")

    private fun invalid(reason: String) = FillDeepLinkResult.Invalid(reason)

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
