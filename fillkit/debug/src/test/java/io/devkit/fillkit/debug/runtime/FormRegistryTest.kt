package io.devkit.fillkit.debug.runtime

import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.FillType
import io.devkit.fillkit.ScenarioValidationMode
import io.devkit.fillkit.fillScenario
import io.devkit.fillkit.runtime.FillKitField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormRegistryTest {
    @Test
    fun registersUpdatesAndUnregistersWithoutStaleCallbacks() {
        val registry = registry()
        val owner = Any()
        var first = ""
        var latest = ""
        registry.register(owner, textField("email", "old") { first = it })
        registry.update(owner, textField("email", "new") { latest = it })

        registry.fill("email")

        assertEquals("", first)
        assertTrue(latest.endsWith("@example.com") || latest.endsWith("@example.org") || latest.endsWith("@example.net"))
        registry.unregister(owner)
        assertTrue(registry.fields.isEmpty())
    }

    @Test
    fun duplicateIdsAreReportedAndOwnersRemainIndependentlyDisposable() {
        val logs = mutableListOf<String>()
        val registry = registry(logs = logs)
        val first = Any()
        val second = Any()
        registry.register(first, textField("email", "") {})
        registry.register(second, textField("email", "") {})

        assertTrue(logs.single().contains("duplicate field ID"))
        assertEquals(2, registry.fields.size)
        registry.unregister(first)
        assertEquals(1, registry.fields.size)
    }

    @Test
    fun registrationOrderIsStableAcrossUpdates() {
        val registry = registry()
        val first = Any()
        val second = Any()
        registry.register(first, field("first", FillType.FirstName, "", {}))
        registry.register(second, field("second", FillType.LastName, "", {}))
        registry.update(first, field("first", FillType.FirstName, "updated", {}))

        assertEquals(listOf("first", "second"), registry.fields.map { it.id })
    }

    @Test
    fun fillAllUsesOneCoherentPersonaAndClearUsesCallbacks() {
        val registry = registry()
        var firstName = ""
        var lastName = ""
        var email = ""
        registry.register(Any(), field("firstName", FillType.FirstName, "", { firstName = it }))
        registry.register(Any(), field("lastName", FillType.LastName, "", { lastName = it }))
        registry.register(Any(), field("email", FillType.Email, "", { email = it }))

        registry.fillAll()

        assertTrue(email.substringBefore('@').startsWith("${firstName.lowercase()}.${lastName.lowercase()}"))
        registry.clearAll()
        assertEquals("", firstName)
        assertEquals("", lastName)
        assertEquals("", email)
    }

    @Test
    fun scenarioAppliesKnownValuesAndLenientlySkipsUnknownValues() {
        val logs = mutableListOf<String>()
        val scenario = fillScenario("happy", "Happy") {
            text("name", "Amina")
            integer("stale", 4)
        }
        val registry = registry(FillKitConfig(scenarios = listOf(scenario)), logs)
        var name = ""
        registry.register(Any(), field("name", FillType.FirstName, "", { name = it }))

        registry.applyScenario("happy")

        assertEquals("Amina", name)
        assertTrue(logs.any { it.contains("unknown field \"stale\"") })
    }

    @Test
    fun strictScenarioTypeMismatchThrowsActionableError() {
        val scenario = fillScenario("bad", "Bad") { integer("name", 7) }
        val registry = registry(
            FillKitConfig(scenarios = listOf(scenario), scenarioValidationMode = ScenarioValidationMode.Strict),
        )
        registry.register(Any(), field("name", FillType.FirstName, "", {}))

        val error = assertThrows(IllegalStateException::class.java) { registry.applyScenario("bad") }
        assertTrue(error.message.orEmpty().contains("expected String, got Int"))
    }

    @Test
    fun regenerateChangesPersonaAndLocaleChangeUsesRequestedDataset() {
        val registry = registry()
        val first = registry.persona
        registry.regenerateAll()
        assertTrue(first != registry.persona)
        registry.changeLocale("en-GB")
        assertEquals("en-GB", registry.localeTag)
        assertEquals("United Kingdom", registry.persona.address.country)
    }

    private fun registry(
        config: FillKitConfig = FillKitConfig(seed = 42),
        logs: MutableList<String> = mutableListOf(),
    ) = FormRegistry("test-form", "en-KE", config, logs::add)

    private fun textField(id: String, value: String, callback: (String) -> Unit) =
        field(id, FillType.Email, value, callback)

    private fun <T : Any> field(
        id: String,
        type: FillType<T>,
        value: T,
        callback: (T) -> Unit,
    ) = FillKitField(id, null, null, type, value, callback, null)
}
