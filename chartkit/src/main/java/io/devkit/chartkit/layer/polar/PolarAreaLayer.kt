package io.devkit.chartkit.layer.polar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.PolarAreaGeometry
import io.devkit.chartkit.geometry.PolarAreaScaling
import io.devkit.chartkit.geometry.PolarAreaSlice
import io.devkit.chartkit.geometry.PolarGeometry
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

/** One wedge's identity, alongside the geometry that places it. */
internal class PolarAreaEntry(
    val label: String,
    val value: Double?,
    val item: Any?,
    val paletteIndex: Int,
    val colorOverride: Int?,
)

/** Where a rose writes its category names. */
enum class PolarAreaLabels {

    /** Nothing. */
    None,

    /** Outside the outer radius, around the circle. The default. */
    Outside,
}

/**
 * Equal-angle wedges of unequal radius — the Nightingale rose.
 *
 * ### Rings, not just wedges
 *
 * A rose with no scale behind it is a shape, not a reading: the wedges are
 * comparable to each other and to nothing else, and a reader has no way to
 * recover a value. The concentric rings are drawn for the same reason a
 * Cartesian chart has gridlines — and, because the default scaling puts area
 * rather than radius in proportion to the value, they are **not** evenly
 * spaced. A ring at half the value sits at 0.71 of the radius, and drawing it
 * halfway out would quietly restate the exaggeration the scaling exists to
 * avoid.
 *
 * ### A zero and a missing value look the same
 *
 * Both reach a radius of zero, and there is no ink at zero radius to
 * distinguish. Unlike a choropleth, which has a colour to spare for "no data",
 * a radius encoding has nowhere to put the distinction — so the layer does not
 * invent one. The accessibility summary says "no value" where the value is
 * absent, which is the one place the difference survives.
 */
