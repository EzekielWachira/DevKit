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

                // Paginated, so the pagination scenarios have something real to
                // fail against: NetKit simulates page 2, and page 1 and 3 come
                // from here, which is what makes the difference visible.
                "/api/v1/services" -> json.setResponseCode(200).setBody(servicesPage(request))

                "/api/v1/auth/refresh" -> json.setResponseCode(200).setBody(
                    """{"access_token":"demo-token-2","token_type":"Bearer","expires_in":3600}""",
                )

                else -> json.setResponseCode(404).setBody("""{"error":"Not found"}""")
            }
        }

        /**
         * Three real pages of services.
         *
         * The pagination scenarios simulate page 2; pages 1 and 3 come from here.
         * That contrast is the whole demonstration — a scenario that replaced
         * every page would not show anything a plain "always fails" rule could
         * not.
         */
        private fun servicesPage(request: RecordedRequest): String {
            val page = request.path
                ?.substringAfter("page=", "1")
                ?.substringBefore('&')
                ?.toIntOrNull()
                ?: 1
            if (page < 1 || page > TOTAL_PAGES) {
                return """{"data":[],"next_page":null,"has_more":false}"""
            }
            val items = (1..2).joinToString(",") { offset ->
                val id = (page - 1) * 2 + offset
                """{"id":"s-$id","name":"Service $id"}"""
            }
            val next = if (page < TOTAL_PAGES) (page + 1).toString() else "null"
            return """{"data":[$items],"next_page":$next,"has_more":${page < TOTAL_PAGES}}"""
        }

        private const val TOTAL_PAGES = 3
    }
}
