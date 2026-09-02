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
 * handlers treat it exactly like a real one. Extra headers make the simulation
 * traceable from a log or a proxy without inspecting NetKit state.
 *
 * A scenario's own headers are applied **last** and may overwrite the ones
 * NetKit derived, because "return `Content-Length: 0` on a body that has one" is
 * a legitimate thing to want to test. The two `X-NetKit-*` markers are added
 * afterwards and cannot be overwritten, so a simulated response is always
 * identifiable as one.
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
            .sentRequestAtMillis(sentAtMillis)
            .receivedResponseAtMillis(receivedAtMillis)
            .body(body)

        // Scenario headers win over the derived ones: `header` replaces rather
        // than appends, so a rule that sets Content-Type gets exactly that.
        decision.headers.forEach { header -> builder.header(header.name, header.value) }

        builder.header(NetKitDefaults.SIMULATED_HEADER, "true")
        decision.ruleId?.let { builder.header(NetKitDefaults.SIMULATED_RULE_HEADER, it) }

        // A HEAD response must not carry a body; returning one makes OkHttp throw.
        if (request.method.equals("HEAD", ignoreCase = true)) {
            builder.body("".toResponseBody(mediaType))
        }
        return builder.build()
    }
}
