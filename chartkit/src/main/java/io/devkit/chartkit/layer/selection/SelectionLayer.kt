package io.devkit.chartkit.layer.selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartRenderContext

/**
 * The guide line drawn through the current selection.
 *
 * Its own layer rather than a few lines inside `LineChart`, for one reason: a
 * crosshair — 0.2 — is this line plus its perpendicular plus a pair of axis
 * readouts. Written here it becomes that by extension; written inside a chart
 * it would have to be moved out first, and by then two charts would have their
 * own copies.
 *
 * The line runs across the value axis at the selection's domain position, so on
 * a horizontal bar chart it is horizontal without a second code path.
 */
internal class SelectionLayer(
    override val id: String = "selection",
    private val dashed: Boolean = true,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = emptyList()

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val selection = context.selection ?: return
        val plot = context.coordinates.plotArea
        if (plot.isEmpty || context.reveal < 1f) return

        val width = context.px(context.dimensions.selectionGuideWidth)
        val effect = if (dashed) {
            PathEffect.dashPathEffect(floatArrayOf(width * 4f, width * 4f))
        } else {
            null
        }

        if (context.coordinates.orientation.isVertical) {
            val x = selection.position.x
            if (!x.isFinite() || x < plot.left || x > plot.right) return
            scope.drawLine(
                color = context.colors.selectionGuide,
                start = Offset(x, plot.top),
                end = Offset(x, plot.bottom),
                strokeWidth = width,
                pathEffect = effect,
            )
        } else {
            val y = selection.position.y
            if (!y.isFinite() || y < plot.top || y > plot.bottom) return
            scope.drawLine(
                color = context.colors.selectionGuide,
                start = Offset(plot.left, y),
                end = Offset(plot.right, y),
                strokeWidth = width,
                pathEffect = effect,
            )
        }
    }
}
