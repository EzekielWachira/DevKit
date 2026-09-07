package io.devkit.chartkit.layer.line

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.LineInterpolation
import io.devkit.chartkit.geometry.LinePoint
import io.devkit.chartkit.geometry.LineSegment
import io.devkit.chartkit.geometry.monotoneControlPoints
import io.devkit.chartkit.geometry.nearestPointIndex
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartX
import kotlin.math.abs

/** How the line itself is stroked. */
enum class LineStyle {
    Solid,

    /** A dashed stroke. Useful for a forecast series against actuals. */
    Dashed,
}

/** When point markers are drawn. */
enum class PointMode {

    /** Never. */
    None,

    /** On every point. */
    Always,

    /** Only on the selected point. */
    SelectedOnly,

    /**
     * On every point while the series is small enough for them to be legible,
     * and only on the selection once it is not. The default.
     *
     * A thousand markers on a line five hundred pixels wide are a solid band,
     * not information — and they cost a thousand `drawCircle` calls a frame to
     * produce it.
     */
    Auto,
    ;

    internal fun drawsAll(pointCount: Int, threshold: Int): Boolean = when (this) {
        None, SelectedOnly -> false
        Always -> true
        Auto -> pointCount <= threshold
    }

    internal val drawsSelected: Boolean get() = this != None
}

/** How the space between a line and its baseline is filled. */
data class AreaFill(
    /** `0..1`. Solid enough to read as a region, light enough to see through. */
    val alpha: Float = 0.22f,
    /** Fades towards the baseline. Off gives a flat wash. */
    val gradient: Boolean = true,
) {
    init {
        require(alpha in 0f..1f) { "Area fill alpha must be in 0..1, was $alpha" }
    }

    companion object {
        val Default: AreaFill = AreaFill()
    }
}

/** One series' screen geometry, computed once per layout rather than per frame. */
internal class LineSeriesGeometry(
    val seriesId: String,
    val seriesName: String,
    val seriesIndex: Int,
    val paletteIndex: Int,
    val colorOverride: Int?,
    /** Parallel to the source data; `null` where the value was missing. */
    val points: List<LinePoint?>,
    /** Runs of consecutive present points — the gaps are what breaks the line. */
    val segments: List<LineSegment>,
    val presentPoints: List<LinePoint>,
    val sortedByDomain: Boolean,
    val items: List<Any?>,
    val xValues: List<ChartX>,
)

/**
 * Lines, and optionally the area beneath them and markers on them.
 *
 * One layer rather than three because they are one geometry: an area chart is a
 * line chart whose path is closed to the baseline, and a point marker sits on a
 * line vertex. Splitting them would mean computing the same interpolated path
 * two or three times and keeping the copies in agreement.
 *
 * Paths are built lazily and cached against the plot size, so an animating
 * reveal — which redraws every frame — does not re-interpolate the curve
 * sixty times a second. The reveal itself is a clip, not a rebuild.
 */
