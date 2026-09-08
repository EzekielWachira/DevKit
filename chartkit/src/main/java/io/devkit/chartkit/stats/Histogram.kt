package io.devkit.chartkit.stats

import io.devkit.chartkit.geometry.ChartMath
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * How a histogram divides its values into bins.
 *
 * The choice changes what the chart says, which is why it is a parameter and
 * not a hidden heuristic: the same measurements binned ten ways and binned
 * fifty ways can look unimodal or bimodal.
 */
sealed interface HistogramBins {

    /**
     * Chosen from the sample size by the Freedman–Diaconis rule, falling back
     * to Sturges' formula when the interquartile range is zero. The default.
     *
     * Freedman–Diaconis sets the bin *width* from the spread of the middle half
     * of the data — `2 · IQR · n^(-1/3)` — so it is not thrown off by a few
     * extreme values the way a rule based on the full range is. It degenerates
     * when the middle half has no spread at all, and Sturges takes over there.
     */
    data object Auto : HistogramBins

    /** Exactly this many equal-width bins across the data's range. */
    data class Count(val count: Int) : HistogramBins {
        init {
            require(count >= 1) { "A histogram needs at least one bin, was $count" }
        }
    }

    /**
     * Bins of this width, aligned to multiples of it.
     *
     * Aligned rather than started at the minimum, so `Width(50.0)` produces
     * boundaries at 0, 50, 100 — the numbers a reader expects to see labelled —
     * instead of at 37, 87, 137.
     */
    data class Width(val width: Double) : HistogramBins {
        init {
            require(width > 0.0 && width.isFinite()) {
                "A histogram bin width must be a finite value > 0, was $width"
            }
        }
    }

    /**
     * Explicit boundaries, ascending. `n` boundaries produce `n - 1` bins.
     *
     * For bins that are not equal width — response-time buckets of 0–50,
     * 50–200, 200–1000 — which no automatic rule will ever produce and which
     * are frequently the ones an organisation has standardised on.
     */
    data class Custom(val boundaries: List<Double>) : HistogramBins {
        init {
            require(boundaries.size >= 2) {
                "Custom bins need at least two boundaries to make one bin, had ${boundaries.size}"
            }
            require(boundaries.all { it.isFinite() }) {
                "Custom bin boundaries must all be finite"
            }
            require(boundaries.zipWithNext().all { (a, b) -> b > a }) {
                "Custom bin boundaries must be strictly ascending, were $boundaries"
            }
        }
    }
}

/** What a histogram bar's height means. */
enum class HistogramMetric {

    /** The number of observations in the bin. The default, and the literal one. */
    Count,

    /**
     * The bin's share of all observations, in `0..1`.
     *
     * Comparable across datasets of different sizes, which raw counts are not.
     */
    Percentage,

    /**
     * Probability density: the share divided by the bin's width.
     *
     * The only one of the three that is meaningful when bins are **not** equal
     * width — under [HistogramBins.Custom] a count bar makes a wide bin look
     * more populated simply for being wide.
     */
    Density,
}

/**
 * One bin: its half-open interval, how many observations fell in it, and the
 * height the chart draws.
 *
 * @param start inclusive.
 * @param end exclusive, except for the final bin, which includes its upper
 *   bound — otherwise the single largest observation in a dataset falls outside
 *   every bin, and a histogram silently loses its maximum.
 * @param value the height under the chosen [HistogramMetric].
 * @param sourceIndices positions in the caller's own list, so a tap on a bar
 *   can hand back the observations behind it.
 */
data class HistogramBin(
    val start: Double,
    val end: Double,
    val count: Int,
    val value: Double,
    val sourceIndices: List<Int> = emptyList(),
) {
    val width: Double get() = end - start

    /** The bin's midpoint — where a tick or a label belongs. */
    val center: Double get() = (start + end) / 2.0
}

/**
 * Turns raw observations into bins.
 *
 * Pure Kotlin and separate from any drawing: binning is the part of a histogram
 * with the interesting decisions in it, and it is verified directly rather than
 * by looking at bar heights.
 */
object HistogramBinner {

    /**
     * Bins [values] according to [bins], measured by [metric].
     *
     * Non-finite entries are dropped and do not contribute to any bin or to the
     * total the percentage and density metrics divide by. An empty or entirely
     * non-finite input yields no bins, which the chart renders as its empty
     * state rather than as a single bar of zero height.
     *
     * A dataset whose values are all identical yields **one** bin around that
     * value rather than a division by a zero range — which is the truthful
     * picture: every observation is in the same place.
     */
    fun bin(
        values: List<Double?>,
        bins: HistogramBins = HistogramBins.Auto,
        metric: HistogramMetric = HistogramMetric.Count,
    ): List<HistogramBin> {
        val usable = ArrayList<IndexedValue<Double>>(values.size)
        values.forEachIndexed { index, value ->
            if (value != null && value.isFinite()) usable += IndexedValue(index, value)
        }
        if (usable.isEmpty()) return emptyList()

        val boundaries = boundaries(usable.map { it.value }, bins)
        if (boundaries.size < 2) return emptyList()

        val counts = IntArray(boundaries.size - 1)
        val indices = Array(boundaries.size - 1) { ArrayList<Int>() }
        usable.forEach { (sourceIndex, value) ->
            val bin = binIndexOf(value, boundaries)
            if (bin >= 0) {
                counts[bin]++
                indices[bin] += sourceIndex
            }
        }

        val total = counts.sum()
        return List(counts.size) { index ->
            val start = boundaries[index]
            val end = boundaries[index + 1]
            val count = counts[index]
            val width = end - start
            val value = when (metric) {
                HistogramMetric.Count -> count.toDouble()
                HistogramMetric.Percentage -> ChartMath.safeDiv(count.toDouble(), total.toDouble())
                HistogramMetric.Density -> ChartMath.safeDiv(
                    ChartMath.safeDiv(count.toDouble(), total.toDouble()),
                    width,
                )
            }
            HistogramBin(
                start = start,
                end = end,
                count = count,
                value = value,
                sourceIndices = indices[index],
            )
        }
    }

