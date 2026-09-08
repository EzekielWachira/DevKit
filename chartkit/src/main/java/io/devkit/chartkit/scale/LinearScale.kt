package io.devkit.chartkit.scale

import io.devkit.chartkit.geometry.ChartMath

/**
 * A continuous mapping from a [NumericDomain] onto a pixel range.
 *
 * ```
 * domain 0 .. 100   ->   range 0px .. 800px
 * scale(25)         ->   200px
 * invert(200f)      ->   25.0
 * ```
 *
 * The domain is [NumericDomain.resolved] on construction, so a degenerate
 * interval — a constant series, a single point — is widened before anything
 * divides by its span. Combined with the finiteness guards in [scale], the type
 * has no input for which it returns a non-finite pixel.
 *
 * ### Linear, and everything else
 *
 * The mapping is linear **in transformed space**. With the default
 * [ScaleTransform.Identity] that is the ordinary linear axis the name says. A
 * logarithmic or symmetric-log axis is the same class with a different
 * [transform], which is why a log chart's grid, ticks, hit testing,
 * annotations, crosshair, range selection and every layer work unchanged:
 * all of them go through [scale] and [invert], and neither knows the
 * difference. See [ScaleTransform] for why that beats a parallel scale type.
 *
 * @param clamp when true, values outside the domain map to the range ends
 *   rather than beyond them. Off by default: a line that leaves the plot
 *   because the caller fixed a domain too narrow is showing the truth, and the
 *   plot area clips it. Bars turn it on, because a bar drawn past the axis
 *   reads as a rendering fault.
 * @param transform an optional reshaping applied before the linear mapping.
 */
class LinearScale(
    domain: NumericDomain,
    override val rangeStart: Float,
    override val rangeEnd: Float,
    val clamp: Boolean = false,
    val transform: ScaleTransform = ScaleTransform.Identity,
) : InvertibleChartScale<Double> {

    /** The interval actually mapped — never degenerate, never non-finite. */
    val domain: NumericDomain = transform.constrainDomain(domain.resolved()).resolved()

    private val rangeSpan: Float = rangeEnd - rangeStart

    /** The domain's ends in transformed space, computed once. */
    private val transformedMin: Double = transform.forward(this.domain.min)
    private val transformedSpan: Double = transform.forward(this.domain.max) - transformedMin

    override fun scale(value: Double): Float {
        if (!value.isFinite()) return rangeStart
        val fraction = fraction(value)
        // Only a transform that declared the value unrepresentable produces a
        // `NaN` here, and the layers read a non-finite position as "no point",
        // which is exactly what LogValuePolicy.Skip asks for.
        if (fraction.isNaN()) return Float.NaN
        val position = rangeStart + (fraction * rangeSpan).toFloat()
        return ChartMath.finiteOr(position, rangeStart)
    }

    /**
     * The normalised position of [value] in `0..1`, before any pixel mapping.
     *
     * `NaN` when the transform cannot represent the value.
     */
    fun fraction(value: Double): Double {
        if (!value.isFinite()) return 0.0
        val transformed = transform.forward(value)
        if (transformed.isNaN()) return Double.NaN
        val raw = if (transformed.isInfinite()) {
            // A value the transform pushed to an infinity — `log(0)`, say —
            // has no finite position, so it is pinned to the end of the axis it
            // ran off. Clamped whether or not the scale clamps: the alternative
            // is a non-finite pixel reaching a draw call.
            if (transformed < 0.0) 0.0 else 1.0
        } else {
            ChartMath.safeDiv(transformed - transformedMin, transformedSpan)
        }
        return if (clamp) ChartMath.clamp(raw, 0.0, 1.0) else raw
    }

    override fun invert(position: Float): Double {
        if (!position.isFinite()) return domain.min
        val fraction = ChartMath.safeDiv(
            (position - rangeStart).toDouble(),
            rangeSpan.toDouble(),
        )
        val bounded = if (clamp) ChartMath.clamp(fraction, 0.0, 1.0) else fraction
        val value = transform.inverse(transformedMin + bounded * transformedSpan)
        return ChartMath.finiteOr(value, domain.min)
    }

    /**
     * Round tick values across the domain, at roughly [count] of them.
     *
     * Delegated to the [transform], so a log axis is labelled in powers and a
     * linear one in round numbers, and the axis renderer does not have to know
     * which it is drawing.
     */
    fun ticks(count: Int = TickGenerator.DEFAULT_TICK_COUNT): List<Double> =
        transform.ticks(domain, count)

    override fun toString(): String =
        "LinearScale(domain=[${domain.min}, ${domain.max}], range=[$rangeStart, $rangeEnd]" +
            (if (transform === ScaleTransform.Identity) "" else ", transform=$transform") + ")"
}
