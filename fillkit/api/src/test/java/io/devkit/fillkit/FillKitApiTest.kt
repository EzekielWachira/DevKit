package io.devkit.fillkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FillKitApiTest {
    @Test
    fun scenarioDslPreservesTypedValuesAndOrder() {
        val scenario = fillScenario("happy", "Happy") {
            text("name", "Amina")
            integer("age", 29)
            boolean("active", true)
        }
        assertEquals(listOf("name", "age", "active"), scenario.values.keys.toList())
        assertEquals(FillValue.Integer(29), scenario.values["age"])
    }

    @Test
    fun invalidConstraintsFailEarly() {
        assertThrows(IllegalArgumentException::class.java) { FillType.Integer(65..18) }
        assertThrows(IllegalArgumentException::class.java) { FillType.Text(10, 2) }
        assertThrows(IllegalArgumentException::class.java) { FillType.Selection(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { FillType.Password(minLength = 2) }
        assertThrows(IllegalArgumentException::class.java) { FillType.Date(FillDate(2025, 1, 1), FillDate(2024, 1, 1)) }
    }

    @Test
    fun fillDateValidatesCalendarDates() {
        assertEquals(FillDate(2024, 2, 29), FillDate(2024, 2, 29))
        assertThrows(IllegalArgumentException::class.java) { FillDate(2025, 2, 29) }
    }

    @Test
    fun personasSupportArbitraryTypedValuesAndStableIds() {
        val persona = fillPersona("provider-12", "Experienced Provider") {
            firstName("Brian")
            value("yearsExperience", 12)
            value("businessType", "Company")
            metadata("fixture", "provider")
        }

        assertEquals(FillValue.Integer(12), persona.values["yearsExperience"])
        assertEquals("provider", persona.metadata["fixture"])
    }

    @Test
    fun personaPacksRejectDuplicateIds() {
        val persona = fillPersona("same", "First") { email("first@example.com") }
        assertThrows(IllegalArgumentException::class.java) {
            personaPack("people", "People") { persona(persona); persona(persona.copy(name = "Second")) }
        }
    }

    @Test
    fun unifiedPacksExposeLocalesPersonasScenariosAndGenerators() {
        val locale = fillLocalePack("sw-KE", "Kenya — Swahili") {
            firstNames("Amina")
            phone { countryCode = "+254"; formats("7########") }
        }
        val people = personaPack("people", "People") {
            persona(fillPersona("amina", "Amina") { firstName("Amina") })
        }
        val scenarios = scenarioPack("validation", "Validation") {
            scenario("empty", "Empty") { text("name", "") }
        }
        val generator = fillGenerator<String>("profession") { oneOf("Plumber") }
        val generators = generatorPack("services", "Services") { generator(generator) }
        val pack = fillKitPack("provider", "Provider") {
            locale(locale); personas(people); scenarios(scenarios); generators(generators)
        }
        val config = FillKitConfig(packs = listOf(pack))

        assertEquals(listOf("sw-KE"), config.allLocalePacks().map(FillLocalePack::code))
        assertEquals(listOf("amina"), config.allPersonas().map(FillPersona::id))
        assertEquals(listOf("empty"), config.allScenarios().map(FillScenario::id))
        assertTrue(config.allGenerators().any { it.first == "profession" })
    }

    @Test
    fun legacyMapGeneratorSyntaxRemainsSourceCompatible() {
        val generator = FillGenerator<String> { context ->
            listOf("One", "Two").random(context.random)
        }
        val config = FillKitConfig(customGenerators = mapOf("service" to generator))
        assertEquals("service", config.allGenerators().single().first)
    }

    @Test
    fun sameScopeGeneratorDuplicatesFailButLocalOverrideIsExplicit() {
        val packed = fillGenerator<String>("service") { "Packed" }
        val local = fillGenerator<String>("service") { "Local" }
        assertThrows(IllegalArgumentException::class.java) {
            FillKitConfig(generators = listOf(local, local))
        }
        val pack = generatorPack("shared", "Shared") { generator(packed) }
        val config = FillKitConfig(generatorPacks = listOf(pack), generators = listOf(local))
        assertEquals(listOf("service", "service"), config.allGenerators().map { it.first })
    }
}
