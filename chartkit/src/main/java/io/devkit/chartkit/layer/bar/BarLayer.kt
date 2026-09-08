package io.devkit.chartkit.layer.bar

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.geometry.BarSlice
import io.devkit.chartkit.geometry.ChartMath
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX
import kotlin.math.abs
import kotlin.math.min

/** Identity and source data for one bar series, alongside its computed slices. */
internal class BarSeriesGeometry(
    val seriesId: String,
    val seriesName: String,
    val seriesIndex: Int,
    val paletteIndex: Int,
    val colorOverride: Int?,
    val items: List<Any?>,
    val categoryLabels: List<String>,
    /** Value per category, in category order; `null` where the series has none. */
    val values: List<Double?>,
    /**
     * The index in the caller's own list for each category, or `-1`.
     *
     * Categories are the chart's ordering; the caller's list is theirs. This is
     * the map between them, and it is what lets a tooltip hand back the right
     * object for a category the series did not list first.
     */
    val sourceIndices: List<Int>,
)

/**
 * Bars: single, grouped, stacked and 100% stacked, vertical or horizontal.
 *
 * All four groupings and both orientations are one implementation. The slices
 * arrive already positioned from
 * [io.devkit.chartkit.geometry.computeBarSlices], which works in domain and
 * value terms rather than x and y — so "horizontal bar chart" is a parameter
 * here, not a second renderer that would drift from this one the first time
 * either was fixed.
 *
 * @param slices the rectangles to draw, at reveal fraction 1. The reveal is
 *   reapplied per frame so animation does not rebuild the geometry.
 */
