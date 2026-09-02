package io.devkit.netkit

import io.devkit.netkit.config.NetKitConfig
import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.history.NetworkOutcome
import io.devkit.netkit.history.NetworkRecordSource
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.TimeoutType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * End-to-end coverage of the OkHttp binding against a real server.
 *
 * Simulated delays are kept in the low tens of milliseconds so the whole class
 * runs in well under a second; `simulatedTimeoutDelayMillis` overrides the
 * realistic "wait for the client timeout" behaviour for the same reason.
 */
class NetKitInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var netKit: NetKit
    private lateinit var client: OkHttpClient

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

    private fun get(path: String = "/api/v1/bookings"): Request =
        Request.Builder().url(url(path)).build()

    // ---- pass-through -----------------------------------------------------

    @Test
    fun `an untouched request reaches the server`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        client.newCall(get()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("""{"ok":true}""", response.body.string())
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a disabled netkit passes through even with rules configured`() {
        netKit.controller.addRule(
            EndpointRule.forPath("/api/v1/bookings", action = NetworkAction.ReturnResponse(500)),
        )
        netKit.controller.disable()
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(get()).execute().use { assertEquals(200, it.code) }
        assertEquals(1, server.requestCount)
    }

    // ---- HTTP overrides ---------------------------------------------------

    @Test
    fun `a simulated response never reaches the server`() {
        netKit.controller.addRule(
            EndpointRule.forPath("/api/v1/bookings", action = NetworkAction.ReturnResponse(500)),
        )

        client.newCall(get()).execute().use { response ->
            assertEquals(500, response.code)
            assertEquals("Internal Server Error", response.message)
        }
        assertEquals("the request must not have left the device", 0, server.requestCount)
    }

    @Test
    fun `an override returns the configured status body and content type`() {
        netKit.controller.addRule(
            EndpointRule.forPath(
                path = "/api/v1/bookings",
                method = HttpMethod.GET,
                action = NetworkAction.ReturnResponse(
                    statusCode = 503,
                    body = """{"message":"Bookings service temporarily unavailable"}""",
                    contentType = "application/json",
                ),
            ),
        )

        client.newCall(get()).execute().use { response ->
            assertEquals(503, response.code)
            assertEquals(
                """{"message":"Bookings service temporarily unavailable"}""",
                response.body.string(),
            )
            assertEquals("application/json", response.header("Content-Type"))
            assertEquals("true", response.header(NetKitDefaults.SIMULATED_HEADER))
            assertNotNull(response.header(NetKitDefaults.SIMULATED_RULE_HEADER))
        }
    }

    @Test
    fun `an override with no body returns the default envelope`() {
        netKit.controller.addRule(
            EndpointRule.forPath("/api/v1/bookings", action = NetworkAction.ReturnResponse(429)),
        )

        client.newCall(get()).execute().use { response ->
            assertEquals(429, response.code)
            assertEquals(NetKitDefaults.DEFAULT_ERROR_BODY, response.body.string())
        }
    }

    @Test
    fun `other endpoints keep reaching the real backend while one is overridden`() {
        netKit.controller.addRule(
            EndpointRule.forPath("/api/v1/bookings", action = NetworkAction.ReturnResponse(500)),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("profile"))

        client.newCall(get("/api/v1/bookings")).execute().use { assertEquals(500, it.code) }
        client.newCall(get("/api/v1/profile")).execute().use { assertEquals(200, it.code) }

        assertEquals(1, server.requestCount)
        assertEquals("/api/v1/profile", server.takeRequest().path)
    }

    // ---- offline ----------------------------------------------------------

    @Test
    fun `global offline throws an UnknownHostException`() {
        netKit.controller.setOffline(true)

        val error = runCatching { client.newCall(get()).execute() }.exceptionOrNull()

        assertTrue("expected UnknownHostException but was $error", error is UnknownHostException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an endpoint offline rule fails only that endpoint`() {
        netKit.controller.addRule(
            EndpointRule.forPath("/api/v1/checkout", action = NetworkAction.Offline),
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val failure = runCatching { client.newCall(get("/api/v1/checkout")).execute() }.exceptionOrNull()
        client.newCall(get("/api/v1/profile")).execute().use { assertEquals(200, it.code) }

        assertTrue(failure is UnknownHostException)
    }

    // ---- timeouts ---------------------------------------------------------

    @Test
    fun `global timeout throws a SocketTimeoutException`() {
        netKit.controller.setTimeout(TimeoutType.READ)

        val error = runCatching { client.newCall(get()).execute() }.exceptionOrNull()

        assertTrue("expected SocketTimeoutException but was $error", error is SocketTimeoutException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an endpoint timeout rule throws a SocketTimeoutException`() {
        netKit.controller.addRule(
            EndpointRule.forPath(
                path = "/api/v1/checkout",
                method = HttpMethod.POST,
                action = NetworkAction.Timeout(TimeoutType.CONNECT),
            ),
        )

        val request = Request.Builder()
            .url(url("/api/v1/checkout"))
            .post("""{"cart":1}""".toRequestBody("application/json".toMediaType()))
            .build()

        val error = runCatching { client.newCall(request).execute() }.exceptionOrNull()

        assertTrue(error is SocketTimeoutException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a timeout with no config override waits for the client's own read timeout`() {
        val bounded = NetKit.create(NetKitConfig())
        bounded.controller.setTimeout(TimeoutType.READ)
        val boundedClient = OkHttpClient.Builder()
            .addInterceptor(bounded.interceptor)
            .readTimeout(60, TimeUnit.MILLISECONDS)
            .build()

        val startedAt = System.nanoTime()
        val error = runCatching { boundedClient.newCall(get()).execute() }.exceptionOrNull()
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(error is SocketTimeoutException)
        assertTrue("expected to wait for the 60ms read timeout, waited $elapsedMillis ms", elapsedMillis >= 50)
    }

    // ---- latency ----------------------------------------------------------

    @Test
    fun `global latency delays the call before it reaches the server`() {
        netKit.controller.setGlobalLatency(120)
        server.enqueue(MockResponse().setResponseCode(200))

        val startedAt = System.nanoTime()
        client.newCall(get()).execute().use { assertEquals(200, it.code) }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("expected at least 120ms, was $elapsedMillis ms", elapsedMillis >= 110)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an endpoint delay replaces the global latency`() {
        netKit.controller.setGlobalLatency(5_000)
        netKit.controller.addRule(
            EndpointRule.forPath("/api/v1/bookings", action = NetworkAction.Delay(30)),
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val startedAt = System.nanoTime()
        client.newCall(get()).execute().use { assertEquals(200, it.code) }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("the 5s global latency must not apply, waited $elapsedMillis ms", elapsedMillis < 1_000)
    }

    // ---- history ----------------------------------------------------------

    @Test
    fun `a real request is recorded as REAL`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        client.newCall(get()).execute().use { it.body.string() }

        val record = netKit.controller.history.value.single()
        assertEquals("GET", record.method)
        assertEquals("/api/v1/bookings", record.path)
        assertEquals(NetworkRecordSource.REAL, record.source)
        assertEquals(200, (record.outcome as NetworkOutcome.Completed).statusCode)
        assertNull(record.scenarioLabel)
    }

    @Test
    fun `a simulated request is recorded as SIMULATED with the responsible scenario`() {
        val rule = EndpointRule.forPath(
            path = "/api/v1/bookings",
            action = NetworkAction.ReturnResponse(500),
            name = "Bookings Failure",
        )
        netKit.controller.addRule(rule)

        client.newCall(get()).execute().use { it.body.string() }

        val record = netKit.controller.history.value.single()
        assertEquals(NetworkRecordSource.SIMULATED, record.source)
        assertTrue(record.isSimulated)
        assertEquals("Bookings Failure", record.scenarioLabel)
        assertEquals(rule.id, record.ruleId)
        assertEquals(500, (record.outcome as NetworkOutcome.Completed).statusCode)
    }

    @Test
    fun `a simulated failure is recorded with its exception type`() {
        netKit.controller.setOffline(true)

        runCatching { client.newCall(get()).execute() }

        val record = netKit.controller.history.value.single()
        val outcome = record.outcome as NetworkOutcome.Failed
        assertEquals("UnknownHostException", outcome.kind)
        assertEquals("OFFLINE", outcome.label)
        assertTrue(record.isSimulated)
    }

    @Test
    fun `history captures a masked authorization header`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val request = Request.Builder()
            .url(url("/api/v1/bookings"))
            .header("Authorization", "Bearer super-secret-token")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { it.body.string() }

        val record = netKit.controller.history.value.single()
        val authorization = record.requestHeaders.single { it.name == "Authorization" }
        assertEquals("Bearer ••••••••", authorization.value)
        assertTrue(authorization.masked)
        assertFalse(record.requestHeaders.single { it.name == "Accept" }.masked)
    }

    @Test
    fun `history captures request and response body previews`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":42}"""),
        )
        val request = Request.Builder()
            .url(url("/api/v1/checkout"))
            .post("""{"cart":7}""".toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { it.body.string() }

        val record = netKit.controller.history.value.single()
        assertEquals("""{"cart":7}""", record.requestBody?.text)
        assertEquals("""{"id":42}""", record.responseBody?.text)
    }

    @Test
    fun `a peeked response body is still readable by the application`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"value":"intact"}"""),
        )

        val body = client.newCall(get()).execute().use { it.body.string() }

        assertEquals("""{"value":"intact"}""", body)
        assertEquals("""{"value":"intact"}""", netKit.controller.history.value.single().responseBody?.text)
    }

    @Test
    fun `history can be disabled entirely`() {
        val quiet = NetKit.create(NetKitConfig(historyEnabled = false))
        val quietClient = OkHttpClient.Builder().addInterceptor(quiet.interceptor).build()
        server.enqueue(MockResponse().setResponseCode(200))

        quietClient.newCall(get()).execute().use { it.body.string() }

        assertTrue(quiet.controller.history.value.isEmpty())
    }

    @Test
    fun `history honours the configured entry limit`() {
        val small = NetKit.create(NetKitConfig(maxHistoryEntries = 2))
        val smallClient = OkHttpClient.Builder().addInterceptor(small.interceptor).build()
        repeat(4) { server.enqueue(MockResponse().setResponseCode(200)) }

        repeat(4) { index ->
            smallClient.newCall(get("/request-$index")).execute().use { it.body.string() }
        }

        val records = small.controller.history.value
        assertEquals(2, records.size)
        assertEquals("/request-3", records.first().path)
        assertEquals("/request-2", records.last().path)
    }

    @Test
    fun `reset restores normal networking and keeps history`() {
        netKit.controller.setOffline(true)
        netKit.controller.addRule(
            EndpointRule.forPath("/api/v1/bookings", action = NetworkAction.ReturnResponse(500)),
        )
        runCatching { client.newCall(get("/api/v1/profile")).execute() }
        server.enqueue(MockResponse().setResponseCode(200))

        netKit.controller.reset()
        client.newCall(get()).execute().use { assertEquals(200, it.code) }

        assertTrue(netKit.controller.state.value.rules.isEmpty())
        assertFalse(netKit.controller.state.value.isSimulating)
        assertEquals(2, netKit.controller.history.value.size)
    }
}
