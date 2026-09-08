package io.devkit.chartkit.layer.statistical

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.stats.BoxStatistics
import io.devkit.chartkit.stats.DensityCurve

/** What a violin draws inside its body, alongside the density outline. */
enum class ViolinOverlay {

    /** Nothing. The density shape alone. */
    None,

    /** A line at the median. The default: one number, unambiguously placed. */
    Median,

    /**
     * A miniature box plot inside the violin.
     *
     * The combination most statistical tools default to, and the most
     * informative: the density shows the distribution's shape, the box shows
     * the numbers a reader can quote.
     */
    Box,
}

/** One category's density curve and the summary drawn inside it. */
internal class ViolinEntry(
    val label: String,
    val curve: DensityCurve,
    val statistics: BoxStatistics?,
    val item: Any?,
    val paletteIndex: Int,
    val colorOverride: Int?,
)

/**
 * Violin marks: a kernel density estimate mirrored about each category's centre.
 *
 * The curve arrives already estimated by
 * [io.devkit.chartkit.stats.DensityEstimator], so no statistics happen here —
 * the same split as the box plot, and for the same reason.
 *
 * ### Widths are comparable across categories
 *
 * Every violin in a chart is scaled by the **largest peak in the chart**, not
 * by its own. Normalising each violin to its own peak would make every category
 * the same width and silently discard the fact that one distribution is more
 * concentrated than another — which is one of the two things a violin is for.
 *
 * ### A degenerate sample is drawn as a line
 *
 * A constant sample has no density to estimate. It is drawn as a bar at its
 * value rather than as a flat violin, because a flat violin claims a uniform
 * distribution the data does not have.
 */
