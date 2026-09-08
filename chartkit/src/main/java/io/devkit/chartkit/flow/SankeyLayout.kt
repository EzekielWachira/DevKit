package io.devkit.chartkit.flow

import io.devkit.chartkit.geometry.ChartRect
import kotlin.math.abs
import kotlin.math.max

/** A node's box, in pixels. */
class SankeyNodeBox(
    val nodeIndex: Int,
    val column: Int,
    val bounds: ChartRect,
)

/**
 * A link's band, in pixels.
 *
 * A band rather than a line: the width *is* the weight, which is the whole
 * claim a Sankey diagram makes. Drawn as a smooth ribbon between the two
 * vertical extents.
 *
 * @param sourceTop where the band leaves the source node.
 * @param targetTop where it arrives at the target.
 * @param thicknessAtSource its width where it leaves. Equal to
 *   [thicknessAtTarget] in the usual case; they differ only when a node's box
 *   had to be scaled to fit the plot, which happens when one column carries far
 *   more flow than another.
 */
@Suppress("LongParameterList")
class SankeyBand(
    val linkIndex: Int,
    val sourceX: Float,
    val targetX: Float,
    val sourceTop: Float,
    val targetTop: Float,
    val thicknessAtSource: Float,
    val thicknessAtTarget: Float,
) {
    val sourceCenter: Float get() = sourceTop + thicknessAtSource / 2f
    val targetCenter: Float get() = targetTop + thicknessAtTarget / 2f
}

/** Node boxes and link bands, computed together so their edges line up. */
class SankeyGeometry(
    val boxes: List<SankeyNodeBox>,
    val bands: List<SankeyBand>,
) {
    fun boxOf(nodeIndex: Int): SankeyNodeBox? = boxes.firstOrNull { it.nodeIndex == nodeIndex }
}

/**
 * How a Sankey diagram uses the space it is given.
 *
 * @param nodeWidth the width of a node's box.
 * @param nodePadding vertical space between two nodes in the same column.
 * @param iterations passes of the ordering refinement. Zero keeps the caller's
 *   own node order, which is sometimes what a stage-based flow wants.
 */
class SankeyLayoutSpec(
    val nodeWidth: Float = 16f,
    val nodePadding: Float = 12f,
    val iterations: Int = 6,
)

/**
 * Places a [SankeyGraph]'s nodes and links inside a rectangle.
 *
 * ```text
 * ┌──┐▓▓▓▓▓▓▓▓▓┌──┐
 * │  │▒▒▒▒▒▒▒▒▒│  │░░░░░┌──┐
 * └──┘         └──┘░░░░░│  │
 * ```
 *
 * ### Pure Kotlin, and computed once
 *
 * Nothing here touches Compose, and nothing here runs during a draw pass. The
 * layout is a function of the graph and the plot rectangle, so it is cached on
 * exactly those — selecting a node or hovering a link does not recompute it,
 * which matters because the ordering refinement below is the most expensive
 * thing in the diagram.
 *
 * ### Vertical order
 *
 * Nodes within a column are ordered by the *barycentre* of what they connect
 * to: a node sits opposite the average position of its neighbours. Repeating
 * the sweep a few times, alternately left-to-right and right-to-left, is the
 * standard Sugiyama-style heuristic and is what removes most of the crossings.
 * It is a heuristic — minimising crossings exactly is NP-hard — but it is
 * deterministic, so the same data always draws the same diagram.
 */
object SankeyLayout {

    fun layout(
        graph: SankeyGraph,
        bounds: ChartRect,
        spec: SankeyLayoutSpec = SankeyLayoutSpec(),
    ): SankeyGeometry {
        if (graph.isEmpty || bounds.isEmpty) return SankeyGeometry(emptyList(), emptyList())

        val columns = graph.columnCount
        if (columns <= 0) return SankeyGeometry(emptyList(), emptyList())

        val columnMembers = Array(columns) { depth -> graph.column(depth).toMutableList() }
        repeat(max(0, spec.iterations)) { pass ->
            refineOrder(graph, columnMembers, leftToRight = pass % 2 == 0)
        }

        // Pixels per unit of flow. Taken from the busiest column, so every
        // column is drawn at the same scale — sizing each column to its own
        // height would make a node's box mean something different depending on
        // which column it is in.
        val scale = flowScale(graph, columnMembers, bounds.height, spec.nodePadding)

        val boxes = ArrayList<SankeyNodeBox>(graph.nodes.size)
        val columnX = FloatArray(columns)
        val step = if (columns <= 1) {
            0f
        } else {
            (bounds.width - spec.nodeWidth) / (columns - 1)
        }
        for (depth in 0 until columns) {
            columnX[depth] = bounds.left + step * depth
        }

        columnMembers.forEachIndexed { depth, members ->
            val totalHeight = members.sumOf { graph.nodes[it].throughput } * scale +
                spec.nodePadding * max(0, members.size - 1)
            // Centred vertically: a short column pinned to the top reads as
            // though its flow arrives early, which it does not.
            var cursor = bounds.top + ((bounds.height - totalHeight) / 2.0).toFloat().coerceAtLeast(0f)
            members.forEach { index ->
                val height = (graph.nodes[index].throughput * scale).toFloat()
                boxes += SankeyNodeBox(
                    nodeIndex = index,
                    column = depth,
                    bounds = ChartRect(
                        left = columnX[depth],
                        top = cursor,
                        right = columnX[depth] + spec.nodeWidth,
                        bottom = cursor + height,
                    ),
                )
                cursor += height + spec.nodePadding
            }
        }

        return SankeyGeometry(boxes, bands(graph, boxes, scale))
    }

