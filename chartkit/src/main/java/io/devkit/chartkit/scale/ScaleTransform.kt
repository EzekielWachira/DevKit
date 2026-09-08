package io.devkit.chartkit.scale

import io.devkit.chartkit.geometry.ChartMath
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign

/**
 * What a plain log scale does with a value it cannot represent.
 *
 * `log(0)` is negative infinity and `log(-1)` is not a real number, so zero and
 * negative values have no position on a logarithmic axis. There is no correct
 * silent answer — dropping the point hides data, and clamping it to the axis
 * floor draws it somewhere it is not — so the behaviour is stated.
 */
enum class LogValuePolicy {

    /**
     * Pin the value to the axis' low end. The default.
     *
     * Keeps the point on the chart and keeps the series connected, at the cost
     * of drawing it slightly below where it belongs — which for a value that
     * cannot be drawn at all is the least misleading of the options.
     */
    Clamp,

    /**
     * Treat the value as missing, so the point is not drawn and the line breaks.
     *
     * Honest, and the right choice when zeros in the data mean "no reading"
     * rather than "a reading of zero".
     */
    Skip,

    /**
     * Throw, naming the value.
     *
     * For a caller who would rather find out at the call site than discover a
     * point sitting on the axis. Consider [io.devkit.chartkit.scale.AxisScale.Symlog]
     * instead, which represents zero and negatives properly.
     */
    Reject,
}

/**
 * A monotonic reshaping of the value axis, applied before the linear mapping.
 *
 * ### Why a transform and not a scale type
 *
 * A logarithmic axis is a linear mapping of `log(v)`. Expressing it that way
 * rather than as a second `ChartScale` implementation means every part of the
 * engine that already works — the grid, the axis renderer, hit testing,
 * annotations, the crosshair, range selection, the viewport, every layer —
 * works on a log axis unchanged, because all of them go through
 * [LinearScale.scale] and [LinearScale.invert] and neither of them knows the
 * difference. A parallel `LogScale` class would have needed every one of those
 * to learn about it.
 *
 * ### Custom scales
 *
 * The interface is open. A caller with a genuinely different mapping — a
 * probability axis, a power scale, a perceptual lightness scale — implements
 * these four members and passes it as
 * [io.devkit.chartkit.scale.AxisScale.Custom]. The only requirement is that
 * [forward] be **strictly increasing** over the domain: a non-monotonic
 * transform makes two different values share a pixel, and every hit test,
 * inversion and tick placement downstream then reports one of them wrongly.
 */
interface ScaleTransform {

    /** The value's position in transformed space. `NaN` when unrepresentable. */
    fun forward(value: Double): Double

    /** The inverse of [forward]. */
    fun inverse(transformed: Double): Double

    /** Tick values across [domain], at roughly [count] of them. */
    fun ticks(domain: NumericDomain, count: Int): List<Double>

    /**
     * [domain] narrowed to what this transform can represent.
     *
     * A log axis given `[0, 1000]` cannot map its own lower bound, so it
     * reports a domain starting at a small positive number instead. Called once
     * when the axis is built, so nothing downstream ever sees an interval it
     * cannot divide by.
     */
    fun constrainDomain(domain: NumericDomain): NumericDomain = domain

    /** True when [value] has a position on this axis at all. */
    fun isRepresentable(value: Double): Boolean = forward(value).isFinite()

    /** The identity: an ordinary linear axis. */
    companion object {
        val Identity: ScaleTransform = IdentityTransform
    }
}

private object IdentityTransform : ScaleTransform {
    override fun forward(value: Double): Double = value
    override fun inverse(transformed: Double): Double = transformed
    override fun ticks(domain: NumericDomain, count: Int): List<Double> =
        TickGenerator.ticks(domain, count)

    override fun toString(): String = "ScaleTransform.Identity"
}

/**
 * A logarithmic axis.
 *
 * ```text
 * 1      10     100    1000
 * ├──────┼──────┼──────┤
 * ```
 *
 * The mapping every reader of a log chart expects: equal pixel distances are
 * equal *ratios*. What it cannot do is represent zero or a negative number, and
 * the whole of [LogValuePolicy] exists because that limitation has no silent
 * answer. A dataset that legitimately crosses zero wants
 * [io.devkit.chartkit.scale.AxisScale.Symlog].
 *
 * @param base any base above 1. Ten by default, because that is what an axis
 *   labelled `1 10 100` reads as; two is the other common choice, and `e` is
 *   available as [kotlin.math.E].
 */
