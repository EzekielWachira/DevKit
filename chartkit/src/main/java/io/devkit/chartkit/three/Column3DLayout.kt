package io.devkit.chartkit.three

import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.geometry.BarStacking
import io.devkit.chartkit.geometry.ChartMath
import kotlin.math.abs
import kotlin.math.max

/**
 * How several stacks share one category.
 *
 * The distinction §46 of any 3D column implementation lives or dies on:
 * *stacking* piles segments along `y` over one footprint, while *grouping*
 * gives each stack a footprint of its own. Where that second footprint goes is
 * the only choice, and it is this.
 */
enum class Column3DArrangement {

    /**
     * Stacks sit side by side across the category band, all at the same depth.
     *
     * The 2D reading, extruded. Heights stay directly comparable because every
     * column is the same distance from the reader, which is what makes this the
     * default: depth in a chart is a grouping cue, and using it for the
     * comparison the chart is actually for costs accuracy for decoration.
     */
    Side,

    /**
     * Each stack takes its own row in depth, sharing the full category band.
     *
     * The arrangement the reference demo is recognisable by. Reads well for two
     * or three stacks over few categories and badly beyond that, because a
     * column in the back row is both smaller under perspective and partly
     * hidden by the front one.
     */
    Depth,
}

/**
 * One series as the 3D layout needs it: values already aligned to the chart's
 * merged category order, and a stack to belong to.
 *
 * @param stackId which stack this series is part of. Series sharing an id are
 *   piled on one footprint; series with different ids are grouped. A chart that
 *   is not stacked gives every series its own id, which makes "grouped" and
 *   "one series per stack" the same case rather than two.
 */
internal class Column3DSeries(
    val seriesId: String,
    val seriesName: String,
    val paletteIndex: Int,
    val colorOverride: Int?,
    val stackId: String,
    val values: List<Double?>,
    val sourceIndices: List<Int>,
)

/**
 * One column segment: its box, and everything a tooltip, a label or a screen
 * reader needs about it.
 *
 * The value is carried in **three** forms because they answer different
 * questions. [value] is what the caller supplied and what a tooltip must show.
 * [plottedValue] is what was drawn, which differs under percent stacking.
 * [stackTotal] is the column's total, which is the number a reader of a stacked
 * chart is usually after and which no single segment knows on its own.
 */
internal class Column3DSegment(
    val key: Chart3DKey,
    val seriesName: String,
    val value: Double,
    val plottedValue: Double,
    val stackTotal: Double,
    val paletteIndex: Int,
    val colorOverride: Int?,
    val cuboid: Cuboid3D,
    /** The caller's own object for this point, or `null` when there is none. */
    val item: Any?,
)

/** The complete 3D column layout: segments, and the volume the plot occupies. */
internal class Column3DLayout(
    val segments: List<Column3DSegment>,
    /** The whole plot volume, columns or not — what the frame is built on. */
    val volume: Bounds3D,
    /** Stack ids in the order they were first declared, for depth placement. */
    val stackIds: List<String>,
)

/**
 * Turns an already-computed bar layout into 3D boxes.
 *
 * ### What this does not do
 *
 * It does not stack. [BarStacking] does, and this calls it — once per stack
 * group, with the grouping the chart was given. Percent normalisation,
 * positive-and-negative accumulation around zero and the null handling that
 * leaves a hole rather than a zero-height box all come from there, unchanged
 * and unduplicated. A second stack implementation for 3D would be the single
 * most likely place for the two chart families to disagree about what a number
 * means, and disagreeing quietly.
 *
 * What it *does* is add the third dimension the 2D engine has no notion of:
 * where a stack sits in depth, how deep a column is, and the box that follows.
 */
internal object Column3DLayoutEngine {

