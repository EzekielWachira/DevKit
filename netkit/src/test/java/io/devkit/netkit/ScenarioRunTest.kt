package io.devkit.netkit

import io.devkit.netkit.engine.ScenarioDecision
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.Probability
import io.devkit.netkit.scenario.SequenceCompletionBehavior
import io.devkit.netkit.scenario.SequenceStep
import io.devkit.netkit.scenario.condition.PreviousResult
import io.devkit.netkit.scenario.condition.PreviousResultCondition
import io.devkit.netkit.scenario.chaos.ChaosPresets
import io.devkit.netkit.scenario.condition.RequestCountCondition
import io.devkit.netkit.scenario.persistence.ScenarioWriteResult
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import io.devkit.netkit.scenario.run.ExecutionEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Scenario runs: restarting, resetting, counting and tracing.
 *
 * The single most important test in the release is
 * [restarting on the same seed reproduces the decision sequence] — everything
 * else 0.3 added exists to make that one true.
 */
class ScenarioRunTest {

    // ---- restart -------------------------------------------------------------

    @Test
    fun `restarting on the same seed reproduces the decision sequence`() {
        val harness = EngineHarness(probabilistic(), seed = 843_921_773)

        val before = harness.statuses(40)
        harness.restart()
        val after = harness.statuses(40)

        assertEquals(before, after)
    }

    @Test
    fun `restarting on a new seed changes the decision sequence`() {
        val harness = EngineHarness(probabilistic(), seed = 1)

        val before = harness.statuses(40)
        harness.restartWith(2)
        val after = harness.statuses(40)

        assertNotEquals(before, after)
    }

    @Test
    fun `a restart keeps the seed and issues a new run id`() {
        val harness = EngineHarness(probabilistic(), seed = 500)
        val original = harness.run!!

        val restarted = harness.restart()!!

        assertEquals(original.seed, restarted.seed)
        assertNotEquals(original.id, restarted.id)
    }

