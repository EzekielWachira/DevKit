package io.devkit.chartkit.geometry

import kotlin.math.sqrt

/**
 * How a polar-area slice turns its value into a radius.
 *
 * ```text
 * Area      r ∝ √v    a slice's area is its value
 * Radius    r ∝ v     a slice's radius is its value, and its area is v²
 * ```
 */
enum class PolarAreaScaling {

    /**
     * Radius as the square root of the value, so **area** carries it. The
     * default.
     *
     * A wedge is read by how much ink it covers, and a wedge's area grows with
     * the square of its radius. Scaling the radius linearly therefore doubles
     * the apparent size of a value that doubled — twice, once for each
     * dimension — so a series running from 1 to 4 looks like one running from 1
     * to 16. Florence Nightingale's own diagrams are area-proportional; the
     * many later "coxcombs" that are not are the reason the form has a
     * reputation for exaggeration.
     */
    Area,

    /**
     * Radius directly proportional to the value.
     *
     * Offered because it is occasionally what a reader is being asked for — a
     * radial distance that *is* the quantity, such as a reach or a range in
     * kilometres — and because refusing it would push callers to pre-transform
     * their data, which hides the decision rather than removing it. It
     * overstates differences in every other case.
     */
    Radius,
}

/**
 * One wedge: its angular slot, and how far out it reaches.
 *
 * @param value the caller's own number, untouched.
 * @param radius where the wedge's arc sits, in pixels. Zero for a value that is
 *   missing or not positive.
 */
class PolarAreaSlice internal constructor(
    val index: Int,
    val startAngle: Float,
    val sweepAngle: Float,
    val radius: Float,
    val value: Double?,
) {
    /** True when there is a wedge to draw. */
    val isDrawable: Boolean get() = radius > 0f && sweepAngle != 0f

    /** The angle halfway across the wedge, where a label belongs. */
    val midAngle: Float get() = PolarGeometry.normalizeAngle(startAngle + sweepAngle / 2f)
}

/**
 * Equal-angle wedges of unequal radius — the polar area diagram, or Nightingale
 * rose.
 *
 * ```text
 *       ╱│╲        every wedge takes the same angle
 *      ╱ │ ╲       and reaches out by its own value
 *     ╱──┼──╲
 * ```
 *
 * ### What distinguishes it from a pie
 *
 * A pie gives every slice the same radius and varies the angle; this gives
 * every slice the same angle and varies the radius. The consequence is that a
 * rose can show a series whose values do **not** sum to a meaningful whole —
 * monthly rainfall, deaths by cause, wind by direction — where a pie would be
 * claiming a total that nobody has. It is also readable around a cycle: twelve
 * equal wedges are twelve months, and the shape closes where the year does.
 *
 * Plain Kotlin: no Compose, no `android.graphics`.
 */
object PolarAreaGeometry {

    /**
     * Places every wedge.
     *
     * @param maxValue the value reaching the full radius, or `null` to take the
     *   data's own largest. Fix it when two roses must be comparable — without
     *   it each chart rescales to itself and two roses of very different
     *   magnitudes look identical.
     */
    @Suppress("LongParameterList")
    fun slices(
        values: List<Double?>,
        outerRadius: Float,
        startAngle: Float,
        sweepAngle: Float,
        direction: PolarDirection,
        scaling: PolarAreaScaling = PolarAreaScaling.Area,
        maxValue: Double? = null,
    ): List<PolarAreaSlice> {
        if (values.isEmpty() || outerRadius <= 0f) return emptyList()

        val largest = maxValue
            ?: values.filterNotNull().filter { it.isFinite() && it > 0.0 }.maxOrNull()
            ?: 0.0
        val slot = sweepAngle / values.size
        val sign = direction.sign

        return values.mapIndexed { index, value ->
            val usable = value?.takeIf { it.isFinite() && it > 0.0 }
            val fraction = if (largest > 0.0 && usable != null) usable / largest else 0.0
            val scaled = when (scaling) {
                // √ rather than the value itself, so the ink is the quantity.
                PolarAreaScaling.Area -> sqrt(fraction)
                PolarAreaScaling.Radius -> fraction
            }
            PolarAreaSlice(
                index = index,
                startAngle = startAngle + slot * index * sign,
                sweepAngle = slot * sign,
                radius = (outerRadius * scaled).toFloat(),
                value = value,
            )
        }
    }

    /**
     * Which wedge a point landed in, or `-1`.
     *
     * Angle picks the wedge and radius confirms the point is inside the ink.
     * A point beyond a short wedge's arc belongs to no wedge even though it is
     * within that wedge's angular slot, because the empty space out there is
     * not part of the mark — selecting it would let a reader tap well outside a
     * small value and be told they had hit it.
     */
    fun sliceAt(
        slices: List<PolarAreaSlice>,
        angle: Float,
        radius: Float,
        direction: PolarDirection,
    ): Int {
        slices.forEach { slice ->
            if (!slice.isDrawable) return@forEach
            val travelled = PolarGeometry.angleFrom(slice.startAngle, angle, direction)
            if (travelled <= kotlin.math.abs(slice.sweepAngle) && radius <= slice.radius) {
                return slice.index
            }
        }
        return -1
    }
}
