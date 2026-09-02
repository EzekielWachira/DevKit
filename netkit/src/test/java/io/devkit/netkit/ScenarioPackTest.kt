package io.devkit.netkit

import io.devkit.netkit.scenario.GlobalNetworkMode
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.MalformedResponseType
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.SequenceCompletionBehavior
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.scenario.model.ScenarioSource
import io.devkit.netkit.scenario.pack.scenarioPack
import io.devkit.netkit.scenario.persistence.ScenarioWriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario packs declared in application code.
 *
 * The properties that matter are that ids are **stable across app launches** —
 * otherwise an activated built-in scenario disappears on every restart — and
 * that built-ins are never written to the scenario store, where they would
 * become stale duplicates of code that has since changed.
 */
class ScenarioPackTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun checkoutPack() = scenarioPack("Checkout") {
        description("Everything that can go wrong at payment time")

        scenario("Gateway unavailable") {
            description("The payment gateway is down")
            post("/api/v1/checkout") { respond(503) }
        }

        scenario("Retry eventually succeeds") {
            post("/api/v1/checkout") {
                sequence {
                    respond(500)
                    respond(500)
                    respond(200, body = """{"orderId":"o-1"}""")
                }
            }
        }

        scenario("Slow everything") {
            latency(2_500)
            get("/api/v1/notifications") { passThrough() }
        }

        scenario("Malformed payment response") {
            post("/api/v1/checkout") { malformed(MalformedResponseType.TruncatedJson) }
        }

