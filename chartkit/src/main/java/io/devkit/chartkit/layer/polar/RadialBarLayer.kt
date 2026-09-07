package io.devkit.chartkit.layer.polar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.geometry.RadialGeometry
import io.devkit.chartkit.geometry.RadialRangePolicy
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

/** One concentric metric: its value, its identity, and where it came from. */
internal class RadialBarEntry(
    val label: String,
    val value: Double?,
    val item: Any?,
    val paletteIndex: Int,
    val colorOverride: Int?,
)

/**
 * Concentric progress rings — one per metric, outermost first.
 *
 * ```text
 * ╭───────────╮   CPU     72
 *  ╭────────╮     Memory  46
 *   ╭─────╮       Disk    88
 * ```
 *
 * Built on the same [io.devkit.chartkit.coordinate.PolarCoordinates] as pie and
 * donut, and on the same angle convention, so a radial chart starting at the
 * top and running clockwise needs no configuration to match the pie beside it.
 *
 * Each track is a stroked arc rather than a filled ring segment: a stroke of
 * the track's thickness is one draw call and rounds its own caps, which is
 * exactly the shape a progress ring wants.
 *
 * @param minValue, maxValue the domain a value's sweep is measured against.
 *   Not fixed at `0..100`: a radial chart of storage in gigabytes is as valid
 *   as one of percentages, and forcing the caller to normalise first is the
 *   conversion step ChartKit exists to avoid.
 */
internal class RadialBarLayer(
    override val id: String,
    private val entries: List<RadialBarEntry>,
    private val seriesId: String,
    private val seriesName: String,
    private val minValue: Double,
    private val maxValue: Double,
    private val trackThickness: Float,
    private val trackSpacing: Float,
    private val rangePolicy: RadialRangePolicy,
    private val valueFormatter: io.devkit.chartkit.formatter.ChartValueFormatter,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    private fun progressOf(value: Double?): Double =
        RadialGeometry.progress(value, minValue, maxValue, rangePolicy)

    private fun radiusOf(index: Int, outerRadius: Float): Float =
        RadialGeometry.trackRadius(index, outerRadius, trackThickness, trackSpacing)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val polar = context.polar
        if (!polar.isDrawable || entries.isEmpty()) return
        val reveal = context.reveal.coerceIn(0f, 1f)
        val selectedIndex = (context.selection?.takeIf { it.seriesId == seriesId })?.pointIndex ?: -1

        entries.forEachIndexed { index, entry ->
            val radius = radiusOf(index, polar.outerRadius)
            // A track whose radius has gone negative has been squeezed out by
            // the ones outside it; drawing it would put an arc through the
            // centre of the chart.
            if (radius <= trackThickness / 2f) return@forEachIndexed

            val colour = entry.colorOverride?.let { Color(it) }
                ?: context.colors.seriesColor(entry.paletteIndex)

            // The full track first: it is the "out of 100" a bar is read
            // against, and without it a 40% ring is just a short arc.
            scope.drawTrack(
                center = polar.center,
                radius = radius,
                startAngle = polar.startAngle,
                sweepAngle = polar.sweepAngle,
                thickness = trackThickness,
                color = context.colors.radialTrack,
                direction = polar.direction,
            )

            val sweep = (progressOf(entry.value) * polar.sweepAngle).toFloat() * reveal
            if (sweep <= 0f) return@forEachIndexed
            scope.drawTrack(
                center = polar.center,
                radius = radius,
                startAngle = polar.startAngle,
                sweepAngle = sweep,
                thickness = trackThickness,
                color = colour,
                direction = polar.direction,
                rounded = true,
            )

            if (index == selectedIndex) {
                scope.drawTrack(
                    center = polar.center,
                    radius = radius,
                    startAngle = polar.startAngle,
                    sweepAngle = polar.sweepAngle,
                    thickness = trackThickness + context.px(context.dimensions.selectionGuideWidth) * 4f,
                    color = context.colors.selectionGuide.copy(alpha = 0.35f),
                    direction = polar.direction,
                )
            }
        }
    }

    @Suppress("LongParameterList")
    private fun DrawScope.drawTrack(
        center: ChartOffset,
        radius: Float,
        startAngle: Float,
        sweepAngle: Float,
        thickness: Float,
        color: Color,
        direction: io.devkit.chartkit.geometry.PolarDirection,
        rounded: Boolean = false,
    ) {
        if (radius <= 0f || sweepAngle <= 0f) return
        drawArc(
            color = color,
            startAngle = PolarGeometry.toCanvasAngle(startAngle),
            sweepAngle = sweepAngle * direction.sign,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(
                width = thickness,
                cap = if (rounded) StrokeCap.Round else StrokeCap.Butt,
            ),
        )
    }

    /**
     * Which concentric track a point landed on.
     *
     * Radius picks the track, angle confirms the point is inside the chart's
     * sweep. Tracks are rings, so the "nearest" answer is unambiguous: a point
     * is in exactly one band or in the gap between two, and a gap belongs to
     * neither.
     */
    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val polar = context.polar
        if (!polar.isDrawable) return null
        val radius = polar.radiusOf(point)
        val angle = polar.angleOf(point)
        if (!PolarGeometry.isAngleWithin(angle, polar.startAngle, polar.sweepAngle, polar.direction)) {
            return null
        }

        val index = RadialGeometry.trackAt(
            radius = radius,
            trackCount = entries.size,
            outerRadius = polar.outerRadius,
            thickness = trackThickness,
            spacing = trackSpacing,
        )
        return if (index < 0) null else selectionFor(index, polar)
    }

    internal fun selectionFor(
        index: Int,
        polar: io.devkit.chartkit.coordinate.PolarCoordinates,
    ): AnyChartSelection? {
        val entry = entries.getOrNull(index) ?: return null
        val progress = progressOf(entry.value)
        val radius = radiusOf(index, polar.outerRadius)
        val sweep = (progress * polar.sweepAngle).toFloat()
        val anchorAngle = PolarGeometry.normalizeAngle(
            polar.startAngle + polar.direction.sign * sweep,
        )
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = index,
            x = ChartX.Category(entry.label),
            y = entry.value ?: 0.0,
            item = entry.item,
            position = PolarGeometry.pointOnCircle(polar.center, radius, anchorAngle),
            details = ChartSelectionDetails.Polar(
                fraction = progress,
                label = entry.label,
                startAngle = polar.startAngle,
                sweepAngle = sweep,
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
            entries = entries.map { entry ->
                // "72 out of 100" rather than a bare number: a radial bar's
                // meaning is the value *against its range*, and a reader who
                // cannot see the ring has no other way to learn the maximum.
                ChartLayerEntry(
                    label = "${entry.label}: ${valueFormatter.format(entry.value ?: 0.0)} out of " +
                        valueFormatter.format(maxValue),
                    value = entry.value,
                )
            },
        ),
    )
}