internal class BarLayer(
    override val id: String,
    private val series: List<BarSeriesGeometry>,
    private val slices: List<BarSlice>,
    private val cornerRadiusOverride: Dp?,
    private val baseline: Float,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = series.map { it.seriesId }

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        if (slices.isEmpty()) return
        val plot = context.cartesian.plotArea
        if (plot.isEmpty) return

        val radius = context.px(cornerRadiusOverride ?: context.dimensions.barCornerRadius)
        val reveal = context.reveal.coerceIn(0f, 1f)
        val selection = context.selection

        slices.forEach { slice ->
            val rect = slice.rect.grownFromBaseline(baseline, reveal, context)
            if (rect.isEmpty) return@forEach
            val source = series.getOrNull(slice.seriesIndex)
            val colour = source?.colorOverride?.let { Color(it) }
                ?: context.colors.seriesColor(slice.paletteIndex)

            // The radius is capped at half the bar's shorter side. Without the
            // cap a 4dp radius on a 3px bar produces a rounded rect wider than
            // the bar, which the renderer draws as a lens.
            val effectiveRadius = min(radius, min(abs(rect.width), abs(rect.height)) / 2f)

            val shape = barPath(
                rect = rect,
                radius = effectiveRadius,
                roundFarEnd = slice.isBarEnd,
                positive = slice.plottedValue >= 0.0,
                vertical = context.cartesian.orientation.isVertical,
            )
            scope.drawPath(shape, colour)

            val isSelected = selection != null &&
                selection.seriesId == source?.seriesId &&
                selection.pointIndex == slice.pointIndex
            if (isSelected) {
                // A wash *plus* an outline. Emphasis by opacity alone is
                // invisible to a reader with low contrast sensitivity, and
                // invisible again on a light bar against a light surface.
                scope.drawPath(shape, context.colors.selectionHighlight)
                scope.drawPath(
                    path = shape,
                    color = context.colors.selectionGuide,
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }
        }
    }

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        if (slices.isEmpty()) return null

        // A rectangle test first: a bar is a real target and the reader aimed
        // at it. Only when nothing is hit does the domain-nearest fallback run,
        // which is what makes a scrub across a bar chart still select something.
        slices.firstOrNull { it.rect.contains(point) }?.let { return it.toSelection(context) }
        if (mode == HitTestMode.Contains) {
            // A tap in the band but above a short bar still means that bar.
            val categoryScale = context.cartesian.categories ?: return null
            val along = context.cartesian.domainOf(point)
            val index = categoryScale.indexAt(along)
            if (index < 0) return null
            val pointerValue = context.cartesian.valueOf(point)
            return slices.filter { it.categoryIndex == index }
                .minByOrNull { slice ->
                    abs(
                        pointerValue - context.cartesian.valueOf(
                            ChartOffset(slice.rect.centerX, slice.rect.centerY),
                        ),
                    )
                }
                ?.toSelection(context)
        }

        val along = context.cartesian.domainOf(point)
        return slices.minByOrNull { slice ->
            abs(context.cartesian.domainOf(ChartOffset(slice.rect.centerX, slice.rect.centerY)) - along)
        }?.toSelection(context)
    }

    /**
     * Every series' value in the selected category.
     *
     * What makes a grouped or stacked bar chart's tooltip able to report the
     * whole column rather than only the segment the finger landed on.
     */
    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        val label = (selection.x as? ChartX.Category)?.label ?: return emptyList()
        return series.mapNotNull { s ->
            val index = s.categoryLabels.indexOf(label)
            if (index < 0) return@mapNotNull null
            val value = s.values.getOrNull(index) ?: return@mapNotNull null
            ChartTooltipEntry(
                seriesId = s.seriesId,
                seriesName = s.seriesName,
                value = value,
                item = s.sourceIndices.getOrNull(index)?.let { s.items.getOrNull(it) },
                paletteIndex = s.paletteIndex,
            )
        }
    }

    override fun describe(): List<ChartLayerSummary> = series.map { s ->
        ChartLayerSummary(
            seriesId = s.seriesId,
            seriesName = s.seriesName,
            pointCount = s.values.size,
            entries = s.values.mapIndexed { index, value ->
                ChartLayerEntry(
                    label = s.categoryLabels.getOrElse(index) { index.toString() },
                    value = value,
                )
            },
        )
    }

    /** Positions of every drawn bar, for the value-label layer to sit above. */
    internal fun sliceGeometry(): List<BarSlice> = slices

    internal fun seriesFor(slice: BarSlice): BarSeriesGeometry? = series.getOrNull(slice.seriesIndex)

    private fun BarSlice.toSelection(context: ChartRenderContext): AnyChartSelection? {
        val source = series.getOrNull(seriesIndex) ?: return null
        val label = source.categoryLabels.getOrElse(categoryIndex) { categoryIndex.toString() }
        val anchor = if (context.cartesian.orientation.isVertical) {
            ChartOffset(rect.centerX, min(rect.top, rect.bottom))
        } else {
            ChartOffset(maxOf(rect.left, rect.right), rect.centerY)
        }
        return ChartSelection(
            seriesId = source.seriesId,
            seriesName = source.seriesName,
            seriesIndex = source.seriesIndex,
            pointIndex = pointIndex,
            x = ChartX.Category(label),
            y = value,
            item = source.items.getOrNull(pointIndex),
            position = anchor,
        )
    }

    /**
     * A bar rounded only at its far end.
     *
     * The baseline end stays square: a bar with rounded bottom corners appears
     * to float above the axis it is measured from, and an interior stack
     * segment with rounded corners breaks the bar into pieces.
     */
    private fun barPath(
        rect: ChartRect,
        radius: Float,
        roundFarEnd: Boolean,
        positive: Boolean,
        vertical: Boolean,
    ): Path {
        val path = Path()
        if (radius <= 0f || !roundFarEnd) {
            path.addRect(Rect(rect.left, rect.top, rect.right, rect.bottom))
            return path
        }
        val corner = CornerRadius(radius, radius)
        val zero = CornerRadius.Zero
        // Which end is "far" depends on the sign as well as the orientation: a
        // negative bar on a vertical chart is rounded at its bottom.
        val roundRect = when {
            vertical && positive -> RoundRect(
                rect = Rect(rect.left, rect.top, rect.right, rect.bottom),
                topLeft = corner, topRight = corner, bottomRight = zero, bottomLeft = zero,
            )
            vertical -> RoundRect(
                rect = Rect(rect.left, rect.top, rect.right, rect.bottom),
                topLeft = zero, topRight = zero, bottomRight = corner, bottomLeft = corner,
            )
            positive -> RoundRect(
                rect = Rect(rect.left, rect.top, rect.right, rect.bottom),
                topLeft = zero, topRight = corner, bottomRight = corner, bottomLeft = zero,
            )
            else -> RoundRect(
                rect = Rect(rect.left, rect.top, rect.right, rect.bottom),
                topLeft = corner, topRight = zero, bottomRight = zero, bottomLeft = corner,
            )
        }
        path.addRoundRect(roundRect)
        return path
    }

    /**
     * The bar at [reveal] of its full length, still anchored to the baseline.
     *
     * Applied here rather than baked into the slices so the same geometry is
     * reused across every animation frame — and so a bar's hit rectangle always
     * matches what is on screen, including mid-animation.
     */
    private fun ChartRect.grownFromBaseline(
        baseline: Float,
        reveal: Float,
        context: ChartRenderContext,
    ): ChartRect {
        if (reveal >= 1f) return this
        return if (context.cartesian.orientation.isVertical) {
            ChartRect(
                left = left,
                top = ChartMath.lerp(baseline, top, reveal),
                right = right,
                bottom = ChartMath.lerp(baseline, bottom, reveal),
            ).normalized
        } else {
            ChartRect(
                left = ChartMath.lerp(baseline, left, reveal),
                top = top,
                right = ChartMath.lerp(baseline, right, reveal),
                bottom = bottom,
            ).normalized
        }
    }
}
