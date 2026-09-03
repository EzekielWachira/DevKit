package io.devkit.netkit.interceptor

import io.devkit.netkit.config.NetKitConfig
import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.engine.NetworkScenarioEngine
import io.devkit.netkit.engine.ScenarioDecision
import io.devkit.netkit.history.BodyPreview
import io.devkit.netkit.history.NetworkHistoryStore
import io.devkit.netkit.history.NetworkOutcome
import io.devkit.netkit.history.NetworkRecord
import io.devkit.netkit.history.NetworkRecordKind
import io.devkit.netkit.history.NetworkRecordMapper
import io.devkit.netkit.history.NetworkRecordSource
import io.devkit.netkit.masking.MaskedHeader
import io.devkit.netkit.replay.ReplaySnapshotStore
import io.devkit.netkit.replay.ReplayTag
import io.devkit.netkit.replay.ReplayUnavailableReason
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
    private val replaySnapshots: ReplaySnapshotStore? = null,
    private val mapper: NetworkRecordMapper = NetworkRecordMapper(config),
    private val clock: NetKitClock = NetKitClock.System,
    private val sleeper: Sleeper = Sleeper.Thread,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val replayTag = request.tag(ReplayTag::class.java)
        val decision = decide(request, bypass = replayTag?.bypass == true)

        // Fast path: nothing to simulate and nothing to record.
        if (!config.historyEnabled && decision is ScenarioDecision.PassThrough) {
            return chain.proceed(request)
        }
        return executeAndRecord(chain, request, decision, replayTag)
    }

    /**
     * A scenario that cannot be evaluated must never take the application's
     * network down with it, so any unexpected failure degrades to pass-through.
     */
    private fun decide(request: Request, bypass: Boolean): ScenarioDecision = try {
        // A bypassed replay must not consume anything: no evaluation index, no
        // random draw, no rule counter, no sequence step. Short-circuiting here
        // rather than inside the engine is what makes that literally true.
        if (bypass) {
            IDLE_DECISION
        } else {
            // `evaluate` reads the configuration once and short-circuits
            // internally, so this path never sees two different snapshots for one
            // request. `inspection` is read first so headers and the body source
            // are only built when some condition actually needs them.
            engine.evaluate(mapper.toTarget(request, engine.inspection))
        }
    } catch (error: RuntimeException) {
        IDLE_DECISION
    }

    private fun executeAndRecord(
        chain: Interceptor.Chain,
        request: Request,
        decision: ScenarioDecision,
        replayTag: ReplayTag?,
    ): Response {
        val startedAtMillis = clock.nowMillis()
        val startedAtNanos = clock.elapsedNanos()
        val recorder = Recorder(request, decision, startedAtMillis, replayTag)

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

                is ScenarioDecision.FailDisconnect -> throw disconnectFailure(request)

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

    /**
     * The exception a dropped connection produces.
     *
     * A plain [IOException] with the message OkHttp's own socket layer would
     * surface, rather than a NetKit-specific subclass: client code that catches
     * `IOException` and inspects the message — which, for better or worse, plenty
     * does — should behave the same way it would against a real reset.
     *
     * This is an application-layer simulation. Nothing here touches a socket.
     */
    private fun disconnectFailure(request: Request): IOException =
        IOException("NetKit simulated disconnect: connection reset by ${request.url.host}")

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
        private val replayTag: ReplayTag?,
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
                malformed = decision.malformed,
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
            malformed: Boolean = false,
        ) {
            val url = request.url
            val recordId = history.nextRecordId()

            // The unmasked request is kept only here, in memory, and only long
            // enough to stay replayable; the record itself never sees it.
            val replayUnavailable = if (replaySnapshots == null) {
                ReplayUnavailableReason.DISABLED
            } else {
                replaySnapshots.capture(recordId, request)
            }

            history.record(
                NetworkRecord(
                    id = recordId,
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
                    kind = if (replayTag != null) {
                        NetworkRecordKind.REPLAY
                    } else {
                        NetworkRecordKind.LIVE
                    },
                    scenarioLabel = decision.scenarioLabel,
                    ruleId = decision.ruleId,
                    ruleSource = decision.source,
                    sequenceStep = decision.sequence?.step,
                    sequenceStepCount = decision.sequence?.stepCount,
                    malformed = malformed,
                    replayOfRecordId = replayTag?.sourceRecordId,
                    replayUnavailableReason = replayUnavailable,
                ),
            )
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        val IDLE_DECISION = ScenarioDecision.PassThrough()
    }
}
