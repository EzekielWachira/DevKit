package io.devkit.chartkit.layer.flow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.flow.SankeyBand
import io.devkit.chartkit.flow.SankeyGeometry
import io.devkit.chartkit.flow.SankeyGraph
import io.devkit.chartkit.flow.SankeyLayout
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX

/** Where a Sankey node's name is written. */
enum class SankeyLabels {

    /** Nothing. */
    None,

    /** Beside the node's box, outside the flow. The default. */
    Outside,

    /** Name and throughput. */
    OutsideWithValue,
}

/**
 * Weighted flows between nodes.
 *
 * ### Bands, not lines
 *
 * A link's **width is its weight**. That is the entire claim a Sankey diagram
 * makes, so the links are filled ribbons rather than strokes of a fixed
 * thickness — a diagram of hairlines would be a graph drawing with a flow
 * diagram's layout and none of its meaning.
 *
 * ### Emphasis is not only alpha
 *
 * Selecting a node highlights the flows touching it. The unconnected ones take
 * a different *colour* as well as a lower opacity, because connection state
 * carried by opacity alone is invisible on a dense diagram and to a reader with
 * low contrast sensitivity.
 *
 * ### The layout is not recomputed here
 *
 * [geometry] arrives already laid out, keyed on the graph and the plot
 * rectangle. Selecting a node, hovering a band or animating the reveal does not
 * touch it — the ordering refinement is the most expensive thing in the
 * diagram, and running it per frame would be the difference between a diagram
 * and a slideshow.
 */
