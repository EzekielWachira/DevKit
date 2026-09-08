package io.devkit.chartkit.charts

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.model.ChartX

/**
 * Places arbitrary Compose content at positions in a chart's own coordinates.
 *
 * ```kotlin
 * CartesianChart(
 *     overlay = {
 *         Card(Modifier.chartAnchor(domain = "Mar", value = 90_000.0)) {
 *             Text("Launch", Modifier.padding(6.dp))
 *         }
 *     },
 * ) {
 *     line(series = revenue, x = { it.month }, y = { it.amount })
 * }
 * ```
 *
 * ### Why a Compose slot and not a canvas annotation
 *
 * [io.devkit.chartkit.annotation.ChartAnnotation] covers marks a chart can
 * *draw*: rules, regions, markers, call-outs, arrows and labels. What it cannot
 * cover is a card, an image, a button, a badge with an avatar in it — anything
 * that wants layout, theming, its own click target or its own semantics node.
 * Rasterising those onto a canvas would lose all four. So the annotations stay
 * canvas-drawn and this exists beside them, for content that has to be
 * composed.
 *
 * ### Positions follow the data
 *
 * [chartAnchor] resolves through the chart's own scales and viewport, so
 * overlay content stays on the value it names through a zoom or a pan — and
 * disappears when that value leaves the plot, rather than sliding along the
 * edge.
 *
 * The overlay sits above the canvas and below the tooltip. Content in it
 * receives pointer input normally, which is what makes an interactive badge
 * possible; it also means a large overlay can steal taps from the chart, so
 * keep it small.
 */
@ExperimentalChartKitApi
interface ChartOverlayScope {

    /** The region data is drawn inside, in pixels. */
    val plotArea: ChartRect

    /**
     * The pixel position of a domain value and an optional value-axis value.
     *
     * `null` when the position is off the axis — an unknown category, or a
     * value outside the visible window. A caller placing content by hand checks
     * for it; [chartAnchor] handles it by not placing the content at all.
     *
     * A `null` [value] anchors to the top of the plot, which is where something
     * that has a date but no magnitude belongs.
     */
    fun positionOf(domain: Any?, value: Double? = null): ChartOffset?

    /** The pixel position of a resolved domain value. */
    fun positionOfChartX(domain: ChartX, value: Double? = null): ChartOffset?

    /**
     * Places this content at a point in the chart's coordinates.
     *
     * @param alignment which part of the content sits on the point.
     *   [Alignment.Center] centres it; [Alignment.BottomCenter] rests it on the
     *   point, which is what a label above a data point wants.
     */
    fun Modifier.chartAnchor(
        domain: Any?,
        value: Double? = null,
        alignment: Alignment = Alignment.Center,
    ): Modifier
}

/** The implementation, built from the chart's geometry once per layout. */
@OptIn(ExperimentalChartKitApi::class)
internal class ChartOverlayScopeImpl(
    private val geometry: CartesianGeometry,
    private val resolveDomain: (Any?) -> Float?,
) : ChartOverlayScope {

    override val plotArea: ChartRect get() = geometry.coordinates.plotArea

    override fun positionOf(domain: Any?, value: Double?): ChartOffset? =
        place(resolveDomain(domain), value)

    override fun positionOfChartX(domain: ChartX, value: Double?): ChartOffset? =
        place(geometry.domainPositionOf(domain), value)

    private fun place(domainPosition: Float?, value: Double?): ChartOffset? {
        val coordinates = geometry.coordinates
        val plot = coordinates.plotArea
        if (plot.isEmpty) return null
        val at = domainPosition ?: return null
        if (!at.isFinite()) return null
        val valuePosition = value?.let { coordinates.positionOfValue(it) }
            ?: coordinates.valueOf(ChartOffset(plot.left, plot.top))
        if (!valuePosition.isFinite()) return null
        val point = coordinates.pointAt(at, valuePosition)
        // Off-plot content is not placed rather than clamped to the edge: a
        // badge pinned to the left of a zoomed chart would claim to mark a value
        // that is no longer on screen.
        return if (plot.contains(point)) point else null
    }

    override fun Modifier.chartAnchor(
        domain: Any?,
        value: Double?,
        alignment: Alignment,
    ): Modifier = this.layout { measurable, constraints ->
        // Measured unconstrained, then placed inside the full plot box. The
        // element reports the box's size so it does not disturb the layout, and
        // the child is positioned within it — which is what lets one overlay
        // hold several anchored pieces of content without any of them affecting
        // the others.
        val placeable = measurable.measure(Constraints())
        layout(constraints.maxWidth, constraints.maxHeight) {
            val point = positionOf(domain, value) ?: return@layout
            // `align(size, Zero)` gives the offset that puts `alignment` of the
            // content on the origin: `-w/2` for Center, `0` for TopStart.
            val adjust = alignment.align(
                IntSize(placeable.width, placeable.height),
                IntSize.Zero,
                layoutDirection,
            )
            placeable.place(
                x = point.x.toInt() + adjust.x,
                y = point.y.toInt() + adjust.y,
            )
        }
    }
}
