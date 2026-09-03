package io.devkit.netkit

import io.devkit.netkit.engine.ScenarioDecision
import io.devkit.netkit.scenario.EndpointMatcher
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.condition.QueryParameterCondition
import io.devkit.netkit.scenario.model.DefaultScenarioValidator
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ValidationResult
import io.devkit.netkit.scenario.preset.AuthScenarioPresets
import io.devkit.netkit.scenario.preset.PaginationScenarioPresets
import io.devkit.netkit.scenario.preset.PresetConfiguration
import io.devkit.netkit.scenario.preset.ScenarioPreset
import io.devkit.netkit.scenario.preset.ScenarioPresetRegistry
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import io.devkit.netkit.scenario.runtime.ActiveScenarioSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presets, tested through the **scenarios they generate** rather than through the
 * UI that gathers their input.
 *
 * That is the whole architectural claim being verified: a preset is a generator,
 * the rules it produces stand on their own, and nothing in the engine knows a
 * preset exists. If these tests can drive a generated scenario through the real
 * engine and get the documented behaviour, the claim holds.
 */
class ScenarioPresetTest {

    private val validator = DefaultScenarioValidator()

    // ---- the architectural guarantees ----------------------------------------

    @Test
    fun `every shipped preset generates a valid scenario from its defaults`() {
        ScenarioPresetRegistry().all.forEach { preset ->
            val scenario = preset.build(preset.defaults())
            val result = validator.validate(scenario)
            assertTrue(
                "${preset.id} produced an invalid scenario: ${result.errors}",
                result is ValidationResult.Valid,
            )
            assertTrue("${preset.id} produced no rules", scenario.rules.isNotEmpty())
        }
    }

    @Test
    fun `every shipped preset accepts its own defaults`() {
        ScenarioPresetRegistry().all.forEach { preset ->
            val blocking = preset.validate(preset.defaults()).filterNot { it.startsWith("Note:") }
            assertTrue("${preset.id} rejects its own defaults: $blocking", blocking.isEmpty())
        }
    }

    /**
     * The guarantee that makes presets safe to evolve: a generated scenario keeps
     * working when its preset is renamed, changed or deleted, because the rules
     * carry everything.
     */
    @Test
    fun `a generated scenario runs identically without its preset metadata`() {
        val scenario = AuthScenarioPresets.tokenExpires.build(
            AuthScenarioPresets.tokenExpires.defaults(),
        )
        val stripped = scenario.copy(preset = null)

        assertEquals(statuses(scenario, 4), statuses(stripped, 4))
    }

    @Test
    fun `preset provenance is recorded but never behavioural`() {
        val preset = AuthScenarioPresets.refreshFails
        val scenario = preset.build(preset.defaults())

        assertEquals(preset.id, scenario.preset!!.presetId)
        assertEquals(preset.name, scenario.preset!!.presetName)
        // The wizard's answers are kept so it can be reopened pre-populated.
        assertTrue(scenario.preset!!.configuration.isNotEmpty())
    }

    @Test
    fun `the registry accepts application presets alongside the built-in ones`() {
        val custom = object : ScenarioPreset {
            override val id = "app.booking-payment"
            override val name = "Booking payment declined"
            override val description = "The app's own failure mode."
            override val category = io.devkit.netkit.scenario.preset.PresetCategory.OTHER
            override val fields = emptyList<io.devkit.netkit.scenario.preset.PresetField>()
            override fun build(configuration: PresetConfiguration) = NetworkScenario(
                name = name,
                rules = listOf(
                    io.devkit.netkit.scenario.EndpointRule.forPath(
                        path = "/api/v1/bookings/checkout",
                        method = HttpMethod.POST,
                        action = NetworkAction.ReturnResponse(402),
                    ),
                ),
            )
        }

        val registry = ScenarioPresetRegistry().plus(listOf(custom))

        assertNotNull(registry.byId("app.booking-payment"))
        assertTrue(registry.all.size > ScenarioPresetRegistry.builtIn.size)
        assertTrue(registry.byCategory().any { it.first.label == "Other" })
    }

    // ---- authentication -------------------------------------------------------

