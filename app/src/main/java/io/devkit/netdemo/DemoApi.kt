package io.devkit.netdemo

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/** One endpoint the demo screen can call. */
data class DemoEndpoint(
    val id: String,
    val method: String,
    val path: String,
    val description: String,
)

/** The outcome of one demo call, as the application experienced it. */
sealed interface DemoResult {
    data object Idle : DemoResult
    data object Loading : DemoResult
    data class Success(val statusCode: Int, val body: String, val durationMillis: Long) : DemoResult
    data class Failure(val kind: String, val message: String, val durationMillis: Long) : DemoResult
}

/**
 * The sample application's networking layer.
 *
 * Ordinary production code: it knows nothing about NetKit and only asks
 * [DebugNetworking] for whatever interceptors this build type provides. That is
 * the point — attaching NetKit does not change how an app is written.
 */
object DemoApi {

    val endpoints: List<DemoEndpoint> = listOf(
        DemoEndpoint("profile", "GET", "/api/v1/profile", "Loads the signed-in user"),
        DemoEndpoint("bookings", "GET", "/api/v1/bookings", "Lists upcoming bookings"),
        DemoEndpoint("notifications", "GET", "/api/v1/notifications", "Polls for notifications"),
        DemoEndpoint("checkout", "POST", "/api/v1/checkout", "Submits an order"),
        // Added for the 0.3 demo packs: pagination presets need a paginated
        // endpoint to target, and the auth presets need a refresh endpoint that
        // is distinguishable from the protected ones.
        DemoEndpoint("services-1", "GET", "/api/v1/services?page=1", "Loads page 1 of services"),
        DemoEndpoint("services-2", "GET", "/api/v1/services?page=2", "Loads page 2 of services"),
        DemoEndpoint("refresh", "POST", "/api/v1/auth/refresh", "Exchanges the refresh token"),
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply { DebugNetworking.interceptors.forEach(::addInterceptor) }
            .proxySelector(LoopbackAwareProxySelector)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Sends loopback traffic direct and everything else through the platform's
     * proxy.
     *
     * A device with a debugging proxy configured (Charles, Proxyman, an emulator
     * `http_proxy` setting) otherwise routes the demo's own local backend through
     * that proxy, where it fails to connect. Android does not honour
     * `http.nonProxyHosts`, so the exclusion has to be explicit.
     */
    private object LoopbackAwareProxySelector : ProxySelector() {

        private val platform: ProxySelector? = getDefault()

        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host
            if (host == null || isLoopback(host)) return listOf(Proxy.NO_PROXY)
            return platform?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) {
            platform?.connectFailed(uri, address, failure)
        }

        private fun isLoopback(host: String) =
            host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    }

    /** Executes [endpoint]. Blocking — call it off the main thread. */
    fun call(endpoint: DemoEndpoint): DemoResult {
        val request = Request.Builder()
            .url(DebugNetworking.baseUrl.trimEnd('/') + endpoint.path)
            .header("Authorization", "Bearer demo-access-token-do-not-log")
            .header("Accept", "application/json")
            .apply {
                if (endpoint.method == "POST") {
                    post("""{"cartId":"demo-cart"}""".toRequestBody(JSON))
                }
            }
            .build()

        val startedAt = System.nanoTime()
        return try {
            client.newCall(request).execute().use { response ->
                DemoResult.Success(
                    statusCode = response.code,
                    body = response.body.string().take(240),
                    durationMillis = elapsedMillis(startedAt),
                )
            }
        } catch (error: IOException) {
            DemoResult.Failure(
                kind = error.javaClass.simpleName,
                message = error.message.orEmpty(),
                durationMillis = elapsedMillis(startedAt),
            )
        }
    }

    private fun elapsedMillis(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000

    private val JSON = "application/json".toMediaType()
}
