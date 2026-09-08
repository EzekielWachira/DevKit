package io.devkit.chartkit.layer.polar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import io.devkit.chartkit.model.ChartSelectionDetails
import io.devkit.chartkit.model.ChartX

/**
 * One qualitative range behind a gauge's value.
 *
 * Ranges rather than named severities. "Low, medium, high" is one application's
 * vocabulary; "under target, on target, over target" is another's, and a
 * latency gauge's bands run the other way round from a battery's. A library
 * that named them would be asserting a meaning it cannot know, so a band is an
 * interval with an optional label the caller supplies.
 *
 * @param color `null` takes the theme's ordered band colours by position.
 */
data class GaugeBand(
    val from: Double,
    val to: Double,
    val label: String? = null,
    val color: Int? = null,
) {
    init {
        require(from.isFinite() && to.isFinite()) {
            "A gauge band needs finite bounds, was [$from, $to]"
        }
    }

    internal val low: Double get() = minOf(from, to)
    internal val high: Double get() = maxOf(from, to)

    operator fun contains(value: Double): Boolean = value in low..high
}

/** How a gauge shows its value. */
enum class GaugeIndicator {

    /** An arc filled from the minimum. The default: readable at any size. */
    Arc,

    /** A needle pointing at the value. */
    Needle,

    /** Both. */
    ArcAndNeedle,
    ;

    internal val hasArc: Boolean get() = this != Needle
    internal val hasNeedle: Boolean get() = this != Arc
}

/**
 * A single value against a range, drawn as an arc.
 *
 * ### Built on the polar engine
 *
 * Not a separate polar implementation. The coordinate system, the centre-content
 * slot, the tooltip overlay, the selection state and the accessibility layer are
 * [io.devkit.chartkit.charts.PolarChartCore]'s, the same ones the pie, donut,
 * radial bar and sunburst use. What a gauge adds is one arc and one needle.
 *
 * ### The value is clamped; the announcement is not
 *
 * A value outside `[min, max]` draws at the end of the arc, because there is
 * nowhere else to draw it — but the tooltip and the screen reader report the
 * **real** number. A gauge that silently renamed 130% as 100% would be hiding
 * exactly the reading its owner most needs to see.
 */
