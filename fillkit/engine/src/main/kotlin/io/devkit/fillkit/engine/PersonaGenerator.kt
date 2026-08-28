package io.devkit.fillkit.engine

import io.devkit.fillkit.FillDate
import io.devkit.fillkit.FillLocalePack
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
        val firstName = locale.firstNames.random(stream("firstName"))
        val lastName = locale.lastNames.random(stream("lastName"))
        val username = normalize("$firstName.$lastName")
        val emailRandom = stream("email")
        val emailSuffix = emailRandom.nextInt(0, 5).takeIf { it != 0 }?.toString().orEmpty()
        val email = "$username$emailSuffix@${safeDomains.random(emailRandom)}"
        val birthRandom = stream("dateOfBirth")
        val age = birthRandom.nextInt(18, 66)
        val birthYear = 2025 - age
        val birthMonth = birthRandom.nextInt(1, 13)
        val birthDay = birthRandom.nextInt(1, FillDate.daysInMonth(birthYear, birthMonth) + 1)
        val companyRandom = stream("company")
        val companyPrefix = locale.companyPrefixes.random(companyRandom)
        val companySlug = normalize(companyPrefix)
        val phoneData = requireNotNull(locale.phone) { "locale ${locale.code} has no phone data after fallback" }
        val phoneRandom = stream("phone")
        val addressRandom = stream("address")
        return FakePersona(
            firstName = firstName,
            lastName = lastName,
            email = email,
            username = username,
            phoneNumber = phone(phoneRandom, phoneData.countryCode, phoneData.formats.random(phoneRandom)),
            dateOfBirth = FillDate(birthYear, birthMonth, birthDay),
            age = age,
            address = FakeAddress(
                street = "${addressRandom.nextInt(10, 999)} ${locale.streetNames.random(addressRandom)}",
                city = locale.cities.random(addressRandom),
                region = locale.regions.random(addressRandom),
                country = requireNotNull(locale.country),
                postalCode = locale.postalCodes.random(addressRandom),
            ),
            company = FakeCompany(
                name = "$companyPrefix ${locale.companySuffixes.random(companyRandom)}",
                jobTitle = locale.jobTitles.random(stream("jobTitle")),
                website = "https://$companySlug.example.com",
            ),
        )
    }

    companion object {
        val safeDomains = listOf("example.com", "example.org", "example.net")

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