    /**
     * @param categoryCentres the pixel centre of each category band, relative to
     *   the plot's left edge.
     * @param bandWidth the usable width of one band, in pixels.
     * @param valueFraction maps a value to its `0..1` position on the value
     *   axis — the chart's own scale, transform and domain included, so a
     *   logarithmic 3D chart needs no code here at all.
     * @param plotHeight the pixel height the value axis spans.
     * @param plotWidth the pixel width the domain axis spans.
     * @param reveal `0..1`; every segment grows from the baseline, so a stack
     *   builds as one continuous column rather than separating into its parts.
     */
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    fun layout(
        categories: List<String>,
        series: List<Column3DSeries>,
        items: Map<String, List<Any?>>,
        grouping: BarGrouping,
        arrangement: Column3DArrangement,
        depth: Chart3DDepth,
        categoryCentres: List<Float>,
        bandWidth: Float,
        valueFraction: (Double) -> Double,
        plotWidth: Float,
        plotHeight: Float,
        groupPadding: Double,
        depthGap: Double,
        reveal: Float = 1f,
    ): Column3DLayout {
        require(groupPadding >= 0.0 && groupPadding < 1.0) {
            "Group padding must be in [0, 1), was $groupPadding"
        }
        if (!depthGap.isFinite() || depthGap < 0.0) {
            throw Chart3DException(
                "The gap between depth rows must be a non-negative, finite fraction of a " +
                    "column's depth, was $depthGap",
            )
        }

        // Stacks in declaration order, so the same data always produces the
        // same arrangement. Ordering by anything derived from the values — the
        // stack totals, say — would move a whole row of columns backwards the
        // first time the numbers changed.
        val stackIds = LinkedHashSet<String>().apply { series.forEach { add(it.stackId) } }.toList()
        val stackCount = max(1, stackIds.size)
        val band = bandWidth.toDouble()
        val height = plotHeight.toDouble()

        val slotWidth: Double
        val columnDepth: Double
        when (arrangement) {
            Column3DArrangement.Side -> {
                val gutter = band * groupPadding
                slotWidth = max(0.0, (band - gutter * (stackCount - 1)) / stackCount)
                columnDepth = resolveDepth(depth, slotWidth)
            }
            Column3DArrangement.Depth -> {
                slotWidth = band
                // Every row shares the band's width, so the depth each row gets
                // is what is left of a sensible total once the gaps are taken
                // out — otherwise a four-stack chart is four times as deep as a
                // one-stack chart and the camera has to pull back to hold it.
                val total = resolveDepth(depth, slotWidth) * DEPTH_ROWS_REFERENCE
                columnDepth = total / (stackCount + depthGap * (stackCount - 1))
            }
        }

        val baseline = valueFraction(0.0).let { fraction ->
            // A domain that excludes zero — a chart of values from 40 to 90 —
            // has no baseline inside the plot. Clamping keeps the columns
            // standing on the floor of the frame rather than starting below it.
            ChartMath.clamp(fraction, 0.0, 1.0) * height
        }
        val revealFraction = ChartMath.clamp(reveal, 0f, 1f).toDouble()

        val segments = ArrayList<Column3DSegment>(series.size * categories.size)
        val depthStride = columnDepth * (1.0 + depthGap)

        stackIds.forEachIndexed { stackIndex, stackId ->
            val members = series.filter { it.stackId == stackId }
            if (members.isEmpty()) return@forEachIndexed

            // The one stack engine, called with this stack's members only.
            // Series in a different stack must not raise these segments, which
            // is exactly what one global call would do.
            val bounds = BarStacking.bounds(members.map { it.values }, grouping)
            val totals = stackTotals(members)

            val xOffset = when (arrangement) {
                Column3DArrangement.Side -> {
                    val gutter = band * groupPadding
                    -band / 2.0 + stackIndex * (slotWidth + gutter)
                }
                Column3DArrangement.Depth -> -band / 2.0
            }
            val z = when (arrangement) {
                Column3DArrangement.Side -> 0.0
                Column3DArrangement.Depth -> stackIndex * depthStride
            }

            members.forEachIndexed { memberIndex, member ->
                val memberBounds = bounds.getOrNull(memberIndex) ?: return@forEachIndexed
                categories.forEachIndexed { categoryIndex, category ->
                    // A null value has no extent, and no box. Not a box of zero
                    // height: that is what a *zero* looks like, and the two are
                    // different facts.
                    val extent = memberBounds.getOrNull(categoryIndex) ?: return@forEachIndexed
                    val rawValue = member.values.getOrNull(categoryIndex) ?: return@forEachIndexed
                    val centre = categoryCentres.getOrNull(categoryIndex) ?: return@forEachIndexed

                    // Stated from the segment's base to its tip, not from its
                    // lower edge to its upper one. [BarStacking] always returns
                    // an ascending interval, so a column below the baseline
                    // arrives as `-20..0` — and building a box from that as-is
                    // produces one that is geometrically right and has no idea
                    // it points downward. The box is told, and [Cuboid3D]
                    // normalises it back for the winding.
                    val descending = extent.start < 0.0
                    val base = if (descending) extent.endInclusive else extent.start
                    val tip = if (descending) extent.start else extent.endInclusive
                    val yStart = valueFraction(base) * height
                    val yEnd = valueFraction(tip) * height
                    // Both ends grow from the same baseline, which is what keeps
                    // a stack contiguous at every frame of the reveal: segment
                    // n's start is segment n-1's end, and lerping both from one
                    // origin preserves the equality exactly.
                    val animatedStart = ChartMath.lerp(baseline, yStart, revealFraction)
                    val animatedEnd = ChartMath.lerp(baseline, yEnd, revealFraction)

                    val sourceIndex = member.sourceIndices.getOrNull(categoryIndex) ?: -1
                    val pointIndex = if (sourceIndex >= 0) sourceIndex else categoryIndex
                    val key = Chart3DKey(
                        seriesId = member.seriesId,
                        categoryIndex = categoryIndex,
                        category = category,
                        stackId = stackId,
                        pointIndex = pointIndex,
                    )
                    segments += Column3DSegment(
                        key = key,
                        seriesName = member.seriesName,
                        value = rawValue,
                        plottedValue = tip - base,
                        stackTotal = totals.getOrElse(categoryIndex) { 0.0 },
                        paletteIndex = member.paletteIndex,
                        colorOverride = member.colorOverride,
                        cuboid = Cuboid3D(
                            x = centre.toDouble() + xOffset,
                            width = slotWidth,
                            yStart = animatedStart,
                            yEnd = animatedEnd,
                            z = z,
                            depth = columnDepth,
                            key = key,
                        ),
                        item = items[member.seriesId]?.getOrNull(pointIndex),
                    )
                }
            }
        }

        val totalDepth = when (arrangement) {
            Column3DArrangement.Side -> columnDepth
            Column3DArrangement.Depth -> columnDepth + depthStride * (stackCount - 1)
        }
        return Column3DLayout(
            segments = segments,
            volume = Bounds3D(
                minX = 0.0,
                maxX = plotWidth.toDouble(),
                minY = 0.0,
                maxY = height,
                minZ = 0.0,
                maxZ = max(totalDepth, MIN_VOLUME_DEPTH),
            ),
            stackIds = stackIds,
        )
    }

