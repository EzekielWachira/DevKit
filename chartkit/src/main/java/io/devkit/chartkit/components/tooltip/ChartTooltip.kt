package io.devkit.chartkit.components.tooltip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * The default tooltip.
 *
 * Shows the domain value as a heading and one line per series beneath it, so
 * the same composable serves a single tapped bar and a crosshair over four
 * lines:
 *
 * ```text
 * Jan                 Jan
 * 24,000              ● Revenue    30,000
 *                     ● Expenses   21,000
 *                     ● Profit      9,000
 * ```
 *
 * Overridable wholesale — every chart takes a `tooltip` slot receiving the same
 * [ChartTooltipData], including the caller's own data objects — so this is a
 * default rather than a constraint:
 *
 * ```kotlin
 * tooltip = { data ->
 *     Card { Text("${data.item.customerName}: ${data.selection.y}") }
 * }
 * ```
 *
 * A composable and not canvas drawing, because a tooltip is text and shape,
 * both of which Compose measures better than a `DrawScope` can — and because a
 * caller's replacement has to be able to contain arbitrary UI.
 */
@Composable
fun <T> ChartTooltip(
    data: ChartTooltipData<T>,
    modifier: Modifier = Modifier,
    valueFormatter: ChartValueFormatter? = null,
    showSeriesNames: Boolean = data.isMultiSeries,
) {
    val colors = ChartKitTheme.colors
    val typography = ChartKitTheme.typography
    val dimensions = ChartKitTheme.dimensions
    // The caller's formatter if they gave one, otherwise the chart's own axis
    // formatter — never a raw dump of the double.
    val formatter = valueFormatter ?: data.valueFormatter

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(dimensions.tooltipCornerRadius))
            .background(colors.tooltipContainer)
            .padding(dimensions.tooltipPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = data.xLabel,
            style = typography.tooltipTitle,
            color = colors.tooltipContent,
        )
        data.entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showSeriesNames) {
                    // A swatch as well as the name: on a four-series tooltip the
                    // colour is how a reader ties a line to its number, and the
                    // name is how they read it when the two colours are close.
                    Spacer(
                        Modifier
                            .size(dimensions.legendIndicatorSize * 0.7f)
                            .clip(CircleShape)
                            .background(colors.seriesColor(entry.paletteIndex)),
                    )
                    Spacer(Modifier.width(dimensions.labelPadding))
                    Text(
                        text = entry.seriesName.ifBlank { entry.seriesId },
                        style = typography.tooltipValue,
                        color = colors.tooltipContent,
                    )
                    Spacer(Modifier.width(dimensions.legendItemSpacing))
                }
                Text(
                    // The entry's own axis wrote this — `82 mm` beside `14.2 °C`
                    // beside `1,018 hPa`. A single chart-wide formatter would
                    // write two of those three wrong, which is the whole
                    // difference between a multi-axis tooltip and a list of
                    // numbers. A caller's explicit formatter still wins.
                    text = when {
                        valueFormatter != null -> valueFormatter.format(entry.value)
                        entry.formattedValue != null -> entry.formattedValue
                        else -> formatter.format(entry.value)
                    },
                    style = typography.tooltipTitle,
                    color = colors.tooltipContent,
                )
            }
        }
        // A polar selection knows its share of the whole, which is the number a
        // reader of a pie chart is usually after and one no Cartesian
        // selection has.
        data.selection.polar?.let { polar ->
            Text(
                text = PERCENT_FORMAT.format(polar.fraction * 100.0) + "%",
                style = typography.tooltipValue,
                color = colors.tooltipContent,
            )
        }
    }
}

private val PERCENT_FORMAT = java.text.DecimalFormat("0.#")
