package io.devkit.netkit

import io.devkit.netkit.config.NetKitConfig
import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.MalformedResponseType
import io.devkit.netkit.scenario.NetworkAction
import io.devkit.netkit.scenario.ResponseHeader
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Custom and malformed simulated responses, end to end against a real server.
 *
 * Every case asserts `server.requestCount == 0` where a response is simulated:
 * the whole promise of NetKit is that a simulated response never touches the
 * backend, and a test that only checked the status code would pass even if it
 * did.
 */
class CustomResponseTest {

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

    private fun get(path: String = "/api/v1/bookings"): Request =
        Request.Builder().url(server.url(path).toString()).build()

    private fun rule(action: NetworkAction, path: String = "/api/v1/bookings") {
        netKit.controller.addRule(
            io.devkit.netkit.scenario.EndpointRule.forPath(path, HttpMethod.ANY, action),
        )
    }

    // ---- successful custom responses ---------------------------------------

    @Test
    fun `a 200 with a custom body never reaches the server`() {
        rule(
            NetworkAction.ReturnResponse(
                statusCode = 200,
                body = """{"data":[]}""",
                contentType = "application/json",
            ),
        )

        client.newCall(get()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("""{"data":[]}""", response.body.string())
            assertEquals("application/json", response.header("Content-Type"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a 201 is a perfectly ordinary simulated response`() {
        rule(NetworkAction.ReturnResponse(201, """{"orderId":"o-1"}"""))

        client.newCall(get()).execute().use { response ->
            assertEquals(201, response.code)
            assertEquals("Created", response.message)
            assertEquals("""{"orderId":"o-1"}""", response.body.string())
        }
    }

    @Test
    fun `a 204 with no body returns nothing rather than an error envelope`() {
        rule(NetworkAction.ReturnResponse(204))

        client.newCall(get()).execute().use { response ->
            assertEquals(204, response.code)
            assertEquals("", response.body.string())
        }
    }

    @Test
    fun `a 200 with no body gets a marker payload rather than an error envelope`() {
        rule(NetworkAction.ReturnResponse(200))

        client.newCall(get()).execute().use { response ->
            assertEquals(NetKitDefaults.DEFAULT_SUCCESS_BODY, response.body.string())
        }
    }

    @Test
    fun `a 4xx with no body gets the error envelope`() {
        rule(NetworkAction.ReturnResponse(409))

        client.newCall(get()).execute().use { response ->
            assertEquals(409, response.code)
            assertEquals("Conflict", response.message)
            assertEquals(NetKitDefaults.DEFAULT_ERROR_BODY, response.body.string())
        }
    }

    @Test
    fun `a 422 with a backend-shaped body is returned verbatim`() {
        val body = """{"errors":[{"field":"email","code":"invalid"}]}"""
        rule(NetworkAction.ReturnResponse(422, body, "application/problem+json"))

        client.newCall(get()).execute().use { response ->
            assertEquals(422, response.code)
            assertEquals(body, response.body.string())
            assertEquals("application/problem+json", response.header("Content-Type"))
        }
    }

    // ---- headers -----------------------------------------------------------

    @Test
    fun `custom headers reach the client`() {
        rule(
            NetworkAction.ReturnResponse(
                statusCode = 429,
                headers = listOf(
                    ResponseHeader("Retry-After", "60"),
                    ResponseHeader("X-RateLimit-Remaining", "0"),
                    ResponseHeader("Content-Language", "fr"),
                ),
            ),
        )

        client.newCall(get()).execute().use { response ->
            assertEquals(429, response.code)
            assertEquals("60", response.header("Retry-After"))
            assertEquals("0", response.header("X-RateLimit-Remaining"))
            assertEquals("fr", response.header("Content-Language"))
        }
    }

    @Test
    fun `a scenario header may override a derived one`() {
        rule(
            NetworkAction.ReturnResponse(
                statusCode = 200,
                body = "{}",
                contentType = "application/json",
                headers = listOf(ResponseHeader("Content-Type", "text/plain")),
            ),
        )

        client.newCall(get()).execute().use { response ->
            assertEquals("text/plain", response.header("Content-Type"))
        }
    }

    @Test
    fun `the simulated markers cannot be overwritten by a scenario header`() {
        rule(
            NetworkAction.ReturnResponse(
                statusCode = 200,
                headers = listOf(ResponseHeader(NetKitDefaults.SIMULATED_HEADER, "false")),
            ),
        )

        client.newCall(get()).execute().use { response ->
            // A simulated response must always be identifiable as one.
            assertEquals("true", response.header(NetKitDefaults.SIMULATED_HEADER))
        }
    }

    // ---- malformed ---------------------------------------------------------

    @Test
    fun `invalid JSON is returned exactly as declared`() {
        rule(NetworkAction.Malformed(MalformedResponseType.InvalidJson))

        client.newCall(get()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals(MalformedResponseType.InvalidJson.body, response.body.string())
            assertEquals("application/json", response.header("Content-Type"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an empty body is returned with a JSON content type`() {
        rule(NetworkAction.Malformed(MalformedResponseType.EmptyBody))

        client.newCall(get()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("", response.body.string())
            assertEquals("application/json", response.header("Content-Type"))
            assertEquals("0", response.header("Content-Length"))
        }
    }

    @Test
    fun `truncated JSON is returned as declared`() {
        rule(NetworkAction.Malformed(MalformedResponseType.TruncatedJson))

        client.newCall(get()).execute().use { response ->
            val body = response.body.string()
            assertEquals(MalformedResponseType.TruncatedJson.body, body)
            assertFalse(body.endsWith("}"))
        }
    }

    @Test
    fun `HTML instead of JSON reproduces the classic proxy failure`() {
        rule(NetworkAction.Malformed(MalformedResponseType.HtmlInsteadOfJson))

        client.newCall(get()).execute().use { response ->
            assertEquals("text/html", response.header("Content-Type"))
            assertTrue(response.body.string().contains("<html>"))
        }
    }

    @Test
    fun `wrong content type sends valid JSON as text`() {
        rule(NetworkAction.Malformed(MalformedResponseType.WrongContentType))

        client.newCall(get()).execute().use { response ->
            assertEquals("text/plain", response.header("Content-Type"))
            assertEquals("""{"ok":true}""", response.body.string())
        }
    }

    @Test
    fun `an unexpected primitive is returned where an object was expected`() {
        rule(NetworkAction.Malformed(MalformedResponseType.UnexpectedPrimitive))

        client.newCall(get()).execute().use { response ->
            assertEquals("\"ok\"", response.body.string())
        }
    }

    @Test
    fun `a custom malformed payload is returned verbatim`() {
        rule(
            NetworkAction.Malformed(
                MalformedResponseType.Custom(
                    label = "Duplicate keys",
                    body = """{"id":1,"id":2}""",
                ),
            ),
        )

        client.newCall(get()).execute().use { response ->
            assertEquals("""{"id":1,"id":2}""", response.body.string())
        }
    }

    @Test
    fun `a malformed response can carry a non-200 status`() {
        rule(NetworkAction.Malformed(MalformedResponseType.HtmlInsteadOfJson, statusCode = 502))

        client.newCall(get()).execute().use { response ->
            assertEquals(502, response.code)
        }
    }

    @Test
    fun `a malformed response is flagged in history`() {
        rule(NetworkAction.Malformed(MalformedResponseType.InvalidJson))

        client.newCall(get()).execute().close()

        val record = netKit.controller.history.value.single()
        assertTrue(record.malformed)
        assertTrue(record.isSimulated)
        assertTrue(record.badges.contains("MALFORMED"))
    }

    // ---- unaffected traffic ------------------------------------------------

    @Test
    fun `an unmatched endpoint still reaches the real server`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"real":true}"""))
        rule(NetworkAction.ReturnResponse(503), path = "/api/v1/checkout")

        client.newCall(get("/api/v1/bookings")).execute().use { response ->
            assertEquals("""{"real":true}""", response.body.string())
        }
        assertEquals(1, server.requestCount)
        assertFalse(netKit.controller.history.value.single().isSimulated)
        assertNull(netKit.controller.history.value.single().ruleId)
    }
}
