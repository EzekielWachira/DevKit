package io.devkit.chartkit.axis

import io.devkit.chartkit.geometry.ChartMath
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.TickGenerator
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Whether a chart's value axes generate their ticks together or separately.
 *
 * ### What is actually being aligned
 *
 * Not the values — three axes measuring millimetres, degrees and hectopascals
 * have no values in common and forcing some would be nonsense. What aligns is
 * the *screen rows*: every axis is given the same number of intervals over its
 * own domain, so the fifth gridline is the fifth tick of all three axes at
 * once.
 *
 * ```text
 * Rainfall   Temperature   Pressure
 *      250            40       1040   ← same row
 *      200            30       1030
 *      150            20       1020
 *      100            10       1010
 *       50             0       1000
 *        0           -10        990   ← same row
 * ```
 *
 * That is the difference between a plot whose one set of gridlines is readable
 * against every axis, and a plot where a gridline is meaningful for the primary
 * axis and arbitrary for the other two.
 */
enum class AxisTickAlignment {

    /**
     * Every axis picks its own round numbers. The default.
     *
     * The right choice when the axes are read separately, and the honest one:
     * an aligned axis has been widened past its data to make the rows line up,
     * and that trade is worth stating rather than making silently everywhere.
     */
    Independent,

    /**
     * Axes share a tick count, so corresponding ticks land on the same row.
     *
     * Each axis still gets round numbers — the alignment chooses the *step*
     * from the nice-number ladder and then extends the domain outward, rather
     * than dividing the existing domain into equal parts. An axis that cannot
     * be aligned without severe distortion is left independent and reported;
     * see [AxisAlignment].
     */
    Aligned,
}

/** Something the axis layer could not do as asked, reported rather than hidden. */
data class AxisDiagnostic(
    val axisId: ChartAxisId?,
    val message: String,
)

/** One axis' aligned interval and the ticks on it. */
internal data class AlignedAxis(
    val domain: NumericDomain,
    val ticks: List<Double>,
)

/** What one axis brings to an alignment. */
internal data class AlignmentRequest(
    val id: ChartAxisId,
    /** The interval the axis would use on its own, after its domain policy. */
    val domain: NumericDomain,
    val tickCount: Int,
    val alignZero: Boolean,
)

internal data class AlignmentResult(
    val axes: Map<ChartAxisId, AlignedAxis>,
    val diagnostics: List<AxisDiagnostic>,
)

/**
 * Gives a set of axes a shared tick count, and optionally a shared zero row.
 *
 * ### Why the domain grows rather than the step shrinking
 *
 * Two ways to make an axis have exactly *n* intervals: divide its domain into
 * *n* parts, or pick a round step and extend the domain to a multiple of it.
 * The first gives perfect rows and labels like `17.3`; the second gives round
 * labels and a little empty space at the top of the plot. ChartKit takes the
 * second every time, for the same reason [TickGenerator] exists at all — an
 * unreadable axis is worse than a slightly loose one.
 *
 * ### Why alignment can decline
 *
 * Zero alignment in particular can demand an absurd domain: an axis over
 * `[999, 1001]` asked to put its zero on the same row as an axis over `[-5, 5]`
 * would have to span `[-1000, 1000]`, flattening its own data into a single
 * line. Past [MAX_SPAN_GROWTH] the axis keeps its own interval and the caller
 * is told, which is the only outcome that is neither a lie nor a crash.
 */
internal object AxisAlignment {

    /** How much an axis may be widened before alignment is declined. */
    const val MAX_SPAN_GROWTH: Double = 4.0

    /** Beyond this many intervals every axis is unreadably dense. */
    private const val MAX_INTERVALS: Int = 12

    private const val MIN_INTERVALS: Int = 2

    fun align(requests: List<AlignmentRequest>): AlignmentResult {
        if (requests.size < 2) return AlignmentResult(emptyMap(), emptyList())

        val diagnostics = mutableListOf<AxisDiagnostic>()
        val usable = requests.filter { it.domain.span.isFinite() && !it.domain.resolved().isDegenerate }
        if (usable.size < 2) return AlignmentResult(emptyMap(), diagnostics)

        // The shared interval count starts from what the axes asked for. Ticks
        // are approximate by contract, so taking the maximum keeps every axis at
        // least as dense as it wanted rather than thinning the busiest one.
        val target = usable.maxOf { it.tickCount - 1 }.coerceIn(MIN_INTERVALS, MAX_INTERVALS)

        val zeroGroup = usable.filter { it.alignZero }
        val plan = if (zeroGroup.size >= 2) {
            planWithZero(zeroGroup, usable, target, diagnostics)
        } else {
            usable.associate { it.id to fit(it.domain, target) }
        }

        val accepted = LinkedHashMap<ChartAxisId, AlignedAxis>(plan.size)
        plan.forEach { (id, aligned) ->
            val request = usable.first { it.id == id }
            val natural = request.domain.resolved().span
            if (natural > ChartMath.EPSILON && aligned.domain.span > natural * MAX_SPAN_GROWTH) {
                diagnostics += AxisDiagnostic(
                    axisId = id,
                    message = "Axis \"${id.value}\" was left unaligned: sharing tick rows would " +
                        "have widened it from ${format(natural)} to ${format(aligned.domain.span)}, " +
                        "flattening its own data.",
                )
            } else {
                accepted[id] = aligned
            }
        }
        // One aligned axis is not an alignment; it is just a widened axis.
        if (accepted.size < 2) return AlignmentResult(emptyMap(), diagnostics)
        return AlignmentResult(accepted, diagnostics)
    }

