package io.devkit.chartkit.gauge

import io.devkit.chartkit.geometry.ChartMath
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.scale.NumericDomain

/**
 * What a gauge does with a reading outside its range.
 *
 * A dial has a physical end. A speedometer reading 250 on a gauge that stops at
 * 200 has to draw the needle *somewhere*, and every choice is a statement:
 * pinning it to the end says "at least the maximum", letting it swing past says
 * "off the scale", and refusing says "this is not a reading I can show".
 */
enum class GaugeOverflow {

    /**
     * The needle stops at the end of the arc. The default.
     *
     * What a real instrument does, and the only option that cannot draw a
     * needle somewhere the reader will misread — a needle past `max` on a
     * full-circle gauge points at a *smaller* number.
     *
     * The **announcement and the tooltip still report the real value**; see
     * [GaugeScale.fractionOf] for why the two differ on purpose.
     */
    Clamp,

    /**
     * The needle leaves the arc, at the angle the value implies.
     *
     * For a dial whose overrun is the point — an engine tachometer's redline
     * runs past the last label — and only safe on a sweep with room past its
     * end.
     */
    AllowOverflow,

    /** An out-of-range value is a programming error. Throws [GaugeException]. */
    Reject,
}

/** A gauge that cannot be built, or a reading it will not show. */
class GaugeException(message: String) : IllegalArgumentException(message)

/**
 * Maps a number onto an angle, and back.
 *
 * ### The whole of a gauge's mathematics
 *
 * ```text
 * value  →  normalise against [min, max]  →  0..1  →  startAngle..endAngle
 * ```
 *
 * Everything else a dial draws — where a tick goes, how wide a band's arc is,
 * which value a finger landed on, where the needle points — is one of those two
 * directions. Keeping them here, in plain Kotlin with no Compose and no
 * `android.graphics`, is what makes them testable on the JVM and what keeps
 * bands, ticks, needles and hit testing from each deriving their own angle and
 * quietly disagreeing at the ends of the arc.
 *
 * ### Angles
 *
 * ChartKit's convention throughout: **zero at twelve o'clock, increasing
 * clockwise**. A semicircular speedometer opening upward runs from `-90°`
 * (nine o'clock) to `90°` (three o'clock), which is what
 * `GaugeScale(min, max, startAngle = -90f, endAngle = 90f)` says.
 *
 * @param direction which way values advance around the dial. Counter-clockwise
 *   is one sign flip, not a second set of geometry.
 */
