package io.devkit.fillkit.engine

import io.devkit.fillkit.FillContentHint
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillLocaleRegion
import io.devkit.fillkit.FillNameOrder
import io.devkit.fillkit.FillPhoneNumberFormat
import io.devkit.fillkit.FillSeed
import io.devkit.fillkit.FillType
import io.devkit.fillkit.FillValue
import io.devkit.fillkit.engine.locale.BuiltInLocalePacks
import io.devkit.fillkit.engine.locale.DefaultFillLocaleRegistry
import io.devkit.fillkit.engine.locale.FillLocaleMatch
import io.devkit.fillkit.fillLocalePack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Representative coverage across every writing system and data model FillKit ships. */
class GlobalLocaleTest {

    private val registry = DefaultFillLocaleRegistry()

    private val representative = listOf(
        "en-KE", "en-US", "fr-FR", "de-DE", "es-MX", "pt-BR",
        "hi-IN", "ja-JP", "zh-CN", "ko-KR", "ar-SA",
    )

    private fun resolver(code: String, seed: Long = 845912L) = FillValueResolver(
        registry.resolve(FillLocale.Code(code)),
        emptyList(),
        FillSeed(seed),
        formId = "form",
    )

    private fun persona(code: String, seed: Long = 845912L) = resolver(code, seed).generatedPersona()

    private fun text(code: String, key: String, seed: Long = 845912L) =
        (persona(code, seed).values.getValue(key) as FillValue.Text).value

    // --- Coverage -------------------------------------------------------------

    @Test
    fun coversEveryRegion() {
        val regions = BuiltInLocalePacks.all.map { it.region }.toSet()
        assertTrue(FillLocaleRegion.Africa in regions)
        assertTrue(FillLocaleRegion.Europe in regions)
        assertTrue(FillLocaleRegion.NorthAmerica in regions)
        assertTrue(FillLocaleRegion.SouthAmerica in regions)
        assertTrue(FillLocaleRegion.Asia in regions)
        assertTrue(FillLocaleRegion.MiddleEast in regions)
        assertTrue(FillLocaleRegion.Oceania in regions)
    }

    @Test
    fun everyRepresentativeLocaleGeneratesACompletePersona() {
        representative.forEach { code ->
            val values = persona(code).values
            listOf("firstName", "lastName", "fullName", "email", "phone", "city", "country").forEach { key ->
                val value = (values[key] as? FillValue.Text)?.value
                assertTrue("$code has no $key", !value.isNullOrBlank())
            }
        }
    }

    @Test
    fun localePackCodesAreUniqueAndWellFormed() {
        val codes = BuiltInLocalePacks.all.map { it.code }
        assertEquals(codes.size, codes.distinct().size)
        codes.forEach { code ->
            assertTrue("$code is not a language-region tag", Regex("[a-z]{2,3}-[A-Z]{2}").matches(code))
        }
    }

    // --- Unicode and names ----------------------------------------------------

    @Test
    fun nonLatinNamesAreNotFlattenedIntoAscii() {
        assertTrue("Japanese given name should stay in kana or kanji", text("ja-JP", "firstName").any { it.code > 0x2E80 })
        assertTrue(text("zh-CN", "lastName").any { it.code > 0x2E80 })
        assertTrue(text("ko-KR", "firstName").any { it.code in 0xAC00..0xD7A3 })
        assertTrue(text("hi-IN", "firstName").any { it.code in 0x0900..0x097F })
        assertTrue(text("ar-SA", "firstName").any { it.code in 0x0600..0x06FF })
    }

    @Test
    fun fullNameFollowsTheLocalesOwnOrder() {
        val japanese = persona("ja-JP")
        val given = (japanese.values.getValue("firstName") as FillValue.Text).value
        val family = (japanese.values.getValue("lastName") as FillValue.Text).value
        val full = (japanese.values.getValue("fullName") as FillValue.Text).value
        assertTrue("Japanese full name puts the family name first: $full", full.startsWith(family))
        assertTrue(full.contains(given))

        val american = persona("en-US")
        val usGiven = (american.values.getValue("firstName") as FillValue.Text).value
        assertTrue((american.values.getValue("fullName") as FillValue.Text).value.startsWith(usGiven))
    }

    @Test
    fun spanishFullNamesUseTwoSurnames() {
        val full = text("es-ES", "fullName")
        assertEquals("expected given plus two surnames in \"$full\"", 3, full.split(' ').size)
    }

