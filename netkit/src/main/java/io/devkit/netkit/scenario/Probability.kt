package io.devkit.netkit.scenario

import io.devkit.netkit.scenario.random.NetKitRandom

/**
 * A chance in `0.0..1.0`.
 *
 * A value class rather than a bare `Double` because "0.3" is ambiguous every time
 * it appears in a signature — 30% or 0.3%? — and because a probability outside
 * `0..1` is a bug that should be caught where it is written, not where it is
 * drawn against. `NetworkAction`s, chaos and rules all carry one, and mixing a
 * percentage into a place expecting a fraction is exactly the mistake this makes
 * impossible.
 */
@JvmInline
value class Probability(val value: Double) : Comparable<Probability> {

    init {
        require(value in 0.0..1.0 && !value.isNaN()) {
            "NetKit probability must be between 0.0 and 1.0 (was $value)"
        }
    }

    /** True when this gate can never pass. */
    val isNever: Boolean get() = value <= 0.0

    /** True when this gate always passes, so no random draw is needed. */
    val isAlways: Boolean get() = value >= 1.0

    /** `30%` — how the UI and the execution timeline render it. */
    val percentLabel: String get() = "${percent}%"

    /** The chance as a whole percentage, rounded. */
    val percent: Int get() = Math.round(value * 100).toInt()

    /**
     * Draws against this probability.
     *
     * Short-circuits at both ends so a rule with no probability set — the
     * overwhelmingly common case — never touches the random stream. That is not
     * only a performance point: it means adding a `100%` rule to a scenario
     * cannot shift the values every other rule draws.
     */
    fun draw(random: NetKitRandom): Boolean = when {
        isNever -> false
        isAlways -> true
        else -> random.nextDouble() < value
    }

    override fun compareTo(other: Probability): Int = value.compareTo(other.value)

    override fun toString(): String = percentLabel

    companion object {
        /** Always fires. The default for a rule with no probability configured. */
        val ALWAYS: Probability = Probability(1.0)

        /** Never fires. */
        val NEVER: Probability = Probability(0.0)

        /** `Probability.ofPercent(30)` reads better than `Probability(0.3)` at a call site. */
        fun ofPercent(percent: Int): Probability =
            Probability(percent.coerceIn(0, 100) / 100.0)

        /** Clamps any double into range instead of throwing. For parsed input. */
        fun clamped(value: Double): Probability =
            if (value.isNaN()) ALWAYS else Probability(value.coerceIn(0.0, 1.0))
    }
}

/**
 * A closed range of simulated latency, in milliseconds.
 *
 * Modelled as its own type rather than a `LongRange` so it can validate itself,
 * carry a label, and answer [isFixed] — the case where a range degenerates to one
 * value and no random draw should happen.
 *
 * @param minMillis lower bound, inclusive. Must not be negative.
 * @param maxMillis upper bound, inclusive. Must not be below [minMillis].
 */
data class LatencyRange(val minMillis: Long, val maxMillis: Long) {

    init {
        require(minMillis >= 0) { "NetKit latency cannot be negative (was $minMillis)" }
        require(maxMillis >= minMillis) {
            "NetKit latency range is inverted (${minMillis}ms > ${maxMillis}ms)"
        }
    }

    /** True when both ends agree, so [pick] is deterministic without a draw. */
    val isFixed: Boolean get() = minMillis == maxMillis

    /** True when this range never delays anything. */
    val isZero: Boolean get() = maxMillis == 0L

    /** `500–3000ms`, or `2500ms` when fixed. */
    val label: String
        get() = if (isFixed) "${minMillis}ms" else "$minMillis–${maxMillis}ms"

    /** A value inside the range. Draws only when the range is not fixed. */
    fun pick(random: NetKitRandom): Long =
        if (isFixed) minMillis else random.nextLong(minMillis, maxMillis)

    companion object {
        /** A range that adds no delay. */
        val NONE: LatencyRange = LatencyRange(0, 0)

        /** A range that is really a single value. */
        fun fixed(millis: Long): LatencyRange = LatencyRange(millis, millis)
    }
}

/**
 * One possible result of a random choice, with its relative likelihood.
 *
 * Weights are relative and are normalised at selection time, so `60/15/10/10/5`
 * and `12/3/2/2/1` describe the same distribution. That is deliberate: a QA
 * engineer adding a fifth outcome to a set that already sums to 100 should not
 * have to rebalance the other four.
 *
 * @param weight relative likelihood. Must be positive — a zero-weight outcome is
 *   an outcome someone meant to delete, and silently keeping it in the list is
 *   how a scenario ends up with a branch nobody can explain.
 * @param action what happens when this outcome is chosen.
 */
data class WeightedOutcome(
    val weight: Int,
    val action: NetworkAction,
) {
    init {
        require(weight > 0) { "NetKit outcome weight must be positive (was $weight)" }
        require(action !is NetworkAction.Weighted) {
            "NetKit weighted outcomes cannot nest"
        }
        require(action !is NetworkAction.Sequence) {
            "NetKit weighted outcomes cannot contain a response sequence"
        }
    }

    /** `HTTP 500` — the label shown beside the weight. */
    val label: String get() = action.label
}

/**
 * Picks one outcome, deterministically for a given [random].
 *
 * Selection walks the list accumulating weights against a single draw, so the
 * result depends only on the draw and on the list's order and weights — not on
 * how many outcomes there are or how large the weights happen to be.
 *
 * @return the chosen outcome, or `null` when [this] is empty.
 */
internal fun List<WeightedOutcome>.pick(random: NetKitRandom): WeightedOutcome? {
    if (isEmpty()) return null
    if (size == 1) return this[0]
    val total = sumOf { it.weight }
    if (total <= 0) return this[0]
    var cursor = random.nextInt(total)
    for (index in indices) {
        cursor -= this[index].weight
        if (cursor < 0) return this[index]
    }
    // Unreachable while weights are positive; returning the last outcome rather
    // than null keeps a rounding surprise from becoming a dropped decision.
    return last()
}

/** The percentage each outcome represents, for the editor's live preview. */
fun List<WeightedOutcome>.percentages(): List<Int> {
    val total = sumOf { it.weight }
    if (total <= 0) return List(size) { 0 }
    return map { Math.round(it.weight * 100.0 / total).toInt() }
}
