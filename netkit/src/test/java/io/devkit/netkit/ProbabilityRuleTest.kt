package io.devkit.netkit

import io.devkit.netkit.engine.ScenarioDecision
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.LatencyRange
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.Probability
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.scenario.WeightedOutcome
import io.devkit.netkit.scenario.percentages
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probability, weighted outcomes and random latency, through the real engine.
 *
 * These go through [EngineHarness] rather than calling the evaluator directly,
 * because the interesting properties — that a seed reproduces a run, that a
 * failed gate falls through to the next rule — are properties of the whole
 * pipeline and not of any one stage.
 */
class ProbabilityRuleTest {

    @Test
    fun `a zero-probability rule never fires`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(
                        action = NetworkAction.ReturnResponse(500),
                    ).copy(probability = Probability.NEVER),
                ),
            ),
        )

        repeat(50) {
            assertTrue(harness.decide() is ScenarioDecision.PassThrough)
        }
    }

    @Test
    fun `a full-probability rule always fires`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(Fixtures.rule(action = NetworkAction.ReturnResponse(500))),
            ),
        )

        repeat(50) {
            assertEquals(500, (harness.decide() as ScenarioDecision.RespondWith).statusCode)
        }
    }

    @Test
    fun `an intermediate probability reproduces exactly on the same seed`() {
        val configuration = ActiveNetworkConfiguration(
            rules = listOf(
                Fixtures.rule(action = NetworkAction.ReturnResponse(500))
                    .copy(id = "probabilistic", probability = Probability(0.3)),
            ),
        )

        val first = EngineHarness(configuration, seed = 892_113).statuses(40)
        val second = EngineHarness(configuration, seed = 892_113).statuses(40)

        assertEquals(first, second)
        assertTrue("the rule should sometimes fire", first.any { it == 500 })
        assertTrue("the rule should sometimes decline", first.any { it == null })
    }

    @Test
    fun `a different seed produces a different decision sequence`() {
        val configuration = ActiveNetworkConfiguration(
            rules = listOf(
                Fixtures.rule(action = NetworkAction.ReturnResponse(500))
                    .copy(id = "probabilistic", probability = Probability(0.3)),
            ),
        )

        assertNotEquals(
            EngineHarness(configuration, seed = 1).statuses(40),
            EngineHarness(configuration, seed = 2).statuses(40),
        )
    }

    /**
     * The layering property. Without it a 30% failure rule would leave 70% of
     * requests untouched instead of slow, and stacking rules would be pointless.
     */
    @Test
    fun `a failed probability falls through to the next rule`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(action = NetworkAction.ReturnResponse(500))
                        .copy(id = "sometimes-fails", probability = Probability(0.3)),
                    Fixtures.rule(action = NetworkAction.Delay(2_000)).copy(id = "otherwise-slow"),
                ),
            ),
            seed = 555,
        )

        val decisions = List(40) { harness.decide() }

        assertTrue(decisions.any { it is ScenarioDecision.RespondWith })
        assertTrue(decisions.any { it is ScenarioDecision.Delay })
        // Nothing reached the real backend untouched: every request was claimed
        // by one rule or the other.
        assertTrue(decisions.none { it is ScenarioDecision.PassThrough })
    }

    // ---- weighted outcomes ---------------------------------------------------

    @Test
    fun `weighted outcome selection is deterministic`() {
        val configuration = weightedConfiguration()

        assertEquals(
            EngineHarness(configuration, seed = 4242).labels(60),
            EngineHarness(configuration, seed = 4242).labels(60),
        )
    }

    @Test
    fun `every weighted outcome kind is reachable`() {
        val harness = EngineHarness(weightedConfiguration(), seed = 20_260_903)
        val decisions = List(300) { harness.decide(method = "POST", path = "/api/v1/checkout") }

        assertTrue(
            "pass-through is a legitimate outcome",
            decisions.any { it is ScenarioDecision.PassThrough },
        )
        assertTrue(decisions.any { it is ScenarioDecision.RespondWith && it.statusCode == 500 })
        assertTrue(decisions.any { it is ScenarioDecision.RespondWith && it.statusCode == 503 })
        assertTrue(decisions.any { it is ScenarioDecision.FailTimeout })
        assertTrue(decisions.any { it is ScenarioDecision.FailDisconnect })
    }

    @Test
    fun `a single outcome always wins`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(
                        action = NetworkAction.Weighted(
                            listOf(WeightedOutcome(7, NetworkAction.ReturnResponse(418))),
                        ),
                    ),
                ),
            ),
        )

        repeat(30) {
            assertEquals(418, (harness.decide() as ScenarioDecision.RespondWith).statusCode)
        }
    }

    @Test
    fun `a zero or negative weight is rejected`() {
        listOf(0, -3).forEach { weight ->
            try {
                WeightedOutcome(weight, NetworkAction.Offline)
                error("Expected weight $weight to be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("positive"))
            }
        }
    }

    @Test
    fun `an empty weighted action is rejected`() {
        try {
            NetworkAction.Weighted(emptyList())
            error("Expected an empty weighted action to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("at least one outcome"))
        }
    }

    @Test
    fun `weighted outcomes cannot nest`() {
        try {
            WeightedOutcome(
                1,
                NetworkAction.Weighted(listOf(WeightedOutcome(1, NetworkAction.Offline))),
            )
            error("Expected nesting to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("nest"))
        }
    }

    @Test
    fun `weights are normalised into percentages`() {
        val outcomes = listOf(
            WeightedOutcome(3, NetworkAction.Offline),
            WeightedOutcome(1, NetworkAction.Disconnect),
        )
        assertEquals(listOf(75, 25), outcomes.percentages())
    }

    // ---- random latency ------------------------------------------------------

    @Test
    fun `a random delay stays in range and reproduces`() {
        val configuration = ActiveNetworkConfiguration(
            rules = listOf(
                Fixtures.rule(action = NetworkAction.RandomDelay(LatencyRange(500, 3_000))),
            ),
        )

        val first = EngineHarness(configuration, seed = 77).delays(30)
        val second = EngineHarness(configuration, seed = 77).delays(30)

        assertEquals(first, second)
        assertTrue("every request should have been delayed", first.all { it != null })
        assertTrue(first.filterNotNull().all { it in 500L..3_000L })
        assertTrue("a range should vary", first.distinct().size > 1)
    }

    @Test
    fun `a fixed random delay behaves like a plain delay`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(action = NetworkAction.RandomDelay(LatencyRange.fixed(1_200))),
                ),
            ),
        )

        repeat(10) {
            assertEquals(1_200L, (harness.decide() as ScenarioDecision.Delay).delayMillis)
        }
    }

    @Test
    fun `a zero-length random delay becomes a pass through`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(action = NetworkAction.RandomDelay(LatencyRange.NONE)),
                ),
            ),
        )

        assertTrue(harness.decide() is ScenarioDecision.PassThrough)
    }

    @Test
    fun `a disconnect is distinct from offline`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(Fixtures.rule(action = NetworkAction.Disconnect)),
            ),
        )

        val decision = harness.decide()
        assertTrue(decision is ScenarioDecision.FailDisconnect)
        assertTrue(decision.isSimulated)
    }

    private fun weightedConfiguration() = ActiveNetworkConfiguration(
        rules = listOf(
            Fixtures.rule(
                method = HttpMethod.POST,
                path = "/api/v1/checkout",
                action = NetworkAction.Weighted(
                    listOf(
                        WeightedOutcome(60, NetworkAction.PassThrough),
                        WeightedOutcome(15, NetworkAction.ReturnResponse(500)),
                        WeightedOutcome(10, NetworkAction.ReturnResponse(503)),
                        WeightedOutcome(10, NetworkAction.Timeout(TimeoutType.READ)),
                        WeightedOutcome(5, NetworkAction.Disconnect),
                    ),
                ),
            ).copy(id = "checkout-weighted"),
        ),
    )

    private fun EngineHarness.labels(count: Int): List<String> =
        List(count) { decide(method = "POST", path = "/api/v1/checkout")::class.simpleName!! }
}
