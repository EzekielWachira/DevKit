package io.devkit.netkit.history

import io.devkit.netkit.config.NetKitConfig
import io.devkit.netkit.masking.MaskedHeader
import io.devkit.netkit.scenario.RequestTarget
import io.devkit.netkit.scenario.condition.RequestBodyPeek
import io.devkit.netkit.scenario.condition.RequestBodySource
import io.devkit.netkit.scenario.runtime.RequestInspection
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Converts OkHttp types into the transport-independent history model, applying
 * masking on the way in.
 *
 * Body capture is deliberately conservative. NetKit never consumes an
 * application's body: request bodies are re-serialised into a scratch buffer and
 * response bodies are read with `Response.peekBody`, which leaves the original
 * stream intact. Duplex, one-shot, oversized and non-textual bodies are skipped
 * rather than risking a broken upload or an OOM on a large download.
 */
internal class NetworkRecordMapper(private val config: NetKitConfig) {

    /**
     * Extracts the transport-independent target the scenario engine matches on.
     *
     * [inspection] decides how much of the request is captured. The default is
     * [RequestInspection.Minimal] — URL and method only, exactly what 0.1 needed
     * — and the extra work is done only for the conditions that asked for it, so
     * a scenario without header or body conditions costs nothing new here.
     */
    fun toTarget(
        request: Request,
        inspection: RequestInspection = RequestInspection.Minimal,
    ): RequestTarget {
        val url = request.url
        return RequestTarget(
            method = request.method,
            scheme = url.scheme,
            host = url.host,
            port = url.port,
            path = url.encodedPath,
            encodedQuery = url.encodedQuery,
            headers = if (inspection.headers) headerPairs(request.headers) else emptyList(),
            bodySource = if (inspection.body) bodySource(request) else RequestBodySource.Absent,
        )
    }

    /**
     * Bounded, non-destructive access to the request body for a body condition.
     *
     * Reuses the same safety rules as history capture, and for the same reason:
     * NetKit must never be the thing that broke an upload. A duplex or one-shot
     * body is refused outright — those can only be written once, and buffering
     * one to evaluate a debug condition would leave nothing for the server. A
     * body past the condition limit is refused rather than copied. Everything
     * else is written into a scratch buffer, leaving the original untouched.
     *
     * The lambda is not invoked unless a body condition actually asks.
     */
    private fun bodySource(request: Request): RequestBodySource = RequestBodySource { maxBytes ->
        val body = request.body ?: return@RequestBodySource RequestBodyPeek.Absent
        if (body.isDuplex() || body.isOneShot()) {
            return@RequestBodySource unavailable(RequestBodyPeek.Unavailable.Reason.NOT_REPEATABLE)
        }
        val declared = safeContentLength(body)
        if (declared > maxBytes) {
            return@RequestBodySource unavailable(RequestBodyPeek.Unavailable.Reason.TOO_LARGE)
        }
        val charset = charsetOrNull(body.contentType())
            ?: return@RequestBodySource unavailable(RequestBodyPeek.Unavailable.Reason.BINARY)
        try {
            val buffer = Buffer()
            body.writeTo(buffer)
            if (buffer.size > maxBytes) {
                // A chunked body that could not declare its length gets past the
                // check above, so the real size is checked again after writing.
                unavailable(RequestBodyPeek.Unavailable.Reason.TOO_LARGE)
            } else {
                RequestBodyPeek.Text(buffer.readString(charset))
            }
        } catch (error: Throwable) {
            // `writeTo` is application code and can throw anything at all.
            unavailable(RequestBodyPeek.Unavailable.Reason.UNREADABLE)
        }
    }

    private fun unavailable(reason: RequestBodyPeek.Unavailable.Reason): RequestBodyPeek =
        RequestBodyPeek.Unavailable(reason)

    /** The raw header pairs a condition matches against, in wire order. */
    private fun headerPairs(headers: Headers): List<Pair<String, String>> {
        if (headers.size == 0) return emptyList()
        val pairs = ArrayList<Pair<String, String>>(headers.size)
        for (index in 0 until headers.size) {
            pairs += headers.name(index) to headers.value(index)
        }
        return pairs
    }

    /** Masks the URL so credential-bearing query parameters never reach history. */
    fun maskedUrl(request: Request): String = config.masker.maskUrl(request.url.toString())