@Suppress("LongParameterList")
internal class GaugeLayer(
    override val id: String,
    private val value: Double,
    private val minimum: Double,
    private val maximum: Double,
    private val bands: List<GaugeBand>,
    private val indicator: GaugeIndicator,
    private val seriesId: String,
    private val seriesName: String,
    private val label: String,
    private val item: Any?,
    private val valueFormatter: ChartValueFormatter,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    /** The value's position in `0..1`, clamped to the arc. */
    private val fraction: Double = when {
        !value.isFinite() || maximum <= minimum -> 0.0
        else -> ((value - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0)
    }

    /** The band the value falls in, or `null`. */
    private val activeBand: GaugeBand? = bands.firstOrNull { value in it }

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val polar = context.polar
        if (!polar.isDrawable) return
        val reveal = context.reveal.coerceIn(0f, 1f)
        val thickness = polar.ringThickness
        val radius = PolarGeometry.anchorRadius(polar.innerRadius, polar.outerRadius)

        // The track first: the "100%" the value is measured against. Without it
        // a half-full gauge and a full one look the same at a glance.
        scope.drawArcStroke(
            centre = polar.center,
            radius = radius,
            thickness = thickness,
            startAngle = polar.startAngle,
            sweepAngle = polar.sweepAngle,
            colour = context.colors.gauge.track,
        )

        bands.forEachIndexed { index, band ->
            val from = fractionOf(band.low)
            val to = fractionOf(band.high)
            if (to <= from) return@forEachIndexed
            scope.drawArcStroke(
                centre = polar.center,
                radius = radius,
                thickness = thickness,
                startAngle = polar.startAngle + (polar.sweepAngle * from).toFloat(),
                sweepAngle = (polar.sweepAngle * (to - from)).toFloat(),
                colour = band.color?.let { Color(it) } ?: context.colors.comparison.band(index),
            )
        }

        if (indicator.hasArc) {
            val sweep = (polar.sweepAngle * fraction * reveal).toFloat()
            if (sweep > 0f) {
                scope.drawArcStroke(
                    centre = polar.center,
                    radius = radius,
                    thickness = thickness,
                    startAngle = polar.startAngle,
                    sweepAngle = sweep,
                    // The band's colour when the value is in one, so the arc
                    // itself says which range the reading falls in rather than
                    // relying on the reader tracing it back to the background.
                    colour = activeBand?.color?.let { Color(it) }
                        ?: bands.indexOf(activeBand).takeIf { it >= 0 }
                            ?.let { context.colors.comparison.band(it) }
                        ?: context.colors.gauge.progress,
                    round = true,
                )
            }
        }

        if (indicator.hasNeedle) drawNeedle(scope, context, reveal)
    }

    private fun drawNeedle(scope: DrawScope, context: ChartRenderContext, reveal: Float) {
        val polar = context.polar
        val angle = polar.startAngle + (polar.sweepAngle * fraction * reveal).toFloat()
        val tip = PolarGeometry.pointOnCircle(polar.center, polar.outerRadius, angle)
        val halfWidth = context.px(context.dimensions.gaugeNeedleWidth) / 2f
        // A triangle from a hub to the tip, rather than a line: a needle that
        // tapers reads as pointing, and a bare stroke reads as a spoke.
        val left = PolarGeometry.pointOnCircle(polar.center, halfWidth, angle - NEEDLE_BASE_DEGREES)
        val right = PolarGeometry.pointOnCircle(polar.center, halfWidth, angle + NEEDLE_BASE_DEGREES)
        val path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(left.x, left.y)
            lineTo(right.x, right.y)
            close()
        }
        scope.drawPath(path, context.colors.gauge.needle)
        scope.drawCircle(
            color = context.colors.gauge.needle,
            radius = halfWidth * NEEDLE_HUB_FACTOR,
            center = Offset(polar.center.x, polar.center.y),
        )
    }

    private fun fractionOf(bound: Double): Double = when {
        maximum <= minimum -> 0.0
        else -> ((bound - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0)
    }

    /**
     * A tap anywhere in the ring selects the gauge.
     *
     * There is only one value, so "which item did I hit" has one answer — and
     * requiring a reader to land on the filled part of the arc would make a
     * gauge reading 4% almost impossible to select.
     */
    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val polar = context.polar
        if (!polar.containsInRing(point)) return null
        val angle = polar.startAngle + (polar.sweepAngle * fraction).toFloat()
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = 0,
            x = ChartX.Category(label),
            y = value,
            item = item,
            position = PolarGeometry.pointOnCircle(
                polar.center,
                PolarGeometry.anchorRadius(polar.innerRadius, polar.outerRadius),
                angle,
            ),
            details = ChartSelectionDetails.Polar(
                fraction = fraction,
                label = label,
                startAngle = polar.startAngle,
                sweepAngle = (polar.sweepAngle * fraction).toFloat(),
            ),
        )
    }

    override fun describe(): List<ChartLayerSummary> = listOf(
        ChartLayerSummary(
            seriesId = seriesId,
            seriesName = seriesName.ifBlank { label },
            pointCount = 1,
            entries = listOf(
                ChartLayerEntry(label = label, value = value, detail = statement(valueFormatter)),
            ),
        ),
    )

    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? = statement(formatter)

    /**
     * The reading, its range, and the band it falls in.
     *
     * The raw value is always the **real** one, even when it lies outside the
     * gauge and the arc had to be clamped: a reader who cannot see the dial
     * needs the number that was measured, not the number that fitted.
     */
    private fun statement(formatter: ChartValueFormatter): String = buildString {
        append(label)
        append(": ")
        append(formatter.format(value))
        append(", of ")
        append(formatter.format(minimum))
        append(" to ")
        append(formatter.format(maximum))
        append(".")
        activeBand?.label?.takeIf { it.isNotBlank() }?.let {
            append(" ")
            append(it)
            append(".")
        }
        if (value < minimum || value > maximum) {
            append(" Outside the gauge's range.")
        }
    }

    private companion object {
        /** Half the angular width of the needle's base. */
        const val NEEDLE_BASE_DEGREES = 90f
        const val NEEDLE_HUB_FACTOR = 2.2f
    }
}

/**
 * An arc drawn as a stroke of a given thickness.
 *
 * A stroked arc rather than a filled ring segment: a gauge's arc has no radial
 * edges to get wrong, its ends are caps rather than corners, and rounding them
 * is one flag instead of a path.
 */
private fun DrawScope.drawArcStroke(
    centre: ChartOffset,
    radius: Float,
    thickness: Float,
    startAngle: Float,
    sweepAngle: Float,
    colour: Color,
    round: Boolean = false,
) {
    if (radius <= 0f || thickness <= 0f || sweepAngle <= 0f) return
    drawArc(
        color = colour,
        startAngle = PolarGeometry.toCanvasAngle(startAngle),
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(
            width = thickness,
            cap = if (round) StrokeCap.Round else StrokeCap.Butt,
        ),
    )
}
