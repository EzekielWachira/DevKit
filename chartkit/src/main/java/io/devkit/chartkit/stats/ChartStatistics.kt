package io.devkit.chartkit.stats

import io.devkit.chartkit.geometry.ChartMath
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The descriptive statistics ChartKit's statistical charts are built on.
 *
 * Plain Kotlin: no Compose, no `android.*`, no coordinate system. A box plot's
 * whiskers and a violin's outline are arithmetic, and arithmetic verified by
 * looking at a canvas is arithmetic verified badly — so all of it lives here,
 * runs on the JVM, and is tested directly.
 *
 * ### Non-finite input
 *
 * Every entry point filters `NaN` and infinities out before computing anything,
 * and says so. The alternative is a `NaN` quartile reaching a `drawPath`, where
 * it renders as nothing and looks exactly like a layout bug. Filtering is
 * therefore the documented behaviour rather than a silent convenience, and the
 * count of usable samples is reported alongside the result so a caller can see
 * that values were dropped.
 */
object ChartStatistics {

    /** [values], finite entries only, ascending. The input list is untouched. */
    fun finiteSorted(values: Iterable<Double>): DoubleArray {
        val kept = ArrayList<Double>()
        for (value in values) if (value.isFinite()) kept += value
        val array = kept.toDoubleArray()
        array.sort()
        return array
    }

    /**
     * The [p]-quantile of an ascending sample, `p` in `0..1`.
     *
     * ### Which definition
     *
     * Linear interpolation between the two order statistics either side of
     * `h = (n - 1) p` — the method R calls **type 7** and NumPy calls
     * `"linear"`, and the default in both. There are at least nine published
     * quartile definitions and they disagree by a visible amount on small
     * samples, so a charting library that leaves the choice implicit produces
     * a box plot nobody can reconcile with their own analysis. This one is
     * named, not merely implemented.
     *
     * ```text
     * h = (n - 1) p
     * Q = x[⌊h⌋] + (h - ⌊h⌋) · (x[⌊h⌋ + 1] - x[⌊h⌋])
     * ```
     *
     * An empty sample has no quantile and yields `NaN`; a one-element sample
     * yields that element for every `p`.
     */
    fun quantile(sorted: DoubleArray, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        if (sorted.size == 1) return sorted[0]
        val fraction = ChartMath.clamp(p, 0.0, 1.0)
        val h = (sorted.size - 1) * fraction
        val low = floor(h).toInt().coerceIn(0, sorted.size - 1)
        val high = (low + 1).coerceAtMost(sorted.size - 1)
        val weight = h - low
        return sorted[low] + weight * (sorted[high] - sorted[low])
    }

    /** The median, by the same definition as [quantile]. */
    fun median(sorted: DoubleArray): Double = quantile(sorted, 0.5)

    /** The median of any collection, sorted and filtered first. */
    fun median(values: Iterable<Double>): Double = median(finiteSorted(values))

    /** `[q1, median, q3]`, by the same definition as [quantile]. */
    fun quartiles(sorted: DoubleArray): Quartiles = Quartiles(
        q1 = quantile(sorted, 0.25),
        median = quantile(sorted, 0.5),
        q3 = quantile(sorted, 0.75),
    )

    /** The interquartile range, `q3 - q1`. `NaN` for an empty sample. */
    fun interquartileRange(sorted: DoubleArray): Double {
        val q = quartiles(sorted)
        return q.q3 - q.q1
    }

    /** The arithmetic mean of the finite entries, or `NaN` when there are none. */
    fun mean(values: Iterable<Double>): Double {
        var sum = 0.0
        var count = 0
        for (value in values) {
            if (!value.isFinite()) continue
            sum += value
            count++
        }
        return if (count == 0) Double.NaN else sum / count
    }

    /**
     * The sample standard deviation (Bessel-corrected, `n - 1`).
     *
     * The sample rather than the population form because a charted dataset is
     * almost always a sample of something, and because the two differ most on
     * exactly the small datasets a chart is most likely to hold. Zero for a
     * one-element sample, which is the honest answer: one observation has no
     * spread.
     */
    fun standardDeviation(sorted: DoubleArray): Double {
        if (sorted.size < 2) return 0.0
        val average = sorted.average()
        var sumSquares = 0.0
        for (value in sorted) {
            val delta = value - average
            sumSquares += delta * delta
        }
        val variance = sumSquares / (sorted.size - 1)
        return if (variance > 0.0) sqrt(variance) else 0.0
    }

