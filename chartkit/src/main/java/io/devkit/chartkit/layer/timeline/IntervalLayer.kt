package io.devkit.chartkit.layer.timeline

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.coordinate.CartesianCoordinates
import io.devkit.chartkit.formatter.ChartTimeFormatter
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.timeline.TimelineEntry
import io.devkit.chartkit.timeline.TimelineModel

/** What an interval chart writes on a bar wide enough for it. */
enum class IntervalLabels {
    None,

    /** The entry's own name, inside the bar. The default. */
    Inside,
}

/**
 * Events and durations along a time axis, in lanes.
 *
 * ```text
 * Room A   ▐████████▌      ▐██████▌
 * Room B        ▐██████████▌     ◆
 * Room C   ▐███▌      ▐███████████▌
 *          09:00   12:00   15:00
 * ```
 *
 * ### One layer for three charts
 *
 * A point timeline, a duration chart and a Gantt-style task view differ in what
 * the caller's lambdas return, not in how they are drawn: a point event is an
 * interval with no end, a task is an interval with a progress overlay, and a
 * milestone is a marker. Three layers would have been three hit tests and three
 * accessibility adapters for one shape.
 *
 * ### Rows on the value axis
 *
 * Lanes and their rows sit at **integer positions on the value axis**, exactly
 * as a heatmap's rows do, and the chart labels that axis through
 * [io.devkit.chartkit.axis.ChartAxis.ticks]. That is what lets the whole
 * Cartesian engine — the time scale, the viewport, zoom, pan, the crosshair, the
 * annotations, range selection — work here unchanged, rather than a second
 * coordinate system that would have needed all of them again.
 */