    @Test
    fun koreanFullNamesJoinWithoutASeparator() {
        val pack = registry.resolve(FillLocale.Code("ko-KR"))
        assertEquals("", pack.person?.separator)
        assertEquals(FillNameOrder.FamilyFirst, pack.person?.order)
        assertTrue(!text("ko-KR", "fullName").contains(' '))
    }

    // --- Email ----------------------------------------------------------------

    @Test
    fun everyLocaleProducesAValidSafeEmail() {
        val pattern = Regex("[a-z0-9][a-z0-9.\\-]*@example\\.(com|org|net)")
        BuiltInLocalePacks.all.forEach { pack ->
            val email = text(pack.code, "email")
            assertTrue("${pack.code} produced a malformed email: $email", pattern.matches(email))
        }
    }

    @Test
    fun unicodeNamesAreTransliteratedForEmails() {
        assertTrue(text("ja-JP", "email").substringBefore('@').all { it.isLetterOrDigit() || it == '.' })
        assertTrue(text("ar-SA", "email").substringBefore('@').all { it.isLetterOrDigit() || it == '.' })
    }

    @Test
    fun anUntransliterableNameStillYieldsAValidAddress() {
        val pack = fillLocalePack("xx-XX", "Test", region = FillLocaleRegion.Other) {
            person { givenNames("日本語"); familyNames("文字") }
            location { cities("City"); country("Testland"); administrativeAreas("Area"); postalCodes("00000") }
            phone { countryCode("+99"); pattern("1", subscriberDigits = 8) }
            business { suffixes("Ltd"); jobTitles("Engineer") }
            location { streetNames("Main Street") }
        }
        val custom = DefaultFillLocaleRegistry(listOf(pack))
        val engine = FillValueResolver(custom.resolve(FillLocale.Code("xx-XX")), seed = FillSeed(1))
        val email = (engine.generatedPersona().values.getValue("email") as FillValue.Text).value
        assertTrue("expected a synthetic fallback handle, got $email", email.startsWith("user"))
        assertTrue(email.endsWith("@example.com") || email.endsWith("@example.org") || email.endsWith("@example.net"))
    }

    // --- Phone ----------------------------------------------------------------

    @Test
    fun phoneNumbersCarryTheLocalesCallingCode() {
        mapOf(
            "en-KE" to "+254", "en-NG" to "+234", "en-GB" to "+44", "fr-FR" to "+33",
            "en-IN" to "+91", "ja-JP" to "+81", "pt-BR" to "+55", "ar-SA" to "+966",
        ).forEach { (code, calling) ->
            assertTrue("$code phone should start with $calling", text(code, "phone").startsWith(calling))
        }
    }

    @Test
    fun phoneFormatsRenderTheSameNumberThreeWays() {
        val engine = resolver("en-KE")
        val generated = engine.generatedPersona()
        fun phone(format: FillPhoneNumberFormat) = engine.resolve(
            FillResolutionRequest("phone", FillType.PhoneNumber(format = format)),
            generated,
        )
        val e164 = phone(FillPhoneNumberFormat.E164)
        val national = phone(FillPhoneNumberFormat.National)
        val international = phone(FillPhoneNumberFormat.International)

        assertTrue(e164.startsWith("+254") && !e164.contains(' '))
        assertTrue(national.startsWith("0") && !national.startsWith("+"))
        assertTrue(international.startsWith("+254 "))
        assertEquals(e164.removePrefix("+254"), international.filter(Char::isDigit).removePrefix("254"))
    }

    // --- Addresses ------------------------------------------------------------

    @Test
    fun administrativeAreaTerminologyIsRegional() {
        fun label(code: String) = registry.resolve(FillLocale.Code(code)).address?.administrativeAreaLabel
        assertEquals("County", label("en-KE"))
        assertEquals("State", label("en-US"))
        assertEquals("Province", label("en-CA"))
        assertEquals("都道府県", label("ja-JP"))
        assertEquals("Bundesland", label("de-DE"))
    }

    @Test
    fun localesWithoutPostalCodesSaySo() {
        val uganda = registry.resolve(FillLocale.Code("en-UG"))
        assertTrue(!uganda.address!!.postalCodeSupported)
        val engine = resolver("en-UG")
        val error = runCatching {
            engine.resolve(FillResolutionRequest("postalCode", FillType.PostalCode), engine.generatedPersona())
        }.exceptionOrNull()
        assertTrue("expected an explicit unsupported error, got $error", error is UnsupportedOperationException)
    }

    @Test
    fun countryNamesAreAvailableInBothForms() {
        val germany = registry.resolve(FillLocale.Code("de-DE"))
        assertEquals("Germany", germany.address?.countryName)
        assertEquals("Deutschland", germany.address?.localizedCountryName)
        assertEquals("DE", germany.countryCode)
    }