    /**
     * The signed total of each category's stack, in the caller's own values.
     *
     * From the values and not from the computed extents, for two reasons. The
     * extents are magnitudes — a segment running from `-20` to `0` has a length
     * of twenty whichever way it points — so summing them would report `+4` for
     * a stack of `+3` and `-1`, which is neither what is drawn nor what anybody
     * means by a total. And under percent stacking the extents are fractions,
     * so a total taken from them would be `1.0` for every category and would
     * tell a tooltip nothing.
     */
    private fun stackTotals(members: List<Column3DSeries>): List<Double> {
        val categoryCount = members.maxOfOrNull { it.values.size } ?: 0
        return List(categoryCount) { category ->
            members.sumOf { member ->
                member.values.getOrNull(category)?.takeIf { it.isFinite() } ?: 0.0
            }
        }
    }

    /**
     * A column's natural unit is its own footprint width, and its world is
     * measured in plot pixels — so an absolute depth is taken as it stands.
     */
    private fun resolveDepth(depth: Chart3DDepth, slotWidth: Double): Double = when (depth) {
        Chart3DDepth.Auto -> slotWidth * COLUMN_3D_AUTO_DEPTH
        is Chart3DDepth.Relative -> slotWidth * depth.fraction
        is Chart3DDepth.Absolute -> depth.pixels.toDouble()
    }.let { resolved ->
        if (!resolved.isFinite() || abs(resolved) < MIN_COLUMN_DEPTH) MIN_COLUMN_DEPTH else resolved
    }

    /** A depth row arrangement is sized against this many notional rows. */
    private const val DEPTH_ROWS_REFERENCE = 2.0

    /** Thin enough to read as flat, thick enough that the box is not degenerate. */
    private const val MIN_COLUMN_DEPTH = 1.0

    /** A plot with no columns still has a volume, so the frame has somewhere to be. */
    private const val MIN_VOLUME_DEPTH = 1.0
}

/**
 * What [Chart3DDepth.Auto] resolves to for a column: a fraction of the column's
 * own footprint width, deep enough to read as a solid and shallow enough to
 * keep two heights comparable.
 */
const val COLUMN_3D_AUTO_DEPTH: Double = 0.85
