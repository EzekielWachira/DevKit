package io.devkit.fillkit

/**
 * A generated address, modelled the way locales actually differ rather than as
 * street/city/state/ZIP. [administrativeArea] carries whatever the locale calls
 * that level; the label lives on the locale pack.
 */
data class FillAddress(
    val lines: List<String>,
    val locality: String? = null,
    val subLocality: String? = null,
    val administrativeArea: String? = null,
    val postalCode: String? = null,
    val countryCode: String? = null,
    val countryName: String? = null,
) {
    fun singleLine(): String = buildList {
        addAll(lines)
        subLocality?.let(::add)
        locality?.let(::add)
        administrativeArea?.let(::add)
        postalCode?.let(::add)
        countryName?.let(::add)
    }.filter(String::isNotBlank).joinToString(", ")
}

/**
 * Locale-aware full names.
 *
 * Given-first with a space is one convention among several: Japanese and Chinese
 * put the family name first, Spanish-speaking locales use two surnames, and some
 * scripts join without a separator.
 */
object FillNameFormatter {

    fun fullName(person: FillPersonLocaleData?, given: String, family: List<String>): String {
        val data = person ?: FillPersonLocaleData()
        val surnames = family.take(data.familyNameCount.coerceAtLeast(1)).filter(String::isNotBlank)
        val parts = when (data.order) {
            FillNameOrder.GivenFirst -> listOf(given) + surnames
            FillNameOrder.FamilyFirst -> surnames + listOf(given)
        }
        return parts.filter(String::isNotBlank).joinToString(data.separator).trim()
    }

    /**
     * A machine-safe handle for emails and usernames.
     *
     * Non-Latin names use the pack's own Latin forms when it supplies them; if it
     * cannot transliterate, a deterministic synthetic handle is used rather than
     * a malformed address.
     */
    fun username(
        person: FillPersonLocaleData?,
        given: String,
        family: String,
        style: FillUsernameStyle = FillUsernameStyle.DottedLatin,
        fallbackSeed: Long = 0L,
    ): String {
        val data = person ?: FillPersonLocaleData()
        val latinGiven = data.latin[given] ?: given
        val latinFamily = data.latin[family] ?: family
        val separator = if (style == FillUsernameStyle.HyphenLatin) "-" else "."
        val handle = normalize("$latinGiven$separator$latinFamily", separator)
        if (handle.length >= MIN_HANDLE_LENGTH) return handle
        // Nothing survived normalisation (a fully non-Latin name with no Latin
        // form): fall back to a stable synthetic handle instead of an invalid one.
        val suffix = (fallbackSeed.toULong() % 100_000u).toString().padStart(5, '0')
        return "user$suffix"
    }

    /** Lowercase, ASCII-safe, collapsed separators, no leading or trailing punctuation. */
    fun normalize(value: String, separator: String = "."): String {
        val separatorChar = separator.firstOrNull() ?: '.'
        val mapped = value.lowercase().map { char ->
            when {
                char.isAsciiLetterOrDigit() -> char
                else -> separatorChar
            }
        }.joinToString("")
        val collapsed = Regex("\\$separatorChar{2,}").replace(mapped, separator)
        return collapsed.trim(separatorChar)
    }

    private const val MIN_HANDLE_LENGTH = 3

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in '0'..'9'
}

/** Locale-aware phone rendering from one generated national number. */
object FillPhoneFormatter {

    fun format(
        data: FillPhoneLocaleData,
        nationalNumber: String,
        format: FillPhoneNumberFormat = FillPhoneNumberFormat.International,
    ): String {
        val digits = nationalNumber.filter(Char::isDigit)
        return when (format) {
            FillPhoneNumberFormat.E164 -> "${data.countryCallingCode}$digits"
            FillPhoneNumberFormat.International ->
                listOf(data.countryCallingCode, group(digits, data.grouping)).joinToString(" ").trim()
            FillPhoneNumberFormat.National ->
                "${data.nationalPrefix}${group(digits, data.grouping)}".trim()
        }
    }

    /** Splits digits by the locale's grouping, keeping any remainder as one block. */
    fun group(digits: String, grouping: List<Int>): String {
        if (grouping.isEmpty()) return digits
        val blocks = mutableListOf<String>()
        var index = 0
        grouping.forEach { size ->
            if (index >= digits.length) return@forEach
            blocks += digits.substring(index, (index + size).coerceAtMost(digits.length))
            index += size
        }
        if (index < digits.length) blocks += digits.substring(index)
        return blocks.filter(String::isNotEmpty).joinToString(" ")
    }
}

/** Builds a [FillAddress] from locale data plus already-chosen parts. */
object FillAddressFormatter {

    fun build(
        data: FillAddressLocaleData?,
        streetNumber: String,
        streetName: String,
        city: String?,
        subLocality: String?,
        administrativeArea: String?,
        postalCode: String?,
    ): FillAddress {
        val address = data ?: FillAddressLocaleData()
        val line = address.streetFormat
            .replace("{number}", streetNumber)
            .replace("{street}", streetName)
            .trim()
        return FillAddress(
            lines = listOf(line).filter(String::isNotBlank),
            locality = city,
            subLocality = subLocality,
            administrativeArea = administrativeArea,
            postalCode = postalCode.takeIf { address.postalCodeSupported },
            countryCode = address.countryCode,
            countryName = address.countryName,
        )
    }
}
