package io.devkit.netkit

import io.devkit.netkit.scenario.EndpointMatcher
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.LatencyRange
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.Probability
import io.devkit.netkit.scenario.SequenceCompletionBehavior
import io.devkit.netkit.scenario.SequenceStep
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.scenario.WeightedOutcome
import io.devkit.netkit.scenario.chaos.ChaosConfig
import io.devkit.netkit.scenario.chaos.ChaosExclusions
import io.devkit.netkit.scenario.chaos.ChaosScope
import io.devkit.netkit.scenario.condition.BodyCondition
import io.devkit.netkit.scenario.condition.HeaderCondition
import io.devkit.netkit.scenario.condition.PreviousResult
import io.devkit.netkit.scenario.condition.PreviousResultCondition
import io.devkit.netkit.scenario.condition.QueryParameterCondition
import io.devkit.netkit.scenario.condition.RequestCountCondition
import io.devkit.netkit.scenario.condition.StringMatch
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.serialization.JsonScenarioSerializer
import io.devkit.netkit.scenario.serialization.ScenarioImportResult
import io.devkit.netkit.scenario.serialization.ScenarioSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Schema 2, and the promise that schema 1 still works.
 *
 * The backward-compatibility tests use **literal 0.2-era JSON** rather than
 * anything this build produces. A round-trip through the current serializer would
 * prove only that today's writer agrees with today's reader; what matters is that
 * a file a QA engineer exported months ago still imports, and the only way to
 * test that is to write one down.
 */
class SchemaMigrationTest {

    private val serializer = JsonScenarioSerializer()

    // ---- backward compatibility ---------------------------------------------

    /** Exactly what NetKit 0.2 wrote. Do not regenerate this from the current build. */
    private val schemaOneScenario = """
        {
          "format": "netkit",
          "schemaVersion": 1,
          "type": "scenario",
          "exportedAt": "2026-03-12T10:48:00Z",
          "generator": "netkit/0.2.0",
          "scenario": {
            "id": "scn-legacy-1",
            "name": "Checkout Retry Bug",
            "description": "Two failures then a success.",
            "enabled": true,
            "global": { "mode": "normal", "latencyMillis": 2500 },
            "rules": [
              {
                "id": "netkit-rule-legacy-1",
                "name": "Bookings retry",
                "enabled": true,
                "method": "GET",
                "matcher": { "type": "exactPath", "path": "/api/v1/bookings" },
                "action": {
                  "type": "sequence",
                  "completion": "repeatLast",
                  "steps": [
                    { "type": "respond", "statusCode": 500, "contentType": "application/json" },
                    { "type": "respond", "statusCode": 200, "body": "{\"ok\":true}",
                      "contentType": "application/json" }
                  ]
                }
              },
              {
                "id": "netkit-rule-legacy-2",
                "enabled": true,
                "method": "POST",
                "matcher": { "type": "exactPath", "path": "/api/v1/checkout" },
                "action": { "type": "timeout", "timeoutType": "read" }
              }
            ],
            "createdAt": 1710240000000,
            "updatedAt": 1710240000000,
            "source": "created"
          }
        }
    """.trimIndent()

    @Test
    fun `a NetKit 0-2 scenario file still imports`() {
        val result = serializer.import(schemaOneScenario)

        assertTrue("import failed: ${result.failureReason}", result is ScenarioImportResult.Scenario)
        val scenario = (result as ScenarioImportResult.Scenario).scenario
        assertEquals("Checkout Retry Bug", scenario.name)
        assertEquals(2, scenario.rules.size)
        assertEquals(2_500L, scenario.globalConfig!!.latencyMillis)
    }

    @Test
    fun `a migrated scenario keeps its rules and sequences intact`() {
        val scenario = (serializer.import(schemaOneScenario) as ScenarioImportResult.Scenario)
            .scenario

        val sequence = scenario.rules[0].action as NetworkAction.Sequence
        assertEquals(2, sequence.steps.size)
        assertEquals(SequenceCompletionBehavior.REPEAT_LAST, sequence.completion)
        assertEquals(
            500,
            (sequence.steps[0].action as NetworkAction.ReturnResponse).statusCode,
        )
        assertEquals(
            """{"ok":true}""",
            (sequence.steps[1].action as NetworkAction.ReturnResponse).body,
        )
        assertEquals(
            TimeoutType.READ,
            (scenario.rules[1].action as NetworkAction.Timeout).type,
        )
    }

