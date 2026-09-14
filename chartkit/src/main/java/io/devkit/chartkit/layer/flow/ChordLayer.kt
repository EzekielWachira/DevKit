package io.devkit.chartkit.layer.flow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.flow.ChordArc
import io.devkit.chartkit.flow.ChordGeometry
import io.devkit.chartkit.flow.ChordLayout
import io.devkit.chartkit.flow.ChordMatrix
import io.devkit.chartkit.flow.ChordRibbon
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX
import kotlin.math.abs

/** Where a chord group's name is written. */
enum class ChordLabels {

    /** Nothing. */
    None,

    /** Outside the group's band. The default. */
    Outside,

    /** Name and total. */
    OutsideWithValue,
}

/** A tap that landed on a group's band. */
data class ChordGroupSelection(
    val groupIndex: Int,
    val id: String,
    val label: String,
    val item: Any?,
)

/** A tap that landed on one flow's ribbon. */
data class ChordFlowSelection(
    val flowIndex: Int,
    val sourceIndex: Int,
    val targetIndex: Int,
    val sourceLabel: String,
    val targetLabel: String,
    val item: Any?,
)

/**
 * Flows between groups arranged on a circle.
 *
 * ```text
 *      ╭──── Europe ────╮
 *     ╱   ╲         ╱    ╲
 *   Asia ══╬═══════╬═ Africa
 *     ╲   ╱         ╲    ╱
 *      ╰── Americas ────╯
 * ```
 *
 * ### Why a circle rather than columns
 *
 * A Sankey diagram puts flow on a left-to-right axis, which encodes a direction
 * of travel through stages and cannot express a flow that goes back. Arranged on
 * a circle there is no upstream or downstream, so a pair of groups can exchange
 * in both directions and a group can flow into itself. That is the whole reason
 * this exists beside [SankeyLayer] rather than as an option on it.
 *
 * ### Ribbons carry the value in their width
 *
 * As with a Sankey band, the ribbon's **width is its weight** at both ends, and
 * a group's arc is exactly the sum of the ends attached to it. See [ChordMatrix]
 * for why each ribbon is one flow rather than a merged pair.
 *
 * ### Emphasis is not only alpha
 *
 * Selecting a group highlights the flows touching it and mutes the rest by
 * colour as well as opacity — the same reasoning as [SankeyLayer], and for the
 * same reader.
 *
 * @param geometry arrives already laid out, keyed on the matrix and the circle.
 *   Selecting, hovering and animating the reveal do not touch it.
 */
