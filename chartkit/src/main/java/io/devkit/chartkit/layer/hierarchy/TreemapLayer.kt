package io.devkit.chartkit.layer.hierarchy

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartMath
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.hierarchy.HierarchyNode
import io.devkit.chartkit.hierarchy.TreemapTile
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartX

/** What a treemap writes inside a tile that is big enough for it. */
enum class TreemapLabels {

    /** Nothing. The chart is read through its tooltip and its legend. */
    None,

    /** The node's name. The default. */
    Label,

    /** Name and value. */
    LabelAndValue,

    /** Name and its share of the visible root. */
    LabelAndPercentage,
}

/**
 * Nested rectangles whose areas are proportional to their values.
 *
 * ### One Canvas, not one composable per rectangle
 *
 * A hierarchy of two thousand nodes is two thousand rectangles. As composables
 * that is two thousand layout nodes, two thousand semantics nodes and a
 * recomposition per selection change; as a canvas it is one draw pass over a
 * pre-computed list. Only the tooltip and the breadcrumb — one of each — are
 * Compose content.
 *
 * ### Colour follows the branch
 *
 * A node takes the palette slot of its ancestor at the first drawn level, so a
 * department and its teams read as one family. Depth is then expressed as
 * lightness within that hue rather than as another colour, because a treemap
 * that assigned a fresh hue per node would have as many colours as leaves and
 * none of them would mean anything.
 */
