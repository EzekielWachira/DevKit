package io.devkit.netkit

import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.MalformedResponseType
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.ResponseHeader
import io.devkit.netkit.scenario.SequenceCompletionBehavior
import io.devkit.netkit.scenario.SequenceStep
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioMetadata
import io.devkit.netkit.scenario.model.ScenarioPack
import io.devkit.netkit.scenario.model.ScenarioSource
import io.devkit.netkit.scenario.serialization.JsonScenarioSerializer
import io.devkit.netkit.scenario.serialization.ScenarioImportResult
import io.devkit.netkit.scenario.serialization.ScenarioSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `.netkit.json` format: round trips, rejections, and what must never be in
 * an exported file.
 *
 * The rejection cases matter as much as the happy path. A `.netkit.json` arrives
 * from another person's device by way of a bug tracker or a chat app, so
 * "malformed input is handled gracefully" is a security property, not a nicety.
 */
class ScenarioSerializerTest {

    private val serializer = JsonScenarioSerializer(nowMillis = { 1_772_000_000_000 })

    private fun scenario(
        name: String = "Checkout retry",
        rules: List<io.devkit.netkit.scenario.EndpointRule> = listOf(
            Fixtures.rule(action = NetworkAction.ReturnResponse(503)),
        ),
        global: GlobalNetworkConfig? = GlobalNetworkConfig(latencyMillis = 2_500),
    ) = NetworkScenario(
        name = name,
        description = "Reproduces QA-421",
        globalConfig = global,
        rules = rules,
    )

    private fun importScenario(content: String): NetworkScenario {
        val result = serializer.import(content)
        assertTrue("expected a scenario, got $result", result is ScenarioImportResult.Scenario)
        return (result as ScenarioImportResult.Scenario).scenario
    }

    // ---- round trips -------------------------------------------------------

