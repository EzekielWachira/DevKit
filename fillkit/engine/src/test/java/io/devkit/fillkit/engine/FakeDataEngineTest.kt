package io.devkit.fillkit.engine

import io.devkit.fillkit.FillDate
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillType
import io.devkit.fillkit.engine.locale.DefaultFillLocaleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeDataEngineTest {
    @Test
    fun sameSeedProducesSamePersonaAndValues() {
        val first = FakeDataEngine(42, "en-KE")
        val second = FakeDataEngine(42, "en-KE")
        val firstPersona = first.newPersona()
        val secondPersona = second.newPersona()

        assertEquals(firstPersona, secondPersona)
        assertEquals(first.generate(FillType.Integer(18..65), firstPersona), second.generate(FillType.Integer(18..65), secondPersona))
    }

    @Test
    fun differentSeedsProduceDifferentPersonas() {
        assertNotEquals(FakeDataEngine(1, "en-KE").newPersona(), FakeDataEngine(2, "en-KE").newPersona())
    }

    @Test
    fun localesResolveExactlyAndFallBackByRegion() {
        val registry = DefaultFillLocaleRegistry()
        assertEquals("en-GB", registry.resolve(FillLocale.Code("en-GB")).code)
        assertEquals("en-KE", registry.resolve(FillLocale.Code("sw_KE")).code)
        assertEquals("en-US", registry.resolve(FillLocale.Code("fr-FR")).code)
    }

    @Test
    fun personaFieldsAreCoherentAndSynthetic() {
        repeat(100) { seed ->
            val engine = FakeDataEngine(seed.toLong(), "en-KE")
            val persona = engine.newPersona()
            val email = engine.generate(FillType.Email, persona)
            assertEquals(persona.email, email)
            assertTrue(email.substringBefore('@').startsWith(PersonaGenerator.normalize("${persona.firstName}.${persona.lastName}")))
            assertTrue(email.substringAfter('@') in PersonaGenerator.safeDomains)
            assertTrue(engine.generate(FillType.Website, persona).endsWith(".example.com"))
            assertEquals("Kenya", engine.generate(FillType.Country, persona))
        }
    }

    @Test
    fun numericAndTextConstraintsAreAlwaysSatisfied() {
        val engine = FakeDataEngine(99, "en-US")
        val persona = engine.newPersona()
        repeat(200) {
            assertTrue(engine.generate(FillType.Integer(18..65), persona) in 18..65)
            val decimal = engine.generate(FillType.Decimal(1.25..2.75, 2), persona)
            assertTrue(decimal in 1.25..2.75)
            assertEquals(decimal, kotlin.math.round(decimal * 100) / 100.0, 0.00001)
            assertTrue(engine.generate(FillType.Text(10, 24), persona).length in 10..24)
        }
    }

    @Test
    fun passwordGuaranteesEveryConfiguredCategory() {
        val engine = FakeDataEngine(6, "en-GB")
        val persona = engine.newPersona()
        repeat(100) {
            val value = engine.generate(FillType.Password(12, 16), persona)
            assertTrue(value.length in 12..16)
            assertTrue(value.any(Char::isUpperCase))
            assertTrue(value.any(Char::isLowerCase))
            assertTrue(value.any(Char::isDigit))
            assertTrue(value.any { it in "!@#%+-_" })
        }
    }

    @Test
    fun datesAndSelectionsStayInsideConstraints() {
        val engine = FakeDataEngine(74, "en-US")
        val persona = engine.newPersona()
        val min = FillDate(2020, 2, 27)
        val max = FillDate(2020, 3, 2)
        val options = listOf("Plumber", "Electrician", "Carpenter")
        repeat(100) {
            assertTrue(engine.generate(FillType.Date(min, max), persona) in min..max)
            assertTrue(engine.generate(FillType.Selection(options), persona) in options)
        }
    }

    @Test
    fun booleanProbabilityEndpointsAreExact() {
        val engine = FakeDataEngine(8, "en-US")
        val persona = engine.newPersona()
        assertTrue(engine.generate(FillType.BooleanValue(1f), persona))
        assertFalse(engine.generate(FillType.BooleanValue(0f), persona))
    }
}
