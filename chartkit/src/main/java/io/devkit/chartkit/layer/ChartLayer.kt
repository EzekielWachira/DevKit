package io.devkit.chartkit.layer

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import io.devkit.chartkit.coordinate.CoordinateSystem
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.model.AnyChartRangeSelection
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.viewport.ChartViewport
import io.devkit.chartkit.theme.ChartColors
import io.devkit.chartkit.theme.ChartDimensions
import io.devkit.chartkit.theme.ChartTypography

/**
 * Everything a layer needs to draw one frame.
 *
 * Assembled once per frame by the chart and handed to every layer, which is
 * what guarantees they agree: two layers given the same [coordinates] cannot
 * disagree about where a value sits, and a combined bar-plus-line chart is
 * therefore correct by construction rather than by two implementations
 * happening to compute the same padding.
 *
 * The [coordinates] are typed as the general [CoordinateSystem], so the same
 * context serves Cartesian and polar layers. A layer that needs one in
 * particular narrows it — a bar layer cannot draw without a value axis, and a
 * slice layer cannot draw without a centre — but the context, the theme, the
 * animation clock and the selection are shared unchanged.
 *
 * @param reveal the `0..1` initial-draw fraction. Layers scale geometry by it.
 * @param selection the current selection, so a layer can emphasise its own item
 *   without needing to know how selection was made.
 * @param range the domain interval being dragged out, if any.
 * @param viewport the visible window of the domain. Layers that draw in
 *   full-domain fractions — the range overlay — map through it.
 */
internal class ChartRenderContext(
    val coordinates: CoordinateSystem,
    val colors: ChartColors,
    val typography: ChartTypography,
    val dimensions: ChartDimensions,
    val density: Density,
    val textMeasurer: TextMeasurer,
    val reveal: Float,
    val selection: AnyChartSelection?,
    val range: AnyChartRangeSelection? = null,
    val viewport: ChartViewport = ChartViewport.Full,
) {
    /** [dp] in pixels, at the current density. */
    fun px(dp: androidx.compose.ui.unit.Dp): Float = with(density) { dp.toPx() }

    /**
     * The Cartesian coordinates, for a layer that cannot work without them.
     *
     * Throws rather than returning null: a bar layer reaching a polar chart is
     * a wiring bug in ChartKit, not a condition a caller can recover from, and
     * silently drawing nothing would hide it.
     */
    val cartesian: io.devkit.chartkit.coordinate.CartesianCoordinates
        get() = coordinates as? io.devkit.chartkit.coordinate.CartesianCoordinates
            ?: error("This layer requires Cartesian coordinates, got \${coordinates::class.simpleName}")

    /** The polar coordinates, for a slice or radial layer. */
    val polar: io.devkit.chartkit.coordinate.PolarCoordinates
        get() = coordinates as? io.devkit.chartkit.coordinate.PolarCoordinates
            ?: error("This layer requires polar coordinates, got \${coordinates::class.simpleName}")
}

/**
 * One drawable stratum of a chart.
 *
 * Kept small on purpose. A grid draws and does nothing else; a bar layer draws,
 * responds to hit tests and describes itself for a screen reader. A single
 * interface demanding all three from everyone would either be full of empty
 * overrides or force a grid to pretend it can be selected, so everything but
 * [draw] has a default.
 *
 * Adding a layer type — an annotation rule, an event marker, a candlestick —
 * means adding an implementation. It does not mean touching the coordinate
 * system, the layout engine or the interaction model. That property is what
 * made the polar layers cost two classes rather than a second engine.
 */
internal interface ChartLayerRenderer {

    /** Stable within one chart; used for keying and for debugging. */
    val id: String

    /** The series this layer draws, in declaration order. */
    val seriesIds: List<String>

    /**
     * Whether the layer is clipped to the plot area.
     *
     * True for anything drawing data: once a viewport exists, a zoomed chart's
     * off-screen geometry would otherwise be painted across the axes. False for
     * overlays whose whole point is to reach outside it — a crosshair's axis
     * chips sit in the gutter.
     */
    val clipToPlot: Boolean get() = true

    fun draw(scope: DrawScope, context: ChartRenderContext)

    /**
     * The item at [point], or `null` when the layer has nothing there.
     *
     * The chart asks every layer and keeps the best answer, so a combined chart
     * selects the bar the finger is on rather than whichever layer was asked
     * first.
     */
    fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? = null

    /** A factual, readable description of what this layer contains. */
    fun describe(): List<ChartLayerSummary> = emptyList()

    /**
     * Every series' value at the same domain position as [selection].
     *
     * What turns a single-point tooltip into a multi-series one: a crosshair
     * over four lines asks each layer what it has at the selected x, and the
     * overlay renders them together. A layer with nothing to add — a grid, a
     * crosshair — returns nothing, and the tooltip falls back to the selection
     * alone.
     */
    fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> = emptyList()
}

/**
 * What a layer tells a screen reader about one series.
 *
 * Values, not interpretation. "Revenue: 6 points, January 24,000 …" and never
 * "revenue is trending upward" — a trend claim is a statistical assertion
 * ChartKit has not computed, and a confident wrong one is worse than none.
 */
internal data class ChartLayerSummary(
    val seriesId: String,
    val seriesName: String,
    val pointCount: Int,
    val entries: List<ChartLayerEntry>,
)

internal data class ChartLayerEntry(val label: String, val value: Double?)
