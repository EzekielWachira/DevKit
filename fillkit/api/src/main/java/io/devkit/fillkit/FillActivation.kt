package io.devkit.fillkit

/** Where an activation came from. Only used for diagnostics and launcher history. */
enum class FillActivationSource { Panel, QaLauncher, DeepLink, Test, Programmatic }

/**
 * The one request every FillKit entry point produces. The developer panel, the QA
 * launcher, Compose tests and deep links all build this and hand it to the
 * activation engine; nothing else activates a scenario.
 */
data class FillActivationRequest(
    val formId: String,
    val scenarioPackId: String? = null,
    val scenarioId: String? = null,
    val personaPackId: String? = null,
    val personaId: String? = null,
    val locale: String? = null,
    /** Locale pack coordinate recorded by a reproduction; compared, not applied. */
    val localePack: String? = null,
    val seed: Long? = null,
    val generation: Int = 0,
    val configurationFingerprint: String? = null,
    /** Non-zero per-field regeneration counters to restore, from a reproduction. */
    val fieldGenerations: Map<String, Int> = emptyMap(),
    val source: FillActivationSource = FillActivationSource.Programmatic,
    /** Fill every registered field once the state is applied. */
    val fill: Boolean = true,
) {
    init {
        require(formId.isNotBlank()) { "activation formId cannot be blank" }
        require(seed == null || FillKitSeed.isValid(seed)) {
            "activation seed must be in ${FillKitSeed.MIN}..${FillKitSeed.MAX}"
        }
        require(generation in 0..FillReproductionSpec.MAX_GENERATION) {
            "activation generation must be in 0..${FillReproductionSpec.MAX_GENERATION}"
        }
    }

    /** Lossy only in [source] and [fill], which are not part of a reproduction. */
    fun toReproductionSpec(resolvedSeed: Long = seed ?: 0L) = FillReproductionSpec(
        formId = formId,
        seed = seed ?: resolvedSeed,
        generation = generation,
        locale = locale,
        localePack = localePack,
        scenarioPackId = scenarioPackId,
        scenarioId = scenarioId,
        personaPackId = personaPackId,
        personaId = personaId,
        configurationFingerprint = configurationFingerprint,
        fieldGenerations = fieldGenerations,
    )
}

/** QA launcher naming for the same request; deliberately not a second model. */
typealias FillKitLaunchRequest = FillActivationRequest

fun FillReproductionSpec.toActivationRequest(
    source: FillActivationSource = FillActivationSource.Programmatic,
    fill: Boolean = true,
) = FillActivationRequest(
    formId = formId,
    scenarioPackId = scenarioPackId,
    scenarioId = scenarioId,
    personaPackId = personaPackId,
    personaId = personaId,
    locale = locale,
    localePack = localePack,
    seed = seed,
    generation = generation,
    configurationFingerprint = configurationFingerprint,
    fieldGenerations = fieldGenerations,
    source = source,
    fill = fill,
)

enum class FillActivationRejection {
    NoRuntime,
    UnknownScenario,
    UnknownPersona,
    UnknownLocale,
    InvalidRequest,
    Expired,
}

/** Explicit outcome so callers never have to read logs to know what happened. */
sealed interface FillActivationResult {
    /** Everything in the request was applied to a live host. */
    data class Applied(val spec: FillReproductionSpec) : FillActivationResult

    /** Applied, but some optional part of the request was unusable. */
    data class PartiallyApplied(val spec: FillReproductionSpec, val warnings: List<String>) : FillActivationResult

    /** No host for [formId] is composed yet; the request is stored consume-once. */
    data class Pending(val formId: String, val expiresAtMillis: Long) : FillActivationResult

    data class Rejected(val reason: FillActivationRejection, val message: String) : FillActivationResult
}

/** The reproduction that was actually applied, or null when nothing was. */
val FillActivationResult.appliedSpec: FillReproductionSpec?
    get() = when (this) {
        is FillActivationResult.Applied -> spec
        is FillActivationResult.PartiallyApplied -> spec
        else -> null
    }

val FillActivationResult.warnings: List<String>
    get() = (this as? FillActivationResult.PartiallyApplied)?.warnings.orEmpty()

val FillActivationResult.isApplied: Boolean
    get() = this is FillActivationResult.Applied || this is FillActivationResult.PartiallyApplied