class GaugeScale(
    val min: Double,
    val max: Double,
    val startAngle: Float,
    val sweepAngle: Float,
    val direction: PolarDirection = PolarDirection.Clockwise,
    val overflow: GaugeOverflow = GaugeOverflow.Clamp,
) {

    init {
        if (!min.isFinite() || !max.isFinite()) {
            throw GaugeException(
                "A gauge needs finite bounds, was [$min, $max]. A NaN or infinite bound has no " +
                    "angle, and every tick, band and needle derived from it would be NaN too.",
            )
        }
        if (max <= min) {
            throw GaugeException(
                "A gauge needs max > min, was [$min, $max]. A range of zero width cannot be " +
                    "divided by, and one that runs backwards would draw every value at the " +
                    "wrong end — reverse the sweep with PolarDirection.CounterClockwise instead.",
            )
        }
        if (!startAngle.isFinite() || !sweepAngle.isFinite()) {
            throw GaugeException("A gauge needs finite angles, was start=$startAngle sweep=$sweepAngle")
        }
        if (sweepAngle <= 0f || sweepAngle > PolarGeometry.FULL_CIRCLE) {
            throw GaugeException(
                "A gauge sweep must be in (0, 360], was $sweepAngle. Zero has no arc to draw on " +
                    "and more than a full turn maps two values onto one angle.",
            )
        }
    }

    /** The interval this gauge measures. */
    val domain: NumericDomain get() = NumericDomain(min, max)

    /** The span of the range. Never zero — see the constructor. */
    val span: Double get() = max - min

    /** The angle the maximum sits at. */
    val endAngle: Float get() = PolarGeometry.normalizeAngle(startAngle + sweepAngle * direction.sign)

    /** True when [value] is inside the gauge's range. */
    operator fun contains(value: Double): Boolean = value.isFinite() && value in min..max

    /**
     * Where [value] sits along the arc, in `0..1`.
     *
     * Under [GaugeOverflow.Clamp] this is bounded — which is what stops a
     * needle leaving the dial. It is deliberately **not** what the tooltip or
     * the screen reader report: a gauge that renamed 250 km/h as 200 would be
     * hiding the reading most worth seeing. The drawn position is clamped; the
     * announced number never is.
     */
    fun fractionOf(value: Double): Double {
        if (!value.isFinite()) return 0.0
        val raw = (value - min) / span
        return when (overflow) {
            GaugeOverflow.Clamp -> raw.coerceIn(0.0, 1.0)
            GaugeOverflow.AllowOverflow -> raw
            GaugeOverflow.Reject -> {
                if (raw < 0.0 || raw > 1.0) {
                    throw GaugeException(
                        "Value $value is outside the gauge's range [$min, $max] and the gauge " +
                            "is set to GaugeOverflow.Reject.",
                    )
                }
                raw
            }
        }
    }

    /** The angle [value] points at, in ChartKit's convention. */
    fun angleOf(value: Double): Float = angleAtFraction(fractionOf(value))

    /** The angle at [fraction] of the way along the arc. */
    fun angleAtFraction(fraction: Double): Float =
        startAngle + (sweepAngle * fraction * direction.sign).toFloat()

    /**
     * The value at [angle], with no regard for whether the angle is on the arc.
     *
     * For a caller who has already established that it is — a drag that was
     * clamped to the sweep, say. [valueAtOrNull] is the one to reach for when
     * the angle came from a finger.
     */
    fun valueAt(angle: Float): Double {
        val travelled = PolarGeometry.angleFrom(startAngle, angle, direction)
        return min + span * (travelled / sweepAngle).toDouble()
    }

    /**
     * The value at [angle], or `null` when the angle is off the arc.
     *
     * A tap below a semicircular gauge is not a reading. Returning `null`
     * rather than the nearest end is what stops a finger just outside the arc
     * setting the dial to its maximum — the failure mode of every gauge that
     * wraps `atan2` straight into a value.
     *
     * @param tolerance extra degrees accepted past each end, so a finger a
     *   whisker outside the arc still reads as the end value rather than as
     *   nothing.
     */
    fun valueAtOrNull(angle: Float, tolerance: Float = 0f): Double? {
        if (!angle.isFinite()) return null
        val travelled = PolarGeometry.angleFrom(startAngle, angle, direction)
        val slack = tolerance.coerceAtLeast(0f)
        // Past the end but within tolerance, or before the start — which shows
        // up as an angle just short of a full turn.
        return when {
            travelled <= sweepAngle -> min + span * (travelled / sweepAngle).toDouble()
            travelled <= sweepAngle + slack -> max
            travelled >= PolarGeometry.FULL_CIRCLE - slack -> min
            else -> null
        }
    }

    /** [value] snapped to a multiple of [step] from [min], and into range. */
    fun snap(value: Double, step: Double): Double {
        if (!step.isFinite() || step <= 0.0) return value.coerceIn(min, max)
        val steps = Math.round((value - min) / step).toDouble()
        return (min + steps * step).coerceIn(min, max)
    }

    /** The same scale over a different value range. */
    fun withRange(min: Double, max: Double): GaugeScale =
        GaugeScale(min, max, startAngle, sweepAngle, direction, overflow)

    override fun toString(): String =
        "GaugeScale([$min, $max], start=$startAngle, sweep=$sweepAngle, $direction, $overflow)"

    companion object {

        /**
         * A scale between two angles rather than a start and a sweep.
         *
         * The form the reference speedometer is stated in — `-90°` to `90°` —
         * and the one most callers reach for. `end == start` is read as a full
         * turn, because a zero-width gauge is never what anybody meant.
         */
        fun between(
            min: Double,
            max: Double,
            startAngle: Float,
            endAngle: Float,
            direction: PolarDirection = PolarDirection.Clockwise,
            overflow: GaugeOverflow = GaugeOverflow.Clamp,
        ): GaugeScale {
            val travelled = PolarGeometry.angleFrom(startAngle, endAngle, direction)
            val sweep = if (travelled <= ChartMath.EPSILON) PolarGeometry.FULL_CIRCLE else travelled
            return GaugeScale(min, max, startAngle, sweep, direction, overflow)
        }
    }
}
