package io.devkit.netkit

import io.devkit.netkit.config.NetKitConfig
import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.history.NetworkRecordKind
import io.devkit.netkit.masking.DefaultSensitiveDataMasker
import io.devkit.netkit.replay.ReplayEligibility
import io.devkit.netkit.replay.ReplayOverride
import io.devkit.netkit.replay.ReplayResult
import io.devkit.netkit.replay.ReplaySnapshot
import io.devkit.netkit.replay.ReplayUnavailableReason
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.NetworkAction
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Request replay: sending a recorded call again.
 *
 * The two things worth proving are that replay reproduces the original request
 * faithfully — headers and all — and that it can never *expose* what it
 * reproduces. A replay must be able to send a real `Authorization` header while
 * the history row, the clipboard and any export still show only a mask.
 */
class RequestReplayTest {

    private lateinit var server: MockWebServer
    private lateinit var netKit: NetKit
    private lateinit var client: OkHttpClient

    private val token = "Bearer real-access-token-do-not-leak"

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        netKit = NetKit.create(NetKitConfig(simulatedTimeoutDelayMillis = 10))
        client = OkHttpClient.Builder()
            .addInterceptor(netKit.interceptor)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        netKit.shutdown()
        server.shutdown()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun makeCall(request: Request) {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        client.newCall(request).execute().close()
    }

    private fun recordedGet(path: String = "/api/v1/bookings"): Long {
        makeCall(
            Request.Builder()
                .url(url(path))
                .header("Authorization", token)
                .header("Accept", "application/json")
                .build(),
        )
        return netKit.controller.history.value.first().id
    }

    private fun recordedPost(path: String = "/api/v1/checkout"): Long {
        makeCall(
            Request.Builder()
                .url(url(path))
                .header("Authorization", token)
                .post("""{"cartId":"c-1"}""".toRequestBody(JSON))
                .build(),
        )
        return netKit.controller.history.value.first().id
    }

    // ---- eligibility -------------------------------------------------------

    @Test
    fun `a recorded GET is replayable`() {
        val id = recordedGet()

        val eligibility = netKit.replayer.eligibility(id)

        assertTrue(eligibility is ReplayEligibility.Eligible)
        assertFalse((eligibility as ReplayEligibility.Eligible).snapshot.isSideEffectful)
    }

    @Test
    fun `a recorded POST is replayable but flagged as side-effectful`() {
        val id = recordedPost()

        val eligibility = netKit.replayer.eligibility(id) as ReplayEligibility.Eligible

        assertTrue(eligibility.snapshot.isSideEffectful)
        assertEquals("POST", eligibility.snapshot.method)
    }

    @Test
    fun `mutating verbs are all treated as side-effectful`() {
        listOf("POST", "PUT", "PATCH", "DELETE").forEach { method ->
            makeCall(
                Request.Builder()
                    .url(url("/api/v1/thing"))
                    .method(method, "{}".toRequestBody(JSON))
                    .build(),
            )
            val id = netKit.controller.history.value.first().id
            val snapshot = (netKit.replayer.eligibility(id) as ReplayEligibility.Eligible).snapshot
            assertTrue("$method should be side-effectful", snapshot.isSideEffectful)
        }
    }

    @Test
    fun `safe verbs are not flagged`() {
        listOf("GET", "HEAD").forEach { method ->
            makeCall(Request.Builder().url(url("/api/v1/thing")).method(method, null).build())
            val id = netKit.controller.history.value.first().id
            val snapshot = (netKit.replayer.eligibility(id) as ReplayEligibility.Eligible).snapshot
            assertFalse("$method should be safe", snapshot.isSideEffectful)
        }
    }

    @Test
    fun `a one-shot body makes a request unreplayable`() {
        val oneShot = object : RequestBody() {
            override fun isOneShot() = true
            override fun contentType() = JSON
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("""{"stream":true}""")
            }
        }
        makeCall(Request.Builder().url(url("/api/v1/upload")).post(oneShot).build())
        val id = netKit.controller.history.value.first().id

        val eligibility = netKit.replayer.eligibility(id)

