package io.devkit.chartkit.layer.polar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.geometry.PolarSlice
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSelectionDetails
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX

/** Where a pie or donut writes its slice labels. */
enum class SliceLabelPosition {

    /** Nowhere. The legend carries the names. The default. */
    None,

    /**
     * Inside the slice.
     *
     * Only where the slice is genuinely big enough: a label is drawn only when
     * the arc at the label's radius is wider than the text and the ring is
     * taller than it. A pie with one dominant slice therefore labels that one
     * and leaves the slivers to the legend, rather than stacking six unreadable
     * strings on top of each other.
     */
    Inside,

    /**
     * Outside the ring, joined to its slice by a leader line.
     *
     * Placement is collision-aware in the same conservative way: a label whose
     * measured box would overlap one already placed, or would leave the plot,
     * is skipped. Nothing is shrunk or ellipsised, so what survives is legible.
     */
    Outside,

    /**
     * [Inside] where the slice is big enough to hold the text, [Outside]
     * otherwise, and nothing at all when neither fits.
     *
     * The policy most charts want and few state: the dominant slices carry
     * their labels on themselves, the slivers get a leader line out to the
     * margin, and whatever still collides is dropped rather than overlapped.
     */
    Auto,
}

/** What a slice label says. */
enum class SliceLabelContent {
    Label,
    Value,
    Percentage,
    LabelAndPercentage,
}

/** One slice's identity: what it is called, what it came from, how it is coloured. */
internal class SliceSeriesEntry(
    val label: String,
    val item: Any?,
    val paletteIndex: Int,
    val colorOverride: Int?,
)

/**
 * Pie and donut slices.
 *
 * The single renderer behind both. A donut is a pie with a non-zero inner
 * radius — that is the whole difference, and it lives in
 * [io.devkit.chartkit.coordinate.PolarCoordinates] rather than here, so there
 * is no `DonutChartCanvas` to keep in step with a `PieChartCanvas`.
 *
 * Arcs are drawn with `drawArc`, one call per slice, on the chart's own canvas.
 * A composable per slice would put a layout node on every wedge of every pie —
 * fine for four slices, ruinous for forty, and unnecessary for a shape the
 * canvas draws natively.
 */