@Suppress("LongParameterList")
internal class IntervalLayer(
    override val id: String,
    private val model: TimelineModel,
    private val seriesId: String,
    private val seriesName: String,
    private val labels: IntervalLabels,
    private val showProgress: Boolean,
    private val showDependencies: Boolean,
    private val timeFormatter: ChartTimeFormatter,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.cartesian
        if (coordinates.plotArea.isEmpty || model.isEmpty) return
        val reveal = context.reveal.coerceIn(0f, 1f)
        val radius = context.px(context.dimensions.timelineBarCornerRadius)
        val milestoneRadius = context.px(context.dimensions.milestoneRadius)

        drawLaneSeparators(scope, context)

        model.entries.forEachIndexed { index, entry ->
            val row = model.absoluteRow(index)
            val colour = entry.colorOverride?.let { Color(it) } ?: context.colors.timeline.interval

            if (entry.isMilestone || !entry.isInterval) {
                val centre = pointFor(coordinates, entry.start, row) ?: return@forEachIndexed
                drawMilestone(scope, centre, milestoneRadius * reveal, colour, entry.isMilestone, context)
            } else {
                val rect = barFor(coordinates, entry, row, reveal) ?: return@forEachIndexed
                if (rect.width <= 0f || rect.height <= 0f) return@forEachIndexed
                scope.drawRoundRect(
                    color = colour,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    cornerRadius = CornerRadius(radius, radius),
                )
                if (showProgress) drawProgress(scope, context, entry, rect, radius)
            }

            if (isSelected(context, index)) {
                val rect = barFor(coordinates, entry, row, 1f)
                    ?: pointFor(coordinates, entry.start, row)?.let { centre ->
                        ChartRect(
                            centre.x - milestoneRadius,
                            centre.y - milestoneRadius,
                            centre.x + milestoneRadius,
                            centre.y + milestoneRadius,
                        )
                    }
                    ?: return@forEachIndexed
                scope.drawRoundRect(
                    color = context.colors.selectionGuide,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }
        }

        if (showDependencies && reveal >= 1f) drawDependencies(scope, context)
        if (labels != IntervalLabels.None && reveal >= 1f) drawLabels(scope, context)
    }

    /**
     * The completed part of a task, drawn inside its own bar.
     *
     * Inside rather than as a second bar beneath it, so "60% of this task" is
     * read against the task's own length instead of against the axis.
     */
    private fun drawProgress(
        scope: DrawScope,
        context: ChartRenderContext,
        entry: TimelineEntry,
        rect: ChartRect,
        radius: Float,
    ) {
        val progress = entry.progress ?: return
        if (progress <= 0.0) return
        val inset = rect.height * PROGRESS_INSET
        val width = (rect.width * progress).toFloat()
        if (width <= 0f) return
        scope.drawRoundRect(
            color = context.colors.timeline.progress,
            topLeft = Offset(rect.left, rect.top + inset),
            size = Size(width, (rect.height - inset * 2f).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(radius, radius),
        )
    }

    /**
     * A diamond for a milestone, a dot for a plain point event.
     *
     * Two shapes, not two colours: a reader who cannot distinguish the colours
     * can still tell a deadline from an occurrence.
     */
    private fun drawMilestone(
        scope: DrawScope,
        centre: ChartOffset,
        radius: Float,
        colour: Color,
        isMilestone: Boolean,
        context: ChartRenderContext,
    ) {
        if (radius <= 0f) return
        if (!isMilestone) {
            scope.drawCircle(colour, radius * POINT_EVENT_FACTOR, Offset(centre.x, centre.y))
            return
        }
        val path = Path().apply {
            moveTo(centre.x, centre.y - radius)
            lineTo(centre.x + radius, centre.y)
            lineTo(centre.x, centre.y + radius)
            lineTo(centre.x - radius, centre.y)
            close()
        }
        scope.drawPath(path, context.colors.timeline.milestone)
    }

    private fun drawLaneSeparators(scope: DrawScope, context: ChartRenderContext) {
        if (model.lanes.size <= 1) return
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        var offset = 0
        model.lanes.dropLast(1).forEachIndexed { index, _ ->
            offset += model.rowsPerLane.getOrElse(index) { 1 }
            // Between two lanes, not on a row: the boundary sits at the half
            // step, so no separator is ever drawn through an interval bar.
            val position = coordinates.positionOfValue(offset - 0.5)
            if (!position.isFinite()) return@forEachIndexed
            val a = coordinates.pointAt(coordinates.domainOf(ChartOffset(plot.left, plot.top)), position)
            val b = coordinates.pointAt(
                coordinates.domainOf(ChartOffset(plot.right, plot.bottom)),
                position,
            )
            scope.drawLine(
                color = context.colors.timeline.laneSeparator,
                start = Offset(a.x, a.y),
                end = Offset(b.x, b.y),
                strokeWidth = context.px(context.dimensions.gridLineWidth),
            )
        }
    }

    /**
     * Direct connectors between dependent entries.
     *
     * Direct, and deliberately not routed around the bars in between. Routing
     * arrows well is an edge-routing pass over the whole chart; routing them
     * badly puts arrows through the tasks they connect, which is worse than a
     * straight line a reader can follow. A caller who needs elaborate routing
     * has the dependency model and a custom layer.
     */
    private fun drawDependencies(scope: DrawScope, context: ChartRenderContext) {
        if (model.dependencies.isEmpty()) return
        val coordinates = context.cartesian
        model.dependencies.forEach { dependency ->
            val from = model.entries.getOrNull(dependency.fromIndex) ?: return@forEach
            val to = model.entries.getOrNull(dependency.toIndex) ?: return@forEach
            val start = pointFor(coordinates, from.end ?: from.start, model.absoluteRow(dependency.fromIndex))
                ?: return@forEach
            val end = pointFor(coordinates, to.start, model.absoluteRow(dependency.toIndex))
                ?: return@forEach
            scope.drawLine(
                color = context.colors.timeline.laneSeparator,
                start = Offset(start.x, start.y),
                end = Offset(end.x, end.y),
                strokeWidth = context.px(context.dimensions.gridLineWidth),
            )
        }
    }

    private fun drawLabels(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.cartesian
        val style = context.typography.nodeLabel.copy(color = context.colors.timeline.laneLabel)
        val padding = context.px(context.dimensions.labelPadding)
        model.entries.forEachIndexed { index, entry ->
            if (!entry.isInterval || entry.isMilestone) return@forEachIndexed
            val rect = barFor(coordinates, entry, model.absoluteRow(index), 1f) ?: return@forEachIndexed
            val layout = context.textMeasurer.measure(entry.label, style, maxLines = 1)
            // Measured, not estimated: a label wider than its bar would read as
            // belonging to the neighbouring task it spills into.
            if (layout.size.width + padding * 2f > rect.width) return@forEachIndexed
            if (layout.size.height > rect.height) return@forEachIndexed
            scope.drawText(
                textLayoutResult = layout,
                topLeft = Offset(rect.left + padding, rect.centerY - layout.size.height / 2f),
            )
        }
    }

    /** One entry's bar, or `null` when it cannot be placed. */
    private fun barFor(
        coordinates: CartesianCoordinates,
        entry: TimelineEntry,
        row: Int,
        reveal: Float,
    ): ChartRect? {
        val end = entry.end ?: return null
        val from = coordinates.positionOfDomain(entry.start.toDouble()) ?: return null
        val toFull = coordinates.positionOfDomain(end.toDouble()) ?: return null
        if (!from.isFinite() || !toFull.isFinite()) return null
        // Bars grow from their own start, which is what makes a Gantt chart
        // read as tasks beginning rather than as bars sliding in from the left.
        val to = from + (toFull - from) * reveal
        val half = rowHalfHeight(coordinates, row)
        val top = coordinates.positionOfValue(row - half)
        val bottom = coordinates.positionOfValue(row + half)
        if (!top.isFinite() || !bottom.isFinite()) return null
        val a = coordinates.pointAt(from, top)
        val b = coordinates.pointAt(to, bottom)
        return ChartRect(a.x, a.y, b.x, b.y).normalized
    }

    private fun pointFor(
        coordinates: CartesianCoordinates,
        instant: Long,
        row: Int,
    ): ChartOffset? {
        val position = coordinates.positionOfDomain(instant.toDouble()) ?: return null
        val value = coordinates.positionOfValue(row.toDouble())
        if (!position.isFinite() || !value.isFinite()) return null
        return coordinates.pointAt(position, value)
    }

    /** Half a row's height, in value-axis units. */
    private fun rowHalfHeight(coordinates: CartesianCoordinates, row: Int): Double = ROW_FILL / 2.0

    private fun isSelected(context: ChartRenderContext, index: Int): Boolean =
        context.selection?.let { it.seriesId == seriesId && it.pointIndex == index } == true

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val coordinates = context.cartesian
        val touch = context.px(context.dimensions.milestoneRadius) * TOUCH_FACTOR

        model.entries.forEachIndexed { index, entry ->
            val row = model.absoluteRow(index)
            val rect = barFor(coordinates, entry, row, 1f)
            val hit = when {
                rect != null -> rect.contains(point)
                else -> pointFor(coordinates, entry.start, row)?.let { centre ->
                    kotlin.math.abs(centre.x - point.x) <= touch &&
                        kotlin.math.abs(centre.y - point.y) <= touch
                } == true
            }
            if (!hit) return@forEachIndexed
            val anchor = rect?.let { ChartOffset(it.centerX, it.top) }
                ?: pointFor(coordinates, entry.start, row)
                ?: return@forEachIndexed
            return ChartSelection(
                seriesId = seriesId,
                seriesName = seriesName,
                seriesIndex = 0,
                pointIndex = index,
                x = ChartX.Time(entry.start),
                y = row.toDouble(),
                item = entry.item,
                position = anchor,
            )
        }
        return null
    }

    override fun describe(): List<ChartLayerSummary> {
        if (model.isEmpty) return emptyList()
        return listOf(
            ChartLayerSummary(
                seriesId = seriesId,
                seriesName = seriesName,
                pointCount = model.entries.size,
                entries = model.entries.map { entry ->
                    ChartLayerEntry(
                        label = entry.label,
                        // A timeline's "value" is a moment, not a magnitude, so
                        // the detail carries the whole statement and the numeric
                        // slot stays empty rather than announcing a row index.
                        value = null,
                        detail = describeEntry(entry),
                    )
                },
            ),
        )
    }

    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? {
        val entry = model.entries.getOrNull(selection.pointIndex) ?: return null
        return describeEntry(entry)
    }

    /** The event, its lane, and when it happened or how long it ran. */
    private fun describeEntry(entry: TimelineEntry): String = buildString {
        append(entry.label)
        if (model.lanes.size > 1) {
            append(", ")
            append(entry.lane)
        }
        append(": ")
        append(timeFormatter.format(entry.start))
        entry.end?.let {
            append(" to ")
            append(timeFormatter.format(it))
        }
        if (entry.isMilestone) append(", milestone")
        entry.progress?.let {
            append(", ")
            append(Math.round(it * 100.0))
            append(" percent complete")
        }
        append(".")
    }

    private companion object {
        /** How much of a row an interval bar fills, in value-axis units. */
        const val ROW_FILL = 0.62

        const val PROGRESS_INSET = 0.28f
        const val POINT_EVENT_FACTOR = 0.7f

        /** Milestones are small; fingers are not. */
        const val TOUCH_FACTOR = 1.8f
    }
}
