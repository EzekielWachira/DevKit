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
import io.devkit.chartkit.model.ChartTooltipEntry
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

/**
 * One series' screen geometry, computed once per layout rather than per frame.
 *
 * ### Drawn points and source values are separate lists
 *
 * [points] holds only what is **drawn** — the visible window, after any
 * downsampling — while [xValues], [values] and [items] stay parallel to the
 * caller's whole list. Keeping the two apart is what lets a chart draw two
 * thousand of a hundred thousand points while a tooltip, a selection and an
 * accessibility announcement still refer to the original observation. A single
 * list would force a choice between drawing everything and lying about what
 * exists.
 *
 * @param points the drawn positions. `null` entries mark gaps between drawn
 *   runs, which is what breaks the line under
 *   [io.devkit.chartkit.model.MissingValuePolicy.Break].
 * @param domainValues the continuous domain position of every source point,
 *   ascending, or `null` on a category axis or unordered data. Present so hit
 *   testing can binary-search the **whole** series rather than only the drawn
 *   subset — a scrub over a downsampled line still selects the observation
 *   nearest the finger, not the nearest one that survived sampling.
 * @param values every source point's value, `null` where missing.
 */
internal class LineSeriesGeometry(
    val seriesId: String,
    val seriesName: String,
    val seriesIndex: Int,
    val paletteIndex: Int,
    val colorOverride: Int?,
    val points: List<LinePoint?>,
    /** Runs of consecutive present points — the gaps are what breaks the line. */
    val segments: List<LineSegment>,
    val presentPoints: List<LinePoint>,
    val sortedByDomain: Boolean,
    val items: List<Any?>,
    val xValues: List<ChartX>,
    val values: List<Double?> = emptyList(),
    val domainValues: DoubleArray? = null,
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
        val plot = context.cartesian.plotArea
        if (plot.isEmpty || series.isEmpty()) return

        val paths = paths(plot, baselineWithin(context))
        val strokeWidth = context.px(lineWidthOverride ?: context.dimensions.lineWidth)
        val vertical = context.cartesian.orientation.isVertical
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

    /**
     * Segments as polylines, and the area beneath them as closed polygons.
     *
     * Straight-line interpolation only. A curved line is a Bézier the layer
     * hands to Compose, and flattening it into points here would produce a
     * *different* curve from the one on screen — subtly, and invisibly, which is
     * the worst kind of export error. A curved chart is reported as unexported
     * and a caller falls back to a raster capture.
     */
    override fun renderScene(
        builder: io.devkit.chartkit.scene.ChartSceneBuilder,
        context: ChartRenderContext,
    ): Boolean {
        if (interpolation != LineInterpolation.Linear) {
            builder.unexported(id)
            return false
        }
        val plot = context.cartesian.plotArea
        if (plot.isEmpty || series.isEmpty()) return true
        val strokeWidth = context.px(lineWidthOverride ?: context.dimensions.lineWidth)
        val baseline = baselineWithin(context)

        builder.group(id, clip = plot) {
            series.forEach { entry ->
                val colour = context.seriesColor(entry)
                entry.segments.forEach { segment ->
                    val points = segment.points.map { it.position }
                    if (points.size < 2) return@forEach
                    if (fill != null) {
                        // Closed down to the baseline and back, which is the
                        // same polygon the area path describes.
                        add(
                            io.devkit.chartkit.scene.ChartSceneNode.Path(
                                points = points +
                                    io.devkit.chartkit.geometry.ChartOffset(points.last().x, baseline) +
                                    io.devkit.chartkit.geometry.ChartOffset(points.first().x, baseline),
                                color = colour.copy(alpha = colour.alpha * AREA_EXPORT_ALPHA),
                                style = io.devkit.chartkit.scene.PaintStyle.Fill,
                                closed = true,
                            ),
                        )
                    }
                    add(
                        io.devkit.chartkit.scene.ChartSceneNode.Path(
                            points = points,
                            color = colour,
                            style = io.devkit.chartkit.scene.PaintStyle.Stroke,
                            strokeWidth = strokeWidth,
                            dash = if (style == LineStyle.Dashed) {
                                floatArrayOf(strokeWidth * 3f, strokeWidth * 2f)
                            } else {
                                null
                            },
                        ),
                    )
                }

                if (pointMode != PointMode.None &&
                    pointMode.drawsAll(entry.presentPoints.size, pointMarkerThreshold)
                ) {
                    val radius = context.px(context.dimensions.pointRadius)
                    entry.presentPoints.forEach { point ->
                        add(
                            io.devkit.chartkit.scene.ChartSceneNode.Circle(
                                center = point.position,
                                radius = radius,
                                color = colour,
                            ),
                        )
                    }
                }
            }
        }
        return true
    }

    private fun drawMarkers(scope: DrawScope, context: ChartRenderContext) {
        if (pointMode == PointMode.None) return
        val radius = context.px(context.dimensions.pointRadius)
        val selectedRadius = context.px(context.dimensions.selectedPointRadius)
        val reveal = context.reveal.coerceIn(0f, 1f)
        val plot = context.cartesian.plotArea
        val vertical = context.cartesian.orientation.isVertical
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
        val vertical = context.cartesian.orientation.isVertical
        val along = if (vertical) point.x else point.y

        var best: AnyChartSelection? = null
        var bestDistance = Float.MAX_VALUE

        series.forEach { s ->
            if (s.presentPoints.isEmpty()) return@forEach
            // The whole series where the data allows it, so downsampling
            // changes what is drawn and not what can be selected.
            val candidate = nearestSourcePoint(s, along, context)
                ?: s.presentPoints.getOrNull(
                    nearestPointIndex(s.presentPoints, along, s.sortedByDomain),
                )
                ?: return@forEach
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

    /**
     * Every series' value at the selected domain position.
     *
     * Matched on the resolved [ChartX] rather than on the point index: two
     * series over the same months need not have the same number of points, and
     * index matching would report February's revenue against March's expenses.
     */
    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> = series.mapNotNull { s ->
        val index = s.xValues.indexOfFirst { it == selection.x }
        if (index < 0) return@mapNotNull null
        // Read from the source values, not from the drawn points: on a
        // downsampled series the point at this x may not have been drawn, and
        // the reader still asked what the series was worth there.
        val value = s.values.getOrNull(index) ?: return@mapNotNull null
        ChartTooltipEntry(
            seriesId = s.seriesId,
            seriesName = s.seriesName,
            value = value,
            item = s.items.getOrNull(index),
            paletteIndex = s.paletteIndex,
        )
    }

    override fun describe(): List<ChartLayerSummary> = series.map { s ->
        val present = s.values.filterNotNull()
        ChartLayerSummary(
            seriesId = s.seriesId,
            seriesName = s.seriesName,
            pointCount = s.values.size,
            // Past the announcement cap the entries would be built, allocated
            // and then never read: the summary falls back to a range at that
            // size, and computing the range is all that is needed.
            entries = if (s.values.size <= io.devkit.chartkit.accessibility.ChartAccessibility.MAX_ANNOUNCED_POINTS) {
                s.values.mapIndexed { index, value ->
                    ChartLayerEntry(
                        label = s.xValues.getOrNull(index).labelOrIndex(index),
                        value = value,
                    )
                }
            } else {
                emptyList()
            },
            valueRange = present.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() },
            missingCount = s.values.size - present.size,
        )
    }

    /**
     * The nearest **source** observation to a domain pixel.
     *
     * Binary search over the full series' domain values, then the position is
     * recomputed from the scales — so the answer does not depend on whether the
     * point survived downsampling or fell outside the drawn window. Returns
     * `null` on a category axis or unordered data, where there is no sorted
     * domain to search and the drawn points are the best available answer.
     */
    private fun nearestSourcePoint(
        s: LineSeriesGeometry,
        along: Float,
        context: ChartRenderContext,
    ): LinePoint? {
        val domainValues = s.domainValues ?: return null
        if (domainValues.isEmpty() || !s.sortedByDomain) return null
        val coordinates = context.cartesian
        val scale = coordinates.continuousDomain ?: return null
        val target = scale.invert(along)

        var index = io.devkit.chartkit.data.VisibleRange.lowerBound(domainValues, target)
        if (index >= domainValues.size) index = domainValues.size - 1
        val previous = (index - 1).coerceAtLeast(0)
        var best = if (
            kotlin.math.abs(domainValues[previous] - target) <=
            kotlin.math.abs(domainValues[index] - target)
        ) {
            previous
        } else {
            index
        }

        // Walk outward to the nearest point that actually has a value: a gap in
        // the data is not a place the selection can land.
        if (s.values.getOrNull(best) == null) {
            var offset = 1
            var found = -1
            while (offset < domainValues.size) {
                val low = best - offset
                val high = best + offset
                if (low < 0 && high >= domainValues.size) break
                if (low >= 0 && s.values.getOrNull(low) != null) {
                    found = low
                    break
                }
                if (high < domainValues.size && s.values.getOrNull(high) != null) {
                    found = high
                    break
                }
                offset++
            }
            if (found < 0) return null
            best = found
        }

        val value = s.values.getOrNull(best) ?: return null
        val domainPosition = coordinates.positionOfDomain(domainValues[best]) ?: return null
        val offset = coordinates.pointAt(domainPosition, coordinates.positionOfValue(value))
        if (!offset.isFinite) return null
        return LinePoint(offset, best, value)
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
        val plot = context.cartesian.plotArea
        val zero = context.cartesian.baseline
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

/**
 * The flat opacity a gradient area fill is exported at.
 *
 * SVG gradients are expressible, but the fill on screen is a vertical ramp
 * whose stops depend on the plot's height — reproducing it exactly would mean
 * emitting a `<linearGradient>` per series keyed on a layout that the exported
 * file no longer has. A single translucent fill is visibly the same shape and
 * honestly a simplification.
 */
private const val AREA_EXPORT_ALPHA = 0.25f
