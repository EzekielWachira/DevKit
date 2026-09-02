package io.devkit.netkit

import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.engine.DecisionOrigin
import io.devkit.netkit.engine.ScenarioDecision
import io.devkit.netkit.engine.ScenarioEvaluator
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.RequestTarget
import io.devkit.netkit.scenario.SequenceCompletionBehavior
import io.devkit.netkit.scenario.SequenceStep
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import io.devkit.netkit.scenario.runtime.DefaultScenarioExecutionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Response sequences: the same endpoint behaving differently on each request.
 *
 * The invariants worth protecting are that a sequence advances exactly once per
 * request, that concurrent requests never collide on the same step, and that
 * every completion behaviour is honoured after the last step. Nothing here
 * sleeps — sequences are counter arithmetic, and a test that needed a delay
 * would be testing the wrong thing.
 */
class ResponseSequenceTest {

    private val executionState = DefaultScenarioExecutionState()

    private fun target(path: String = "/api/v1/bookings") = RequestTarget(
        method = "GET",
        scheme = "https",
        host = "api.example.com",
        port = 443,
        path = path,
    )

    private fun sequenceRule(
        vararg actions: NetworkAction,
        completion: SequenceCompletionBehavior = SequenceCompletionBehavior.REPEAT_LAST,
        path: String = "/api/v1/bookings",
    ) = EndpointRule.forPath(
        path = path,
        method = HttpMethod.ANY,
        action = NetworkAction.Sequence(actions.map(::SequenceStep), completion),
    )

    private fun configuration(rule: EndpointRule) =
        ActiveNetworkConfiguration(rules = listOf(rule))

    private fun evaluate(configuration: ActiveNetworkConfiguration, path: String = "/api/v1/bookings") =
        ScenarioEvaluator.evaluate(configuration, target(path), executionState)

    private fun statuses(configuration: ActiveNetworkConfiguration, count: Int): List<Int?> =
        List(count) { (evaluate(configuration) as? ScenarioDecision.RespondWith)?.statusCode }

    @Test
    fun `500 500 200 serves each step in order`() {
        val configuration = configuration(
            sequenceRule(
                NetworkAction.ReturnResponse(500),
                NetworkAction.ReturnResponse(500),
                NetworkAction.ReturnResponse(200),
            ),
        )

        assertEquals(listOf(500, 500, 200), statuses(configuration, 3))
    }

    @Test
    fun `repeat-last keeps serving the final step forever`() {
        val configuration = configuration(
            sequenceRule(
                NetworkAction.ReturnResponse(500),
                NetworkAction.ReturnResponse(200),
                completion = SequenceCompletionBehavior.REPEAT_LAST,
            ),
        )

        assertEquals(listOf(500, 200, 200, 200, 200), statuses(configuration, 5))
    }

    @Test
    fun `pass-through releases the endpoint once the sequence is done`() {
        val configuration = configuration(
            sequenceRule(
                NetworkAction.ReturnResponse(500),
                NetworkAction.ReturnResponse(500),
                completion = SequenceCompletionBehavior.PASS_THROUGH,
            ),
        )

        assertEquals(500, (evaluate(configuration) as ScenarioDecision.RespondWith).statusCode)
        assertEquals(500, (evaluate(configuration) as ScenarioDecision.RespondWith).statusCode)

        val afterwards = evaluate(configuration)
        assertTrue(afterwards is ScenarioDecision.PassThrough)
        // The rule is still credited, so history explains why the request went
        // to the real backend after two simulated failures.
        assertEquals(DecisionOrigin.ENDPOINT_RULE, afterwards.origin)
        assertNotNull(afterwards.ruleId)
    }

    @Test
    fun `loop starts again from the first step`() {
        val configuration = configuration(
            sequenceRule(
                NetworkAction.ReturnResponse(500),
                NetworkAction.ReturnResponse(502),
                NetworkAction.ReturnResponse(200),
                completion = SequenceCompletionBehavior.LOOP,
            ),
        )

        assertEquals(listOf(500, 502, 200, 500, 502, 200, 500), statuses(configuration, 7))
    }

    @Test
    fun `a sequence can mix response kinds`() {
        val configuration = configuration(
            sequenceRule(
                NetworkAction.Timeout(TimeoutType.READ),
                NetworkAction.Offline,
                NetworkAction.ReturnResponse(200, """{"ok":true}"""),
            ),
        )

        assertTrue(evaluate(configuration) is ScenarioDecision.FailTimeout)
        assertTrue(evaluate(configuration) is ScenarioDecision.FailOffline)
        val success = evaluate(configuration) as ScenarioDecision.RespondWith
        assertEquals(200, success.statusCode)
        assertEquals("""{"ok":true}""", success.body)
    }

    @Test
    fun `each decision names the step it ran`() {
        val configuration = configuration(
            sequenceRule(
                NetworkAction.ReturnResponse(500),
                NetworkAction.ReturnResponse(200),
            ),
        )

        val first = evaluate(configuration)
        val second = evaluate(configuration)

        assertEquals(1, first.sequence?.step)
        assertEquals(2, first.sequence?.stepCount)
        assertEquals("1 / 2", first.sequence?.display)
        assertEquals(2, second.sequence?.step)
        // The label is the rule's identity; the step is structured data beside
        // it, so history can render each exactly once.
        assertEquals(second.scenarioLabel, first.scenarioLabel)
    }