    /**
     * Bands, stacked against each node's edge in the order their other end sits.
     *
     * Sorting each node's links by the vertical position of the *far* end is
     * what stops the ribbons crossing each other inside a single node — a
     * crossing there means nothing and reads as a rendering fault.
     */
    private fun bands(
        graph: SankeyGraph,
        boxes: List<SankeyNodeBox>,
        scale: Double,
    ): List<SankeyBand> {
        val byNode = boxes.associateBy { it.nodeIndex }
        val outCursor = HashMap<Int, Float>()
        val inCursor = HashMap<Int, Float>()

        val ordered = graph.links.sortedWith(
            compareBy(
                { byNode[it.sourceIndex]?.column ?: 0 },
                { byNode[it.targetIndex]?.bounds?.top ?: 0f },
            ),
        )

        val bands = ArrayList<SankeyBand>(graph.links.size)
        // Outgoing stacks are filled in target order; incoming stacks then have
        // to be filled in source order, so the two passes are separate.
        val outByLink = HashMap<SankeyLink, Float>()
        ordered.forEach { link ->
            val box = byNode[link.sourceIndex] ?: return@forEach
            val top = outCursor.getOrPut(link.sourceIndex) { box.bounds.top }
            outByLink[link] = top
            outCursor[link.sourceIndex] = top + (link.value * scale).toFloat()
        }

        graph.links
            .sortedWith(
                compareBy(
                    { byNode[it.targetIndex]?.column ?: 0 },
                    { byNode[it.sourceIndex]?.bounds?.top ?: 0f },
                ),
            )
            .forEach { link ->
                val sourceBox = byNode[link.sourceIndex] ?: return@forEach
                val targetBox = byNode[link.targetIndex] ?: return@forEach
                val thickness = (link.value * scale).toFloat()
                val inTop = inCursor.getOrPut(link.targetIndex) { targetBox.bounds.top }
                inCursor[link.targetIndex] = inTop + thickness
                bands += SankeyBand(
                    linkIndex = link.index,
                    sourceX = sourceBox.bounds.right,
                    targetX = targetBox.bounds.left,
                    sourceTop = outByLink[link] ?: sourceBox.bounds.top,
                    targetTop = inTop,
                    thicknessAtSource = thickness,
                    thicknessAtTarget = thickness,
                )
            }
        return bands
    }

    /** Pixels per unit of flow, from whichever column needs the most room. */
    private fun flowScale(
        graph: SankeyGraph,
        columns: Array<out List<Int>>,
        height: Float,
        padding: Float,
    ): Double {
        var worst = 0.0
        columns.forEach { members ->
            if (members.isEmpty()) return@forEach
            val flow = members.sumOf { graph.nodes[it].throughput }
            val available = height - padding * (members.size - 1)
            if (flow <= 0.0 || available <= 0f) return@forEach
            val needed = flow / available
            if (needed > worst) worst = needed
        }
        return if (worst <= 0.0) 0.0 else 1.0 / worst
    }

    /**
     * One barycentre sweep over the columns.
     *
     * Each node is scored by the mean position of its neighbours in the column
     * being swept *from*, and the column is re-sorted by that score. A node with
     * no neighbours in that direction keeps its current position, so an
     * unconnected node does not migrate to the top of the diagram.
     */
    private fun refineOrder(
        graph: SankeyGraph,
        columns: Array<MutableList<Int>>,
        leftToRight: Boolean,
    ) {
        val positions = HashMap<Int, Double>()
        columns.forEach { members ->
            members.forEachIndexed { position, node -> positions[node] = position.toDouble() }
        }

        val order = if (leftToRight) columns.indices else columns.indices.reversed()
        order.forEach { depth ->
            val members = columns[depth]
            if (members.size <= 1) return@forEach
            val scored = members.map { node ->
                val related = graph.links.mapNotNull { link ->
                    when {
                        leftToRight && link.targetIndex == node -> positions[link.sourceIndex]
                        !leftToRight && link.sourceIndex == node -> positions[link.targetIndex]
                        else -> null
                    }
                }
                node to (related.average().takeIf { related.isNotEmpty() } ?: positions[node] ?: 0.0)
            }
            // A stable sort, so nodes with equal barycentres keep the caller's
            // own order rather than shuffling between passes.
            members.clear()
            members += scored.sortedBy { it.second }.map { it.first }
            members.forEachIndexed { position, node -> positions[node] = position.toDouble() }
        }
    }

    /**
     * The band under a point, or `null`.
     *
     * A ribbon is a curve rather than a rectangle, so the test interpolates the
     * band's centre at the pointer's x and compares against half its thickness
     * — the same cubic the renderer draws, evaluated at one place.
     */
    fun hitTestBand(bands: List<SankeyBand>, x: Float, y: Float): SankeyBand? =
        bands.firstOrNull { band ->
            if (x < minOf(band.sourceX, band.targetX) || x > maxOf(band.sourceX, band.targetX)) {
                false
            } else {
                val t = if (band.targetX == band.sourceX) {
                    0f
                } else {
                    ((x - band.sourceX) / (band.targetX - band.sourceX)).coerceIn(0f, 1f)
                }
                val centre = smoothstep(band.sourceCenter, band.targetCenter, t)
                val half = maxOf(band.thicknessAtSource, band.thicknessAtTarget) / 2f
                abs(y - centre) <= max(half, MIN_BAND_HIT)
            }
        }

    /**
     * The ribbon's own centre curve: an ease with zero gradient at both ends.
     *
     * Matches the cubic Bézier the renderer draws with horizontal control
     * points, so what is hit is what is on screen.
     */
    internal fun smoothstep(from: Float, to: Float, t: Float): Float {
        val eased = t * t * (3f - 2f * t)
        return from + (to - from) * eased
    }

    /** A hairline flow is still worth being able to tap. */
    private const val MIN_BAND_HIT = 4f
}
