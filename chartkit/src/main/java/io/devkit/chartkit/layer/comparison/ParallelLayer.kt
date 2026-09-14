package io.devkit.chartkit.layer.comparison

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ParallelAxis
import io.devkit.chartkit.geometry.ParallelGeometry
import io.devkit.chartkit.geometry.ParallelLayout
import io.devkit.chartkit.geometry.ParallelPolyline
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.state.ChartParallelBrushState

/** What a parallel-coordinates chart writes beside its axes. */
enum class ParallelAxisLabels {

    /** Nothing. */
    None,

    /** The dimension names, above the axes. The default. */
    Names,

    /** Names, and each axis' own minimum and maximum at its ends. */
    NamesAndRange,
}

/** A tap that landed on one row's line. */
data class ParallelRowSelection(
    val rowIndex: Int,
    val groupLabel: String?,
    val item: Any?,
)

/**
 * Many rows compared across many measures, one polyline each.
 *
 * ### Every axis has its own domain
 *
 * The dimensions are in different units, so forcing them onto one scale would
 * flatten every axis but the largest into a line along the bottom. The
 * consequence is stated rather than hidden: **vertical position is comparable
 * only within an axis**. A line high on two axes is high on each separately; it
 * is not "higher overall", because there is no overall.
 *
 * ### Filtered rows stay on the chart
 *
 * A brush mutes the rows it excludes rather than removing them. What a reader
 * is doing when they brush is comparing a subset *against* the whole, and a
 * chart that deleted the rest would answer a different question — and would
 * make each drag rescale the axes underneath the finger doing the dragging.
 *
 * @param geometry arrives already laid out. Brushing, selecting and animating
 *   the reveal do not touch it.
 */