    @Test
    fun `token expires returns a 401 once and leaves refresh alone`() {
        val scenario = AuthScenarioPresets.tokenExpires.build(
            PresetConfiguration(
                mapOf(
                    "protectedPaths" to "/api/v1",
                    "refreshPath" to "/api/v1/auth/refresh",
                    "count" to "1",
                ),
            ),
        )
        val harness = harness(scenario)

        assertTrue(harness.decide(path = "/api/v1/bookings") is ScenarioDecision.RespondWith)
        assertTrue(harness.decide(path = "/api/v1/bookings") is ScenarioDecision.PassThrough)
        // Refresh must never be caught by the 401 rule, or the app can never recover.
        assertTrue(
            harness.decide(method = "POST", path = "/api/v1/auth/refresh")
                is ScenarioDecision.PassThrough,
        )
    }

    @Test
    fun `token expires with a count of zero keeps failing`() {
        val scenario = AuthScenarioPresets.tokenExpires.build(
            PresetConfiguration(
                mapOf(
                    "protectedPaths" to "/api/v1",
                    "refreshPath" to "/api/v1/auth/refresh",
                    "count" to "0",
                ),
            ),
        )

        assertEquals(listOf(401, 401, 401, 401), statuses(scenario, 4))
    }

    @Test
    fun `the protected rule matches a prefix, not one path`() {
        val scenario = AuthScenarioPresets.tokenExpires.build(
            AuthScenarioPresets.tokenExpires.defaults(),
        )
        val protectedRule = scenario.rules.last()

        assertTrue(protectedRule.matcher is EndpointMatcher.PathPrefix)
        val harness = harness(scenario)
        assertTrue(harness.decide(path = "/api/v1/notifications") is ScenarioDecision.RespondWith)
    }

    @Test
    fun `refresh succeeds returns the configured payload`() {
        val body = """{"access_token":"my-shape"}"""
        val scenario = AuthScenarioPresets.refreshSucceeds.build(
            PresetConfiguration(
                mapOf(
                    "protectedPaths" to "/api/v1",
                    "refreshPath" to "/api/v1/auth/refresh",
                    "refreshMethod" to "POST",
                    "refreshBody" to body,
                ),
            ),
        )
        val harness = harness(scenario)

        val refresh = harness.decide(method = "POST", path = "/api/v1/auth/refresh")
            as ScenarioDecision.RespondWith
        assertEquals(200, refresh.statusCode)
        assertEquals(body, refresh.body)
    }

    @Test
    fun `refresh fails rejects the refresh with the configured status`() {
        val scenario = AuthScenarioPresets.refreshFails.build(
            PresetConfiguration(
                mapOf(
                    "protectedPaths" to "/api/v1",
                    "refreshPath" to "/api/v1/auth/refresh",
                    "refreshMethod" to "POST",
                    "refreshStatus" to "500",
                ),
            ),
        )
        val harness = harness(scenario)

        val refresh = harness.decide(method = "POST", path = "/api/v1/auth/refresh")
        assertEquals(500, (refresh as ScenarioDecision.RespondWith).statusCode)
        // And protected calls keep failing, which is what makes this a session-expiry test.
        assertEquals(401, (harness.decide(path = "/api/v1/bookings") as ScenarioDecision.RespondWith).statusCode)
    }

    @Test
    fun `refresh slow delays the refresh rather than failing it`() {
        val scenario = AuthScenarioPresets.refreshSlow.build(
            PresetConfiguration(
                mapOf(
                    "protectedPaths" to "/api/v1",
                    "refreshPath" to "/api/v1/auth/refresh",
                    "refreshMethod" to "POST",
                    "delayMillis" to "5000",
                ),
            ),
        )

        val refresh = harness(scenario).decide(method = "POST", path = "/api/v1/auth/refresh")
        assertEquals(5_000L, (refresh as ScenarioDecision.RespondWith).delayMillis)
    }

    @Test
    fun `refresh slow with a zero delay times out instead`() {
        val scenario = AuthScenarioPresets.refreshSlow.build(
            PresetConfiguration(
                mapOf(
                    "protectedPaths" to "/api/v1",
                    "refreshPath" to "/api/v1/auth/refresh",
                    "refreshMethod" to "POST",
                    "delayMillis" to "0",
                ),
            ),
        )

        assertTrue(
            harness(scenario).decide(method = "POST", path = "/api/v1/auth/refresh")
                is ScenarioDecision.FailTimeout,
        )
    }

