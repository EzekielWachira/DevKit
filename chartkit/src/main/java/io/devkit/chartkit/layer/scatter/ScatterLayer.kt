package io.devkit.chartkit.layer.scatter

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ScatterIndex
import io.devkit.chartkit.geometry.ScatterPoint
import io.devkit.chartkit.geometry.ScatterShape
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX
import kotlin.math.sqrt

/**
 * How scatter markers are filled.
 *
 * @param alpha `0..1`. Below `1` on purpose for bubbles: overlapping opaque
 *   bubbles hide each other entirely, and the reader cannot tell one large
 *   bubble from three stacked ones. Translucency makes the overlap visible,
 *   which is information rather than decoration.
 * @param strokeWidthFraction the outline, as a fraction of the marker's radius.
 *   An outline is what keeps two overlapping translucent bubbles readable as
 *   two.
 */
data class ScatterStyle(
    val alpha: Float = 1f,
    val strokeWidthFraction: Float = 0f,
) {
    init {
        require(alpha in 0f..1f) { "Scatter alpha must be in 0..1, was $alpha" }
        require(strokeWidthFraction >= 0f) { "Stroke width fraction cannot be negative" }
    }

    companion object {
        /** Opaque, unstroked. Right for a plain scatter, where markers are small. */
        val Point: ScatterStyle = ScatterStyle()

        /** Translucent with an outline. Right for bubbles, which overlap. */
        val Bubble: ScatterStyle = ScatterStyle(alpha = 0.55f, strokeWidthFraction = 0.08f)
    }
}

/** One scatter series' screen geometry, computed once per layout. */
internal class ScatterSeriesGeometry(
    val seriesId: String,
    val seriesName: String,
    val seriesIndex: Int,
    val paletteIndex: Int,
    val colorOverride: Int?,
    val points: List<ScatterPoint>,
    val items: List<Any?>,
    val xValues: List<ChartX>,
    /** The plotted value per source index; `null` where the point was missing. */
    val values: List<Double?>,
    /** The size-encoded value per source index, for a bubble chart's tooltip. */
    val sizeValues: List<Double?>,
)

/**
 * Scatter and bubble markers.
 *
 * One layer for both, because a bubble chart *is* a scatter whose radius comes
 * from a third variable rather than from the theme — the difference lives in
 * [io.devkit.chartkit.scale.SizeScale] and in the geometry handed here, not in
 * the drawing.
 *
 * Markers are drawn on the chart's own canvas, one primitive each. A composable
 * per point would put a layout node on every observation, which is fine for
 * forty and impossible for fifty thousand.
 *
 * Hit testing goes through a [ScatterIndex] rather than a scan: scatter data
 * has no order to binary-search, and a scan is `O(n)` on every pointer frame.
 */
