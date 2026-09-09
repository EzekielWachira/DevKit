package io.devkit.chartkit.components.legend

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.scale.bandBreaks
import io.devkit.chartkit.scale.bandRangeLabel
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * A legend for a [ColorScale] — what the shading on a map or a heatmap means.
 *
 * ```text
 * continuous:  ▐▓▓▒▒░░▌  0 ─────────── 100
 * banded:      ▪ 0–20  ▪ 20–40  ▪ 40–60  ▪ 60–80  ▪ 80+
 * ```
 *
 * Two forms, chosen from the scale itself rather than from a parameter,
 * because the scale already knows which it is. A gradient bar for a scale that
 * genuinely varies continuously, and discrete swatches for one that puts values
 * into bands — drawing a quantile scale as a smooth ramp would tell the reader
 * that the colour halfway between two swatches means something, and it does
 * not.
 *
 * ### Missing data gets a swatch too
 *
 * When [missingLabel] is set, the "no data" colour is shown alongside the
 * scale. Regions painted with it are the ones the reader is most likely to
 * misread as "low", and an unexplained grey on a map is a question the legend
 * should answer.
 *
 * @param formatter how band boundaries and end labels are written.
 */
@Composable
fun ChartColorLegend(
    scale: ColorScale,
    modifier: Modifier = Modifier,
    formatter: ChartValueFormatter = ChartNumberFormatters.compact(),
    title: String? = null,
    missingLabel: String? = null,
) {
    val stops = scale.legendStops()
    if (stops.isEmpty() && missingLabel == null) return

    val dimensions = ChartKitTheme.dimensions
    val typography = ChartKitTheme.typography
    val colors = ChartKitTheme.colors

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(dimensions.labelPadding),
    ) {
        if (title != null) {
            Text(text = title, style = typography.legendLabel, color = colors.axisLabel)
        }

        when (scale) {
            is ColorScale.Continuous -> ContinuousLegend(scale, formatter)
            else -> BandedLegend(scale, stops, formatter)
        }

        if (missingLabel != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimensions.labelPadding),
            ) {
                Box(
                    modifier = Modifier
                        .size(dimensions.legendIndicatorSize)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.geo.missing),
                )
                Text(
                    text = missingLabel,
                    style = typography.legendLabel,
                    color = colors.axisLabel,
                )
            }
        }
    }
}

/**
 * A gradient bar with its two ends labelled.
 *
 * The ends and not every stop: a continuous ramp has no meaningful interior
 * ticks, and printing five numbers under a gradient invites the reader to read
 * a value off a colour, which a ramp does not support accurately. The tooltip
 * is where an exact value comes from.
 */
@Composable
private fun ContinuousLegend(scale: ColorScale.Continuous, formatter: ChartValueFormatter) {
    val dimensions = ChartKitTheme.dimensions
    val typography = ChartKitTheme.typography
    val colors = ChartKitTheme.colors
    val stops = scale.legendStops()
    val ramp = stops.map { it.color }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(
            modifier = Modifier
                .width(dimensions.colorLegendBarWidth)
                .height(dimensions.colorLegendBarHeight)
                .clip(RoundedCornerShape(2.dp))
                // One description for the whole bar. A screen reader announcing
                // each of the gradient's samples would read out a list of
                // colours, which tells a non-sighted reader nothing.
                .clearAndSetSemantics {
                    contentDescription = "Colour scale from " +
                        "${formatter.format(scale.domain.min)} to " +
                        formatter.format(scale.domain.max)
                },
        ) {
            if (ramp.size >= 2) {
                drawRect(brush = Brush.horizontalGradient(ramp))
            } else if (ramp.size == 1) {
                drawRect(color = ramp.first())
            }
        }
        Row(
            modifier = Modifier.width(dimensions.colorLegendBarWidth),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatter.format(scale.domain.min),
                style = typography.legendLabel,
                color = colors.axisLabel,
            )
            Text(
                text = formatter.format(scale.domain.max),
                style = typography.legendLabel,
                color = colors.axisLabel,
            )
        }
    }
}

/**
 * One swatch per band, labelled with the interval it covers.
 *
 * Wrapped in a [FlowRow] rather than fixed to one line: five quantile bands
 * labelled with real numbers do not fit across a phone in portrait, and a
 * legend that clips is a legend that lies.
 */
@Composable
private fun BandedLegend(
    scale: ColorScale,
    stops: List<io.devkit.chartkit.scale.ColorStop>,
    formatter: ChartValueFormatter,
) {
    val dimensions = ChartKitTheme.dimensions
    val typography = ChartKitTheme.typography
    val colors = ChartKitTheme.colors

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(dimensions.legendItemSpacing),
        verticalArrangement = Arrangement.spacedBy(dimensions.labelPadding),
    ) {
        // Relabelled with the caller's formatter where the scale can say what
        // its bands are, so a legend of currency reads as currency. A scale
        // that carries its own names — a threshold scale's "critical" — keeps
        // them, because a number would be a worse label than the word.
        val breaks = scale.bandBreaks()
        val named = scale is ColorScale.Threshold && scale.labels.isNotEmpty()

        stops.forEachIndexed { index, stop ->
            val label = when {
                named -> stop.label ?: formatter.format(stop.value)
                breaks.isNotEmpty() ->
                    bandRangeLabel(index, stops.size, breaks, formatter::format)

                else -> stop.label ?: formatter.format(stop.value)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimensions.labelPadding),
            ) {
                Box(
                    modifier = Modifier
                        .size(dimensions.legendIndicatorSize)
                        .clip(RoundedCornerShape(2.dp))
                        .background(stop.color),
                )
                Text(text = label, style = typography.legendLabel, color = colors.axisLabel)
            }
        }
    }
}
