package io.devkit.chartkit.layer.grid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartRenderContext

/**
 * Rules behind the data, aligned to the axis ticks.
 *
 * Takes the tick positions the axes already computed rather than deriving its
 * own. A grid whose lines do not land on the labelled values is worse than no
 * grid: it invites the reader to measure against lines that mean nothing.
 *
 * [ChartGrid] names directions **on screen**, so `Horizontal` is a horizontal
 * rule whichever axis it came from. Which tick set produces which direction
 * depends on the chart's orientation, and that mapping is made once, here.
 *
 * @param domainTicks pixel positions along the domain (category or x) axis.
 * @param valueTicks pixel positions along the value axis.
 */
internal class GridLayer(
    private val grid: ChartGrid,
    private val domainTicks: List<Float>,
    private val valueTicks: List<Float>,
    override val id: String = "grid",
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = emptyList()

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        if (grid == ChartGrid.None) return
        val plot = context.coordinates.plotArea
        if (plot.isEmpty) return

        val strokeWidth = context.px(context.dimensions.gridLineWidth)
        val color = context.colors.gridLine
        val vertical = context.coordinates.orientation.isVertical

        // On a vertical chart the value axis runs up the screen, so its ticks
        // are horizontal rules. On a horizontal one it runs across, so they are
        // vertical rules — and the domain ticks swap with them.
        val horizontalRules = if (vertical) valueTicks else domainTicks
        val verticalRules = if (vertical) domainTicks else valueTicks

        if (grid.hasHorizontal) {
            horizontalRules.forEach { y ->
                if (y.isFinite() && y >= plot.top - 0.5f && y <= plot.bottom + 0.5f) {
                    scope.drawLine(color, Offset(plot.left, y), Offset(plot.right, y), strokeWidth)
                }
            }
        }
        if (grid.hasVertical) {
            verticalRules.forEach { x ->
                if (x.isFinite() && x >= plot.left - 0.5f && x <= plot.right + 0.5f) {
                    scope.drawLine(color, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth)
                }
            }
        }
    }
}
