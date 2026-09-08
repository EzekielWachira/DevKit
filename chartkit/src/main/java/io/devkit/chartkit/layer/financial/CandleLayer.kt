package io.devkit.chartkit.layer.financial

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.devkit.chartkit.geometry.ChartMath
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.OhlcPoint
import io.devkit.chartkit.geometry.PriceDirection
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX
import kotlin.math.abs

/** Which financial mark a period is drawn as. */
enum class PriceMarkStyle {

    /**
     * A filled body from open to close, with a wick to the high and the low.
     *
     * Reads the open-to-close move as an area, which is what makes a
     * candlestick chart legible at a glance.
     */
    Candle,

    /**
     * A vertical high–low bar with a tick left for the open and right for the
     * close.
     *
     * Denser than a candle and legible at widths where a candle body collapses
     * to a line, which is why it is offered rather than being a styling detail.
     */
    OhlcBar,
}

/** One period's price mark, positioned. */
internal class CandleGeometry(
    val point: OhlcPoint,
    val domainPosition: Float,
)

/**
 * Candlestick and OHLC marks.
 *
 * ### One layer, two marks
 *
 * A candle and an OHLC bar encode the same four numbers with the same
 * positioning and the same hit testing; only the strokes differ. Two layers
 * would be two copies of the domain-position arithmetic, and the first fix
 * applied to one would leave the other wrong.
 *
 * ### Colour
 *
 * Nothing here knows that rising is green. The layer asks the theme for
 * [io.devkit.chartkit.theme.ChartFinancialColors.increase] and `decrease` by
 * name; an application whose market colours rising prices red swaps two values
 * in its theme.
 *
 * ### Gaps
 *
 * A missing period is a gap, never a fabricated candle. Weekends, holidays and
 * halted sessions are absences, and inventing a flat candle for them would put
 * a price on a day nothing traded.
 */
internal class CandleLayer(
    override val id: String,
    private val candles: List<CandleGeometry>,
    private val style: PriceMarkStyle,
    private val seriesId: String,
    private val seriesName: String,
    private val items: List<Any?>,
    private val bodyWidth: Float,
    private val valueFormatter: io.devkit.chartkit.formatter.ChartValueFormatter,
    private val domainLabel: (ChartX) -> String,
    private val xValues: List<ChartX>,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        if (plot.isEmpty || candles.isEmpty()) return

        val colors = context.colors.financial
        val wickWidth = context.px(context.dimensions.candleWickWidth)
        val minBody = context.px(context.dimensions.candleMinBodyWidth)
        val width = bodyWidth.coerceAtLeast(minBody)
        val reveal = context.reveal.coerceIn(0f, 1f)
        val selection = context.selection
        val baseline = coordinates.baseline

        candles.forEach { candle ->
            val x = candle.domainPosition
            // Culled by the plot's own bounds. A zoomed price chart has most of
            // its periods off screen, and each one costs several draw calls.
            if (x + width < plot.left || x - width > plot.right) return@forEach

            val point = candle.point
            val colour = when (point.direction) {
                PriceDirection.Increase -> colors.increase
                PriceDirection.Decrease -> colors.decrease
                PriceDirection.Neutral -> colors.neutral
            }

            // Marks grow out of the baseline like bars do, so a price chart
            // arriving reads the same way the rest of the library does.
            fun at(value: Double): Float =
                ChartMath.lerp(baseline, coordinates.positionOfValue(value), reveal)

            val high = at(point.high)
            val low = at(point.low)
            val open = at(point.open)
            val close = at(point.close)

            when (style) {
                PriceMarkStyle.Candle -> {
                    scope.drawLine(colors.wick, Offset(x, high), Offset(x, low), wickWidth)
                    val top = minOf(open, close)
                    val bottom = maxOf(open, close)
                    // A period that opened and closed at the same price has no
                    // body. Drawing a zero-height rect draws nothing, so the
                    // doji is drawn as the line it is.
                    val height = (bottom - top).coerceAtLeast(wickWidth)
                    scope.drawRect(
                        color = colour,
                        topLeft = Offset(x - width / 2f, top),
                        size = Size(width, height),
                    )
                }

                PriceMarkStyle.OhlcBar -> {
                    scope.drawLine(colour, Offset(x, high), Offset(x, low), wickWidth * 1.5f)
                    scope.drawLine(
                        colour,
                        Offset(x - width / 2f, open),
                        Offset(x, open),
                        wickWidth * 1.5f,
                    )
                    scope.drawLine(
                        colour,
                        Offset(x, close),
                        Offset(x + width / 2f, close),
                        wickWidth * 1.5f,
                    )
                }
            }

            if (selection?.seriesId == seriesId && selection.pointIndex == point.sourceIndex) {
                scope.drawRect(
                    color = context.colors.selectionGuide,
                    topLeft = Offset(x - width / 2f - wickWidth, minOf(high, low)),
                    size = Size(width + wickWidth * 2f, abs(high - low)),
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }
        }
    }

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        if (candles.isEmpty()) return null
        // Nearest along the domain, always. A candle body is a few pixels wide
        // and the reader is choosing a period, not aiming at a rectangle.
        val along = context.cartesian.domainOf(point)
        val nearest = candles.minByOrNull { abs(it.domainPosition - along) } ?: return null
        return selectionFor(nearest, context)
    }

    private fun selectionFor(
        candle: CandleGeometry,
        context: ChartRenderContext,
    ): AnyChartSelection {
        val coordinates = context.cartesian
        val point = candle.point
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = point.sourceIndex,
            x = xValues.getOrElse(point.sourceIndex) { ChartX.Numeric(point.domainValue) },
            // The close is the number a period is quoted at.
            y = point.close,
            item = items.getOrNull(point.sourceIndex),
            position = coordinates.pointAt(
                candle.domainPosition,
                coordinates.positionOfValue(point.high),
            ),
        )
    }

    /**
     * Open, high, low, close and the change — five rows, not one.
     *
     * A candle's "value" is not a number, and a tooltip that reported only the
     * close would leave out the three quantities the mark is drawn from.
     */
    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        if (selection.seriesId != seriesId) return emptyList()
        val candle = candles.firstOrNull { it.point.sourceIndex == selection.pointIndex }
            ?: return emptyList()
        val point = candle.point
        val item = items.getOrNull(point.sourceIndex)
        fun row(name: String, value: Double) = ChartTooltipEntry(
            seriesId = seriesId,
            seriesName = name,
            value = value,
            item = item,
            paletteIndex = 0,
        )
        return listOf(
            row("Open", point.open),
            row("High", point.high),
            row("Low", point.low),
            row("Close", point.close),
            row("Change", point.change),
        )
    }

    override fun describe(): List<ChartLayerSummary> = listOf(
        ChartLayerSummary(
            seriesId = seriesId,
            seriesName = seriesName,
            pointCount = candles.size,
            entries = candles.map { candle ->
                val point = candle.point
                val label = domainLabel(
                    xValues.getOrElse(point.sourceIndex) { ChartX.Numeric(point.domainValue) },
                )
                ChartLayerEntry(
                    label = label,
                    value = point.close,
                    detail = "$label: open ${valueFormatter.format(point.open)}, " +
                        "high ${valueFormatter.format(point.high)}, " +
                        "low ${valueFormatter.format(point.low)}, " +
                        "close ${valueFormatter.format(point.close)}",
                )
            },
        ),
    )
}
