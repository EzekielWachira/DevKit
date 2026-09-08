package io.devkit.chartkit.layer.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.graph.ChartGraph
import io.devkit.chartkit.graph.GraphPositions
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.state.ChartPlanarViewportState
import kotlin.math.sqrt

/** When a network graph writes its node names. */
enum class GraphLabels {

    /** Never. */
    None,

    /** Beside every node. Only readable on a small graph. */
    All,

    /**
     * Beside the selection and its neighbours. The default.
     *
     * A hundred labels over a hundred nodes is a grey rectangle. Labelling what
     * the reader has asked about is the only version of "show the names" that
     * scales.
     */
    Selected,
}

/** What a tap on a graph node hands back. */
data class GraphNodeSelection(
    val nodeIndex: Int,
    val id: String,
    val label: String,
    val degree: Int,
    val neighbours: List<String>,
    val item: Any?,
)

/** What a tap on a graph edge hands back. */
data class GraphEdgeSelection(
    val edgeIndex: Int,
    val sourceIndex: Int,
    val targetIndex: Int,
    val sourceLabel: String,
    val targetLabel: String,
    val item: Any?,
)

/**
 * Nodes and edges, drawn from a layout computed elsewhere.
 *
 * ### Canvas, and only Canvas
 *
 * Every node and every edge is a draw call, not a composable. A three-hundred
 * node graph as composables is six hundred layout nodes and six hundred
 * semantics nodes for a picture whose meaning is the *shape*, and a screen
 * reader handed six hundred of them has been given noise rather than access.
 * The accessibility story here is the summary and the selection — see
 * [describeSelection].
 *
 * ### Culling
 *
 * Zoomed in, nodes outside the plot are skipped, and an edge is skipped when
 * neither end is anywhere near it. At full zoom the test is skipped too, so the
 * common case pays nothing for it.
 */