@Suppress("LongParameterList")
internal class ChordLayer(
    override val id: String,
    private val matrix: ChordMatrix,
    private val geometry: ChordGeometry,
    private val seriesId: String,
    private val seriesName: String,
    private val valueFormatter: ChartValueFormatter,
    private val labels: ChordLabels,
    private val bandThickness: Float,
    private val groupColorOf: ((Int) -> Int?)? = null,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val polar = context.polar
        if (!polar.isDrawable || matrix.isEmpty) return
        val reveal = context.reveal.coerceIn(0f, 1f)
        val highlighted = highlightedGroups(context)
        val ribbonRadius = ribbonRadius(polar.outerRadius)
        if (ribbonRadius <= 0f) return

        // Ribbons first and bands over them: a band is the thing a finger aims
        // at, and a ribbon arriving at it should terminate under its edge rather
        // than across it.
        geometry.ribbons.forEach { ribbon ->
            if (!ribbon.isDrawable) return@forEach
            val connected = highlighted == null ||
                ribbon.sourceGroup in highlighted || ribbon.targetGroup in highlighted
            val base = groupColour(ribbon.sourceGroup, context)
            val colour = if (connected) {
                base.copy(alpha = base.alpha * CONNECTED_ALPHA)
            } else {
                context.colors.flow.linkMuted.copy(alpha = MUTED_ALPHA)
            }
            scope.drawPath(
                path = ribbonPath(ribbon, polar.center, ribbonRadius, reveal),
                color = colour,
            )
        }

        geometry.arcs.forEach { arc ->
            if (!arc.isDrawable) return@forEach
            val colour = groupColour(arc.groupIndex, context)
            val muted = highlighted != null && arc.groupIndex !in highlighted
            scope.drawBand(
                center = polar.center,
                radius = polar.outerRadius - bandThickness / 2f,
                startAngle = arc.startAngle,
                sweepAngle = arc.sweepAngle * reveal,
                thickness = bandThickness,
                color = if (muted) colour.copy(alpha = MUTED_ALPHA) else colour,
            )
            if (highlighted != null && arc.groupIndex in highlighted) {
                scope.drawBand(
                    center = polar.center,
                    radius = polar.outerRadius - bandThickness / 2f,
                    startAngle = arc.startAngle,
                    sweepAngle = arc.sweepAngle * reveal,
                    thickness = bandThickness + context.px(context.dimensions.selectionGuideWidth) * 3f,
                    color = context.colors.selectionGuide.copy(alpha = SELECTION_ALPHA),
                )
            }
        }

        if (reveal >= 1f && labels != ChordLabels.None) {
            geometry.arcs.forEach { arc -> drawGroupLabel(scope, context, arc) }
        }
    }

    /**
     * The ribbon as a closed path: two arcs joined by two curves through the
     * centre.
     *
     * The centre is the control point for both curves, which is what makes a
     * ribbon narrow in the middle and splay at its ends. A straight quadrilateral
     * between the two arcs would be unreadable the moment two ribbons crossed —
     * the pinch is what lets the eye follow one of them.
     */
    private fun ribbonPath(
        ribbon: ChordRibbon,
        center: ChartOffset,
        radius: Float,
        reveal: Float,
    ): Path {
        val box = Rect(
            left = center.x - radius,
            top = center.y - radius,
            right = center.x + radius,
            bottom = center.y + radius,
        )
        val origin = Offset(center.x, center.y)
        val sourceStart = ribbon.sourceStart
        val sourceSweep = ribbon.sourceSweep * reveal
        val targetStart = ribbon.targetStart
        val targetSweep = ribbon.targetSweep * reveal

        val sourceHead = PolarGeometry.pointOnCircle(center, radius, sourceStart)
        val targetHead = PolarGeometry.pointOnCircle(center, radius, targetStart)

        // `arcTo` leaves the cursor at the arc's own far end, so each curve
        // starts where the preceding arc finished and only its destination
        // needs naming.
        return Path().apply {
            moveTo(sourceHead.x, sourceHead.y)
            arcTo(box, PolarGeometry.toCanvasAngle(sourceStart), sourceSweep, false)
            quadraticTo(origin.x, origin.y, targetHead.x, targetHead.y)
            arcTo(box, PolarGeometry.toCanvasAngle(targetStart), targetSweep, false)
            quadraticTo(origin.x, origin.y, sourceHead.x, sourceHead.y)
            close()
        }
    }

    private fun DrawScope.drawBand(
        center: ChartOffset,
        radius: Float,
        startAngle: Float,
        sweepAngle: Float,
        thickness: Float,
        color: Color,
    ) {
        if (radius <= 0f || abs(sweepAngle) <= ChordArc.MIN_SWEEP) return
        drawArc(
            color = color,
            startAngle = PolarGeometry.toCanvasAngle(startAngle),
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = thickness),
        )
    }

    private fun drawGroupLabel(scope: DrawScope, context: ChartRenderContext, arc: ChordArc) {
        val group = matrix.groups.getOrNull(arc.groupIndex) ?: return
        if (!arc.isDrawable) return
        val text = if (labels == ChordLabels.OutsideWithValue) {
            "${group.label}  ${valueFormatter.format(group.total)}"
        } else {
            group.label
        }
        val style = context.typography.nodeLabel.copy(color = context.colors.flow.label)
        val layout = context.textMeasurer.measure(text, style, maxLines = 1)
        val polar = context.polar
        val padding = context.px(context.dimensions.labelPadding)
        val anchor = PolarGeometry.pointOnCircle(
            polar.center,
            polar.outerRadius + padding,
            arc.midAngle,
        )

        // Horizontal text, placed left or right of the circle by which side the
        // group sits on. Text rotated to follow the arc reads well on a printed
        // circos plot and badly on a phone, where half the names arrive upside
        // down and none of them can be selected by a screen reader in the order
        // they are drawn.
        val onRightHalf = arc.midAngle < PolarGeometry.FULL_CIRCLE / 2f
        val left = if (onRightHalf) anchor.x else anchor.x - layout.size.width
        val top = anchor.y - layout.size.height / 2f

        val plot = polar.plotArea
        if (left < plot.left || left + layout.size.width > plot.right) return
        if (top < plot.top || top + layout.size.height > plot.bottom) return
        scope.drawText(textLayoutResult = layout, topLeft = Offset(left, top))
    }

    private fun groupColour(index: Int, context: ChartRenderContext): Color =
        groupColorOf?.invoke(index)?.let { Color(it) } ?: context.colors.seriesColor(index)

    /** Where the ribbons attach: just inside the groups' band. */
    private fun ribbonRadius(outerRadius: Float): Float = outerRadius - bandThickness

    /** The group indices emphasised by the current selection, or `null` for none. */
    private fun highlightedGroups(context: ChartRenderContext): Set<Int>? {
        val selection = context.selection?.takeIf { it.seriesId == seriesId } ?: return null
        return when (val target = selection.item) {
            is ChordGroupSelection -> matrix.neighbours(target.groupIndex)
            is ChordFlowSelection -> setOf(target.sourceIndex, target.targetIndex)
            else -> null
        }
    }

    /**
     * Which band or ribbon a point landed on.
     *
     * Bands first: a band is a deliberate, easily aimed target sitting on the
     * rim, and a tap that lands on both a band and the ribbon terminating under
     * it meant the band.
     */
    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val polar = context.polar
        if (!polar.isDrawable || matrix.isEmpty) return null
        val radius = polar.radiusOf(point)
        val angle = polar.angleOf(point)
        val ribbonRadius = ribbonRadius(polar.outerRadius)

        if (radius in ribbonRadius..polar.outerRadius) {
            val groupIndex = ChordLayout.groupAt(geometry.arcs, angle, polar.direction)
            if (groupIndex >= 0) return groupSelection(groupIndex, polar)
        }

        if (radius > ribbonRadius) return null
        // Last drawn is nearest the reader, so the ribbons are searched in
        // reverse — the same rule the map layers follow for overlapping areas.
        geometry.ribbons.asReversed().forEach { ribbon ->
            if (!ribbon.isDrawable) return@forEach
            val outline = ChordLayout.ribbonOutline(ribbon, polar.center, ribbonRadius)
            if (ChordLayout.contains(outline, point)) return flowSelection(ribbon, polar)
        }
        return null
    }

    private fun groupSelection(
        groupIndex: Int,
        polar: io.devkit.chartkit.coordinate.PolarCoordinates,
    ): AnyChartSelection? {
        val group = matrix.groups.getOrNull(groupIndex) ?: return null
        val arc = geometry.arcFor(groupIndex) ?: return null
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = groupIndex,
            x = ChartX.Category(group.label),
            y = group.total,
            item = ChordGroupSelection(groupIndex, group.id, group.label, group.item),
            position = PolarGeometry.pointOnCircle(
                polar.center,
                polar.outerRadius - bandThickness / 2f,
                arc.midAngle,
            ),
        )
    }

    private fun flowSelection(
        ribbon: ChordRibbon,
        polar: io.devkit.chartkit.coordinate.PolarCoordinates,
    ): AnyChartSelection? {
        val flow = matrix.flows.getOrNull(ribbon.flowIndex) ?: return null
        val source = matrix.groups.getOrNull(flow.sourceIndex) ?: return null
        val target = matrix.groups.getOrNull(flow.targetIndex) ?: return null
        val ribbonRadius = ribbonRadius(polar.outerRadius)
        val from = PolarGeometry.pointOnCircle(
            polar.center,
            ribbonRadius,
            ribbon.sourceStart + ribbon.sourceSweep / 2f,
        )
        val to = PolarGeometry.pointOnCircle(
            polar.center,
            ribbonRadius,
            ribbon.targetStart + ribbon.targetSweep / 2f,
        )
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = ribbon.flowIndex,
            x = ChartX.Category("${source.label} to ${target.label}"),
            y = flow.value,
            item = ChordFlowSelection(
                flowIndex = ribbon.flowIndex,
                sourceIndex = flow.sourceIndex,
                targetIndex = flow.targetIndex,
                sourceLabel = source.label,
                targetLabel = target.label,
                item = flow.item,
            ),
            // Halfway along the ribbon's own pinch, not the midpoint of the
            // straight line between its ends — on a wide ribbon those are far
            // apart, and a tooltip anchored off the shape looks unattached.
            position = ChartOffset((from.x + to.x) / 2f, (from.y + to.y) / 2f),
        )
    }

    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> = when (val target = selection.item) {
        is ChordGroupSelection -> matrix.flowsTouching(target.groupIndex).map { flow ->
            val partnerIndex =
                if (flow.sourceIndex == target.groupIndex) flow.targetIndex else flow.sourceIndex
            val partner = matrix.groups.getOrNull(partnerIndex)
            val outbound = flow.sourceIndex == target.groupIndex
            ChartTooltipEntry(
                seriesId = seriesId,
                seriesName = if (outbound) {
                    "to ${partner?.label.orEmpty()}"
                } else {
                    "from ${partner?.label.orEmpty()}"
                },
                value = flow.value,
                item = flow.item,
                paletteIndex = partnerIndex,
            )
        }

        is ChordFlowSelection -> listOf(
            ChartTooltipEntry(
                seriesId = seriesId,
                seriesName = "${target.sourceLabel} to ${target.targetLabel}",
                value = matrix.flows.getOrNull(target.flowIndex)?.value ?: 0.0,
                item = target.item,
                paletteIndex = target.sourceIndex,
            ),
        )

        else -> emptyList()
    }

    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? = when (val target = selection.item) {
        is ChordGroupSelection -> {
            val group = matrix.groups.getOrNull(target.groupIndex)
            // In and out separately: a group's arc is the sum of the two, and a
            // reader who cannot see the ribbons has no other way to learn the
            // split — which is usually the whole question.
            "${target.label}: ${formatter.format(group?.outgoing ?: 0.0)} out, " +
                "${formatter.format(group?.incoming ?: 0.0)} in"
        }

        is ChordFlowSelection ->
            "${target.sourceLabel} to ${target.targetLabel}: " +
                formatter.format(matrix.flows.getOrNull(target.flowIndex)?.value ?: 0.0)

        else -> null
    }

    override fun describe(): List<ChartLayerSummary> = listOf(
        ChartLayerSummary(
            seriesId = seriesId,
            seriesName = seriesName,
            pointCount = matrix.groups.size,
            entries = matrix.groups.map { group ->
                ChartLayerEntry(
                    label = group.label,
                    value = group.total,
                    detail = "${group.label}: ${valueFormatter.format(group.outgoing)} out, " +
                        "${valueFormatter.format(group.incoming)} in",
                )
            },
        ),
    )

    private companion object {
        /** A ribbon touching the selection, or every ribbon when nothing is selected. */
        const val CONNECTED_ALPHA = 0.72f
        const val MUTED_ALPHA = 0.18f
        const val SELECTION_ALPHA = 0.35f
    }
}
