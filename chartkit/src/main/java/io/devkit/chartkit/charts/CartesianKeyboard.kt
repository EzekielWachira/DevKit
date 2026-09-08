package io.devkit.chartkit.charts

import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextMeasurer
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.theme.ChartTheme
import kotlin.math.abs

/**
 * Stepping a chart's selection without a pointer.
 *
 * ### Why it reuses the pointer path
 *
 * A keyboard, a D-pad and a screen reader's "next item" action all mean the
 * same thing as dragging one position along the domain — so they resolve
 * through the **same** hit test, produce the **same** [AnyChartSelection], set
 * the **same** state and move the **same** crosshair. A parallel "focused index"
 * model would have been a second notion of what is selected, and the two would
 * disagree the first time a chart was driven both ways.
 *
 * ### Steps, not points
 *
 * A chart can hold several series of different lengths; "the ninth point" has
 * no single meaning across them. A *step* is a position along the domain, and
 * every series answers with whatever it has nearest — which is exactly what a
 * scrub does, and what makes stepping work on a combined chart.
 */
internal fun CartesianGeometry.stepCount(): Int = when {
    categoryCount > 0 -> categoryCount
    else -> summaries.maxOfOrNull { it.pointCount } ?: 0
}

/** The series ids a reader can move between, in declaration order. */
internal fun CartesianGeometry.navigableSeriesIds(): List<String> =
    summaries.map { it.seriesId }.distinct()

/**
 * The selection at [step], optionally restricted to one series.
 *
 * Returns `null` when the chart has nothing there — an empty chart, or a step
 * that falls in a gap of the only visible series — and the caller leaves the
 * selection alone rather than clearing it, so a key press that finds nothing
 * does not lose the reader's place.
 */
internal fun CartesianGeometry.selectionAtStep(
    step: Int,
    seriesId: String?,
    theme: ChartTheme,
    density: Density,
    textMeasurer: TextMeasurer,
): AnyChartSelection? {
    val count = stepCount()
    if (count <= 0) return null
    val bounded = step.coerceIn(0, count - 1)

    // The middle of a band for a category axis, and an even division of the
    // domain otherwise — the same two conventions the range readout uses.
    val fraction = if (categoryCount > 0) {
        (bounded + 0.5) / count
    } else {
        if (count == 1) 0.5 else bounded.toDouble() / (count - 1)
    }

    val domain = domainValueAtFraction(fraction)
    val position = domainPositionOf(domain) ?: return null
    if (!position.isFinite()) return null

    val plot = coordinates.plotArea
    val probe = coordinates.pointAt(
        position,
        coordinates.valueOf(ChartOffset(plot.centerX, plot.centerY)),
    )
    val context = ChartRenderContext(
        coordinates = coordinates,
        colors = theme.colors,
        typography = theme.typography,
        dimensions = theme.dimensions,
        density = density,
        textMeasurer = textMeasurer,
        reveal = 1f,
        selection = null,
        viewport = viewport,
    )

    return hitTestable
        .mapNotNull { it.hitTest(probe, context, HitTestMode.NearestDomain) }
        .filter { seriesId == null || it.seriesId == seriesId }
        .minByOrNull { abs(coordinates.domainOf(it.position) - position) }
}

/** The step a selection currently sits at, or `0`. */
internal fun CartesianGeometry.stepOf(selection: AnyChartSelection?): Int {
    val current = selection ?: return 0
    val count = stepCount()
    if (count <= 0) return 0
    val fraction = fractionOf(current.x) ?: return 0
    return if (categoryCount > 0) {
        (fraction * count).toInt().coerceIn(0, count - 1)
    } else {
        Math.round(fraction * (count - 1)).toInt().coerceIn(0, count - 1)
    }
}