    /**
     * Fits [domain] into exactly [intervals] round steps.
     *
     * Walks the nice-number ladder until one step covers the data, so the
     * result is both the requested number of intervals and labelled in round
     * numbers.
     */
    fun fit(domain: NumericDomain, intervals: Int): AlignedAxis {
        val resolved = domain.resolved()
        val count = intervals.coerceIn(MIN_INTERVALS, MAX_INTERVALS)
        var step = TickGenerator.stepFor(resolved.span, count)
        if (step < ChartMath.EPSILON || !step.isFinite()) {
            return AlignedAxis(resolved, listOf(resolved.min, resolved.max))
        }
        var guard = 0
        while (guard++ < LADDER_LIMIT) {
            val low = floor(resolved.min / step + TOLERANCE) * step
            val high = low + step * count
            if (high >= resolved.max - step * TOLERANCE) {
                return AlignedAxis(NumericDomain(low, high), ticksOf(low, step, count))
            }
            step = TickGenerator.nextStep(step)
        }
        return AlignedAxis(resolved, TickGenerator.ticks(resolved, count + 1))
    }

    /**
     * Plans an alignment where the zero-aligned axes also share a zero row.
     *
     * Zero lands on the same row when every axis in the group puts the same
     * *number of intervals* below it. So the group agrees on how many intervals
     * sit below zero and how many above, and each axis multiplies those counts
     * by its own step. The interval count that falls out is then imposed on the
     * axes that only asked for row alignment, so all of them still share rows.
     */
    private fun planWithZero(
        zeroGroup: List<AlignmentRequest>,
        all: List<AlignmentRequest>,
        target: Int,
        diagnostics: MutableList<AxisDiagnostic>,
    ): Map<ChartAxisId, AlignedAxis> {
        val steps = zeroGroup.associate { request ->
            val resolved = request.domain.resolved()
            // The span the axis must cover once zero is inside it — an axis over
            // [980, 1040] asked to include zero is really covering [0, 1040].
            val withZero = resolved.expandToIncludeZero()
            request.id to TickGenerator.stepFor(withZero.span, target).takeIf {
                it > ChartMath.EPSILON && it.isFinite()
            }
        }
        if (steps.values.any { it == null }) {
            return all.associate { it.id to fit(it.domain, target) }
        }

        var below = 0
        var above = 0
        zeroGroup.forEach { request ->
            val step = steps.getValue(request.id)!!
            val resolved = request.domain.resolved()
            below = max(below, ceil((-resolved.min) / step - TOLERANCE).toInt().coerceAtLeast(0))
            above = max(above, ceil(resolved.max / step - TOLERANCE).toInt().coerceAtLeast(0))
        }
        if (below + above < MIN_INTERVALS) above = MIN_INTERVALS - below
        if (below + above > MAX_INTERVALS) {
            diagnostics += AxisDiagnostic(
                axisId = null,
                message = "Zero alignment would need ${below + above} tick intervals, more than " +
                    "$MAX_INTERVALS; the axes were aligned on rows without a shared zero.",
            )
            return all.associate { it.id to fit(it.domain, target) }
        }

        val intervals = below + above
        return all.associate { request ->
            if (request.alignZero) {
                val step = steps[request.id] ?: TickGenerator.stepFor(
                    request.domain.resolved().expandToIncludeZero().span,
                    intervals,
                )
                val low = -below * step
                request.id to AlignedAxis(
                    NumericDomain(low, above * step),
                    ticksOf(low, step, intervals),
                )
            } else {
                request.id to fit(request.domain, intervals)
            }
        }
    }

    private fun ticksOf(low: Double, step: Double, intervals: Int): List<Double> =
        (0..intervals).map { index ->
            val value = low + step * index
            // A tick computed as -1.7763568394002505E-15 formats as "-0".
            if (kotlin.math.abs(value) < step * 1e-9) 0.0 else value
        }

    private fun format(value: Double): String = ChartMath.finiteOr(value, 0.0).let {
        if (it == floor(it) && kotlin.math.abs(it) < 1e12) it.toLong().toString() else "%.3g".format(it)
    }

    private const val TOLERANCE: Double = 1e-9
    private const val LADDER_LIMIT: Int = 40
}
