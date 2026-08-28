package io.devkit.fillkit

import kotlin.random.Random

/** A FillKit master seed. Reproduction always starts from one of these. */
@JvmInline
value class FillSeed(val value: Long) {
    override fun toString(): String = value.toString()
}

object FillKitSeed {
    /** Seeds stay in a range that survives a decimal round-trip in a bug report. */
    const val MIN: Long = 0L
    const val MAX: Long = 999_999_999_999L

    fun random(): FillSeed = FillSeed(Random.nextLong(MIN, MAX + 1))

    fun of(value: Long): FillSeed {
        require(value in MIN..MAX) { "FillKit seed must be in $MIN..$MAX" }
        return FillSeed(value)
    }

    fun parseOrNull(text: String?): FillSeed? =
        text?.trim()?.toLongOrNull()?.takeIf { it in MIN..MAX }?.let(::FillSeed)

    fun isValid(value: Long): Boolean = value in MIN..MAX
}

/**
 * Stable hashing behind derived random streams and configuration fingerprints.
 *
 * The algorithm is part of FillKit's reproduction contract: changing it changes
 * every generated value for a given seed, so [ALGORITHM_VERSION] participates in
 * configuration fingerprints.
 */
object FillSeedDerivation {
    const val ALGORITHM_VERSION: Int = 1

    private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 1099511628211L
    private const val SEPARATOR: Byte = 0x1F

    /**
     * Derives one deterministic child stream from a master seed and a stable
     * namespace. Callers pass identifiers (form, field, generator, purpose) rather
     * than positions, so inserting an unrelated field cannot shift another field's
     * stream.
     */
    fun derive(master: Long, generation: Int, vararg namespace: String?): Long {
        var hash = mix(FNV_OFFSET xor master)
        hash = mix(hash xor generation.toLong())
        hash = mix(hash xor ALGORITHM_VERSION.toLong())
        namespace.forEach { part -> hash = mix(hash xor hash64(part.orEmpty())) }
        return hash
    }

    /** Order-sensitive 64-bit hash of the joined parts. */
    fun hash64(vararg parts: String?): Long {
        var hash = FNV_OFFSET
        parts.forEachIndexed { index, part ->
            if (index > 0) hash = fold(hash, SEPARATOR)
            part.orEmpty().encodeToByteArray().forEach { byte -> hash = fold(hash, byte) }
        }
        return hash
    }

    /** Short, stable, printable digest used for configuration fingerprints. */
    fun shortHash(vararg parts: String?): String {
        @Suppress("SpreadOperator")
        val hash = mix(hash64(*parts))
        return java.lang.Long.toHexString(hash).padStart(16, '0').take(12)
    }

    private fun fold(hash: Long, byte: Byte): Long = (hash xor (byte.toLong() and 0xFF)) * FNV_PRIME

    /** SplitMix64 finalizer: cheap avalanche so adjacent namespaces diverge. */
    private fun mix(value: Long): Long {
        var z = value + -7046029254386353131L // 0x9E3779B97F4A7C15
        z = (z xor (z ushr 30)) * -4658895280553007687L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -7723592293110705685L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }
}
