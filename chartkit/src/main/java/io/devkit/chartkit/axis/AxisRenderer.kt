package io.devkit.chartkit.axis

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.layer.ChartRenderContext

/**
 * One axis, measured and ready to draw.
 *
 * Measurement happens once per layout — laying text out is the expensive part
 * of drawing an axis, and doing it inside the draw pass would repeat it on
 * every animation frame. What reaches [drawAxis] is a list of already-laid-out
 * labels with positions.
 *
 * @param labels the labels that survived overlap resolution, already thinned.
 */
internal class MeasuredAxis(
    val position: AxisPosition,
    val config: ChartAxis,
    val ticks: List<Float>,
    val labels: List<MeasuredAxisLabel>,
    val title: TextLayoutResult?,
    val rotated: Boolean,
    /** Which axis this is, for offsets, grids and diagnostics. */
    val id: ChartAxisId = ChartAxisId.DefaultY,
    /**
     * How far outside the plot edge this axis is drawn.
     *
     * Zero for the axis against the plot; the previous axis' whole gutter for
     * the next one out. Measured by the layout engine — see
     * [io.devkit.chartkit.layout.ChartLayout.axisOffsets].
     */
    val offset: Float = 0f,
    /**
     * The palette slot the labels and title borrow, or `null` for the theme's
     * own axis colour.
     *
     * A slot rather than a resolved colour because the palette lives in the
     * theme and the geometry is built without one — the axis is measured during
     * layout and coloured during the draw, like every series in the chart.
     *
     * Set only by [io.devkit.chartkit.axis.AxisStyleMode.MatchSeries]. The axis
     * *line* is never tinted: a coloured rule across the edge of a plot reads
     * as data, and an axis is not data.
     */
    val accentPaletteIndex: Int? = null,
)

internal class MeasuredAxisLabel(
    val layout: TextLayoutResult,
    /** The tick's position along the axis. */
    val at: Float,
)

/**
 * Draws an axis line, its ticks, its labels and its title.
 *
 * Labels are drawn *outside* the plot area, in the gutter the layout engine
 * reserved for exactly this — which is why no layer needs to know axis labels
 * exist.
 */
