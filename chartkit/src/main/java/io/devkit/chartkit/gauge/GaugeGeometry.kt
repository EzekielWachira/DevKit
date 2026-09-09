package io.devkit.chartkit.gauge

import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.geometry.PolarGeometry
import kotlin.math.max
import kotlin.math.min

/**
 * The centre and radius a gauge of a given sweep should be drawn at.
 *
 * @param center where the pivot goes, in the bounds' own coordinates.
 * @param radius the outer radius that fits.
 */
class GaugeFit(val center: ChartOffset, val radius: Float)

/**
 * Where a needle's outline goes, and how big an arc actually is.
 *
 * Plain Kotlin: no Compose, no `android.graphics`. A needle is a short list of
 * points and an arc's extent is four comparisons, and both are things a test
 * should be able to check without a device.
 */
object GaugeGeometry {

    /**
     * The box an arc occupies, relative to its own centre.
     *
     * ### Why this is not simply the circle's box
     *
     * A semicircular speedometer drawn inside the square its full circle would
     * need wastes the entire bottom half. Centring it in that square then puts
     * the dial in the upper portion of the card with a hole beneath it, and
     * shrinking the card to fit makes the arc smaller rather than moving it.
     *
     * So the extent is computed from the sweep: the two endpoints, whichever of
     * the four compass extremes the arc actually passes through, and — because
     * a needle radiates from the middle — the centre itself.
     *
     * ```text
     *  -90° ─────── 0° ─────── 90°        the arc's own box
     *   ╭───────────────────────╮         is half the circle's:
     *   │                       │         full width, half height
     *   ╰───────────●───────────╯
     * ```
     *
     * @param includeCenter whether the pivot is part of the drawn content. True
     *   for a needle gauge; false for a bare arc, whose box on a narrow sweep
     *   is narrower still.
     */
    fun arcBounds(
        startAngle: Float,
        sweepAngle: Float,
        radius: Float = 1f,
        includeCenter: Boolean = true,
    ): ChartRect {
        if (radius <= 0f) return ChartRect.Zero
        val sweep = sweepAngle.coerceIn(0f, PolarGeometry.FULL_CIRCLE)
        if (sweep >= PolarGeometry.FULL_CIRCLE) {
            return ChartRect(-radius, -radius, radius, radius)
        }

        var left = if (includeCenter) 0f else Float.MAX_VALUE
        var top = if (includeCenter) 0f else Float.MAX_VALUE
        var right = if (includeCenter) 0f else -Float.MAX_VALUE
        var bottom = if (includeCenter) 0f else -Float.MAX_VALUE

        fun include(angle: Float) {
            val point = pointAt(angle, radius)
            left = min(left, point.x)
            top = min(top, point.y)
            right = max(right, point.x)
            bottom = max(bottom, point.y)
        }

        include(startAngle)
        include(startAngle + sweep)
        // The compass points the arc passes through are where it bulges; an
        // extent taken from the endpoints alone would clip every arc that
        // crosses one.
        COMPASS.forEach { compass ->
            if (PolarGeometry.angleFrom(startAngle, compass, io.devkit.chartkit.geometry.PolarDirection.Clockwise) <= sweep) {
                include(compass)
            }
        }
        return ChartRect(left, top, right, bottom)
    }

    /**
     * The largest gauge of this sweep that fits [bounds], and where its centre
     * goes.
     *
     * The arc's own box — not the circle's — is fitted to the space, so a
     * semicircle in a wide, short card gets a radius set by the width rather
     * than by the height, and its pivot sits at the bottom where a speedometer's
     * belongs instead of in the middle of the card.
     *
     * @param reserve pixels kept outside the arc, for tick labels drawn there.
     */
    fun fit(
        bounds: ChartRect,
        startAngle: Float,
        sweepAngle: Float,
        reserve: Float = 0f,
        includeCenter: Boolean = true,
    ): GaugeFit {
        val centre = ChartOffset(bounds.centerX, bounds.centerY)
        if (bounds.isEmpty) return GaugeFit(centre, 0f)

        // The arc's extent at unit radius: a shape, independent of scale.
        val unit = arcBounds(startAngle, sweepAngle, radius = 1f, includeCenter = includeCenter)
        val unitWidth = unit.width
        val unitHeight = unit.height
        if (unitWidth <= 0f || unitHeight <= 0f) return GaugeFit(centre, 0f)

        val available = ChartRect(
            bounds.left + reserve,
            bounds.top + reserve,
            bounds.right - reserve,
            bounds.bottom - reserve,
        )
        if (available.isEmpty) return GaugeFit(centre, 0f)

        val radius = min(available.width / unitWidth, available.height / unitHeight)
        if (radius <= 0f) return GaugeFit(centre, 0f)

        // Place the centre so the arc's box lands centred in the space. The
        // unit box is relative to the centre, so its own centre is the offset
        // the pivot has to move by.
        return GaugeFit(
            center = ChartOffset(
                available.centerX - unit.centerX * radius,
                available.centerY - unit.centerY * radius,
            ),
            radius = radius,
        )
    }

    /** The point at [angle] and [radius] from the origin, in ChartKit's convention. */
    fun pointAt(angle: Float, radius: Float): ChartOffset =
        PolarGeometry.pointOnCircle(ChartOffset(0f, 0f), radius, angle)

