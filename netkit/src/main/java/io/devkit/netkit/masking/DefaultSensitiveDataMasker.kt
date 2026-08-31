package io.devkit.netkit.masking

/**
 * The masker NetKit installs unless [io.devkit.netkit.config.NetKitConfig] supplies another.
 *
 * Header and parameter names are matched case-insensitively, because servers and
 * clients disagree about capitalisation (`Authorization`, `authorization`,
 * `AUTHORIZATION` are the same header).
 *
 * A masked `Authorization: Bearer eyJhbGci…` becomes `Bearer ••••••••`: the
 * scheme survives because knowing *which* auth scheme was used is a debugging
 * signal, while the credential itself is not.
 *
 * @param additionalHeaderNames extra header names to redact, e.g. `X-Tenant-Secret`.
 * @param additionalQueryParameters extra query parameter names to redact.
 */
class DefaultSensitiveDataMasker(
    additionalHeaderNames: Set<String> = emptySet(),
    additionalQueryParameters: Set<String> = emptySet(),
) : SensitiveDataMasker {

    private val headerNames: Set<String> =
        (DEFAULT_HEADER_NAMES + additionalHeaderNames).mapTo(mutableSetOf()) { it.lowercase() }

    private val queryParameters: Set<String> =
        (DEFAULT_QUERY_PARAMETERS + additionalQueryParameters).mapTo(mutableSetOf()) { it.lowercase() }

    override fun maskHeaders(headers: List<Pair<String, String>>): List<MaskedHeader> =
        headers.map { (name, value) ->
            if (name.lowercase() in headerNames) {
                MaskedHeader(name, maskCredential(value), masked = true)
            } else {
                MaskedHeader(name, value, masked = false)
            }
        }

    override fun maskUrl(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val fragmentStart = url.indexOf('#', queryStart)
        val queryEnd = if (fragmentStart < 0) url.length else fragmentStart
        val query = url.substring(queryStart + 1, queryEnd)
        if (query.isEmpty()) return url

        var changed = false
        val maskedQuery = query.split('&').joinToString("&") { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) return@joinToString pair
            val name = pair.substring(0, separator)
            if (name.lowercase() !in queryParameters) return@joinToString pair
            changed = true
            "$name=$MASK"
        }
        if (!changed) return url
        return url.substring(0, queryStart + 1) + maskedQuery + url.substring(queryEnd)
    }

    /** Keeps a leading auth scheme (`Bearer`, `Basic`, …) and redacts the rest. */
    private fun maskCredential(value: String): String {
        if (value.isBlank()) return value
        val separator = value.indexOf(' ')
        if (separator <= 0) return MASK
        val scheme = value.substring(0, separator)
        return if (scheme.all { it.isLetter() }) "$scheme $MASK" else MASK
    }

    companion object {
        /** The redaction placeholder used everywhere in NetKit. */
        const val MASK: String = "••••••••"

        /** Headers redacted out of the box. */
        val DEFAULT_HEADER_NAMES: Set<String> = setOf(
            "Authorization",
            "Proxy-Authorization",
            "Cookie",
            "Set-Cookie",
            "X-Api-Key",
            "Api-Key",
            "X-Auth-Token",
            "X-Csrf-Token",
        )

        /** Query parameters redacted out of the box. */
        val DEFAULT_QUERY_PARAMETERS: Set<String> = setOf(
            "access_token",
            "api_key",
            "apikey",
            "auth",
            "id_token",
            "password",
            "refresh_token",
            "secret",
            "token",
        )
    }
}
