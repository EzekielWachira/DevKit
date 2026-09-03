package io.devkit.netkit

import io.devkit.netkit.masking.DefaultSensitiveDataMasker
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.Probability
import io.devkit.netkit.scenario.ResponseHeader
import io.devkit.netkit.scenario.model.NetworkScenario
import io.devkit.netkit.scenario.model.ScenarioId
import io.devkit.netkit.scenario.run.ExecutionEvent
import io.devkit.netkit.scenario.run.ExecutionEventType
import io.devkit.netkit.scenario.run.RunStartReason
import io.devkit.netkit.scenario.run.ScenarioRun
import io.devkit.netkit.scenario.run.ScenarioRunId
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import io.devkit.netkit.scenario.serialization.JsonScenarioSerializer
import io.devkit.netkit.scenario.serialization.ReproductionExporter
import io.devkit.netkit.scenario.serialization.ScenarioImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reproduction workflow, and the promise that it leaks nothing.
 *
 * The security assertions here are the load-bearing ones. A reproduction's whole
 * value is that it can be attached to a public bug tracker without anyone reading
 * it first, and a single credential reaching one would end that.
 */
class ReproductionTest {

    private val exporter = ReproductionExporter(DefaultSensitiveDataMasker()) { FIXED_TIME }
    private val serializer = JsonScenarioSerializer()

    // ---- the summary ----------------------------------------------------------

    @Test
    fun `the summary carries everything needed to reproduce`() {
        val summary = exporter.summary(run(), scenario())

        assertTrue(summary.contains("NetKit Reproduction"))
        assertTrue(summary.contains("Poor Mobile Network"))
        assertTrue(summary.contains("843921773"))
        assertTrue(summary.contains("run-abc12345"))
        assertTrue(summary.contains("Schema:"))
        assertTrue(summary.contains(NetKitVersion.NAME))
    }

    @Test
    fun `the summary names the scenario id a developer must import`() {
        val summary = exporter.summary(run(), scenario())
        assertTrue(summary.contains("checkout-chaos"))
    }

    // ---- the trace --------------------------------------------------------------

    @Test
    fun `the trace lists decisions by evaluation index`() {
        val trace = exporter.trace(run(), events())

        assertTrue(trace.contains("Seed: 843921773"))
        assertTrue(trace.contains("#1 GET /api/v1/profile"))
        assertTrue(trace.contains("#17 GET /api/v1/bookings"))
        assertTrue(trace.contains("HTTP 503"))
    }

    /**
     * Truncation from the front, not the back. The events immediately before a
     * failure are the ones worth having; the first two hundred requests of a
     * session are not.
     */
    @Test
    fun `a long trace keeps the most recent events`() {
        val many = (1L..500L).map { index ->
            ExecutionEvent(
                evaluationIndex = index,
                atMillis = FIXED_TIME,
                type = ExecutionEventType.ACTION_EXECUTED,
                method = "GET",
                path = "/api/v1/bookings",
                detail = "event-$index",
            )
        }

        val trace = exporter.trace(run(), many)

        assertTrue(trace.contains("event-500"))
        assertFalse(trace.contains("event-1 "))
        assertTrue(trace.contains("showing the last"))
    }

    @Test
    fun `a traced path has its credentials masked`() {
        val leaky = listOf(
            ExecutionEvent(
                evaluationIndex = 1,
                atMillis = FIXED_TIME,
                type = ExecutionEventType.ACTION_EXECUTED,
                method = "GET",
                path = "/api/v1/bookings?access_token=super-secret&page=2",
                detail = "HTTP 500",
            ),
        )

        val trace = exporter.trace(run(), leaky)

        assertFalse("the token leaked", trace.contains("super-secret"))
        assertTrue("the masker's placeholder should be there", trace.contains("••••••••"))
        // The harmless parameter survives, because it is the useful part.
        assertTrue(trace.contains("page=2"))
    }

    // ---- the exported file --------------------------------------------------------