internal class TreemapLayer(
    override val id: String,
    private val tiles: List<TreemapTile>,
    private val visibleRoot: HierarchyNode,
    private val seriesId: String,
    private val seriesName: String,
    private val labels: TreemapLabels,
    private val valueFormatter: ChartValueFormatter,
    private val paletteIndexOf: (HierarchyNode) -> Int,
    private val colorOverrideOf: ((HierarchyNode) -> Int?)? = null,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    /** Leaves are drawn last, so a branch's frame never covers its children. */
    private val drawn: List<TreemapTile> = tiles.filter { it.node !== visibleRoot }

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val plot = context.planar.plotArea
        if (plot.isEmpty || drawn.isEmpty()) return
        val reveal = context.reveal.coerceIn(0f, 1f)
        val radius = context.px(context.dimensions.treemapCornerRadius)
        val border = context.px(context.dimensions.treemapTileBorderWidth)

        drawn.forEach { tile ->
            // The reveal grows each tile out of its own centre rather than out
            // of the chart's: growing everything from one corner would have the
            // rectangles slide past each other, which reads as a layout glitch
            // rather than as an entrance.
            val rect = tile.bounds.grownFromCentre(reveal)
            if (rect.isEmpty) return@forEach

            val colour = tileColour(tile.node, context)
            scope.drawRoundRect(
                color = colour,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                cornerRadius = CornerRadius(radius, radius),
            )
            if (border > 0f) {
                scope.drawRoundRect(
                    color = context.colors.hierarchy.tileBorder,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = border),
                )
            }

            if (isSelected(tile.node, context)) {
                // A wash and an outline, not one or the other: emphasis by
                // opacity alone is invisible to a reader with low contrast
                // sensitivity, and invisible again on a pale tile.
                scope.drawRoundRect(
                    color = context.colors.selectionHighlight,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    cornerRadius = CornerRadius(radius, radius),
                )
                scope.drawRoundRect(
                    color = context.colors.selectionGuide,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }

            if (reveal >= 1f) drawLabel(scope, context, tile, rect)
        }
    }

    /**
     * A tile's label, drawn only when the tile can actually hold it.
     *
     * Measured rather than estimated. A label that overflows its rectangle
     * reads as belonging to the neighbour it spills into, which is worse than
     * no label — and truncating to an ellipsis in a 12-pixel box produces a row
     * of tiles all labelled "…".
     */
    private fun drawLabel(
        scope: DrawScope,
        context: ChartRenderContext,
        tile: TreemapTile,
        rect: ChartRect,
    ) {
        if (labels == TreemapLabels.None) return
        val text = labelFor(tile.node) ?: return
        val style = context.typography.cellLabel.copy(color = context.colors.hierarchy.tileLabel)
        val layout: TextLayoutResult = context.textMeasurer.measure(text, style, maxLines = 2)
        val padding = context.px(context.dimensions.labelPadding)
        if (layout.size.width + padding * 2f > rect.width) return
        if (layout.size.height + padding * 2f > rect.height) return
        scope.drawText(layout, topLeft = Offset(rect.left + padding, rect.top + padding))
    }

    private fun labelFor(node: HierarchyNode): String? = when (labels) {
        TreemapLabels.None -> null
        TreemapLabels.Label -> node.label
        TreemapLabels.LabelAndValue -> "${node.label}\n${valueFormatter.format(node.value)}"
        TreemapLabels.LabelAndPercentage ->
            "${node.label}\n${percentage(node.fractionOf(visibleRoot))}"
    }

    private fun tileColour(node: HierarchyNode, context: ChartRenderContext): Color {
        colorOverrideOf?.invoke(node)?.let { return Color(it) }
        val base = context.colors.seriesColor(paletteIndexOf(node))
        val levelsBelow = (node.depth - visibleRoot.depth - 1).coerceAtLeast(0)
        if (levelsBelow == 0) return base
        // Lighter with depth, within the branch's own hue. Capped so a deep
        // tree does not fade its leaves into the background.
        val lift = (levelsBelow * DEPTH_LIFT).coerceAtMost(MAX_DEPTH_LIFT)
        return base.copy(alpha = (1f - lift).coerceAtLeast(MIN_TILE_ALPHA))
    }

    private fun isSelected(node: HierarchyNode, context: ChartRenderContext): Boolean =
        context.selection?.let { it.seriesId == seriesId && it.xLabel == node.id } == true

    /**
     * The **innermost** tile containing the point.
     *
     * Innermost, because a nested treemap's tiles overlap by construction: a
     * leaf sits inside its parent's rectangle, and the parent is the frame
     * rather than the target. Taking the first match would select the
     * department every time a reader aimed at a team.
     */
    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val hit = drawn.filter { it.bounds.contains(point) }.maxByOrNull { it.node.depth }
            ?: return null
        return selectionFor(hit)
    }

    /** The selection for a node, for drill-down and programmatic selection. */
    fun selectionFor(tile: TreemapTile): AnyChartSelection = ChartSelection(
        seriesId = seriesId,
        seriesName = seriesName,
        seriesIndex = 0,
        pointIndex = tile.node.depth,
        // The node's *id*, so a selection survives two siblings sharing a
        // label — which they routinely do in a real hierarchy.
        x = ChartX.Category(tile.node.id),
        y = tile.node.value,
        item = tile.node.item,
        position = ChartOffset(tile.bounds.centerX, tile.bounds.top),
    )

    /** The node behind a selection, for a caller acting on a drill-down. */
    fun nodeOf(selection: AnyChartSelection): HierarchyNode? =
        drawn.firstOrNull { it.node.id == selection.xLabel }?.node

    override fun describe(): List<ChartLayerSummary> {
        val children = visibleRoot.children
        if (children.isEmpty()) return emptyList()
        return listOf(
            ChartLayerSummary(
                seriesId = seriesId,
                seriesName = seriesName.ifBlank { visibleRoot.label },
                pointCount = children.size,
                entries = children.map { child ->
                    ChartLayerEntry(
                        label = child.label,
                        value = child.value,
                        detail = "${child.label}: ${valueFormatter.format(child.value)}, " +
                            "${percentage(child.fractionOf(visibleRoot))} of ${visibleRoot.label}",
                    )
                },
            ),
        )
    }

    /**
     * The path, the value and the two shares that matter.
     *
     * A treemap node's meaning is entirely relative — "24 percent of
     * Engineering" is the fact, and the raw number alone would leave a reader
     * who cannot see the rectangles with nothing to compare it against.
     */
    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? {
        val node = nodeOf(selection) ?: return null
        val parent = node.parent
        return buildString {
            append(node.path.joinToString(", "))
            append(". ")
            append(formatter.format(node.value))
            append(".")
            if (parent != null && parent.value > 0.0) {
                append(" ")
                append(percentage(node.fractionOfParent))
                append(" of ")
                append(parent.label)
                append(".")
            }
            if (parent !== visibleRoot && visibleRoot.value > 0.0) {
                append(" ")
                append(percentage(node.fractionOf(visibleRoot)))
                append(" of ")
                append(visibleRoot.label)
                append(".")
            }
        }
    }

    private companion object {
        const val DEPTH_LIFT = 0.18f
        const val MAX_DEPTH_LIFT = 0.5f
        const val MIN_TILE_ALPHA = 0.45f
    }
}

/** A share as a whole-number percentage, the way a chart label states one. */
internal fun percentage(fraction: Double): String {
    if (!fraction.isFinite()) return "0%"
    return "${Math.round(fraction * 100.0)}%"
}

/** The rectangle at [fraction] of its size, still centred where it was. */
private fun ChartRect.grownFromCentre(fraction: Float): ChartRect {
    if (fraction >= 1f) return this
    val cx = centerX
    val cy = centerY
    return ChartRect(
        left = ChartMath.lerp(cx, left, fraction),
        top = ChartMath.lerp(cy, top, fraction),
        right = ChartMath.lerp(cx, right, fraction),
        bottom = ChartMath.lerp(cy, bottom, fraction),
    )
}
