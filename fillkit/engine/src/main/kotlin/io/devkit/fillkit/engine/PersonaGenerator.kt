package io.devkit.fillkit.engine

import io.devkit.fillkit.FillAddressFormatter
import io.devkit.fillkit.FillDate
import io.devkit.fillkit.FillLocalePack
import io.devkit.fillkit.FillNameFormatter
import io.devkit.fillkit.FillPhoneFormatter
import io.devkit.fillkit.FillPhoneNumberFormat
import io.devkit.fillkit.FillUsernameStyle
import kotlin.random.Random

class PersonaGenerator {
    /** Legacy single-stream generation, kept for the 0.1 [FakeDataEngine]. */
    fun generate(random: Random, locale: FillLocalePack): FakePersona =
        generate(locale) { random }

    /**
     * Each persona attribute draws from its own derived stream, so adding an
     * attribute later cannot change the ones that already existed. Dependent
     * values (username, email, website) stay derived from the chosen name.
     */
    fun generate(source: FillRandomSource, locale: FillLocalePack, variant: String? = null): FakePersona =
        generate(locale) { attribute ->
            source.stream(FillRandomSource.PERSONA, locale.code, variant, attribute)
        }

    private inline fun generate(locale: FillLocalePack, stream: (String) -> Random): FakePersona {
        val person = locale.person
        val givenName = locale.firstNames.pick(stream("firstName"), "Alex")
        val familyName = locale.lastNames.pick(stream("lastName"), "Doe")
        val extraFamily = locale.lastNames.pick(stream("secondFamilyName"), familyName)
        val familyNames = listOf(familyName, extraFamily)
        val formattedName = FillNameFormatter.fullName(person, givenName, familyNames)

        val emailRandom = stream("email")
        val style = locale.internet?.usernameStyle ?: FillUsernameStyle.DottedLatin
        val username = FillNameFormatter.username(
            person = person,
            given = givenName,
            family = familyName,
            style = style,
            fallbackSeed = emailRandom.nextLong(),
        )
        val emailSuffix = emailRandom.nextInt(0, 5).takeIf { it != 0 }?.toString().orEmpty()
        val domains = locale.internet?.emailDomains ?: safeDomains
        val email = "$username$emailSuffix@${domains.pick(emailRandom, "example.com")}"

        val birthRandom = stream("dateOfBirth")
        val age = birthRandom.nextInt(18, 66)
        val birthYear = 2025 - age
        val birthMonth = birthRandom.nextInt(1, 13)
        val birthDay = birthRandom.nextInt(1, FillDate.daysInMonth(birthYear, birthMonth) + 1)

        val companyRandom = stream("company")
        val companyPrefix = locale.companyPrefixes.pick(companyRandom, "Northstar")
        val companySlug = normalize(FillNameFormatter.normalize(companyPrefix))

        val phoneRandom = stream("phone")
        val nationalDigits = nationalNumber(locale, phoneRandom)
        val phoneNumber = locale.phoneData
            ?.let { FillPhoneFormatter.format(it, nationalDigits, FillPhoneNumberFormat.International) }
            ?: nationalDigits

        val addressRandom = stream("address")
        val address = FillAddressFormatter.build(
            data = locale.address,
            streetNumber = addressRandom.nextInt(1, 999).toString(),
            streetName = locale.streetNames.pick(addressRandom, "Example Street"),
            city = locale.cities.pick(addressRandom, "Springfield"),
            subLocality = locale.address?.subLocalities?.pickOrNull(addressRandom),
            administrativeArea = locale.regions.pick(addressRandom, ""),
            postalCode = locale.postalCodes.pickOrNull(addressRandom),
        )

        return FakePersona(
            firstName = givenName,
            lastName = familyName,
            email = email,
            username = username,
            phoneNumber = phoneNumber,
            dateOfBirth = FillDate(birthYear, birthMonth, birthDay),
            age = age,
            address = FakeAddress(
                street = address.lines.firstOrNull().orEmpty(),
                city = address.locality.orEmpty(),
                region = address.administrativeArea.orEmpty(),
                country = locale.country ?: locale.countryCode,
                postalCode = address.postalCode.orEmpty(),
            ),
            company = FakeCompany(
                name = "$companyPrefix ${locale.companySuffixes.pick(companyRandom, "Ltd")}",
                jobTitle = locale.jobTitles.pick(stream("jobTitle"), "Software Engineer"),
                website = "https://${companySlug.ifEmpty { "example" }}.example.com",
            ),
            formattedFullName = formattedName,
            nationalPhoneDigits = nationalDigits,
            localizedCountry = locale.address?.localizedCountryName,
        )
    }

    companion object {
        val safeDomains = listOf("example.com", "example.org", "example.net")

        /** National digits for a locale, honouring its declared prefixes and length. */
        fun nationalNumber(locale: FillLocalePack, random: Random): String {
            val data = locale.phoneData ?: return buildString { repeat(9) { append(random.nextInt(10)) } }
            val pattern = data.patterns.pickOrNull(random)
            val prefix = pattern?.prefixes?.pick(random, "").orEmpty()
            val digits = pattern?.subscriberDigits ?: 9
            return prefix + buildString { repeat(digits) { append(random.nextInt(10)) } }
        }

        internal fun <T> List<T>.pickOrNull(random: Random): T? = if (isEmpty()) null else random(random)

        internal fun List<String>.pick(random: Random, fallback: String): String =
            if (isEmpty()) fallback else random(random)

        fun normalize(value: String): String = value
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '.' }
            .joinToString("")
            .replace(Regex("\\.+"), ".")
            .trim('.')

        fun phone(random: Random, callingCode: String, format: String = if (callingCode == "+1") "##########" else "#########"): String = buildString {
            append(callingCode)
            format.forEach { append(if (it == '#') random.nextInt(10) else it) }
        }
    }
}