    @Test
    fun businessSuffixesFollowTheLocale() {
        fun suffixes(code: String) = registry.resolve(FillLocale.Code(code)).companySuffixes
        assertTrue("GmbH" in suffixes("de-DE"))
        assertTrue("SARL" in suffixes("fr-FR"))
        assertTrue("Pty Ltd" in suffixes("en-ZA"))
        assertTrue("LLC" in suffixes("en-US"))
        assertTrue("株式会社" in suffixes("ja-JP"))
    }

    @Test
    fun currencyFollowsRegionNotLanguage() {
        assertEquals("EUR", registry.resolve(FillLocale.Code("fr-FR")).currencyCode)
        assertEquals("CAD", registry.resolve(FillLocale.Code("fr-CA")).currencyCode)
        assertEquals("XOF", registry.resolve(FillLocale.Code("fr-SN")).currencyCode)

        val engine = resolver("en-KE")
        val amount = engine.resolve(
            FillResolutionRequest("total", FillType.CurrencyAmount()),
            engine.generatedPersona(),
        )
        assertTrue("expected a KES amount, got $amount", amount.startsWith("KES "))
    }

    // --- Locale vs country ----------------------------------------------------

    @Test
    fun languageAndRegionAreModelledSeparately() {
        val english = registry.resolve(FillLocale.Code("en-KE"))
        val swahili = registry.resolve(FillLocale.Code("sw-KE"))
        assertEquals("KE", english.countryCode)
        assertEquals("KE", swahili.countryCode)
        assertEquals("en", english.language)
        assertEquals("sw", swahili.language)
        // The Swahili variant inherits Kenya's geography and phone plan.
        assertEquals(english.phoneData?.countryCallingCode, swahili.phoneData?.countryCallingCode)
        assertEquals(english.cities, swahili.cities)
        assertNotEquals(english.firstNames, swahili.firstNames)
    }

    @Test
    fun aCountryOnlyRequestPicksALanguagePack() {
        assertEquals("KE", registry.resolve(FillLocale.Country("KE")).countryCode)
        assertEquals("JP", registry.resolve(FillLocale.Country("jp")).countryCode)
    }

    // --- Fallback -------------------------------------------------------------

    @Test
    fun fallbackPrefersExactThenRegionThenLanguageThenDefault() {
        assertEquals(FillLocaleMatch.Exact, registry.resolution("sw-KE").match)
        // de-AT has no pack; German is the closest thing FillKit ships.
        val austrian = registry.resolution("de-AT")
        assertEquals(FillLocaleMatch.Language, austrian.match)
        assertEquals("de-DE", austrian.pack.code)
        val puertoRico = registry.resolution("es-PR")
        assertEquals(FillLocaleMatch.Language, puertoRico.match)
        assertTrue(puertoRico.pack.language == "es")
        val unknown = registry.resolution("zz-ZZ")
        assertEquals(FillLocaleMatch.Default, unknown.match)
        assertEquals("en-US", unknown.pack.code)
    }

    @Test
    fun fallbackExplainsItself() {
        assertTrue(registry.resolution("es-PR").describe().contains("es-PR"))
        assertTrue(registry.resolution("zz-ZZ").describe().contains("no zz-ZZ locale pack registered"))
        assertTrue(registry.resolution("en-KE").describe().contains("exact"))
    }

    @Test
    fun anUnknownLocaleNeverCrashes() {
        listOf("", "x", "!!", "en", "EN_us", "zz", "qqq-QQ").forEach { tag ->
            val pack = registry.resolution(tag).pack
            assertTrue("resolving '$tag' produced no pack", pack.code.isNotEmpty())
        }
    }

    // --- Composition ----------------------------------------------------------

    @Test
    fun aCustomPackOverridesABuiltInWithoutLosingInheritedData() {
        val custom = fillLocalePack("en-KE", "Company Kenya", id = "company-kenya", version = "2") {
            business { suffixes("Ltd", "Limited") }
        }
        val composed = DefaultFillLocaleRegistry(listOf(custom)).resolve(FillLocale.Code("en-KE"))
        assertEquals(listOf("Ltd", "Limited"), composed.companySuffixes)
        assertEquals("company-kenya@2", composed.coordinate())
        // Geography, phone and postal data still come from the built-in pack.
        assertTrue(composed.cities.contains("Nairobi"))
        assertEquals("+254", composed.phoneData?.countryCallingCode)
    }

