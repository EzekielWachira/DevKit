package io.devkit.chartkit.flow

import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import kotlin.math.abs

/**
 * One group's arc on the circle.
 *
 * @param startAngle the leading edge, as an absolute chart angle — zero at
 *   twelve o'clock, increasing clockwise. See [PolarGeometry] for the
 *   convention.
 * @param sweepAngle how far the arc runs, **signed** by the chart's direction,
 *   so it can be handed to a canvas without a second sign decision.
 */
class ChordArc internal constructor(
    val groupIndex: Int,
    val startAngle: Float,
    val sweepAngle: Float,
) {
    /** The trailing edge. */
    val endAngle: Float get() = startAngle + sweepAngle

    /** The angle halfway along, where a label belongs. */
    val midAngle: Float get() = PolarGeometry.normalizeAngle(startAngle + sweepAngle / 2f)

    /** True when the arc is wide enough to draw. */
    val isDrawable: Boolean get() = abs(sweepAngle) > MIN_SWEEP

    internal companion object {
        const val MIN_SWEEP = 0.01f
    }
}

/**
 * One flow's ribbon: the segment it occupies at each end.
 *
 * Both ends span the same angular *quantity* — the flow's value — but not the
 * same number of degrees, because the two groups have different totals spread
 * over different arcs. That is the correct reading: a ribbon's end is its share
 * of the group it lands in.
 */
class ChordRibbon internal constructor(
    val flowIndex: Int,
    val sourceGroup: Int,
    val targetGroup: Int,
    val sourceStart: Float,
    val sourceSweep: Float,
    val targetStart: Float,
    val targetSweep: Float,
) {
    val sourceEnd: Float get() = sourceStart + sourceSweep
    val targetEnd: Float get() = targetStart + targetSweep

    /** True when both ends are wide enough to be worth a shape. */
    val isDrawable: Boolean
        get() = abs(sourceSweep) > ChordArc.MIN_SWEEP && abs(targetSweep) > ChordArc.MIN_SWEEP
}

/** Where every arc and ribbon sits, for one frame. */
class ChordGeometry internal constructor(
    val arcs: List<ChordArc>,
    val ribbons: List<ChordRibbon>,
) {
    /** The arc for [groupIndex], or `null` when the group carries no flow. */
    fun arcFor(groupIndex: Int): ChordArc? = arcs.firstOrNull { it.groupIndex == groupIndex }
}

/**
 * Angular layout and ribbon outlines for a chord diagram.
 *
 * Plain Kotlin: no Compose, no `android.graphics`. Which group a tap landed on,
 * which ribbon it landed in, and where every edge sits are the parts worth
 * testing directly, and they are all here.
 */
object ChordLayout {

    /** How many points an arc or a curve is flattened into for hit testing. */
    private const val FLATTEN_SAMPLES = 12

    /** One end of one flow, waiting for an angle. */
    private class End(
        val flowIndex: Int,
        val isSource: Boolean,
        val partner: Int,
        val value: Double,
    )

    /**
     * Places every group and every ribbon.
     *
     * Groups take angle in proportion to [ChordGroup.total], in the caller's own
     * order, separated by [padAngle]. Within a group the ends are ordered by the
     * group they connect to and then by the caller's flow order, which is what
     * keeps ribbons to the same partner adjacent instead of interleaved — and,
     * being a total order over indices, keeps two layouts of the same data
     * identical.
     *
     * @param startAngle where the first group begins.
     * @param sweepAngle how much of the circle the whole diagram covers.
     * @param padAngle the gap between two groups, in degrees.
     */
    fun layout(
        matrix: ChordMatrix,
        startAngle: Float,
        sweepAngle: Float,
        direction: PolarDirection,
        padAngle: Float,
    ): ChordGeometry {
        val groupCount = matrix.groups.size
        if (groupCount == 0 || matrix.flows.isEmpty()) return ChordGeometry(emptyList(), emptyList())

        val grandTotal = matrix.groups.sumOf { it.total }
        if (grandTotal <= 0.0) return ChordGeometry(emptyList(), emptyList())

        // A full circle closes on itself, so every group needs a gap after it.
        // A partial sweep has two free ends and needs one fewer.
        val isFullCircle = abs(sweepAngle) >= PolarGeometry.FULL_CIRCLE - ChordArc.MIN_SWEEP
        val gaps = if (isFullCircle) groupCount else (groupCount - 1).coerceAtLeast(0)
        // Padding never eats the whole circle: on a diagram of many small groups
        // a generous pad would otherwise leave nothing to draw, and a diagram of
        // nothing but gaps is worse than a cramped one.
        val totalPad = (padAngle * gaps).coerceIn(0f, abs(sweepAngle) * MAX_PAD_SHARE)
        val available = abs(sweepAngle) - totalPad
        val perGap = if (gaps > 0) totalPad / gaps else 0f
        val sign = direction.sign

        val ends = Array(groupCount) { ArrayList<End>() }
        matrix.flows.forEach { flow ->
            ends[flow.sourceIndex] += End(flow.index, true, flow.targetIndex, flow.value)
            ends[flow.targetIndex] += End(flow.index, false, flow.sourceIndex, flow.value)
        }
        ends.forEach { list ->
            // Outgoing before incoming for the same flow, so a self-flow's two
            // ends keep a stable order rather than depending on the sort's.
            list.sortWith(compareBy({ it.partner }, { it.flowIndex }, { !it.isSource }))
        }

        val arcs = ArrayList<ChordArc>(groupCount)
        // Each end's placed segment, keyed by flow and side, so the ribbon pass
        // is a lookup rather than a second search through the groups.
        val placedStart = HashMap<Long, Float>(matrix.flows.size * 2)
        val placedSweep = HashMap<Long, Float>(matrix.flows.size * 2)

        var cursor = startAngle
        matrix.groups.forEachIndexed { groupIndex, group ->
            val extent = (group.total / grandTotal * available).toFloat()
            arcs += ChordArc(groupIndex, cursor, extent * sign)

            var within = cursor
            ends[groupIndex].forEach { end ->
                val share = (end.value / grandTotal * available).toFloat()
                val key = keyOf(end.flowIndex, end.isSource)
                placedStart[key] = within
                placedSweep[key] = share * sign
                within += share * sign
            }
            cursor += (extent + perGap) * sign
        }

        val ribbons = matrix.flows.mapNotNull { flow ->
            val sourceKey = keyOf(flow.index, true)
            val targetKey = keyOf(flow.index, false)
            val sourceStart = placedStart[sourceKey] ?: return@mapNotNull null
            val targetStart = placedStart[targetKey] ?: return@mapNotNull null
            ChordRibbon(
                flowIndex = flow.index,
                sourceGroup = flow.sourceIndex,
                targetGroup = flow.targetIndex,
                sourceStart = sourceStart,
                sourceSweep = placedSweep[sourceKey] ?: 0f,
                targetStart = targetStart,
                targetSweep = placedSweep[targetKey] ?: 0f,
            )
        }

        return ChordGeometry(arcs, ribbons)
    }