    /**
     * The bin boundaries [bins] produces for [values], ascending.
     *
     * Exposed separately because it is the decision worth testing on its own —
     * counting into a set of boundaries is trivial, and choosing the boundaries
     * is not.
     */
    fun boundaries(values: List<Double>, bins: HistogramBins): List<Double> {
        if (values.isEmpty()) return emptyList()
        if (bins is HistogramBins.Custom) return bins.boundaries

        val minimum = values.min()
        val maximum = values.max()

        // Every value identical: one bin around it, of a width proportionate to
        // the value so the bar has a drawable extent. Zero width would give the
        // axis nothing to scale against.
        if (maximum - minimum <= ChartMath.EPSILON) {
            val half = if (kotlin.math.abs(minimum) < ChartMath.EPSILON) 0.5 else kotlin.math.abs(minimum) * 0.05
            return listOf(minimum - half, maximum + half)
        }

        return when (bins) {
            is HistogramBins.Count -> equalWidth(minimum, maximum, bins.count)
            is HistogramBins.Width -> alignedWidth(minimum, maximum, bins.width)
            HistogramBins.Auto -> equalWidth(minimum, maximum, suggestedBinCount(values))
            is HistogramBins.Custom -> bins.boundaries
        }
    }

    /**
     * The bin count [HistogramBins.Auto] would choose for [values].
     *
     * Freedman–Diaconis, then Sturges when the IQR has collapsed. Capped at
     * [MAX_AUTO_BINS]: a rule applied to a hundred thousand tightly clustered
     * observations can ask for tens of thousands of bins, each narrower than a
     * pixel.
     */
    fun suggestedBinCount(values: List<Double>): Int {
        val sorted = ChartStatistics.finiteSorted(values)
        if (sorted.size < 2) return 1
        val range = sorted.last() - sorted.first()
        if (range <= ChartMath.EPSILON) return 1

        val iqr = ChartStatistics.interquartileRange(sorted)
        val count = if (iqr.isFinite() && iqr > ChartMath.EPSILON) {
            val width = 2.0 * iqr / Math.cbrt(sorted.size.toDouble())
            if (width > ChartMath.EPSILON) ceil(range / width).toInt() else sturges(sorted.size)
        } else {
            sturges(sorted.size)
        }
        return count.coerceIn(1, MAX_AUTO_BINS)
    }

    /** `⌈log₂ n⌉ + 1`. */
    private fun sturges(n: Int): Int =
        (ceil(kotlin.math.ln(n.toDouble()) / kotlin.math.ln(2.0)).toInt() + 1).coerceAtLeast(1)

    private fun equalWidth(minimum: Double, maximum: Double, count: Int): List<Double> {
        val bins = count.coerceIn(1, MAX_AUTO_BINS)
        val width = (maximum - minimum) / bins
        if (!width.isFinite() || width <= 0.0) return listOf(minimum, maximum)
        // The last boundary is set to the maximum exactly rather than
        // accumulated, so floating-point drift cannot leave the largest
        // observation a hair outside the final bin.
        return List(bins + 1) { index ->
            if (index == bins) maximum else minimum + width * index
        }
    }

    private fun alignedWidth(minimum: Double, maximum: Double, width: Double): List<Double> {
        val first = floor(minimum / width) * width
        val count = ceil((maximum - first) / width).toInt().coerceIn(1, MAX_AUTO_BINS)
        return List(count + 1) { index -> first + width * index }
    }

    /**
     * The bin [value] belongs to, or `-1`.
     *
     * Half-open `[start, end)` throughout, except that the final bin closes at
     * its upper bound. Binary search, so binning a hundred thousand
     * observations into a hundred bins is `n log b` rather than `n · b`.
     */
    fun binIndexOf(value: Double, boundaries: List<Double>): Int {
        if (boundaries.size < 2) return -1
        if (value < boundaries.first()) return -1
        if (value > boundaries.last()) return -1
        if (value == boundaries.last()) return boundaries.size - 2

        var low = 0
        var high = boundaries.size - 1
        while (low < high - 1) {
            val mid = (low + high) / 2
            if (boundaries[mid] <= value) low = mid else high = mid
        }
        return low
    }

    /**
     * More bins than this and each is narrower than a pixel on any real chart.
     *
     * A cap rather than a warning: the alternative to capping is a rule that
     * occasionally asks for fifty thousand `drawRect` calls.
     */
    const val MAX_AUTO_BINS: Int = 512
}

/** Square root of [n], the simplest of the classic bin-count rules. Kept for tests. */
internal fun sqrtRule(n: Int): Int = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1)