@Suppress("LongParameterList")
internal class PolarAreaLayer(
    override val id: String,
    private val entries: List<PolarAreaEntry>,
    private val slices: List<PolarAreaSlice>,
    private val seriesId: String,
    private val seriesName: String,
    private val valueFormatter: ChartValueFormatter,
    private val labels: PolarAreaLabels,
    private val scaling: PolarAreaScaling,
    private val ringCount: Int,
    private val maxValue: Double,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val polar = context.polar
        if (!polar.isDrawable || entries.isEmpty()) return
        val reveal = context.reveal.coerceIn(0f, 1f)
        val selectedIndex = (context.selection?.takeIf { it.seriesId == seriesId })?.pointIndex ?: -1
        drawRings(scope, context, polar.outerRadius)

        slices.forEach { slice ->
            if (!slice.isDrawable) return@forEach
            val entry = entries.getOrNull(slice.index) ?: return@forEach
            val colour = entry.colorOverride?.let { Color(it) }
                ?: context.colors.seriesColor(entry.paletteIndex)
            val radius = slice.radius * reveal
            if (radius <= 0f) return@forEach

            scope.drawWedge(polar.center, radius, slice, colour.copy(alpha = WEDGE_ALPHA))
            scope.drawWedge(
                center = polar.center,
                radius = radius,
                slice = slice,
                color = colour,
                stroke = context.px(context.dimensions.gridLineWidth),
            )
            if (slice.index == selectedIndex) {
                scope.drawWedge(
                    center = polar.center,
                    radius = radius,
                    slice = slice,
                    color = context.colors.selectionGuide,
                    stroke = context.px(context.dimensions.selectionGuideWidth) * 2f,
                )
            }
        }

        if (reveal >= 1f && labels != PolarAreaLabels.None) {
            slices.forEach { slice -> drawLabel(scope, context, slice) }
        }
    }

    /**
     * The concentric value rings.
     *
     * Placed by running each ring's *value* through the same scaling the wedges
     * use, rather than by dividing the radius evenly. Even spacing would be a
     * second, contradictory scale drawn underneath the first.
     */
    private fun drawRings(scope: DrawScope, context: ChartRenderContext, outerRadius: Float) {
        if (ringCount <= 0 || maxValue <= 0.0) return
        val polar = context.polar
        for (ring in 1..ringCount) {
            val value = maxValue * ring / ringCount
            val radius = PolarAreaGeometry.slices(
                values = listOf(value),
                outerRadius = outerRadius,
                startAngle = 0f,
                sweepAngle = PolarGeometry.FULL_CIRCLE,
                direction = polar.direction,
                scaling = scaling,
                maxValue = maxValue,
            ).firstOrNull()?.radius ?: continue
            if (radius <= 0f) continue
            scope.drawCircle(
                color = context.colors.gridLine,
                radius = radius,
                center = Offset(polar.center.x, polar.center.y),
                style = Stroke(width = context.px(context.dimensions.gridLineWidth)),
            )
        }
    }

    private fun DrawScope.drawWedge(
        center: ChartOffset,
        radius: Float,
        slice: PolarAreaSlice,
        color: Color,
        stroke: Float? = null,
    ) {
        drawArc(
            color = color,
            startAngle = PolarGeometry.toCanvasAngle(slice.startAngle),
            sweepAngle = slice.sweepAngle,
            useCenter = true,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = stroke?.let { Stroke(width = it) } ?: androidx.compose.ui.graphics.drawscope.Fill,
        )
    }

    private fun drawLabel(scope: DrawScope, context: ChartRenderContext, slice: PolarAreaSlice) {
        val entry = entries.getOrNull(slice.index) ?: return
        val polar = context.polar
        val style = context.typography.sliceLabel.copy(color = context.colors.axisLabel)
        val layout = context.textMeasurer.measure(entry.label, style, maxLines = 1)
        val padding = context.px(context.dimensions.labelPadding)
        // Anchored to the full outer radius, not the wedge's own: labels that
        // moved in and out with the values would sit at twelve different
        // distances and read as a second, meaningless encoding.
        val anchor = PolarGeometry.pointOnCircle(polar.center, polar.outerRadius + padding, slice.midAngle)
        val onRight = slice.midAngle < PolarGeometry.FULL_CIRCLE / 2f
        val left = if (onRight) anchor.x else anchor.x - layout.size.width
        val top = anchor.y - layout.size.height / 2f
        val plot = polar.plotArea
        if (left < plot.left || left + layout.size.width > plot.right) return
        if (top < plot.top || top + layout.size.height > plot.bottom) return
        scope.drawText(textLayoutResult = layout, topLeft = Offset(left, top))
    }

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val polar = context.polar
        if (!polar.isDrawable) return null
        val index = PolarAreaGeometry.sliceAt(
            slices = slices,
            angle = polar.angleOf(point),
            radius = polar.radiusOf(point),
            direction = polar.direction,
        )
        if (index < 0) return null
        val entry = entries.getOrNull(index) ?: return null
        val slice = slices.getOrNull(index) ?: return null
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = index,
            x = ChartX.Category(entry.label),
            y = entry.value ?: 0.0,
            item = entry.item,
            position = PolarGeometry.pointOnCircle(polar.center, slice.radius, slice.midAngle),
            details = ChartSelectionDetails.Polar(
                fraction = if (maxValue > 0.0) (entry.value ?: 0.0) / maxValue else 0.0,
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
        val entry = entries.getOrNull(selection.pointIndex) ?: return emptyList()
        return listOf(
            ChartTooltipEntry(
                seriesId = seriesId,
                seriesName = entry.label,
                value = entry.value ?: 0.0,
                item = entry.item,
                paletteIndex = entry.paletteIndex,
            ),
        )
    }

    override fun describe(): List<ChartLayerSummary> = listOf(
        ChartLayerSummary(
            seriesId = seriesId,
            seriesName = seriesName,
            pointCount = entries.size,
            missingCount = entries.count { it.value == null },
            entries = entries.map { entry ->
                ChartLayerEntry(
                    label = entry.label,
                    value = entry.value,
                    // The one place a missing value and a zero stay apart: both
                    // draw nothing, and a radius encoding has nowhere to put the
                    // difference.
                    detail = "${entry.label}: " +
                        (entry.value?.let { valueFormatter.format(it) } ?: "no value"),
                )
            },
        ),
    )

    private companion object {
        /** Filled wedges overlap nothing, but a wash reads better under the rings. */
        const val WEDGE_ALPHA = 0.85f
    }
}
