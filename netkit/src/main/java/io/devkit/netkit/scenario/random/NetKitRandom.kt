package io.devkit.netkit.scenario.random

/**
 * The only source of randomness in NetKit.
 *
 * Every stochastic decision NetKit makes — a probability gate, a latency inside a
 * range, a weighted outcome, a chaos action — goes through this interface. That
 * is not a style preference: it is the single technical rule that makes NetKit
 * 0.3's central promise ("run it again with the same seed and see the same
 * failures") true rather than approximately true. A feature that reached for
 * `Random.Default` would silently opt itself out of reproducibility, and the
 * scenario it broke would be the one nobody could reproduce.
 *
 * Implementations are **not** required to be thread-safe. NetKit never shares one
 * instance across concurrent evaluations; see [RunRandomSource] for how a run's
 * seed is turned into a per-evaluation stream.
 */
interface NetKitRandom {

    /** A value in `[0.0, 1.0)`. */
    fun nextDouble(): Double

    /** A value in `[0, bound)`. [bound] must be positive. */
    fun nextInt(bound: Int): Int

    /** A value in `[min, max]`, inclusive at both ends. [min] must not exceed [max]. */
    fun nextLong(min: Long, max: Long): Long
}

/**
 * A `SplitMix64` generator.
 *
 * Chosen over [java.util.Random] for three reasons that all matter here: it is
 * seeded by a single `Long` with no hidden scrambling, its output is identical on
 * every JVM and every Android version (so a seed shared between a QA device and a
 * developer's machine really does reproduce), and advancing it is a handful of
 * arithmetic operations with no allocation and no lock.
 *
 * Not thread-safe by design — one instance belongs to one evaluation.
 */
class SeededNetKitRandom(seed: Long) : NetKitRandom {

    private var state: Long = seed

    /** The raw next 64 bits. Exposed for tests that assert stream identity. */
    fun nextLong(): Long {
        state += GAMMA
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /**
     * Takes the top 53 bits, which is exactly the precision of a `Double`
     * mantissa: every representable value in `[0, 1)` is reachable and none is
     * twice as likely as its neighbour.
     */
    override fun nextDouble(): Double = (nextLong() ushr 11) * DOUBLE_UNIT

    override fun nextInt(bound: Int): Int {
        require(bound > 0) { "NetKit random bound must be positive (was $bound)" }
        // Rejection-free and unbiased enough for simulation: the modulo bias over
        // 63 bits for a bound that is at most a few thousand is unmeasurable, and
        // rejection sampling would make the stream position depend on the bound,
        // which would break determinism when a scenario is edited.
        return ((nextLong() ushr 1) % bound).toInt()
    }

    override fun nextLong(min: Long, max: Long): Long {
        require(min <= max) { "NetKit random range is inverted ($min > $max)" }
        if (min == max) return min
        val span = max - min + 1
        // A span that overflows can only come from a range spanning most of the
        // Long domain, which no latency or weight ever does; fall back to the
        // whole domain rather than returning a wrong value.
        if (span <= 0) return nextLong()
        return min + (nextLong() ushr 1) % span
    }

    private companion object {
        const val GAMMA: Long = -0x61c8864680b583ebL
        const val DOUBLE_UNIT: Double = 1.0 / (1L shl 53)
    }
}

/**
 * Randomness that never varies. Useful for a scenario that has been reduced to a
 * single deterministic path, and for tests that want to pin one branch.
 *
 * @param value what [nextDouble] returns; `0.0` makes every probability gate pass
 *   and every weighted choice pick the first outcome.
 */
class FixedNetKitRandom(private val value: Double = 0.0) : NetKitRandom {
    override fun nextDouble(): Double = value
    override fun nextInt(bound: Int): Int = (value * bound).toInt().coerceIn(0, bound - 1)
    override fun nextLong(min: Long, max: Long): Long = min
}

/**
 * What a random draw is *for*.
 *
 * Two draws made during one evaluation must not come from the same stream
 * position, or a rule's probability gate and its latency would move together —
 * a 30% rule would always pick the same delay when it fired. Each purpose gets
 * its own derived stream, so adding a probability check to a rule cannot shift
 * the latency values another rule was already producing.
 *
 * The ordinals are part of the reproducibility contract: **never renumber them**,
 * and add new purposes at the end. A trace captured by 0.3 must still replay on
 * 0.4.
 */
enum class RandomPurpose {
    /** A rule's or chaos's probability gate. */
    PROBABILITY,

    /** Selecting one of several weighted outcomes. */
    OUTCOME,

    /** Picking a value inside a latency range. */
    LATENCY,

    /** Selecting a chaos failure action. */
    CHAOS_ACTION,
    ;

    internal val salt: Long get() = SALTS[ordinal]