    fun maskHeaders(headers: Headers): List<MaskedHeader> {
        if (headers.size == 0) return emptyList()
        val pairs = ArrayList<Pair<String, String>>(headers.size)
        for (index in 0 until headers.size) {
            pairs += headers.name(index) to headers.value(index)
        }
        return config.masker.maskHeaders(pairs)
    }

    /** Returns a preview of the outgoing body, or `null` when it cannot be read safely. */
    fun requestBodyPreview(request: Request): BodyPreview? {
        if (!config.captureBodies) return null
        val body = request.body ?: return null
        if (body.isDuplex() || body.isOneShot()) return null
        val declaredLength = safeContentLength(body)
        if (declaredLength > config.maxBodyPreviewBytes) {
            return BodyPreview(SKIPPED_TOO_LARGE, truncated = true, byteCount = declaredLength)
        }
        val charset = charsetOrNull(body.contentType()) ?: return null
        return try {
            val buffer = Buffer()
            body.writeTo(buffer)
            val bytes = buffer.size
            if (bytes > config.maxBodyPreviewBytes) {
                // A body that reports no content length (chunked, or a writer that
                // cannot say) gets past the declared-size check, so the real size
                // has to be checked again after writing.
                BodyPreview(SKIPPED_TOO_LARGE, truncated = true, byteCount = bytes)
            } else {
                BodyPreview(text = buffer.readString(charset), truncated = false, byteCount = bytes)
            }
        } catch (error: Throwable) {
            // `writeTo` is application code — a Moshi adapter, a protobuf writer,
            // a hand-rolled body — and it can throw anything at all. Capturing a
            // preview is a NetKit convenience; letting it escape would turn a
            // successful request into a crash on an OkHttp thread and leak the
            // response that was about to be returned.
            BodyPreview(
                SKIPPED_UNREADABLE,
                truncated = false,
                byteCount = declaredLength.coerceAtLeast(0),
            )
        }
    }

    /**
     * Returns a preview of the incoming body without consuming it.
     * The caller keeps using [response] exactly as before.
     */
    fun responseBodyPreview(response: Response): BodyPreview? {
        if (!config.captureBodies) return null
        val charset = charsetOrNull(response.body.contentType()) ?: return null
        return try {
            val peeked = response.peekBody(config.maxBodyPreviewBytes)
            val bytes = peeked.bytes()
            val declared = response.body.contentLength()
            BodyPreview(
                text = String(bytes, charset),
                truncated = bytes.size.toLong() >= config.maxBodyPreviewBytes,
                byteCount = if (declared >= 0) declared else bytes.size.toLong(),
            )
        } catch (error: Throwable) {
            // Same reasoning as the request side: a peek that fails must cost a
            // preview, never the response.
            null
        }
    }

    /** A preview for a body NetKit itself produced; always safe to keep verbatim. */
    fun simulatedBodyPreview(body: String): BodyPreview {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val limit = config.maxBodyPreviewBytes
        val truncated = bytes.size > limit
        val text = if (truncated) String(bytes, 0, limit.toInt(), StandardCharsets.UTF_8) else body
        return BodyPreview(text = text, truncated = truncated, byteCount = bytes.size.toLong())
    }

    private fun safeContentLength(body: RequestBody): Long = try {
        body.contentLength()
    } catch (error: Throwable) {
        -1L
    }

    /**
     * The charset to decode a body with, or `null` when the payload is binary.
     * A missing content type is treated as binary: guessing would risk turning
     * a protobuf or image payload into a screenful of replacement characters.
     */
    private fun charsetOrNull(contentType: MediaType?): Charset? {
        if (contentType == null) return null
        val type = contentType.type.lowercase()
        val subtype = contentType.subtype.lowercase()
        val textual = type == "text" ||
            subtype == "json" ||
            subtype == "xml" ||
            subtype == "html" ||
            subtype == "x-www-form-urlencoded" ||
            subtype.endsWith("+json") ||
            subtype.endsWith("+xml")
        if (!textual) return null
        return contentType.charset(StandardCharsets.UTF_8)
    }

    private companion object {
        const val SKIPPED_TOO_LARGE = "[NetKit] body not captured — larger than the preview limit"
        const val SKIPPED_UNREADABLE = "[NetKit] body not captured — could not be read safely"
    }
}