        scenario("Payment timeout") {
            post("/api/v1/checkout") {
                name("Checkout timeout")
                timeout(TimeoutType.READ)
            }
        }
    }

    @Test
    fun `a pack declares its scenarios`() {
        val pack = checkoutPack()

        assertEquals("Checkout", pack.pack.name)
        assertEquals("Everything that can go wrong at payment time", pack.pack.description)
        assertEquals(5, pack.scenarios.size)
        assertEquals(
            listOf(
                "Gateway unavailable",
                "Retry eventually succeeds",
                "Slow everything",
                "Malformed payment response",
                "Payment timeout",
            ),
            pack.scenarios.map { it.name },
        )
    }

    @Test
    fun `every declared scenario belongs to its pack and is read-only`() {
        val pack = checkoutPack()

        pack.scenarios.forEach { scenario ->
            assertEquals(pack.pack.id, scenario.metadata.packId)
            assertEquals(ScenarioSource.BUILT_IN, scenario.metadata.source)
            assertTrue(scenario.metadata.isReadOnly)
        }
        assertTrue(pack.pack.isReadOnly)
    }

    @Test
    fun `ids are stable across re-declaration`() {
        // The same code on the next app launch must produce the same ids, or an
        // activated built-in scenario vanishes on every restart.
        val first = checkoutPack()
        val second = checkoutPack()

        assertEquals(first.pack.id, second.pack.id)
        assertEquals(first.scenarios.map { it.id }, second.scenarios.map { it.id })
        assertEquals(
            first.scenarios.flatMap { it.rules }.map { it.id },
            second.scenarios.flatMap { it.rules }.map { it.id },
        )
    }

    @Test
    fun `the DSL builds every action kind`() {
        val pack = scenarioPack("Kinds") {
            scenario("All") {
                get("/a") { passThrough() }
                get("/b") { delay(500) }
                get("/c") { respond(418, body = "{}") }
                get("/d") { malformed(MalformedResponseType.EmptyBody) }
                get("/e") { offline() }
                get("/f") { timeout(TimeoutType.CONNECT) }
                get("/g") {
                    sequence(SequenceCompletionBehavior.LOOP) {
                        respond(500)
                        respond(200)
                    }
                }
            }
        }

        val actions = pack.scenarios.single().rules.map { it.action }
        assertTrue(actions[0] is NetworkAction.PassThrough)
        assertEquals(NetworkAction.Delay(500), actions[1])
        assertEquals(418, (actions[2] as NetworkAction.ReturnResponse).statusCode)
        assertEquals(MalformedResponseType.EmptyBody, (actions[3] as NetworkAction.Malformed).type)
        assertTrue(actions[4] is NetworkAction.Offline)
        assertEquals(TimeoutType.CONNECT, (actions[5] as NetworkAction.Timeout).type)
        assertEquals(
            SequenceCompletionBehavior.LOOP,
            (actions[6] as NetworkAction.Sequence).completion,
        )
    }

    @Test
    fun `methods are scoped as declared`() {
        val pack = scenarioPack("Verbs") {
            scenario("All") {
                get("/a") { offline() }
                post("/b") { offline() }
                put("/c") { offline() }
                patch("/d") { offline() }
                delete("/e") { offline() }
                any("/f") { offline() }
            }
        }

        assertEquals(
            listOf(
                HttpMethod.GET,
                HttpMethod.POST,
                HttpMethod.PUT,
                HttpMethod.PATCH,
                HttpMethod.DELETE,
                HttpMethod.ANY,
            ),
            pack.scenarios.single().rules.map { it.method },
        )
    }

    @Test
    fun `a scenario can leave the global network alone`() {
        val pack = scenarioPack("Rules only") {
            scenario("No global") { get("/a") { offline() } }
            scenario("Offline") { offline() }
        }

        assertNull(pack.scenarios[0].globalConfig)
        assertEquals(GlobalNetworkMode.Offline, pack.scenarios[1].globalConfig?.mode)
    }

    @Test
    fun `a rule can be declared switched off`() {
        val pack = scenarioPack("Optional") {
            scenario("Mostly on") {
                get("/a") { offline() }
                get("/b") {
                    offline()
                    disabled()
                }
            }
        }

        val rules = pack.scenarios.single().rules
        assertTrue(rules[0].enabled)
        assertFalse(rules[1].enabled)
    }

    @Test(expected = IllegalStateException::class)
    fun `a rule with two behaviours is rejected`() {
        scenarioPack("Bad") {
            scenario("Confused") {
                get("/a") {
                    respond(500)
                    offline()
                }
            }
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `a rule with no behaviour is rejected`() {
        scenarioPack("Bad") {
            scenario("Empty") { get("/a") { } }
        }
    }

    // ---- registration ------------------------------------------------------

    @Test
    fun `registered packs appear alongside saved scenarios`() = runTest {
        val manager = Fixtures.manager(scope, builtInPacks = listOf(checkoutPack()))
        manager.save(Fixtures.scenario("My own"))

        assertEquals(6, manager.scenarios.value.size)
        assertEquals(1, manager.packs.value.size)
        assertEquals(5, manager.packContents.value.single().scenarios.size)
        assertEquals(listOf("My own"), manager.looseScenarios.value.map { it.name })
    }

    @Test
    fun `a built-in scenario is never written to storage`() = runTest {
        val storage = io.devkit.netkit.scenario.persistence.InMemoryScenarioStorage()
        val repository = Fixtures.repository(storage)
        val manager = Fixtures.manager(scope, repository, listOf(checkoutPack()))

        manager.activate(checkoutPack().scenarios.first().id)

        // Only the activation is remembered — the definitions stay in code, so
        // changing them in a later build cannot leave a stale copy behind.
        val document = storage.read().orEmpty()
        assertFalse(document.contains("Gateway unavailable"))
        assertTrue(repository.scenarios.value.isEmpty())
    }

    @Test
    fun `a built-in scenario can be activated`() = runTest {
        val pack = checkoutPack()
        val manager = Fixtures.manager(scope, builtInPacks = listOf(pack))
        val gateway = pack.scenarios.first()

        assertTrue(manager.activate(gateway.id))

        assertEquals(gateway.id, manager.activeScenarioId.value)
        assertEquals(gateway.id, manager.activeSnapshot.value?.id)
    }

    @Test
    fun `duplicating a built-in produces an editable copy`() = runTest {
        val pack = checkoutPack()
        val manager = Fixtures.manager(scope, builtInPacks = listOf(pack))
        val gateway = pack.scenarios.first()

        val copy = manager.duplicate(gateway.id).scenarioOrNull!!

        assertNotEquals(gateway.id, copy.id)
        assertEquals(ScenarioSource.CREATED_IN_APP, copy.metadata.source)
        assertFalse(copy.metadata.isReadOnly)
        assertEquals(gateway.rules.size, copy.rules.size)
        assertTrue(manager.save(copy.copy(name = "Edited")) is ScenarioWriteResult.Success)
    }

    @Test
    fun `a built-in scenario cannot be deleted`() = runTest {
        val pack = checkoutPack()
        val manager = Fixtures.manager(scope, builtInPacks = listOf(pack))

        manager.delete(pack.scenarios.first().id)

        assertEquals(5, manager.scenarios.value.size)
        assertTrue(manager.lastError.value is
            io.devkit.netkit.scenario.persistence.ScenarioPersistenceError.ReadOnly)
    }

    @Test
    fun `a built-in scenario can be exported`() = runTest {
        val pack = checkoutPack()
        val manager = Fixtures.manager(scope, builtInPacks = listOf(pack))

        val export = manager.export(pack.scenarios.first().id)

        assertTrue(export!!.content.contains("Gateway unavailable"))
        assertEquals("gateway-unavailable.netkit.json", export.suggestedFileName)
    }

    @Test
    fun `a whole built-in pack can be exported`() = runTest {
        val pack = checkoutPack()
        val manager = Fixtures.manager(scope, builtInPacks = listOf(pack))

        val export = manager.exportPack(pack.pack.id)!!

        assertTrue(export.content.contains("\"type\": \"scenario-pack\""))
        assertEquals("checkout.netkit.json", export.suggestedFileName)
        assertTrue(export.content.contains("Retry eventually succeeds"))
    }
}