@Suppress("LongParameterList")
internal class GraphLayer(
    override val id: String,
    private val graph: ChartGraph,
    private val positions: GraphPositions,
    private val viewport: ChartPlanarViewportState,
    private val seriesId: String,
    private val seriesName: String,
    private val labels: GraphLabels,
    private val nodeRadiusOf: (Int) -> Float,
    private val nodeColorOf: ((Int) -> Int?)? = null,
    private val draggedIndex: Int? = null,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val bounds = context.planar.contentBounds
        if (bounds.isEmpty || graph.isEmpty || positions.size < graph.nodes.size) return
        val reveal = context.reveal.coerceIn(0f, 1f)
        val highlighted = highlightedNodes(context)
        val edgeWidth = context.px(context.dimensions.graphEdgeWidth)

        // Edges first: a node is the target and must sit on top of the lines
        // meeting it, or the graph reads as a web with dots behind it.
        graph.edges.forEach { edge ->
            val from = pointOf(edge.sourceIndex, bounds) ?: return@forEach
            val to = pointOf(edge.targetIndex, bounds) ?: return@forEach
            if (!segmentIntersects(bounds, from, to)) return@forEach
            val connected = highlighted != null &&
                (edge.sourceIndex in highlighted && edge.targetIndex in highlighted)
            scope.drawLine(
                color = if (connected) {
                    context.colors.graph.edgeHighlight
                } else {
                    context.colors.graph.edge
                },
                start = Offset(from.x, from.y),
                // The reveal draws the edges outward from their sources, which
                // is what makes a force layout look like it is settling rather
                // than fading in.
                end = Offset(
                    from.x + (to.x - from.x) * reveal,
                    from.y + (to.y - from.y) * reveal,
                ),
                strokeWidth = if (connected) edgeWidth * 2f else edgeWidth,
            )
        }

        graph.nodes.forEach { node ->
            val centre = pointOf(node.index, bounds) ?: return@forEach
            val radius = nodeRadiusOf(node.index) * reveal
            if (radius <= 0f) return@forEach
            if (!bounds.inflated(radius).contains(centre)) return@forEach

            val colour = when {
                nodeColorOf?.invoke(node.index) != null -> Color(nodeColorOf.invoke(node.index)!!)
                highlighted == null -> context.colors.graph.node
                node.index in highlighted -> context.colors.graph.neighbour
                else -> context.colors.graph.node
            }
            scope.drawCircle(colour, radius, Offset(centre.x, centre.y))
            scope.drawCircle(
                color = context.colors.graph.nodeBorder,
                radius = radius,
                center = Offset(centre.x, centre.y),
                style = Stroke(width = context.px(context.dimensions.graphEdgeWidth)),
            )

            if (isSelected(node.index, context) || node.index == draggedIndex) {
                // A ring outside the node, so it reads at any node size and
                // does not depend on the node's own colour being distinguishable
                // from the highlight.
                scope.drawCircle(
                    color = context.colors.selectionGuide,
                    radius = radius + context.px(context.dimensions.selectionGuideWidth) * 3f,
                    center = Offset(centre.x, centre.y),
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }
        }

        if (reveal >= 1f) drawLabels(scope, context, bounds, highlighted)
    }

    private fun drawLabels(
        scope: DrawScope,
        context: ChartRenderContext,
        bounds: ChartRect,
        highlighted: Set<Int>?,
    ) {
        val visible: List<Int> = when (labels) {
            GraphLabels.None -> return
            GraphLabels.All -> graph.nodes.indices.toList()
            GraphLabels.Selected -> highlighted?.toList() ?: return
        }
        val style = context.typography.nodeLabel.copy(color = context.colors.graph.label)
        visible.forEach { index ->
            val node = graph.nodes.getOrNull(index) ?: return@forEach
            val centre = pointOf(index, bounds) ?: return@forEach
            val layout = context.textMeasurer.measure(node.label, style, maxLines = 1)
            val left = centre.x - layout.size.width / 2f
            val top = centre.y + nodeRadiusOf(index) + context.px(context.dimensions.labelPadding)
            if (top + layout.size.height > bounds.bottom) return@forEach
            scope.drawText(layout, topLeft = Offset(left, top))
        }
    }

    /** A node's position in pixels, through the viewport, or `null` if unusable. */
    private fun pointOf(index: Int, bounds: ChartRect): ChartOffset? {
        if (index !in 0 until positions.size) return null
        val x = viewport.transformX(positions.x[index])
        val y = viewport.transformY(positions.y[index])
        if (!x.isFinite() || !y.isFinite()) return null
        return ChartOffset(bounds.left + x * bounds.width, bounds.top + y * bounds.height)
    }

    private fun highlightedNodes(context: ChartRenderContext): Set<Int>? {
        val selection = context.selection?.takeIf { it.seriesId == seriesId } ?: return null
        return when (val target = selection.item) {
            is GraphNodeSelection -> graph.adjacency.getOrNull(target.nodeIndex).orEmpty() +
                target.nodeIndex

            is GraphEdgeSelection -> setOf(target.sourceIndex, target.targetIndex)
            else -> null
        }
    }

    private fun isSelected(index: Int, context: ChartRenderContext): Boolean =
        (context.selection?.item as? GraphNodeSelection)?.nodeIndex == index

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val bounds = context.planar.contentBounds
        val touchSlop = context.px(context.dimensions.graphNodeRadius) * TOUCH_FACTOR

        // Nodes first, nearest wins: two overlapping nodes both contain the
        // point, and the reader aimed at whichever centre is closer.
        var bestNode = -1
        var bestDistance = Float.MAX_VALUE
        graph.nodes.forEach { node ->
            val centre = pointOf(node.index, bounds) ?: return@forEach
            val dx = centre.x - point.x
            val dy = centre.y - point.y
            val distance = sqrt(dx * dx + dy * dy)
            val reach = maxOf(nodeRadiusOf(node.index), touchSlop)
            if (distance <= reach && distance < bestDistance) {
                bestDistance = distance
                bestNode = node.index
            }
        }
        if (bestNode >= 0) return nodeSelection(bestNode, bounds)

        val edgeReach = context.px(context.dimensions.graphEdgeWidth) * EDGE_TOUCH_FACTOR
        graph.edges.forEach { edge ->
            val from = pointOf(edge.sourceIndex, bounds) ?: return@forEach
            val to = pointOf(edge.targetIndex, bounds) ?: return@forEach
            if (distanceToSegment(point, from, to) > edgeReach) return@forEach
            val source = graph.nodes.getOrNull(edge.sourceIndex) ?: return@forEach
            val target = graph.nodes.getOrNull(edge.targetIndex) ?: return@forEach
            return ChartSelection(
                seriesId = seriesId,
                seriesName = seriesName,
                seriesIndex = 0,
                pointIndex = edge.index,
                x = ChartX.Category("${source.label} — ${target.label}"),
                y = edge.weight,
                item = GraphEdgeSelection(
                    edgeIndex = edge.index,
                    sourceIndex = edge.sourceIndex,
                    targetIndex = edge.targetIndex,
                    sourceLabel = source.label,
                    targetLabel = target.label,
                    item = edge.item,
                ),
                position = ChartOffset((from.x + to.x) / 2f, (from.y + to.y) / 2f),
            )
        }
        return null
    }

    /** The node nearest [point] within a touch target, for dragging. */
    fun nodeAt(point: ChartOffset, context: ChartRenderContext): Int? {
        val selection = hitTest(point, context, HitTestMode.Contains) ?: return null
        return (selection.item as? GraphNodeSelection)?.nodeIndex
    }

    private fun nodeSelection(index: Int, bounds: ChartRect): AnyChartSelection? {
        val node = graph.nodes.getOrNull(index) ?: return null
        val centre = pointOf(index, bounds) ?: return null
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = index,
            x = ChartX.Category(node.label),
            y = node.degree.toDouble(),
            item = GraphNodeSelection(
                nodeIndex = index,
                id = node.id,
                label = node.label,
                degree = node.degree,
                neighbours = graph.adjacency.getOrNull(index).orEmpty()
                    .mapNotNull { graph.nodes.getOrNull(it)?.label },
                item = node.item,
            ),
            position = ChartOffset(centre.x, centre.y - nodeRadiusOf(index)),
        )
    }

    /** A selected node's connections, so the tooltip can name them. */
    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        val node = selection.item as? GraphNodeSelection ?: return emptyList()
        return graph.edgesAt(node.nodeIndex).mapNotNull { edge ->
            val other = if (edge.sourceIndex == node.nodeIndex) edge.targetIndex else edge.sourceIndex
            val name = graph.nodes.getOrNull(other)?.label ?: return@mapNotNull null
            ChartTooltipEntry(
                seriesId = "$seriesId-${edge.index}",
                seriesName = name,
                value = edge.weight,
                item = edge.item,
                paletteIndex = other,
            )
        }.take(MAX_TOOLTIP_EDGES)
    }

    /**
     * The graph's shape, not its contents.
     *
     * A summary naming every node and edge of a three-hundred-node graph is not
     * accessible; it is a way of making the chart unusable politely. What a
     * reader can hold is the size and the most connected nodes, and then
     * whatever they select.
     */
    override fun describe(): List<ChartLayerSummary> {
        if (graph.isEmpty) return emptyList()
        val hubs = graph.nodes.sortedByDescending { it.degree }.take(MAX_ANNOUNCED_HUBS)
        return listOf(
            ChartLayerSummary(
                seriesId = seriesId,
                seriesName = seriesName,
                pointCount = graph.nodes.size,
                entries = hubs.map { node ->
                    ChartLayerEntry(
                        label = node.label,
                        value = node.degree.toDouble(),
                        detail = "${node.label}: ${node.degree} connections",
                    )
                },
            ),
        )
    }

    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? = when (val target = selection.item) {
        is GraphNodeSelection -> buildString {
            append(target.label)
            append(". ")
            append(target.degree)
            append(if (target.degree == 1) " connection" else " connections")
            if (target.neighbours.isNotEmpty()) {
                append(": ")
                append(target.neighbours.take(MAX_ANNOUNCED_NEIGHBOURS).joinToString(", "))
                if (target.neighbours.size > MAX_ANNOUNCED_NEIGHBOURS) {
                    append(" and ${target.neighbours.size - MAX_ANNOUNCED_NEIGHBOURS} more")
                }
            }
            append(".")
        }

        is GraphEdgeSelection -> "${target.sourceLabel} to ${target.targetLabel}."
        else -> null
    }

    private companion object {
        /** Nodes are small; fingers are not. */
        const val TOUCH_FACTOR = 1.6f
        const val EDGE_TOUCH_FACTOR = 6f
        const val MAX_TOOLTIP_EDGES = 8
        const val MAX_ANNOUNCED_HUBS = 5
        const val MAX_ANNOUNCED_NEIGHBOURS = 6
    }
}

/** The rectangle grown by [amount] on every side, for culling with a margin. */
private fun ChartRect.inflated(amount: Float): ChartRect =
    ChartRect(left - amount, top - amount, right + amount, bottom + amount)

/** True when the segment could touch [bounds] at all. A cheap reject, not exact. */
private fun segmentIntersects(bounds: ChartRect, a: ChartOffset, b: ChartOffset): Boolean =
    maxOf(a.x, b.x) >= bounds.left && minOf(a.x, b.x) <= bounds.right &&
        maxOf(a.y, b.y) >= bounds.top && minOf(a.y, b.y) <= bounds.bottom

/** The perpendicular distance from [point] to the segment `a`–`b`. */
private fun distanceToSegment(point: ChartOffset, a: ChartOffset, b: ChartOffset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= 0f) {
        val px = point.x - a.x
        val py = point.y - a.y
        return sqrt(px * px + py * py)
    }
    val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    val cx = a.x + t * dx
    val cy = a.y + t * dy
    val ex = point.x - cx
    val ey = point.y - cy
    return sqrt(ex * ex + ey * ey)
}
