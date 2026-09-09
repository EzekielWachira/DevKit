package io.devkit.chartkit.gauge

import io.devkit.chartkit.geometry.ChartMath
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.TickGenerator
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Which side of the arc a tick sits on. */
enum class GaugeTickPlacement {

    /** Toward the centre. The default: it leaves the outside for the labels. */
    Inside,

    /** Away from the centre. */
    Outside,

    /** Straddling the arc, half in and half out. */
    Cross,
}

/**
 * How many ticks a gauge draws, and where.
 *
 * ### Values, not angles
 *
 * A tick every fifteen degrees is a decoration. A tick at every twenty km/h is
 * a scale — and on a `0..200` gauge over 180° those are not the same set of
 * marks. So ticks are generated in the **value** domain, through the same
 * [TickGenerator] every Cartesian axis uses, and then mapped to angles by
 * [GaugeScale]. A dial and a bar chart over the same numbers therefore agree
 * about what a round number is.
 *
 * @param interval the spacing between major ticks, in value units. `null`
 *   derives round numbers from the range and the room available.
 * @param count the approximate number of major ticks when [interval] is `null`.
 *   `null` derives it from the arc's drawn length — see [GaugeTickPlan.of].
 * @param minorCount how many *subdivisions* each major interval is cut into.
 *   Four gives three minor ticks between each pair of majors, which is the
 *   conventional dial. Zero draws none.
 * @param minorInterval an explicit minor spacing, overriding [minorCount].
 * @param includeEnd whether a tick is forced at the maximum. On by default:
 *   a speedometer whose last label is 180 on a gauge that reaches 200 reads as
 *   broken.
 */
data class GaugeTickConfig(
    val interval: Double? = null,
    val count: Int? = null,
    val minorCount: Int = 4,
    val minorInterval: Double? = null,
    val includeEnd: Boolean = true,
    val placement: GaugeTickPlacement = GaugeTickPlacement.Inside,
) {
    init {
        require(interval == null || (interval.isFinite() && interval > 0.0)) {
            "A tick interval must be finite and positive, was $interval"
        }
        require(count == null || count >= 2) { "A gauge needs at least 2 major ticks, was $count" }
        require(minorCount >= 0) { "minorCount cannot be negative, was $minorCount" }
        require(minorInterval == null || (minorInterval.isFinite() && minorInterval > 0.0)) {
            "A minor tick interval must be finite and positive, was $minorInterval"
        }
    }

    companion object {

        /** Round numbers, four subdivisions, ticks inside the arc. */
        val Default: GaugeTickConfig = GaugeTickConfig()

        /** Major ticks only — for a dial with no room for subdivisions. */
        val MajorOnly: GaugeTickConfig = GaugeTickConfig(minorCount = 0)

        /** No ticks at all. */
        val None: GaugeTickConfig = GaugeTickConfig(count = 2, minorCount = 0)
    }
}

/**
 * The ticks a gauge will draw, and which of them are labelled.
 *
 * ### Why labels are thinned and ticks are not
 *
 * A dial with more marks than labels is a normal dial — every wristwatch is
 * one. A dial with overlapping labels is unreadable. So when there is not room
 * for every number, ChartKit drops **labels** at a uniform stride and keeps
 * every tick mark, which loses the least: the reader still sees the scale's
 * granularity and can count between the numbers that remain.
 *
 * @param major every major tick's value, in ascending order.
 * @param minor every minor tick's value, majors excluded.
 * @param labelled the subset of [major] that gets a number beside it.
 */
