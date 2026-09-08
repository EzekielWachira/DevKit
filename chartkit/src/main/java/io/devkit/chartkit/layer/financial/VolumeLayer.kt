package io.devkit.chartkit.layer.financial

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.devkit.chartkit.geometry.ChartMath
import io.devkit.chartkit.geometry.ChartOffset
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

/** One period's volume bar, positioned, with the direction that colours it. */
internal class VolumeGeometry(
    val sourceIndex: Int,
    val domainPosition: Float,
    val volume: Double,
    val direction: PriceDirection,
)

/**
 * Volume bars on a continuous domain.
 *
 * ### Why not the bar layer
 *
 * Volume is charted against **time**, not against categories, and it is
 * coloured by the direction of the period's price move rather than by its
 * series. The category bar layer can do neither, and bending it to would mean
 * a bar layer carrying a per-bar colour and a continuous domain that no bar
 * chart uses.
 *
 * The direction comes from the candle the volume belongs to, which is why
 * ChartKit carries volume on [io.devkit.chartkit.geometry.OhlcPoint] rather
 * than as a parallel series: a volume bar coloured from a separately-indexed
 * price list is one dropped period away from being coloured wrongly.
 *
 * Volume with no direction — supplied on its own, with no prices — is drawn
 * neutral rather than guessed at.
 */
internal class VolumeLayer(
    override val id: String,
    private val bars: List<VolumeGeometry>,
    private val seriesId: String,
    private val seriesName: String,
    private val items: List<Any?>,
    private val barWidth: Float,
    private val valueFormatter: io.devkit.chartkit.formatter.ChartValueFormatter,
    private val domainLabel: (ChartX) -> String,
    private val xValues: List<ChartX>,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        if (plot.isEmpty || bars.isEmpty()) return

        val colors = context.colors.financial
        val minWidth = context.px(context.dimensions.candleMinBodyWidth)
        val width = barWidth.coerceAtLeast(minWidth)
        val reveal = context.reveal.coerceIn(0f, 1f)
        val baseline = coordinates.baseline.coerceIn(plot.top, plot.bottom)
        val selection = context.selection

        bars.forEach { bar ->
            val x = bar.domainPosition
            if (x + width < plot.left || x - width > plot.right) return@forEach

            val top = ChartMath.lerp(baseline, coordinates.positionOfValue(bar.volume), reveal)
            val height = abs(baseline - top)
            if (height <= 0f) return@forEach

            val colour = when (bar.direction) {
                PriceDirection.Increase -> colors.increase
                PriceDirection.Decrease -> colors.decrease
                PriceDirection.Neutral -> colors.neutral
            }
            // Translucent: volume sits under the price it explains, and an
            // opaque band of bars competes with the series above it.
            scope.drawRect(
                color = colour.copy(alpha = colour.alpha * VOLUME_ALPHA),
                topLeft = Offset(x - width / 2f, minOf(top, baseline)),
                size = Size(width, height),
            )

            if (selection?.seriesId == seriesId && selection.pointIndex == bar.sourceIndex) {
                scope.drawRect(
                    color = context.colors.selectionGuide,
                    topLeft = Offset(x - width / 2f, minOf(top, baseline)),
                    size = Size(width, height),
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
        if (bars.isEmpty()) return null
        val along = context.cartesian.domainOf(point)
        val nearest = bars.minByOrNull { abs(it.domainPosition - along) } ?: return null
        val coordinates = context.cartesian
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = nearest.sourceIndex,
            x = xValues.getOrElse(nearest.sourceIndex) { ChartX.Numeric(0.0) },
            y = nearest.volume,
            item = items.getOrNull(nearest.sourceIndex),
            position = coordinates.pointAt(
                nearest.domainPosition,
                coordinates.positionOfValue(nearest.volume),
            ),
        )
    }

    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        if (selection.seriesId != seriesId) return emptyList()
        val bar = bars.firstOrNull { it.sourceIndex == selection.pointIndex } ?: return emptyList()
        return listOf(
            ChartTooltipEntry(
                seriesId = seriesId,
                seriesName = seriesName.ifBlank { "Volume" },
                value = bar.volume,
                item = items.getOrNull(bar.sourceIndex),
                paletteIndex = 0,
            ),
        )
    }

    override fun describe(): List<ChartLayerSummary> = listOf(
        ChartLayerSummary(
            seriesId = seriesId,
            seriesName = seriesName.ifBlank { "Volume" },
            pointCount = bars.size,
            entries = bars.map { bar ->
                val label = domainLabel(xValues.getOrElse(bar.sourceIndex) { ChartX.Numeric(0.0) })
                ChartLayerEntry(
                    label = label,
                    value = bar.volume,
                    detail = "$label: volume ${valueFormatter.format(bar.volume)}",
                )
            },
        ),
    )

    private companion object {
        /** Present but subordinate to the price series it explains. */
        const val VOLUME_ALPHA = 0.65f
    }
}