    private fun keyOf(flowIndex: Int, isSource: Boolean): Long =
        flowIndex.toLong() * 2L + if (isSource) 0L else 1L

    /**
     * The ribbon's outline as a polygon, in drawing order.
     *
     * Two arcs at [radius] joined by two quadratic curves through the centre —
     * the same shape the layer strokes, flattened. Sharing one outline between
     * drawing and hit testing is what keeps a tap landing where the ink is: two
     * independent approximations of a curved band would disagree at the edges,
     * and the disagreement would be invisible until a user tapped it.
     */
    fun ribbonOutline(
        ribbon: ChordRibbon,
        center: ChartOffset,
        radius: Float,
        samples: Int = FLATTEN_SAMPLES,
    ): List<ChartOffset> {
        if (radius <= 0f) return emptyList()
        val steps = samples.coerceAtLeast(2)
        val points = ArrayList<ChartOffset>(steps * 4)

        sampleArc(points, center, radius, ribbon.sourceStart, ribbon.sourceSweep, steps)
        val sourceEnd = PolarGeometry.pointOnCircle(center, radius, ribbon.sourceEnd)
        val targetStart = PolarGeometry.pointOnCircle(center, radius, ribbon.targetStart)
        sampleQuadratic(points, sourceEnd, center, targetStart, steps)

        sampleArc(points, center, radius, ribbon.targetStart, ribbon.targetSweep, steps)
        val targetEnd = PolarGeometry.pointOnCircle(center, radius, ribbon.targetEnd)
        val sourceStart = PolarGeometry.pointOnCircle(center, radius, ribbon.sourceStart)
        sampleQuadratic(points, targetEnd, center, sourceStart, steps)

        return points
    }

    private fun sampleArc(
        into: MutableList<ChartOffset>,
        center: ChartOffset,
        radius: Float,
        startAngle: Float,
        sweepAngle: Float,
        steps: Int,
    ) {
        for (step in 0..steps) {
            val angle = startAngle + sweepAngle * step / steps
            into += PolarGeometry.pointOnCircle(center, radius, angle)
        }
    }

    private fun sampleQuadratic(
        into: MutableList<ChartOffset>,
        from: ChartOffset,
        control: ChartOffset,
        to: ChartOffset,
        steps: Int,
    ) {
        // The endpoints are already on the list from the arcs on either side,
        // so only the interior is sampled — a duplicated vertex is harmless for
        // a fill and a wasted comparison for every hit test.
        for (step in 1 until steps) {
            val t = step.toFloat() / steps
            val inverse = 1f - t
            into += ChartOffset(
                x = inverse * inverse * from.x + 2f * inverse * t * control.x + t * t * to.x,
                y = inverse * inverse * from.y + 2f * inverse * t * control.y + t * t * to.y,
            )
        }
        into += to
    }

    /**
     * True when [point] is inside [polygon].
     *
     * Ray casting: count the edges a ray from the point crosses, and an odd
     * count means inside. Chosen over a winding rule because a ribbon whose ends
     * overlap — which happens whenever two groups are adjacent and the curve
     * doubles back — is one region to a reader and would be two to a
     * non-zero-winding test.
     */
    fun contains(polygon: List<ChartOffset>, point: ChartOffset): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var previous = polygon.last()
        polygon.forEach { current ->
            val straddles = (current.y > point.y) != (previous.y > point.y)
            if (straddles) {
                val crossingX = (previous.x - current.x) *
                    (point.y - current.y) / (previous.y - current.y) + current.x
                if (point.x < crossingX) inside = !inside
            }
            previous = current
        }
        return inside
    }

    /**
     * Which group's arc [angle] falls in, or `-1`.
     *
     * The caller has already established that the radius is inside the band;
     * this answers the angular half.
     */
    fun groupAt(arcs: List<ChordArc>, angle: Float, direction: PolarDirection): Int {
        arcs.forEach { arc ->
            if (!arc.isDrawable) return@forEach
            val travelled = PolarGeometry.angleFrom(arc.startAngle, angle, direction)
            if (travelled <= abs(arc.sweepAngle)) return arc.groupIndex
        }
        return -1
    }

    /** However many groups and however wide the pad, most of the circle is data. */
    private const val MAX_PAD_SHARE = 0.4f
}
