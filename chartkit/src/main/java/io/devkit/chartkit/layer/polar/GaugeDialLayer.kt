package io.devkit.chartkit.layer.polar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.gauge.GaugeBandResolution
import io.devkit.chartkit.gauge.GaugeGeometry
import io.devkit.chartkit.gauge.GaugeMarker
import io.devkit.chartkit.gauge.GaugeMarkerShape
import io.devkit.chartkit.gauge.GaugeNeedleShape
import io.devkit.chartkit.gauge.GaugeNeedleStyle
import io.devkit.chartkit.gauge.GaugePane
import io.devkit.chartkit.gauge.GaugePivotStyle
import io.devkit.chartkit.gauge.GaugeScale
import io.devkit.chartkit.gauge.GaugeTickPlacement
import io.devkit.chartkit.gauge.GaugeTickPlan
import io.devkit.chartkit.gauge.GaugeValue
import io.devkit.chartkit.geometry.ChartOffset
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
 * Everything a dial draws that does not move.
 *
 * ### Why the split
 *
 * A gauge on a live dashboard redraws whenever its value changes — several
 * times a second for a CPU or a network meter. The face, the bands, the ticks
 * and the numbers are identical across every one of those frames, and
 * re-deriving them per frame means regenerating ticks, re-resolving bands and
 * re-measuring every label to draw the same picture.
 *
 * So the static half is computed once, keyed on the things that actually change
 * it — the scale, the bands, the tick configuration, the size — and the layer
 * keeps a reference. What a value change costs is a needle and a number.
 *
 * @param labelText the numbers beside the ticks, already formatted. Measured
 *   lazily at draw time through the context's own [androidx.compose.ui.text.TextMeasurer],
 *   which caches: measuring here would need a measurer at construction and
 *   would move text layout off the one cache that already exists for it.
 */
class GaugeDialPlan internal constructor(
    internal val scale: GaugeScale,
    internal val bands: GaugeBandResolution,
    internal val ticks: GaugeTickPlan,
    internal val labelText: Map<Double, String>,
    internal val markers: List<GaugeMarker>,
    internal val compact: Boolean,
) {
    /** Bands the policy dropped, for a caller that wants to know. */
    val diagnostics: List<String> get() = bands.skipped
}

/**
 * A dial: a face, a scale, threshold bands, and one or more needles.
 *
 * ### Built on the polar engine, not beside it
 *
 * The coordinate system, the centre-content slot, the tooltip overlay, the
 * selection state, the animation clock and the accessibility layer are
 * [io.devkit.chartkit.charts.PolarChartCore]'s — the same ones the pie, donut,
 * radial bar and sunburst use. What a dial adds is a scale in the angular
 * domain and the marks that read against it.
 *
 * ### Distinct from the arc gauge
 *
 * [GaugeLayer] draws a value as a filled arc: a KPI ring, readable at any size,
 * with no scale to read against. This draws an instrument: ticks, numbers and a
 * needle. They share the scale, the bands and the polar geometry and keep their
 * own rendering, because their visual grammar genuinely differs — an arc gauge
 * with tick marks is neither one thing nor the other.
 *
 * @param values the needles, in draw order. The last is drawn on top.
 * @param animatedValues each needle's currently animated value, by id. Falls
 *   back to the declared value, so a static render draws the settled dial.
 */
