package io.devkit.chartkit.layer

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import io.devkit.chartkit.coordinate.CartesianCoordinates
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.model.AnyChartSelection
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
 * @param reveal the `0..1` initial-draw fraction. Layers scale geometry by it.
 * @param selection the current selection, so a layer can emphasise its own item
 *   without needing to know how selection was made.
 */
internal class ChartRenderContext(
    val coordinates: CartesianCoordinates,
    val colors: ChartColors,
    val typography: ChartTypography,
    val dimensions: ChartDimensions,
    val density: Density,
    val textMeasurer: TextMeasurer,
    val reveal: Float,
    val selection: AnyChartSelection?,
) {
    /** [dp] in pixels, at the current density. */
    fun px(dp: androidx.compose.ui.unit.Dp): Float = with(density) { dp.toPx() }
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
 * system, the layout engine or the interaction model, which is the property
 * that has to hold for 0.2 to be cheaper than 0.1.
 */
internal interface ChartLayerRenderer {

    /** Stable within one chart; used for keying and for debugging. */
    val id: String

    /** The series this layer draws, in declaration order. */
    val seriesIds: List<String>

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
