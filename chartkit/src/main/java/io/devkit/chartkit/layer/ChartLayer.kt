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
    /**
     * A domain position pushed in from outside this chart.
     *
     * How linked charts share a crosshair: one chart publishes the domain value
     * under the pointer, and every chart in the group receives it here and
     * draws its own guide at its own scale's position for that value. Shared as
     * a **domain value** and not as a pixel or a fraction, because two charts
     * over different datasets have different domains — aligning them by
     * fraction would put the same pixel, not the same date, under both guides.
     */
    val externalDomain: io.devkit.chartkit.model.ChartX? = null,
    /**
     * Whether this frame is being drawn for a reader or for a picture.
     *
     * Layers that draw something transient — a crosshair following a pointer,
     * a highlight that only makes sense mid-gesture — check it and skip. See
     * [io.devkit.chartkit.render.ChartRenderMode] for why the whole decision is
     * one value rather than several flags.
     */
    val renderMode: io.devkit.chartkit.render.ChartRenderMode =
        io.devkit.chartkit.render.ChartRenderMode.Interactive,
    /**
     * What each value axis reads at the current selection.
     *
     * Empty unless the chart asked for per-axis crosshair readouts. Computed
     * once per selection from the tooltip's own entries rather than by hit
     * testing again inside the crosshair — a chip that said something different
     * from the tooltip beside it would be worse than no chip.
     */
    val axisReadouts: List<AxisValueReadout> = emptyList(),
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

    /** The geographic coordinates, for a map layer. */
    val geo: io.devkit.chartkit.coordinate.GeoCoordinates
        get() = coordinates as? io.devkit.chartkit.coordinate.GeoCoordinates
            ?: error("This layer requires geographic coordinates, got \${coordinates::class.simpleName}")

    /** The planar coordinates, for a treemap, flow, funnel or graph layer. */
    val planar: io.devkit.chartkit.coordinate.PlanarCoordinates
        get() = coordinates as? io.devkit.chartkit.coordinate.PlanarCoordinates
            ?: error("This layer requires planar coordinates, got \${coordinates::class.simpleName}")

    /**
     * A copy at its settled state, with no selection.
     *
     * What an export is built from: a picture that depended on a running
     * animation clock or on the pointer's position would differ between two
     * captures of the same chart.
     */
    internal fun settledForExport(): ChartRenderContext = ChartRenderContext(
        coordinates = coordinates,
        colors = colors,
        typography = typography,
        dimensions = dimensions,
        density = density,
        textMeasurer = textMeasurer,
        reveal = 1f,
        selection = null,
        range = null,
        viewport = viewport,
        externalDomain = null,
        renderMode = io.devkit.chartkit.render.ChartRenderMode.Static,
    )

    /** A copy carrying per-axis crosshair readouts. */
    internal fun withReadouts(readouts: List<AxisValueReadout>): ChartRenderContext =
        ChartRenderContext(
            coordinates = coordinates,
            colors = colors,
            typography = typography,
            dimensions = dimensions,
            density = density,
            textMeasurer = textMeasurer,
            reveal = reveal,
            selection = selection,
            range = range,
            viewport = viewport,
            externalDomain = externalDomain,
            renderMode = renderMode,
            axisReadouts = readouts,
        )

    /** A copy drawing against [other], for a layer bound to a second axis. */
    internal fun withCoordinates(other: CoordinateSystem): ChartRenderContext =
        ChartRenderContext(
            coordinates = other,
            colors = colors,
            typography = typography,
            dimensions = dimensions,
            density = density,
            textMeasurer = textMeasurer,
            reveal = reveal,
            selection = selection,
            range = range,
            viewport = viewport,
            externalDomain = externalDomain,
            renderMode = renderMode,
            axisReadouts = axisReadouts,
        )
}

/**
 * One value axis' reading at the selected position, ready to draw as a chip.
 *
 * @param at the pixel row (or column, on a horizontal chart) the value maps to
 *   on **its own** axis — three axes over three quantities put their chips at
 *   three different heights, which is the point.
 * @param offset how far outside the plot that axis sits, so a chip lands beside
 *   the second axis on a side rather than on top of the first.
 */
internal data class AxisValueReadout(
    val axisId: io.devkit.chartkit.axis.ChartAxisId,
    val position: io.devkit.chartkit.axis.AxisPosition,
    val offset: Float,
    val at: Float,
    val text: String,
)

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

    /**
     * Which value axis this layer is measured against, by name.
     *
     * Only meaningful on a Cartesian chart that declared more than one. The
     * chart hands each layer a render context built over the scale of the axis
     * it named, so the layer itself needs no knowledge of the arrangement — it
     * draws against the coordinates it is given, whether the chart has one
     * value axis or four. See [io.devkit.chartkit.axis.ValueAxisBinding] for why
     * the binding is explicit rather than inferred, and
     * [io.devkit.chartkit.axis.ChartAxisId] for why it is a name.
     */
    val valueAxisId: io.devkit.chartkit.axis.ChartAxisId
        get() = io.devkit.chartkit.axis.ChartAxisId.DefaultY

    fun draw(scope: DrawScope, context: ChartRenderContext)

    /**
     * Adds this layer's geometry to a renderer-neutral scene, or does nothing.
     *
     * Returns `true` when the layer contributed a faithful representation of
     * what it draws. A layer that returns `false` is named in
     * [io.devkit.chartkit.scene.ChartScene.unexportedLayers], so a vector export
     * says which parts of the chart are missing from it rather than shipping a
     * picture with data quietly absent.
     *
     * Optional on purpose. Implementing it for a layer whose drawing does not
     * reduce to the scene's primitives would mean either approximating the
     * picture — the worst outcome, because the difference is invisible — or
     * growing the scene model into a second rendering API.
     */
    fun renderScene(
        builder: io.devkit.chartkit.scene.ChartSceneBuilder,
        context: ChartRenderContext,
    ): Boolean = false

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
     * The sentence a screen reader announces for [selection], or `null` to let
     * the chart build its own.
     *
     * The default is right for anything whose selection is one series, one x
     * and one number. It is not right for a flow — "Search to Checkout: 1,240
     * users" — a hierarchy node, whose share of its parent is the point, or a
     * graph node, whose connections are. Those layers know what their selection
     * means and say so here, rather than every chart engine growing a case for
     * each of them.
     */
    fun describeSelection(
        selection: AnyChartSelection,
        formatter: io.devkit.chartkit.formatter.ChartValueFormatter,
    ): String? = null

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
    /**
     * The interval the present values occupy, when [entries] was not
     * materialised.
     *
     * A fifty-thousand-point series has fifty thousand entries that will never
     * be read out — the announcement caps long before that and falls back to a
     * range. Building them anyway costs fifty thousand allocations per layout
     * for a string nobody hears, so a layer past the cap supplies the range
     * directly and leaves [entries] empty.
     */
    val valueRange: ClosedFloatingPointRange<Double>? = null,
    /** How many of [pointCount] had no value. */
    val missingCount: Int = 0,
)

/**
 * One announced data point.
 *
 * @param label how the point is identified — a category, a date, an x value.
 * @param value the number, or `null` when the point is missing.
 * @param detail a complete phrase replacing the default `"label: value"`, for
 *   points that are not one number. A box plot's category has five, a candle
 *   has four, and a scatter observation has two coordinates and possibly a
 *   size; announcing only one of them would describe a fraction of the mark.
 */
internal data class ChartLayerEntry(
    val label: String,
    val value: Double?,
    val detail: String? = null,
)
