package io.devkit.netkit.interceptor

import io.devkit.netkit.config.NetKitConfig
import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.engine.NetworkScenarioEngine
import io.devkit.netkit.engine.ScenarioDecision
import io.devkit.netkit.history.BodyPreview
import io.devkit.netkit.history.NetworkHistoryStore
import io.devkit.netkit.history.NetworkOutcome
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.history.NetworkRecordMapper
import io.devkit.netkit.history.NetworkRecordSource
import io.devkit.netkit.masking.MaskedHeader
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.util.NetKitClock
import io.devkit.netkit.util.Sleeper
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The OkHttp binding.
 *
 * The interceptor is deliberately thin: it maps an OkHttp [Request] to the
 * transport-independent request model, asks [NetworkScenarioEngine] what to do,
 * carries out that single decision, and records what happened. All matching and
 * precedence logic lives in the engine, so it can be tested — and later reused
 * for other transports — without OkHttp.
 *
 * Install it as an **application** interceptor:
 *
 * ```kotlin
 * OkHttpClient.Builder().addInterceptor(netKit.interceptor).build()
 * ```
 *
 * As an application interceptor it sees one entry per logical call, so redirects
 * and retries do not produce duplicate history rows, and a simulated response
 * short-circuits before any connection is opened.
 *
 * Thread safety: this class holds no mutable state of its own; every per-request
 * value is a local. It is safe to share one instance across clients and threads.
 */