    @Test
    fun packInheritanceAvoidsDuplicatingSharedData() {
        val variant = fillLocalePack("sw-KE", "Company Swahili", id = "company-sw-ke") {
            extends("en-KE")
            person { givenNames("Zuri", "Imani") }
        }
        val composed = DefaultFillLocaleRegistry(listOf(variant)).resolve(FillLocale.Code("sw-KE"))
        assertEquals(listOf("Zuri", "Imani"), composed.firstNames)
        assertTrue(composed.cities.contains("Nairobi"))
        assertEquals("+254", composed.phoneData?.countryCallingCode)
    }

    @Test
    fun inheritanceCyclesAreRejected() {
        val a = fillLocalePack("aa-AA", "A") { extends("bb-BB") }
        val b = fillLocalePack("bb-BB", "B") { extends("aa-AA") }
        val error = runCatching { DefaultFillLocaleRegistry(listOf(a, b)) }.exceptionOrNull()
        assertTrue("expected a cycle error, got $error", error is IllegalStateException)
        assertTrue(error!!.message!!.contains("cycle"))
    }

    @Test
    fun extendingAnUnknownLocaleIsRejected() {
        val orphan = fillLocalePack("qq-QQ", "Orphan") { extends("no-SUCH") }
        val error = runCatching { DefaultFillLocaleRegistry(listOf(orphan)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    // --- Determinism ----------------------------------------------------------

    @Test
    fun theSameSeedInDifferentLocalesGivesDifferentButStableData() {
        val kenyan = persona("en-KE")
        val japanese = persona("ja-JP")
        val french = persona("fr-FR")
        assertNotEquals(kenyan, japanese)
        assertNotEquals(japanese, french)

        assertEquals(kenyan, persona("en-KE"))
        assertEquals(japanese, persona("ja-JP"))
        assertEquals(french, persona("fr-FR"))
    }

    @Test
    fun aFieldCanOverrideTheFormLocale() {
        val engine = resolver("en-KE")
        val generated = engine.generatedPersona()
        val local = engine.resolve(FillResolutionRequest("phone", FillType.PhoneNumber()), generated)
        val british = engine.resolve(
            FillResolutionRequest(
                "internationalPhone",
                FillType.PhoneNumber(),
                locale = registry.resolve(FillLocale.Code("en-GB")),
            ),
            generated,
        )
        assertTrue(local.startsWith("+254"))
        assertTrue("field override should use the UK plan, got $british", british.startsWith("+44"))
    }

    // --- Semantic aliases -----------------------------------------------------

    @Test
    fun localePacksCanLocaliseFieldLabels() {
        fun aliases(code: String) = registry.resolve(FillLocale.Code(code)).semantics?.normalized.orEmpty()
        assertEquals(FillContentHint.FirstName, aliases("fr-FR")["prénom"])
        assertEquals(FillContentHint.LastName, aliases("de-DE")["nachname"])
        assertEquals(FillContentHint.City, aliases("es-MX")["ciudad"])
        assertEquals(FillContentHint.FirstName, aliases("sw-KE")["jina"])
        assertNull(aliases("en-US")["prénom"])
    }

    @Test
    fun theSuggestionEngineUsesLocaleAliases() {
        val french = registry.resolve(FillLocale.Code("fr-FR"))
        val engine = FieldSuggestionEngine(
            localeAliases = french.semantics!!.normalized,
            localeCode = french.code,
        )
        val suggestions = engine.suggest(io.devkit.fillkit.FieldMetadata(id = "f1", label = "Prénom"))
        assertEquals(FillType.FirstName, suggestions.first().type)
    }

    // --- RTL ------------------------------------------------------------------

    @Test
    fun rightToLeftLocalesAreFlagged() {
        listOf("ar-SA", "ar-AE", "ar-EG", "he-IL").forEach { code ->
            assertTrue("$code should be marked right-to-left", registry.resolve(FillLocale.Code(code)).rightToLeft)
        }
        assertTrue(!registry.resolve(FillLocale.Code("en-US")).rightToLeft)
        assertTrue(!registry.resolve(FillLocale.Code("tr-TR")).rightToLeft)
    }

    // --- Safety ---------------------------------------------------------------

    @Test
    fun onlySafeDomainsAreEverGenerated() {
        BuiltInLocalePacks.all.forEach { pack ->
            pack.internet?.emailDomains?.forEach { domain ->
                assertTrue("$domain is not a reserved example domain", domain.endsWith("example.com") ||
                    domain.endsWith("example.org") || domain.endsWith("example.net"))
            }
        }
    }
}
