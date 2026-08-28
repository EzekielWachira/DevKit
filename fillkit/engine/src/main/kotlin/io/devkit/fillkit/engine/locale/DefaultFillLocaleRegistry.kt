package io.devkit.fillkit.engine.locale

import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillLocalePack
import io.devkit.fillkit.FillLocaleRegistry

class DefaultFillLocaleRegistry(
    customPacks: List<FillLocalePack> = emptyList(),
    private val defaultCode: String = "en-US",
) : FillLocaleRegistry {
    private val builtIns = BuiltInLocalePacks.all.associateBy { normalize(it.code) }
    private val custom = customPacks.associateBy { normalize(it.code) }

    init {
        require(custom.size == customPacks.size) { "duplicate custom locale pack code" }
        require(normalize(defaultCode) in builtIns || normalize(defaultCode) in custom) { "unknown default locale: $defaultCode" }
    }

    override fun availableLocales(): List<FillLocalePack> =
        (builtIns + custom).values.sortedBy(FillLocalePack::displayName)

    override fun resolve(locale: FillLocale): FillLocalePack {
        val requested = when (locale) {
            FillLocale.System -> defaultCode
            is FillLocale.Code -> locale.value
        }
        val normalized = normalize(requested)
        val region = normalized.substringAfter('-', "")
        val keys = listOf(normalized, region.takeIf(String::isNotEmpty)?.let { "en-$it" }, normalize(defaultCode))
            .filterNotNull().distinct()
        val candidates = keys.flatMap { key -> listOfNotNull(custom[key], builtIns[key]) }.distinctBy { it.code to it.displayName }
        check(candidates.isNotEmpty()) { "FillKit has no locale fallback for $requested" }
        return candidates.drop(1).fold(candidates.first()) { current, fallback -> current.withFallback(fallback) }
    }

    private fun FillLocalePack.withFallback(fallback: FillLocalePack) = copy(
        firstNames = firstNames.ifEmpty { fallback.firstNames },
        lastNames = lastNames.ifEmpty { fallback.lastNames },
        cities = cities.ifEmpty { fallback.cities },
        regions = regions.ifEmpty { fallback.regions },
        country = country ?: fallback.country,
        streetNames = streetNames.ifEmpty { fallback.streetNames },
        postalCodes = postalCodes.ifEmpty { fallback.postalCodes },
        companyPrefixes = companyPrefixes.ifEmpty { fallback.companyPrefixes },
        companySuffixes = companySuffixes.ifEmpty { fallback.companySuffixes },
        jobTitles = jobTitles.ifEmpty { fallback.jobTitles },
        phone = phone ?: fallback.phone,
    )

    companion object {
        fun normalize(tag: String): String {
            val parts = tag.replace('_', '-').split('-')
            return if (parts.size >= 2) "${parts[0].lowercase()}-${parts[1].uppercase()}" else tag.lowercase()
        }
    }
}

@Deprecated("Use DefaultFillLocaleRegistry", ReplaceWith("DefaultFillLocaleRegistry()"))
typealias LocaleDataRegistry = DefaultFillLocaleRegistry
