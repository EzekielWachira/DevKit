package io.devkit.netkit.interceptor

import io.devkit.netkit.config.NetKitDefaults
import io.devkit.netkit.engine.ScenarioDecision
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Builds the synthetic [Response] returned for a
 * [ScenarioDecision.RespondWith] decision.
 *
 * The response carries every field OkHttp and typical clients expect — protocol,
 * originating request, reason phrase, content type, `Content-Length`, and the
 * request/response timestamps — so Retrofit converters, caching layers and error
 * handlers treat it exactly like a real one. Two extra headers make the
 * simulation traceable from a log or a proxy without inspecting NetKit state.
 */
internal object SimulatedResponseFactory {

    fun build(
        request: Request,
        decision: ScenarioDecision.RespondWith,
        sentAtMillis: Long,
        receivedAtMillis: Long,
    ): Response {
        val mediaType = decision.contentType.toMediaTypeOrNull()
        val body = decision.body.toResponseBody(mediaType)
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(decision.statusCode)
            .message(NetKitDefaults.statusMessage(decision.statusCode))
            .addHeader("Content-Type", decision.contentType)
            .addHeader("Content-Length", body.contentLength().toString())
            .addHeader(NetKitDefaults.SIMULATED_HEADER, "true")
            .sentRequestAtMillis(sentAtMillis)
            .receivedResponseAtMillis(receivedAtMillis)
            .body(body)

        decision.ruleId?.let { builder.addHeader(NetKitDefaults.SIMULATED_RULE_HEADER, it) }

        // A HEAD response must not carry a body; returning one makes OkHttp throw.
        if (request.method.equals("HEAD", ignoreCase = true)) {
            builder.body("".toResponseBody(mediaType))
        }
        return builder.build()
    }
}