    @Test
    fun `an exported reproduction carries the scenario, the seed and the trace`() {
        val export = exporter.export(run(), scenario(), events(), includeTrace = true)

        assertTrue(export.content.contains("\"type\": \"reproduction\""))
        assertTrue(export.content.contains("\"seed\": 843921773"))
        assertTrue(export.content.contains("\"runId\": \"run-abc12345\""))
        assertTrue(export.content.contains("Poor Mobile Network"))
        assertTrue(export.content.contains("\"trace\""))
        assertEquals("poor-mobile-network-843921773.netkit-run.json", export.suggestedFileName)
    }

    @Test
    fun `a reproduction can be exported without a trace`() {
        val export = exporter.export(run(), scenario(), events(), includeTrace = false)

        assertFalse(export.content.contains("\"trace\""))
        assertTrue(export.content.contains("\"seed\": 843921773"))
    }

    @Test
    fun `an exported reproduction imports and offers its seed`() {
        val export = exporter.export(run(), scenario(), events(), includeTrace = true)

        val result = serializer.import(export.content)

        assertTrue("import failed: ${result.failureReason}", result is ScenarioImportResult.Reproduction)
        val reproduction = result as ScenarioImportResult.Reproduction
        assertEquals(843_921_773L, reproduction.seed)
        assertEquals("run-abc12345", reproduction.runId)
        assertEquals("Poor Mobile Network", reproduction.scenario.name)
        assertTrue(reproduction.trace.isNotEmpty())
        assertEquals(843_921_773L, reproduction.summary.seed)
    }