    @Test
    fun `a restart resets rule hit counters`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(action = NetworkAction.ReturnResponse(500))
                        .copy(id = "first-only", conditions = listOf(RequestCountCondition.Exactly(1))),
                ),
            ),
        )

        assertEquals(listOf(500, null, null), harness.statuses(3))
        harness.restart()
        assertEquals(listOf(500, null, null), harness.statuses(3))
    }

    @Test
    fun `a restart resets response sequences`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(
                        action = NetworkAction.Sequence(
                            steps = listOf(
                                SequenceStep(NetworkAction.ReturnResponse(500)),
                                SequenceStep(NetworkAction.ReturnResponse(200)),
                            ),
                            completion = SequenceCompletionBehavior.REPEAT_LAST,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf(500, 200, 200), harness.statuses(3))
        harness.restart()
        assertEquals(listOf(500, 200), harness.statuses(2))
    }

    @Test
    fun `a restart clears previous-result state`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(path = "/api/v1/profile", action = NetworkAction.ReturnResponse(500))
                        .copy(id = "breaks"),
                    Fixtures.rule(action = NetworkAction.ReturnResponse(503)).copy(
                        id = "follows",
                        conditions = listOf(
                            PreviousResultCondition(PreviousResult.AFTER_SIMULATED_FAILURE),
                        ),
                    ),
                ),
            ),
        )

        harness.decide(path = "/api/v1/profile")
        assertTrue(harness.decide() is ScenarioDecision.RespondWith)

        harness.restart()

        assertTrue(
            "the earlier failure should be forgotten",
            harness.decide() is ScenarioDecision.PassThrough,
        )
    }

    @Test
    fun `a restart clears statistics and the timeline`() {
        val harness = EngineHarness(probabilistic(), seed = 42)
        harness.statuses(10)

        assertTrue(harness.timeline.isNotEmpty())
        harness.restart()

        assertEquals(0, harness.run!!.evaluationCount)
        assertTrue(harness.runManager.ruleStatistics.value.isEmpty())
        // Only the "run restarted" event survives.
        assertEquals(1, harness.timeline.size)
        assertEquals(ExecutionEventType.RUN_RESTARTED, harness.timeline.single().type)
    }

    @Test
    fun `resetting runtime state keeps the run identity`() {
        val harness = EngineHarness(probabilistic(), seed = 42)
        val original = harness.run!!
        harness.statuses(10)

        harness.runManager.resetRuntimeState()

        val current = harness.run!!
        assertEquals(original.id, current.id)
        assertEquals(original.startedAtMillis, current.startedAtMillis)
        assertEquals(0, current.evaluationCount)
    }

    // ---- counters ------------------------------------------------------------

    @Test
    fun `the run counts simulated and pass-through requests separately`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(action = NetworkAction.ReturnResponse(500))
                        .copy(id = "first-two", conditions = listOf(RequestCountCondition.Range(1, 2))),
                ),
            ),
        )

        harness.statuses(5)

        val run = harness.run!!
        assertEquals(5, run.evaluationCount)
        assertEquals(2, run.simulatedCount)
        assertEquals(3, run.passThroughCount)
        assertEquals(40, run.simulatedPercent)
    }

    @Test
    fun `the run averages only the requests it actually delayed`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(action = NetworkAction.Delay(2_000))
                        .copy(id = "slow", conditions = listOf(RequestCountCondition.Exactly(1))),
                ),
            ),
        )

        harness.statuses(4)

        val run = harness.run!!
        assertEquals(1, run.latencyInjectedCount)
        assertEquals(2_000, run.averageInjectedLatencyMillis)
        assertEquals("2.0s", run.averageLatencyLabel)
    }

    @Test
    fun `an idle configuration claims no evaluation index`() {
        val harness = EngineHarness(ActiveNetworkConfiguration())

        repeat(20) { harness.decide() }

        assertEquals(0, harness.run!!.evaluationCount)
    }

    // ---- statistics ----------------------------------------------------------

    @Test
    fun `rule statistics narrow through the funnel`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(action = NetworkAction.ReturnResponse(500)).copy(
                        id = "narrow",
                        conditions = listOf(RequestCountCondition.AtLeast(3)),
                        probability = Probability.ALWAYS,
                    ),
                ),
            ),
        )

        // Two requests to an unrelated path, then five to the rule's own.
        repeat(2) { harness.decide(path = "/api/v1/profile") }
        harness.statuses(5)

        val stats = harness.statisticsFor("narrow")!!
        assertEquals(7, stats.evaluated)
        assertEquals(5, stats.matched)
        assertEquals(3, stats.conditionPassed)
        assertEquals(3, stats.probabilityPassed)
        assertEquals(3, stats.executed)
        assertNull("a firing rule needs no diagnosis", stats.diagnosis)
    }

    @Test
    fun `a rule that never matched says so`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(path = "/api/v1/nowhere", action = NetworkAction.Offline)
                        .copy(id = "unmatched"),
                    Fixtures.rule(action = NetworkAction.Delay(1)).copy(id = "matches"),
                ),
            ),
        )

        harness.statuses(3)

        assertTrue(harness.statisticsFor("unmatched")!!.diagnosis!!.contains("Matched no request"))
    }

    @Test
    fun `a rule whose probability never came up says so`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(action = NetworkAction.ReturnResponse(500))
                        .copy(id = "never", probability = Probability.NEVER),
                ),
            ),
        )

        harness.statuses(5)

        val stats = harness.statisticsFor("never")!!
        assertEquals(5, stats.conditionPassed)
        assertEquals(0, stats.probabilityPassed)
        assertTrue(stats.diagnosis!!.contains("probability never came up"))
    }

    // ---- timeline ------------------------------------------------------------

    @Test
    fun `the timeline records a decision per request`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(Fixtures.rule(action = NetworkAction.ReturnResponse(500))),
            ),
        )

        harness.statuses(3)

        val executed = harness.timeline.filter { it.type == ExecutionEventType.ACTION_EXECUTED }
        assertEquals(3, executed.size)
        assertEquals(listOf(1L, 2L, 3L), executed.map { it.evaluationIndex })
        assertTrue(executed.all { it.detail == "HTTP 500" })
    }

    @Test
    fun `the timeline distinguishes a condition failure from a probability failure`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(
                    Fixtures.rule(action = NetworkAction.ReturnResponse(500)).copy(
                        id = "conditional",
                        conditions = listOf(RequestCountCondition.Exactly(99)),
                    ),
                    Fixtures.rule(action = NetworkAction.ReturnResponse(503))
                        .copy(id = "unlucky", probability = Probability.NEVER),
                ),
            ),
        )

        harness.decide()

        val types = harness.timeline.map { it.type }
        assertTrue(types.contains(ExecutionEventType.RULE_SKIPPED_CONDITION))
        assertTrue(types.contains(ExecutionEventType.RULE_SKIPPED_PROBABILITY))
    }

    @Test
    fun `the timeline is bounded`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(Fixtures.rule(action = NetworkAction.ReturnResponse(500))),
            ),
        )
        harness.runManager.timeline.let { timeline ->
            repeat(2_000) { harness.decide() }
            assertTrue(
                "timeline grew past its cap: ${timeline.snapshot().size}",
                timeline.snapshot().size <= io.devkit.netkit.config.NetKitLimits.MAX_EXECUTION_EVENTS,
            )
        }
    }

    @Test
    fun `a disabled timeline records nothing but still decides`() {
        val harness = EngineHarness(
            ActiveNetworkConfiguration(
                rules = listOf(Fixtures.rule(action = NetworkAction.ReturnResponse(500))),
            ),
            recordTimeline = false,
        )

        assertEquals(listOf(500, 500), harness.statuses(2))
        assertTrue(harness.timeline.isEmpty())
    }

    // ---- run identity --------------------------------------------------------

    @Test
    fun `stopping a run clears it`() {
        val harness = EngineHarness(probabilistic())
        harness.statuses(3)

        harness.runManager.stopRun()

        assertNull(harness.run)
        assertFalse(harness.runManager.isRunning)
    }

    @Test
    fun `restarting when nothing is running does nothing`() {
        val harness = EngineHarness(probabilistic())
        harness.runManager.stopRun()

        assertNull(harness.restart())
        assertNull(harness.restartWith(1))
        assertNull(harness.runManager.restartWithNewSeed())
    }

    /**
     * Chaos and a scenario both want a run, and only one can have it. The
     * scenario wins while it is active; when it goes away the run has to pass
     * back to chaos rather than simply stopping, or chaos would keep failing
     * requests with no seed anybody could quote.
     */
    @Test
    fun `a run passes back to chaos when a scenario is deactivated`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val manager = Fixtures.manager(scope)
            val controller = Fixtures.controller(scope, manager = manager)
            controller.setChaos(ChaosPresets.poorMobileNetwork.config)

            val chaosRun = manager.runManager.run.value
            assertNotNull("chaos should own a run", chaosRun)
            assertNull(chaosRun!!.scenarioId)

            val saved = (manager.save(Fixtures.scenario("Scoped")) as ScenarioWriteResult.Success)
                .scenario
            manager.activate(saved.id)
            assertEquals(
                "the scenario should own the run while active",
                saved.id,
                manager.runManager.run.value?.scenarioId,
            )

            manager.deactivate()
            val afterRun = manager.runManager.run.value
            assertNotNull("chaos should own the run again", afterRun)
            assertNull(afterRun!!.scenarioId)
        } finally {
            scope.cancel()
        }
    }

    private fun probabilistic() = ActiveNetworkConfiguration(
        rules = listOf(
            Fixtures.rule(action = NetworkAction.ReturnResponse(500))
                .copy(id = "probabilistic", probability = Probability(0.3)),
        ),
    )
}
