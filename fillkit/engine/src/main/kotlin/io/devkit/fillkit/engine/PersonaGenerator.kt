package io.devkit.fillkit.engine

import io.devkit.fillkit.FillDate
import io.devkit.fillkit.engine.locale.LocaleData
import kotlin.random.Random

class PersonaGenerator {
    fun generate(random: Random, locale: LocaleData): FakePersona {
        val firstName = locale.firstNames.random(random)
        val lastName = locale.lastNames.random(random)
        val username = normalize("$firstName.$lastName")
        val emailSuffix = random.nextInt(0, 5).takeIf { it != 0 }?.toString().orEmpty()
        val email = "$username$emailSuffix@${safeDomains.random(random)}"
        val age = random.nextInt(18, 66)
        val birthYear = 2025 - age
        val birthMonth = random.nextInt(1, 13)
        val birthDay = random.nextInt(1, FillDate.daysInMonth(birthYear, birthMonth) + 1)
        val companySlug = normalize(locale.companyPrefixes.random(random))
        return FakePersona(
            firstName = firstName,
            lastName = lastName,
            email = email,
            username = username,
            phoneNumber = phone(random, locale.countryCallingCode),
            dateOfBirth = FillDate(birthYear, birthMonth, birthDay),
            age = age,
            address = FakeAddress(
                street = "${random.nextInt(10, 999)} ${locale.streetNames.random(random)}",
                city = locale.cities.random(random),
                region = locale.regions.random(random),
                country = locale.country,
                postalCode = locale.postalCodes.random(random),
            ),
            company = FakeCompany(
                name = "${locale.companyPrefixes.random(random)} ${locale.companySuffixes.random(random)}",
                jobTitle = locale.jobTitles.random(random),
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

        fun phone(random: Random, callingCode: String): String = buildString {
            append(callingCode)
            repeat(if (callingCode == "+1") 10 else 9) { append(random.nextInt(10)) }
        }
    }
}