internal class LineLayer(
    override val id: String,
    private val series: List<LineSeriesGeometry>,
    private val interpolation: LineInterpolation,
    private val style: LineStyle,
    private val fill: AreaFill?,
    private val pointMode: PointMode,
    private val lineWidthOverride: androidx.compose.ui.unit.Dp?,
    private val pointMarkerThreshold: Int,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = series.map { it.seriesId }

    private var cachedPaths: Map<String, LinePaths>? = null
    private var cacheKey: Any? = null

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val plot = context.coordinates.plotArea
        if (plot.isEmpty || series.isEmpty()) return

        val paths = paths(plot, baselineWithin(context))
        val strokeWidth = context.px(lineWidthOverride ?: context.dimensions.lineWidth)
        val vertical = context.coordinates.orientation.isVertical
        val reveal = context.reveal.coerceIn(0f, 1f)

        // Revealing by clipping rather than by trimming the path: a path
        // rebuilt per frame at 10,000 points is the difference between a
        // smooth animation and a stutter, and the visual result is identical.
        val clipRight = if (vertical) plot.left + plot.width * reveal else plot.right
        val clipBottom = if (vertical) plot.bottom else plot.top + plot.height * reveal

        scope.clipRect(
            left = plot.left,
            top = plot.top,
            right = clipRight,
            bottom = clipBottom,
        ) {
            // Areas first, then every line, so a line is never buried under the
            // next series' fill.
            if (fill != null) {
                series.forEach { s ->
                    val colour = context.seriesColor(s)
                    paths[s.seriesId]?.area?.let { area ->
                        drawPath(
                            path = area,
                            brush = fillBrush(colour, plot.top, plot.bottom, vertical, plot.left, plot.right),
                        )
                    }
                }
            }
            series.forEach { s ->
                val colour = context.seriesColor(s)
                paths[s.seriesId]?.line?.let { line ->
                    drawPath(
                        path = line,
                        color = colour,
                        style = Stroke(
                            width = strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = if (style == LineStyle.Dashed) {
                                PathEffect.dashPathEffect(
                                    floatArrayOf(strokeWidth * 3f, strokeWidth * 2f),
                                )
                            } else {
                                null
                            },
                        ),
                    )
                }
            }
        }

        drawMarkers(scope, context)
    }

    private fun drawMarkers(scope: DrawScope, context: ChartRenderContext) {
        if (pointMode == PointMode.None) return
        val radius = context.px(context.dimensions.pointRadius)
        val selectedRadius = context.px(context.dimensions.selectedPointRadius)
        val reveal = context.reveal.coerceIn(0f, 1f)
        val plot = context.coordinates.plotArea
        val vertical = context.coordinates.orientation.isVertical
        val revealEdge = if (vertical) plot.left + plot.width * reveal else plot.top + plot.height * reveal

        series.forEach { s ->
            val colour = context.seriesColor(s)
            val drawAll = pointMode.drawsAll(s.presentPoints.size, pointMarkerThreshold)
            s.presentPoints.forEach { point ->
                val along = if (vertical) point.position.x else point.position.y
                if (along > revealEdge) return@forEach
                val isSelected = context.selection?.seriesId == s.seriesId &&
                    context.selection.pointIndex == point.sourceIndex
                when {
                    isSelected && pointMode.drawsSelected -> {
                        scope.drawCircle(
                            color = colour,
                            radius = selectedRadius,
                            center = Offset(point.position.x, point.position.y),
                        )
                        // A ring in the surface colour keeps the emphasised
                        // point legible against a line of the same colour —
                        // size alone is hard to see, and opacity alone is not
                        // an accessible signal.
                        scope.drawCircle(
                            color = context.colors.tooltipContent,
                            radius = selectedRadius / 2.4f,
                            center = Offset(point.position.x, point.position.y),
                        )
                    }
                    drawAll -> scope.drawCircle(
                        color = colour,
                        radius = radius,
                        center = Offset(point.position.x, point.position.y),
                    )
                }
            }
        }
    }

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        if (series.isEmpty()) return null
        val vertical = context.coordinates.orientation.isVertical
        val along = if (vertical) point.x else point.y

        var best: AnyChartSelection? = null
        var bestDistance = Float.MAX_VALUE

        series.forEach { s ->
            if (s.presentPoints.isEmpty()) return@forEach
            val index = nearestPointIndex(s.presentPoints, along, s.sortedByDomain)
            if (index < 0) return@forEach
            val candidate = s.presentPoints[index]
            // Along the domain axis for a scrub — the reader is choosing an x,
            // not aiming at a pixel — but full 2D distance decides *which*
            // series wins when several are stacked at the same x.
            val distance = when (mode) {
                HitTestMode.NearestDomain -> {
                    val domainDelta = abs(
                        (if (vertical) candidate.position.x else candidate.position.y) - along,
                    )
                    val valueDelta = abs(
                        (if (vertical) candidate.position.y else candidate.position.x) -
                            (if (vertical) point.y else point.x),
                    )
                    domainDelta * DOMAIN_DISTANCE_WEIGHT + valueDelta
                }
                HitTestMode.Contains -> {
                    val dx = candidate.position.x - point.x
                    val dy = candidate.position.y - point.y
                    val radius = context.px(context.dimensions.selectedPointRadius) * 2f
                    val d = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (d > radius) return@forEach else d
                }
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = ChartSelection(
                    seriesId = s.seriesId,
                    seriesName = s.seriesName,
                    seriesIndex = s.seriesIndex,
                    pointIndex = candidate.sourceIndex,
                    x = s.xValues.getOrElse(candidate.sourceIndex) { ChartX.Numeric(0.0) },
                    y = candidate.value,
                    item = s.items.getOrNull(candidate.sourceIndex),
                    position = candidate.position,
                )
            }
        }
        return best
    }

    override fun describe(): List<ChartLayerSummary> = series.map { s ->
        ChartLayerSummary(
            seriesId = s.seriesId,
            seriesName = s.seriesName,
            pointCount = s.points.size,
            entries = s.points.mapIndexed { index, point ->
                ChartLayerEntry(
                    label = s.xValues.getOrNull(index).labelOrIndex(index),
                    value = point?.value,
                )
            },
        )
    }

    private fun ChartRenderContext.seriesColor(s: LineSeriesGeometry): Color =
        s.colorOverride?.let { Color(it) } ?: colors.seriesColor(s.paletteIndex)

    private fun fillBrush(
        colour: Color,
        top: Float,
        bottom: Float,
        vertical: Boolean,
        left: Float,
        right: Float,
    ): Brush {
        val alpha = fill?.alpha ?: 0f
        if (fill?.gradient != true) return SolidColor(colour.copy(alpha = alpha))
        val stops = listOf(colour.copy(alpha = alpha), colour.copy(alpha = 0f))
        return if (vertical) {
            Brush.verticalGradient(stops, startY = top, endY = bottom)
        } else {
            Brush.horizontalGradient(stops.reversed(), startX = left, endX = right)
        }
    }

    /**
     * The pixel row the area closes down to.
     *
     * The zero line where the domain contains it, and the nearer plot edge
     * where it does not. Filling to the bottom of the composable instead would
     * shade a region whose height means nothing whenever the axis starts above
     * zero.
     */
    private fun baselineWithin(context: ChartRenderContext): Float {
        val plot = context.coordinates.plotArea
        val zero = context.coordinates.baseline
        return zero.coerceIn(plot.top, plot.bottom)
    }

    /** Builds and caches the stroke and fill paths for the current plot size. */
    private fun paths(
        plot: io.devkit.chartkit.geometry.ChartRect,
        baseline: Float,
    ): Map<String, LinePaths> {
        val key = plot to baseline
        val cached = cachedPaths
        if (cached != null && cacheKey == key) return cached
        val built = series.associate { s -> s.seriesId to buildPaths(s, baseline) }
        cachedPaths = built
        cacheKey = key
        return built
    }

    private fun buildPaths(s: LineSeriesGeometry, baseline: Float): LinePaths {
        val line = Path()
        val area = if (fill != null) Path() else null
        s.segments.forEach { segment ->
            appendSegment(line, segment)
            if (area != null) appendAreaSegment(area, segment, baseline)
        }
        return LinePaths(line, area)
    }

    private fun appendSegment(path: Path, segment: LineSegment) {
        val points = segment.points
        if (points.isEmpty()) return
        val first = points.first().position
        path.moveTo(first.x, first.y)
        if (points.size == 1) {
            // A single point has no segment to stroke; the marker carries it.
            return
        }
        when (interpolation) {
            LineInterpolation.Linear -> points.drop(1).forEach { path.lineTo(it.position.x, it.position.y) }
            LineInterpolation.Step -> {
                var previous = first
                points.drop(1).forEach { point ->
                    path.lineTo(point.position.x, previous.y)
                    path.lineTo(point.position.x, point.position.y)
                    previous = point.position
                }
            }
            LineInterpolation.Smooth -> {
                for (index in 0 until points.size - 1) {
                    val next = points[index + 1].position
                    val controls = monotoneControlPoints(points, index)
                    if (controls == null) {
                        path.lineTo(next.x, next.y)
                    } else {
                        val (c0, c1) = controls
                        path.cubicTo(c0.x, c0.y, c1.x, c1.y, next.x, next.y)
                    }
                }
            }
        }
    }

    private fun appendAreaSegment(path: Path, segment: LineSegment, baseline: Float) {
        val points = segment.points
        if (points.isEmpty()) return
        appendSegment(path, segment)
        val last = points.last().position
        val first = points.first().position
        path.lineTo(last.x, baseline)
        path.lineTo(first.x, baseline)
        path.close()
    }

    private data class LinePaths(val line: Path, val area: Path?)

    private companion object {
        /**
         * How much more a pixel along the domain axis counts than one across it.
         *
         * Scrubbing is a choice of x: the reader drags horizontally and expects
         * the selection to follow, even when their finger is nowhere near the
         * line vertically. Weighting the domain distance up keeps that true
         * while still letting the value distance break ties between series.
         */
        const val DOMAIN_DISTANCE_WEIGHT = 4f
    }
}

internal fun ChartX?.labelOrIndex(index: Int): String = when (this) {
    is ChartX.Category -> label
    is ChartX.Numeric -> value.toString()
    is ChartX.Time -> epochMillis.toString()
    null -> index.toString()
}
