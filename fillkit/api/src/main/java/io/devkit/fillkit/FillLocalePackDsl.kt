package io.devkit.fillkit

/**
 * Builds a locale pack.
 *
 * Adding a country means writing one of these and registering it — no engine,
 * resolver, host or developer-UI change.
 */
fun fillLocalePack(
    code: String,
    displayName: String,
    id: String = code,
    version: String? = null,
    region: FillLocaleRegion = FillLocaleRegion.Other,
    block: FillLocalePackBuilder.() -> Unit = {},
): FillLocalePack = FillLocalePackBuilder().apply(block).build(code, displayName, id, version, region)

class FillLocalePackBuilder internal constructor() {
    private var person: FillPersonLocaleData? = null
    private var address: FillAddressLocaleData? = null
    private var phone: FillPhoneLocaleData? = null
    private var business: FillBusinessLocaleData? = null
    private var internet: FillInternetLocaleData? = null
    private var semantics: FillSemanticAliasData? = null
    private var extends: String? = null
    private var rightToLeft = false
    private var currency: String? = null

    /** Layer this pack on top of another; the registry resolves and cycle-checks it. */
    fun extends(code: String) {
        require(code.isNotBlank()) { "extended locale code cannot be blank" }
        extends = code
    }

    fun extends(pack: FillLocalePack) = extends(pack.code)

    fun rightToLeft(value: Boolean = true) { rightToLeft = value }

    fun currency(code: String) {
        require(code.length == 3) { "currency must be a 3-letter ISO 4217 code" }
        currency = code.uppercase()
    }

    fun person(block: FillPersonBuilder.() -> Unit) {
        person = FillPersonBuilder(person).apply(block).build()
    }

    fun location(block: FillAddressBuilder.() -> Unit) {
        address = FillAddressBuilder(address).apply(block).build()
    }

    fun phone(block: FillPhoneBuilder.() -> Unit) {
        phone = FillPhoneBuilder(phone).apply(block).build()
    }

    fun business(block: FillBusinessBuilder.() -> Unit) {
        business = FillBusinessBuilder(business).apply(block).build()
    }

    fun internet(block: FillInternetBuilder.() -> Unit) {
        internet = FillInternetBuilder(internet).apply(block).build()
    }

    /** Localized field labels the suggestion engine should recognise. */
    fun semanticAliases(block: FillSemanticAliasBuilder.() -> Unit) {
        semantics = FillSemanticAliasBuilder(semantics).apply(block).build()
    }

    // --- Flat shortcuts, unchanged from earlier FillKit versions -------------

    fun firstNames(vararg values: String) = person { givenNames(*values) }
    fun lastNames(vararg values: String) = person { familyNames(*values) }
    fun cities(vararg values: String) = location { cities(*values) }
    fun regions(vararg values: String) = location { administrativeAreas(*values) }
    fun country(value: String) = location { country(value) }
    fun streetNames(vararg values: String) = location { streetNames(*values) }
    fun postalCodes(vararg values: String) = location { postalCodes(*values) }
    fun companyPrefixes(vararg values: String) = business { prefixes(*values) }
    fun companySuffixes(vararg values: String) = business { suffixes(*values) }
    fun jobTitles(vararg values: String) = business { jobTitles(*values) }

    internal fun build(
        code: String,
        displayName: String,
        id: String,
        version: String?,
        region: FillLocaleRegion,
    ) = FillLocalePack(
        code = code,
        displayName = displayName,
        id = id,
        version = version,
        person = person,
        address = address,
        phoneData = phone,
        business = business,
        internet = internet,
        semantics = semantics,
        extends = extends,
        rightToLeft = rightToLeft,
        currencyCode = currency,
        region = region,
    )
}

class FillPersonBuilder internal constructor(private val current: FillPersonLocaleData?) {
    private var data = current ?: FillPersonLocaleData()

    fun givenNames(vararg values: String) { data = data.copy(givenNames = clean(values)) }
    fun familyNames(vararg values: String) { data = data.copy(familyNames = clean(values)) }
    fun middleNames(vararg values: String) { data = data.copy(middleNames = clean(values)) }
    fun prefixes(vararg values: String) { data = data.copy(prefixes = clean(values)) }
    fun suffixes(vararg values: String) { data = data.copy(suffixes = clean(values)) }
    fun order(value: FillNameOrder) { data = data.copy(order = value) }
    fun familyNameCount(value: Int) { data = data.copy(familyNameCount = value) }
    fun separator(value: String) { data = data.copy(separator = value) }