        assertTrue(eligibility is ReplayEligibility.Unavailable)
        assertEquals(
            ReplayUnavailableReason.ONE_SHOT_BODY,
            (eligibility as ReplayEligibility.Unavailable).reason,
        )
        assertEquals(
            ReplayUnavailableReason.ONE_SHOT_BODY,
            netKit.controller.history.value.first().replayUnavailableReason,
        )
    }

    @Test
    fun `an unknown record id is not replayable`() = runTest {
        val result = netKit.replayer.replay(9_999)

        assertTrue(result is ReplayResult.Unavailable)
        assertEquals(
            ReplayUnavailableReason.NO_SNAPSHOT,
            (result as ReplayResult.Unavailable).reason,
        )
    }

    @Test
    fun `replay is unavailable when it is switched off`() = runTest {
        val disabled = NetKit.create(NetKitConfig(replayEnabled = false))
        try {
            val disabledClient = OkHttpClient.Builder()
                .addInterceptor(disabled.interceptor)
                .build()
            server.enqueue(MockResponse().setResponseCode(200))
            disabledClient.newCall(Request.Builder().url(url("/x")).build()).execute().close()

            val id = disabled.controller.history.value.first().id
            assertTrue(disabled.replayer.eligibility(id) is ReplayEligibility.Unavailable)
            assertEquals(
                ReplayUnavailableReason.DISABLED,
                disabled.controller.history.value.first().replayUnavailableReason,
            )
        } finally {
            disabled.shutdown()
        }
    }

    @Test
    fun `snapshots are bounded so credentials do not accumulate`() {
        val bounded = NetKit.create(NetKitConfig(maxReplaySnapshots = 3))
        try {
            val boundedClient = OkHttpClient.Builder()
                .addInterceptor(bounded.interceptor)
                .build()
            repeat(6) {
                server.enqueue(MockResponse().setResponseCode(200))
                boundedClient.newCall(Request.Builder().url(url("/x")).build()).execute().close()
            }

            assertEquals(3, bounded.replaySnapshots!!.size)
            val ids = bounded.controller.history.value.map { it.id }
            // The three newest stay replayable; the oldest are dropped.
            assertTrue(bounded.replayer.eligibility(ids.first()).isEligible)
            assertFalse(bounded.replayer.eligibility(ids.last()).isEligible)
        } finally {
            bounded.shutdown()
        }
    }

    // ---- replaying ---------------------------------------------------------

    @Test
    fun `replaying a GET sends the request again`() = runTest {
        val id = recordedGet()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"replayed":true}"""))

        val result = netKit.replayer.replay(id)

        assertTrue(result is ReplayResult.Success)
        assertEquals(200, (result as ReplayResult.Success).statusCode)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a replay reproduces the original headers`() = runTest {
        val id = recordedGet()
        server.enqueue(MockResponse().setResponseCode(200))
        server.takeRequest()

        netKit.replayer.replay(id)

        val replayed = server.takeRequest()
        assertEquals(token, replayed.getHeader("Authorization"))
        assertEquals("application/json", replayed.getHeader("Accept"))
        assertEquals(id.toString(), replayed.getHeader(NetKitDefaults.REPLAY_HEADER))
    }

    @Test
    fun `a replay appears in history marked REPLAY`() = runTest {
        val id = recordedGet()
        server.enqueue(MockResponse().setResponseCode(200))

        netKit.replayer.replay(id)

        val newest = netKit.controller.history.value.first()
        assertEquals(NetworkRecordKind.REPLAY, newest.kind)
        assertTrue(newest.isReplay)
        assertEquals(id, newest.replayOfRecordId)
        assertTrue(newest.badges.contains("REPLAY"))
        assertNotEquals(id, newest.id)
    }

    @Test
    fun `a replay goes through the active rules`() = runTest {
        val id = recordedGet()
        netKit.controller.addRule(
            EndpointRule.forPath(
                path = "/api/v1/bookings",
                method = HttpMethod.ANY,
                action = NetworkAction.ReturnResponse(503),
            ),
        )

        val result = netKit.replayer.replay(id) as ReplayResult.Success

        assertEquals(503, result.statusCode)
        assertTrue(result.simulated)
        // The scenario answered, so the backend saw nothing new.
        assertEquals(1, server.requestCount)

        val newest = netKit.controller.history.value.first()
        assertTrue(newest.isReplay)
        assertTrue(newest.isSimulated)
        assertEquals(listOf("REPLAY", "SIMULATED"), newest.badges)
    }

    @Test
    fun `bypassing NetKit reaches the real server despite an active rule`() = runTest {
        val id = recordedGet()
        netKit.controller.addRule(
            EndpointRule.forPath(
                path = "/api/v1/bookings",
                action = NetworkAction.ReturnResponse(503),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"real":true}"""))

        val result = netKit.replayer.replay(id, bypassNetKit = true) as ReplayResult.Success

        assertEquals(200, result.statusCode)
        assertFalse(result.simulated)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an override can replace the body`() = runTest {
        val id = recordedPost()
        server.enqueue(MockResponse().setResponseCode(200))
        server.takeRequest()

        netKit.replayer.replay(id, ReplayOverride(body = """{"cartId":"c-2"}"""))

        assertEquals("""{"cartId":"c-2"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `an override can replace the URL`() = runTest {
        val id = recordedGet("/api/v1/bookings")
        server.enqueue(MockResponse().setResponseCode(200))
        server.takeRequest()

        netKit.replayer.replay(id, ReplayOverride(url = url("/api/v1/profile")))

        assertEquals("/api/v1/profile", server.takeRequest().path)
    }

    @Test
    fun `an invalid URL override fails without sending anything`() = runTest {
        val id = recordedGet()

        val result = netKit.replayer.replay(id, ReplayOverride(url = "not a url"))

        assertTrue(result is ReplayResult.Failed)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an override can set a header without ever reading the original`() = runTest {
        val id = recordedGet()
        server.enqueue(MockResponse().setResponseCode(200))
        server.takeRequest()

        netKit.replayer.replay(
            id,
            ReplayOverride(headers = mapOf("Authorization" to "Bearer replacement")),
        )

        assertEquals("Bearer replacement", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a transport failure is reported and recorded`() = runTest {
        val id = recordedGet()
        netKit.controller.addRule(
            EndpointRule.forPath("/api/v1/bookings", action = NetworkAction.Offline),
        )

        val result = netKit.replayer.replay(id)

        assertTrue(result is ReplayResult.Failed)
        assertNotNull((result as ReplayResult.Failed).recordId)
        assertTrue(netKit.controller.history.value.first().isReplay)
    }

    // ---- security ----------------------------------------------------------

    @Test
    fun `the history record masks the credential the snapshot still holds`() = runTest {
        val id = recordedGet()

        val record = netKit.controller.history.value.first { it.id == id }
        val authorization = record.requestHeaders.single { it.name == "Authorization" }
        assertTrue(authorization.masked)
        assertEquals("Bearer ${DefaultSensitiveDataMasker.MASK}", authorization.value)
        assertFalse(authorization.value.contains("real-access-token"))

        // The replay still sends the real one — that separation is the point.
        server.enqueue(MockResponse().setResponseCode(200))
        server.takeRequest()
        netKit.replayer.replay(id)
        assertEquals(token, server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a replayed record is masked exactly like the original`() = runTest {
        val id = recordedGet()
        server.enqueue(MockResponse().setResponseCode(200))

        netKit.replayer.replay(id)

        val replayed = netKit.controller.history.value.first()
        val authorization = replayed.requestHeaders.single { it.name == "Authorization" }
        assertTrue(authorization.masked)
        assertFalse(authorization.value.contains("real-access-token"))
    }

    @Test
    fun `clearing history drops every replay snapshot`() {
        val id = recordedGet()
        assertTrue(netKit.replayer.eligibility(id).isEligible)

        netKit.controller.clearHistory()

        // A snapshot outliving its history row would keep a credential alive for
        // a request nobody can see any more.
        assertFalse(netKit.replayer.eligibility(id).isEligible)
        assertEquals(0, netKit.replaySnapshots!!.size)
    }

    @Test
    fun `resetEverything drops replay snapshots`() {
        val id = recordedGet()

        netKit.controller.resetEverything()

        assertFalse(netKit.replayer.eligibility(id).isEligible)
    }

    @Test
    fun `a body can be replaced only when there is a textual one`() {
        val textual = (
            netKit.replayer.eligibility(recordedPost()) as ReplayEligibility.Eligible
            ).snapshot
        assertTrue(textual.hasReplaceableBody)

        val none = (netKit.replayer.eligibility(recordedGet()) as ReplayEligibility.Eligible).snapshot
        assertFalse(none.hasReplaceableBody)
    }

    /**
     * The snapshot must expose *whether* a body exists, never the body itself:
     * a request body is where `{"password":…}` and `{"refresh_token":…}` live,
     * and NetKit does not mask bodies.
     */
    @Test
    fun `the snapshot never exposes the request body`() {
        val id = recordedPost()
        val snapshot = (netKit.replayer.eligibility(id) as ReplayEligibility.Eligible).snapshot

        // Java reflection rather than kotlin-reflect: this asserts on the class's
        // actual public surface, which is what a UI can reach.
        val readable = ReplaySnapshot::class.java.methods
            .filter { it.parameterCount == 0 && java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .mapNotNull { method -> runCatching { method.invoke(snapshot) }.getOrNull() }
            .map(Any::toString)

        assertTrue("no public member may expose the body", readable.none { it.contains("cartId") })
        assertTrue("no public member may expose a credential", readable.none { it.contains(token) })
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
