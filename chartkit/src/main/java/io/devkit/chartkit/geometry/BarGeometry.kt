package io.devkit.chartkit.geometry

import io.devkit.chartkit.scale.CategoryScale
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import kotlin.math.abs
import kotlin.math.max

/**
 * Which axis carries the categories.
 *
 * A horizontal bar chart is not a second renderer — it is the same geometry
 * with the category axis and the value axis exchanged. Everything that follows
 * is written in terms of "category axis" and "value axis" rather than x and y,
 * and the single [ChartOrientation] parameter decides which is which. That is
 * what stops `HorizontalBarChart` becoming a parallel implementation that
 * drifts from `BarChart` the first time either is fixed.
 */
enum class ChartOrientation {

    /** Categories along the bottom, values up the side. Bars rise. */
    Vertical,

    /** Categories up the side, values along the bottom. Bars extend right. */
    Horizontal,
    ;

    val isVertical: Boolean get() = this == Vertical
}

/** How several series share one category. */
enum class BarGrouping {

    /** Side by side within the category's band. */
    Grouped,

    /** Piled on top of each other, each starting where the last ended. */
    Stacked,

    /**
     * Stacked and normalised so each category fills the same length.
     *
     * Segments become each value's share of the category's total. Shares are
     * taken over the sum of **absolute** values, so a category mixing signs
     * still normalises rather than dividing by a total that cancels to near
     * zero — and a category totalling zero yields zero-length segments instead
     * of a division by zero.
     */
    StackedPercent,
    ;

    val isStacked: Boolean get() = this != Grouped
}

/**
 * One drawable bar, with everything hit testing and tooltips need attached.
 *
 * [rect] is already in pixels and already oriented; a layer draws it without
 * knowing whether the chart is vertical.
 */
internal data class BarSlice(
    val seriesIndex: Int,
    val paletteIndex: Int,
    val pointIndex: Int,
    val categoryIndex: Int,
    val rect: ChartRect,
    /** The value as supplied, before any percent normalisation. */
    val value: Double,
    /** The value actually plotted — equal to [value] outside percent mode. */
    val plottedValue: Double,
    /**
     * Whether this slice's far end is the visual end of its bar.
     *
     * False for every segment of a stack except the outermost. Rounding the
     * corners of an interior segment carves notches out of a bar that is
     * supposed to read as one continuous length, which is both ugly and a
     * misreading of what a stack means.
     */
    val isBarEnd: Boolean,
)

/**
 * Value-space bar extents, before anything is mapped to pixels.
 *
 * Kept separate from the pixel step because it is the part with the interesting
 * arithmetic — stacking, sign handling, normalisation — and the part worth
 * testing without a plot area.
 */
internal object BarStacking {

    /**
     * `[start, end]` in value space for each series and category.
     *
     * Indexed `[seriesIndex][categoryIndex]`; `null` where a series has no value
     * for a category, which leaves a hole rather than a zero-height bar.
     *
     * Stacked bars accumulate **per sign**: positives pile upward from zero and
     * negatives downward from zero, so a category holding `+3` and `-1` draws a
     * segment above the baseline and one below it rather than a single bar of
     * net height 2. That is the only reading under which the segment lengths
     * still match the values they represent.
     */
    fun bounds(
        values: List<List<Double?>>,
        grouping: BarGrouping,
    ): List<List<ClosedFloatingPointRange<Double>?>> {
        if (values.isEmpty()) return emptyList()
        val categoryCount = values.maxOf { it.size }
        val plotted = if (grouping == BarGrouping.StackedPercent) {
            toPercent(values, categoryCount)
        } else {
            values
        }

        if (!grouping.isStacked) {
            return plotted.map { series ->
                series.map { value ->
                    value?.takeIf { it.isFinite() }?.let { v ->
                        if (v >= 0.0) 0.0..v else v..0.0
                    }
                }
            }
        }

        val positiveTop = DoubleArray(categoryCount)
        val negativeBottom = DoubleArray(categoryCount)
        return plotted.map { series ->
            List(categoryCount) { category ->
                val value = series.getOrNull(category)?.takeIf { it.isFinite() }
                when {
                    value == null -> null
                    value >= 0.0 -> {
                        val start = positiveTop[category]
                        positiveTop[category] = start + value
                        start..positiveTop[category]
                    }
                    else -> {
                        val end = negativeBottom[category]
                        negativeBottom[category] = end + value
                        negativeBottom[category]..end
                    }
                }
            }
        }
    }

