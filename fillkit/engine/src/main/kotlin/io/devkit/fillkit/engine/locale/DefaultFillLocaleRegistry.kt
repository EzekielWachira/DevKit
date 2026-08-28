package io.devkit.fillkit.engine.locale

import io.devkit.fillkit.FillAddressLocaleData
import io.devkit.fillkit.FillBusinessLocaleData
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillLocalePack
import io.devkit.fillkit.FillLocaleRegistry
import io.devkit.fillkit.FillPersonLocaleData
import io.devkit.fillkit.FillSemanticAliasData

/**
 * Resolves a locale request to one fully composed pack.
 *
 * Composition is explicit and layered: a pack declared with `extends` is merged
 * onto its parent, then application packs override built-ins for the same code,
 * then form-scoped packs override those. Sections are merged field by field, so
 * a variant can supply only names and inherit phone, postal and geography.
 */
class DefaultFillLocaleRegistry(
    customPacks: List<FillLocalePack> = emptyList(),
    private val defaultCode: String = "en-US",
) : FillLocaleRegistry {

    private val composed: Map<String, FillLocalePack>
    private val fallback: LocaleFallbackResolver

    init {
        val declared = LinkedHashMap<String, FillLocalePack>()
        BuiltInLocalePacks.all.forEach { declared[normalize(it.code)] = it }
        // Later packs override earlier ones for the same code rather than being
        // silently combined; the caller controls the order.
        customPacks.forEach { pack ->
            val key = normalize(pack.code)
            declared[key] = declared[key]?.let { existing -> pack.layeredOn(existing) } ?: pack
        }
        composed = declared.mapValues { (key, pack) -> pack.resolveInheritance(declared, key) }
        fallback = LocaleFallbackResolver(composed, defaultCode)
        require(normalize(defaultCode) in composed) { "unknown default locale: $defaultCode" }
    }

    override fun availableLocales(): List<FillLocalePack> =
        composed.values.sortedWith(compareBy({ it.region.ordinal }, { it.displayName }))

    override fun resolve(locale: FillLocale): FillLocalePack = resolution(locale).pack

    fun resolution(locale: FillLocale, systemTag: String? = null): FillLocaleResolution =
        fallback.resolve(locale, systemTag)

    fun resolution(tag: String): FillLocaleResolution = fallback.resolveTag(tag)

    /** Walks the `extends` chain, rejecting cycles rather than looping forever. */
    private fun FillLocalePack.resolveInheritance(
        declared: Map<String, FillLocalePack>,
        key: String,
        seen: MutableSet<String> = linkedSetOf(),
    ): FillLocalePack {
        if (!seen.add(key)) {
            val cycle = (seen.toList() + key).joinToString(" -> ")
            throw IllegalStateException("FillKit locale pack inheritance cycle detected: $cycle")
        }
        val parentCode = extends ?: return this
        val parentKey = normalize(parentCode)
        val parent = declared[parentKey]
            ?: throw IllegalArgumentException("locale pack \"$code\" extends unknown locale \"$parentCode\"")
        return layeredOn(parent.resolveInheritance(declared, parentKey, seen), inheritance = true)
    }

    private companion object {
        fun normalize(tag: String) = LocaleFallbackResolver.normalize(tag)
    }
}

/**
 * Field-by-field merge: this pack wins where it declares something.
 *
 * An override keeps the base pack's `extends`, so replacing only the names of a
 * variant does not detach it from the parent it inherits geography from.
 * Resolving inheritance clears it, which terminates the chain.
 */
internal fun FillLocalePack.layeredOn(base: FillLocalePack, inheritance: Boolean = false): FillLocalePack = copy(
    person = person.layeredOn(base.person),
    address = address.layeredOn(base.address),
    phoneData = phoneData ?: base.phoneData,
    business = business.layeredOn(base.business),
    internet = internet ?: base.internet,
    semantics = semantics.layeredOn(base.semantics),
    currencyCode = currencyCode ?: base.currencyCode,
    rightToLeft = rightToLeft || base.rightToLeft,
    extends = if (inheritance) null else extends ?: base.extends,
)

private fun FillPersonLocaleData?.layeredOn(base: FillPersonLocaleData?): FillPersonLocaleData? {
    if (this == null) return base
    if (base == null) return this
    return copy(
        givenNames = givenNames.ifEmpty { base.givenNames },
        familyNames = familyNames.ifEmpty { base.familyNames },
        middleNames = middleNames.ifEmpty { base.middleNames },
        prefixes = prefixes.ifEmpty { base.prefixes },
        suffixes = suffixes.ifEmpty { base.suffixes },
        latin = base.latin + latin,
    )
}

private fun FillAddressLocaleData?.layeredOn(base: FillAddressLocaleData?): FillAddressLocaleData? {
    if (this == null) return base
    if (base == null) return this
    return copy(
        cities = cities.ifEmpty { base.cities },
        administrativeAreas = administrativeAreas.ifEmpty { base.administrativeAreas },
        administrativeAreaLabel = administrativeAreaLabel
            .takeIf { it != FillAddressLocaleData().administrativeAreaLabel } ?: base.administrativeAreaLabel,
        subLocalities = subLocalities.ifEmpty { base.subLocalities },
        streetNames = streetNames.ifEmpty { base.streetNames },
        postalCodes = postalCodes.ifEmpty { base.postalCodes },
        postalCodeSupported = if (postalCodes.isEmpty() && !postalCodeSupported) {
            base.postalCodeSupported && base.postalCodes.isNotEmpty()
        } else {
            postalCodeSupported
        },
        countryCode = countryCode ?: base.countryCode,
        countryName = countryName ?: base.countryName,
        localizedCountryName = localizedCountryName ?: base.localizedCountryName,
        streetFormat = streetFormat.takeIf { it != FillAddressLocaleData().streetFormat } ?: base.streetFormat,
    )
}

private fun FillBusinessLocaleData?.layeredOn(base: FillBusinessLocaleData?): FillBusinessLocaleData? {
    if (this == null) return base
    if (base == null) return this
    return copy(
        prefixes = prefixes.ifEmpty { base.prefixes },
        suffixes = suffixes.ifEmpty { base.suffixes },
        jobTitles = jobTitles.ifEmpty { base.jobTitles },
    )
}

private fun FillSemanticAliasData?.layeredOn(base: FillSemanticAliasData?): FillSemanticAliasData? {
    if (this == null) return base
    if (base == null) return this
    return FillSemanticAliasData(base.aliases + aliases)
}

@Suppress("unused")
@Deprecated("Use DefaultFillLocaleRegistry", ReplaceWith("DefaultFillLocaleRegistry()"))
typealias LocaleDataRegistry = DefaultFillLocaleRegistry
