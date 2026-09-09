package io.devkit.chartkit.charts

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import io.devkit.chartkit.axis.AxisPosition
import io.devkit.chartkit.axis.MeasuredAxis
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.scene.ChartScene
import io.devkit.chartkit.scene.ChartSceneBuilder
import io.devkit.chartkit.scene.ChartSceneNode
import io.devkit.chartkit.scene.TextAnchor
import io.devkit.chartkit.scene.buildChartScene
import io.devkit.chartkit.theme.ChartColors

/**
 * Builds a renderer-neutral scene from finished Cartesian geometry.
 *
 * ### Incremental, not a second renderer
 *
 * Every layer already knows how to draw itself; this asks each one whether it
 * can also *describe* itself, and collects what comes back. A layer that
 * cannot — a candlestick, a violin, a heatmap — says so, and its name lands in
 * [ChartScene.unexportedLayers] rather than being silently omitted. That is what
 * lets vector export exist at all without rewriting the drawing code, and what
 * makes its coverage honest.
 *
 * The axes are built here rather than in a layer because they are drawn by the
 * chart itself: they live outside the plot, in the gutter the layout reserved,
 * and no layer knows they exist.
 */
internal fun buildCartesianScene(
    geometry: CartesianGeometry,
    context: ChartRenderContext,
    size: ChartRect,
    background: Color?,
    density: Density,
): ChartScene = buildChartScene(size.width, size.height, background) {
    val plot = geometry.coordinates.plotArea

    // Each renderer described against its own axis' coordinates, exactly as it
    // was drawn. Exporting them all against the primary axis' scale would put a
    // threshold stated in °C at whatever height that number happens to be in
    // millimetres — a picture that is wrong in a way nothing in it shows.
    val axisContexts = geometry.axisCoordinates.mapValues { (_, coords) ->
        context.withCoordinates(coords)
    }
    geometry.renderers.forEach { renderer ->
        val rendererContext = axisContexts[geometry.axisOf(renderer)] ?: context
        if (!renderer.renderScene(this, rendererContext)) unexported(renderer.id)
    }

    geometry.domainAxis?.let { axis(it, plot, context.colors, density) }
    geometry.valueAxes.forEach { axis(it, plot, context.colors, density) }
}

/**
 * One axis: its line, its ticks and its labels.
 *
 * The label positions are the ones the chart measured, not ones recomputed from
 * the text — so a viewer whose font metrics differ still puts each label on its
 * own tick, which is the property that matters.
 */
private fun ChartSceneBuilder.axis(
    axis: MeasuredAxis,
    plot: ChartRect,
    colors: ChartColors,
    density: Density,
) {
    if (!axis.config.visible) return
    val horizontal = axis.position.isHorizontal
    // The edge this axis draws against: the plot's own for the innermost axis
    // on a side, and further out by [MeasuredAxis.offset] for the next. An
    // export that ignored the offset would stack two axes on one line.
    val offset = axis.offset.takeIf { it.isFinite() && it > 0f } ?: 0f
    val edge = when (axis.position) {
        AxisPosition.Bottom -> plot.bottom + offset
        AxisPosition.Top -> plot.top - offset
        AxisPosition.Start -> plot.left - offset
        AxisPosition.End -> plot.right + offset
    }
    // Which way the ticks and labels sit from the axis line.
    val outward = when (axis.position) {
        AxisPosition.Bottom, AxisPosition.End -> 1f
        AxisPosition.Top, AxisPosition.Start -> -1f
    }

    // Named by the axis rather than by its edge: two axes can share a side, and
    // two groups with the same name would be indistinguishable in the output.
    group("axis-${axis.id.value}") {
        if (axis.config.showLine) {
            add(
                ChartSceneNode.Line(
                    from = if (horizontal) {
                        ChartOffset(plot.left, edge)
                    } else {
                        ChartOffset(edge, plot.top)
                    },
                    to = if (horizontal) {
                        ChartOffset(plot.right, edge)
                    } else {
                        ChartOffset(edge, plot.bottom)
                    },
                    color = colors.axisLine,
                    strokeWidth = 1f,
                ),
            )
        }

        axis.labels.forEach { label ->
            val text = label.layout.layoutInput.text.text
            if (text.isBlank()) return@forEach
            val fontSize = with(density) { label.layout.layoutInput.style.fontSize.toPx() }
            val gap = fontSize * LABEL_GAP_FACTOR
            // The baseline, not the top: SVG positions text by its baseline, and
            // approximating it from the box's top would drift with the font.
            val baselineOffset = label.layout.firstBaseline
            add(
                ChartSceneNode.Text(
                    text = text,
                    position = if (horizontal) {
                        ChartOffset(label.at, edge + outward * gap + baselineOffset)
                    } else {
                        ChartOffset(
                            edge + outward * gap,
                            label.at + baselineOffset - label.layout.size.height / 2f,
                        )
                    },
                    color = colors.axisLabel,
                    fontSizePx = fontSize,
                    anchor = when {
                        horizontal -> TextAnchor.Middle
                        outward < 0f -> TextAnchor.End
                        else -> TextAnchor.Start
                    },
                    rotationDegrees = if (axis.rotated) ROTATED_LABEL_DEGREES else 0f,
                ),
            )
        }
    }
}

/** How far a label sits from the axis line, as a fraction of its own size. */
private const val LABEL_GAP_FACTOR = 0.9f

/** The angle [io.devkit.chartkit.axis.AxisLabelOverflow.Rotate] draws at. */
private const val ROTATED_LABEL_DEGREES = -45f
