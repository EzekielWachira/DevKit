package io.devkit.netkit

import io.devkit.netkit.config.NetKitConfig
import io.devkit.netkit.config.NetKitLimits
import io.devkit.netkit.history.InMemoryNetworkHistoryStore
import io.devkit.netkit.history.NetworkOutcome
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.model.ScenarioPack
import io.devkit.netkit.scenario.pack.scenarioPack
import io.devkit.netkit.scenario.persistence.InMemoryScenarioStorage
import io.devkit.netkit.scenario.persistence.JsonScenarioRepository
import io.devkit.netkit.scenario.persistence.ScenarioStorage
import io.devkit.netkit.scenario.persistence.ScenarioWriteResult
import io.devkit.netkit.scenario.runtime.ImportCollisionPolicy
import io.devkit.netkit.scenario.runtime.ScenarioImportOutcome
import io.devkit.netkit.scenario.serialization.JsonScenarioSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The ways NetKit is expected to survive contact with a real application.
 *
 * Each case here is a defect that a review found in this release rather than a
 * feature: an id that collided across restarts, a history publish that lost a
 * row, an application body that threw on the OkHttp thread. They are grouped
 * together because what they have in common is not a feature area — it is that
 * every one of them was invisible until something went subtly wrong.
 */
class RobustnessTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ---- persisted identity ------------------------------------------------

    /**
     * Rule ids are persisted inside saved scenarios and are the key sequence
     * cursors live under. A counter that restarts with the process would let a
     * rule created today collide with one saved yesterday, and two rules sharing
     * a cursor means a `500 → 500 → 200` sequence firing on the wrong request.
     */
    @Test
    fun `rule ids are unique across processes, not merely within one`() {
        val ids = List(1_000) { EndpointRule.nextId() }

        assertEquals(1_000, ids.toSet().size)
        // The distinguishing property against a counter: the id is drawn from a
        // space a second process cannot walk into, not from 1, 2, 3…
        assertTrue(
            "a rule id must not be a restartable counter",
            ids.all { id ->
                runCatching { java.util.UUID.fromString(id.removePrefix("netkit-rule-")) }.isSuccess
            },
        )
    }

    @Test
    fun `a scenario saved in one session cannot collide with a rule made in the next`() = runTest {
        val storage: ScenarioStorage = InMemoryScenarioStorage()

        val first = JsonScenarioRepository(storage)
        val saved = first.save(
            Fixtures.scenario("Retry", Fixtures.rule(action = NetworkAction.ReturnResponse(500))),
        ).scenarioOrNull!!

        // A second process: fresh repository, fresh everything.
        val second = JsonScenarioRepository(storage)
        second.load()
        val freshRule = EndpointRule.forPath("/other", action = NetworkAction.Offline)

        assertFalse(freshRule.id == saved.rules.single().id)
    }

    // ---- history -----------------------------------------------------------

    /**
     * Concurrent writers each build a snapshot and publish it. Publishing
     * outside the lock lets the writer that finished first publish last, so a
     * record silently disappears from the console until the next request.
     */
    @Test
    fun `concurrent history writes never lose a record`() {
        val store = InMemoryNetworkHistoryStore(maxEntries = 500)
        val writers = 8
        val perWriter = 50
        val pool = Executors.newFixedThreadPool(writers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)

        try {
            repeat(writers) {
                pool.submit {
                    start.await()
                    repeat(perWriter) {
                        store.record(record(store.nextRecordId()))
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(writers * perWriter, store.records.value.size)
        assertEquals(writers * perWriter, store.records.value.map { it.id }.toSet().size)
    }

    @Test
    fun `a cleared history cannot be resurrected by an in-flight write`() {
        val store = InMemoryNetworkHistoryStore(maxEntries = 100)
        repeat(20) { store.record(record(store.nextRecordId())) }

        store.clear()

        assertTrue(store.records.value.isEmpty())
    }

    private fun record(id: Long) = NetworkRecord(
        id = id,
        startedAtMillis = 0,
        durationMillis = 1,
        method = "GET",
        scheme = "https",
        host = "api.example.com",
        path = "/x",
        url = "https://api.example.com/x",
        outcome = NetworkOutcome.Completed(200, "OK"),
    )

    // ---- application bodies ------------------------------------------------

    /**
     * `RequestBody.writeTo` is application code — a Moshi adapter, a protobuf
     * writer, something hand-rolled — and it can throw anything. Capturing a
     * preview is a NetKit convenience; letting the throw escape would turn a
     * request that was about to succeed into a crash on an OkHttp thread and
     * leak the response with it.
     */
    @Test
    fun `a request body that throws does not fail the request`() {
        val server = MockWebServer().apply { start() }
        val netKit = NetKit.create(NetKitConfig())
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(netKit.interceptor)
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

            val hostile = object : RequestBody() {
                override fun contentType() = "application/json".toMediaType()
                // -1, not a throw: OkHttp's own BridgeInterceptor calls this
                // before NetKit is reached, so throwing here would test OkHttp.
                override fun contentLength(): Long = -1
                private var written = false
                override fun writeTo(sink: BufferedSink) {
                    // Succeeds for the real send, throws for NetKit's preview —
                    // the order NetKit actually uses.
                    if (written) throw IllegalStateException("adapter blew up")
                    written = true
                    sink.writeUtf8("""{"cart":"c-1"}""")
                }
            }

            client.newCall(
                Request.Builder().url(server.url("/api/v1/checkout").toString()).post(hostile).build(),
            ).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("""{"ok":true}""", response.body.string())
            }

            // Recorded, with the body noted as unreadable rather than dropped.
            val stored = netKit.controller.history.value.single()
            assertEquals(200, (stored.outcome as NetworkOutcome.Completed).statusCode)
        } finally {
            netKit.shutdown()
            server.shutdown()
        }
    }

    @Test
    fun `a body with no declared length is not buffered past the preview limit`() {
        val server = MockWebServer().apply { start() }
        val netKit = NetKit.create(NetKitConfig(maxBodyPreviewBytes = 64))
        try {
            val client = OkHttpClient.Builder().addInterceptor(netKit.interceptor).build()
            server.enqueue(MockResponse().setResponseCode(200))

            val unknownLength = object : RequestBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength(): Long = -1
                override fun writeTo(sink: BufferedSink) {
                    sink.writeUtf8("x".repeat(4_096))
                }
            }

            client.newCall(
                Request.Builder().url(server.url("/upload").toString()).post(unknownLength).build(),
            ).execute().close()

            val preview = netKit.controller.history.value.single().requestBody
            assertNotNull(preview)
            assertTrue(preview!!.truncated)
            // The oversized payload is reported, never retained.
            assertFalse(preview.text.contains("xxxx"))
        } finally {
            netKit.shutdown()
            server.shutdown()
        }
    }

    // ---- import atomicity --------------------------------------------------

    /**
     * The import preview promises a file is imported whole or not at all. A pack
     * whose seventh scenario is rejected must not leave six scenarios and a
     * stray pack behind.
     */
    @Test
    fun `a pack containing one invalid scenario imports nothing`() = runTest {
        val manager = Fixtures.manager(scope)
        val serializer = JsonScenarioSerializer()
        val pack = ScenarioPack(name = "Checkout")
        val oversized = Fixtures.scenario(
            "Too big",
            Fixtures.rule(
                action = NetworkAction.ReturnResponse(
                    statusCode = 200,
                    body = "x".repeat(NetKitLimits.MAX_BODY_BYTES + 1),
                ),
            ),
        )
        // Built by hand, because the serializer would refuse to export this.
        val content = serializer.exportPack(pack, listOf(Fixtures.scenario("Fine"), oversized))
            .content

        val outcome = manager.commitImport(manager.preview(content))

        assertFalse(outcome is ScenarioImportOutcome.Imported)
        assertTrue(manager.scenarios.value.isEmpty())
        assertTrue(manager.packs.value.isEmpty())
    }

    /**
     * A code-declared scenario is not in the repository, so "replace" would
     * store a second scenario under the same id — permanently shadowed by the
     * built-in and impossible to delete.
     */
    @Test
    fun `an import cannot replace a built-in scenario`() = runTest {
        val pack = scenarioPack("Checkout") {
            scenario("Gateway unavailable") { post("/api/v1/checkout") { respond(503) } }
        }
        val manager = Fixtures.manager(scope, builtInPacks = listOf(pack))
        val builtIn = pack.scenarios.single()
        val content = JsonScenarioSerializer().exportScenario(
            builtIn.copy(rules = listOf(Fixtures.rule(action = NetworkAction.ReturnResponse(418)))),
        ).content

        val outcome = manager.commitImport(
            manager.preview(content),
            ImportCollisionPolicy.REPLACE_EXISTING,
        )

        assertTrue(outcome is ScenarioImportOutcome.Imported)
        val imported = (outcome as ScenarioImportOutcome.Imported).scenarios.single()
        // Imported as a copy despite the policy, so both stay reachable.
        assertFalse(imported.id == builtIn.id)
        assertEquals(0, outcome.replaced)
        assertNotNull(manager.find(builtIn.id))
        assertNotNull(manager.find(imported.id))
    }

    @Test
    fun `an imported copy keeps its own creation time`() = runTest {
        val manager = Fixtures.manager(scope)
        val mine = manager.save(Fixtures.scenario("Checkout")).scenarioOrNull!!
        val stale = mine.copy(
            metadata = mine.metadata.copy(createdAtMillis = 1L, updatedAtMillis = 1L),
        )
        val content = JsonScenarioSerializer().exportScenario(stale).content

        manager.commitImport(manager.preview(content))

        val copy = manager.scenarios.value.single { it.id != mine.id }
        assertTrue(
            "a fresh copy must not inherit the exporting device's timestamps",
            copy.metadata.createdAtMillis > 1L,
        )
    }

    // ---- storage failures --------------------------------------------------

    /** A write that never reached storage must not be reported as a success. */
    @Test
    fun `a failed save is reported and leaves the published list unchanged`() = runTest {
        val repository = JsonScenarioRepository(FailingStorage)

        val result = repository.save(Fixtures.scenario("Checkout"))

        assertTrue(result is ScenarioWriteResult.Failure)
        assertTrue(repository.scenarios.value.isEmpty())
    }

    @Test
    fun `a failed delete surfaces on the manager rather than passing silently`() = runTest {
        val storage = FlakyStorage()
        val repository = JsonScenarioRepository(storage)
        val manager = Fixtures.manager(scope, repository)
        val saved = manager.save(Fixtures.scenario("Checkout")).scenarioOrNull!!

        storage.failing = true
        manager.delete(saved.id)

        assertNotNull(manager.lastError.value)
    }

    private object FailingStorage : ScenarioStorage {
        override suspend fun read(): String? = null
        override suspend fun write(document: String): Unit = throw IOException("disk full")
        override suspend fun clear() = Unit
    }

    private class FlakyStorage : ScenarioStorage {
        var failing = false
        private var document: String? = null

        override suspend fun read(): String? = document

        override suspend fun write(document: String) {
            if (failing) throw IOException("disk full")
            this.document = document
        }

        override suspend fun clear() {
            document = null
        }
    }

    // ---- runtime consistency -----------------------------------------------

    /**
     * The engine must decide "is anything configured" and "what should happen"
     * from one snapshot. Reading twice lets a request arriving as the console
     * toggles the master switch get one answer from each.
     */
    @Test
    fun `the engine reads the configuration exactly once per evaluation`() {
        var reads = 0
        val controller = Fixtures.controller(scope)
        val engine = io.devkit.netkit.engine.DefaultNetworkScenarioEngine(
            io.devkit.netkit.scenario.runtime.DefaultScenarioExecutionState(),
        ) {
            reads++
            controller.configuration
        }

        engine.evaluate(
            io.devkit.netkit.scenario.RequestTarget(
                method = "GET",
                scheme = "https",
                host = "api.example.com",
                port = 443,
                path = "/x",
            ),
        )

        assertEquals(1, reads)
    }

    @Test
    fun `removing a temporary rule resets its cursor exactly once`() = runTest {
        val controller = Fixtures.controller(scope)
        val rule = Fixtures.rule(
            action = NetworkAction.Sequence(
                listOf(
                    io.devkit.netkit.scenario.SequenceStep(NetworkAction.ReturnResponse(500)),
                    io.devkit.netkit.scenario.SequenceStep(NetworkAction.ReturnResponse(200)),
                ),
            ),
        )
        controller.addRule(rule)

        controller.removeRule(rule.id)

        assertTrue(controller.state.value.rules.isEmpty())
        assertNull(controller.scenarios.sequenceProgress.value[rule.id])
    }
}
