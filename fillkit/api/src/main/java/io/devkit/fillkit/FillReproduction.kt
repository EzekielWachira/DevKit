package io.devkit.fillkit

/**
 * The single pure-data description of a reproducible FillKit state. The developer
 * panel, the QA launcher, Compose tests and deep links all converge on this type.
 */
data class FillReproductionSpec(
    val formId: String,
    val seed: Long,
    val generation: Int = 0,
    val locale: String? = null,
    /** Resolved locale pack coordinate, e.g. `builtin-en-ke@1`. */
    val localePack: String? = null,
    val scenarioPackId: String? = null,
    val scenarioId: String? = null,
    val personaPackId: String? = null,
    val personaId: String? = null,
    val configurationFingerprint: String? = null,
    /**
     * Per-field regeneration counters, non-zero entries only. Filled in when a
     * developer or QA engineer rerolled individual fields from the overlay, so
     * that exploration stays reproducible without bloating the common case.
     */
    val fieldGenerations: Map<String, Int> = emptyMap(),
    val version: Int = VERSION,
) {
    init {
        require(formId.isNotBlank()) { "reproduction formId cannot be blank" }
        require(FillKitSeed.isValid(seed)) { "reproduction seed must be in ${FillKitSeed.MIN}..${FillKitSeed.MAX}" }
        require(generation in 0..MAX_GENERATION) { "reproduction generation must be in 0..$MAX_GENERATION" }
        require(version in 1..VERSION) { "unsupported reproduction spec version: $version" }
        require(fieldGenerations.values.all { it in 1..MAX_GENERATION }) {
            "field generations must be in 1..$MAX_GENERATION; omit zeros"
        }
        require(fieldGenerations.keys.none(String::isBlank)) { "field generation keys cannot be blank" }
    }

    /** Human-readable block intended for bug reports. Never contains generated values. */
    fun describe(token: String? = FillReproductionTokenCodec.encodeOrNull(this)): String = buildString {
        appendLine("FillKit Reproduction")
        appendLine("form=$formId")
        scenarioId?.let { appendLine("scenario=${qualify(scenarioPackId, it)}") }
        appendLine("persona=${personaId?.let { qualify(personaPackId, it) } ?: "random"}")
        locale?.let { appendLine("locale=$it") }
        localePack?.let { appendLine("localePack=$it") }
        appendLine("seed=$seed")
        appendLine("generation=$generation")
        if (fieldGenerations.isNotEmpty()) {
            appendLine("fields=" + fieldGenerations.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" })
        }
        configurationFingerprint?.let { appendLine("config=$it") }
        token?.let { append("token=$it") }
    }.trimEnd()

    companion object {
        const val VERSION: Int = 1
        const val MAX_GENERATION: Int = 100_000

        private fun qualify(packId: String?, id: String) = if (packId == null) id else "$packId/$id"
    }
}

/** Why a token could not be decoded. Surfaced instead of a raw parse exception. */
enum class FillTokenError {
    Blank,
    TooLong,
    MissingPrefix,
    UnsupportedVersion,
    MalformedBody,
    ChecksumMismatch,
    MissingField,
    InvalidField,
}

class FillReproductionTokenException(
    val error: FillTokenError,
    message: String,
) : IllegalArgumentException("FillKit reproduction token rejected ($error): $message")

/**
 * Compact, versioned, URL-safe encoding of a [FillReproductionSpec].
 *
 * This is a debug configuration descriptor, not a secret: it carries identifiers,
 * a seed and a generation counter, never generated field values.
 */
object FillReproductionTokenCodec {
    const val PREFIX: String = "FK"
    const val MAX_TOKEN_LENGTH: Int = 512
    private const val MAX_ID_LENGTH = 96
    private const val MAX_LOCALE_LENGTH = 24

    // `@` is allowed so pack coordinates such as `builtin-en-ke@1` round-trip.
    private val idPattern = Regex("[A-Za-z0-9._:@-]{1,$MAX_ID_LENGTH}")
    private val localePattern = Regex("[A-Za-z0-9_-]{1,$MAX_LOCALE_LENGTH}")
    private val fieldGenerationsPattern = Regex("[A-Za-z0-9._:,-]{1,256}")

    fun encode(spec: FillReproductionSpec): String {
        val payload = buildString {
            field("f", spec.formId)
            field("s", spec.seed.toString())
            field("g", spec.generation.toString())
            spec.locale?.let { field("l", it) }
            spec.localePack?.let { field("lp", it) }
            spec.scenarioPackId?.let { field("sp", it) }
            spec.scenarioId?.let { field("sc", it) }
            spec.personaPackId?.let { field("pp", it) }
            spec.personaId?.let { field("p", it) }
            spec.configurationFingerprint?.let { field("c", it) }
            if (spec.fieldGenerations.isNotEmpty()) {
                field("fg", spec.fieldGenerations.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" })
            }
        }
        val token = "$PREFIX${spec.version}-${Base64Url.encode(payload)}-${checksum(payload)}"
        if (token.length > MAX_TOKEN_LENGTH) {
            throw FillReproductionTokenException(FillTokenError.TooLong, "encoded token exceeds $MAX_TOKEN_LENGTH characters")
        }
        return token
    }

    fun encodeOrNull(spec: FillReproductionSpec): String? = runCatching { encode(spec) }.getOrNull()

    fun decode(token: String?): FillReproductionSpec {
        val trimmed = token?.trim().orEmpty()
        if (trimmed.isEmpty()) throw FillReproductionTokenException(FillTokenError.Blank, "token is empty")
        if (trimmed.length > MAX_TOKEN_LENGTH) {
            throw FillReproductionTokenException(FillTokenError.TooLong, "token exceeds $MAX_TOKEN_LENGTH characters")
        }
        if (!trimmed.startsWith(PREFIX)) {
            throw FillReproductionTokenException(FillTokenError.MissingPrefix, "token must start with \"$PREFIX\"")
        }
        val parts = trimmed.split('-')
        if (parts.size != 3) {
            throw FillReproductionTokenException(FillTokenError.MalformedBody, "expected ${PREFIX}<version>-<body>-<checksum>")
        }
        val version = parts[0].removePrefix(PREFIX).toIntOrNull()
            ?: throw FillReproductionTokenException(FillTokenError.UnsupportedVersion, "version is not a number")
        if (version !in 1..FillReproductionSpec.VERSION) {
            throw FillReproductionTokenException(
                FillTokenError.UnsupportedVersion,
                "version $version is newer than the supported version ${FillReproductionSpec.VERSION}",
            )
        }
        val payload = Base64Url.decodeOrNull(parts[1])
            ?: throw FillReproductionTokenException(FillTokenError.MalformedBody, "body is not valid base64url")
        if (checksum(payload) != parts[2]) {
            throw FillReproductionTokenException(FillTokenError.ChecksumMismatch, "token is corrupt or truncated")
        }
        return parse(payload, version)
    }

    fun decodeOrNull(token: String?): FillReproductionSpec? = runCatching { decode(token) }.getOrNull()

    private fun parse(payload: String, version: Int): FillReproductionSpec {
        val fields = linkedMapOf<String, String>()
        payload.split(';').filter(String::isNotEmpty).forEach { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) throw FillReproductionTokenException(FillTokenError.MalformedBody, "malformed entry \"$entry\"")
            fields[entry.take(separator)] = entry.substring(separator + 1)
        }
        val formId = fields["f"] ?: throw FillReproductionTokenException(FillTokenError.MissingField, "missing form id")
        val seed = fields["s"]?.toLongOrNull()
            ?: throw FillReproductionTokenException(FillTokenError.MissingField, "missing seed")
        if (!FillKitSeed.isValid(seed)) {
            throw FillReproductionTokenException(FillTokenError.InvalidField, "seed $seed is out of range")
        }
        val generation = fields["g"]?.toIntOrNull() ?: 0
        if (generation !in 0..FillReproductionSpec.MAX_GENERATION) {
            throw FillReproductionTokenException(FillTokenError.InvalidField, "generation $generation is out of range")
        }
        return FillReproductionSpec(
            formId = id(formId, "form"),
            seed = seed,
            generation = generation,
            locale = fields["l"]?.also { validate(localePattern, it, "locale") },
            localePack = fields["lp"]?.let { id(it, "locale pack") },
            scenarioPackId = fields["sp"]?.let { id(it, "scenario pack") },
            scenarioId = fields["sc"]?.let { id(it, "scenario") },
            personaPackId = fields["pp"]?.let { id(it, "persona pack") },
            personaId = fields["p"]?.let { id(it, "persona") },
            configurationFingerprint = fields["c"]?.let { id(it, "fingerprint") },
            fieldGenerations = fields["fg"]?.let(::fieldGenerations).orEmpty(),
            version = version,
        )
    }

    private fun StringBuilder.field(key: String, value: String) {
        val pattern = when (key) {
            "l" -> localePattern
            "fg" -> fieldGenerationsPattern
            else -> idPattern
        }
        validate(pattern, value, key)
        append(key).append('=').append(value).append(';')
    }

    private fun fieldGenerations(value: String): Map<String, Int> {
        validate(fieldGenerationsPattern, value, "field generations")
        return value.split(',').filter(String::isNotEmpty).associate { entry ->
            val separator = entry.lastIndexOf(':')
            if (separator <= 0) {
                throw FillReproductionTokenException(FillTokenError.InvalidField, "malformed field generation \"$entry\"")
            }
            val id = id(entry.take(separator), "field")
            val generation = entry.substring(separator + 1).toIntOrNull()
                ?.takeIf { it in 1..FillReproductionSpec.MAX_GENERATION }
                ?: throw FillReproductionTokenException(
                    FillTokenError.InvalidField,
                    "field generation for \"$id\" is out of range",
                )
            id to generation
        }
    }

    private fun id(value: String, label: String): String {
        validate(idPattern, value, label)
        return value
    }

    private fun validate(pattern: Regex, value: String, label: String) {
        if (!pattern.matches(value)) {
            throw FillReproductionTokenException(FillTokenError.InvalidField, "$label \"$value\" is not a valid identifier")
        }
    }

    private fun checksum(payload: String): String =
        java.lang.Long.toHexString(FillSeedDerivation.hash64(payload)).padStart(16, '0').take(8)
}

/** Minimal, allocation-light base64url without padding; avoids an API 26 dependency. */
internal object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val reverse = IntArray(128) { -1 }.apply {
        ALPHABET.forEachIndexed { index, char -> this[char.code] = index }
    }

    fun encode(value: String): String {
        val bytes = value.encodeToByteArray()
        return buildString((bytes.size * 4 + 2) / 3) {
            var index = 0
            while (index < bytes.size) {
                val remaining = bytes.size - index
                val b0 = bytes[index].toInt() and 0xFF
                val b1 = if (remaining > 1) bytes[index + 1].toInt() and 0xFF else 0
                val b2 = if (remaining > 2) bytes[index + 2].toInt() and 0xFF else 0
                append(ALPHABET[b0 ushr 2])
                append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                if (remaining > 1) append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
                if (remaining > 2) append(ALPHABET[b2 and 0x3F])
                index += 3
            }
        }
    }

    fun decodeOrNull(value: String): String? {
        if (value.isEmpty()) return null
        val output = ArrayList<Byte>(value.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        value.forEach { char ->
            val digit = if (char.code < 128) reverse[char.code] else -1
            if (digit < 0) return null
            buffer = (buffer shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output += ((buffer ushr bits) and 0xFF).toByte()
            }
        }
        return runCatching { output.toByteArray().decodeToString() }.getOrNull()
    }
}