    /**
     * The storm scenario has to recover, or the interesting part — what the app
     * did *during* the storm — is buried under an endless failure loop.
     */
    @Test
    fun `the 401 storm fails several calls and then recovers`() {
        val scenario = AuthScenarioPresets.concurrentUnauthorized.build(
            AuthScenarioPresets.concurrentUnauthorized.defaults(),
        )
        val harness = harness(scenario)

        val statuses = List(6) {
            (harness.decide(path = "/api/v1/bookings") as? ScenarioDecision.RespondWith)?.statusCode
        }

        assertTrue("the storm should fail several calls", statuses.count { it == 401 } >= 4)
        assertTrue("the storm should end", statuses.last() == null)
    }

    @Test
    fun `an auth preset rejects a path with spaces`() {
        val problems = AuthScenarioPresets.tokenExpires.validate(
            PresetConfiguration(
                mapOf("protectedPaths" to "/api/ v1", "refreshPath" to "/api/v1/auth/refresh"),
            ),
        )
        assertTrue(problems.any { it.contains("spaces") })
    }

    @Test
    fun `an auth preset notes when refresh sits outside the protected prefix`() {
        val problems = AuthScenarioPresets.tokenExpires.validate(
            PresetConfiguration(
                mapOf("protectedPaths" to "/api/v1", "refreshPath" to "/oauth/token"),
            ),
        )
        // A note, not an error: plenty of apps host refresh elsewhere.
        assertTrue(problems.any { it.startsWith("Note:") })
    }

    // ---- pagination -----------------------------------------------------------

    @Test
    fun `page fails targets one page with a query condition`() {
        val scenario = PaginationScenarioPresets.pageFails.build(
            PresetConfiguration(
                mapOf(
                    "path" to "/api/v1/services",
                    "method" to "GET",
                    "parameter" to "page",
                    "target" to "2",
                    "status" to "500",
                ),
            ),
        )

        // The mechanism is a plain query condition; nothing knows about pagination.
        val condition = scenario.rules.single().conditions.single()
        assertTrue(condition is QueryParameterCondition)
        assertEquals("page", (condition as QueryParameterCondition).name)
        assertEquals("2", condition.value)

        val harness = harness(scenario)
        assertTrue(
            harness.decide(path = "/api/v1/services", query = "page=1")
                is ScenarioDecision.PassThrough,
        )
        assertEquals(
            500,
            (harness.decide(path = "/api/v1/services", query = "page=2")
                as ScenarioDecision.RespondWith).statusCode,
        )
    }

    /** Cursor pagination is the same mechanism with a different parameter name. */
    @Test
    fun `a cursor works exactly like a page number`() {
        val scenario = PaginationScenarioPresets.pageFails.build(
            PresetConfiguration(
                mapOf(
                    "path" to "/api/v1/services",
                    "parameter" to "cursor",
                    "target" to "eyJpZCI6MTB9",
                    "status" to "503",
                ),
            ),
        )
        val harness = harness(scenario)

        assertEquals(
            503,
            (harness.decide(path = "/api/v1/services", query = "cursor=eyJpZCI6MTB9")
                as ScenarioDecision.RespondWith).statusCode,
        )
        assertTrue(
            harness.decide(path = "/api/v1/services", query = "cursor=other")
                is ScenarioDecision.PassThrough,
        )
    }

    @Test
    fun `slow next page delays rather than replacing the response`() {
        val scenario = PaginationScenarioPresets.slowNextPage.build(
            PresetConfiguration(
                mapOf(
                    "path" to "/api/v1/services",
                    "parameter" to "page",
                    "target" to "2",
                    "delayMillis" to "5000",
                ),
            ),
        )

        val decision = harness(scenario)
            .decide(path = "/api/v1/services", query = "page=2")
        // A Delay, not a RespondWith: the data stays real and only timing is faked.
        assertEquals(5_000L, (decision as ScenarioDecision.Delay).delayMillis)
    }

    @Test
    fun `empty next page returns the configured fixture`() {
        val body = """{"items":[],"cursor":null}"""
        val scenario = PaginationScenarioPresets.emptyNextPage.build(
            PresetConfiguration(
                mapOf(
                    "path" to "/api/v1/services",
                    "parameter" to "page",
                    "target" to "2",
                    "body" to body,
                ),
            ),
        )

        val decision = harness(scenario).decide(path = "/api/v1/services", query = "page=2")
            as ScenarioDecision.RespondWith
        assertEquals(200, decision.statusCode)
        assertEquals(body, decision.body)
    }