    /**
     * The outline of a needle pointing at [angle], as a closed polygon.
     *
     * Returned as points rather than drawn, so the shape is testable and so the
     * same geometry serves the canvas and the scene export.
     *
     * ```text
     *          tip
     *           ╱╲
     *          ╱  ╲          length  = tip's distance from the pivot
     *         ╱    ╲         tail    = the stub behind it
     *        ▕  ●   ▏        base    = width at the pivot
     *         ╲tail╱         tip     = width at the point
     * ```
     *
     * @param length the tip's distance from the pivot, in pixels.
     * @param tail the stub's length behind the pivot, in pixels.
     */
    fun needlePolygon(
        center: ChartOffset,
        angle: Float,
        shape: GaugeNeedleShape,
        length: Float,
        tail: Float,
        baseWidth: Float,
        tipWidth: Float,
    ): List<ChartOffset> {
        if (length <= 0f) return emptyList()
        val halfBase = max(baseWidth, 0f) / 2f
        val halfTip = max(tipWidth, 0f) / 2f
        // The needle's own axes: `along` points at the value, `across` is
        // perpendicular. Building the outline from these rather than from
        // rotated rectangles keeps every shape one expression.
        val along = unitVector(angle)
        val across = ChartOffset(-along.y, along.x)

        fun at(distance: Float, offset: Float) = ChartOffset(
            center.x + along.x * distance + across.x * offset,
            center.y + along.y * distance + across.y * offset,
        )

        return when (shape) {
            GaugeNeedleShape.Line -> listOf(
                at(-tail, -halfBase),
                at(length, -halfBase),
                at(length, halfBase),
                at(-tail, halfBase),
            )

            GaugeNeedleShape.Triangle -> buildList {
                add(at(length, 0f))
                add(at(0f, halfBase))
                if (tail > 0f) add(at(-tail, 0f))
                add(at(0f, -halfBase))
            }

            GaugeNeedleShape.Needle -> buildList {
                add(at(length, -halfTip))
                add(at(length, halfTip))
                add(at(0f, halfBase))
                if (tail > 0f) {
                    add(at(-tail, halfBase * TAIL_TAPER))
                    add(at(-tail, -halfBase * TAIL_TAPER))
                }
                add(at(0f, -halfBase))
            }

            GaugeNeedleShape.Arrow -> {
                val headLength = min(length * ARROW_HEAD_FRACTION, length)
                val shaftEnd = length - headLength
                val headHalf = max(halfBase * ARROW_HEAD_SPREAD, halfBase)
                listOf(
                    at(-tail, -halfTip.coerceAtLeast(halfBase * ARROW_SHAFT)),
                    at(shaftEnd, -halfTip.coerceAtLeast(halfBase * ARROW_SHAFT)),
                    at(shaftEnd, -headHalf),
                    at(length, 0f),
                    at(shaftEnd, headHalf),
                    at(shaftEnd, halfTip.coerceAtLeast(halfBase * ARROW_SHAFT)),
                    at(-tail, halfTip.coerceAtLeast(halfBase * ARROW_SHAFT)),
                )
            }
        }
    }

    /**
     * The outline of a marker at [angle], sitting at [radius] from the centre.
     *
     * A triangle points inward at the arc; a line crosses it; a dot returns its
     * centre alone and is drawn as a circle.
     */
    fun markerPolygon(
        center: ChartOffset,
        angle: Float,
        radius: Float,
        shape: GaugeMarkerShape,
        size: Float,
    ): List<ChartOffset> {
        if (size <= 0f) return emptyList()
        val along = unitVector(angle)
        val across = ChartOffset(-along.y, along.x)
        fun at(distance: Float, offset: Float) = ChartOffset(
            center.x + along.x * distance + across.x * offset,
            center.y + along.y * distance + across.y * offset,
        )
        val half = size / 2f
        return when (shape) {
            // Apex on the arc, base outside it: the mark points at the value.
            GaugeMarkerShape.Triangle -> listOf(
                at(radius, 0f),
                at(radius + size, -half),
                at(radius + size, half),
            )
            GaugeMarkerShape.Line -> listOf(
                at(radius - size, -half * LINE_MARKER_WIDTH),
                at(radius + size, -half * LINE_MARKER_WIDTH),
                at(radius + size, half * LINE_MARKER_WIDTH),
                at(radius - size, half * LINE_MARKER_WIDTH),
            )
            GaugeMarkerShape.Dot -> listOf(at(radius, 0f))
        }
    }

    /** The unit vector pointing at [angle] in ChartKit's convention. */
    private fun unitVector(angle: Float): ChartOffset = pointAt(angle, 1f)

    /** The four compass extremes, where an arc's box is widest. */
    private val COMPASS = floatArrayOf(0f, 90f, 180f, 270f)

    private const val TAIL_TAPER = 0.7f
    private const val ARROW_HEAD_FRACTION = 0.28f
    private const val ARROW_HEAD_SPREAD = 2.2f
    private const val ARROW_SHAFT = 0.45f
    private const val LINE_MARKER_WIDTH = 0.5f
}