@Suppress("LongParameterList")
internal class GaugeDialLayer(
    override val id: String,
    private val plan: GaugeDialPlan,
    private val values: List<GaugeValue>,
    private val animatedValues: Map<String, Double>,
    private val pane: GaugePane,
    private val pivot: GaugePivotStyle,
    private val defaultNeedle: GaugeNeedleStyle,
    private val tickPlacement: GaugeTickPlacement,
    private val showTrack: Boolean,
    private val trackThickness: Float?,
    private val seriesName: String,
    private val valueFormatter: ChartValueFormatter,
    private val unit: String,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = values.map { it.id }

    /** Where a needle actually points this frame. */
    private fun drawnValue(value: GaugeValue): Double =
        animatedValues[value.id] ?: value.value

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val polar = context.polar
        if (!polar.isDrawable) return
        val centre = polar.center
        val outer = polar.outerRadius
        val thickness = trackThickness ?: polar.ringThickness
        val trackRadius = outer - thickness / 2f
        val colors = context.colors.gauge

        drawPane(scope, context, centre, outer)

        if (showTrack) {
            scope.arcStroke(
                centre, trackRadius, thickness,
                plan.scale.startAngle, plan.scale.sweepAngle * plan.scale.direction.sign,
                colors.track,
            )
        }

        plan.bands.bands.forEach { band ->
            val bandThickness = thickness * band.style.thickness
            // `position` places the band across the track: 0 at the inner edge,
            // 1 at the outer. A band thinner than the track therefore has
            // somewhere to sit other than the middle.
            val inner = outer - thickness
            val bandCentre = inner + thickness * band.style.position.coerceIn(0f, 1f)
            scope.arcStroke(
                centre, bandCentre, bandThickness,
                band.startAngle, band.sweepAngle * plan.scale.direction.sign,
                (band.style.color ?: band.band.color)
                    ?.let { Color(it) }
                    ?: colors.band(band.sourceIndex),
                alpha = band.style.alpha,
                round = band.style.rounded,
            )
        }

        drawTicks(scope, context, centre, outer, thickness)
        drawLabels(scope, context, centre, outer)
        plan.markers.forEach { drawMarker(scope, context, centre, outer, it) }

        // Needles last and pivot last of all: a hub drawn under the needles
        // shows their bases crossing it.
        values.forEach { drawNeedle(scope, context, centre, outer, it) }
        drawPivot(scope, context, centre, outer)
    }

    /**
     * The dial's face, following the arc rather than always filling a circle.
     *
     * A semicircular dial on a disc face is not a semicircular dial: half the
     * face has no scale on it, the pivot ends up in the middle of a circle
     * rather than at the foot of an arc, and the gauge reads as a full-circle
     * one that lost its bottom half. So a partial sweep gets a wedge — the
     * shape the instrument actually is.
     */
    private fun drawPane(
        scope: DrawScope,
        context: ChartRenderContext,
        centre: ChartOffset,
        outer: Float,
    ) {
        val radius = outer * pane.radius
        if (radius <= 0f) return
        val colors = context.colors.gauge
        val full = plan.scale.sweepAngle >= io.devkit.chartkit.geometry.PolarGeometry.FULL_CIRCLE
        val topLeft = Offset(centre.x - radius, centre.y - radius)
        val size = Size(radius * 2f, radius * 2f)
        val canvasStart = io.devkit.chartkit.geometry.PolarGeometry
            .toCanvasAngle(plan.scale.startAngle)
        val sweep = plan.scale.sweepAngle * plan.scale.direction.sign

        pane.fill?.let { fill ->
            val colour = if (fill == GaugePane.THEMED) colors.pane else Color(fill)
            if (full) {
                scope.drawCircle(colour, radius, Offset(centre.x, centre.y))
            } else {
                // `useCenter` closes the wedge back through the pivot, which is
                // where a dial's face meets itself.
                scope.drawArc(colour, canvasStart, sweep, useCenter = true, topLeft, size)
            }
        }
        pane.border?.let { border ->
            val colour = if (border == GaugePane.THEMED) colors.paneBorder else Color(border)
            val stroke = Stroke(
                width = context.px(pane.borderWidth ?: context.dimensions.axisLineWidth),
            )
            if (full) {
                scope.drawCircle(colour, radius, Offset(centre.x, centre.y), style = stroke)
            } else {
                scope.drawArc(colour, canvasStart, sweep, useCenter = true, topLeft, size, style = stroke)
            }
        }
    }

    private fun drawTicks(
        scope: DrawScope,
        context: ChartRenderContext,
        centre: ChartOffset,
        outer: Float,
        thickness: Float,
    ) {
        val colors = context.colors.gauge
        val majorLength = context.px(context.dimensions.gaugeMajorTickLength)
        val minorLength = context.px(context.dimensions.gaugeMinorTickLength)

        // Minor ticks go first, so a major tick that shares an angle with one
        // is the mark left visible.
        if (!plan.compact) {
            plan.ticks.minor.forEach { value ->
                scope.tick(
                    centre, outer, thickness, plan.scale.angleOf(value), minorLength,
                    context.px(context.dimensions.gaugeMinorTickWidth), colors.minorTick,
                    tickPlacement,
                )
            }
        }
        plan.ticks.major.forEach { value ->
            scope.tick(
                centre, outer, thickness, plan.scale.angleOf(value), majorLength,
                context.px(context.dimensions.gaugeMajorTickWidth), colors.majorTick,
                tickPlacement,
            )
        }
    }

    private fun drawLabels(
        scope: DrawScope,
        context: ChartRenderContext,
        centre: ChartOffset,
        outer: Float,
    ) {
        if (plan.ticks.labelled.isEmpty()) return
        val style = context.typography.gaugeLabel.copy(color = context.colors.gauge.label)
        val gap = context.px(context.dimensions.gaugeLabelGap)
        // Labels sit outside the arc, in the room the chart reserved for them
        // before it chose its radius — see DialGauge's label reserve. Clear of
        // the ticks as well as of the arc: a dial with ticks drawn outward and
        // numbers at the same radius has each number struck through by its own
        // tick.
        val tickReach = when (tickPlacement) {
            GaugeTickPlacement.Outside -> context.px(context.dimensions.gaugeMajorTickLength)
            GaugeTickPlacement.Cross -> context.px(context.dimensions.gaugeMajorTickLength) / 2f
            GaugeTickPlacement.Inside -> 0f
        }
        val radius = outer + tickReach + gap

        plan.ticks.labelled.forEach { value ->
            val text = plan.labelText[value] ?: return@forEach
            if (text.isBlank()) return@forEach
            val layout: TextLayoutResult = context.textMeasurer.measure(text, style)
            val angle = plan.scale.angleOf(value)
            val anchor = GaugeGeometry.pointAt(angle, radius)
            val width = layout.size.width.toFloat()
            val height = layout.size.height.toFloat()
            // Pushed outward by half its own box along the same radius, so a
            // label at nine o'clock sits to the left of the arc and one at
            // twelve sits above it — without a case per compass point.
            val unitX = if (radius > 0f) anchor.x / radius else 0f
            val unitY = if (radius > 0f) anchor.y / radius else -1f
            scope.drawText(
                layout,
                topLeft = Offset(
                    centre.x + anchor.x + unitX * width / 2f - width / 2f,
                    centre.y + anchor.y + unitY * height / 2f - height / 2f,
                ),
            )
        }
    }

    private fun drawMarker(
        scope: DrawScope,
        context: ChartRenderContext,
        centre: ChartOffset,
        outer: Float,
        marker: GaugeMarker,
    ) {
        val size = context.px(marker.size ?: context.dimensions.gaugeMarkerSize)
        val colour = marker.color?.let { Color(it) } ?: context.colors.gauge.marker
        val angle = plan.scale.angleOf(marker.value)
        val radius = outer * marker.position
        val points = GaugeGeometry.markerPolygon(
            ChartOffset(centre.x, centre.y), angle, radius, marker.shape, size,
        )
        if (points.isEmpty()) return
        if (marker.shape == GaugeMarkerShape.Dot) {
            scope.drawCircle(colour, size / 2f, Offset(points.first().x, points.first().y))
        } else {
            scope.drawPath(points.toPath(), colour)
        }
    }

    private fun drawNeedle(
        scope: DrawScope,
        context: ChartRenderContext,
        centre: ChartOffset,
        outer: Float,
        value: GaugeValue,
    ) {
        val style = value.style ?: defaultNeedle
        val angle = plan.scale.angleOf(drawnValue(value))
        val baseWidth = context.px(style.baseWidth ?: context.dimensions.gaugeNeedleWidth)
        val tipWidth = when {
            style.tipWidth != null -> context.px(style.tipWidth)
            style.shape == GaugeNeedleShape.Triangle -> 0f
            else -> context.px(context.dimensions.gaugeNeedleTipWidth)
        }
        val points = GaugeGeometry.needlePolygon(
            center = ChartOffset(centre.x, centre.y),
            angle = angle,
            shape = style.shape,
            length = outer * style.length,
            tail = outer * style.tail,
            baseWidth = baseWidth,
            tipWidth = tipWidth,
        )
        if (points.isEmpty()) return
        val colour = style.color?.let { Color(it) }
            ?: if (values.size > 1) {
                // Several needles are several series, and the palette is what
                // tells them apart in the legend too.
                context.colors.seriesColor(values.indexOf(value))
            } else {
                context.colors.gauge.needle
            }
        scope.drawPath(points.toPath(), colour)
    }

    private fun drawPivot(
        scope: DrawScope,
        context: ChartRenderContext,
        centre: ChartOffset,
        outer: Float,
    ) {
        val radius = outer * pivot.radius
        if (radius <= 0f) return
        scope.drawCircle(
            color = pivot.color?.let { Color(it) } ?: context.colors.gauge.pivot,
            radius = radius,
            center = Offset(centre.x, centre.y),
        )
        pivot.strokeColor?.let { stroke ->
            scope.drawCircle(
                color = Color(stroke),
                radius = radius,
                center = Offset(centre.x, centre.y),
                style = Stroke(
                    width = context.px(pivot.strokeWidth ?: context.dimensions.axisLineWidth),
                ),
            )
        }
    }

    /**
     * A tap anywhere in the dial selects the primary reading.
     *
     * The whole face, not just the arc: a dial is one reading, and requiring a
     * reader to land on a two-degree needle would make it unselectable. A gauge
     * that reads a *value* out of the tap is a different thing — see
     * `GaugeChart`'s interaction modes, which resolve the angle through the
     * scale rather than selecting.
     */
    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val polar = context.polar
        val primary = values.firstOrNull() ?: return null
        if (polar.radiusOf(point) > polar.outerRadius) return null
        val angle = plan.scale.angleOf(primary.value)
        return ChartSelection(
            seriesId = primary.id,
            seriesName = primary.label,
            seriesIndex = 0,
            pointIndex = 0,
            x = ChartX.Category(primary.label),
            y = primary.value,
            item = primary.item,
            position = polar.pointAt(angle, polar.outerRadius * primary.styleLength(defaultNeedle)),
            details = ChartSelectionDetails.Polar(
                fraction = plan.scale.fractionOf(primary.value),
                label = primary.label,
                startAngle = plan.scale.startAngle,
                sweepAngle = (plan.scale.sweepAngle * plan.scale.fractionOf(primary.value)).toFloat(),
            ),
        )
    }

    override fun describe(): List<ChartLayerSummary> = listOf(
        ChartLayerSummary(
            seriesId = values.firstOrNull()?.id ?: id,
            seriesName = seriesName,
            pointCount = values.size,
            entries = values.map { value ->
                ChartLayerEntry(
                    label = value.label,
                    value = value.value,
                    detail = readingOf(value, valueFormatter),
                )
            },
        ),
    )

    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? = values.firstOrNull { it.id == selection.seriesId }?.let { readingOf(it, formatter) }

    /**
     * One needle's reading, in words.
     *
     * The **real** value, always — even when the needle had to be clamped to
     * the end of the arc. A reader who cannot see the dial needs the number
     * that was measured, not the number that fitted.
     */
    internal fun readingOf(value: GaugeValue, formatter: ChartValueFormatter): String = buildString {
        append(value.label)
        append(": ")
        append(formatter.format(value.value))
        if (unit.isNotBlank()) {
            append(" ")
            append(unit)
        }
        append(".")
        plan.bands.bandAt(value.value)?.label?.takeIf { it.isNotBlank() }?.let {
            append(" ")
            append(it)
            append(".")
        }
        if (value.value !in plan.scale) {
            append(" Outside the gauge's range.")
        }
    }

    private fun GaugeValue.styleLength(fallback: GaugeNeedleStyle): Float =
        (style ?: fallback).length
}

