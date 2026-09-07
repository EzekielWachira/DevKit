package io.devkit.chartkit.scale

import io.devkit.chartkit.geometry.ChartMath

/**
 * A continuous linear mapping from a [NumericDomain] onto a pixel range.
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
 * @param clamp when true, values outside the domain map to the range ends
 *   rather than beyond them. Off by default: a line that leaves the plot
 *   because the caller fixed a domain too narrow is showing the truth, and the
 *   plot area clips it. Bars turn it on, because a bar drawn past the axis
 *   reads as a rendering fault.
 */
class LinearScale(
    domain: NumericDomain,
    override val rangeStart: Float,
    override val rangeEnd: Float,
    val clamp: Boolean = false,
) : InvertibleChartScale<Double> {

    /** The interval actually mapped — never degenerate, never non-finite. */
    val domain: NumericDomain = domain.resolved()

    private val rangeSpan: Float = rangeEnd - rangeStart

    override fun scale(value: Double): Float {
        if (!value.isFinite()) return rangeStart
        val fraction = ChartMath.safeDiv(value - domain.min, domain.span)
        val bounded = if (clamp) ChartMath.clamp(fraction, 0.0, 1.0) else fraction
        val position = rangeStart + (bounded * rangeSpan).toFloat()
        return ChartMath.finiteOr(position, rangeStart)
    }

    /** The normalised position of [value] in `0..1`, before any pixel mapping. */
    fun fraction(value: Double): Double {
        if (!value.isFinite()) return 0.0
        val raw = ChartMath.safeDiv(value - domain.min, domain.span)
        return if (clamp) ChartMath.clamp(raw, 0.0, 1.0) else raw
    }

    override fun invert(position: Float): Double {
        if (!position.isFinite()) return domain.min
        val fraction = ChartMath.safeDiv(
            (position - rangeStart).toDouble(),
            rangeSpan.toDouble(),
        )
        val bounded = if (clamp) ChartMath.clamp(fraction, 0.0, 1.0) else fraction
        return ChartMath.finiteOr(domain.min + bounded * domain.span, domain.min)
    }

    /**
     * Round tick values across the domain, at roughly [count] of them.
     *
     * Delegates to [TickGenerator]; "roughly" is the contract, and the reason is
     * documented there.
     */
    fun ticks(count: Int = TickGenerator.DEFAULT_TICK_COUNT): List<Double> =
        TickGenerator.ticks(domain, count)

    override fun toString(): String =
        "LinearScale(domain=[${domain.min}, ${domain.max}], range=[$rangeStart, $rangeEnd])"
}
