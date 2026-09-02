package io.devkit.netkit.history

import io.devkit.netkit.config.NetKitConfig
import io.devkit.netkit.masking.MaskedHeader
import io.devkit.netkit.scenario.RequestTarget
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

    /** Extracts the transport-independent target the scenario engine matches on. */
    fun toTarget(request: Request): RequestTarget {
        val url = request.url
        return RequestTarget(
            method = request.method,
            scheme = url.scheme,
            host = url.host,
            port = url.port,
            path = url.encodedPath,
            encodedQuery = url.encodedQuery,
        )
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
