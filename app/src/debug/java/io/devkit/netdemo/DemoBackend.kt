package io.devkit.netdemo

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.IOException

/**
 * A tiny local "backend" for the demo.
 *
 * It exists so the demo can show the difference between a real server result and
 * a NetKit-simulated one without needing network access, credentials or a
 * staging environment. Debug-only, like everything else in this package.
 */
internal object DemoBackend {

    private var server: MockWebServer? = null

    /**
     * Starts the server and returns its base URL, or `null` if the port is unavailable.
     *
     * The URL is built on loopback rather than from `MockWebServer.url()`, which
     * reports the machine's routable address — reachable from the host, but not
     * from inside an emulator.
     */
    fun start(): String? {
        server?.let { return baseUrlFor(it) }
        return try {
            val started = MockWebServer().apply {
                dispatcher = DemoDispatcher
                start()
            }
            server = started
            baseUrlFor(started)
        } catch (error: IOException) {
            // No port, no demo backend — the demo screen then shows real
            // connection failures, which is still a truthful thing to look at.
            null
        }
    }

    private fun baseUrlFor(server: MockWebServer) = "http://127.0.0.1:${server.port}"

    private object DemoDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val json = MockResponse().setHeader("Content-Type", "application/json")
            return when (request.path?.substringBefore('?')) {
                "/api/v1/profile" -> json.setResponseCode(200)
                    .setBody("""{"id":"u-1","name":"Amina Wanjiku","city":"Nairobi"}""")

                "/api/v1/bookings" -> json.setResponseCode(200)
                    .setBody("""{"bookings":[{"id":"b-1","service":"Plumbing","status":"confirmed"}]}""")

                "/api/v1/notifications" -> json.setResponseCode(200)
                    .setBody("""{"unread":3}""")

                "/api/v1/checkout" -> json.setResponseCode(201)
                    .setBody("""{"orderId":"o-1042","total":"KES 3,500"}""")

                else -> json.setResponseCode(404).setBody("""{"error":"Not found"}""")
            }
        }
    }
}
