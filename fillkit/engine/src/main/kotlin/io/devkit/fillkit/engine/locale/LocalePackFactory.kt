package io.devkit.fillkit.engine.locale

import io.devkit.fillkit.FillAddressLocaleData
import io.devkit.fillkit.FillBusinessLocaleData
import io.devkit.fillkit.FillContentHint
import io.devkit.fillkit.FillInternetLocaleData
import io.devkit.fillkit.FillLocalePack
import io.devkit.fillkit.FillLocaleRegion
import io.devkit.fillkit.FillNameOrder
import io.devkit.fillkit.FillPersonLocaleData
import io.devkit.fillkit.FillPhoneLocaleData
import io.devkit.fillkit.FillPhonePattern
import io.devkit.fillkit.FillSemanticAliasData
import io.devkit.fillkit.FillUsernameStyle

/**
 * Compact constructor for the built-in packs.
 *
 * Every locale is a representative synthetic dataset plus procedural
 * combination rather than a bundled corpus, which keeps the debug artifact
 * small. Nothing here is real personal data: names are common public forenames
 * and surnames, places are public geography, and no address is a real one.
 */
internal fun localePack(
    code: String,
    display: String,
    region: FillLocaleRegion,
    country: String,
    calling: String,
    phonePrefixes: String,
    subscriberDigits: Int,
    grouping: List<Int>,
    given: String,
    family: String,
    cities: String,
    areas: String,
    areaLabel: String,
    postal: String?,
    streets: String,
    companySuffixes: String,
    jobTitles: String,
    currency: String,
    version: String = BUILT_IN_VERSION,
    localizedCountry: String? = null,
    order: FillNameOrder = FillNameOrder.GivenFirst,
    familyNameCount: Int = 1,
    separator: String = " ",
    latin: Map<String, String> = emptyMap(),
    usernameStyle: FillUsernameStyle = FillUsernameStyle.DottedLatin,
    rightToLeft: Boolean = false,
    nationalPrefix: String = "0",
    streetFormat: String = "{number} {street}",
    aliases: Map<String, FillContentHint> = emptyMap(),
    subLocalities: String? = null,
): FillLocalePack = FillLocalePack(
    code = code,
    displayName = display,
    id = "builtin-${code.lowercase()}",
    version = version,
    region = region,
    rightToLeft = rightToLeft,
    currencyCode = currency,
    person = FillPersonLocaleData(
        givenNames = given.csv(),
        familyNames = family.csv(),
        prefixes = emptyList(),
        order = order,
        familyNameCount = familyNameCount,
        separator = separator,
        latin = latin,
    ),
    address = FillAddressLocaleData(
        cities = cities.csv(),
        administrativeAreas = areas.csv(),
        administrativeAreaLabel = areaLabel,
        subLocalities = subLocalities?.csv().orEmpty(),
        streetNames = streets.csv(),
        postalCodes = postal?.csv().orEmpty(),
        postalCodeSupported = postal != null,
        countryCode = code.substringAfter('-', "").uppercase().ifEmpty { null },
        countryName = country,
        localizedCountryName = localizedCountry,
        streetFormat = streetFormat,
    ),
    phoneData = FillPhoneLocaleData(
        countryCallingCode = calling,
        patterns = listOf(FillPhonePattern(phonePrefixes.csv(), subscriberDigits)),
        nationalPrefix = nationalPrefix,
        grouping = grouping,
    ),
    business = FillBusinessLocaleData(
        prefixes = COMPANY_PREFIXES,
        suffixes = companySuffixes.csv(),
        jobTitles = jobTitles.csv(),
    ),
    internet = FillInternetLocaleData(usernameStyle = usernameStyle),
    semantics = aliases.takeIf(Map<String, FillContentHint>::isNotEmpty)?.let(::FillSemanticAliasData),
)

internal const val BUILT_IN_VERSION = "1"

/** Neutral, obviously synthetic company name stems shared by every locale. */
private val COMPANY_PREFIXES = listOf("Northstar", "Prime", "Urban", "Summit", "Meridian", "Atlas")

internal fun String.csv(): List<String> = split(',').map(String::trim).filter(String::isNotEmpty)
