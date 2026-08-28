package io.devkit.fillkit

/** Where the family name sits relative to the given name. */
enum class FillNameOrder { GivenFirst, FamilyFirst }

/** How a locale renders a machine-safe username from a person's name. */
enum class FillUsernameStyle {
    /** `amina.wanjiku` — Latin script joined with dots. */
    DottedLatin,

    /** `amina-wanjiku` — Latin script joined with hyphens. */
    HyphenLatin,

    /** Non-Latin scripts: transliterate if the pack can, else a stable synthetic handle. */
    Transliterated,
}

/** Requested rendering of a generated phone number. */
enum class FillPhoneNumberFormat { International, National, E164 }

/**
 * Name data for one locale.
 *
 * `given + " " + family` is not universally correct, so the pack carries the
 * order, the separator and how many family names a full name uses.
 */
data class FillPersonLocaleData(
    val givenNames: List<String> = emptyList(),
    val familyNames: List<String> = emptyList(),
    val middleNames: List<String> = emptyList(),
    val prefixes: List<String> = emptyList(),
    val suffixes: List<String> = emptyList(),
    val order: FillNameOrder = FillNameOrder.GivenFirst,
    /** Spain and much of Latin America use two surnames in a full name. */
    val familyNameCount: Int = 1,
    /** Japanese and Chinese full names join without a space in native script. */
    val separator: String = " ",
    /** Per-name Latin forms so a non-Latin locale can still build a valid email. */
    val latin: Map<String, String> = emptyMap(),
) {
    init {
        require(familyNameCount in 1..3) { "familyNameCount must be in 1..3" }
    }
}

/**
 * Address data for one locale.
 *
 * Deliberately not street/city/state/ZIP: [administrativeAreaLabel] carries the
 * regional term, and a locale may declare that it has no postal codes at all.
 */
data class FillAddressLocaleData(
    val cities: List<String> = emptyList(),
    val administrativeAreas: List<String> = emptyList(),
    /** County, State, Province, Prefecture, Governorate… */
    val administrativeAreaLabel: String = "Region",
    val streetNames: List<String> = emptyList(),
    val subLocalities: List<String> = emptyList(),
    val postalCodes: List<String> = emptyList(),
    val postalCodeSupported: Boolean = true,
    val countryCode: String? = null,
    val countryName: String? = null,
    /** Endonym, e.g. `Deutschland` for `de-DE`. */
    val localizedCountryName: String? = null,
    /** `{number}` and `{street}` are substituted; other text is literal. */
    val streetFormat: String = "{number} {street}",
)

/** One valid national number shape: a leading prefix plus a subscriber length. */
data class FillPhonePattern(
    val prefixes: List<String>,
    val subscriberDigits: Int,
) {
    init {
        require(prefixes.isNotEmpty()) { "phone pattern needs at least one prefix" }
        require(prefixes.none(String::isBlank)) { "phone prefixes cannot be blank" }
        require(subscriberDigits in 0..12) { "subscriberDigits must be in 0..12" }
    }
}

data class FillPhoneLocaleData(
    val countryCallingCode: String,
    val patterns: List<FillPhonePattern> = emptyList(),
    /** Digit dropped or added when switching between national and international form. */
    val nationalPrefix: String = "0",
    /** Digit grouping used for the readable international form. */
    val grouping: List<Int> = emptyList(),
) {
    init {
        require(countryCallingCode.startsWith('+')) { "phone country calling code must start with +" }
    }
}

data class FillBusinessLocaleData(
    val prefixes: List<String> = emptyList(),
    /** Ltd, GmbH, SARL, Pty Ltd, S.A., 株式会社… */
    val suffixes: List<String> = emptyList(),
    val jobTitles: List<String> = emptyList(),
)

data class FillInternetLocaleData(
    val emailDomains: List<String> = SAFE_DOMAINS,
    val usernameStyle: FillUsernameStyle = FillUsernameStyle.DottedLatin,
) {
    init {
        require(emailDomains.isNotEmpty()) { "email domains cannot be empty" }
        require(emailDomains.all { it in SAFE_DOMAINS }) {
            "FillKit only generates addresses on ${SAFE_DOMAINS.joinToString()}"
        }
    }

    companion object {
        val SAFE_DOMAINS = listOf("example.com", "example.org", "example.net")
    }
}

