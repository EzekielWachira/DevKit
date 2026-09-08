package io.devkit.chartkit.layer.polar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.geometry.ChartMath
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.scale.NumericDomain
import kotlin.math.sqrt

/**
 * One spoke of a radar chart: a metric and the interval it is measured over.
 *
 * @param domain the metric's own `[min, max]`. Each axis carries its own so a
 *   radar of unrelated metrics — a latency in milliseconds beside a score out
 *   of ten — can be drawn honestly.
 */
internal class RadarAxisSpec(
    val label: String,
    val domain: NumericDomain,
)

/** One series' value on each spoke, with the caller's own objects behind them. */
internal class RadarSeriesEntry(
    val seriesId: String,
    val seriesName: String,
    val seriesIndex: Int,
    val paletteIndex: Int,
    val colorOverride: Int?,
    /** Parallel to the axes; `null` where the series has no value for a metric. */
    val values: List<Double?>,
    val items: List<Any?>,
)

/**
 * How a radar chart normalises its spokes.
 *
 * The single most misleading decision available in a radar chart, so it is
 * explicit rather than implied.
 */
enum class RadarNormalization {

    /**
     * Every spoke shares one domain, taken from all the values in the chart.
     *
     * Correct when the metrics are commensurable — five scores out of ten, six
     * percentages. The polygon's shape then means something, because a longer
     * spoke really is a larger number.
     */
    Shared,

    /**
     * Each spoke is scaled to its own metric's range. The default.
     *
     * Correct when the metrics are not commensurable, which is the usual case
     * for a radar chart. The area of the polygon then means nothing in
     * particular and should not be read as a total — which is true of every
     * radar chart, and worth knowing.
     */
    PerAxis,
}

/**
 * The rings and spokes a radar chart is read against.
 *
 * A separate layer from the polygons so the render list decides the order,
 * rather than one layer drawing half its content before the data and half
 * after. Rings are drawn as polygons rather than as circles because a reader
 * measures a vertex against the ring it sits on, and a circular ring crosses
 * every spoke at a different apparent distance from the vertex.
 */
internal class RadarWebLayer(
    private val axes: List<RadarAxisSpec>,
    private val rings: Int,
    private val startAngle: Float,
    private val labelFormatter: (String) -> String,
    override val id: String = "radar-web",
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = emptyList()

    /** Spoke labels sit outside the outer ring, inside the plot square. */
    override val clipToPlot: Boolean get() = false

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val polar = context.polar
        if (!polar.isDrawable || axes.size < 3) return

        val strokeWidth = context.px(context.dimensions.gridLineWidth)
        val gridColour = context.colors.gridLine
        val axisColour = context.colors.axisLine

        for (ring in 1..rings) {
            val radius = polar.outerRadius * ring / rings
            scope.drawPath(polygon(polar.center, radius, startAngle, axes.size), gridColour, style = Stroke(strokeWidth))
        }

        axes.forEachIndexed { index, _ ->
            val angle = angleOf(index, axes.size, startAngle)
            val outer = PolarGeometry.pointOnCircle(polar.center, polar.outerRadius, angle)
            scope.drawLine(
                axisColour,
                Offset(polar.center.x, polar.center.y),
                Offset(outer.x, outer.y),
                strokeWidth,
            )
        }

        drawAxisLabels(scope, context)
    }

    private fun drawAxisLabels(scope: DrawScope, context: ChartRenderContext) {
        val polar = context.polar
        val style = context.typography.axisLabel.copy(color = context.colors.axisLabel)
        val gap = context.px(context.dimensions.labelPadding)
        val plot = polar.plotArea

        axes.forEachIndexed { index, axis ->
            val text = labelFormatter(axis.label)
            if (text.isEmpty()) return@forEachIndexed
            val layout: TextLayoutResult = context.textMeasurer.measure(text, style)
            val angle = angleOf(index, axes.size, startAngle)
            val anchor = PolarGeometry.pointOnCircle(polar.center, polar.outerRadius + gap, angle)

            // Anchored by which side of the circle the spoke is on, so a label
            // on the left grows leftwards instead of across the chart.
            val normalized = PolarGeometry.normalizeAngle(angle)
            val left = when {
                normalized < 5f || normalized > 355f || kotlin.math.abs(normalized - 180f) < 5f ->
                    anchor.x - layout.size.width / 2f
                normalized < 180f -> anchor.x
                else -> anchor.x - layout.size.width
            }
            val top = when {
                normalized < 5f || normalized > 355f -> anchor.y - layout.size.height
                kotlin.math.abs(normalized - 180f) < 5f -> anchor.y
                else -> anchor.y - layout.size.height / 2f
            }
            // A label that would leave the plot is dropped rather than clipped:
            // half a metric name is worse than none, and the legend carries it.
            if (left < plot.left || left + layout.size.width > plot.right ||
                top < plot.top || top + layout.size.height > plot.bottom
            ) {
                return@forEachIndexed
            }
            scope.drawText(layout, topLeft = Offset(left, top))
        }
    }
}