class LogTransform(
    val base: Double = 10.0,
    val policy: LogValuePolicy = LogValuePolicy.Clamp,
) : ScaleTransform {

    init {
        require(base.isFinite() && base > 1.0) {
            "A logarithmic base must be finite and greater than 1, was $base"
        }
    }

    private val lnBase = ln(base)

    override fun forward(value: Double): Double {
        if (!value.isFinite()) return Double.NaN
        if (value <= 0.0) {
            return when (policy) {
                // The scale's own clamping handles the rest: an out-of-domain
                // transformed value maps to the range end when `clamp` is on,
                // and the axis' floor is the smallest thing it can represent.
                LogValuePolicy.Clamp -> Double.NEGATIVE_INFINITY
                LogValuePolicy.Skip -> Double.NaN
                LogValuePolicy.Reject -> throw IllegalArgumentException(
                    "A logarithmic axis cannot plot $value: the logarithm of zero or a " +
                        "negative number is not a real position. Filter the value out, use " +
                        "LogValuePolicy.Skip to draw a gap, or use a symmetric-log axis, " +
                        "which represents zero and negatives properly.",
                )
            }
        }
        return ln(value) / lnBase
    }

    override fun inverse(transformed: Double): Double {
        if (!transformed.isFinite()) return if (transformed < 0.0) 0.0 else Double.NaN
        return base.pow(transformed)
    }

    override fun isRepresentable(value: Double): Boolean = value.isFinite() && value > 0.0

    /**
     * The domain, with its lower bound lifted above zero.
     *
     * Lifted to [MIN_DECADES] decades below the upper bound rather than to a
     * fixed constant, so a chart of values between `0.001` and `0.01` is not
     * squeezed into the top of an axis that starts at `1`.
     */
    override fun constrainDomain(domain: NumericDomain): NumericDomain {
        val resolved = domain.resolved()
        if (resolved.min > 0.0) return resolved
        val upper = if (resolved.max > 0.0) resolved.max else 1.0
        val lower = upper / base.pow(MIN_DECADES)
        return NumericDomain(lower, upper).resolved()
    }

    /**
     * Powers of the base, subdivided when there are few enough decades for the
     * subdivisions to be readable.
     *
     * A four-decade axis is labelled `1 10 100 1000 10000` and a one-decade axis
     * `1 2 3 5 10` — dividing a wide log axis further produces labels so dense
     * they overlap, and leaving a narrow one undivided produces an axis with two
     * ticks on it.
     */
    override fun ticks(domain: NumericDomain, count: Int): List<Double> {
        val constrained = constrainDomain(domain)
        val lowExponent = floor(forward(constrained.min))
        val highExponent = ceil(forward(constrained.max))
        if (!lowExponent.isFinite() || !highExponent.isFinite()) return emptyList()

        val decades = (highExponent - lowExponent).toInt().coerceAtLeast(1)
        val subdivisions: List<Double> = when {
            decades > count -> listOf(1.0)
            decades * SUBDIVISIONS_FEW.size <= count * 2 && base >= 10.0 -> SUBDIVISIONS_FEW
            base >= 10.0 -> listOf(1.0, 3.0)
            else -> listOf(1.0)
        }

        // A decade stride, for an axis covering more decades than it has room
        // for labels: showing every third power of ten beats showing forty.
        val stride = ceil(decades.toDouble() / count.coerceAtLeast(2)).toInt().coerceAtLeast(1)

        val result = ArrayList<Double>(count * 2)
        var exponent = lowExponent
        while (exponent <= highExponent + ChartMath.EPSILON) {
            val decade = base.pow(exponent)
            subdivisions.forEach { multiple ->
                val value = decade * multiple
                if (value >= constrained.min - ChartMath.EPSILON &&
                    value <= constrained.max + ChartMath.EPSILON
                ) {
                    result += value
                }
            }
            exponent += stride
        }
        return result.distinct().sorted()
    }

    override fun toString(): String = "LogTransform(base=$base)"

    private companion object {
        /** How far below the top a log axis reaches when the data includes zero. */
        const val MIN_DECADES = 3.0

        /** `1 2 3 5` reads as a subdivided decade; more of them collide. */
        val SUBDIVISIONS_FEW = listOf(1.0, 2.0, 3.0, 5.0)
    }
}

/**
 * A symmetric-log axis: logarithmic in both tails, linear across zero.
 *
 * ```text
 * -1000  -100   -10   0   10    100   1000
 *   ├──────┼─────┼────┼───┼──────┼──────┤
 *              linear ↑↑↑
 * ```
 *
 * The answer to the one thing a plain log axis cannot do. Profit and loss,
 * temperature anomalies, net flows and score deltas all cross zero and all
 * span orders of magnitude, and neither a linear axis — where everything near
 * zero collapses into one pixel — nor a log axis — which cannot draw them at
 * all — shows them.
 *
 * @param linearThreshold the magnitude below which the axis is linear. Inside
 *   `[-threshold, +threshold]` the mapping is a straight line through zero;
 *   outside it, logarithmic. The choice matters: too small and the linear
 *   region is invisible, too large and the log regions are.
 */