    /**
     * The reason the migration can rewrite nothing: every 0.3 field defaults to
     * the behaviour a 0.2 rule already had.
     */
    @Test
    fun `a migrated rule defaults to always firing with no conditions`() {
        val scenario = (serializer.import(schemaOneScenario) as ScenarioImportResult.Scenario)
            .scenario

        scenario.rules.forEach { rule ->
            assertEquals(Probability.ALWAYS, rule.probability)
            assertTrue(rule.conditions.isEmpty())
            assertFalse(rule.isConditional)
        }
        assertEquals(null, scenario.chaos)
        assertEquals(null, scenario.preset)
    }

    @Test
    fun `a NetKit 0-2 pack file still imports`() {
        val pack = """
            {
              "format": "netkit",
              "schemaVersion": 1,
              "type": "scenario-pack",
              "generator": "netkit/0.2.0",
              "pack": { "id": "pack-legacy", "name": "Checkout", "source": "created" },
              "scenarios": [
                {
                  "id": "scn-legacy-2",
                  "name": "Gateway unavailable",
                  "rules": [
                    {
                      "id": "netkit-rule-legacy-3",
                      "method": "POST",
                      "matcher": { "type": "exactPath", "path": "/api/v1/checkout" },
                      "action": { "type": "respond", "statusCode": 503 }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = serializer.import(pack)

        assertTrue("import failed: ${result.failureReason}", result is ScenarioImportResult.Pack)
        assertEquals(1, (result as ScenarioImportResult.Pack).scenarios.size)
    }

    @Test
    fun `the current build writes schema 2`() {
        val export = serializer.exportScenario(NetworkScenario(name = "Anything"))

        assertTrue(export.content.contains("\"schemaVersion\": 2"))
        assertEquals(2, ScenarioSchema.CURRENT_VERSION)
        assertEquals(1, ScenarioSchema.MIN_SUPPORTED_VERSION)
    }

    /**
     * A 0.3 scenario that uses nothing new must produce a file a reader would not
     * be able to tell from a 0.2 one, apart from the version stamp. That keeps
     * diffs of a scenario repository readable across the upgrade.
     */
    @Test
    fun `a scenario using no 0-3 features writes no 0-3 fields`() {
        val export = serializer.exportScenario(
            NetworkScenario(
                name = "Plain",
                rules = listOf(
                    EndpointRule.forPath("/api/v1/bookings", HttpMethod.GET, NetworkAction.ReturnResponse(500)),
                ),
            ),
        )

        assertFalse(export.content.contains("probability"))
        assertFalse(export.content.contains("conditions"))
        assertFalse(export.content.contains("chaos"))
        assertFalse(export.content.contains("preset"))
    }

    // ---- schema 2 round trip --------------------------------------------------

    @Test
    fun `every 0-3 feature survives a round trip`() {
        val original = fullyLoadedScenario()

        val exported = serializer.exportScenario(original).content
        val result = serializer.import(exported)

        assertTrue("import failed: ${result.failureReason}", result is ScenarioImportResult.Scenario)
        val restored = (result as ScenarioImportResult.Scenario).scenario

        assertEquals(original.rules.size, restored.rules.size)
        assertEquals(original.chaos, restored.chaos)
        assertEquals(original.preset, restored.preset)
        original.rules.forEachIndexed { index, rule ->
            val other = restored.rules[index]
            assertEquals("rule $index matcher", rule.matcher, other.matcher)
            assertEquals("rule $index conditions", rule.conditions, other.conditions)
            assertEquals("rule $index probability", rule.probability, other.probability)
            assertEquals("rule $index action", rule.action, other.action)
        }
    }

    @Test
    fun `a path-prefix matcher survives a round trip`() {
        val scenario = NetworkScenario(
            name = "Prefixed",
            rules = listOf(
                EndpointRule(
                    matcher = EndpointMatcher.PathPrefix("/api/v1"),
                    action = NetworkAction.ReturnResponse(401),
                ),
            ),
        )

        val restored = roundTrip(scenario)
        assertTrue(restored.rules.single().matcher is EndpointMatcher.PathPrefix)
    }

    @Test
    fun `the new actions survive a round trip`() {
        val scenario = NetworkScenario(
            name = "New actions",
            rules = listOf(
                EndpointRule.forPath("/a", action = NetworkAction.Disconnect),
                EndpointRule.forPath(
                    "/b",
                    action = NetworkAction.RandomDelay(LatencyRange(500, 3_000)),
                ),
                EndpointRule.forPath(
                    "/c",
                    action = NetworkAction.Weighted(
                        listOf(
                            WeightedOutcome(3, NetworkAction.PassThrough),
                            WeightedOutcome(1, NetworkAction.Disconnect),
                        ),
                    ),
                ),
            ),
        )

        val restored = roundTrip(scenario)
        assertEquals(NetworkAction.Disconnect, restored.rules[0].action)
        assertEquals(
            LatencyRange(500, 3_000),
            (restored.rules[1].action as NetworkAction.RandomDelay).latency,
        )
        assertEquals(2, (restored.rules[2].action as NetworkAction.Weighted).outcomes.size)
    }

    // ---- rejecting bad schema-2 data -------------------------------------------

    @Test
    fun `an out-of-range probability is refused`() {
        val result = serializer.import(fileWithRule("""
            "probability": 1.5,
            "action": { "type": "respond", "statusCode": 500 }
        """.trimIndent()))

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue(result.failureReason!!.contains("between 0.0 and 1.0"))
    }

    @Test
    fun `a negative outcome weight is refused`() {
        val result = serializer.import(fileWithRule("""
            "action": {
              "type": "weighted",
              "outcomes": [ { "weight": -1, "action": { "type": "offline" } } ]
            }
        """.trimIndent()))

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue(result.failureReason!!.contains("positive"))
    }

    @Test
    fun `an empty weighted action is refused`() {
        val result = serializer.import(fileWithRule("""
            "action": { "type": "weighted", "outcomes": [] }
        """.trimIndent()))

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue(result.failureReason!!.contains("at least one outcome"))
    }

    @Test
    fun `an inverted latency range is refused`() {
        val result = serializer.import(fileWithRule("""
            "action": { "type": "randomDelay", "minMillis": 3000, "maxMillis": 500 }
        """.trimIndent()))

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue(result.failureReason!!.contains("inverted"))
    }

    @Test
    fun `an invalid request-count condition is refused`() {
        val result = serializer.import(fileWithRule("""
            "conditions": [ { "type": "requestCount", "kind": "exactly", "from": 0 } ],
            "action": { "type": "respond", "statusCode": 500 }
        """.trimIndent()))

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue(result.failureReason!!.contains("at least 1"))
    }

    @Test
    fun `an every-N interval of zero is refused`() {
        val result = serializer.import(fileWithRule("""
            "conditions": [ { "type": "requestCount", "kind": "every", "interval": 0 } ],
            "action": { "type": "respond", "statusCode": 500 }
        """.trimIndent()))

        assertTrue(result is ScenarioImportResult.InvalidFile)
    }

    @Test
    fun `a header condition with no name is refused`() {
        val result = serializer.import(fileWithRule("""
            "conditions": [ { "type": "header", "name": "", "match": "exists" } ],
            "action": { "type": "respond", "statusCode": 500 }
        """.trimIndent()))

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue(result.failureReason!!.contains("missing its name"))
    }

    @Test
    fun `a query condition comparing nothing is refused`() {
        val result = serializer.import(fileWithRule("""
            "conditions": [ { "type": "query", "name": "page", "match": "equals", "value": "" } ],
            "action": { "type": "respond", "statusCode": 500 }
        """.trimIndent()))

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue(result.failureReason!!.contains("no value to compare"))
    }

    /**
     * An empty `anyOf` matches nothing, which would silently disable the rule.
     * Refusing is honest; importing an inert rule is not.
     */
    @Test
    fun `an empty anyOf group is refused`() {
        val result = serializer.import(fileWithRule("""
            "conditions": [ { "type": "anyOf", "conditions": [] } ],
            "action": { "type": "respond", "statusCode": 500 }
        """.trimIndent()))

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue(result.failureReason!!.contains("at least one entry"))
    }

    @Test
    fun `an unknown condition type is refused rather than dropped`() {
        val result = serializer.import(fileWithRule("""
            "conditions": [ { "type": "phaseOfTheMoon", "phase": "waxing" } ],
            "action": { "type": "respond", "statusCode": 500 }
        """.trimIndent()))

        // Dropping it would widen the rule from "sometimes" to "always", which is
        // a scenario reproducing the wrong bug.
        assertTrue(result is ScenarioImportResult.InvalidFile)
    }

    @Test
    fun `an out-of-range chaos failure rate is refused`() {
        val result = serializer.import(fileWithChaos(""""failureProbability": 2.0"""))

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue(result.failureReason!!.contains("between 0.0 and 1.0"))
    }

    @Test
    fun `an inverted chaos latency range is refused`() {
        val result = serializer.import(
            fileWithChaos(""""minLatencyMillis": 3000, "maxLatencyMillis": 500"""),
        )

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue(result.failureReason!!.contains("inverted"))
    }

    @Test
    fun `a chaos pass-through outcome is refused`() {
        val result = serializer.import(
            fileWithChaos(
                """"failures": [ { "weight": 1, "action": { "type": "passThrough" } } ]""",
            ),
        )

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue(result.failureReason!!.contains("pass-through"))
    }

    // ---- helpers ----------------------------------------------------------------

    private fun roundTrip(scenario: NetworkScenario): NetworkScenario {
        val result = serializer.import(serializer.exportScenario(scenario).content)
        assertTrue("import failed: ${result.failureReason}", result is ScenarioImportResult.Scenario)
        return (result as ScenarioImportResult.Scenario).scenario
    }

    private fun fullyLoadedScenario() = NetworkScenario(
        name = "Everything 0.3 can do",
        rules = listOf(
            EndpointRule(
                id = "rule-conditions",
                name = "Page 2, first attempt only, half the time",
                method = HttpMethod.GET,
                matcher = EndpointMatcher.ExactPath("/api/v1/services"),
                conditions = listOf(
                    QueryParameterCondition("page", StringMatch.EQUALS, "2"),
                    RequestCountCondition.Exactly(1),
                    HeaderCondition("X-App-Version", StringMatch.CONTAINS, "4."),
                    BodyCondition("coupon", jsonField = "code"),
                    PreviousResultCondition(PreviousResult.BEFORE_ANY_FAILURE),
                ),
                probability = Probability(0.5),
                action = NetworkAction.ReturnResponse(500),
            ),
            EndpointRule(
                id = "rule-weighted",
                matcher = EndpointMatcher.PathPrefix("/api/v1"),
                action = NetworkAction.Weighted(
                    listOf(
                        WeightedOutcome(60, NetworkAction.PassThrough),
                        WeightedOutcome(15, NetworkAction.ReturnResponse(503)),
                        WeightedOutcome(10, NetworkAction.Timeout(TimeoutType.CONNECT)),
                        WeightedOutcome(5, NetworkAction.Disconnect),
                    ),
                ),
            ),
            EndpointRule(
                id = "rule-random-delay",
                matcher = EndpointMatcher.ExactPath("/api/v1/feed"),
                conditions = listOf(RequestCountCondition.Every(3)),
                action = NetworkAction.RandomDelay(LatencyRange(200, 800)),
            ),
            EndpointRule(
                id = "rule-sequence",
                matcher = EndpointMatcher.ExactPath("/api/v1/checkout"),
                action = NetworkAction.Sequence(
                    steps = listOf(
                        SequenceStep(NetworkAction.ReturnResponse(500)),
                        SequenceStep(NetworkAction.Disconnect),
                        SequenceStep(NetworkAction.PassThrough),
                    ),
                    completion = SequenceCompletionBehavior.PASS_THROUGH,
                ),
            ),
        ),
        chaos = ChaosConfig(
            enabled = true,
            failureProbability = Probability(0.15),
            latency = LatencyRange(500, 3_000),
            failures = listOf(
                WeightedOutcome(3, NetworkAction.ReturnResponse(500)),
                WeightedOutcome(2, NetworkAction.Disconnect),
            ),
            scope = ChaosScope(
                hosts = listOf("api.staging.example.com"),
                pathPrefixes = listOf("/api/v1"),
                methods = listOf(HttpMethod.GET, HttpMethod.POST),
            ),
            exclusions = ChaosExclusions(listOf("/api/v1/auth/refresh", "/analytics")),
        ),
        preset = io.devkit.netkit.scenario.model.ScenarioPresetOrigin(
            presetId = "auth.refresh-fails",
            presetName = "Refresh fails",
            configuration = mapOf("protectedPaths" to "/api/v1"),
        ),
    )

    /** A schema-2 file with one rule whose body is [ruleFields]. */
    private fun fileWithRule(ruleFields: String) = """
        {
          "format": "netkit",
          "schemaVersion": 2,
          "type": "scenario",
          "scenario": {
            "id": "scn-1",
            "name": "Test",
            "rules": [
              {
                "id": "rule-1",
                "method": "GET",
                "matcher": { "type": "exactPath", "path": "/api/v1/bookings" },
                $ruleFields
              }
            ]
          }
        }
    """.trimIndent()

    /** A schema-2 file whose scenario has chaos with [chaosFields]. */
    private fun fileWithChaos(chaosFields: String) = """
        {
          "format": "netkit",
          "schemaVersion": 2,
          "type": "scenario",
          "scenario": {
            "id": "scn-1",
            "name": "Test",
            "chaos": { "enabled": true, $chaosFields }
          }
        }
    """.trimIndent()

    @Test
    fun `the migration pipeline reports the version it came from`() {
        val result = serializer.import(schemaOneScenario)
        assertNotNull(result)
        assertEquals(1, (result as ScenarioImportResult.Scenario).summary.schemaVersion)
    }
}