    /** Latin forms for non-Latin names, used when building an email username. */
    fun latin(vararg pairs: Pair<String, String>) { data = data.copy(latin = data.latin + pairs) }

    internal fun build() = data
}

class FillAddressBuilder internal constructor(current: FillAddressLocaleData?) {
    private var data = current ?: FillAddressLocaleData()

    fun cities(vararg values: String) { data = data.copy(cities = clean(values)) }
    fun administrativeAreas(vararg values: String) { data = data.copy(administrativeAreas = clean(values)) }
    fun administrativeAreaLabel(value: String) { data = data.copy(administrativeAreaLabel = value) }
    fun subLocalities(vararg values: String) { data = data.copy(subLocalities = clean(values)) }
    fun streetNames(vararg values: String) { data = data.copy(streetNames = clean(values)) }
    fun streetFormat(value: String) { data = data.copy(streetFormat = value) }
    fun postalCodes(vararg values: String) { data = data.copy(postalCodes = clean(values)) }

    /** Declare that this locale does not use postal codes rather than inventing some. */
    fun noPostalCodes() { data = data.copy(postalCodes = emptyList(), postalCodeSupported = false) }

    fun countryCode(value: String) { data = data.copy(countryCode = value.uppercase()) }
    fun country(value: String) { data = data.copy(countryName = value) }
    fun localizedCountry(value: String) { data = data.copy(localizedCountryName = value) }

    internal fun build() = data
}

class FillPhoneBuilder internal constructor(current: FillPhoneLocaleData? = null) {
    private var callingCode: String = current?.countryCallingCode.orEmpty()
    private val patterns = current?.patterns.orEmpty().toMutableList()
    private var nationalPrefix: String = current?.nationalPrefix ?: "0"
    private var grouping: List<Int> = current?.grouping.orEmpty()

    /** Legacy property form: `countryCode = "+254"`. */
    var countryCode: String
        get() = callingCode
        set(value) { callingCode = value }

    fun countryCode(value: String) { callingCode = value }
    fun countryCallingCode(value: String) { callingCode = value }
    fun nationalPrefix(value: String) { nationalPrefix = value }
    fun grouping(vararg sizes: Int) { grouping = sizes.toList() }

    fun pattern(prefixes: List<String>, subscriberDigits: Int) {
        patterns += FillPhonePattern(prefixes, subscriberDigits)
    }

    fun pattern(vararg prefixes: String, subscriberDigits: Int) =
        pattern(prefixes.toList(), subscriberDigits)

    /** Legacy `#`-template form; converted to a prefix plus a digit count. */
    fun formats(vararg values: String) {
        values.forEach { format ->
            val prefix = format.takeWhile { it != '#' }
            val digits = format.count { it == '#' }
            patterns += FillPhonePattern(listOf(prefix.ifEmpty { "" }.ifEmpty { "0" }), digits)
        }
    }

    internal fun build(): FillPhoneLocaleData {
        require(callingCode.isNotBlank()) { "phone data requires a country calling code" }
        return FillPhoneLocaleData(callingCode, patterns.toList(), nationalPrefix, grouping)
    }
}

class FillBusinessBuilder internal constructor(current: FillBusinessLocaleData?) {
    private var data = current ?: FillBusinessLocaleData()

    fun prefixes(vararg values: String) { data = data.copy(prefixes = clean(values)) }
    fun suffixes(vararg values: String) { data = data.copy(suffixes = clean(values)) }
    fun jobTitles(vararg values: String) { data = data.copy(jobTitles = clean(values)) }

    internal fun build() = data
}

class FillInternetBuilder internal constructor(current: FillInternetLocaleData?) {
    private var data = current ?: FillInternetLocaleData()

    fun emailDomains(vararg values: String) { data = data.copy(emailDomains = values.toList()) }
    fun usernameStyle(value: FillUsernameStyle) { data = data.copy(usernameStyle = value) }

    internal fun build() = data
}

class FillSemanticAliasBuilder internal constructor(current: FillSemanticAliasData?) {
    private val aliases = current?.aliases.orEmpty().toMutableMap()

    infix fun String.mapsTo(hint: FillContentHint) { aliases[this] = hint }
    fun alias(label: String, hint: FillContentHint) { aliases[label] = hint }

    internal fun build() = FillSemanticAliasData(aliases.toMap())
}

private fun clean(values: Array<out String>): List<String> =
    values.map { it.also { value -> require(value.isNotBlank()) { "locale value cannot be blank" } } }
