package io.devkit.chartkit.layer.range

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.devkit.chartkit.geometry.ChartMath
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartRenderContext

/**
 * The band a reader has dragged out across the domain.
 *
 * Draws a wash **and** two edge markers. The markers are not decoration: a
 * region distinguished only by a tint disappears for a reader with low contrast
 * sensitivity, and on a busy chart the tint is easy to mistake for a series
 * fill. The edges say where the selection actually stops.
 *
 * The range is stored as fractions of the **full** domain, so the band survives
 * a zoom: selecting March to July and then zooming in leaves the band over the
 * same dates rather than over the same pixels. Mapping through the viewport is
 * what makes that work, and it happens here.
 */
internal class RangeSelectionLayer(
    override val id: String = "range",
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = emptyList()

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val range = context.range ?: return
        if (range.isEmpty) return
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        if (plot.isEmpty) return

        val vertical = coordinates.orientation.isVertical
        val extent = if (vertical) plot.width else plot.height
        val origin = if (vertical) plot.left else plot.top

        // Full-domain fraction → viewport fraction → pixels.
        fun positionOf(fraction: Double): Float =
            origin + (context.viewport.fractionWithin(fraction) * extent).toFloat()

        val rawStart = positionOf(range.startFraction)
        val rawEnd = positionOf(range.endFraction)
        val low = ChartMath.clamp(minOf(rawStart, rawEnd), origin, origin + extent)
        val high = ChartMath.clamp(maxOf(rawStart, rawEnd), origin, origin + extent)
        if (high - low <= 0f) return

        val handleWidth = context.px(context.dimensions.rangeHandleWidth)

        if (vertical) {
            scope.drawRect(
                color = context.colors.rangeFill,
                topLeft = Offset(low, plot.top),
                size = Size(high - low, plot.height),
            )
            scope.drawLine(context.colors.rangeBorder, Offset(low, plot.top), Offset(low, plot.bottom), handleWidth)
            scope.drawLine(context.colors.rangeBorder, Offset(high, plot.top), Offset(high, plot.bottom), handleWidth)
        } else {
            scope.drawRect(
                color = context.colors.rangeFill,
                topLeft = Offset(plot.left, low),
                size = Size(plot.width, high - low),
            )
            scope.drawLine(context.colors.rangeBorder, Offset(plot.left, low), Offset(plot.right, low), handleWidth)
            scope.drawLine(context.colors.rangeBorder, Offset(plot.left, high), Offset(plot.right, high), handleWidth)
        }
    }
}