/** Localized field labels that the 0.3 suggestion engine can recognise. */
data class FillSemanticAliasData(val aliases: Map<String, FillContentHint> = emptyMap()) {
    init {
        require(aliases.keys.none(String::isBlank)) { "semantic alias keys cannot be blank" }
    }

    /** Lowercased for matching; callers may write aliases in natural case. */
    val normalized: Map<String, FillContentHint> = aliases.mapKeys { it.key.lowercase().trim() }
}

/** Legacy phone shape kept so 0.1–0.4 packs and call sites keep working. */
data class FillPhoneData(
    val countryCode: String,
    val formats: List<String>,
) {
    init {
        require(countryCode.startsWith('+')) { "phone country code must start with +" }
        require(formats.isNotEmpty()) { "phone formats cannot be empty" }
        require(formats.all { it.count { char -> char == '#' } > 0 }) { "phone formats must contain # placeholders" }
    }
}

/**
 * Modular locale data. Every section is optional, so a pack can describe only
 * what it knows and inherit the rest.
 */
data class FillLocalePack(
    val code: String,
    val displayName: String,
    /** Stable pack identity for fingerprints and diagnostics, e.g. `builtin-kenya`. */
    val id: String = code,
    val version: String? = null,
    val person: FillPersonLocaleData? = null,
    val address: FillAddressLocaleData? = null,
    val phoneData: FillPhoneLocaleData? = null,
    val business: FillBusinessLocaleData? = null,
    val internet: FillInternetLocaleData? = null,
    val semantics: FillSemanticAliasData? = null,
    /** Code of a pack this one layers on top of; resolved by the registry. */
    val extends: String? = null,
    val rightToLeft: Boolean = false,
    /** ISO 4217, driven by region rather than language. */
    val currencyCode: String? = null,
    /** Continent-style grouping for the locale picker. */
    val region: FillLocaleRegion = FillLocaleRegion.Other,
) {
    init {
        require(code.isNotBlank()) { "locale pack code cannot be blank" }
        require(displayName.isNotBlank()) { "locale pack display name cannot be blank" }
        require(id.isNotBlank()) { "locale pack id cannot be blank" }
    }

    /** BCP-47 language subtag, e.g. `sw` in `sw-KE`. */
    val language: String get() = code.substringBefore('-').lowercase()

    /** BCP-47 region subtag, e.g. `KE` in `sw-KE`; empty for language-only packs. */
    val countryCode: String
        get() = address?.countryCode ?: code.substringAfter('-', "").uppercase()

    /** `builtin-kenya@2` when versioned, otherwise the id. */
    fun coordinate(): String = version?.let { "$id@$it" } ?: id

    /** Lowercased haystack for locale search: country, language, code, display name. */
    fun searchText(): String = listOfNotNull(
        displayName, code, language, countryCode, address?.countryName, address?.localizedCountryName,
    ).joinToString(" ").lowercase()

    // --- Flat views retained from earlier FillKit versions -------------------

    val firstNames: List<String> get() = person?.givenNames.orEmpty()
    val lastNames: List<String> get() = person?.familyNames.orEmpty()
    val cities: List<String> get() = address?.cities.orEmpty()
    val regions: List<String> get() = address?.administrativeAreas.orEmpty()
    val country: String? get() = address?.countryName
    val streetNames: List<String> get() = address?.streetNames.orEmpty()
    val postalCodes: List<String> get() = address?.postalCodes.orEmpty()
    val companyPrefixes: List<String> get() = business?.prefixes.orEmpty()
    val companySuffixes: List<String> get() = business?.suffixes.orEmpty()
    val jobTitles: List<String> get() = business?.jobTitles.orEmpty()
    val phone: FillPhoneData?
        get() = phoneData?.let { data ->
            FillPhoneData(
                countryCode = data.countryCallingCode,
                formats = data.patterns.map { pattern ->
                    pattern.prefixes.first() + "#".repeat(pattern.subscriberDigits)
                }.ifEmpty { listOf("#########") },
            )
        }
}

/** Grouping used by the locale picker so 40+ locales are never one flat list. */
enum class FillLocaleRegion(val displayName: String) {
    Africa("Africa"),
    Europe("Europe"),
    NorthAmerica("North America"),
    SouthAmerica("South America"),
    Asia("Asia"),
    MiddleEast("Middle East"),
    Oceania("Oceania"),
    Other("Other"),
}

interface FillLocaleRegistry {
    fun resolve(locale: FillLocale): FillLocalePack
    fun availableLocales(): List<FillLocalePack>
}