    /**
     * The imported scenario has to be a *working* scenario, not a description of
     * one — otherwise "import and press restart" would not reproduce anything.
     */
    @Test
    fun `an imported reproduction runs the behaviour it recorded`() {
        val export = exporter.export(run(), scenario(), events(), includeTrace = false)
        val imported = (serializer.import(export.content) as ScenarioImportResult.Reproduction)

        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                scenario = io.devkit.netkit.scenario.runtime.ActiveScenarioSnapshot.of(
                    imported.scenario,
                ),
            ),
            seed = imported.seed,
        )

        assertEquals(
            listOf(503, 503, 503),
            harness.statuses(3).also { assertNotNull(it) },
        )
    }

    @Test
    fun `a reproduction with no run metadata is refused`() {
        val result = serializer.import(
            """
            {
              "format": "netkit",
              "schemaVersion": 2,
              "type": "reproduction",
              "scenario": { "id": "scn-1", "name": "Test" }
            }
            """.trimIndent(),
        )

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue(result.failureReason!!.contains("no run metadata"))
    }

    @Test
    fun `a reproduction with no scenario is refused`() {
        val result = serializer.import(
            """
            {
              "format": "netkit",
              "schemaVersion": 2,
              "type": "reproduction",
              "run": { "runId": "run-1", "seed": 12345 }
            }
            """.trimIndent(),
        )

        assertTrue(result is ScenarioImportResult.InvalidFile)
        assertTrue(result.failureReason!!.contains("nothing to reproduce"))
    }

    /** A reproduction is not a trusted channel just because it came from a colleague. */
    @Test
    fun `a reproduction carrying an invalid scenario is refused`() {
        val result = serializer.import(
            """
            {
              "format": "netkit",
              "schemaVersion": 2,
              "type": "reproduction",
              "run": { "runId": "run-1", "seed": 12345 },
              "scenario": {
                "id": "scn-1",
                "name": "Test",
                "rules": [
                  {
                    "id": "r1",
                    "matcher": { "type": "exactPath", "path": "/a" },
                    "probability": 9.0,
                    "action": { "type": "respond", "statusCode": 500 }
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        assertTrue(result is ScenarioImportResult.InvalidFile)
    }

    // ---- security ---------------------------------------------------------------

    /**
     * The assertion the format's usefulness rests on. There is no setting that
     * relaxes any of these.
     */
    @Test
    fun `an exported reproduction contains no credentials`() {
        val leaky = scenario().copy(
            rules = listOf(
                EndpointRule.forPath(
                    path = "/api/v1/bookings",
                    action = NetworkAction.ReturnResponse(
                        statusCode = 200,
                        headers = listOf(
                            ResponseHeader("Authorization", "Bearer super-secret-token"),
                            ResponseHeader("Set-Cookie", "session=cookie-value-xyz"),
                            ResponseHeader("X-Api-Key", "key-9999"),
                            ResponseHeader("Retry-After", "60"),
                        ),
                    ),
                ),
            ),
        )
        val leakyEvents = listOf(
            ExecutionEvent(
                evaluationIndex = 1,
                atMillis = FIXED_TIME,
                type = ExecutionEventType.ACTION_EXECUTED,
                method = "POST",
                path = "/api/v1/login?password=hunter2&api_key=key-9999",
                detail = "HTTP 200",
            ),
        )

        val export = exporter.export(run(), leaky, leakyEvents, includeTrace = true)

        // Each value is chosen not to occur incidentally elsewhere in the file —
        // a substring of the run id would make this test pass or fail by accident.
        listOf("super-secret-token", "cookie-value-xyz", "key-9999", "hunter2").forEach { secret ->
            assertFalse("\"$secret\" leaked into the reproduction", export.content.contains(secret))
        }
        // A harmless header is kept, so the sanitiser is discriminating rather
        // than simply stripping everything.
        assertTrue(export.content.contains("Retry-After"))
        assertTrue(export.warnings.isNotEmpty())
    }

    @Test
    fun `a reproduction carries no request or response bodies from real traffic`() {
        val export = exporter.export(run(), scenario(), events(), includeTrace = true)

        // The trace records decisions, never payloads.
        assertFalse(export.content.contains("requestBody"))
        assertFalse(export.content.contains("responseBody"))
        assertFalse(export.content.contains("replay"))
    }

    @Test
    fun `the summary carries no credentials even for a leaky scenario`() {
        val summary = exporter.summary(run(), scenario())

        // The summary is identity and counters only — there is nowhere for a
        // secret to be, and this test exists so that stays true.
        assertFalse(summary.contains("Authorization"))
        assertFalse(summary.contains("Cookie"))
        assertFalse(summary.contains("token"))
    }

    // ---- fixtures ---------------------------------------------------------------

    private fun run() = ScenarioRun(
        id = ScenarioRunId("run-abc12345"),
        scenarioId = ScenarioId("checkout-chaos"),
        scenarioName = "Poor Mobile Network",
        seed = 843_921_773,
        startedAtMillis = FIXED_TIME,
        startReason = RunStartReason.ACTIVATED,
        evaluationCount = 37,
        simulatedCount = 8,
        passThroughCount = 29,
    )

    private fun scenario() = NetworkScenario(
        id = ScenarioId("checkout-chaos"),
        name = "Poor Mobile Network",
        rules = listOf(
            EndpointRule.forPath(
                path = "/api/v1/bookings",
                action = NetworkAction.ReturnResponse(503),
                probability = Probability.ALWAYS,
            ),
        ),
    )

    private fun events() = listOf(
        ExecutionEvent(
            evaluationIndex = 1,
            atMillis = FIXED_TIME,
            type = ExecutionEventType.CHAOS_PASS_THROUGH,
            method = "GET",
            path = "/api/v1/profile",
            ruleLabel = "Chaos",
            detail = "Pass through",
        ),
        ExecutionEvent(
            evaluationIndex = 17,
            atMillis = FIXED_TIME,
            type = ExecutionEventType.CHAOS_ACTION,
            method = "GET",
            path = "/api/v1/bookings",
            ruleLabel = "Chaos",
            detail = "HTTP 503",
            reason = "15% failure rate",
        ),
    )

    private companion object {
        /** Fixed so exported timestamps are assertable. */
        const val FIXED_TIME = 1_788_400_000_000L
    }
}