class NetKitInterceptor internal constructor(
    private val engine: NetworkScenarioEngine,
    private val history: NetworkHistoryStore,
    private val config: NetKitConfig,
    private val mapper: NetworkRecordMapper = NetworkRecordMapper(config),
    private val clock: NetKitClock = NetKitClock.System,
    private val sleeper: Sleeper = Sleeper.Thread,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val decision = decide(request)

        // Fast path: nothing to simulate and nothing to record.
        if (!config.historyEnabled && decision is ScenarioDecision.PassThrough) {
            return chain.proceed(request)
        }
        return executeAndRecord(chain, request, decision)
    }

    /**
     * A scenario that cannot be evaluated must never take the application's
     * network down with it, so any unexpected failure degrades to pass-through.
     */
    private fun decide(request: Request): ScenarioDecision = try {
        if (engine.isIdle) {
            IDLE_DECISION
        } else {
            engine.evaluate(mapper.toTarget(request))
        }
    } catch (error: RuntimeException) {
        IDLE_DECISION
    }

    private fun executeAndRecord(
        chain: Interceptor.Chain,
        request: Request,
        decision: ScenarioDecision,
    ): Response {
        val startedAtMillis = clock.nowMillis()
        val startedAtNanos = clock.elapsedNanos()
        val recorder = Recorder(request, decision, startedAtMillis)

        try {
            return when (decision) {
                is ScenarioDecision.PassThrough -> proceed(chain, request, recorder, startedAtNanos)

                is ScenarioDecision.Delay -> {
                    sleeper.sleep(decision.delayMillis)
                    proceed(chain, request, recorder, startedAtNanos)
                }

                is ScenarioDecision.RespondWith -> {
                    sleeper.sleep(decision.delayMillis)
                    val response = SimulatedResponseFactory.build(
                        request = request,
                        decision = decision,
                        sentAtMillis = startedAtMillis,
                        receivedAtMillis = clock.nowMillis(),
                    )
                    recorder.recordSimulatedResponse(response, decision, elapsedMillis(startedAtNanos))
                    response
                }

                is ScenarioDecision.FailOffline -> throw offlineFailure(request)

                is ScenarioDecision.FailTimeout -> {
                    sleeper.sleep(timeoutDelayMillis(chain, decision.type))
                    throw timeoutFailure(decision.type)
                }
            }
        } catch (error: IOException) {
            recorder.recordFailure(error, elapsedMillis(startedAtNanos))
            throw error
        }
    }

    private fun proceed(
        chain: Interceptor.Chain,
        request: Request,
        recorder: Recorder,
        startedAtNanos: Long,
    ): Response {
        val response = chain.proceed(request)
        recorder.recordRealResponse(response, elapsedMillis(startedAtNanos))
        return response
    }

    /**
     * How long a simulated timeout blocks. By default NetKit honours the
     * client's own connect/read timeout so the caller waits exactly as long as a
     * real timeout would; `NetKitConfig.simulatedTimeoutDelayMillis` overrides
     * that, which is what keeps tests fast.
     */
    private fun timeoutDelayMillis(chain: Interceptor.Chain, type: TimeoutType): Long {
        config.simulatedTimeoutDelayMillis?.let { return it }
        val configured = when (type) {
            TimeoutType.CONNECT -> chain.connectTimeoutMillis()
            TimeoutType.READ -> chain.readTimeoutMillis()
        }.toLong()
        val effective = if (configured <= 0) NetKitDefaults.FALLBACK_TIMEOUT_MILLIS else configured
        return effective.coerceAtMost(NetKitDefaults.MAX_SIMULATED_TIMEOUT_MILLIS)
    }

    private fun offlineFailure(request: Request): IOException =
        UnknownHostException("NetKit offline scenario: unable to resolve host ${request.url.host}")

    private fun timeoutFailure(type: TimeoutType): IOException = when (type) {
        TimeoutType.CONNECT -> SocketTimeoutException("NetKit simulated connect timeout")
        TimeoutType.READ -> SocketTimeoutException("NetKit simulated read timeout")
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (clock.elapsedNanos() - startedAtNanos) / NANOS_PER_MILLI

    /**
     * Builds the history entry for one request.
     *
     * Request-side fields are captured lazily on the first record call so a
     * pass-through with history disabled never touches headers or bodies.
     */
    private inner class Recorder(
        private val request: Request,
        private val decision: ScenarioDecision,
        private val startedAtMillis: Long,
    ) {
        fun recordRealResponse(response: Response, durationMillis: Long) {
            if (!config.historyEnabled) return
            store(
                durationMillis = durationMillis,
                outcome = NetworkOutcome.Completed(response.code, response.message),
                responseHeaders = mapper.maskHeaders(response.headers),
                responseBody = mapper.responseBodyPreview(response),
                source = NetworkRecordSource.REAL,
            )
        }

        fun recordSimulatedResponse(
            response: Response,
            decision: ScenarioDecision.RespondWith,
            durationMillis: Long,
        ) {
            if (!config.historyEnabled) return
            store(
                durationMillis = durationMillis,
                outcome = NetworkOutcome.Completed(response.code, response.message),
                responseHeaders = mapper.maskHeaders(response.headers),
                responseBody = mapper.simulatedBodyPreview(decision.body),
                source = NetworkRecordSource.SIMULATED,
            )
        }

        fun recordFailure(error: IOException, durationMillis: Long) {
            if (!config.historyEnabled) return
            store(
                durationMillis = durationMillis,
                outcome = NetworkOutcome.Failed(
                    kind = error.javaClass.simpleName,
                    message = error.message,
                ),
                responseHeaders = emptyList(),
                responseBody = null,
                source = if (decision.isSimulated) {
                    NetworkRecordSource.SIMULATED
                } else {
                    NetworkRecordSource.REAL
                },
            )
        }

        private fun store(
            durationMillis: Long,
            outcome: NetworkOutcome,
            responseHeaders: List<MaskedHeader>,
            responseBody: BodyPreview?,
            source: NetworkRecordSource,
        ) {
            val url = request.url
            history.record(
                NetworkRecord(
                    id = history.nextRecordId(),
                    startedAtMillis = startedAtMillis,
                    durationMillis = durationMillis,
                    method = request.method,
                    scheme = url.scheme,
                    host = url.host,
                    path = url.encodedPath,
                    url = mapper.maskedUrl(request),
                    requestHeaders = mapper.maskHeaders(request.headers),
                    requestBody = mapper.requestBodyPreview(request),
                    outcome = outcome,
                    responseHeaders = responseHeaders,
                    responseBody = responseBody,
                    source = source,
                    scenarioLabel = decision.scenarioLabel,
                    ruleId = decision.ruleId,
                ),
            )
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        val IDLE_DECISION = ScenarioDecision.PassThrough()
    }
}