/**
 * Radar polygons: one closed shape per series, on the shared polar coordinates.
 *
 * ### Why this is not a third coordinate system
 *
 * A radar chart is a polar chart whose radius carries a value and whose angle
 * carries a category — exactly what [io.devkit.chartkit.coordinate.PolarCoordinates]
 * already describes. It needed no new coordinate system, no new layout and no
 * new interaction model; it needed two layers, which is the amount of code a
 * chart type should cost once the engine is right.
 *
 * ### A missing value is a gap, not a zero
 *
 * A series with no value on one spoke leaves the polygon open there rather than
 * pulling it to the centre. Drawing a zero would assert a measurement of zero,
 * and on a radar chart that reads as the series being worst at that metric.
 */
internal class RadarLayer(
    override val id: String,
    private val axes: List<RadarAxisSpec>,
    private val series: List<RadarSeriesEntry>,
    private val startAngle: Float,
    private val filled: Boolean,
    private val showPoints: Boolean,
    private val valueFormatter: io.devkit.chartkit.formatter.ChartValueFormatter,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = series.map { it.seriesId }

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val polar = context.polar
        if (!polar.isDrawable || axes.size < 3 || series.isEmpty()) return

        val reveal = context.reveal.coerceIn(0f, 1f)
        val strokeWidth = context.px(context.dimensions.lineWidth)
        val pointRadius = context.px(context.dimensions.pointRadius)
        val selection = context.selection

        series.forEach { entry ->
            val colour = entry.colorOverride?.let { Color(it) }
                ?: context.colors.seriesColor(entry.paletteIndex)
            val vertices = verticesOf(entry, polar, reveal)
            if (vertices.count { it != null } < 2) return@forEach

            // The polygon grows out of the centre, which is the one point every
            // spoke shares — so the shape opens rather than sliding.
            val path = pathOf(vertices)
            if (filled) scope.drawPath(path, colour.copy(alpha = FILL_ALPHA))
            scope.drawPath(path, colour, style = Stroke(width = strokeWidth))

            if (showPoints) {
                vertices.forEachIndexed { index, vertex ->
                    if (vertex == null) return@forEachIndexed
                    val selected = selection?.seriesId == entry.seriesId &&
                        selection.pointIndex == index
                    val radius = if (selected) {
                        context.px(context.dimensions.selectedPointRadius)
                    } else {
                        pointRadius
                    }
                    scope.drawCircle(colour, radius, Offset(vertex.x, vertex.y))
                    if (selected) {
                        scope.drawCircle(
                            context.colors.tooltipContent,
                            radius / 2.4f,
                            Offset(vertex.x, vertex.y),
                        )
                    }
                }
            }
        }
    }

    /** Each spoke's vertex, or `null` where the series has no value for it. */
    private fun verticesOf(
        entry: RadarSeriesEntry,
        polar: io.devkit.chartkit.coordinate.PolarCoordinates,
        reveal: Float,
    ): List<ChartOffset?> = axes.mapIndexed { index, axis ->
        val value = entry.values.getOrNull(index) ?: return@mapIndexed null
        if (!value.isFinite()) return@mapIndexed null
        val fraction = ChartMath.clamp(
            ChartMath.safeDiv(value - axis.domain.min, axis.domain.span),
            0.0,
            1.0,
        ).toFloat()
        val radius = polar.innerRadius +
            (polar.outerRadius - polar.innerRadius) * fraction * reveal
        PolarGeometry.pointOnCircle(polar.center, radius, angleOf(index, axes.size, startAngle))
    }

    private fun pathOf(vertices: List<ChartOffset?>): Path {
        val path = Path()
        var started = false
        vertices.forEach { vertex ->
            if (vertex == null) {
                // A gap: the polygon reopens at the next present spoke rather
                // than closing across the missing one.
                started = false
                return@forEach
            }
            if (!started) {
                path.moveTo(vertex.x, vertex.y)
                started = true
            } else {
                path.lineTo(vertex.x, vertex.y)
            }
        }
        // Only close a polygon that has no gaps in it; a partial ring closed
        // across its gap would draw an edge the data does not support.
        if (vertices.none { it == null } && vertices.size >= 3) path.close()
        return path
    }

    /**
     * The nearest vertex, within a tolerance.
     *
     * By vertex and not by polygon area: a reader tapping a radar chart is
     * choosing a metric on a series, and a point-in-polygon test would report
     * whichever shape happened to enclose the finger — which for two
     * overlapping series is not a question with an answer.
     */
    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val polar = context.polar
        if (!polar.isDrawable || axes.isEmpty()) return null
        val tolerance = context.px(context.dimensions.selectedPointRadius) * HIT_TOLERANCE_FACTOR

        var best: AnyChartSelection? = null
        var bestDistance = tolerance

        series.forEach { entry ->
            verticesOf(entry, polar, 1f).forEachIndexed { index, vertex ->
                if (vertex == null) return@forEachIndexed
                val dx = vertex.x - point.x
                val dy = vertex.y - point.y
                val distance = sqrt(dx * dx + dy * dy)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = ChartSelection(
                        seriesId = entry.seriesId,
                        seriesName = entry.seriesName,
                        seriesIndex = entry.seriesIndex,
                        pointIndex = index,
                        x = ChartX.Category(axes[index].label),
                        y = entry.values.getOrNull(index) ?: 0.0,
                        item = entry.items.getOrNull(index),
                        position = vertex,
                    )
                }
            }
        }
        return best
    }

    /** Every series' value on the selected spoke — the comparison radar is for. */
    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        val axisIndex = axes.indexOfFirst { ChartX.Category(it.label) == selection.x }
        if (axisIndex < 0) return emptyList()
        return series.mapNotNull { entry ->
            val value = entry.values.getOrNull(axisIndex) ?: return@mapNotNull null
            ChartTooltipEntry(
                seriesId = entry.seriesId,
                seriesName = entry.seriesName,
                value = value,
                item = entry.items.getOrNull(axisIndex),
                paletteIndex = entry.paletteIndex,
            )
        }
    }

    override fun describe(): List<ChartLayerSummary> = series.map { entry ->
        ChartLayerSummary(
            seriesId = entry.seriesId,
            seriesName = entry.seriesName,
            pointCount = axes.size,
            entries = axes.mapIndexed { index, axis ->
                ChartLayerEntry(
                    label = axis.label,
                    value = entry.values.getOrNull(index),
                    detail = entry.values.getOrNull(index)?.let {
                        "${axis.label}: ${valueFormatter.format(it)}"
                    } ?: "${axis.label}: no value",
                )
            },
        )
    }

    private companion object {
        /** Solid enough to read as a region, light enough to see the one beneath. */
        const val FILL_ALPHA = 0.22f
        const val HIT_TOLERANCE_FACTOR = 3f
    }
}

/** The angle of spoke [index] of [count], in ChartKit's convention. */
internal fun angleOf(index: Int, count: Int, startAngle: Float): Float =
    if (count <= 0) startAngle else PolarGeometry.normalizeAngle(startAngle + 360f * index / count)

/** A closed regular polygon with a vertex on every spoke. */
internal fun polygon(
    center: ChartOffset,
    radius: Float,
    startAngle: Float,
    sides: Int,
): Path {
    val path = Path()
    if (sides < 3 || radius <= 0f) return path
    for (index in 0 until sides) {
        val point = PolarGeometry.pointOnCircle(center, radius, angleOf(index, sides, startAngle))
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    return path
}
