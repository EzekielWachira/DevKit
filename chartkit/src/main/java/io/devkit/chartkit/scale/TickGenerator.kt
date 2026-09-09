package io.devkit.chartkit.scale

import io.devkit.chartkit.geometry.ChartMath
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Chooses round numbers to label an axis with.
 *
 * The alternative — dividing the domain into `n` equal parts — produces axes
 * labelled `17.3, 34.6, 51.9`, which is technically correct and unreadable. The
 * classic "nice numbers" approach used here instead snaps the step to 1, 2, 5
 * or 10 times a power of ten, so a domain of `0..97` labels as `0 20 40 60 80
 * 100` rather than `0 19.4 38.8 …`.
 *
 * ### Why the count is approximate
 *
 * A round step and an exact tick count are in conflict: `0..100` with exactly
 * seven ticks needs a step of `16.67`. ChartKit resolves it in favour of the
 * round step every time, because the number of labels is a layout preference
 * and their legibility is not. Callers asking for five ticks should expect four
 * to six.
 */
object TickGenerator {

    /** Enough to read a trend from, few enough to fit a phone in portrait. */
    const val DEFAULT_TICK_COUNT: Int = 5

    /** More than this and labels collide on any realistic axis. */
    private const val MAX_TICK_COUNT: Int = 40

    /**
     * Round tick values covering [domain], at approximately [count] of them.
     *
     * The first tick is at or below `domain.min` and the last at or above
     * `domain.max`, then both are trimmed to the domain — so ticks never sit
     * outside the plot area, and an axis whose domain was fixed by the caller
     * does not grow to meet a rounder number.
     */
    fun ticks(domain: NumericDomain, count: Int = DEFAULT_TICK_COUNT): List<Double> {
        val resolved = domain.resolved()
        val requested = count.coerceIn(2, MAX_TICK_COUNT)

        val step = niceStep(resolved.span, requested)
        if (step < ChartMath.EPSILON || !step.isFinite()) {
            return listOf(resolved.min, resolved.max)
        }

        val first = floor(resolved.min / step) * step
        val last = ceil(resolved.max / step) * step

        val result = ArrayList<Double>(requested + 2)
        var index = 0
        // Accumulating by multiplication rather than by repeated addition:
        // adding 0.1 forty times lands on 4.000000000000002, which formats as
        // "4.000000000000002" on an axis label.
        while (true) {
            val value = first + step * index
            if (value > last + step * 0.5) break
            // Snapping to zero: `-1e-17` is arithmetically a different number
            // from `0` and prints as `-0` on a baseline label.
            val snapped = if (abs(value) < step * 1e-9) 0.0 else value
            if (snapped >= resolved.min - step * 1e-9 && snapped <= resolved.max + step * 1e-9) {
                result.add(ChartMath.clamp(snapped, resolved.min, resolved.max))
            }
            index++
            if (index > MAX_TICK_COUNT * 4) break
        }

        return when {
            result.isEmpty() -> listOf(resolved.min, resolved.max)
            result.size == 1 -> listOf(resolved.min, resolved.max)
            else -> result.distinct()
        }
    }

    /**
     * A domain extended outward to the nearest round tick values.
     *
     * Used when the caller wants the axis itself rounded rather than only its
     * labels — `0..97` becomes `0..100`. Not the default: it silently changes
     * the visible range, which is fine for an axis and wrong for a fixed domain
     * the caller chose deliberately.
     */
    fun niceDomain(domain: NumericDomain, count: Int = DEFAULT_TICK_COUNT): NumericDomain {
        val resolved = domain.resolved()
        val step = niceStep(resolved.span, count.coerceIn(2, MAX_TICK_COUNT))
        if (step < ChartMath.EPSILON || !step.isFinite()) return resolved
        return NumericDomain(
            min = floor(resolved.min / step) * step,
            max = ceil(resolved.max / step) * step,
        )
    }

    /**
     * The number of decimal places a tick list needs to be read unambiguously.
     *
     * Derived from the step rather than from the values, so an axis labels as
     * `0.0 0.5 1.0` and not `0 0.5 1`. Capped at six: beyond that the label is
     * longer than the plot is wide.
     */
    fun suggestedDecimals(ticks: List<Double>): Int {
        if (ticks.size < 2) return if (ticks.singleOrNull()?.let { it == floor(it) } != false) 0 else 2
        var smallestStep = Double.POSITIVE_INFINITY
        for (index in 1 until ticks.size) {
            val step = abs(ticks[index] - ticks[index - 1])
            if (step > ChartMath.EPSILON && step < smallestStep) smallestStep = step
        }
        if (!smallestStep.isFinite()) return 0
        val magnitude = floor(log10(smallestStep)).toInt()
        return (-magnitude).coerceIn(0, 6)
    }

    /**
     * The nice step [ticks] would use for this span and count.
     *
     * Published for multi-axis tick alignment, which needs to *choose* a step
     * rather than accept the one a single domain implies: three axes only share
     * screen rows when each covers its own data in the same number of nice
     * intervals. See [io.devkit.chartkit.axis.AxisTickAlignment].
     */
    internal fun stepFor(span: Double, count: Int): Double = niceStep(span, count)

    /**
     * The next step up the 1-2-5-10 ladder.
     *
     * Alignment walks it when a step covers the data in fewer intervals than
     * the shared count needs. Walking a ladder rather than scaling by a
     * constant is what keeps the enlarged axis labelled in round numbers — the
     * whole point of nice ticks, and the thing an aligned axis most easily
     * loses.
     */
    internal fun nextStep(step: Double): Double {
        if (!step.isFinite() || step < ChartMath.EPSILON) return step
        val magnitude = 10.0.pow(floor(log10(step)))
        if (!magnitude.isFinite() || magnitude < ChartMath.EPSILON) return step
        val normalized = step / magnitude
        return when {
            normalized < 1.5 -> 2.0 * magnitude
            normalized < 3.5 -> 5.0 * magnitude
            normalized < 7.5 -> 10.0 * magnitude
            else -> 20.0 * magnitude
        }
    }

    /**
     * A step of 1, 2, 5 or 10 times a power of ten, closest to `span / count`.
     */
    private fun niceStep(span: Double, count: Int): Double {
        if (!span.isFinite() || span < ChartMath.EPSILON) return 0.0
        val rough = span / count
        val magnitude = 10.0.pow(floor(log10(rough)))
        if (!magnitude.isFinite() || magnitude < ChartMath.EPSILON) return 0.0
        val normalized = rough / magnitude
        val multiplier = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        return multiplier * magnitude
    }
}