    /**
     * Each value as its share of its category's total absolute magnitude.
     *
     * A zero total produces zeroes, not `NaN` — the case arises whenever a
     * category has no data yet, which on a live dashboard is every category for
     * the first frame.
     */
    fun toPercent(values: List<List<Double?>>, categoryCount: Int): List<List<Double?>> {
        val totals = DoubleArray(categoryCount)
        values.forEach { series ->
            for (category in 0 until categoryCount) {
                val value = series.getOrNull(category)
                if (value != null && value.isFinite()) totals[category] += abs(value)
            }
        }
        return values.map { series ->
            List(categoryCount) { category ->
                val value = series.getOrNull(category)?.takeIf { it.isFinite() }
                when {
                    value == null -> null
                    totals[category] < ChartMath.EPSILON -> 0.0
                    else -> value / totals[category]
                }
            }
        }
    }

    /** The domain the value axis needs to show every extent in [bounds]. */
    fun domainOf(bounds: List<List<ClosedFloatingPointRange<Double>?>>): NumericDomain? =
        NumericDomain.of(
            bounds.asSequence()
                .flatten()
                .filterNotNull()
                .flatMap { sequenceOf(it.start, it.endInclusive) }
                .asIterable(),
        )
}

/**
 * Turns value-space extents into pixel rectangles inside the plot.
 *
 * @param groupPadding the fraction of a category band left as a gutter between
 *   the bars of a grouped chart. Ignored when stacked, where the series share
 *   one bar.
 * @param animationFraction `0..1`; bars grow from their baseline. Applied here
 *   rather than in the draw pass so an animating bar's hit rectangle matches
 *   what is on screen.
 * @param minimumBarLength the smallest length a non-zero bar is drawn at, in
 *   pixels. A tiny-but-real value that rounds to nothing is indistinguishable
 *   from no data, so it is nudged up to stay visible. The cost is that lengths
 *   below this no longer encode their value proportionally, which is why the
 *   default is small and the parameter is exposed.
 */