internal fun DrawScope.drawAxis(
    axis: MeasuredAxis,
    plotArea: ChartRect,
    context: ChartRenderContext,
) {
    if (!axis.config.visible || plotArea.isEmpty) return

    val colors = context.colors
    val tickLength = if (axis.config.showTicks) context.px(context.dimensions.tickLength) else 0f
    val labelPadding = context.px(context.dimensions.labelPadding)
    val lineWidth = context.px(context.dimensions.axisLineWidth)
    // Unspecified means "whatever the measured text already carries", which is
    // the theme's colour; an accent replaces it without remeasuring.
    val accent = axis.accentPaletteIndex
        ?.let { colors.palette.getOrNull(it % colors.palette.size.coerceAtLeast(1)) }
        ?: Color.Unspecified

    // The edge this axis draws against: the plot's own for the innermost axis,
    // and further out by the gutters of the axes inside it for the rest. Every
    // position below is expressed against `plot`, so nothing else in this
    // function has to know that a second axis on the same side exists.
    val plot = axis.offset.takeIf { it.isFinite() && it > 0f }?.let { offset ->
        when (axis.position) {
            AxisPosition.Bottom -> ChartRect(plotArea.left, plotArea.top, plotArea.right, plotArea.bottom + offset)
            AxisPosition.Top -> ChartRect(plotArea.left, plotArea.top - offset, plotArea.right, plotArea.bottom)
            AxisPosition.Start -> ChartRect(plotArea.left - offset, plotArea.top, plotArea.right, plotArea.bottom)
            AxisPosition.End -> ChartRect(plotArea.left, plotArea.top, plotArea.right + offset, plotArea.bottom)
        }
    } ?: plotArea

    if (axis.config.showLine) {
        val (start, end) = when (axis.position) {
            AxisPosition.Bottom -> Offset(plot.left, plot.bottom) to Offset(plot.right, plot.bottom)
            AxisPosition.Top -> Offset(plot.left, plot.top) to Offset(plot.right, plot.top)
            AxisPosition.Start -> Offset(plot.left, plot.top) to Offset(plot.left, plot.bottom)
            AxisPosition.End -> Offset(plot.right, plot.top) to Offset(plot.right, plot.bottom)
        }
        drawLine(colors.axisLine, start, end, lineWidth)
    }

    if (axis.config.showTicks) {
        axis.ticks.forEach { at ->
            if (!at.isFinite()) return@forEach
            when (axis.position) {
                AxisPosition.Bottom ->
                    drawLine(colors.axisLine, Offset(at, plot.bottom), Offset(at, plot.bottom + tickLength), lineWidth)
                AxisPosition.Top ->
                    drawLine(colors.axisLine, Offset(at, plot.top - tickLength), Offset(at, plot.top), lineWidth)
                AxisPosition.Start ->
                    drawLine(colors.axisLine, Offset(plot.left - tickLength, at), Offset(plot.left, at), lineWidth)
                AxisPosition.End ->
                    drawLine(colors.axisLine, Offset(plot.right, at), Offset(plot.right + tickLength, at), lineWidth)
            }
        }
    }

    if (axis.config.showLabels) {
        axis.labels.forEach { label ->
            val width = label.layout.size.width.toFloat()
            val height = label.layout.size.height.toFloat()
            when (axis.position) {
                AxisPosition.Bottom -> {
                    val top = plot.bottom + tickLength + labelPadding
                    if (axis.rotated) {
                        // Rotated about the tick so the label's right end stays
                        // under the value it belongs to; anchoring by the centre
                        // instead makes long labels appear to point at their
                        // neighbours.
                        rotate(degrees = -45f, pivot = Offset(label.at, top)) {
                            drawText(label.layout, color = accent, topLeft = Offset(label.at - width, top))
                        }
                    } else {
                        drawText(label.layout, color = accent, topLeft = Offset(label.at - width / 2f, top))
                    }
                }
                AxisPosition.Top -> {
                    val bottom = plot.top - tickLength - labelPadding - height
                    drawText(label.layout, color = accent, topLeft = Offset(label.at - width / 2f, bottom))
                }
                AxisPosition.Start ->
                    drawText(
                        label.layout,
                        color = accent,
                        topLeft = Offset(plot.left - tickLength - labelPadding - width, label.at - height / 2f),
                    )
                AxisPosition.End ->
                    drawText(
                        label.layout,
                        color = accent,
                        topLeft = Offset(plot.right + tickLength + labelPadding, label.at - height / 2f),
                    )
            }
        }
    }

    axis.title?.let { title ->
        val width = title.size.width.toFloat()
        val height = title.size.height.toFloat()
        // The title sits just outside this axis' own labels rather than at the
        // edge of the canvas. With one axis per side the two are the same
        // place; with two, anchoring to the canvas would stack both titles in
        // the same strip and leave the inner axis' gutter empty.
        val labelExtent = if (!axis.config.showLabels || axis.labels.isEmpty()) {
            0f
        } else if (axis.position.isHorizontal) {
            axis.labels.maxOf { it.layout.size.height }.toFloat()
        } else {
            axis.labels.maxOf { it.layout.size.width }.toFloat()
        }
        val band = tickLength + labelPadding + labelExtent + labelPadding
        when (axis.position) {
            AxisPosition.Bottom ->
                drawText(title, color = accent, topLeft = Offset(plot.centerX - width / 2f, plot.bottom + band))
            AxisPosition.Top ->
                drawText(title, color = accent, topLeft = Offset(plot.centerX - width / 2f, plot.top - band - height))
            // Rotated a quarter turn: a vertical axis title written horizontally
            // needs a gutter as wide as the words, which on a phone is most of
            // the chart.
            AxisPosition.Start -> {
                val centre = plot.left - band - height / 2f
                rotate(degrees = -90f, pivot = Offset(centre, plot.centerY)) {
                    drawText(
                        title,
                        color = accent,
                        topLeft = Offset(centre - width / 2f, plot.centerY - height / 2f),
                    )
                }
            }
            AxisPosition.End -> {
                val centre = plot.right + band + height / 2f
                rotate(degrees = 90f, pivot = Offset(centre, plot.centerY)) {
                    drawText(
                        title,
                        color = accent,
                        topLeft = Offset(centre - width / 2f, plot.centerY - height / 2f),
                    )
                }
            }
        }
    }
}