internal class ViolinLayer(
    override val id: String,
    private val entries: List<ViolinEntry>,
    private val seriesId: String,
    private val seriesName: String,
    private val overlay: ViolinOverlay,
    private val valueFormatter: io.devkit.chartkit.formatter.ChartValueFormatter,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    /** The largest density anywhere in the chart — the shared width reference. */
    private val peak: Double = entries.maxOfOrNull { it.curve.peak } ?: 0.0

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val coordinates = context.cartesian
        val plot = coordinates.plotArea
        val categories = coordinates.categories ?: return
        if (plot.isEmpty || entries.isEmpty() || peak <= 0.0) return

        val colors = context.colors.statistical
        val band = categories.innerBandWidth
        val maxHalfWidth = band * context.dimensions.violinWidthFraction / 2f
        val outlineWidth = context.px(context.dimensions.violinOutlineWidth)
        val reveal = context.reveal.coerceIn(0f, 1f)
        val selection = context.selection

        entries.forEachIndexed { index, entry ->
            val centre = categories.positionAt(index)
            val fill = entry.colorOverride?.let { Color(it).copy(alpha = 0.30f) } ?: colors.densityFill
            val outline = entry.colorOverride?.let { Color(it) } ?: colors.densityOutline

            if (entry.curve.isDegenerate) {
                // No spread: a bar at the value, which is what the data says.
                val value = entry.curve.positions.firstOrNull() ?: return@forEachIndexed
                val position = coordinates.positionOfValue(value)
                val half = maxHalfWidth * reveal
                val a = coordinates.pointAt(centre - half, position)
                val b = coordinates.pointAt(centre + half, position)
                scope.drawLine(outline, Offset(a.x, a.y), Offset(b.x, b.y), outlineWidth * 2f)
                return@forEachIndexed
            }

            // The body grows outward from the category's own centre line, so
            // the reveal reads as a distribution opening rather than sliding.
            val path = violinPath(context, centre, maxHalfWidth * reveal, entry)
            scope.drawPath(path, fill)
            scope.drawPath(path, outline, style = Stroke(width = outlineWidth))

            drawOverlay(scope, context, centre, band, entry, reveal)

            if (selection?.seriesId == seriesId && selection.pointIndex == index) {
                scope.drawPath(
                    path = path,
                    color = context.colors.selectionGuide,
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }
        }
    }

    /** The closed outline: up one side of the density, back down the mirror. */
    private fun violinPath(
        context: ChartRenderContext,
        centre: Float,
        maxHalfWidth: Float,
        entry: ViolinEntry,
    ): Path {
        val coordinates = context.cartesian
        val path = Path()
        val curve = entry.curve
        var started = false

        for (index in 0 until curve.size) {
            val value = curve.positions[index]
            val half = (curve.densities[index] / peak).toFloat() * maxHalfWidth
            val point = coordinates.pointAt(centre + half, coordinates.positionOfValue(value))
            if (!point.isFinite) continue
            if (!started) {
                path.moveTo(point.x, point.y)
                started = true
            } else {
                path.lineTo(point.x, point.y)
            }
        }
        if (!started) return path

        for (index in curve.size - 1 downTo 0) {
            val value = curve.positions[index]
            val half = (curve.densities[index] / peak).toFloat() * maxHalfWidth
            val point = coordinates.pointAt(centre - half, coordinates.positionOfValue(value))
            if (!point.isFinite) continue
            path.lineTo(point.x, point.y)
        }
        path.close()
        return path
    }

    private fun drawOverlay(
        scope: DrawScope,
        context: ChartRenderContext,
        centre: Float,
        band: Float,
        entry: ViolinEntry,
        reveal: Float,
    ) {
        if (overlay == ViolinOverlay.None || reveal < 1f) return
        val statistics = entry.statistics?.takeIf { it.isValid } ?: return
        val coordinates = context.cartesian
        val colors = context.colors.statistical
        val stroke = context.px(context.dimensions.lineWidth)

        fun cross(value: Double, extent: Float, colour: Color, width: Float) {
            val position = coordinates.positionOfValue(value)
            val half = extent / 2f
            val a = coordinates.pointAt(centre - half, position)
            val b = coordinates.pointAt(centre + half, position)
            scope.drawLine(colour, Offset(a.x, a.y), Offset(b.x, b.y), width)
        }

        when (overlay) {
            ViolinOverlay.Median -> cross(statistics.median, band * 0.4f, colors.median, stroke * 1.6f)
            ViolinOverlay.Box -> {
                val boxExtent = band * INNER_BOX_FRACTION
                val q1 = coordinates.positionOfValue(statistics.q1)
                val q3 = coordinates.positionOfValue(statistics.q3)
                val low = coordinates.positionOfValue(statistics.minimum)
                val high = coordinates.positionOfValue(statistics.maximum)

                val stemA = coordinates.pointAt(centre, low)
                val stemB = coordinates.pointAt(centre, high)
                scope.drawLine(colors.boxBorder, Offset(stemA.x, stemA.y), Offset(stemB.x, stemB.y), stroke)

                val boxA = coordinates.pointAt(centre - boxExtent / 2f, q1)
                val boxB = coordinates.pointAt(centre + boxExtent / 2f, q3)
                scope.drawRect(
                    color = colors.boxBorder,
                    topLeft = Offset(minOf(boxA.x, boxB.x), minOf(boxA.y, boxB.y)),
                    size = androidx.compose.ui.geometry.Size(
                        kotlin.math.abs(boxB.x - boxA.x),
                        kotlin.math.abs(boxB.y - boxA.y),
                    ),
                )
                cross(statistics.median, boxExtent * 1.4f, colors.median, stroke * 1.6f)
            }
            ViolinOverlay.None -> Unit
        }
    }

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val coordinates = context.cartesian
        val categories = coordinates.categories ?: return null
        val index = categories.indexAt(coordinates.domainOf(point))
        if (index < 0 || index >= entries.size) return null
        val entry = entries[index]
        val centre = categories.positionAt(index)
        val median = entry.statistics?.median ?: entry.curve.positions.firstOrNull() ?: 0.0
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = index,
            x = ChartX.Category(entry.label),
            y = median,
            item = entry.item,
            position = coordinates.pointAt(centre, coordinates.positionOfValue(median)),
        )
    }

    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        if (selection.seriesId != seriesId) return emptyList()
        val entry = entries.getOrNull(selection.pointIndex) ?: return emptyList()
        val statistics = entry.statistics ?: return emptyList()
        fun row(name: String, value: Double) = ChartTooltipEntry(
            seriesId = seriesId,
            seriesName = name,
            value = value,
            item = entry.item,
            paletteIndex = entry.paletteIndex,
        )
        return listOf(
            row("Q3", statistics.q3),
            row("Median", statistics.median),
            row("Q1", statistics.q1),
        )
    }

    /**
     * The underlying statistics, not the shape.
     *
     * A screen reader cannot be given a silhouette. "Bimodal, with a long right
     * tail" would be an interpretation ChartKit has not computed, so what is
     * announced is the summary the violin was estimated from, plus the sample
     * size — which is what makes the estimate worth anything.
     */
    override fun describe(): List<ChartLayerSummary> = listOf(
        ChartLayerSummary(
            seriesId = seriesId,
            seriesName = seriesName,
            pointCount = entries.size,
            entries = entries.map { entry ->
                val statistics = entry.statistics
                ChartLayerEntry(
                    label = entry.label,
                    value = statistics?.median,
                    detail = buildString {
                        append(entry.label)
                        append(": ")
                        append(entry.curve.sampleCount)
                        append(" observations")
                        if (statistics != null && statistics.isValid) {
                            append(", median ")
                            append(valueFormatter.format(statistics.median))
                            append(", from ")
                            append(valueFormatter.format(statistics.minimum))
                            append(" to ")
                            append(valueFormatter.format(statistics.maximum))
                        }
                    },
                )
            },
        ),
    )

    private companion object {
        /** Narrow enough to read as an inset summary, not as a second chart. */
        const val INNER_BOX_FRACTION = 0.12f
    }
}