internal class ScatterLayer(
    override val id: String,
    private val series: List<ScatterSeriesGeometry>,
    private val shape: ScatterShape,
    private val style: ScatterStyle,
    private val sizeEncoded: Boolean,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = series.map { it.seriesId }

    private var indices: Map<String, ScatterIndex>? = null
    private var indexKey: Any? = null

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val plot = context.cartesian.plotArea
        if (plot.isEmpty || series.isEmpty()) return

        val reveal = context.reveal.coerceIn(0f, 1f)
        val selection = context.selection

        series.forEach { s ->
            val colour = s.colorOverride?.let { Color(it) }
                ?: context.colors.seriesColor(s.paletteIndex)
            val fill = if (style.alpha >= 1f) colour else colour.copy(alpha = style.alpha)

            s.points.forEach { point ->
                // Markers grow into place rather than fading: a half-opaque
                // marker reads as a low-confidence observation, and a
                // half-sized one reads as an animation.
                val radius = point.radius * reveal
                if (radius <= 0f) return@forEach

                val selected = selection?.seriesId == s.seriesId &&
                    selection.pointIndex == point.sourceIndex

                scope.drawMarker(shape, point.position, radius, fill)
                if (style.strokeWidthFraction > 0f) {
                    scope.drawMarkerOutline(
                        shape = shape,
                        center = point.position,
                        radius = radius,
                        color = colour,
                        strokeWidth = (radius * style.strokeWidthFraction).coerceAtLeast(1f),
                    )
                }
                if (selected) {
                    // A ring outside the marker, so emphasis does not depend on
                    // the marker's own size — which on a bubble chart is
                    // already carrying a value.
                    scope.drawMarkerOutline(
                        shape = shape,
                        center = point.position,
                        radius = radius + context.px(context.dimensions.selectionGuideWidth) * 2f,
                        color = context.colors.selectionGuide,
                        strokeWidth = context.px(context.dimensions.selectionGuideWidth) * 2f,
                    )
                }
            }
        }
    }

    private fun DrawScope.drawMarker(
        shape: ScatterShape,
        center: ChartOffset,
        radius: Float,
        color: Color,
    ) {
        when (shape) {
            ScatterShape.Circle -> drawCircle(color, radius, Offset(center.x, center.y))
            ScatterShape.Square -> {
                // Matched by area to the circle of the same radius, so a chart
                // mixing shapes does not appear to encode a magnitude.
                val half = radius * SQUARE_AREA_MATCH
                drawRect(
                    color = color,
                    topLeft = Offset(center.x - half, center.y - half),
                    size = Size(half * 2f, half * 2f),
                )
            }
            ScatterShape.Diamond -> drawPath(diamondPath(center, radius * DIAMOND_AREA_MATCH), color)
        }
    }

    private fun DrawScope.drawMarkerOutline(
        shape: ScatterShape,
        center: ChartOffset,
        radius: Float,
        color: Color,
        strokeWidth: Float,
    ) {
        val stroke = Stroke(width = strokeWidth)
        when (shape) {
            ScatterShape.Circle ->
                drawCircle(color, radius, Offset(center.x, center.y), style = stroke)
            ScatterShape.Square -> {
                val half = radius * SQUARE_AREA_MATCH
                drawRect(
                    color = color,
                    topLeft = Offset(center.x - half, center.y - half),
                    size = Size(half * 2f, half * 2f),
                    style = stroke,
                )
            }
            ScatterShape.Diamond ->
                drawPath(diamondPath(center, radius * DIAMOND_AREA_MATCH), color, style = stroke)
        }
    }

    private fun diamondPath(center: ChartOffset, half: Float): Path = Path().apply {
        moveTo(center.x, center.y - half)
        lineTo(center.x + half, center.y)
        lineTo(center.x, center.y + half)
        lineTo(center.x - half, center.y)
        close()
    }

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val plot = context.cartesian.plotArea
        if (plot.isEmpty || series.isEmpty()) return null
        val indexed = indices(plot)

        // A generous target: markers are small, fingers are not, and the
        // alternative to a tolerance is a chart nothing can be selected on.
        val tolerance = context.px(context.dimensions.selectedPointRadius) * HIT_TOLERANCE_FACTOR

        var best: AnyChartSelection? = null
        var bestDistance = Float.MAX_VALUE

        series.forEach { s ->
            val index = indexed[s.seriesId] ?: return@forEach
            val hit = index.nearest(point, tolerance)
            if (hit < 0) return@forEach
            val candidate = s.points[hit]
            val dx = candidate.position.x - point.x
            val dy = candidate.position.y - point.y
            val distance = sqrt(dx * dx + dy * dy)
            if (distance < bestDistance) {
                bestDistance = distance
                best = ChartSelection(
                    seriesId = s.seriesId,
                    seriesName = s.seriesName,
                    seriesIndex = s.seriesIndex,
                    pointIndex = candidate.sourceIndex,
                    x = s.xValues.getOrElse(candidate.sourceIndex) { ChartX.Numeric(0.0) },
                    y = s.values.getOrNull(candidate.sourceIndex) ?: 0.0,
                    item = s.items.getOrNull(candidate.sourceIndex),
                    // The top of the marker, so a tooltip does not sit on the
                    // observation it is describing.
                    position = ChartOffset(candidate.position.x, candidate.position.y - candidate.radius),
                )
            }
        }
        return best
    }

    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> = series.mapNotNull { s ->
        if (s.seriesId != selection.seriesId) return@mapNotNull null
        val value = s.values.getOrNull(selection.pointIndex) ?: return@mapNotNull null
        ChartTooltipEntry(
            seriesId = s.seriesId,
            seriesName = s.seriesName,
            value = value,
            item = s.items.getOrNull(selection.pointIndex),
            paletteIndex = s.paletteIndex,
        )
    }

    override fun describe(): List<ChartLayerSummary> = series.map { s ->
        ChartLayerSummary(
            seriesId = s.seriesId,
            seriesName = s.seriesName,
            pointCount = s.values.size,
            entries = s.values.mapIndexed { index, value ->
                val x = s.xValues.getOrNull(index)
                val label = when (x) {
                    is ChartX.Category -> x.label
                    is ChartX.Numeric -> formatShort(x.value)
                    is ChartX.Time -> x.epochMillis.toString()
                    null -> index.toString()
                }
                // Both coordinates: on a scatter the pair is the observation,
                // and reading out only the y would describe half of it. The
                // size, where one is encoded, is the third fact.
                val size = s.sizeValues.getOrNull(index)
                ChartLayerEntry(
                    label = label,
                    value = value,
                    detail = buildString {
                        append("x ")
                        append(label)
                        append(", y ")
                        append(value?.let(::formatShort) ?: "no value")
                        if (sizeEncoded && size != null) {
                            append(", size ")
                            append(formatShort(size))
                        }
                    },
                )
            },
        )
    }

    /** Builds and caches one spatial index per series for the current plot. */
    private fun indices(plot: io.devkit.chartkit.geometry.ChartRect): Map<String, ScatterIndex> {
        val cached = indices
        if (cached != null && indexKey == plot) return cached
        val built = series.associate { s -> s.seriesId to ScatterIndex(s.points, plot) }
        indices = built
        indexKey = plot
        return built
    }

    private companion object {
        /** Side/2 of a square with the area of a unit circle: √π / 2. */
        const val SQUARE_AREA_MATCH = 0.8862269f

        /** Half-diagonal of a diamond with the area of a unit circle: √(2π) / 2. */
        const val DIAMOND_AREA_MATCH = 1.2533141f

        /** How far past a marker a tap still selects it. */
        const val HIT_TOLERANCE_FACTOR = 3f
    }
}

/** Compact enough for a screen reader; exact enough to be checked. */
internal fun formatShort(value: Double): String =
    if (!value.isFinite()) "" else java.text.DecimalFormat("0.###").format(value)
