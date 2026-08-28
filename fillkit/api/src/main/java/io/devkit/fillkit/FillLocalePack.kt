package io.devkit.fillkit

/** Optional phone-generation rules supplied by a locale pack. */
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

/** Modular locale data. Every dataset is optional and falls back independently. */
data class FillLocalePack(
    val code: String,
    val displayName: String,
    val firstNames: List<String> = emptyList(),
    val lastNames: List<String> = emptyList(),
    val cities: List<String> = emptyList(),
    val regions: List<String> = emptyList(),
    val country: String? = null,
    val streetNames: List<String> = emptyList(),
    val postalCodes: List<String> = emptyList(),
    val companyPrefixes: List<String> = emptyList(),
    val companySuffixes: List<String> = emptyList(),
    val jobTitles: List<String> = emptyList(),
    val phone: FillPhoneData? = null,
) {
    init {
        require(code.isNotBlank()) { "locale pack code cannot be blank" }
        require(displayName.isNotBlank()) { "locale pack display name cannot be blank" }
    }
}

interface FillLocaleRegistry {
    fun resolve(locale: FillLocale): FillLocalePack
    fun availableLocales(): List<FillLocalePack>
}

fun fillLocalePack(code: String, displayName: String, block: FillLocalePackBuilder.() -> Unit): FillLocalePack =
    FillLocalePackBuilder().apply(block).build(code, displayName)

class FillLocalePackBuilder internal constructor() {
    private var firstNames = emptyList<String>()
    private var lastNames = emptyList<String>()
    private var cities = emptyList<String>()
    private var regions = emptyList<String>()
    private var country: String? = null
    private var streetNames = emptyList<String>()
    private var postalCodes = emptyList<String>()
    private var companyPrefixes = emptyList<String>()
    private var companySuffixes = emptyList<String>()
    private var jobTitles = emptyList<String>()
    private var phone: FillPhoneData? = null

    fun firstNames(vararg values: String) { firstNames = clean(values) }
    fun lastNames(vararg values: String) { lastNames = clean(values) }
    fun cities(vararg values: String) { cities = clean(values) }
    fun regions(vararg values: String) { regions = clean(values) }
    fun country(value: String) { country = value.requireContent("country") }
    fun streetNames(vararg values: String) { streetNames = clean(values) }
    fun postalCodes(vararg values: String) { postalCodes = clean(values) }
    fun companyPrefixes(vararg values: String) { companyPrefixes = clean(values) }
    fun companySuffixes(vararg values: String) { companySuffixes = clean(values) }
    fun jobTitles(vararg values: String) { jobTitles = clean(values) }
    fun phone(block: FillPhoneBuilder.() -> Unit) { phone = FillPhoneBuilder().apply(block).build() }

    internal fun build(code: String, name: String) = FillLocalePack(
        code, name, firstNames, lastNames, cities, regions, country, streetNames,
        postalCodes, companyPrefixes, companySuffixes, jobTitles, phone,
    )

    private fun clean(values: Array<out String>): List<String> = values.map { it.requireContent("locale value") }
    private fun String.requireContent(label: String): String = apply { require(isNotBlank()) { "$label cannot be blank" } }
}

class FillPhoneBuilder internal constructor() {
    var countryCode: String = ""
    private var formats = emptyList<String>()
    fun formats(vararg values: String) { formats = values.toList() }
    internal fun build() = FillPhoneData(countryCode, formats)
}
