package io.devkit.netkit

import io.devkit.netkit.engine.DecisionOrigin
import io.devkit.netkit.engine.ScenarioDecision
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.LatencyRange
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.Probability
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.scenario.WeightedOutcome
import io.devkit.netkit.scenario.chaos.ChaosConfig
import io.devkit.netkit.scenario.chaos.ChaosExclusions
import io.devkit.netkit.scenario.chaos.ChaosPresets
import io.devkit.netkit.scenario.chaos.ChaosScope
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chaos mode, deterministically.
 *
 * Every test pins a seed, so an assertion like "exactly these requests failed" is
 * meaningful and stable. No test sleeps: latency is asserted on the *decision*,
 * which carries the chosen delay, rather than by timing the interceptor.
 */
class ChaosTest {

    @Test
    fun `disabled chaos changes nothing`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(chaos = aggressive().copy(enabled = false)),
        )

        repeat(50) { assertTrue(harness.decide() is ScenarioDecision.PassThrough) }
    }

    @Test
    fun `chaos failures reproduce exactly on the same seed`() {
        val configuration = ActiveNetworkConfiguration(chaos = aggressive())

        val first = EngineHarness(configuration, seed = 883_201).kinds(60)
        val second = EngineHarness(configuration, seed = 883_201).kinds(60)

        assertEquals(first, second)
    }

    @Test
    fun `a different seed changes which requests fail`() {
        val configuration = ActiveNetworkConfiguration(chaos = aggressive())

        assertNotEquals(
            EngineHarness(configuration, seed = 1).kinds(60),
            EngineHarness(configuration, seed = 2).kinds(60),
        )
    }

    @Test
    fun `every configured chaos outcome is reachable`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(chaos = aggressive()),
            seed = 4_242_424,
        )
        val decisions = List(400) { harness.decide() }

        assertTrue(decisions.any { it is ScenarioDecision.RespondWith && it.statusCode == 500 })
        assertTrue(decisions.any { it is ScenarioDecision.RespondWith && it.statusCode == 503 })
        assertTrue(decisions.any { it is ScenarioDecision.FailTimeout })
        assertTrue(decisions.any { it is ScenarioDecision.FailDisconnect })
    }

    @Test
    fun `chaos attributes its decisions to itself`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                chaos = aggressive().copy(failureProbability = Probability.ALWAYS),
            ),
        )

        val decision = harness.decide()
        assertEquals(DecisionOrigin.CHAOS, decision.origin)
        assertEquals("Chaos", decision.scenarioLabel)
    }

    // ---- scope ---------------------------------------------------------------

    @Test
    fun `chaos outside its host scope does nothing`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                chaos = alwaysFails().copy(
                    scope = ChaosScope(hosts = listOf("api.staging.example.com")),
                ),
            ),
        )

        assertTrue(harness.decide(host = "api.example.com") is ScenarioDecision.PassThrough)
        assertTrue(
            harness.decide(host = "api.staging.example.com") is ScenarioDecision.RespondWith,
        )
    }

    @Test
    fun `chaos outside its path scope does nothing`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                chaos = alwaysFails().copy(scope = ChaosScope(pathPrefixes = listOf("/api/v1"))),
            ),
        )

        assertTrue(harness.decide(path = "/health") is ScenarioDecision.PassThrough)
        assertTrue(harness.decide(path = "/api/v1/bookings") is ScenarioDecision.RespondWith)
    }

    /** `/api/v1` must not claim `/api/v10`, or a scenario blames the wrong endpoint. */
    @Test
    fun `a path prefix respects segment boundaries`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                chaos = alwaysFails().copy(scope = ChaosScope(pathPrefixes = listOf("/api/v1"))),
            ),
        )

        assertTrue(harness.decide(path = "/api/v10/bookings") is ScenarioDecision.PassThrough)
        assertTrue(harness.decide(path = "/api/v1") is ScenarioDecision.RespondWith)
    }

    @Test
    fun `chaos outside its method scope does nothing`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                chaos = alwaysFails().copy(
                    scope = ChaosScope(methods = listOf(HttpMethod.POST)),
                ),
            ),
        )

        assertTrue(harness.decide(method = "GET") is ScenarioDecision.PassThrough)
        assertTrue(harness.decide(method = "POST") is ScenarioDecision.RespondWith)
    }

    /** A trailing glob star is how people write a prefix, and must normalise away. */
    @Test
    fun `a globbed path prefix means the same as a plain one`() {
        val globbed = ChaosScope(pathPrefixes = listOf("/api/v1/*"))
        val plain = ChaosScope(pathPrefixes = listOf("/api/v1"))

        assertEquals(plain.label, globbed.label)
    }

    // ---- exclusions ----------------------------------------------------------

    /**
     * The control that keeps chaos from breaking the machinery you need in order
     * to observe chaos.
     */
    @Test
    fun `an excluded path is spared even inside the scope`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                chaos = alwaysFails().copy(
                    scope = ChaosScope(pathPrefixes = listOf("/api/v1")),
                    exclusions = ChaosExclusions(listOf("/api/v1/auth/refresh")),
                ),
            ),
        )

        assertTrue(
            harness.decide(path = "/api/v1/auth/refresh") is ScenarioDecision.PassThrough,
        )
        assertTrue(harness.decide(path = "/api/v1/bookings") is ScenarioDecision.RespondWith)
    }

    @Test
    fun `exclusions are counted separately from in-scope requests`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                chaos = alwaysFails().copy(
                    exclusions = ChaosExclusions(listOf("/api/v1/auth")),
                ),
            ),
        )

        harness.decide(path = "/api/v1/auth/refresh")
        harness.decide(path = "/api/v1/bookings")

        val stats = harness.runManager.chaosStatistics.value
        assertEquals(2, stats.evaluated)
        assertEquals(1, stats.excluded)
        assertEquals(1, stats.inScope)
        assertEquals(1, stats.failed)
    }

    // ---- latency -------------------------------------------------------------

    @Test
    fun `chaos latency applies to requests that do not fail`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                chaos = ChaosConfig(
                    enabled = true,
                    failureProbability = Probability.NEVER,
                    latency = LatencyRange(500, 3_000),
                ),
            ),
            seed = 12_345,
        )

        val delays = List(30) { (harness.decide() as ScenarioDecision.Delay).delayMillis }

        assertTrue(delays.all { it in 500..3_000 })
        assertTrue("a range should vary", delays.distinct().size > 1)
    }

    @Test
    fun `chaos latency reproduces on the same seed`() {
        val configuration = ActiveNetworkConfiguration(
            chaos = ChaosConfig(
                enabled = true,
                failureProbability = Probability.NEVER,
                latency = LatencyRange(500, 3_000),
            ),
        )

        assertEquals(
            EngineHarness(configuration, seed = 55).delays(20),
            EngineHarness(configuration, seed = 55).delays(20),
        )
    }

    @Test
    fun `chaos with neither failures nor latency is idle`() {
        assertTrue(
            ChaosConfig(enabled = true, failureProbability = Probability.NEVER).isIdle,
        )
        assertTrue(ChaosConfig(enabled = false).isIdle)
        assertFalse(aggressive().isIdle)
    }

    // ---- precedence ----------------------------------------------------------

    /**
     * A rule is a deliberate statement about one endpoint; chaos is weather. If
     * chaos could override a rule, the endpoint you pinned down would be the one
     * you could not.
     */
    @Test
    fun `an endpoint rule beats chaos`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(Fixtures.rule(action = NetworkAction.ReturnResponse(402))),
                chaos = alwaysFails(),
            ),
        )

        repeat(20) {
            assertEquals(402, (harness.decide() as ScenarioDecision.RespondWith).statusCode)
        }
    }

    @Test
    fun `chaos beats the global configuration`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                global = io.devkit.netkit.scenario.GlobalNetworkConfig(latencyMillis = 100),
                chaos = alwaysFails(),
            ),
        )

        assertEquals(DecisionOrigin.CHAOS, harness.decide().origin)
    }

    @Test
    fun `a request chaos passes over still reaches the global layer`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                global = io.devkit.netkit.scenario.GlobalNetworkConfig(latencyMillis = 100),
                chaos = alwaysFails().copy(scope = ChaosScope(pathPrefixes = listOf("/other"))),
            ),
        )

        val decision = harness.decide()
        assertEquals(DecisionOrigin.GLOBAL, decision.origin)
        assertEquals(100L, (decision as ScenarioDecision.Delay).delayMillis)
    }

    // ---- validation ----------------------------------------------------------

    @Test
    fun `chaos failures cannot include pass-through`() {
        try {
            ChaosConfig(
                enabled = true,
                failures = listOf(WeightedOutcome(1, NetworkAction.PassThrough)),
            )
            error("Expected pass-through to be rejected as a chaos failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("pass-through"))
        }
    }

    @Test
    fun `every shipped preset is usable and enabled`() {
        ChaosPresets.all.forEach { preset ->
            assertFalse("${preset.name} should do something", preset.config.isIdle)
            assertTrue("${preset.name} should be enabled", preset.config.enabled)
            assertTrue("${preset.name} needs failures", preset.config.failures.isNotEmpty())
        }
    }

    @Test
    fun `presets are ordered from mild to severe`() {
        val rates = ChaosPresets.all.map { it.config.failureProbability.value }
        assertEquals(rates.sorted(), rates)
    }

    private fun aggressive() = ChaosConfig(
        enabled = true,
        failureProbability = Probability(0.25),
        latency = LatencyRange.NONE,
        failures = listOf(
            WeightedOutcome(3, NetworkAction.ReturnResponse(500)),
            WeightedOutcome(3, NetworkAction.ReturnResponse(503)),
            WeightedOutcome(2, NetworkAction.Timeout(TimeoutType.READ)),
            WeightedOutcome(2, NetworkAction.Disconnect),
        ),
    )

    private fun alwaysFails() = aggressive().copy(
        failureProbability = Probability.ALWAYS,
        failures = listOf(WeightedOutcome(1, NetworkAction.ReturnResponse(503))),
    )

    private fun EngineHarness.kinds(count: Int): List<String> =
        List(count) { decide()::class.simpleName!! }
}