internal class SliceLayer(
    override val id: String,
    private val slices: List<PolarSlice>,
    private val entries: List<SliceSeriesEntry>,
    private val seriesId: String,
    private val seriesName: String,
    private val labelPosition: SliceLabelPosition,
    private val labelContent: SliceLabelContent,
    private val valueFormatter: io.devkit.chartkit.formatter.ChartValueFormatter,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    // Outside labels and their leader lines live beyond the ring, which is
    // inside the plot square but outside the circle.
    override val clipToPlot: Boolean get() = true

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val polar = context.polar
        if (!polar.isDrawable || slices.isEmpty()) return

        val reveal = context.reveal.coerceIn(0f, 1f)
        val selectionOffset = context.px(context.dimensions.sliceSelectionOffset)
        val selectedIndex = (context.selection?.takeIf { it.seriesId == seriesId })?.pointIndex ?: -1

        slices.forEach { slice ->
            if (slice.sweepAngle <= 0f) return@forEach
            val entry = entries.getOrNull(slice.sourceIndex) ?: return@forEach

            // The whole ring sweeps in together, each slice keeping its share.
            // Growing them one after another instead reads as a progress bar
            // rather than as a chart arriving.
            val fromStart = PolarGeometry.angleFrom(polar.startAngle, slice.startAngle, polar.direction)
            val animatedStart = polar.startAngle + polar.direction.sign * fromStart * reveal
            val animatedSweep = slice.sweepAngle * reveal
            if (animatedSweep <= 0f) return@forEach

            val selected = slice.sourceIndex == selectedIndex
            // Displacement, not a colour change: it survives being printed,
            // screenshotted, or read by somebody who cannot tell the two
            // colours apart.
            val centre = if (selected) {
                val mid = PolarGeometry.midAngle(animatedStart, animatedSweep, polar.direction)
                PolarGeometry.pointOnCircle(polar.center, selectionOffset, mid)
            } else {
                polar.center
            }

            val colour = entry.colorOverride?.let { Color(it) }
                ?: context.colors.seriesColor(entry.paletteIndex)

            scope.drawSlice(
                center = centre,
                innerRadius = polar.innerRadius,
                outerRadius = polar.outerRadius,
                startAngle = animatedStart,
                sweepAngle = animatedSweep,
                color = colour,
                direction = polar.direction,
            )

            if (selected) {
                scope.drawSliceOutline(
                    center = centre,
                    innerRadius = polar.innerRadius,
                    outerRadius = polar.outerRadius,
                    startAngle = animatedStart,
                    sweepAngle = animatedSweep,
                    color = context.colors.selectionGuide,
                    strokeWidth = context.px(context.dimensions.selectionGuideWidth) * 2f,
                    direction = polar.direction,
                )
            }
        }

        if (labelPosition != SliceLabelPosition.None && reveal >= 1f) {
            drawLabels(scope, context)
        }
    }

    /**
     * Draws a wedge, or a ring segment when the inner radius is non-zero.
     *
     * `useCenter = true` on `drawArc` gives a pie wedge; a donut segment needs
     * a path — arc out, arc back — because an arc stroked at the ring's
     * thickness rounds its ends differently and leaves seams between slices.
     */
    @Suppress("LongParameterList")
    private fun DrawScope.drawSlice(
        center: ChartOffset,
        innerRadius: Float,
        outerRadius: Float,
        startAngle: Float,
        sweepAngle: Float,
        color: Color,
        direction: io.devkit.chartkit.geometry.PolarDirection,
    ) {
        val canvasStart = PolarGeometry.toCanvasAngle(startAngle)
        val canvasSweep = sweepAngle * direction.sign

        if (innerRadius <= 0f) {
            drawArc(
                color = color,
                startAngle = canvasStart,
                sweepAngle = canvasSweep,
                useCenter = true,
                topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                size = Size(outerRadius * 2f, outerRadius * 2f),
            )
            return
        }

        val path = ringSegmentPath(center, innerRadius, outerRadius, canvasStart, canvasSweep)
        drawPath(path, color)
    }

    @Suppress("LongParameterList")
    private fun DrawScope.drawSliceOutline(
        center: ChartOffset,
        innerRadius: Float,
        outerRadius: Float,
        startAngle: Float,
        sweepAngle: Float,
        color: Color,
        strokeWidth: Float,
        direction: io.devkit.chartkit.geometry.PolarDirection,
    ) {
        val canvasStart = PolarGeometry.toCanvasAngle(startAngle)
        val canvasSweep = sweepAngle * direction.sign
        val path = ringSegmentPath(
            center = center,
            innerRadius = if (innerRadius <= 0f) 0f else innerRadius,
            outerRadius = outerRadius,
            canvasStart = canvasStart,
            canvasSweep = canvasSweep,
        )
        drawPath(path, color, style = Stroke(width = strokeWidth))
    }

    private fun ringSegmentPath(
        center: ChartOffset,
        innerRadius: Float,
        outerRadius: Float,
        canvasStart: Float,
        canvasSweep: Float,
    ): Path = Path().apply {
        val outer = Size(outerRadius * 2f, outerRadius * 2f)
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(
                Offset(center.x - outerRadius, center.y - outerRadius),
                outer,
            ),
            startAngleDegrees = canvasStart,
            sweepAngleDegrees = canvasSweep,
            forceMoveTo = true,
        )
        if (innerRadius > 0f) {
            val inner = Size(innerRadius * 2f, innerRadius * 2f)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    Offset(center.x - innerRadius, center.y - innerRadius),
                    inner,
                ),
                startAngleDegrees = canvasStart + canvasSweep,
                sweepAngleDegrees = -canvasSweep,
                forceMoveTo = false,
            )
        } else {
            lineTo(center.x, center.y)
        }
        close()
    }

    private fun drawLabels(scope: DrawScope, context: ChartRenderContext) {
        val polar = context.polar
        val style = context.typography.sliceLabel.copy(color = context.colors.tooltipContent)
        val outsideStyle = context.typography.sliceLabel.copy(color = context.colors.axisLabel)
        val gap = context.px(context.dimensions.labelPadding)
        // The same collision rule the bar chart's value labels and the 3D
        // charts' labels use, so "this label was dropped" means the same thing
        // everywhere in ChartKit.
        val placer = io.devkit.chartkit.layer.label.LabelPlacer(polar.plotArea, slices.size)

        slices.forEach { slice ->
            if (slice.sweepAngle <= 0f) return@forEach
            val entry = entries.getOrNull(slice.sourceIndex) ?: return@forEach
            val text = labelText(entry, slice)
            if (text.isEmpty()) return@forEach

            val mid = PolarGeometry.midAngle(slice.startAngle, slice.sweepAngle, polar.direction)

            // Auto is not a third placement: it is Inside when the slice can
            // hold the text and Outside when it cannot, decided per slice from
            // the measured text rather than from the slice count.
            val inside = context.textMeasurer.measure(text, style).let { layout ->
                val radius = PolarGeometry.anchorRadius(polar.innerRadius, polar.outerRadius)
                val arcLength = (Math.PI * radius * slice.sweepAngle / 180.0).toFloat()
                arcLength >= layout.size.width && polar.ringThickness >= layout.size.height
            }
            val resolved = when (labelPosition) {
                SliceLabelPosition.Auto ->
                    if (inside) SliceLabelPosition.Inside else SliceLabelPosition.Outside
                else -> labelPosition
            }

            when (resolved) {
                SliceLabelPosition.Inside -> {
                    val layout: TextLayoutResult = context.textMeasurer.measure(text, style)
                    if (!inside) return@forEach
                    val radius = PolarGeometry.anchorRadius(polar.innerRadius, polar.outerRadius)
                    val anchor = PolarGeometry.pointOnCircle(polar.center, radius, mid)
                    scope.drawText(
                        layout,
                        topLeft = Offset(
                            anchor.x - layout.size.width / 2f,
                            anchor.y - layout.size.height / 2f,
                        ),
                    )
                }

                SliceLabelPosition.Outside -> {
                    val layout = context.textMeasurer.measure(text, outsideStyle)
                    val from = PolarGeometry.pointOnCircle(polar.center, polar.outerRadius, mid)
                    val to = PolarGeometry.pointOnCircle(polar.center, polar.outerRadius + gap * 2f, mid)
                    val at = placeOutsideLabel(
                        placer = placer,
                        to = to,
                        rightHalf = to.x >= polar.center.x,
                        gap = gap,
                        width = layout.size.width.toFloat(),
                        height = layout.size.height.toFloat(),
                    ) ?: return@forEach

                    scope.drawLine(
                        color = context.colors.axisLine,
                        start = Offset(from.x, from.y),
                        end = Offset(to.x, to.y),
                        strokeWidth = context.px(context.dimensions.axisLineWidth),
                    )
                    scope.drawText(layout, topLeft = Offset(at.x, at.y))
                }

                SliceLabelPosition.None, SliceLabelPosition.Auto -> Unit
            }
        }
    }

    private fun labelText(entry: SliceSeriesEntry, slice: PolarSlice): String = when (labelContent) {
        SliceLabelContent.Label -> entry.label
        SliceLabelContent.Value -> valueFormatter.format(slice.value)
        SliceLabelContent.Percentage -> percentage(slice.fraction)
        SliceLabelContent.LabelAndPercentage ->
            "${entry.label} ${percentage(slice.fraction)}"
    }

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val polar = context.polar
        if (!polar.isDrawable) return null
        val index = PolarGeometry.hitTestSlices(
            slices = slices,
            center = polar.center,
            point = point,
            innerRadius = polar.innerRadius,
            outerRadius = polar.outerRadius,
            direction = polar.direction,
        )
        if (index < 0) return null
        return selectionFor(slices[index], polar)
    }

    /** A selection for [slice], for a tap or for a programmatic select. */
    internal fun selectionFor(
        slice: PolarSlice,
        polar: io.devkit.chartkit.coordinate.PolarCoordinates,
    ): AnyChartSelection? {
        val entry = entries.getOrNull(slice.sourceIndex) ?: return null
        val mid = PolarGeometry.midAngle(slice.startAngle, slice.sweepAngle, polar.direction)
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = slice.sourceIndex,
            // The slice's label *is* its domain value: on a part-to-whole chart
            // the category is the identity, and putting it here means the
            // shared tooltip, the accessibility layer and the crosshair-free
            // overlay all read it without a polar special case.
            x = ChartX.Category(entry.label),
            y = slice.value,
            item = entry.item,
            position = polar.pointAtFraction(mid, ANCHOR_RING_FRACTION),
            details = ChartSelectionDetails.Polar(
                fraction = slice.fraction,
                label = entry.label,
                startAngle = slice.startAngle,
                sweepAngle = slice.sweepAngle,
            ),
        )
    }

    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        val slice = slices.firstOrNull { it.sourceIndex == selection.pointIndex } ?: return emptyList()
        val entry = entries.getOrNull(slice.sourceIndex) ?: return emptyList()
        return listOf(
            ChartTooltipEntry(
                seriesId = seriesId,
                seriesName = entry.label,
                value = slice.value,
                item = entry.item,
                paletteIndex = entry.paletteIndex,
            ),
        )
    }

    override fun describe(): List<ChartLayerSummary> = listOf(
        ChartLayerSummary(
            seriesId = seriesId,
            seriesName = seriesName,
            pointCount = slices.size,
            entries = slices.mapNotNull { slice ->
                val entry = entries.getOrNull(slice.sourceIndex) ?: return@mapNotNull null
                // The share, not the raw value: on a part-to-whole chart the
                // percentage is what the picture actually communicates, and a
                // screen-reader user should hear the same thing.
                ChartLayerEntry(
                    label = "${entry.label} (${percentage(slice.fraction)})",
                    value = slice.value,
                )
            },
        ),
    )

    private companion object {
        /** Halfway through the ring: the middle of a donut band, mid-radius on a pie. */
        const val ANCHOR_RING_FRACTION = 0.5f
    }
}

private val PERCENT_FORMAT = java.text.DecimalFormat("0.#")

internal fun percentage(fraction: Double): String =
    if (!fraction.isFinite()) "" else PERCENT_FORMAT.format(fraction * 100.0) + "%"

