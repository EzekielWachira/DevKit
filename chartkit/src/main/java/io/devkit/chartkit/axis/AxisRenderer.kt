package io.devkit.chartkit.axis

import androidx.compose.ui.geometry.Offset
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
    plot: ChartRect,
    context: ChartRenderContext,
) {
    if (!axis.config.visible || plot.isEmpty) return

    val colors = context.colors
    val tickLength = if (axis.config.showTicks) context.px(context.dimensions.tickLength) else 0f
    val labelPadding = context.px(context.dimensions.labelPadding)
    val lineWidth = context.px(context.dimensions.axisLineWidth)

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
                            drawText(label.layout, topLeft = Offset(label.at - width, top))
                        }
                    } else {
                        drawText(label.layout, topLeft = Offset(label.at - width / 2f, top))
                    }
                }
                AxisPosition.Top -> {
                    val bottom = plot.top - tickLength - labelPadding - height
                    drawText(label.layout, topLeft = Offset(label.at - width / 2f, bottom))
                }
                AxisPosition.Start ->
                    drawText(
                        label.layout,
                        topLeft = Offset(plot.left - tickLength - labelPadding - width, label.at - height / 2f),
                    )
                AxisPosition.End ->
                    drawText(
                        label.layout,
                        topLeft = Offset(plot.right + tickLength + labelPadding, label.at - height / 2f),
                    )
            }
        }
    }

    axis.title?.let { title ->
        val width = title.size.width.toFloat()
        val height = title.size.height.toFloat()
        when (axis.position) {
            AxisPosition.Bottom ->
                drawText(title, topLeft = Offset(plot.centerX - width / 2f, size.height - height))
            AxisPosition.Top ->
                drawText(title, topLeft = Offset(plot.centerX - width / 2f, 0f))
            // Rotated a quarter turn: a vertical axis title written horizontally
            // needs a gutter as wide as the words, which on a phone is most of
            // the chart.
            AxisPosition.Start -> rotate(degrees = -90f, pivot = Offset(height / 2f, plot.centerY)) {
                drawText(title, topLeft = Offset(height / 2f - width / 2f, plot.centerY - height / 2f))
            }
            AxisPosition.End -> rotate(degrees = 90f, pivot = Offset(size.width - height / 2f, plot.centerY)) {
                drawText(
                    title,
                    topLeft = Offset(size.width - height / 2f - width / 2f, plot.centerY - height / 2f),
                )
            }
        }
    }
}