class SymlogTransform(
    val linearThreshold: Double = 1.0,
    val base: Double = 10.0,
) : ScaleTransform {

    init {
        require(linearThreshold.isFinite() && linearThreshold > 0.0) {
            "The linear threshold must be finite and positive, was $linearThreshold"
        }
        require(base.isFinite() && base > 1.0) {
            "A logarithmic base must be finite and greater than 1, was $base"
        }
    }

    private val lnBase = ln(base)

    /**
     * Continuous and smooth at the threshold by construction.
     *
     * Inside the linear region the value is scaled so that `±threshold` lands
     * exactly at `±1`; outside it, `1 + log_base(|v| / threshold)` continues
     * from there. Both halves therefore agree at the join, which is what stops
     * a visible kink appearing at `±threshold`.
     */
    override fun forward(value: Double): Double {
        if (!value.isFinite()) return Double.NaN
        val magnitude = abs(value)
        if (magnitude <= linearThreshold) return value / linearThreshold
        return sign(value) * (1.0 + ln(magnitude / linearThreshold) / lnBase)
    }

    override fun inverse(transformed: Double): Double {
        if (!transformed.isFinite()) return Double.NaN
        val magnitude = abs(transformed)
        if (magnitude <= 1.0) return transformed * linearThreshold
        return sign(transformed) * linearThreshold * base.pow(magnitude - 1.0)
    }

    override fun isRepresentable(value: Double): Boolean = value.isFinite()

    /**
     * Zero, the threshold, and powers of the base out to each end.
     *
     * Zero is always present: it is the axis' point of symmetry and the value a
     * reader is comparing everything against.
     */
    override fun ticks(domain: NumericDomain, count: Int): List<Double> {
        val resolved = domain.resolved()
        val result = sortedSetOf<Double>()
        if (0.0 in resolved) result += 0.0

        val perSide = (count / 2).coerceAtLeast(2)
        listOf(1.0, -1.0).forEach { direction ->
            val limit = if (direction > 0) resolved.max else resolved.min
            if (direction > 0 && limit <= 0.0) return@forEach
            if (direction < 0 && limit >= 0.0) return@forEach
            val magnitude = abs(limit)
            if (magnitude < linearThreshold) {
                // Entirely inside the linear region on this side, so the
                // ordinary nice-number generator is exactly right.
                TickGenerator.ticks(NumericDomain(0.0, magnitude), perSide).forEach {
                    val value = direction * it
                    if (value in resolved) result += value
                }
                return@forEach
            }
            result += direction * linearThreshold
            val decades = ln(magnitude / linearThreshold) / lnBase
            val stride = ceil(decades / perSide).coerceAtLeast(1.0)
            var exponent = stride
            while (exponent <= decades + ChartMath.EPSILON) {
                val value = direction * linearThreshold * base.pow(exponent)
                if (value in resolved) result += value
                exponent += stride
            }
        }
        return result.toList()
    }

    override fun toString(): String =
        "SymlogTransform(linearThreshold=$linearThreshold, base=$base)"
}

/**
 * How an axis maps values to positions.
 *
 * ```kotlin
 * LineChart(
 *     data = latencies, x = { it.at }, y = { it.micros },
 *     yAxis = ChartAxis(title = "Latency (µs)", scale = AxisScale.Log()),
 * )
 * ```
 *
 * Stated on the **axis** rather than on the chart, because that is what it is a
 * property of: a chart with a log value axis and a linear domain axis is
 * ordinary, and a parameter on the chart could not express it.
 */
sealed interface AxisScale {

    /** The transform this kind maps through. */
    fun transform(): ScaleTransform

    /** Equal distances are equal differences. The default. */
    data object Linear : AxisScale {
        override fun transform(): ScaleTransform = ScaleTransform.Identity
    }

    /** Equal distances are equal ratios. Cannot represent zero or negatives. */
    data class Log(
        val base: Double = 10.0,
        val policy: LogValuePolicy = LogValuePolicy.Clamp,
    ) : AxisScale {
        override fun transform(): ScaleTransform = LogTransform(base, policy)
    }

    /** Logarithmic in the tails, linear across zero. */
    data class Symlog(
        val linearThreshold: Double = 1.0,
        val base: Double = 10.0,
    ) : AxisScale {
        override fun transform(): ScaleTransform = SymlogTransform(linearThreshold, base)
    }

    /**
     * A caller's own monotonic transform.
     *
     * The escape hatch that means a mapping ChartKit has not thought of does
     * not require forking it.
     */
    data class Custom(val scaleTransform: ScaleTransform) : AxisScale {
        override fun transform(): ScaleTransform = scaleTransform
    }
}

/** A logarithmic position scale over [domain]. */
@Suppress("FunctionNaming")
fun LogScale(
    domain: NumericDomain,
    rangeStart: Float,
    rangeEnd: Float,
    base: Double = 10.0,
    policy: LogValuePolicy = LogValuePolicy.Clamp,
    clamp: Boolean = false,
): LinearScale = LinearScale(
    domain = domain,
    rangeStart = rangeStart,
    rangeEnd = rangeEnd,
    clamp = clamp,
    transform = LogTransform(base, policy),
)

/** A symmetric-log position scale over [domain]. */
@Suppress("FunctionNaming")
fun SymlogScale(
    domain: NumericDomain,
    rangeStart: Float,
    rangeEnd: Float,
    linearThreshold: Double = 1.0,
    base: Double = 10.0,
    clamp: Boolean = false,
): LinearScale = LinearScale(
    domain = domain,
    rangeStart = rangeStart,
    rangeEnd = rangeEnd,
    clamp = clamp,
    transform = SymlogTransform(linearThreshold, base),
)
