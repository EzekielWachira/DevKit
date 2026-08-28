package io.devkit.fillkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FillScenarioCatalogTest {

    private val providerPack = scenarioPack("provider", "Provider Onboarding", version = "3") {
        scenario(
            id = "experienced-provider",
            name = "Experienced Provider",
            targetForm = "provider-onboarding",
            description = "Normal provider with an established business.",
            category = "Happy path",
            tags = setOf("happy-path", "kenya"),
        ) { persona("experienced-provider") }
        scenario(
            id = "maximum-values",
            name = "Maximum Values",
            targetForm = "provider-onboarding",
            description = "Exercises field boundaries.",
            category = "Validation",
            tags = setOf("validation", "edge-case"),
        ) { generated("businessName", "business-name") }
    }

    private val checkoutPack = scenarioPack("checkout", "Checkout") {
        scenario(id = "returning-customer", name = "Returning Customer", targetForm = "checkout") {
            persona("returning-customer")
        }
    }

    private fun catalog(
        personaIds: Set<String> = setOf("experienced-provider", "returning-customer"),
        generatorIds: Set<String> = setOf("business-name"),
        navigableForms: Set<String> = emptySet(),
        hasNavigator: Boolean = true,
    ) = FillScenarioCatalog.build(
        FillCatalogInput(
            scenarioPacks = listOf(providerPack, checkoutPack),
            personaIds = personaIds,
            generatorIds = generatorIds,
            navigableForms = navigableForms,
            hasNavigator = hasNavigator,
        ),
    )

    @Test
    fun listsEveryScenarioWithItsPack() {
        val entries = catalog().entries
        assertEquals(3, entries.size)
        assertEquals("Provider Onboarding", entries.first().packName)
        assertEquals("3", entries.first().packVersion)
    }

    @Test
    fun exposesFormsAndTags() {
        val catalog = catalog()
        assertEquals(listOf("checkout", "provider-onboarding"), catalog.forms)
        assertEquals(listOf("edge-case", "happy-path", "kenya", "validation"), catalog.tags)
    }

    @Test
    fun searchesNameIdFormTagsAndDescription() {
        val catalog = catalog()
        assertEquals(listOf("maximum-values"), catalog.search("maximum").map { it.id })
        assertEquals(listOf("maximum-values"), catalog.search("boundaries").map { it.id })
        assertEquals(listOf("returning-customer"), catalog.search("checkout").map { it.id })
        assertEquals(2, catalog.search("provider").size)
        assertTrue(catalog.search("nothing-here").isEmpty())
    }

    @Test
    fun filtersByFormPackAndTag() {
        val catalog = catalog()
        assertEquals(2, catalog.search(form = "provider-onboarding").size)
        assertEquals(1, catalog.search(packId = "checkout").size)
        assertEquals(listOf("maximum-values"), catalog.search(tag = "validation").map { it.id })
    }

    @Test
    fun buildsALaunchRequestFromTheTargetForm() {
        val entry = catalog().find("experienced-provider")!!
        val request = entry.launchRequest(seed = 845912L, locale = "en-KE")
        assertEquals("provider-onboarding", request.formId)
        assertEquals("provider", request.scenarioPackId)
        assertEquals("experienced-provider", request.scenarioId)
        assertEquals("experienced-provider", request.personaId)
        assertEquals(845912L, request.seed)
        assertEquals(FillActivationSource.QaLauncher, request.source)
    }

    @Test
    fun reportsAMissingPersonaAsBlocking() {
        val entry = catalog(personaIds = setOf("returning-customer")).find("experienced-provider")!!
        assertFalse(entry.launchable)
        assertEquals("Missing persona: experienced-provider", entry.blockingReason)
    }

    @Test
    fun reportsAMissingGeneratorAsBlocking() {
        val entry = catalog(generatorIds = setOf("something-else")).find("maximum-values")!!
        assertFalse(entry.launchable)
        assertTrue(entry.blockingReason!!.contains("business-name"))
    }

    @Test
    fun reportsAFormTheNavigatorCannotReach() {
        val entry = catalog(navigableForms = setOf("checkout")).find("maximum-values")!!
        assertFalse(entry.launchable)
        assertTrue(entry.blockingReason!!.contains("provider-onboarding"))
    }

    @Test
    fun warnsWithoutBlockingWhenNoNavigatorIsConfigured() {
        val entry = catalog(hasNavigator = false).find("maximum-values")!!
        assertTrue(entry.launchable)
        assertTrue(entry.issues.any { it.kind == FillCatalogIssueKind.NoNavigator })
    }

    @Test
    fun scenarioWithoutATargetFormIsListedButNeedsOne() {
        val local = FillScenarioCatalog.build(
            FillCatalogInput(scenarios = listOf(fillScenario("ad-hoc", "Ad hoc") { text("a", "b") })),
        )
        val entry = local.find("ad-hoc")!!
        assertTrue(entry.launchable)
        assertNull(entry.targetForm)
        assertTrue(entry.issues.any { it.kind == FillCatalogIssueKind.MissingTargetForm })
        assertEquals("checkout", entry.launchRequest(formId = "checkout").formId)
    }

    @Test
    fun unknownScenarioIsNotFound() {
        assertNull(catalog().find("does-not-exist"))
        assertNotNull(catalog().find("returning-customer"))
    }

    @Test
    fun scenarioMetadataSurvivesPackComposition() {
        val scenario = providerPack.scenarios.first { it.id == "maximum-values" }
        assertEquals("Validation", scenario.category)
        assertEquals(setOf("validation", "edge-case"), scenario.tags)
        assertTrue(scenario.searchText("Provider Onboarding").contains("provider onboarding"))
    }
}
