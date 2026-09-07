package io.devkit.chartkit.layer.crosshair

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection

/**
 * The guides drawn through the current selection, and the values they read.
 *
 * A layer rather than a few lines inside `LineChart`, and the reason matters:
 * a crosshair belongs to the *coordinate system*, not to a chart type. Written
 * here, a candlestick, a scatter or a combined chart gets one by adding itself
 * to the same engine. Written inside `LineChart`, the second chart that wanted
 * one would copy it, and the two would drift.
 *
 * The guides are expressed in domain and value terms, so a horizontal bar
 * chart's crosshair runs the correct way round without a second code path.
 *
 * @param domainLabel formats the selection's domain value for the axis chip.
 *   Supplied by the chart from its own axis formatter, so the chip and the axis
 *   never disagree about how a date or a number is written.
 * @param valueLabel formats the selection's value.
 */
internal class CrosshairLayer(
    private val config: CrosshairConfig,
    private val domainLabel: (AnyChartSelection) -> String,
    private val valueLabel: (Double) -> String,
    override val id: String = "crosshair",
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = emptyList()

    // The axis chips are drawn in the gutter, outside the plot, by design.
    override val clipToPlot: Boolean get() = false

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        if (!config.enabled) return
        val selection = context.selection ?: return
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        // Suppressed during the reveal: a crosshair over a chart that is still
        // drawing itself in points at values that are not there yet.
        if (plot.isEmpty || context.reveal < 1f) return

        val width = context.px(context.dimensions.crosshairWidth)
        val effect = PathEffect.dashPathEffect(floatArrayOf(width * 4f, width * 4f))
        val colour = context.colors.crosshairGuide
        val vertical = coordinates.orientation.isVertical

        // "Domain" and "value" rather than x and y: on a horizontal chart the
        // domain guide is the horizontal one.
        val domainPosition = if (vertical) selection.position.x else selection.position.y
        val valuePosition = if (vertical) selection.position.y else selection.position.x

        if (config.vertical) {
            drawGuide(scope, plot, domainPosition, alongDomain = true, vertical, colour, width, effect)
        }
        if (config.horizontal) {
            drawGuide(scope, plot, valuePosition, alongDomain = false, vertical, colour, width, effect)
        }

        if (config.showAxisLabels) {
            if (config.vertical) {
                drawAxisChip(
                    scope = scope,
                    context = context,
                    text = domainLabel(selection),
                    plot = plot,
                    position = domainPosition,
                    onDomainAxis = true,
                    vertical = vertical,
                )
            }
            if (config.horizontal) {
                drawAxisChip(
                    scope = scope,
                    context = context,
                    text = valueLabel(selection.y),
                    plot = plot,
                    position = valuePosition,
                    onDomainAxis = false,
                    vertical = vertical,
                )
            }
        }
    }

    @Suppress("LongParameterList")
    private fun drawGuide(
        scope: DrawScope,
        plot: ChartRect,
        position: Float,
        alongDomain: Boolean,
        verticalChart: Boolean,
        colour: androidx.compose.ui.graphics.Color,
        width: Float,
        effect: PathEffect,
    ) {
        if (!position.isFinite()) return
        // A domain guide on a vertical chart is a vertical rule; on a
        // horizontal chart the two swap.
        val isVerticalRule = if (alongDomain) verticalChart else !verticalChart
        if (isVerticalRule) {
            if (position < plot.left || position > plot.right) return
            scope.drawLine(colour, Offset(position, plot.top), Offset(position, plot.bottom), width, pathEffect = effect)
        } else {
            if (position < plot.top || position > plot.bottom) return
            scope.drawLine(colour, Offset(plot.left, position), Offset(plot.right, position), width, pathEffect = effect)
        }
    }

    /**
     * A small chip carrying the selected value, on the axis the guide crosses.
     *
     * Clamped inside the plot's extent so the chip at the first or last point
     * does not hang off the edge of the composable.
     */
    @Suppress("LongParameterList")
    private fun drawAxisChip(
        scope: DrawScope,
        context: ChartRenderContext,
        text: String,
        plot: ChartRect,
        position: Float,
        onDomainAxis: Boolean,
        vertical: Boolean,
    ) {
        if (text.isEmpty() || !position.isFinite()) return
        val style = context.typography.crosshairLabel.copy(color = context.colors.crosshairLabelContent)
        val layout: TextLayoutResult = context.textMeasurer.measure(text, style)
        val padding = context.px(context.dimensions.crosshairLabelPadding)
        val boxWidth = layout.size.width + padding * 2f
        val boxHeight = layout.size.height + padding * 2f
        val radius = context.px(context.dimensions.tooltipCornerRadius) / 2f

        val isVerticalRule = if (onDomainAxis) vertical else !vertical
        val left: Float
        val top: Float
        if (isVerticalRule) {
            // Below the plot, centred on the rule.
            left = (position - boxWidth / 2f).coerceIn(
                plot.left,
                (plot.right - boxWidth).coerceAtLeast(plot.left),
            )
            top = plot.bottom + padding
        } else {
            // Beside the plot, centred on the rule.
            left = (plot.left - boxWidth - padding).coerceAtLeast(0f)
            top = (position - boxHeight / 2f).coerceIn(
                plot.top,
                (plot.bottom - boxHeight).coerceAtLeast(plot.top),
            )
        }

        scope.drawRoundRect(
            color = context.colors.crosshairLabelContainer,
            topLeft = Offset(left, top),
            size = Size(boxWidth, boxHeight),
            cornerRadius = CornerRadius(radius, radius),
        )
        scope.drawText(layout, topLeft = Offset(left + padding, top + padding))
    }
}
