package io.devkit.chartkit.components.tooltip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.theme.ChartKitTheme
import kotlin.math.roundToInt

/**
 * The default tooltip: series, x and value, on a Material-style surface.
 *
 * Overridable wholesale — every chart takes a `tooltip` slot receiving the same
 * [ChartSelection], including the caller's own data object — so ChartKit's
 * tooltip is a default rather than a constraint:
 *
 * ```kotlin
 * tooltip = { selection ->
 *     Card { Text("${selection.item.customerName}: ${selection.y}") }
 * }
 * ```
 *
 * A composable and not canvas drawing, because a tooltip is text and shape,
 * both of which Compose measures better than a `DrawScope` can — and because a
 * caller's replacement has to be able to contain arbitrary UI.
 */
@Composable
fun <T> ChartTooltip(
    selection: ChartSelection<T>,
    modifier: Modifier = Modifier,
    valueFormatter: ChartValueFormatter? = null,
    showSeriesName: Boolean = true,
) {
    val colors = ChartKitTheme.colors
    val typography = ChartKitTheme.typography
    val dimensions = ChartKitTheme.dimensions
    val formatter = valueFormatter ?: ChartValueFormatter.Raw

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(dimensions.tooltipCornerRadius))
            .background(colors.tooltipContainer)
            .padding(dimensions.tooltipPadding),
    ) {
        if (showSeriesName) {
            Text(
                text = selection.seriesName,
                style = typography.tooltipTitle,
                color = colors.tooltipContent,
            )
        }
        Text(
            text = selection.xLabel,
            style = typography.tooltipValue,
            color = colors.tooltipContent,
        )
        Text(
            text = formatter.format(selection.y),
            style = typography.tooltipTitle,
            color = colors.tooltipContent,
        )
    }
}

/**
 * Where a tooltip of [tooltipWidth] × [tooltipHeight] should be placed so that
 * it points at [anchorX], [anchorY] and stays inside [bounds].
 *
 * Measured placement rather than a fixed offset. A tooltip nudged "16dp up and
 * to the right" is off-screen for any selection near the top-right corner, and
 * that is exactly where the highest value in a rising series is.
 *
 * Preference order: above the anchor, then below it if there is no room, then
 * clamped. Horizontally it is centred and then pulled back inside the bounds,
 * so it never leaves the chart even at the first or last point.
 */
internal fun tooltipOffset(
    anchorX: Float,
    anchorY: Float,
    tooltipWidth: Int,
    tooltipHeight: Int,
    bounds: ChartRect,
    gap: Float,
): IntOffset {
    if (!anchorX.isFinite() || !anchorY.isFinite()) return IntOffset(0, 0)

    val above = anchorY - tooltipHeight - gap
    val below = anchorY + gap
    val top = when {
        above >= bounds.top -> above
        below + tooltipHeight <= bounds.bottom -> below
        else -> (bounds.bottom - tooltipHeight).coerceAtLeast(bounds.top)
    }

    val left = (anchorX - tooltipWidth / 2f)
        .coerceIn(
            bounds.left,
            // `coerceAtLeast(bounds.left)`: a tooltip wider than the chart has
            // no valid range, and `coerceIn` with min > max throws.
            (bounds.right - tooltipWidth).coerceAtLeast(bounds.left),
        )

    return IntOffset(left.roundToInt(), top.roundToInt())
}