@Suppress("LongParameterList")
internal class ParallelLayer(
    override val id: String,
    private val geometry: ParallelGeometry,
    private val groupNames: List<String>,
    private val items: (Int) -> Any?,
    private val rowValues: (Int, Int) -> Double?,
    private val seriesId: String,
    private val seriesName: String,
    private val valueFormatter: ChartValueFormatter,
    private val labels: ParallelAxisLabels,
    private val brushState: ChartParallelBrushState?,
    private val lineWidth: Float,
    private val groupColorOf: ((Int) -> Int?)? = null,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        if (!context.planar.isDrawable || geometry.axes.isEmpty()) return
        val reveal = context.reveal.coerceIn(0f, 1f)
        val selected = (context.selection?.item as? ParallelRowSelection)?.rowIndex

        geometry.axes.forEach { axis ->
            scope.drawLine(
                color = context.colors.axisLine,
                start = Offset(axis.x, axis.top),
                end = Offset(axis.x, axis.bottom),
                strokeWidth = context.px(context.dimensions.axisLineWidth),
            )
        }

        // Excluded rows first and underneath: the point of a brush is to read
        // the survivors against the whole, and drawing the muted ones on top
        // would grey out the answer.
        val (admitted, excluded) = geometry.polylines.partition { admits(it) }
        excluded.forEach { line ->
            scope.drawPolyline(line, context.colors.flow.linkMuted.copy(alpha = MUTED_ALPHA), reveal, lineWidth)
        }
        admitted.forEach { line ->
            val colour = groupColour(line.groupIndex, context)
            val emphasised = selected == line.rowIndex
            scope.drawPolyline(
                polyline = line,
                color = if (emphasised) colour else colour.copy(alpha = colour.alpha * LINE_ALPHA),
                reveal = reveal,
                width = if (emphasised) lineWidth * SELECTED_WIDTH else lineWidth,
            )
        }

        brushState?.let { brushes -> drawBrushes(scope, context, brushes) }
        if (reveal >= 1f && labels != ParallelAxisLabels.None) {
            geometry.axes.forEach { axis -> drawAxisLabels(scope, context, axis) }
        }
    }

    private fun DrawScope.drawPolyline(
        polyline: ParallelPolyline,
        color: Color,
        reveal: Float,
        width: Float,
    ) {
        polyline.segments.forEach { run ->
            if (run.size < 2) return@forEach
            // Revealed left to right across the axes rather than by fading:
            // every line shares the same x positions, so a fade would show the
            // whole thicket at once at every stage of the animation.
            val visible = (run.size * reveal).toInt().coerceAtLeast(2).coerceAtMost(run.size)
            val path = Path().apply {
                moveTo(run[0].x, run[0].y)
                for (index in 1 until visible) lineTo(run[index].x, run[index].y)
            }
            drawPath(path, color, style = Stroke(width = width))
        }
    }

    private fun drawBrushes(
        scope: DrawScope,
        context: ChartRenderContext,
        brushes: ChartParallelBrushState,
    ) {
        val handleWidth = context.px(context.dimensions.parallelBrushWidth)
        brushes.ranges.forEach { (axisIndex, range) ->
            val axis = geometry.axes.getOrNull(axisIndex) ?: return@forEach
            val top = axis.positionOf(range.endInclusive) ?: return@forEach
            val bottom = axis.positionOf(range.start) ?: return@forEach
            val active = brushes.activeAxis == axisIndex
            scope.drawRect(
                color = context.colors.selectionGuide.copy(
                    alpha = if (active) BRUSH_ACTIVE_ALPHA else BRUSH_ALPHA,
                ),
                topLeft = Offset(axis.x - handleWidth / 2f, minOf(top, bottom)),
                size = Size(handleWidth, kotlin.math.abs(bottom - top)),
            )
        }
    }

    private fun drawAxisLabels(scope: DrawScope, context: ChartRenderContext, axis: ParallelAxis) {
        val style = context.typography.axisLabel.copy(color = context.colors.axisLabel)
        val name = context.textMeasurer.measure(axis.label, style, maxLines = 1)
        val plot = context.planar.plotArea
        val padding = context.px(context.dimensions.labelPadding)

        // The first and last names are pinned inside the plot rather than
        // centred on their axis, which would put half of each outside it.
        val centred = axis.x - name.size.width / 2f
        val left = centred.coerceIn(plot.left, plot.right - name.size.width)
        scope.drawText(name, topLeft = Offset(left, axis.top - name.size.height - padding))

        if (labels != ParallelAxisLabels.NamesAndRange) return
        val small = context.typography.axisLabel.copy(color = context.colors.axisLabel)
        listOf(
            valueFormatter.format(axis.domain.max) to axis.top + padding,
            valueFormatter.format(axis.domain.min) to axis.bottom - padding,
        ).forEachIndexed { index, (text, y) ->
            val layout = context.textMeasurer.measure(text, small, maxLines = 1)
            val x = (axis.x + padding).coerceAtMost(plot.right - layout.size.width)
            val top = if (index == 0) y else y - layout.size.height
            scope.drawText(layout, topLeft = Offset(x, top))
        }
    }

    private fun admits(polyline: ParallelPolyline): Boolean =
        brushState?.admits { axis -> rowValues(polyline.rowIndex, axis) } ?: true

    private fun groupColour(index: Int, context: ChartRenderContext): Color =
        groupColorOf?.invoke(index)?.let { Color(it) } ?: context.colors.seriesColor(index)

    /**
     * The nearest line to the finger, within a forgiving radius.
     *
     * Lines cross constantly, so "the line under the point" is rarely one line.
     * The nearest by perpendicular distance is the one the finger was aiming
     * at; excluded rows are skipped, because a tap that selected a row the
     * reader had just filtered out would undo their own filter.
     */
    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        if (geometry.polylines.isEmpty()) return null
        val tolerance = context.px(context.dimensions.parallelHitRadius)
        val limit = tolerance * tolerance
        var best: ParallelPolyline? = null
        var bestDistance = Float.MAX_VALUE
        geometry.polylines.forEach { line ->
            if (!admits(line)) return@forEach
            val distance = ParallelLayout.distanceSquaredTo(line, point)
            if (distance < bestDistance) {
                bestDistance = distance
                best = line
            }
        }
        val found = best?.takeIf { bestDistance <= limit } ?: return null
        return selectionFor(found, point)
    }

    private fun selectionFor(polyline: ParallelPolyline, point: ChartOffset): AnyChartSelection {
        // Anchored on the nearest axis crossing rather than on the finger, so a
        // tooltip attaches to a vertex of the line it names.
        val anchor = polyline.points.filterNotNull()
            .minByOrNull { kotlin.math.abs(it.x - point.x) }
            ?: ChartOffset(point.x, point.y)
        val group = groupNames.getOrNull(polyline.groupIndex)
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = polyline.groupIndex,
            pointIndex = polyline.rowIndex,
            x = ChartX.Category(group ?: "Row ${polyline.rowIndex}"),
            y = rowValues(polyline.rowIndex, 0) ?: 0.0,
            item = ParallelRowSelection(polyline.rowIndex, group, items(polyline.rowIndex)),
            position = anchor,
        )
    }

    /**
     * Every dimension's value for the selected row.
     *
     * A parallel-coordinates selection is a whole row — that is what a line is —
     * so a tooltip naming one axis would report a fraction of what was tapped.
     */
    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        val target = selection.item as? ParallelRowSelection ?: return emptyList()
        return geometry.axes.mapNotNull { axis ->
            val value = rowValues(target.rowIndex, axis.index) ?: return@mapNotNull null
            ChartTooltipEntry(
                seriesId = seriesId,
                seriesName = axis.label,
                value = value,
                item = target.item,
                paletteIndex = selection.seriesIndex,
            )
        }
    }

    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? {
        val target = selection.item as? ParallelRowSelection ?: return null
        val readings = geometry.axes.joinToString(", ") { axis ->
            val value = rowValues(target.rowIndex, axis.index)
            "${axis.label} ${value?.let { formatter.format(it) } ?: "no value"}"
        }
        return listOfNotNull(target.groupLabel, readings).joinToString(": ")
    }

    override fun describe(): List<ChartLayerSummary> {
        val admitted = geometry.polylines.count { admits(it) }
        return listOf(
            ChartLayerSummary(
                seriesId = seriesId,
                seriesName = seriesName,
                pointCount = admitted,
                // Axes rather than rows: a hundred rows read out one at a time
                // is not an announcement anybody listens to, and the dimensions
                // and their ranges are what orient a reader who cannot see the
                // lines. The brushes are named because they are why the count
                // is what it is.
                entries = geometry.axes.map { axis ->
                    val brush = brushState?.ranges?.get(axis.index)
                    ChartLayerEntry(
                        label = axis.label,
                        value = null,
                        detail = buildString {
                            append(axis.label)
                            append(": ")
                            append(valueFormatter.format(axis.domain.min))
                            append(" to ")
                            append(valueFormatter.format(axis.domain.max))
                            if (brush != null) {
                                append(", filtered to ")
                                append(valueFormatter.format(brush.start))
                                append(" to ")
                                append(valueFormatter.format(brush.endInclusive))
                            }
                        },
                    )
                },
            ),
        )
    }

    private companion object {
        /** A thicket of opaque lines is a block of colour; some transparency is the chart. */
        const val LINE_ALPHA = 0.55f
        const val MUTED_ALPHA = 0.12f
        const val BRUSH_ALPHA = 0.30f
        const val BRUSH_ACTIVE_ALPHA = 0.55f
        const val SELECTED_WIDTH = 2.2f
    }
}