    @Test
    fun `empty first page ignores the pagination parameter`() {
        val scenario = PaginationScenarioPresets.emptyFirstPage.build(
            PresetConfiguration(mapOf("path" to "/api/v1/services")),
        )

        assertTrue(scenario.rules.single().conditions.isEmpty())
        val harness = harness(scenario)
        assertTrue(harness.decide(path = "/api/v1/services") is ScenarioDecision.RespondWith)
        assertTrue(
            harness.decide(path = "/api/v1/services", query = "page=7")
                is ScenarioDecision.RespondWith,
        )
    }

    /** Retry reuses 0.2's response sequence rather than reimplementing it. */
    @Test
    fun `retry succeeds fails the configured number of times then passes through`() {
        val scenario = PaginationScenarioPresets.retrySucceeds.build(
            PresetConfiguration(
                mapOf(
                    "path" to "/api/v1/services",
                    "parameter" to "page",
                    "target" to "2",
                    "attempts" to "2",
                    "status" to "500",
                ),
            ),
        )

        assertTrue(scenario.rules.single().action is NetworkAction.Sequence)

        val harness = harness(scenario)
        val statuses = List(4) {
            (harness.decide(path = "/api/v1/services", query = "page=2")
                as? ScenarioDecision.RespondWith)?.statusCode
        }
        assertEquals(listOf(500, 500, null, null), statuses)
    }

    @Test
    fun `the duplicate fixture returns a body with repeated ids`() {
        val scenario = PaginationScenarioPresets.fixturePage.build(
            PresetConfiguration(
                mapOf(
                    "path" to "/api/v1/services",
                    "parameter" to "page",
                    "target" to "2",
                    "fixture" to "Duplicate data",
                ),
            ),
        )

        val decision = harness(scenario).decide(path = "/api/v1/services", query = "page=2")
            as ScenarioDecision.RespondWith
        assertTrue(decision.body.contains("\"id\":\"1\""))
    }

    @Test
    fun `the malformed fixture contradicts itself`() {
        val scenario = PaginationScenarioPresets.fixturePage.build(
            PresetConfiguration(
                mapOf(
                    "path" to "/api/v1/services",
                    "parameter" to "page",
                    "target" to "2",
                    "fixture" to "Malformed metadata",
                ),
            ),
        )

        val body = (harness(scenario).decide(path = "/api/v1/services", query = "page=2")
            as ScenarioDecision.RespondWith).body
        // No items, yet more pages: the reliable way to make a client loop.
        assertTrue(body.contains("\"data\":[]"))
        assertTrue(body.contains("\"has_more\":true"))
    }

    @Test
    fun `a pagination preset rejects a path with a query string`() {
        val problems = PaginationScenarioPresets.pageFails.validate(
            PresetConfiguration(
                mapOf("path" to "/api/v1/services?page=2", "parameter" to "page", "target" to "2"),
            ),
        )
        assertTrue(problems.any { it.contains("path only") })
    }

    @Test
    fun `a retry preset refuses an unreasonable number of attempts`() {
        val problems = PaginationScenarioPresets.retrySucceeds.validate(
            PresetConfiguration(
                mapOf(
                    "path" to "/api/v1/services",
                    "parameter" to "page",
                    "target" to "2",
                    "attempts" to "500",
                ),
            ),
        )
        assertTrue(problems.isNotEmpty())
    }

    @Test
    fun `presets carry no runtime state into the scenario`() {
        ScenarioPresetRegistry().all.forEach { preset ->
            val scenario = preset.build(preset.defaults())
            // A definition never carries a seed, a counter or a run id.
            assertNull("${preset.id} set a global config it did not need", scenario.globalConfig)
            assertTrue("${preset.id} should not enable chaos", scenario.chaos == null)
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private fun harness(scenario: NetworkScenario) = EngineHarness(
        ActiveNetworkConfiguration(scenario = ActiveScenarioSnapshot.of(scenario)),
    )

    private fun statuses(scenario: NetworkScenario, count: Int): List<Int?> =
        harness(scenario).let { harness ->
            List(count) {
                (harness.decide(path = "/api/v1/bookings") as? ScenarioDecision.RespondWith)
                    ?.statusCode
            }
        }
}
