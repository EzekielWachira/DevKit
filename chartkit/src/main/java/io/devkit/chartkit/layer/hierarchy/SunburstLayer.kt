package io.devkit.chartkit.layer.hierarchy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.hierarchy.HierarchyNode
import io.devkit.chartkit.hierarchy.SunburstArc
import io.devkit.chartkit.hierarchy.SunburstLayout
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSelectionDetails
import io.devkit.chartkit.model.ChartX

/**
 * A hierarchy as concentric rings.
 *
 * ### Shared with the treemap, not copied from it
 *
 * Nothing about traversal, identity, aggregation, drill-down or accessibility
 * lives here: those are [io.devkit.chartkit.hierarchy.ChartHierarchy], and both
 * charts read the same tree. What is here is arcs — the *only* thing a sunburst
 * does differently from a treemap.
 *
 * ### Canvas paths, not a composable per arc
 *
 * A four-level hierarchy is hundreds of wedges. Each is an arc path with a hole
 * cut out of it, drawn in one pass over precomputed geometry.
 */
internal class SunburstLayer(
    override val id: String,
    private val arcs: List<SunburstArc>,
    private val visibleRoot: HierarchyNode,
    private val seriesId: String,
    private val seriesName: String,
    private val valueFormatter: ChartValueFormatter,
    private val paletteIndexOf: (HierarchyNode) -> Int,
    private val colorOverrideOf: ((HierarchyNode) -> Int?)? = null,
    private val labels: SunburstLabels = SunburstLabels.None,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val polar = context.polar
        if (!polar.isDrawable || arcs.isEmpty()) return
        val reveal = context.reveal.coerceIn(0f, 1f)

        arcs.forEach { arc ->
            if (!arc.isDrawable) return@forEach
            // The reveal sweeps the rings open from the chart's start angle,
            // which is how a reader watches a part-to-whole chart fill.
            val sweep = arc.sweepAngle * reveal
            if (sweep <= 0f) return@forEach

            val path = ringPath(polar.center, arc.innerRadius, arc.outerRadius, arc.startAngle, sweep)
            scope.drawPath(path, arcColour(arc.node, context))

            val gap = context.px(context.dimensions.sunburstRingSpacing)
            if (gap > 0f) {
                scope.drawPath(
                    path = path,
                    color = context.colors.hierarchy.tileBorder,
                    style = Stroke(width = gap),
                )
            }

            if (isSelected(arc.node, context)) {
                scope.drawPath(path, context.colors.selectionHighlight)
                scope.drawPath(
                    path = path,
                    color = context.colors.selectionGuide,
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }
        }

        if (reveal >= 1f && labels != SunburstLabels.None) {
            arcs.forEach { drawArcLabel(scope, context, it) }
        }
    }

    /**
     * A wedge of a ring: the outer sector with the inner one cut out.
     *
     * Built by difference rather than by stroking a thick arc, because a
     * stroked arc's ends are square across the stroke and would overhang the
     * neighbouring wedge by half the ring's thickness.
     */
    private fun ringPath(
        centre: ChartOffset,
        innerRadius: Float,
        outerRadius: Float,
        startAngle: Float,
        sweepAngle: Float,
    ): Path {
        val outer = Path().apply {
            moveTo(centre.x, centre.y)
            arcTo(
                rect = Rect(
                    Offset(centre.x - outerRadius, centre.y - outerRadius),
                    Size(outerRadius * 2f, outerRadius * 2f),
                ),
                startAngleDegrees = PolarGeometry.toCanvasAngle(startAngle),
                sweepAngleDegrees = sweepAngle,
                forceMoveTo = false,
            )
            close()
        }
        if (innerRadius <= 0f) return outer
        val inner = Path().apply {
            addOval(
                Rect(
                    Offset(centre.x - innerRadius, centre.y - innerRadius),
                    Size(innerRadius * 2f, innerRadius * 2f),
                ),
            )
        }
        return Path().apply { op(outer, inner, PathOperation.Difference) }
    }

    /**
     * An arc's label, drawn only where the wedge can hold it.
     *
     * Two measurements have to pass — the arc's angular width at the label's
     * radius, and the ring's thickness — because a wedge can be tall and thin
     * or short and wide, and either one is too small.
     */
    private fun drawArcLabel(scope: DrawScope, context: ChartRenderContext, arc: SunburstArc) {
        if (!arc.isDrawable) return
        val text = when (labels) {
            SunburstLabels.None -> return
            SunburstLabels.Label -> arc.node.label
            SunburstLabels.LabelAndPercentage ->
                "${arc.node.label} ${percentage(arc.node.fractionOf(visibleRoot))}"
        }
        val style = context.typography.sliceLabel.copy(color = context.colors.hierarchy.tileLabel)
        val layout = context.textMeasurer.measure(text, style, maxLines = 1)
        val ringThickness = arc.outerRadius - arc.innerRadius
        if (layout.size.height > ringThickness) return
        // Arc length at the label's radius: the space the text actually has.
        val available = arc.midRadius * Math.toRadians(arc.sweepAngle.toDouble()).toFloat()
        if (layout.size.width > available) return

        val anchor = PolarGeometry.pointOnCircle(context.polar.center, arc.midRadius, arc.midAngle)
        scope.drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                anchor.x - layout.size.width / 2f,
                anchor.y - layout.size.height / 2f,
            ),
        )
    }

    private fun arcColour(node: HierarchyNode, context: ChartRenderContext): Color {
        colorOverrideOf?.invoke(node)?.let { return Color(it) }
        val base = context.colors.seriesColor(paletteIndexOf(node))
        val levelsBelow = (node.depth - visibleRoot.depth - 1).coerceAtLeast(0)
        if (levelsBelow == 0) return base
        val lift = (levelsBelow * DEPTH_LIFT).coerceAtMost(MAX_DEPTH_LIFT)
        return base.copy(alpha = (1f - lift).coerceAtLeast(MIN_ARC_ALPHA))
    }

    private fun isSelected(node: HierarchyNode, context: ChartRenderContext): Boolean =
        context.selection?.let { it.seriesId == seriesId && it.xLabel == node.id } == true

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val polar = context.polar
        val arc = SunburstLayout.hitTest(
            arcs = arcs,
            angleDegrees = polar.angleOf(point),
            radius = polar.radiusOf(point),
        ) ?: return null
        return selectionFor(arc, polar.center)
    }

    private fun selectionFor(arc: SunburstArc, centre: ChartOffset): AnyChartSelection {
        val anchor = PolarGeometry.pointOnCircle(centre, arc.midRadius, arc.midAngle)
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = arc.node.depth,
            x = ChartX.Category(arc.node.id),
            y = arc.node.value,
            item = arc.node.item,
            position = anchor,
            // The polar details a pie tooltip already knows how to read, so a
            // sunburst gets share-aware tooltips for free.
            details = ChartSelectionDetails.Polar(
                fraction = arc.node.fractionOfParent,
                label = arc.node.label,
                startAngle = arc.startAngle,
                sweepAngle = arc.sweepAngle,
            ),
        )
    }

    /** The node behind a selection, for a caller acting on a drill-down. */
    fun nodeOf(selection: AnyChartSelection): HierarchyNode? =
        arcs.firstOrNull { it.node.id == selection.xLabel }?.node

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
        }
    }

    private companion object {
        const val DEPTH_LIFT = 0.16f
        const val MAX_DEPTH_LIFT = 0.45f
        const val MIN_ARC_ALPHA = 0.5f
    }
}

/** What a sunburst writes on an arc big enough for it. */
enum class SunburstLabels {
    None,
    Label,
    LabelAndPercentage,
}
