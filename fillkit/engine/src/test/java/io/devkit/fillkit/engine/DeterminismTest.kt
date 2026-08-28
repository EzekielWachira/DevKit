package io.devkit.fillkit.engine

import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillPersona
import io.devkit.fillkit.FillSeed
import io.devkit.fillkit.FillType
import io.devkit.fillkit.FillValue
import io.devkit.fillkit.engine.locale.DefaultFillLocaleRegistry
import io.devkit.fillkit.fillGenerator
import io.devkit.fillkit.fillPersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reproducibility contract of FillKit 0.4: values depend on the master seed,
 * the generation counter and a field's own identity, never on registration order.
 */
class DeterminismTest {

    private fun resolver(
        seed: Long = SEED,
        generation: Int = 0,
        formId: String = "provider-onboarding",
        locale: String = "en-KE",
    ) = FillValueResolver(
        DefaultFillLocaleRegistry().resolve(FillLocale.Code(locale)),
        listOf("business-name" to fillGenerator<String>("business-name") { "${oneOf("A", "B", "C")}-${integer(1, 999)}" }),
        FillSeed(seed),
        generation,
        formId,
    )

    private fun FillValueResolver.text(fieldId: String, type: FillType<String> = FillType.Email): String =
        resolve(FillResolutionRequest(fieldId, type), generatedPersona())

    @Test
    fun sameSeedProducesSameValue() {
        assertEquals(resolver().text("email"), resolver().text("email"))
    }

    @Test
    fun differentSeedProducesDifferentValue() {
        assertNotEquals(resolver(seed = SEED).text("email"), resolver(seed = SEED + 1).text("email"))
    }

    @Test
    fun sameSeedAndGenerationProducesSameValue() {
        assertEquals(resolver(generation = 3).text("email"), resolver(generation = 3).text("email"))
    }

    @Test
    fun advancingGenerationProducesDeterministicallyDifferentValues() {
        val first = resolver(generation = 0)
        val second = resolver(generation = 1)
        assertNotEquals(first.text("email"), second.text("email"))
        assertEquals(second.text("email"), resolver(generation = 1).text("email"))
    }

    @Test
    fun registrationOrderDoesNotChangeValues() {
        val ordered = resolver()
        val forwards = listOf("firstName", "lastName", "email").map { ordered.text(it) }
        val shuffled = resolver()
        val backwards = listOf("email", "lastName", "firstName").map { shuffled.text(it) }.reversed()
        assertEquals(forwards, backwards)
    }

    @Test
    fun addingAnUnrelatedFieldDoesNotChangeExistingValues() {
        val before = resolver().let { engine ->
            mapOf(
                "email" to engine.text("email"),
                "businessName" to engine.text("businessName", FillType.Custom("business-name", String::class)),
            )
        }
        val after = resolver().let { engine ->
            engine.text("insertedInBetween", FillType.Username)
            mapOf(
                "email" to engine.text("email"),
                "businessName" to engine.text("businessName", FillType.Custom("business-name", String::class)),
            )
        }
        assertEquals(before, after)
    }

    @Test
    fun fieldValuesAreScopedToTheirForm() {
        val code = FillType.OtpCode(8, numericOnly = false)
        assertNotEquals(
            resolver(formId = "provider-onboarding").text("code", code),
            resolver(formId = "checkout").text("code", code),
        )
    }

    @Test
    fun personaDerivedValuesStayStableAcrossForms() {
        // Identity values follow the persona, not the field, so the same seed
        // describes the same person on every screen.
        assertEquals(
            resolver(formId = "provider-onboarding").text("email"),
            resolver(formId = "checkout").text("email"),
        )
    }

    @Test
    fun personaIsDeterministicAndCoherent() {
        val persona = resolver().generatedPersona()
        assertEquals(persona, resolver().generatedPersona())
        val first = persona.text("firstName").lowercase()
        val last = persona.text("lastName").lowercase()
        val email = persona.text("email")
        assertTrue("$email should derive from $first.$last", email.startsWith("$first.$last"))
        assertTrue(email.endsWith("@example.com") || email.endsWith("@example.org") || email.endsWith("@example.net"))
        assertEquals("$first $last", persona.text("fullName").lowercase())
    }

    @Test
    fun localeParticipatesInDerivationAndStaysDeterministicPerLocale() {
        val kenyan = resolver(locale = "en-KE")
        val british = resolver(locale = "en-GB")
        assertNotEquals(kenyan.generatedPersona().text("phone"), british.generatedPersona().text("phone"))

        // The locale pack coordinate seeds every field stream, so the same seed
        // under a different locale is a different — but still stable — draw.
        val otp = FillType.OtpCode(6)
        fun code(engine: FillValueResolver) =
            engine.resolve(FillResolutionRequest("otp", otp), engine.generatedPersona())
        assertNotEquals(code(kenyan), code(british))
        assertEquals(code(kenyan), code(resolver(locale = "en-KE")))
        assertEquals(code(british), code(resolver(locale = "en-GB")))
    }

    @Test
    fun savedPersonaValuesStayCoherentAcrossFields() {
        val persona: FillPersona = fillPersona("returning", "Returning") {
            firstName("Amina")
            lastName("Wanjiku")
        }
        val engine = resolver()
        val generated = engine.generatedPersona()
        val email = engine.resolve(FillResolutionRequest("email", FillType.Email, persona = persona), generated)
        val username = engine.resolve(FillResolutionRequest("username", FillType.Username, persona = persona), generated)
        assertEquals("amina.wanjiku", username)
        assertEquals("amina.wanjiku@example.com", email)
    }

    @Test
    fun rerollingAPersonaDerivedFieldChangesOnlyThatField() {
        val engine = resolver()
        val generated = engine.generatedPersona()
        fun value(fieldId: String, type: FillType<String>, nonce: Int) =
            engine.resolve(FillResolutionRequest(fieldId, type, nonce = nonce), generated)

        val firstName = value("firstName", FillType.FirstName, 0)
        val phone = value("phone", FillType.PhoneNumber(), 0)

        val rerolledPhone = value("phone", FillType.PhoneNumber(), 1)
        assertNotEquals(phone, rerolledPhone)
        assertEquals(firstName, value("firstName", FillType.FirstName, 0))
        assertEquals(rerolledPhone, value("phone", FillType.PhoneNumber(), 1))
        assertNotEquals(rerolledPhone, value("phone", FillType.PhoneNumber(), 2))
    }

    @Test
    fun rerollingOneFieldLeavesOtherFieldsAlone() {
        val engine = resolver()
        val code = FillType.OtpCode(8, numericOnly = false)
        val untouched = engine.text("otpA", code)
        val original = engine.text("otpB", code)
        val rerolled = engine.resolve(
            FillResolutionRequest("otpB", code, nonce = 1),
            engine.generatedPersona(),
        )
        assertEquals(untouched, engine.text("otpA", code))
        assertNotEquals(original, rerolled)
    }

    private fun FillPersona.text(key: String): String = (values.getValue(key) as FillValue.Text).value

    private companion object {
        const val SEED = 845912L
    }
}