/** A polygon as a closed Compose path. */
private fun List<ChartOffset>.toPath(): Path = Path().apply {
    if (isEmpty()) return@apply
    moveTo(first().x, first().y)
    drop(1).forEach { lineTo(it.x, it.y) }
    close()
}

/** One tick mark across the track, on the side [placement] asks for. */
@Suppress("LongParameterList")
private fun DrawScope.tick(
    centre: ChartOffset,
    outer: Float,
    thickness: Float,
    angle: Float,
    length: Float,
    width: Float,
    colour: Color,
    placement: GaugeTickPlacement,
) {
    if (length <= 0f || width <= 0f) return
    // Measured from the track's outer edge, so ticks and bands share a datum
    // and a thicker track does not push the marks off the dial.
    val (from, to) = when (placement) {
        GaugeTickPlacement.Inside -> outer to outer - length
        GaugeTickPlacement.Outside -> outer to outer + length
        GaugeTickPlacement.Cross -> outer + length / 2f to outer - length / 2f
    }
    val start = GaugeGeometry.pointAt(angle, from)
    val end = GaugeGeometry.pointAt(angle, to)
    drawLine(
        color = colour,
        start = Offset(centre.x + start.x, centre.y + start.y),
        end = Offset(centre.x + end.x, centre.y + end.y),
        strokeWidth = width,
        cap = StrokeCap.Butt,
    )
}

/** An arc drawn as a stroke of a given thickness, in ChartKit's angle convention. */
@Suppress("LongParameterList")
private fun DrawScope.arcStroke(
    centre: ChartOffset,
    radius: Float,
    thickness: Float,
    startAngle: Float,
    sweepAngle: Float,
    colour: Color,
    alpha: Float = 1f,
    round: Boolean = false,
) {
    if (radius <= 0f || thickness <= 0f || sweepAngle == 0f) return
    drawArc(
        color = colour,
        startAngle = io.devkit.chartkit.geometry.PolarGeometry.toCanvasAngle(startAngle),
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2f, radius * 2f),
        alpha = alpha,
        style = Stroke(width = thickness, cap = if (round) StrokeCap.Round else StrokeCap.Butt),
    )
}