class GaugeTickPlan(
    val major: List<Double>,
    val minor: List<Double>,
    val labelled: List<Double>,
) {
    val isEmpty: Boolean get() = major.isEmpty() && minor.isEmpty()

    companion object {

        /** Beyond this, a dial's marks merge into a grey band. */
        private const val MAX_MAJOR_TICKS = 24

        /** Below this many degrees per major tick, the marks touch. */
        private const val MIN_DEGREES_PER_MAJOR = 9f

        /** The arc length one tick label needs, before thinning starts. */
        private const val LABEL_ARC_ALLOWANCE_PX = 44f

        /**
         * Builds the plan for a scale, an arc and the room available.
         *
         * @param radius the radius the labels sit at, in pixels. Drives both
         *   the automatic tick count and the label thinning: the same gauge at
         *   two sizes wants two different numbers of labels, and neither is a
         *   property of the data.
         * @param maxLabels a hard cap applied after thinning, or `null`.
         */
        fun of(
            scale: GaugeScale,
            config: GaugeTickConfig = GaugeTickConfig.Default,
            radius: Float = 0f,
            maxLabels: Int? = null,
        ): GaugeTickPlan {
            val domain = NumericDomain(scale.min, scale.max)

            val majors = when {
                config.interval != null -> ticksAt(scale, config.interval, config.includeEnd)
                else -> {
                    val wanted = config.count ?: autoCount(scale, radius)
                    val generated = TickGenerator.ticks(domain, wanted)
                    // The generator trims to the domain, so a range whose ends
                    // are not round loses them. A dial's ends are exactly where
                    // a reader looks first, so they are put back.
                    withEnds(generated, scale, config.includeEnd)
                }
            }.take(MAX_MAJOR_TICKS)

            val minors = minorTicks(scale, majors, config)

            // A full circle's first and last tick land on the same angle. The
            // last is dropped rather than the first, so the gauge still starts
            // its scale at its minimum.
            val drawnMajors = if (scale.sweepAngle >= FULL_TURN && majors.size > 1) {
                majors.dropLast(1)
            } else {
                majors
            }

            return GaugeTickPlan(
                major = drawnMajors,
                minor = minors,
                labelled = thinLabels(drawnMajors, scale, radius, maxLabels),
            )
        }

        /**
         * How many major ticks fit on this arc at this radius.
         *
         * Derived from the arc's drawn length rather than from the range: a
         * `0..200` gauge and a `0..1` gauge want the same number of marks at
         * the same size, and the numbers between them are the generator's
         * problem.
         */
        private fun autoCount(scale: GaugeScale, radius: Float): Int {
            if (radius <= 0f) return TickGenerator.DEFAULT_TICK_COUNT + 1
            val arcLength = (2.0 * PI * radius * (scale.sweepAngle / FULL_TURN)).toFloat()
            val byLength = (arcLength / (LABEL_ARC_ALLOWANCE_PX * 2f)).roundToInt()
            val byAngle = (scale.sweepAngle / MIN_DEGREES_PER_MAJOR).roundToInt()
            return minOf(byLength, byAngle, MAX_MAJOR_TICKS).coerceAtLeast(2)
        }

        /** Every multiple of [interval] inside the range, ends included. */
        private fun ticksAt(scale: GaugeScale, interval: Double, includeEnd: Boolean): List<Double> {
            val first = ceil(scale.min / interval - TOLERANCE) * interval
            val result = ArrayList<Double>(MAX_MAJOR_TICKS)
            var index = 0
            while (index <= MAX_MAJOR_TICKS * 4) {
                val value = first + interval * index
                if (value > scale.max + interval * TOLERANCE) break
                // Snapping: 0.1 added thirty times lands on 3.0000000000000004,
                // which formats as its own label.
                result += snap(value, interval)
                index++
            }
            return withEnds(result, scale, includeEnd)
        }

        private fun withEnds(
            ticks: List<Double>,
            scale: GaugeScale,
            includeEnd: Boolean,
        ): List<Double> {
            val step = ticks.zipWithNext { a, b -> b - a }.minOrNull() ?: scale.span
            val nudge = step * TOLERANCE
            val result = ArrayList(ticks)
            if (result.none { kotlin.math.abs(it - scale.min) <= nudge }) result.add(0, scale.min)
            if (includeEnd && result.none { kotlin.math.abs(it - scale.max) <= nudge }) {
                result += scale.max
            }
            return result.filter { it >= scale.min - nudge && it <= scale.max + nudge }
                .distinct()
                .sorted()
        }

        private fun minorTicks(
            scale: GaugeScale,
            majors: List<Double>,
            config: GaugeTickConfig,
        ): List<Double> {
            val step = config.minorInterval ?: run {
                if (config.minorCount <= 0 || majors.size < 2) return emptyList()
                val majorStep = majors.zipWithNext { a, b -> b - a }.minOrNull() ?: return emptyList()
                if (majorStep <= ChartMath.EPSILON) return emptyList()
                majorStep / config.minorCount
            }
            if (step <= ChartMath.EPSILON || !step.isFinite()) return emptyList()
            // A subdivision finer than a pixel is a solid band, not a scale.
            if (scale.span / step > MAX_MINOR_TICKS) return emptyList()

            val majorSet = majors.map { it }.toDoubleArray()
            val result = ArrayList<Double>()
            var value = floor(scale.min / step + TOLERANCE) * step
            var guard = 0
            while (value <= scale.max + step * TOLERANCE && guard++ < MAX_MINOR_TICKS * 2) {
                val snapped = snap(value, step)
                if (snapped >= scale.min - step * TOLERANCE &&
                    snapped <= scale.max + step * TOLERANCE &&
                    majorSet.none { kotlin.math.abs(it - snapped) <= step * MINOR_MERGE }
                ) {
                    result += snapped
                }
                value += step
            }
            return result
        }

        /**
         * Which major ticks keep a number beside them.
         *
         * A uniform stride with the first kept, like every other axis in
         * ChartKit — dropping labels unevenly makes a dial look like it has
         * irregular intervals.
         */
        private fun thinLabels(
            majors: List<Double>,
            scale: GaugeScale,
            radius: Float,
            maxLabels: Int?,
        ): List<Double> {
            if (majors.isEmpty()) return emptyList()
            val capacity = when {
                radius <= 0f -> majors.size
                else -> {
                    val arcLength = 2.0 * PI * radius * (scale.sweepAngle / FULL_TURN)
                    (arcLength / LABEL_ARC_ALLOWANCE_PX).toInt().coerceAtLeast(2)
                }
            }.let { fits -> minOf(fits, maxLabels ?: majors.size, majors.size) }

            if (capacity >= majors.size) return majors
            if (capacity <= 1) return listOf(majors.first())

            val stride = ceil((majors.size - 1).toDouble() / (capacity - 1)).toInt().coerceAtLeast(1)
            return majors.filterIndexed { index, _ -> index % stride == 0 }
        }

        private fun snap(value: Double, step: Double): Double {
            val rounded = Math.round(value / step).toDouble() * step
            return if (kotlin.math.abs(rounded) < step * 1e-9) 0.0 else rounded
        }

        private const val FULL_TURN = 360f
        private const val TOLERANCE = 1e-9
        private const val MINOR_MERGE = 0.25
        private const val MAX_MINOR_TICKS = 400
    }
}
