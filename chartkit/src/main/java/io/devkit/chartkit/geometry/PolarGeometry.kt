package io.devkit.chartkit.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Which way a sweep advances.
 *
 * Pie and donut charts are read clockwise from the top in every convention a
 * reader is likely to have met, so that is the default. Counter-clockwise is
 * offered because gauges and some scientific plots run the other way, and
 * because it is one sign flip rather than a second set of geometry.
 */
enum class PolarDirection {
    Clockwise,
    CounterClockwise,
    ;

    /** `+1` clockwise, `-1` counter-clockwise, for advancing an angle. */
    internal val sign: Float get() = if (this == Clockwise) 1f else -1f
}

/**
 * Angle and arc arithmetic for polar charts.
 *
 * ### Angle convention
 *
 * **Zero degrees is at the top of the circle — twelve o'clock — and angles
 * increase clockwise.**
 *
 * That is not what a canvas does. `Canvas.drawArc` measures from three o'clock,
 * which is right for trigonometry and wrong for anybody describing a pie chart:
 * "the first slice starts at the top" is what a developer means, and
 * `startAngle = -90f` is what they would otherwise have to write. The
 * conversion happens once, in [toCanvasAngle], and no public API exposes the
 * canvas convention.
 *
 * All of this is plain Kotlin: no Compose, no `android.graphics`. Slice
 * boundaries, centroids and hit testing are testable on the JVM, which is where
 * the arithmetic that decides which slice a finger landed on belongs.
 */
object PolarGeometry {

    const val FULL_CIRCLE: Float = 360f

    /** The angle at which `Canvas` starts measuring, relative to the top. */
    private const val CANVAS_ZERO_OFFSET = -90f

    /** [degrees] mapped into `[0, 360)`. */
    fun normalizeAngle(degrees: Float): Float {
        if (!degrees.isFinite()) return 0f
        val wrapped = degrees % FULL_CIRCLE
        return if (wrapped < 0f) wrapped + FULL_CIRCLE else wrapped
    }

    /**
     * A ChartKit angle expressed in the convention `Canvas.drawArc` expects.
     *
     * The single place the two conventions meet.
     */
    fun toCanvasAngle(chartAngleDegrees: Float): Float =
        chartAngleDegrees + CANVAS_ZERO_OFFSET

    /**
     * The point [radius] from [center] at [angleDegrees].
     *
     * Screen y grows downward, so a clockwise sweep from the top is
     * `(sin θ, −cos θ)` — the sign on the vertical component is what keeps
     * "90°" at three o'clock rather than nine.
     */
    fun pointOnCircle(
        center: ChartOffset,
        radius: Float,
        angleDegrees: Float,
    ): ChartOffset {
        if (!radius.isFinite() || !center.isFinite) return center
        val radians = Math.toRadians(normalizeAngle(angleDegrees).toDouble())
        return ChartOffset(
            x = center.x + (radius * sin(radians)).toFloat(),
            y = center.y - (radius * cos(radians)).toFloat(),
        )
    }

    /**
     * The angle from [center] to [point], in the chart convention.
     *
     * The inverse of [pointOnCircle]. Returns `0` for the centre itself, which
     * is degenerate rather than an error — a tap exactly on the centre of a
     * donut belongs to no slice, and [hitTestSlices] rejects it by radius.
     */
    fun angleOf(center: ChartOffset, point: ChartOffset): Float {
        val dx = point.x - center.x
        val dy = center.y - point.y
        if (abs(dx) < 1e-6f && abs(dy) < 1e-6f) return 0f
        val radians = kotlin.math.atan2(dx.toDouble(), dy.toDouble())
        return normalizeAngle(Math.toDegrees(radians).toFloat())
    }

    /** The distance from [center] to [point]. */
    fun radiusOf(center: ChartOffset, point: ChartOffset): Float {
        val dx = point.x - center.x
        val dy = point.y - center.y
        val distance = sqrt(dx * dx + dy * dy)
        return if (distance.isFinite()) distance else 0f
    }

