package io.devkit.chartkit.hierarchy

import io.devkit.chartkit.geometry.PolarGeometry
import kotlin.math.max

/**
 * One node's arc: a wedge of a ring.
 *
 * @param level how many rings out from the centre this arc is drawn, with the
 *   first drawn ring at `0`. Not the same as [HierarchyNode.depth] once the
 *   chart has been drilled into — the current root sits at the centre and its
 *   children are ring zero, whatever their absolute depth.
 * @param startAngle in ChartKit's convention: zero at twelve o'clock,
 *   increasing clockwise.
 */
class SunburstArc(
    val node: HierarchyNode,
    val level: Int,
    val startAngle: Float,
    val sweepAngle: Float,
    val innerRadius: Float,
    val outerRadius: Float,
) {
    /** The angle halfway through the arc — where a label or anchor belongs. */
    val midAngle: Float get() = PolarGeometry.normalizeAngle(startAngle + sweepAngle / 2f)

    /** Midway between the two radii, for the same reason. */
    val midRadius: Float get() = (innerRadius + outerRadius) / 2f

    /** True when the wedge is large enough to be worth drawing or hitting. */
    val isDrawable: Boolean get() = sweepAngle > 0f && outerRadius > innerRadius
}

/**
 * Turns a hierarchy into concentric rings.
 *
 * ```text
 * angle  = the node's share of its parent
 * radius = its depth below the visible root
 * ```
 *
 * A child's wedge is contained within its parent's, which is what makes the
 * picture readable: the ring is a part-to-whole chart at every level at once.
 *
 * ### Shared with the treemap
 *
 * Traversal, identity, aggregation and drill-down all come from
 * [ChartHierarchy]; this file is only the angular placement. That is why a
 * sunburst and a treemap of the same data agree about what a node's share is —
 * they are computing it from the same numbers rather than from two traversals
 * that happen to match.
 */
object SunburstLayout {

    /**
     * Arcs for every descendant of [root], down to [maxDepth] rings.
     *
     * [root] itself gets no arc: it is the centre, where a donut's hole and its
     * centre content live. A caller that wants the root drawn as a ring passes
     * an `innerRadius` of zero and treats the first ring as the root's
     * children, which is what every sunburst does.
     *
     * @param ringSpacing the gap left between rings, so the levels read as
     *   separate rather than as one gradient.
     * @param sliceGap angular space between sibling arcs, taken out of each
     *   arc's own sweep so the ring still closes.
     */
    @Suppress("LongParameterList")
    fun layout(
        root: HierarchyNode,
        innerRadius: Float,
        outerRadius: Float,
        maxDepth: Int = Int.MAX_VALUE,
        startAngle: Float = 0f,
        sweepAngle: Float = PolarGeometry.FULL_CIRCLE,
        ringSpacing: Float = 1f,
        sliceGap: Float = 0f,
    ): List<SunburstArc> {
        if (outerRadius <= innerRadius || root.value <= 0.0 || root.children.isEmpty()) {
            return emptyList()
        }

        // How many rings the tree actually needs, so a two-level hierarchy in a
        // large circle draws two thick rings rather than two thin ones with
        // empty space beyond them.
        val depth = min(maxDepth, deepestBelow(root))
        if (depth <= 0) return emptyList()

        val band = (outerRadius - innerRadius) / depth
        val arcs = ArrayList<SunburstArc>()
        placeChildren(
            parent = root,
            level = 0,
            depth = depth,
            innerRadius = innerRadius,
            band = band,
            ringSpacing = ringSpacing,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            sliceGap = sliceGap,
            into = arcs,
        )
        return arcs
    }

    /** How many levels of descendants [node] has. */
    fun deepestBelow(node: HierarchyNode): Int {
        if (node.children.isEmpty()) return 0
        return 1 + (node.children.maxOfOrNull { deepestBelow(it) } ?: 0)
    }

    @Suppress("LongParameterList")
    private fun placeChildren(
        parent: HierarchyNode,
        level: Int,
        depth: Int,
        innerRadius: Float,
        band: Float,
        ringSpacing: Float,
        startAngle: Float,
        sweepAngle: Float,
        sliceGap: Float,
        into: MutableList<SunburstArc>,
    ) {
        if (level >= depth || sweepAngle <= 0f) return
        val total = parent.children.sumOf { it.value }
        if (total <= 0.0) return

        val ringInner = innerRadius + band * level
        val ringOuter = max(ringInner, ringInner + band - ringSpacing)

        var cursor = startAngle
        parent.children.forEach { child ->
            val share = (child.value / total * sweepAngle).toFloat()
            // The gap is only affordable when the arc is wider than it: a 0.2%
            // slice with a 2° gap would otherwise get a negative sweep and
            // vanish in a way that reads as a rendering fault.
            val drawn = if (share > sliceGap) share - sliceGap else share
            if (drawn > 0f) {
                into += SunburstArc(
                    node = child,
                    level = level,
                    startAngle = PolarGeometry.normalizeAngle(cursor),
                    sweepAngle = drawn,
                    innerRadius = ringInner,
                    outerRadius = ringOuter,
                )
                placeChildren(
                    parent = child,
                    level = level + 1,
                    depth = depth,
                    innerRadius = innerRadius,
                    band = band,
                    ringSpacing = ringSpacing,
                    // Children are laid out inside the parent's *full* share,
                    // not its drawn one, so the gap does not compound outward
                    // and leave the rings misaligned.
                    startAngle = cursor,
                    sweepAngle = share,
                    sliceGap = sliceGap,
                    into = into,
                )
            }
            cursor += share
        }
    }

    /**
     * The arc under a point, or `null`.
     *
     * Radius first — it rejects the centre and everything outside the outermost
     * ring in one comparison — then the angle within the ring the radius landed
     * in.
     */
    fun hitTest(
        arcs: List<SunburstArc>,
        angleDegrees: Float,
        radius: Float,
    ): SunburstArc? = arcs.firstOrNull { arc ->
        arc.isDrawable &&
            radius >= arc.innerRadius && radius <= arc.outerRadius &&
            PolarGeometry.isAngleWithin(angleDegrees, arc.startAngle, arc.sweepAngle)
    }

    private fun min(a: Int, b: Int): Int = if (a < b) a else b
}
