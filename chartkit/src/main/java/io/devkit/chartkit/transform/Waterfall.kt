package io.devkit.chartkit.transform

/**
 * What one bar of a waterfall represents.
 *
 * The distinction is not cosmetic: a [Total] is measured from zero and an
 * [Increase] is measured from wherever the running total had reached, so the
 * kind decides the geometry as well as the colour.
 */
enum class WaterfallStepKind {

    /** A positive contribution, drawn from the running total upward. */
    Increase,

    /** A negative contribution, drawn from the running total downward. */
    Decrease,

    /**
     * A checkpoint: the running total so far, drawn from zero.
     *
     * The running total continues afterwards rather than restarting, which is
     * what makes a mid-chart subtotal a checkpoint rather than a new baseline.
     */
    Subtotal,

    /**
     * The final figure, drawn from zero.
     *
     * Distinguished from [Subtotal] so a theme can emphasise the number the
     * chart exists to show.
     */
    Total,
    ;

    /** True when the bar is measured from zero rather than from the running total. */
    val isAnchored: Boolean get() = this == Subtotal || this == Total
}

/**
 * One resolved bar of a waterfall.
 *
 * @param label the step's name.
 * @param delta the contribution, signed. Zero for an anchored step, whose
 *   height is its [runningTotal] rather than a change.
 * @param start the value the bar begins at.
 * @param end the value it ends at. For an anchored step, `start` is zero and
 *   `end` is the running total.
 * @param runningTotal the cumulative figure after this step.
 * @param sourceIndex the index in the caller's own list.
 */
class WaterfallStep(
    val label: String,
    val kind: WaterfallStepKind,
    val delta: Double,
    val start: Double,
    val end: Double,
    val runningTotal: Double,
    val sourceIndex: Int,
    val item: Any?,
) {
    /** The bar's own extent, always non-negative. */
    val magnitude: Double get() = kotlin.math.abs(end - start)
}

/**
 * Turns a list of contributions into positioned bars.
 *
 * ```text
 * Starting  ████████████                    100
 * Revenue              ████                 +40
 * Costs                  ██                 -20
 * Tax                      █                -10
 * Total     █████████████                   110
 * ```
 *
 * A pure transform: no Compose, no geometry, no pixels. The running total is
 * the one piece of arithmetic a waterfall gets wrong when each bar computes its
 * own position, so it is computed once, here, and tested on the JVM.
 *
 * ### Sign
 *
 * The step's *kind* decides the direction and the caller's value supplies the
 * magnitude, so `WaterfallStepKind.Decrease` with a value of `20` and one with
 * `-20` both fall by twenty. Requiring the caller to negate their own decreases
 * is the kind of convention that produces a chart that is wrong in exactly one
 * bar.
 */
object WaterfallTransform {

    /**
     * @param kind the step's role. A caller who only has signed numbers passes
     *   `{ if (it.amount < 0) Decrease else Increase }`, which is the common
     *   case and is why it is a lambda rather than a field.
     */
    fun <T> resolve(
        data: List<T>,
        label: (T) -> String,
        value: (T) -> Number?,
        kind: (T) -> WaterfallStepKind,
    ): List<WaterfallStep> {
        var running = 0.0
        return data.mapIndexed { index, item ->
            val stepKind = kind(item)
            val raw = value(item)?.toDouble()?.takeIf { it.isFinite() } ?: 0.0
            val magnitude = kotlin.math.abs(raw)

            when (stepKind) {
                WaterfallStepKind.Subtotal, WaterfallStepKind.Total -> {
                    // Anchored: the bar states the running total rather than a
                    // change, so it is drawn from zero and the total is
                    // unchanged by it.
                    WaterfallStep(
                        label = label(item),
                        kind = stepKind,
                        delta = 0.0,
                        start = 0.0,
                        end = running,
                        runningTotal = running,
                        sourceIndex = index,
                        item = item,
                    )
                }

                WaterfallStepKind.Increase, WaterfallStepKind.Decrease -> {
                    val delta = if (stepKind == WaterfallStepKind.Decrease) -magnitude else magnitude
                    val start = running
                    running += delta
                    WaterfallStep(
                        label = label(item),
                        kind = stepKind,
                        delta = delta,
                        start = start,
                        end = running,
                        runningTotal = running,
                        sourceIndex = index,
                        item = item,
                    )
                }
            }
        }
    }

    /** Infers the kind from the sign, for callers whose data is already signed. */
    fun signedKind(value: Number?): WaterfallStepKind {
        val raw = value?.toDouble() ?: 0.0
        return if (raw < 0.0) WaterfallStepKind.Decrease else WaterfallStepKind.Increase
    }

    /** The interval the bars occupy, including zero. */
    fun extentOf(steps: List<WaterfallStep>): ClosedFloatingPointRange<Double> {
        if (steps.isEmpty()) return 0.0..0.0
        var minimum = 0.0
        var maximum = 0.0
        steps.forEach { step ->
            minimum = minOf(minimum, step.start, step.end)
            maximum = maxOf(maximum, step.start, step.end)
        }
        return minimum..maximum
    }
}