    /**
     * How far [angle] is past [start], measured in [direction].
     *
     * Always in `[0, 360)`, which is what makes "is this angle inside that
     * sweep" a single comparison rather than a case analysis over wrap-around.
     */
    fun angleFrom(start: Float, angle: Float, direction: PolarDirection): Float {
        val delta = when (direction) {
            PolarDirection.Clockwise -> angle - start
            PolarDirection.CounterClockwise -> start - angle
        }
        return normalizeAngle(delta)
    }

    /** True when [angle] lies within [sweep] degrees of [start], in [direction]. */
    fun isAngleWithin(
        angle: Float,
        start: Float,
        sweep: Float,
        direction: PolarDirection = PolarDirection.Clockwise,
    ): Boolean {
        if (sweep <= 0f) return false
        if (sweep >= FULL_CIRCLE) return true
        return angleFrom(start, angle, direction) < sweep
    }

    /**
     * The radius at which a slice's label or tooltip anchor sits.
     *
     * Midway between the two radii, which puts it in the middle of the ring for
     * a donut and halfway out for a pie. Not the geometric centroid of the
     * sector — that sits closer to the arc, and on a thin ring it lands outside
     * the ring entirely.
     */
    fun anchorRadius(innerRadius: Float, outerRadius: Float): Float =
        (innerRadius + outerRadius) / 2f

    /** The angle halfway through a slice, in [direction]. */
    fun midAngle(
        startAngle: Float,
        sweepAngle: Float,
        direction: PolarDirection = PolarDirection.Clockwise,
    ): Float = normalizeAngle(startAngle + direction.sign * sweepAngle / 2f)

    /**
     * The index of the slice under a point, or `-1`.
     *
     * ```text
     * pointer → distance from centre → inside the ring? → angle → slice
     * ```
     *
     * Radius first, because it rejects the donut hole and everything outside
     * the chart in one comparison — and because the hole is the case a
     * consumer is most likely to notice getting wrong.
     *
     * Slice gaps are respected: a slice's drawn sweep already excludes its gap,
     * so a tap that lands in the gap matches nothing. That is deliberate. A gap
     * wide enough to see is wide enough to aim at, and snapping it to a
     * neighbour would make the two slices' boundaries disagree with what is on
     * screen.
     */
    fun hitTestSlices(
        slices: List<PolarSlice>,
        center: ChartOffset,
        point: ChartOffset,
        innerRadius: Float,
        outerRadius: Float,
        direction: PolarDirection = PolarDirection.Clockwise,
    ): Int {
        val radius = radiusOf(center, point)
        if (radius < innerRadius || radius > outerRadius) return -1
        val angle = angleOf(center, point)
        return slices.indexOfFirst {
            it.sweepAngle > 0f && isAngleWithin(angle, it.startAngle, it.sweepAngle, direction)
        }
    }

    /**
     * The largest radius that fits inside [bounds], leaving [padding] around it.
     *
     * Polar charts are square; a wide plot area wastes its extra width rather
     * than drawing an ellipse, because an elliptical pie misreports every angle
     * as an area.
     */
    fun radiusWithin(bounds: ChartRect, padding: Float = 0f): Float {
        if (bounds.isEmpty) return 0f
        val available = minOf(bounds.width, bounds.height) / 2f - max(0f, padding)
        return max(0f, available)
    }
}

/**
 * One angular segment: where it starts, how far it sweeps, and what it came
 * from.
 *
 * Produced by [computePolarSlices] and consumed by the slice layer, hit
 * testing, labels and accessibility — so all four agree on the geometry by
 * construction rather than by four calculations that happen to match.
 *
 * @param sourceIndex the position in the caller's own list.
 * @param value the value as supplied.
 * @param fraction the value's share of the total, in `0..1`.
 * @param startAngle where the drawn arc begins, gap already applied.
 * @param sweepAngle how far it sweeps, gap already removed. Zero for a slice
 *   that has no share — it stays in the list so the legend and the palette keep
 *   their slots.
 */