internal fun computeBarSlices(
    values: List<List<Double?>>,
    pointIndices: List<List<Int>>,
    paletteIndices: List<Int>,
    bounds: List<List<ClosedFloatingPointRange<Double>?>>,
    categoryScale: CategoryScale,
    valueScale: LinearScale,
    orientation: ChartOrientation,
    grouping: BarGrouping,
    plotArea: ChartRect,
    groupPadding: Double = DEFAULT_GROUP_PADDING,
    animationFraction: Float = 1f,
    minimumBarLength: Float = DEFAULT_MINIMUM_BAR_LENGTH,
): List<BarSlice> {
    require(groupPadding >= 0.0 && groupPadding < 1.0) {
        "Group padding must be in [0, 1), was $groupPadding"
    }
    if (plotArea.isEmpty || bounds.isEmpty() || categoryScale.count <= 0) return emptyList()

    val seriesCount = bounds.size
    val bandWidth = categoryScale.innerBandWidth
    if (bandWidth <= 0f) return emptyList()

    val slotWidth = if (grouping.isStacked) {
        bandWidth
    } else {
        val gutter = (bandWidth * groupPadding).toFloat()
        max(0f, (bandWidth - gutter * (seriesCount - 1).coerceAtLeast(0)) / seriesCount)
    }

    val baseline = valueScale.scale(0.0)
    val fraction = ChartMath.clamp(animationFraction, 0f, 1f)
    val result = ArrayList<BarSlice>(seriesCount * categoryScale.count)

    for (seriesIndex in 0 until seriesCount) {
        val seriesBounds = bounds[seriesIndex]
        for (categoryIndex in 0 until categoryScale.count) {
            val extent = seriesBounds.getOrNull(categoryIndex) ?: continue
            val centre = categoryScale.positionAt(categoryIndex)

            val slotStart = if (grouping.isStacked) {
                centre - bandWidth / 2f
            } else {
                val bandStart = centre - bandWidth / 2f
                val gutter = (bandWidth * groupPadding).toFloat()
                bandStart + seriesIndex * (slotWidth + gutter)
            }
            val slotEnd = slotStart + slotWidth

            var from = valueScale.scale(extent.start)
            var to = valueScale.scale(extent.endInclusive)

            // Animate from the baseline, not from the segment's own start: a
            // stacked segment that grew in place would slide rather than build.
            from = ChartMath.lerp(baseline, from, fraction)
            to = ChartMath.lerp(baseline, to, fraction)

            val rawValue = values.getOrNull(seriesIndex)?.getOrNull(categoryIndex) ?: 0.0
            val plotted = extent.endInclusive - extent.start

            // A real but tiny value would otherwise round to a zero-length
            // rect, which reads on screen as "no data". Only once the reveal
            // animation has finished, so bars still grow from nothing.
            val length = to - from
            if (abs(length) < minimumBarLength &&
                abs(plotted) > ChartMath.EPSILON &&
                fraction > 0.99f
            ) {
                val direction = when {
                    length > 0f -> 1f
                    length < 0f -> -1f
                    // Exactly zero on screen: fall back to the sign of the value
                    // and the direction the value axis grows in.
                    plotted >= 0.0 -> valueAxisDirection(orientation)
                    else -> -valueAxisDirection(orientation)
                }
                to = from + direction * minimumBarLength
            }

            val rect = if (orientation.isVertical) {
                ChartRect(left = slotStart, top = to, right = slotEnd, bottom = from)
            } else {
                ChartRect(left = from, top = slotStart, right = to, bottom = slotEnd)
            }

            result += BarSlice(
                seriesIndex = seriesIndex,
                paletteIndex = paletteIndices.getOrElse(seriesIndex) { seriesIndex },
                pointIndex = pointIndices.getOrNull(seriesIndex)
                    ?.getOrNull(categoryIndex) ?: categoryIndex,
                categoryIndex = categoryIndex,
                rect = rect.normalized,
                value = rawValue,
                plottedValue = plotted,
                // Provisional; corrected below once every segment is known.
                isBarEnd = true,
            )
        }
    }
    return if (grouping.isStacked) markStackEnds(result, bounds) else result
}

/**
 * Marks the outermost segment of each stack, in each direction.
 *
 * Direction matters: a category holding both positive and negative values has
 * two ends, one above the baseline and one below, and each gets its rounding.
 */
private fun markStackEnds(
    slices: List<BarSlice>,
    bounds: List<List<ClosedFloatingPointRange<Double>?>>,
): List<BarSlice> {
    fun extentOf(slice: BarSlice): ClosedFloatingPointRange<Double>? =
        bounds.getOrNull(slice.seriesIndex)?.getOrNull(slice.categoryIndex)

    val outermost = HashMap<Pair<Int, Boolean>, BarSlice>()
    slices.forEach { slice ->
        val extent = extentOf(slice) ?: return@forEach
        val positive = extent.endInclusive >= 0.0 && extent.start >= 0.0
        val key = slice.categoryIndex to positive
        val incumbent = outermost[key]
        val incumbentExtent = incumbent?.let(::extentOf)
        val wins = when {
            incumbentExtent == null -> true
            positive -> extent.endInclusive > incumbentExtent.endInclusive
            else -> extent.start < incumbentExtent.start
        }
        if (wins) outermost[key] = slice
    }
    val ends = outermost.values.toSet()
    return slices.map { if (it in ends) it else it.copy(isBarEnd = false) }
}

/**
 * Which pixel direction "more value" points in.
 *
 * On a vertical chart the value axis is inverted — larger values sit at smaller
 * y — so a bar grows towards negative pixels. On a horizontal one it grows
 * towards positive pixels.
 */
private fun valueAxisDirection(orientation: ChartOrientation): Float =
    if (orientation.isVertical) -1f else 1f

/** A gutter wide enough to read as a gap, narrow enough to keep bars usable. */
internal const val DEFAULT_GROUP_PADDING: Double = 0.1

/** Roughly a hairline at mdpi; a real value never disappears entirely. */
internal const val DEFAULT_MINIMUM_BAR_LENGTH: Float = 1f
