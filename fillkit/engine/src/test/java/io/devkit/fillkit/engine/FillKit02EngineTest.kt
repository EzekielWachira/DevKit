package io.devkit.fillkit.engine

import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillType
import io.devkit.fillkit.FillValue
import io.devkit.fillkit.fillGenerator
import io.devkit.fillkit.fillLocalePack
import io.devkit.fillkit.fillPersona
import io.devkit.fillkit.fillScenario
import io.devkit.fillkit.generate
import io.devkit.fillkit.engine.locale.DefaultFillLocaleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FillKit02EngineTest {
    @Test
    fun builtInLocalesIncludeAfricaAndInternationalCollection() {
        val codes = DefaultFillLocaleRegistry().availableLocales().map { it.code }.toSet()
        assertTrue(codes.containsAll(setOf("en-KE", "en-NG", "en-UG", "en-TZ", "en-RW", "en-GH", "en-ZA")))
        assertTrue(codes.containsAll(setOf("en-US", "en-GB", "en-CA", "en-AU")))
    }

    @Test
    fun customLocalesOverrideBuiltInsAndMissingCapabilitiesFallBack() {
        val custom = fillLocalePack("en-KE", "Kenya Custom") { firstNames("OnlyCustom") }
        val resolved = DefaultFillLocaleRegistry(listOf(custom)).resolve(FillLocale.Code("en-KE"))

        assertEquals("Kenya Custom", resolved.displayName)
        assertEquals(listOf("OnlyCustom"), resolved.firstNames)
        assertTrue(resolved.cities.isNotEmpty())
        assertEquals("Kenya", resolved.country)
    }

    @Test
    fun localeFallbackIsRequestedThenEnglishRegionThenDefault() {
        val custom = fillLocalePack("sw-KE", "Kenya — Swahili") { firstNames("Amina") }
        val registry = DefaultFillLocaleRegistry(listOf(custom))
        val swahili = registry.resolve(FillLocale.Code("sw-KE"))

        assertEquals("sw-KE", swahili.code)
        assertEquals(listOf("Amina"), swahili.firstNames)
        assertEquals("Kenya", swahili.country)
        assertEquals("fr-CA", registry.resolve(FillLocale.Code("fr-CA")).code)
        assertEquals("fr-FR", registry.resolve(FillLocale.Code("fr-FR")).code)
        assertEquals("en-US", registry.resolve(FillLocale.Code("zz-ZZ")).code)
    }

    @Test
    fun scenarioCompositionAppliesBaseFirstAndChildOverrides() {
        val base = fillScenario("base", "Base") { text("name", "Base"); integer("age", 18) }
        val child = fillScenario("child", "Child") { include(base); text("name", "Child") }
        val resolved = ScenarioRegistry(listOf(base, child)).resolve("child")

        assertEquals(FillValue.Text("Child"), resolved.values["name"])
        assertEquals(FillValue.Integer(18), resolved.values["age"])
    }

    @Test
    fun scenarioRegistryReportsUnknownAndRecursiveIncludes() {
        val first = fillScenario("first", "First") { include("second") }
        val second = fillScenario("second", "Second") { include("first") }

        assertThrows(IllegalStateException::class.java) { ScenarioRegistry(listOf(first, second)) }
        val valid = ScenarioRegistry(listOf(fillScenario("valid", "Valid") {}))
        assertThrows(IllegalArgumentException::class.java) { valid.resolve("unknown") }
    }

    @Test
    fun generatorUtilitiesAreSeededAndExposeContext() {
        val generator = fillGenerator<String>("summary") {
            val city = weighted("Nairobi" to 5, "Mombasa" to 1)
            "$city-${integer(1, 25)}-${boolean(1f)}-${locale.country}-${persona?.name}"
        }
        val persona = fillPersona("known", "Known User") { firstName("Amina") }
        val first = resolver(listOf("summary" to generator), 42)
        val second = resolver(listOf("summary" to generator), 42)
        val generatedFirst = first.generatedPersona()
        val generatedSecond = second.generatedPersona()
        val request = FillResolutionRequest("summary", FillType.Custom("summary", String::class), persona = persona)

        assertEquals(first.resolve(request, generatedFirst), second.resolve(request, generatedSecond))
        assertTrue(first.resolve(request, generatedFirst).contains("Kenya-Known User"))
    }

    @Test
    fun oneOfOptionalAndTextUtilitiesRespectContracts() {
        val generator = fillGenerator<String>("utilities") {
            val selected = oneOf("A")
            val absent = optional<String>(0f) { "never" }
            "$selected-${text(5)}-$absent-${repeat(2) { it }.joinToString()}"
        }
        val resolver = resolver(listOf("utilities" to generator), 5)
        val value = resolver.resolve(
            FillResolutionRequest("utilities", FillType.Custom("utilities", String::class)),
            resolver.generatedPersona(),
        )

        assertTrue(value.startsWith("A-"))
        assertTrue(value.contains("-null-0, 1"))
    }

    @Test
    fun generatorDependenciesWorkAndCyclesAreRejected() {
        val first = fillGenerator<String>("first") { "Amina" }
        val email = fillGenerator<String>("email-custom") { "${generate<String>("first").lowercase()}@example.com" }
        val resolver = resolver(listOf("first" to first, "email-custom" to email), 1)
        assertEquals(
            "amina@example.com",
            resolver.resolve(
                FillResolutionRequest("email", FillType.Custom("email-custom", String::class)),
                resolver.generatedPersona(),
            ),
        )

        val a = fillGenerator<String>("a") { generate<String>("b") }
        val b = fillGenerator<String>("b") { generate<String>("a") }
        val cyclic = resolver(listOf("a" to a, "b" to b), 1)
        val error = assertThrows(IllegalStateException::class.java) {
            cyclic.resolve(FillResolutionRequest("value", FillType.Custom("a", String::class)), cyclic.generatedPersona())
        }
        assertTrue(error.message.orEmpty().contains("a -> b -> a"))
    }

    @Test
    fun resolverPrecedenceIsExplicitAndPartialPersonasFallBackCoherently() {
        val typeGenerator = fillGenerator<String>("email") { "type@example.com" }
        val idGenerator = fillGenerator<String>("emailField") { "field-id@example.com" }
        val fieldGenerator = fillGenerator<String>("inline") { "inline@example.com" }
        val resolver = resolver(listOf("email" to typeGenerator, "emailField" to idGenerator), 7)
        val generated = resolver.generatedPersona()
        val persona = fillPersona("partial", "Partial") { firstName("Neema"); lastName("Kamau"); email("persona@example.com") }
        val scenarioGenerator = fillGenerator<String>("scenario-inline") { "scenario-generator@example.com" }
        val scenarioGenerated = fillScenario("generated", "Generated") { custom("emailField", scenarioGenerator) }
        val scenarioExplicit = fillScenario("explicit", "Explicit") { text("emailField", "scenario@example.com") }

        assertEquals("scenario@example.com", resolver.resolve(FillResolutionRequest("emailField", FillType.Email, scenarioExplicit, persona, fieldGenerator), generated))
        assertEquals("scenario-generator@example.com", resolver.resolve(FillResolutionRequest("emailField", FillType.Email, scenarioGenerated, persona, fieldGenerator), generated))
        assertEquals("inline@example.com", resolver.resolve(FillResolutionRequest("emailField", FillType.Email, persona = persona, fieldGenerator = fieldGenerator), generated))
        assertEquals("persona@example.com", resolver.resolve(FillResolutionRequest("email", FillType.Email, persona = persona), generated))
        assertEquals("field-id@example.com", resolver.resolve(FillResolutionRequest("emailField", FillType.Email), generated))
        assertEquals("type@example.com", resolver.resolve(FillResolutionRequest("otherEmail", FillType.Email), generated))

        val withoutEmail = fillPersona("partial-name", "Partial Name") { firstName("Neema"); lastName("Kamau") }
        assertEquals("neema.kamau@example.com", resolver.resolve(FillResolutionRequest("email", FillType.Email, persona = withoutEmail), generated))
    }

    @Test
    fun differentSeedChangesGeneration() {
        val generator = fillGenerator<Int>("number") { integer(1, Int.MAX_VALUE - 1) }
        val first = resolver(listOf("number" to generator), 1)
        val second = resolver(listOf("number" to generator), 2)
        assertNotEquals(
            first.resolve(FillResolutionRequest("n", FillType.Custom("number", Int::class)), first.generatedPersona()),
            second.resolve(FillResolutionRequest("n", FillType.Custom("number", Int::class)), second.generatedPersona()),
        )
    }

    private fun resolver(generators: List<Pair<String, io.devkit.fillkit.FillGenerator<*>>>, seed: Long) =
        FillValueResolver(
            DefaultFillLocaleRegistry().resolve(FillLocale.Code("en-KE")),
            generators,
            io.devkit.fillkit.FillSeed(seed),
        )
}
