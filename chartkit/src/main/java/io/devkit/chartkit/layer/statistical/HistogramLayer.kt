package io.devkit.chartkit.layer.statistical

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import io.devkit.chartkit.stats.HistogramBin
import io.devkit.chartkit.stats.HistogramMetric
import kotlin.math.abs
import kotlin.math.min

/**
 * Histogram bars.
 *
 * ### Why not the bar layer
 *
 * A histogram's bars are not categories. They occupy a **continuous** axis,
 * they are adjacent by construction, and their widths carry meaning — under
 * [io.devkit.chartkit.stats.HistogramBins.Custom] they are deliberately
 * unequal. A category scale would space them evenly and lose exactly the fact
 * that distinguishes a histogram from a bar chart: that a wide bin covers more
 * of the domain than a narrow one.
 *
 * So the bars are positioned by the same continuous domain scale a line chart
 * uses, and the layer is small enough that sharing one with
 * [io.devkit.chartkit.layer.bar.BarLayer] would cost more in conditionals than
 * it saves in lines.
 */
internal class HistogramLayer(
    override val id: String,
    private val bins: List<HistogramBin>,
    private val seriesId: String,
    private val seriesName: String,
    private val metric: HistogramMetric,
    private val items: List<Any?>,
    private val paletteIndex: Int,
    private val colorOverride: Int?,
    private val cornerRadiusOverride: androidx.compose.ui.unit.Dp?,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        if (plot.isEmpty || bins.isEmpty()) return

        val colour = colorOverride?.let { Color(it) } ?: context.colors.seriesColor(paletteIndex)
        val reveal = context.reveal.coerceIn(0f, 1f)
        val baseline = coordinates.baseline.coerceIn(plot.top, plot.bottom)
        val radius = context.px(cornerRadiusOverride ?: context.dimensions.barCornerRadius)
        val selection = context.selection

        bins.forEachIndexed { index, bin ->
            val rect = rectFor(bin, context, baseline, reveal) ?: return@forEachIndexed
            if (rect.width <= 0f) return@forEachIndexed

            // Bars shorter than their own corner radius would be drawn as a
            // lens rather than a bar.
            val effectiveRadius = min(radius, min(abs(rect.width), abs(rect.height)) / 2f)
            val path = topRoundedRect(rect, effectiveRadius)
            scope.drawPath(path, colour)

            val selected = selection?.seriesId == seriesId && selection.pointIndex == index
            if (selected) {
                scope.drawPath(path, context.colors.selectionHighlight)
                scope.drawPath(
                    path = path,
                    color = context.colors.selectionGuide,
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }
        }
    }

    /** The bin's rectangle at [reveal] of its height, anchored to the baseline. */
    private fun rectFor(
        bin: HistogramBin,
        context: ChartRenderContext,
        baseline: Float,
        reveal: Float,
    ): ChartRect? {
        val coordinates = context.cartesian
        val left = coordinates.positionOfDomain(bin.start) ?: return null
        val right = coordinates.positionOfDomain(bin.end) ?: return null
        if (!left.isFinite() || !right.isFinite()) return null

        val top = coordinates.positionOfValue(bin.value)
        if (!top.isFinite()) return null

        // A hairline of separation, so adjacent bins read as separate bars
        // without a gap wide enough to suggest empty ranges between them.
        val spacing = context.px(context.dimensions.heatmapCellSpacing) / 2f
        val insetLeft = minOf(left, right) + spacing
        val insetRight = maxOf(left, right) - spacing

        return ChartRect(
            left = if (insetRight > insetLeft) insetLeft else minOf(left, right),
            top = ChartMath.lerp(baseline, top, reveal),
            right = if (insetRight > insetLeft) insetRight else maxOf(left, right),
            bottom = baseline,
        ).normalized
    }

    private fun topRoundedRect(rect: ChartRect, radius: Float): Path {
        val path = Path()
        if (radius <= 0f) {
            path.addRect(Rect(rect.left, rect.top, rect.right, rect.bottom))
            return path
        }
        val corner = CornerRadius(radius, radius)
        path.addRoundRect(
            RoundRect(
                rect = Rect(rect.left, rect.top, rect.right, rect.bottom),
                topLeft = corner,
                topRight = corner,
                bottomRight = CornerRadius.Zero,
                bottomLeft = CornerRadius.Zero,
            ),
        )
        return path
    }

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val coordinates = context.cartesian
        if (bins.isEmpty()) return null
        // By domain position rather than by rectangle: a tap above a short bar
        // still means that bin, and the bins tile the axis with no gaps.
        val domainValue = coordinates.continuousDomain?.invert(point.x) ?: return null
        val index = bins.indexOfFirst { domainValue >= it.start && domainValue <= it.end }
            .takeIf { it >= 0 }
            ?: bins.indices.minByOrNull { abs(bins[it].center - domainValue) }
            ?: return null
        return selectionFor(index, context)
    }

    private fun selectionFor(index: Int, context: ChartRenderContext): AnyChartSelection? {
        val bin = bins.getOrNull(index) ?: return null
        val coordinates = context.cartesian
        val centre = coordinates.positionOfDomain(bin.center) ?: return null
        val top = coordinates.positionOfValue(bin.value)
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = index,
            // The bin's interval is its identity, so that is what the tooltip
            // heading and the accessibility announcement both read.
            x = ChartX.Category(binLabel(bin)),
            y = bin.value,
            // The caller's observations in this bin, rather than one of them:
            // a histogram bar *is* a group, and handing back a single item
            // would be arbitrary.
            item = bin.sourceIndices.mapNotNull { items.getOrNull(it) },
            position = ChartOffset(centre, top),
        )
    }

    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        if (selection.seriesId != seriesId) return emptyList()
        val bin = bins.getOrNull(selection.pointIndex) ?: return emptyList()
        return listOf(
            ChartTooltipEntry(
                seriesId = seriesId,
                seriesName = metricName(),
                value = bin.value,
                item = bin.sourceIndices.mapNotNull { items.getOrNull(it) },
                paletteIndex = paletteIndex,
            ),
        )
    }

    override fun describe(): List<ChartLayerSummary> {
        val fullest = bins.maxByOrNull { it.count }
        return listOf(
            ChartLayerSummary(
                seriesId = seriesId,
                seriesName = seriesName,
                pointCount = bins.size,
                entries = buildList {
                    // The most populated bin first: on a histogram it is the
                    // fact a sighted reader takes from the picture in one
                    // glance, and a screen-reader user should not have to
                    // assemble it from a list of forty bins.
                    if (fullest != null) {
                        add(
                            ChartLayerEntry(
                                label = "Most populated bin",
                                value = fullest.value,
                                detail = "Most populated bin: ${binLabel(fullest)}, " +
                                    "${fullest.count} observations",
                            ),
                        )
                    }
                    bins.forEach { bin ->
                        add(
                            ChartLayerEntry(
                                label = binLabel(bin),
                                value = bin.value,
                                detail = "${binLabel(bin)}: ${bin.count} observations",
                            ),
                        )
                    }
                },
            ),
        )
    }

    private fun metricName(): String = when (metric) {
        HistogramMetric.Count -> "Count"
        HistogramMetric.Percentage -> "Share"
        HistogramMetric.Density -> "Density"
    }

    private fun binLabel(bin: HistogramBin): String =
        "${io.devkit.chartkit.layer.scatter.formatShort(bin.start)} to " +
            io.devkit.chartkit.layer.scatter.formatShort(bin.end)
}