@Suppress("LongParameterList")
internal class SankeyLayer(
    override val id: String,
    private val graph: SankeyGraph,
    private val geometry: SankeyGeometry,
    private val seriesId: String,
    private val seriesName: String,
    private val valueFormatter: ChartValueFormatter,
    private val labels: SankeyLabels,
    private val nodeColorOf: ((Int) -> Int?)? = null,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        if (geometry.boxes.isEmpty()) return
        val reveal = context.reveal.coerceIn(0f, 1f)
        val highlighted = highlightedNodes(context)

        // Bands first: a node's box is the anchor the flows meet, and a band
        // drawn over it would make the boxes look translucent.
        geometry.bands.forEach { band ->
            val link = graph.links.firstOrNull { it.index == band.linkIndex } ?: return@forEach
            val connected = highlighted == null ||
                link.sourceIndex in highlighted || link.targetIndex in highlighted
            val colour = when {
                highlighted == null -> context.colors.flow.link
                connected -> context.colors.flow.linkHighlight
                else -> context.colors.flow.linkMuted
            }
            scope.drawPath(bandPath(band, reveal), colour)
        }

        geometry.boxes.forEach { box ->
            val node = graph.nodes.getOrNull(box.nodeIndex) ?: return@forEach
            val rect = box.bounds
            if (rect.isEmpty) return@forEach
            val colour = nodeColorOf?.invoke(box.nodeIndex)?.let { Color(it) }
                ?: context.colors.seriesColor(box.nodeIndex)
            scope.drawRect(
                color = colour,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height * reveal),
            )
            if (highlighted != null && box.nodeIndex in highlighted) {
                scope.drawRect(
                    color = context.colors.selectionGuide,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height * reveal),
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }

            if (reveal >= 1f && labels != SankeyLabels.None) {
                drawNodeLabel(scope, context, box.bounds, node.label, node.throughput, box.column)
            }
        }
    }

    /**
     * The ribbon between two node edges.
     *
     * A cubic with horizontal control points at the midpoint, which gives the
     * band zero gradient where it leaves and arrives — so it meets the node
     * boxes square rather than at an angle, and two bands leaving the same node
     * do not cross immediately.
     */
    private fun bandPath(band: SankeyBand, reveal: Float): Path {
        val midX = (band.sourceX + band.targetX) / 2f
        val topFrom = band.sourceTop
        val topTo = band.targetTop
        val bottomFrom = band.sourceTop + band.thicknessAtSource * reveal
        val bottomTo = band.targetTop + band.thicknessAtTarget * reveal
        return Path().apply {
            moveTo(band.sourceX, topFrom)
            cubicTo(midX, topFrom, midX, topTo, band.targetX, topTo)
            lineTo(band.targetX, bottomTo)
            cubicTo(midX, bottomTo, midX, bottomFrom, band.sourceX, bottomFrom)
            close()
        }
    }

    private fun drawNodeLabel(
        scope: DrawScope,
        context: ChartRenderContext,
        bounds: io.devkit.chartkit.geometry.ChartRect,
        label: String,
        throughput: Double,
        column: Int,
    ) {
        val text = if (labels == SankeyLabels.OutsideWithValue) {
            "$label  ${valueFormatter.format(throughput)}"
        } else {
            label
        }
        val style = context.typography.nodeLabel.copy(color = context.colors.flow.label)
        val layout = context.textMeasurer.measure(text, style, maxLines = 1)
        val padding = context.px(context.dimensions.labelPadding)
        val plot = context.planar.plotArea

        // The first column labels to its right and every other column to its
        // left, so a label never sits on top of the flows leaving a node — and
        // the leftmost one is not pushed off the plot.
        val left = if (column == 0) {
            bounds.right + padding
        } else {
            bounds.left - padding - layout.size.width
        }
        if (left < plot.left || left + layout.size.width > plot.right) return
        scope.drawText(
            textLayoutResult = layout,
            topLeft = Offset(left, bounds.centerY - layout.size.height / 2f),
        )
    }

    /** The node indices emphasised by the current selection, or `null` for none. */
    private fun highlightedNodes(context: ChartRenderContext): Set<Int>? {
        val selection = context.selection?.takeIf { it.seriesId == seriesId } ?: return null
        return when (val target = selection.item) {
            is SankeyNodeSelection -> graph.neighbours(target.nodeIndex) + target.nodeIndex
            is SankeyLinkSelection -> setOf(target.sourceIndex, target.targetIndex)
            else -> null
        }
    }

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        // Nodes first: a box is a smaller and more deliberate target than the
        // bands that pass behind it, and a tap that lands on both meant the box.
        geometry.boxes.firstOrNull { it.bounds.contains(point) }?.let { box ->
            val node = graph.nodes.getOrNull(box.nodeIndex) ?: return null
            return ChartSelection(
                seriesId = seriesId,
                seriesName = seriesName,
                seriesIndex = 0,
                pointIndex = box.nodeIndex,
                x = ChartX.Category(node.label),
                y = node.throughput,
                item = SankeyNodeSelection(box.nodeIndex, node.id, node.label, node.item),
                position = ChartOffset(box.bounds.centerX, box.bounds.top),
            )
        }

        val band = SankeyLayout.hitTestBand(geometry.bands, point.x, point.y) ?: return null
        val link = graph.links.firstOrNull { it.index == band.linkIndex } ?: return null
        val source = graph.nodes.getOrNull(link.sourceIndex) ?: return null
        val target = graph.nodes.getOrNull(link.targetIndex) ?: return null
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = link.index,
            x = ChartX.Category("${source.label} → ${target.label}"),
            y = link.value,
            item = SankeyLinkSelection(
                linkIndex = link.index,
                sourceIndex = link.sourceIndex,
                targetIndex = link.targetIndex,
                sourceLabel = source.label,
                targetLabel = target.label,
                item = link.item,
            ),
            position = ChartOffset(
                (band.sourceX + band.targetX) / 2f,
                SankeyLayout.smoothstep(band.sourceCenter, band.targetCenter, 0.5f),
            ),
        )
    }

    /** The flows in and out of a selected node, so a tooltip can list them. */
    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        val node = selection.item as? SankeyNodeSelection ?: return emptyList()
        return graph.linksAt(node.nodeIndex).mapNotNull { link ->
            val other = if (link.sourceIndex == node.nodeIndex) link.targetIndex else link.sourceIndex
            val arrow = if (link.sourceIndex == node.nodeIndex) "→ " else "← "
            val name = graph.nodes.getOrNull(other)?.label ?: return@mapNotNull null
            ChartTooltipEntry(
                seriesId = "$seriesId-${link.index}",
                seriesName = arrow + name,
                value = link.value,
                item = link.item,
                paletteIndex = other,
            )
        }
    }

    override fun describe(): List<ChartLayerSummary> {
        if (graph.isEmpty) return emptyList()
        return listOf(
            ChartLayerSummary(
                seriesId = seriesId,
                seriesName = seriesName,
                pointCount = graph.links.size,
                // The *flows* are the content of a Sankey diagram, and the node
                // list without them says nothing about what moved where. Capped
                // by the announcement builder, which falls back to a range.
                entries = graph.links.map { link ->
                    val source = graph.nodes.getOrNull(link.sourceIndex)?.label.orEmpty()
                    val target = graph.nodes.getOrNull(link.targetIndex)?.label.orEmpty()
                    ChartLayerEntry(
                        label = "$source to $target",
                        value = link.value,
                        detail = "$source to $target: ${valueFormatter.format(link.value)}",
                    )
                },
            ),
        )
    }

    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? = when (val target = selection.item) {
        is SankeyLinkSelection ->
            "${target.sourceLabel} to ${target.targetLabel}: ${formatter.format(selection.y)}."

        is SankeyNodeSelection -> {
            val node = graph.nodes.getOrNull(target.nodeIndex)
            buildString {
                append(target.label)
                append(". ")
                if (node != null) {
                    if (node.incoming > 0.0) append("${formatter.format(node.incoming)} in. ")
                    if (node.outgoing > 0.0) append("${formatter.format(node.outgoing)} out. ")
                }
                append("${graph.linksAt(target.nodeIndex).size} connected flows.")
            }
        }

        else -> null
    }
}

/**
 * What a tap on a Sankey node hands back.
 *
 * A typed selection rather than the caller's raw object, because "which node"
 * and "which of its flows" are both answers a consumer needs and the node's own
 * type carries neither. [item] is still the caller's object, untouched.
 */
data class SankeyNodeSelection(
    val nodeIndex: Int,
    val id: String,
    val label: String,
    val item: Any?,
)

/** What a tap on a Sankey link hands back. */
data class SankeyLinkSelection(
    val linkIndex: Int,
    val sourceIndex: Int,
    val targetIndex: Int,
    val sourceLabel: String,
    val targetLabel: String,
    val item: Any?,
)
