package io.devkit.netkit

import io.devkit.netkit.engine.DecisionOrigin
import io.devkit.netkit.engine.ScenarioDecision
import io.devkit.netkit.engine.ScenarioEvaluator
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.RequestTarget
import io.devkit.netkit.scenario.SequenceStep
import io.devkit.netkit.scenario.model.ScenarioId
import io.devkit.netkit.scenario.runtime.DefaultScenarioExecutionState
import io.devkit.netkit.scenario.runtime.RuleSource
import io.devkit.netkit.scenario.runtime.ScenarioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario activation and the precedence between the two layers.
 *
 * The rules being protected here are the ones a QA engineer has to be able to
 * predict out loud: exactly one scenario is active, a temporary override beats
 * it, a scenario's own global beats the console's, and deleting the active
 * scenario cannot leave the runtime pointing at something that no longer exists.
 */
class ScenarioActivationTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val executionState = DefaultScenarioExecutionState()
    private val repository = Fixtures.repository()
    private val manager: ScenarioManager =
        Fixtures.manager(scope, repository, executionState = executionState)
    private val controller = Fixtures.controller(scope, manager = manager)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun target(path: String = "/api/v1/bookings") = RequestTarget(
        method = "GET",
        scheme = "https",
        host = "api.example.com",
        port = 443,
        path = path,
    )

    private fun decide(path: String = "/api/v1/bookings"): ScenarioDecision =
        ScenarioEvaluator.evaluate(controller.configuration, target(path), executionState)

    // ---- activation --------------------------------------------------------

    @Test
    fun `activating a scenario applies its rules`() = runTest {
        val saved = manager.save(
            Fixtures.scenario(
                "Bookings failure",
                Fixtures.rule(action = NetworkAction.ReturnResponse(503)),
            ),
        ).scenarioOrNull!!

        assertTrue(manager.activate(saved.id))

        val decision = decide() as ScenarioDecision.RespondWith
        assertEquals(503, decision.statusCode)
        assertEquals(saved.id, (decision.source as RuleSource.Scenario).scenarioId)
        assertEquals("Bookings failure", (decision.source as RuleSource.Scenario).scenarioName)
    }

    @Test
    fun `activating an unknown id changes nothing`() = runTest {
        assertFalse(manager.activate(ScenarioId("nope")))
        assertNull(manager.activeScenarioId.value)
        assertTrue(decide() is ScenarioDecision.PassThrough)
    }

    @Test
    fun `only one scenario is ever active`() = runTest {
        val first = manager.save(Fixtures.scenario("First", Fixtures.rule())).scenarioOrNull!!
        val second = manager.save(
            Fixtures.scenario(
                "Second",
                Fixtures.rule(action = NetworkAction.ReturnResponse(404)),
            ),
        ).scenarioOrNull!!

        manager.activate(first.id)
        manager.activate(second.id)

        assertEquals(second.id, manager.activeScenarioId.value)
        assertEquals(404, (decide() as ScenarioDecision.RespondWith).statusCode)
    }

    @Test
    fun `deactivating restores normal networking`() = runTest {
        val saved = manager.save(Fixtures.scenario("Failure", Fixtures.rule())).scenarioOrNull!!
        manager.activate(saved.id)

        manager.deactivate()

        assertNull(manager.activeScenarioId.value)
        assertTrue(decide() is ScenarioDecision.PassThrough)
    }

    @Test
    fun `toggleActive turns a scenario on and off`() = runTest {
        val saved = manager.save(Fixtures.scenario("Failure", Fixtures.rule())).scenarioOrNull!!

        assertTrue(manager.toggleActive(saved.id))
        assertEquals(saved.id, manager.activeScenarioId.value)

        assertFalse(manager.toggleActive(saved.id))
        assertNull(manager.activeScenarioId.value)
    }

    @Test
    fun `a paused scenario stays active but stops affecting traffic`() = runTest {
        val saved = manager.save(Fixtures.scenario("Failure", Fixtures.rule())).scenarioOrNull!!
        manager.activate(saved.id)

        manager.setScenarioEnabled(saved.id, false)

        assertEquals(saved.id, manager.activeScenarioId.value)
        assertTrue(decide() is ScenarioDecision.PassThrough)

        manager.setScenarioEnabled(saved.id, true)
        assertTrue(decide() is ScenarioDecision.RespondWith)
    }

    @Test
    fun `deleting the active scenario deactivates it safely`() = runTest {
        val saved = manager.save(Fixtures.scenario("Failure", Fixtures.rule())).scenarioOrNull!!
        manager.activate(saved.id)

        manager.delete(saved.id)

        assertNull(manager.activeScenarioId.value)
        assertNull(controller.configuration.scenario)
        assertTrue(decide() is ScenarioDecision.PassThrough)
    }

    @Test
    fun `editing the active scenario applies immediately`() = runTest {
        val saved = manager.save(
            Fixtures.scenario("Failure", Fixtures.rule(action = NetworkAction.ReturnResponse(500))),
        ).scenarioOrNull!!
        manager.activate(saved.id)
        assertEquals(500, (decide() as ScenarioDecision.RespondWith).statusCode)

        manager.save(
            saved.copy(rules = listOf(Fixtures.rule(action = NetworkAction.ReturnResponse(418)))),
        )

        // Live updating rather than requiring a reactivation: the alternative is
        // silently running a stale definition.
        assertEquals(418, (decide() as ScenarioDecision.RespondWith).statusCode)
    }

    // ---- precedence --------------------------------------------------------

    @Test
    fun `a temporary override beats the active scenario`() = runTest {
        val saved = manager.save(
            Fixtures.scenario("Failure", Fixtures.rule(action = NetworkAction.ReturnResponse(503))),
        ).scenarioOrNull!!
        manager.activate(saved.id)

        controller.addRule(Fixtures.rule(action = NetworkAction.ReturnResponse(418)))

        val decision = decide() as ScenarioDecision.RespondWith
        assertEquals(418, decision.statusCode)
        assertEquals(RuleSource.Temporary, decision.source)
    }

    @Test
    fun `a scenario global beats the console's own global`() = runTest {
        val saved = manager.save(
            Fixtures.scenario(
                "Slow",
                global = GlobalNetworkConfig(latencyMillis = 2_500),
            ),
        ).scenarioOrNull!!
        manager.activate(saved.id)
        controller.setOffline(true)

        val decision = decide()

        assertTrue(decision is ScenarioDecision.Delay)
        assertEquals(2_500L, (decision as ScenarioDecision.Delay).delayMillis)
        assertEquals(DecisionOrigin.GLOBAL, decision.origin)
    }

    @Test
    fun `a scenario that sets no global leaves the console in charge`() = runTest {
        val saved = manager.save(
            Fixtures.scenario("Rules only", Fixtures.rule(path = "/other"), global = null),
        ).scenarioOrNull!!
        manager.activate(saved.id)
        controller.setOffline(true)

        assertTrue(decide() is ScenarioDecision.FailOffline)
    }

    @Test
    fun `a scenario global pinned to normal overrides the console's offline switch`() = runTest {
        val saved = manager.save(
            Fixtures.scenario("Pinned normal", global = GlobalNetworkConfig(GlobalNetworkMode.Normal)),
        ).scenarioOrNull!!
        manager.activate(saved.id)
        controller.setOffline(true)

        assertTrue(decide() is ScenarioDecision.PassThrough)
    }

    @Test
    fun `the effective global names the layer in force`() = runTest {
        val saved = manager.save(
            Fixtures.scenario("Slow", global = GlobalNetworkConfig(latencyMillis = 2_500)),
        ).scenarioOrNull!!
        controller.setGlobalLatency(100)

        assertNull(controller.state.value.effectiveGlobal.scenarioName)

        manager.activate(saved.id)

        assertEquals("Slow", controller.state.value.effectiveGlobal.scenarioName)
        assertEquals(2_500L, controller.state.value.effectiveGlobal.config.latencyMillis)
        assertTrue(controller.state.value.effectiveGlobal.explanation.contains("\"Slow\""))
    }

    @Test
    fun `NetKit disabled ignores the active scenario entirely`() = runTest {
        val saved = manager.save(Fixtures.scenario("Failure", Fixtures.rule())).scenarioOrNull!!
        manager.activate(saved.id)
        controller.disable()

        assertTrue(decide() is ScenarioDecision.PassThrough)
        assertFalse(controller.state.value.isSimulating)
    }

    // ---- sequences and reset ----------------------------------------------

    @Test
    fun `reactivating a scenario restarts its sequences`() = runTest {
        val saved = manager.save(
            Fixtures.scenario(
                "Retry",
                Fixtures.rule(
                    action = NetworkAction.Sequence(
                        listOf(
                            SequenceStep(NetworkAction.ReturnResponse(500)),
                            SequenceStep(NetworkAction.ReturnResponse(200)),
                        ),
                    ),
                ),
            ),
        ).scenarioOrNull!!
        manager.activate(saved.id)

        assertEquals(500, (decide() as ScenarioDecision.RespondWith).statusCode)
        assertEquals(200, (decide() as ScenarioDecision.RespondWith).statusCode)

        manager.deactivate()
        manager.activate(saved.id)

        assertEquals(500, (decide() as ScenarioDecision.RespondWith).statusCode)
    }

    @Test
    fun `editing an active scenario's rules restarts its sequences`() = runTest {
        val rule = Fixtures.rule(
            action = NetworkAction.Sequence(
                listOf(
                    SequenceStep(NetworkAction.ReturnResponse(500)),
                    SequenceStep(NetworkAction.ReturnResponse(200)),
                ),
            ),
        )
        val saved = manager.save(Fixtures.scenario("Retry", rule)).scenarioOrNull!!
        manager.activate(saved.id)
        decide()

        manager.save(saved.copy(description = "unchanged rules"))
        // A metadata-only edit must not reset a running sequence.
        assertEquals(200, (decide() as ScenarioDecision.RespondWith).statusCode)

        manager.save(
            saved.copy(
                rules = listOf(
                    rule.copy(
                        action = NetworkAction.Sequence(
                            listOf(
                                SequenceStep(NetworkAction.ReturnResponse(418)),
                                SequenceStep(NetworkAction.ReturnResponse(200)),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(418, (decide() as ScenarioDecision.RespondWith).statusCode)
    }

    @Test
    fun `controller reset keeps the active scenario but clears temporary state`() = runTest {
        val saved = manager.save(Fixtures.scenario("Failure", Fixtures.rule())).scenarioOrNull!!
        manager.activate(saved.id)
        controller.addRule(Fixtures.rule(path = "/other", action = NetworkAction.Offline))
        controller.setGlobalLatency(5_000)

        controller.reset()

        assertEquals(saved.id, manager.activeScenarioId.value)
        assertTrue(controller.state.value.rules.isEmpty())
        assertEquals(0L, controller.state.value.global.latencyMillis)
        assertTrue(decide() is ScenarioDecision.RespondWith)
    }

    @Test
    fun `resetEverything deactivates the scenario but keeps it saved`() = runTest {
        val saved = manager.save(Fixtures.scenario("Failure", Fixtures.rule())).scenarioOrNull!!
        manager.activate(saved.id)

        controller.resetEverything()

        assertNull(manager.activeScenarioId.value)
        assertEquals(1, manager.scenarios.value.size)
        assertTrue(decide() is ScenarioDecision.PassThrough)
    }

    @Test
    fun `saving the current setup captures it and clears the temporary layer`() = runTest {
        controller.setGlobalLatency(2_500)
        controller.addRule(Fixtures.rule(action = NetworkAction.ReturnResponse(500)))

        val result = controller.scenarios.saveCurrentSetupAsScenario(
            name = "Captured",
            description = "From the console",
        )

        val scenario = result.scenarioOrNull!!
        assertEquals("Captured", scenario.name)
        assertEquals(2_500L, scenario.globalConfig?.latencyMillis)
        assertEquals(1, scenario.rules.size)
        // Cleared, so activating the new scenario does not double every override.
        assertTrue(controller.state.value.rules.isEmpty())
        assertEquals(0L, controller.state.value.global.latencyMillis)
    }

    @Test
    fun `a captured setup gets fresh rule ids`() = runTest {
        val temporary = Fixtures.rule(action = NetworkAction.ReturnResponse(500))
        controller.addRule(temporary)

        val scenario = controller.scenarios
            .saveCurrentSetupAsScenario(name = "Captured")
            .scenarioOrNull!!

        assertFalse(scenario.rules.single().id == temporary.id)
    }
}