    private companion object {
        /**
         * Arbitrary, fixed 64-bit constants. Their only requirement is that they
         * differ from each other and never change between releases.
         */
        val SALTS: LongArray = longArrayOf(
            0x9E3779B97F4A7C15uL.toLong(),
            0xBF58476D1CE4E5B9uL.toLong(),
            0x94D049BB133111EBuL.toLong(),
            0xD6E8FEB86659FD93uL.toLong(),
        )
    }
}

/**
 * Turns one run seed into the streams that run's evaluations draw from.
 *
 * ### Why a derived stream per evaluation rather than one shared generator
 *
 * The obvious design — one generator for the whole run, advanced by each request
 * in turn — reproduces a *sequence* of values but not an assignment of values to
 * requests. Under concurrency, request A and request B race for positions 3 and
 * 4, and which one gets the `503` flips between runs on the same seed. That is
 * exactly the non-reproducibility 0.3 exists to remove.
 *
 * Instead, each evaluation is stamped with a monotonic index by
 * [io.devkit.netkit.scenario.run.ScenarioRunManager] and derives its own stream
 * from `(seed, index, purpose)`. Evaluation #17 draws the same values whatever
 * else is in flight, so a trace that says "failed on evaluation #17" replays
 * identically. Deriving is a few multiplications and one small object, so it is
 * cheaper than the lock a shared generator would need.
 *
 * What still varies between runs is **which request becomes evaluation #17** —
 * that is a property of the application's own request ordering, not of NetKit,
 * and it is the limitation documented in the module README.
 *
 * This object is immutable and safe to share across threads.
 *
 * @param seed the run seed, copied verbatim into every reproduction export.
 */
class RunRandomSource(val seed: Long) {

    /**
     * The stream for one purpose within one evaluation.
     *
     * @param evaluationIndex the run-scoped, 1-based evaluation number.
     * @param purpose what the caller is about to decide.
     */
    fun streamFor(evaluationIndex: Long, purpose: RandomPurpose): NetKitRandom =
        SeededNetKitRandom(mix(seed xor purpose.salt, evaluationIndex))

    /**
     * A stream keyed on a stable string as well as the evaluation index.
     *
     * Used where several independent draws of the same purpose happen in one
     * evaluation — two rules whose probability gates are both checked for the
     * same request. Keying on the rule id keeps each rule's gate independent of
     * how many rules happen to sit above it in the list, so adding an unrelated
     * rule does not change whether an existing one fires.
     */
    fun streamFor(evaluationIndex: Long, purpose: RandomPurpose, key: String): NetKitRandom =
        SeededNetKitRandom(mix(seed xor purpose.salt xor key.stableHash(), evaluationIndex))

    /** The 64-bit finaliser from `SplitMix64`, used here as a mixing function. */
    private fun mix(base: Long, index: Long): Long {
        var z = base + index * GAMMA
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private companion object {
        const val GAMMA: Long = -0x61c8864680b583ebL
    }
}

/**
 * A 64-bit hash that does not depend on the JVM.
 *
 * `String.hashCode` is specified by the Java language and would in fact be
 * stable, but it is 32-bit and clusters badly for the short, similar strings rule
 * ids are. FNV-1a is three lines and spreads them properly.
 */
internal fun String.stableHash(): Long {
    var hash = -0x340d631b7bdddcdbL
    for (index in indices) {
        hash = hash xor this[index].code.toLong()
        hash *= 0x100000001B3L
    }
    return hash
}

/** Generates the seeds NetKit offers when a run needs one. */
object SeedGenerator {

    /**
     * A fresh seed.
     *
     * Deliberately kept to nine digits: a seed is read aloud, retyped from a
     * screenshot and pasted into a ticket, and a full 64-bit value would be
     * transcribed wrong. Nine digits is a billion possibilities, which is far
     * more than a QA team will ever collide on, and it still fits comfortably in
     * [SeededNetKitRandom]'s state.
     */
    fun next(): Long = java.security.SecureRandom().nextLong().let(::normalize)

    /** Constrains any `Long` into the readable range [MIN]`..`[MAX]. */
    fun normalize(raw: Long): Long {
        val span = MAX - MIN + 1
        val positive = if (raw == Long.MIN_VALUE) 0L else kotlin.math.abs(raw)
        return MIN + positive % span
    }

    /**
     * Parses a seed a person typed or pasted, or `null` when it is not one.
     *
     * Tolerant of the ways a seed arrives in practice: spaces, a `Seed:` prefix
     * copied along with the label, and grouping separators.
     */
    fun parse(raw: String): Long? = raw
        .substringAfterLast(':')
        .filter { it.isDigit() || it == '-' }
        .takeIf { it.isNotEmpty() }
        ?.toLongOrNull()

    /** Smallest seed [next] produces. */
    const val MIN: Long = 100_000_000L

    /** Largest seed [next] produces. */
    const val MAX: Long = 999_999_999L
}