    /**
     * The Gaussian kernel evaluated at [u] standard deviations.
     *
     * Exposed because it is the one place a density curve's shape is decided,
     * and because a caller comparing ChartKit's violin against their own
     * analysis needs to know which kernel produced it.
     */
    fun gaussianKernel(u: Double): Double = INV_SQRT_TWO_PI * exp(-0.5 * u * u)

    /**
     * Silverman's rule-of-thumb bandwidth for a Gaussian kernel.
     *
     * ```text
     * h = 0.9 · min(σ, IQR / 1.34) · n^(-1/5)
     * ```
     *
     * The robust form, using the smaller of the standard deviation and a
     * scaled interquartile range, so one extreme outlier does not smear the
     * whole curve flat. Returns `0` when the sample has no spread to estimate
     * a density over — a constant sample is a spike, not a distribution, and
     * the caller draws it as one rather than dividing by a bandwidth of zero.
     */
    fun silvermanBandwidth(sorted: DoubleArray): Double {
        if (sorted.size < 2) return 0.0
        val sigma = standardDeviation(sorted)
        val iqr = interquartileRange(sorted)
        val robust = if (iqr.isFinite() && iqr > 0.0) iqr / 1.34 else sigma
        val scale = when {
            sigma > 0.0 && robust > 0.0 -> minOf(sigma, robust)
            sigma > 0.0 -> sigma
            robust > 0.0 -> robust
            else -> 0.0
        }
        if (scale <= 0.0) return 0.0
        val bandwidth = 0.9 * scale * sorted.size.toDouble().pow(-0.2)
        return if (bandwidth.isFinite() && bandwidth > 0.0) bandwidth else 0.0
    }

    /**
     * The values further than [multiplier] interquartile ranges beyond the
     * quartiles.
     *
     * The conventional Tukey fence, with the multiplier exposed rather than
     * fixed at `1.5`: `1.5` is a convention, not a law, and a dataset that is
     * legitimately heavy-tailed produces a box plot that is nothing but
     * outliers under it.
     *
     * A sample with no spread has no outliers — every value equals the
     * quartiles, so the fences collapse onto them and nothing is beyond.
     */
    fun outliers(sorted: DoubleArray, multiplier: Double = DEFAULT_OUTLIER_MULTIPLIER): DoubleArray {
        if (sorted.size < 2 || multiplier < 0.0) return DoubleArray(0)
        val q = quartiles(sorted)
        val iqr = q.q3 - q.q1
        if (!iqr.isFinite() || iqr <= ChartMath.EPSILON) return DoubleArray(0)
        val low = q.q1 - multiplier * iqr
        val high = q.q3 + multiplier * iqr
        return sorted.filter { it < low || it > high }.toDoubleArray()
    }

    /**
     * The whisker ends: the most extreme values still inside the Tukey fences.
     *
     * The whisker stops at a real observation rather than at the fence itself,
     * which is what a box plot means — the fence is a rule for classifying
     * points, not a value the data reached.
     */
    fun whiskers(sorted: DoubleArray, multiplier: Double = DEFAULT_OUTLIER_MULTIPLIER): Whiskers {
        if (sorted.isEmpty()) return Whiskers(Double.NaN, Double.NaN)
        val q = quartiles(sorted)
        val iqr = q.q3 - q.q1
        if (!iqr.isFinite() || iqr <= ChartMath.EPSILON || multiplier < 0.0) {
            return Whiskers(sorted.first(), sorted.last())
        }
        val low = q.q1 - multiplier * iqr
        val high = q.q3 + multiplier * iqr
        val lower = sorted.firstOrNull { it >= low } ?: sorted.first()
        val upper = sorted.lastOrNull { it <= high } ?: sorted.last()
        return Whiskers(lower, upper)
    }

    /** Tukey's convention, and the default everywhere ChartKit detects outliers. */
    const val DEFAULT_OUTLIER_MULTIPLIER: Double = 1.5

    private val INV_SQRT_TWO_PI = 1.0 / sqrt(2.0 * Math.PI)
}

/** The three quartiles of a sample. */
data class Quartiles(val q1: Double, val median: Double, val q3: Double) {

    /** `q3 - q1`. */
    val iqr: Double get() = q3 - q1

    /** True when every quartile is a real number. */
    val isFinite: Boolean get() = q1.isFinite() && median.isFinite() && q3.isFinite()
}

/** The two whisker ends of a box plot. */
data class Whiskers(val lower: Double, val upper: Double)

/** Absolute difference, guarding the non-finite cases. */
internal fun safeSpan(low: Double, high: Double): Double {
    val span = abs(high - low)
    return if (span.isFinite()) span else 0.0
}