    @Test
    fun `a scenario survives a round trip`() {
        val original = scenario()

        val restored = importScenario(serializer.exportScenario(original).content)

        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.description, restored.description)
        assertEquals(original.globalConfig, restored.globalConfig)
        assertEquals(original.rules, restored.rules)
    }

    @Test
    fun `every action kind survives a round trip`() {
        val rules = listOf(
            Fixtures.rule(path = "/a", action = NetworkAction.PassThrough),
            Fixtures.rule(path = "/b", action = NetworkAction.Delay(1_500)),
            Fixtures.rule(
                path = "/c",
                action = NetworkAction.ReturnResponse(
                    statusCode = 429,
                    body = """{"error":"slow down"}""",
                    contentType = "application/problem+json",
                    headers = listOf(
                        ResponseHeader("Retry-After", "60"),
                        ResponseHeader("X-RateLimit-Remaining", "0"),
                    ),
                    delayMillis = 250,
                ),
            ),
            Fixtures.rule(
                path = "/d",
                action = NetworkAction.Malformed(MalformedResponseType.TruncatedJson, 200),
            ),
            Fixtures.rule(
                path = "/e",
                action = NetworkAction.Malformed(
                    MalformedResponseType.Custom("Nulls", "{\"a\":null,", "application/json"),
                ),
            ),
            Fixtures.rule(path = "/f", action = NetworkAction.Offline),
            Fixtures.rule(path = "/g", action = NetworkAction.Timeout(TimeoutType.CONNECT)),
            Fixtures.rule(
                path = "/h",
                action = NetworkAction.Sequence(
                    steps = listOf(
                        SequenceStep(NetworkAction.ReturnResponse(500)),
                        SequenceStep(NetworkAction.Timeout(TimeoutType.READ)),
                        SequenceStep(NetworkAction.ReturnResponse(200, """{"ok":true}""")),
                    ),
                    completion = SequenceCompletionBehavior.PASS_THROUGH,
                ),
            ),
        )

        val restored = importScenario(serializer.exportScenario(scenario(rules = rules)).content)

        assertEquals(rules, restored.rules)
    }

    @Test
    fun `every global mode survives a round trip`() {
        listOf(
            GlobalNetworkConfig(GlobalNetworkMode.Normal, 0),
            GlobalNetworkConfig(GlobalNetworkMode.Normal, 2_500),
            GlobalNetworkConfig(GlobalNetworkMode.Offline),
            GlobalNetworkConfig(GlobalNetworkMode.Timeout(TimeoutType.CONNECT)),
            GlobalNetworkConfig(GlobalNetworkMode.Timeout(TimeoutType.READ)),
        ).forEach { global ->
            val restored = importScenario(
                serializer.exportScenario(scenario(global = global)).content,
            )
            assertEquals(global, restored.globalConfig)
        }
    }

    @Test
    fun `a scenario that sets no global keeps it unset`() {
        val restored = importScenario(serializer.exportScenario(scenario(global = null)).content)

        assertNull(restored.globalConfig)
    }

    @Test
    fun `every http method survives a round trip`() {
        HttpMethod.entries.forEach { method ->
            val rule = Fixtures.rule(method = method)
            val restored = importScenario(
                serializer.exportScenario(scenario(rules = listOf(rule))).content,
            )
            assertEquals(method, restored.rules.single().method)
        }
    }

    @Test
    fun `a pack survives a round trip`() {
        val pack = ScenarioPack(name = "Checkout", description = "Payment failures")
        val scenarios = listOf(scenario("Declined"), scenario("Timeout"))

        val result = serializer.import(serializer.exportPack(pack, scenarios).content)

        assertTrue(result is ScenarioImportResult.Pack)
        val imported = result as ScenarioImportResult.Pack
        assertEquals(pack.id, imported.pack.id)
        assertEquals("Checkout", imported.pack.name)
        assertEquals(listOf("Declined", "Timeout"), imported.scenarios.map { it.name })
        // Membership travels with the pack so a re-import lands in the right place.
        assertTrue(imported.scenarios.all { it.metadata.packId == pack.id })
    }

    @Test
    fun `an exported file names the format and version`() {
        val export = serializer.exportScenario(scenario())

        assertTrue(export.content.contains("\"format\": \"${ScenarioSchema.FORMAT}\""))
        assertTrue(export.content.contains("\"schemaVersion\": ${ScenarioSchema.CURRENT_VERSION}"))
        assertTrue(export.content.contains("\"type\": \"${ScenarioSchema.TYPE_SCENARIO}\""))
        assertTrue(export.content.contains("\"exportedAt\""))
    }

    @Test
    fun `the suggested file name is a readable slug`() {
        assertEquals(
            "checkout-retry-bug.netkit.json",
            serializer.exportScenario(scenario("Checkout Retry Bug")).suggestedFileName,
        )
        assertEquals(
            "scenario.netkit.json",
            serializer.exportScenario(scenario("!!!")).suggestedFileName,
        )
    }

    @Test
    fun `an imported scenario is always marked as imported`() {
        val builtIn = scenario().copy(
            metadata = ScenarioMetadata(source = ScenarioSource.BUILT_IN),
        )

        val restored = importScenario(serializer.exportScenario(builtIn).content)

        // A hand-edited file must not be able to make itself uneditable.
        assertEquals(ScenarioSource.IMPORTED, restored.metadata.source)
    }

    @Test
    fun `the import summary describes what the file contains`() {
        val rules = listOf(
            Fixtures.rule(path = "/a", action = NetworkAction.ReturnResponse(503)),
            Fixtures.rule(
                path = "/b",
                action = NetworkAction.Sequence(
                    listOf(SequenceStep(NetworkAction.Timeout(TimeoutType.READ))),
                ),
            ),
            Fixtures.rule(
                path = "/c",
                action = NetworkAction.Malformed(MalformedResponseType.InvalidJson),
            ),
        )

        val result = serializer.import(
            serializer.exportScenario(scenario(rules = rules)).content,
        ) as ScenarioImportResult.Scenario

        assertEquals(3, result.summary.ruleCount)
        assertEquals(1, result.summary.scenarioCount)
        assertTrue(result.summary.hasResponseOverrides)
        assertTrue(result.summary.hasSequences)
        assertTrue(result.summary.hasMalformedResponses)
        assertTrue(result.summary.hasTimeouts)
        assertFalse(result.summary.hasOfflineRules)
        assertEquals("Normal · 2500ms", result.summary.globalSummary)
        assertTrue(result.summary.contents.contains("Response sequences"))
    }

    // ---- rejections --------------------------------------------------------

    @Test
    fun `a newer schema version is refused rather than partly imported`() {
        // Derived from the current version rather than hard-coded, so bumping the
        // schema does not quietly turn this into a test of nothing: a literal `1`
        // here stopped matching the moment 0.3 started writing version 2.
        val future = serializer.exportScenario(scenario()).content
            .replace(
                "\"schemaVersion\": ${ScenarioSchema.CURRENT_VERSION}",
                "\"schemaVersion\": 4",
            )

        val result = serializer.import(future)

        assertTrue(result is ScenarioImportResult.UnsupportedVersion)
        val unsupported = result as ScenarioImportResult.UnsupportedVersion
        assertEquals(4, unsupported.found)
        assertEquals(ScenarioSchema.CURRENT_VERSION, unsupported.supported)
        assertTrue(unsupported.reason.contains("Unsupported NetKit schema version: 4"))
    }

    @Test
    fun `a file with no format identifier is refused`() {
        val result = serializer.import("""{"schemaVersion":1,"type":"scenario"}""")

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue((result as ScenarioImportResult.InvalidFile).reason.contains("format"))
    }

    @Test
    fun `a file from another tool is refused`() {
        val result = serializer.import("""{"format":"postman","schemaVersion":1}""")

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue((result as ScenarioImportResult.InvalidFile).reason.contains("postman"))
    }

    @Test
    fun `a file with no schema version is refused`() {
        val result = serializer.import("""{"format":"netkit","type":"scenario"}""")

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue((result as ScenarioImportResult.InvalidFile).reason.contains("schema version"))
    }

    @Test
    fun `malformed JSON is refused without throwing`() {
        listOf("", "   ", "not json at all", "{", "[]", """{"format":"netkit",""")
            .forEach { content ->
                val result = serializer.import(content)
                assertNotNull("expected a rejection for '$content'", result.failureReason)
            }
    }

    @Test
    fun `an unknown file type is refused`() {
        val result = serializer.import(
            """{"format":"netkit","schemaVersion":1,"type":"workspace"}""",
        )

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue((result as ScenarioImportResult.InvalidFile).reason.contains("workspace"))
    }

    @Test
    fun `an unknown action type is refused with a useful message`() {
        val result = serializer.import(
            """
            {
              "format": "netkit",
              "schemaVersion": 1,
              "type": "scenario",
              "scenario": {
                "id": "s1",
                "name": "Chaos",
                "rules": [{
                  "id": "r1",
                  "matcher": {"type": "exactPath", "path": "/api"},
                  "action": {"type": "chaos", "probability": 0.5}
                }]
              }
            }
            """.trimIndent(),
        )

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue(
            (result as ScenarioImportResult.InvalidFile).reason.contains("does not know"),
        )
    }

    @Test
    fun `an unknown matcher type is refused`() {
        val result = serializer.import(
            """
            {
              "format": "netkit", "schemaVersion": 1, "type": "scenario",
              "scenario": {
                "id": "s1", "name": "Regex",
                "rules": [{
                  "id": "r1",
                  "matcher": {"type": "regex", "pattern": ".*"},
                  "action": {"type": "offline"}
                }]
              }
            }
            """.trimIndent(),
        )

        assertNotNull(result.failureReason)
    }

    @Test
    fun `an out-of-range status is refused`() {
        val result = importFailure(action = """{"type":"respond","statusCode":9000}""")

        assertTrue(result.contains("HTTP status must be between"))
    }

    @Test
    fun `a negative delay is refused`() {
        val result = importFailure(action = """{"type":"delay","delayMillis":-5}""")

        assertTrue(result.contains("cannot be negative"))
    }

    @Test
    fun `a negative global latency is refused`() {
        val result = serializer.import(
            """
            {
              "format": "netkit", "schemaVersion": 1, "type": "scenario",
              "scenario": {
                "id": "s1", "name": "Bad",
                "global": {"mode": "normal", "latencyMillis": -1},
                "rules": []
              }
            }
            """.trimIndent(),
        )

        assertTrue(result.failureReason.orEmpty().contains("negative"))
    }

    @Test
    fun `an empty sequence is refused`() {
        val result = importFailure(action = """{"type":"sequence","steps":[]}""")

        assertTrue(result.contains("at least one step"))
    }

    @Test
    fun `a nested sequence is refused`() {
        val result = importFailure(
            action = """{"type":"sequence","steps":[{"type":"sequence","steps":[]}]}""",
        )

        assertTrue(result.contains("cannot be a sequence"))
    }

    @Test
    fun `an unknown completion behaviour is refused`() {
        val result = importFailure(
            action = """
                {"type":"sequence","completion":"chaos",
                 "steps":[{"type":"respond","statusCode":500}]}
            """.trimIndent(),
        )

        assertTrue(result.contains("completion"))
    }

    @Test
    fun `an unknown malformed kind is refused`() {
        val result = importFailure(action = """{"type":"malformed","kind":"cosmic-rays"}""")

        assertTrue(result.contains("cosmic-rays"))
    }

    @Test
    fun `a scenario with no name is refused`() {
        val result = serializer.import(
            """
            {
              "format": "netkit", "schemaVersion": 1, "type": "scenario",
              "scenario": {"id": "s1", "name": "", "rules": []}
            }
            """.trimIndent(),
        )

        assertTrue(result.failureReason.orEmpty().contains("name"))
    }

    @Test
    fun `duplicate rule ids are refused`() {
        val result = serializer.import(
            """
            {
              "format": "netkit", "schemaVersion": 1, "type": "scenario",
              "scenario": {
                "id": "s1", "name": "Twins",
                "rules": [
                  {"id":"r","matcher":{"type":"exactPath","path":"/a"},
                   "action":{"type":"offline"}},
                  {"id":"r","matcher":{"type":"exactPath","path":"/b"},
                   "action":{"type":"offline"}}
                ]
              }
            }
            """.trimIndent(),
        )

        assertTrue(result is ScenarioImportResult.InvalidScenario)
        assertTrue(result.failureReason.orEmpty().contains("unique"))
    }

    @Test
    fun `an oversized file is refused before it is parsed`() {
        val huge = "x".repeat(NetKitLimits.MAX_IMPORT_BYTES + 1)

        val result = serializer.import(huge)

        assertTrue(result is ScenarioImportResult.TooLarge)
        assertEquals(NetKitLimits.MAX_IMPORT_BYTES, (result as ScenarioImportResult.TooLarge).limit)
    }

    @Test
    fun `an oversized response body is refused`() {
        val body = "y".repeat(NetKitLimits.MAX_BODY_BYTES + 1)
        val result = serializer.import(
            """
            {
              "format": "netkit", "schemaVersion": 1, "type": "scenario",
              "scenario": {
                "id": "s1", "name": "Huge",
                "rules": [{
                  "id":"r1","matcher":{"type":"exactPath","path":"/a"},
                  "action":{"type":"respond","statusCode":200,"body":"$body"}
                }]
              }
            }
            """.trimIndent(),
        )

        assertTrue(result is ScenarioImportResult.InvalidScenario)
        assertTrue(result.failureReason.orEmpty().contains("KB"))
    }

    @Test
    fun `a header with a line break is refused`() {
        val result = importFailure(
            action = """
                {"type":"respond","statusCode":200,
                 "headers":[{"name":"X-Bad","value":"a\nb"}]}
            """.trimIndent(),
        )

        assertTrue(result.contains("HTTP does not allow"))
    }

    @Test
    fun `unknown optional fields do not break an import`() {
        val result = serializer.import(
            """
            {
              "format": "netkit", "schemaVersion": 1, "type": "scenario",
              "somethingNew": {"from": "a later patch release"},
              "scenario": {
                "id": "s1", "name": "Forward compatible", "rules": [],
                "unknownField": 42
              }
            }
            """.trimIndent(),
        )

        assertTrue(result is ScenarioImportResult.Scenario)
    }

    private fun importFailure(action: String): String {
        val result = serializer.import(
            """
            {
              "format": "netkit", "schemaVersion": 1, "type": "scenario",
              "scenario": {
                "id": "s1", "name": "Bad",
                "rules": [{
                  "id": "r1",
                  "matcher": {"type": "exactPath", "path": "/api"},
                  "action": $action
                }]
              }
            }
            """.trimIndent(),
        )
        return result.failureReason ?: error("expected a rejection, got $result")
    }
}