data class PolarSlice(
    val sourceIndex: Int,
    val value: Double,
    val fraction: Double,
    val startAngle: Float,
    val sweepAngle: Float,
)

/**
 * What to do with a value a pie cannot represent.
 *
 * A part-to-whole chart has no meaning for a negative share, and `NaN` has none
 * anywhere. Silently taking the absolute value would draw a slice that claims a
 * positive share of a total it reduces; silently treating it as zero hides a
 * data problem the developer would want to know about. So the behaviour is
 * stated rather than guessed.
 */
enum class PolarValuePolicy {

    /**
     * Drop the value: it contributes nothing to the total and draws no slice.
     * The default.
     *
     * The entry keeps its place in the legend and its palette slot, so a chart
     * whose data is briefly wrong does not recolour itself and then recolour
     * back.
     */
    Ignore,

    /**
     * Throw. For a caller who would rather find out at the call site than see a
     * slice quietly missing.
     */
    Reject,
}

/**
 * Turns raw values into slices covering [totalSweep] degrees.
 *
 * Values are normalised, so they need not sum to anything in particular:
 * `40, 30, 20, 10` and `0.4, 0.3, 0.2, 0.1` produce identical charts.
 *
 * @param gapDegrees angular space left between adjacent slices. Taken out of
 *   each slice's own sweep rather than added between them, so the ring still
 *   closes and the slices still sum to [totalSweep].
 */
fun computePolarSlices(
    values: List<Double?>,
    startAngle: Float = 0f,
    totalSweep: Float = PolarGeometry.FULL_CIRCLE,
    gapDegrees: Float = 0f,
    direction: PolarDirection = PolarDirection.Clockwise,
    policy: PolarValuePolicy = PolarValuePolicy.Ignore,
): List<PolarSlice> {
    require(totalSweep > 0f && totalSweep <= PolarGeometry.FULL_CIRCLE) {
        "A polar sweep must be in (0, 360], was $totalSweep"
    }
    require(gapDegrees >= 0f) { "Slice gap cannot be negative, was $gapDegrees" }
    if (values.isEmpty()) return emptyList()

    val usable = values.map { value ->
        when {
            value == null -> null
            !value.isFinite() -> {
                if (policy == PolarValuePolicy.Reject) {
                    throw IllegalArgumentException(
                        "A polar chart cannot plot $value. Filter it out, or use " +
                            "PolarValuePolicy.Ignore to drop it.",
                    )
                }
                null
            }
            value < 0.0 -> {
                if (policy == PolarValuePolicy.Reject) {
                    throw IllegalArgumentException(
                        "A polar chart cannot plot the negative value $value: a share of a " +
                            "whole is never negative. Filter it out, or use " +
                            "PolarValuePolicy.Ignore to drop it.",
                    )
                }
                null
            }
            else -> value
        }
    }

    val total = usable.filterNotNull().sum()
    // An all-zero or all-invalid dataset yields zero-sweep slices rather than
    // a division by zero. The caller sees an empty chart, which is the truth.
    val slices = ArrayList<PolarSlice>(values.size)
    var cursor = startAngle
    usable.forEachIndexed { index, value ->
        val fraction = if (total <= ChartMath.EPSILON || value == null) 0.0 else value / total
        val fullSweep = (fraction * totalSweep).toFloat()
        // The gap is only affordable when the slice is wider than it. A 0.2%
        // slice with a 2° gap would otherwise get a negative sweep and vanish
        // in a way that looks like a rendering fault.
        val drawnSweep = if (fullSweep > gapDegrees) fullSweep - gapDegrees else fullSweep
        slices += PolarSlice(
            sourceIndex = index,
            value = value ?: 0.0,
            fraction = fraction,
            startAngle = PolarGeometry.normalizeAngle(cursor),
            sweepAngle = max(0f, drawnSweep),
        )
        // The cursor advances in the sweep direction, so a counter-clockwise
        // chart is the same arithmetic with one sign flipped rather than a
        // second construction.
        cursor += direction.sign * fullSweep
    }
    return slices
}