    @Test
    fun `a non-sequence rule reports no step`() {
        val configuration = configuration(
            EndpointRule.forPath("/api/v1/bookings", action = NetworkAction.ReturnResponse(500)),
        )

        assertNull(evaluate(configuration).sequence)
    }

    @Test
    fun `resetting one rule restarts only that sequence`() {
        val first = sequenceRule(
            NetworkAction.ReturnResponse(500),
            NetworkAction.ReturnResponse(200),
            path = "/a",
        )
        val second = sequenceRule(
            NetworkAction.ReturnResponse(503),
            NetworkAction.ReturnResponse(201),
            path = "/b",
        )
        val configuration = ActiveNetworkConfiguration(rules = listOf(first, second))

        evaluate(configuration, "/a")
        evaluate(configuration, "/b")
        executionState.reset(first.id)

        assertEquals(500, (evaluate(configuration, "/a") as ScenarioDecision.RespondWith).statusCode)
        assertEquals(201, (evaluate(configuration, "/b") as ScenarioDecision.RespondWith).statusCode)
    }

    @Test
    fun `resetAll restarts every sequence`() {
        val configuration = configuration(
            sequenceRule(
                NetworkAction.ReturnResponse(500),
                NetworkAction.ReturnResponse(200),
            ),
        )

        evaluate(configuration)
        evaluate(configuration)
        executionState.resetAll()

        assertEquals(500, (evaluate(configuration) as ScenarioDecision.RespondWith).statusCode)
    }

    @Test
    fun `a disabled sequence rule never advances`() {
        val rule = EndpointRule.forPath(
            path = "/api/v1/bookings",
            action = NetworkAction.Sequence(
                listOf(SequenceStep(NetworkAction.ReturnResponse(500))),
            ),
            enabled = false,
        )
        val configuration = ActiveNetworkConfiguration(rules = listOf(rule))

        assertTrue(evaluate(configuration) is ScenarioDecision.PassThrough)
        assertEquals(0, executionState.peek(rule.id, 1).completed)
    }

    @Test
    fun `progress is published for the UI`() {
        val rule = sequenceRule(
            NetworkAction.ReturnResponse(500),
            NetworkAction.ReturnResponse(500),
            NetworkAction.ReturnResponse(200),
        )
        val configuration = configuration(rule)

        evaluate(configuration)
        evaluate(configuration)

        val progress = executionState.progress.value.getValue(rule.id)
        assertEquals("2 / 3", progress.display)
        assertEquals(3, progress.nextStepNumber)
        assertEquals(false, progress.isComplete)
    }

    /**
     * Two requests to the same sequenced endpoint at the same moment must get
     * two different steps. A `var index++` would fail this test roughly always.
     */
    @Test
    fun `concurrent requests each claim a distinct step`() {
        val steps = NetKitLimits.MAX_SEQUENCE_STEPS
        val rule = sequenceRule(
            *Array(steps) { NetworkAction.ReturnResponse(200 + it) },
            completion = SequenceCompletionBehavior.PASS_THROUGH,
        )
        val configuration = configuration(rule)

        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(steps)
        val seen = ConcurrentHashMap.newKeySet<Int>()

        try {
            repeat(steps) {
                pool.submit {
                    start.await()
                    val decision = evaluate(configuration)
                    (decision as? ScenarioDecision.RespondWith)?.let { seen.add(it.statusCode) }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        // Every step served exactly once: no duplicates and none skipped.
        assertEquals(steps, seen.size)
        assertEquals((200 until 200 + steps).toSet(), seen)
    }

    @Test
    fun `a repeat-last cursor stays bounded under sustained traffic`() {
        val rule = sequenceRule(
            NetworkAction.ReturnResponse(500),
            NetworkAction.ReturnResponse(200),
        )
        val configuration = configuration(rule)

        repeat(1_000) { evaluate(configuration) }

        // Clamped at the step count rather than climbing towards Int.MAX_VALUE.
        assertEquals(2, executionState.peek(rule.id, 2).completed)
    }

    @Test
    fun `a loop cursor stays inside the step range`() {
        val rule = sequenceRule(
            NetworkAction.ReturnResponse(500),
            NetworkAction.ReturnResponse(200),
            completion = SequenceCompletionBehavior.LOOP,
        )
        val configuration = configuration(rule)

        repeat(1_001) { evaluate(configuration) }

        assertTrue(executionState.peek(rule.id, 2).completed in 0..1)
    }

    @Test
    fun `retainOnly forgets rules that are no longer active`() {
        val kept = sequenceRule(NetworkAction.ReturnResponse(500), path = "/a")
        val dropped = sequenceRule(NetworkAction.ReturnResponse(500), path = "/b")
        val configuration = ActiveNetworkConfiguration(rules = listOf(kept, dropped))

        evaluate(configuration, "/a")
        evaluate(configuration, "/b")
        executionState.retainOnly(setOf(kept.id))

        assertEquals(1, executionState.peek(kept.id, 1).completed)
        assertEquals(0, executionState.peek(dropped.id, 1).completed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty sequence is rejected at construction`() {
        NetworkAction.Sequence(emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a nested sequence is rejected at construction`() {
        SequenceStep(
            NetworkAction.Sequence(listOf(SequenceStep(NetworkAction.ReturnResponse(500)))),
        )
    }
}
