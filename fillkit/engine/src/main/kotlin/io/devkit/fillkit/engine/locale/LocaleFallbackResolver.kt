package io.devkit.fillkit.engine.locale

import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillLocalePack

/** Why a request ended up on the pack it did. Surfaced in the field inspector. */
enum class FillLocaleMatch {
    /** The requested tag has its own pack. */
    Exact,

    /** Same country, different language. */
    Region,

    /** Same language, different country. */
    Language,

    /** Nothing matched; the configured default was used. */
    Default,
}

data class FillLocaleResolution(
    val pack: FillLocalePack,
    val requested: String,
    val match: FillLocaleMatch,
) {
    val exact: Boolean get() = match == FillLocaleMatch.Exact

    /** One-line diagnostic, e.g. `es-PR → es-MX (no es-PR locale pack registered)`. */
    fun describe(): String = when (match) {
        FillLocaleMatch.Exact -> "$requested → ${pack.code} (exact locale pack)"
        FillLocaleMatch.Region -> "$requested → ${pack.code} (no $requested pack; same region)"
        FillLocaleMatch.Language -> "$requested → ${pack.code} (no $requested pack; same language)"
        FillLocaleMatch.Default -> "$requested → ${pack.code} (no $requested locale pack registered)"
    }
}

/**
 * The one place locale fallback is decided.
 *
 * Generators never guess: they ask for a pack and get a resolution that also
 * explains itself, so an unexpected dataset can be traced instead of debugged.
 */
class LocaleFallbackResolver(
    private val packsByCode: Map<String, FillLocalePack>,
    private val defaultCode: String,
) {
    fun resolve(locale: FillLocale, systemTag: String? = null): FillLocaleResolution {
        val requested = when (locale) {
            FillLocale.System -> systemTag ?: defaultCode
            is FillLocale.Code -> locale.value
            is FillLocale.Country -> return resolveCountry(locale.normalized)
        }
        return resolveTag(requested)
    }

    fun resolveTag(requested: String): FillLocaleResolution {
        val normalized = normalize(requested)
        packsByCode[normalized]?.let { return FillLocaleResolution(it, requested, FillLocaleMatch.Exact) }

        val language = normalized.substringBefore('-')
        val region = normalized.substringAfter('-', "")

        if (region.isNotEmpty()) {
            // A different language spoken in the same country keeps geography,
            // phone rules and postal formats correct.
            byRegion(region)?.let { return FillLocaleResolution(it, requested, FillLocaleMatch.Region) }
        }
        byLanguage(language)?.let { return FillLocaleResolution(it, requested, FillLocaleMatch.Language) }

        val fallback = packsByCode[normalize(defaultCode)]
            ?: packsByCode.values.firstOrNull()
            ?: error("FillKit has no locale packs registered")
        return FillLocaleResolution(fallback, requested, FillLocaleMatch.Default)
    }

    private fun resolveCountry(country: String): FillLocaleResolution {
        byRegion(country)?.let { return FillLocaleResolution(it, country, FillLocaleMatch.Region) }
        val fallback = packsByCode[normalize(defaultCode)]
            ?: packsByCode.values.firstOrNull()
            ?: error("FillKit has no locale packs registered")
        return FillLocaleResolution(fallback, country, FillLocaleMatch.Default)
    }

    /** Prefers the country's own majority pack; ordering is stable across runs. */
    private fun byRegion(region: String): FillLocalePack? =
        packsByCode.values.filter { it.code.substringAfter('-', "").equals(region, ignoreCase = true) }
            .minByOrNull { it.code }

    private fun byLanguage(language: String): FillLocalePack? =
        packsByCode.values.filter { it.language == language }.minByOrNull { it.code }

    companion object {
        fun normalize(tag: String): String {
            val parts = tag.trim().replace('_', '-').split('-')
            return if (parts.size >= 2) "${parts[0].lowercase()}-${parts[1].uppercase()}" else parts[0].lowercase()
        }
    }
}
