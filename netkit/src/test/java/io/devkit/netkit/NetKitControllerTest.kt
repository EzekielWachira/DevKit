package io.devkit.netkit

import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.history.InMemoryNetworkHistoryStore
import io.devkit.netkit.history.NetworkOutcome
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.GlobalNetworkConfig
import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.NetworkScenario
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.state.DefaultNetKitController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NetKitControllerTest {

    private fun controller(scenario: NetworkScenario = NetworkScenario.Default) =
        DefaultNetKitController(InMemoryNetworkHistoryStore(NetKitDefaults.MAX_HISTORY_ENTRIES), scenario)

    private fun rule(path: String = "/api/v1/bookings") =
        EndpointRule.forPath(path, HttpMethod.GET, NetworkAction.HttpError(500))

    @Test
    fun `the initial scenario is published`() {
        val initial = NetworkScenario(global = GlobalNetworkConfig(latencyMillis = 250))

        val state = controller(initial).state.value

        assertEquals(250L, state.global.latencyMillis)
        assertTrue(state.isSimulating)
    }

    @Test
    fun `enable and disable flip the master switch`() {
        val controller = controller()

        controller.disable()
        assertFalse(controller.state.value.enabled)

        controller.enable()
        assertTrue(controller.state.value.enabled)
    }

    @Test
    fun `setOffline toggles between offline and normal`() {
        val controller = controller()

        controller.setOffline(true)
        assertEquals(GlobalNetworkMode.Offline, controller.state.value.global.mode)
        assertTrue(controller.state.value.isOffline)

        controller.setOffline(false)
        assertEquals(GlobalNetworkMode.Normal, controller.state.value.global.mode)
    }

    @Test
    fun `setTimeout applies and clears a timeout mode`() {
        val controller = controller()

        controller.setTimeout(TimeoutType.CONNECT)
        assertEquals(GlobalNetworkMode.Timeout(TimeoutType.CONNECT), controller.state.value.global.mode)

        controller.setTimeout(null)
        assertEquals(GlobalNetworkMode.Normal, controller.state.value.global.mode)
    }

    @Test
    fun `latency is applied and negative values are coerced to zero`() {
        val controller = controller()

        controller.setGlobalLatency(2_500)
        assertEquals(2_500L, controller.state.value.global.latencyMillis)

        controller.setGlobalLatency(-100)
        assertEquals(0L, controller.state.value.global.latencyMillis)
    }

    @Test
    fun `mode and latency are independent`() {
        val controller = controller()

        controller.setGlobalLatency(1_000)
        controller.setOffline(true)
        controller.setOffline(false)

        assertEquals(1_000L, controller.state.value.global.latencyMillis)
    }

    @Test
    fun `addRule appends and returns the id`() {
        val controller = controller()
        val created = rule()

        val id = controller.addRule(created)

        assertEquals(created.id, id)
        assertEquals(listOf(created), controller.state.value.rules)
    }

    @Test
    fun `adding a rule with an existing id replaces it instead of duplicating`() {
        val controller = controller()
        val original = rule()
        controller.addRule(original)

        controller.addRule(original.copy(action = NetworkAction.Offline))

        assertEquals(1, controller.state.value.rules.size)
        assertEquals(NetworkAction.Offline, controller.state.value.rules.single().action)
    }

    @Test
    fun `updateRule replaces in place and preserves order`() {
        val controller = controller()
        val first = rule("/first")
        val second = rule("/second")
        controller.addRule(first)
        controller.addRule(second)

        controller.updateRule(first.copy(action = NetworkAction.Offline))

        val rules = controller.state.value.rules
        assertEquals(listOf(first.id, second.id), rules.map { it.id })
        assertEquals(NetworkAction.Offline, rules.first().action)
    }

    @Test
    fun `updating an unknown rule is a no-op`() {
        val controller = controller()
        controller.addRule(rule())
        val before = controller.state.value

        controller.updateRule(rule("/unknown"))

        assertEquals(before, controller.state.value)
    }

    @Test
    fun `removeRule drops only that rule`() {
        val controller = controller()
        val first = rule("/first")
        val second = rule("/second")
        controller.addRule(first)
        controller.addRule(second)

        controller.removeRule(first.id)

        assertEquals(listOf(second.id), controller.state.value.rules.map { it.id })
    }

    @Test
    fun `removing an unknown rule is a no-op`() {
        val controller = controller()
        controller.addRule(rule())
        val before = controller.state.value

        controller.removeRule("does-not-exist")

        assertEquals(before, controller.state.value)
    }

    @Test
    fun `enableRule and disableRule keep the rule in the list`() {
        val controller = controller()
        val created = rule()
        controller.addRule(created)

        controller.disableRule(created.id)
        assertFalse(controller.state.value.rules.single().enabled)
        assertEquals(0, controller.state.value.activeRuleCount)

        controller.enableRule(created.id)
        assertTrue(controller.state.value.rules.single().enabled)
        assertEquals(1, controller.state.value.activeRuleCount)
    }

    @Test
    fun `applyScenario replaces everything atomically`() {
        val controller = controller()
        val replacement = NetworkScenario(
            enabled = false,
            global = GlobalNetworkConfig(mode = GlobalNetworkMode.Offline),
            rules = listOf(rule()),
        )

        controller.applyScenario(replacement)

        assertEquals(replacement, controller.state.value.scenario)
    }

    @Test
    fun `reset restores normal networking and removes every rule`() {
        val controller = controller()
        controller.addRule(rule())
        controller.setOffline(true)
        controller.setGlobalLatency(5_000)
        controller.disable()

        controller.reset()

        val state = controller.state.value
        assertTrue(state.enabled)
        assertEquals(GlobalNetworkMode.Normal, state.global.mode)
        assertEquals(0L, state.global.latencyMillis)
        assertTrue(state.rules.isEmpty())
        assertFalse(state.isSimulating)
    }

    @Test
    fun `reset keeps history and clearHistory keeps the scenario`() {
        val store = InMemoryNetworkHistoryStore(10)
        val controller = DefaultNetKitController(store)
        store.record(
            NetworkRecord(
                id = store.nextRecordId(),
                startedAtMillis = 0,
                durationMillis = 1,
                method = "GET",
                scheme = "https",
                host = "api.example.com",
                path = "/x",
                url = "https://api.example.com/x",
                outcome = NetworkOutcome.Completed(200, "OK"),
            ),
        )
        controller.setOffline(true)

        controller.reset()
        assertEquals(1, controller.history.value.size)

        controller.setOffline(true)
        controller.clearHistory()
        assertTrue(controller.history.value.isEmpty())
        assertTrue(controller.state.value.isOffline)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `state emits a new snapshot on every observable change`() = runTest {
        val controller = controller()
        val initial = controller.state.first()

        controller.setGlobalLatency(500)

        assertNotEquals(initial, controller.state.first())
    }

    @Test
    fun `an update that changes nothing keeps the same state instance`() {
        val controller = controller()
        val before = controller.state.value

        controller.setGlobalLatency(0)
        controller.enable()

        assertTrue("no-op updates must not churn state", before === controller.state.value)
    }

    @Test
    fun `activeSummary reports overrides rather than the global mode alone`() {
        val controller = controller()
        assertEquals("Normal", controller.state.value.activeSummary)

        controller.addRule(rule())
        assertEquals("1 override", controller.state.value.activeSummary)

        controller.addRule(rule("/second"))
        assertEquals("2 overrides", controller.state.value.activeSummary)

        controller.setOffline(true)
        assertEquals("Offline +2", controller.state.value.activeSummary)

        controller.reset()
        controller.setOffline(true)
        assertEquals("Offline", controller.state.value.activeSummary)

        controller.disable()
        assertEquals("Off", controller.state.value.activeSummary)
    }

    @Test
    fun `concurrent rule additions are all applied`() {
        val controller = controller()
        val threads = 8
        val perThread = 50
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { thread ->
            pool.execute {
                start.await()
                repeat(perThread) { index -> controller.addRule(rule("/t$thread-$index")) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(threads * perThread, controller.state.value.rules.size)
    }
}
