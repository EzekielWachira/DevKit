package io.devkit.chartkit.components.legend

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.theme.ChartKitTheme

/** Where the legend sits relative to the plot. */
enum class LegendPosition {
    None,
    Top,
    Bottom,
    Start,
    End,
}

/** One row of the legend. */
data class ChartLegendEntry(
    val seriesId: String,
    val name: String,
    val color: Color,
    val visible: Boolean,
)

/**
 * The chart's legend.
 *
 * A real composable, not canvas drawing: legend entries are text that has to
 * wrap, scroll and — when toggling is on — be reachable by a screen reader and
 * by touch. All of that is what Compose already does well, and none of it is
 * something a `DrawScope` can do at all.
 *
 * A long legend wraps rather than overflowing, and a legend taller than
 * [maxHeightBeforeScroll] scrolls inside itself. Neither is decoration: twelve
 * series on a phone in portrait will otherwise push the plot out of the layout.
 *
 * @param onToggle called when an entry is tapped, or `null` for a display-only
 *   legend. When non-null the entries become toggleable and announce their
 *   state.
 */
@Composable
fun ChartLegend(
    entries: List<ChartLegendEntry>,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    onToggle: ((String) -> Unit)? = null,
) {
    if (entries.isEmpty()) return
    val dimensions = ChartKitTheme.dimensions

    if (vertical) {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(dimensions.labelPadding),
        ) {
            entries.forEach { entry -> LegendItem(entry, onToggle) }
        }
    } else {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(dimensions.legendItemSpacing),
            verticalArrangement = Arrangement.spacedBy(dimensions.labelPadding),
        ) {
            entries.forEach { entry -> LegendItem(entry, onToggle) }
        }
    }
}

@Composable
private fun LegendItem(entry: ChartLegendEntry, onToggle: ((String) -> Unit)?) {
    val dimensions = ChartKitTheme.dimensions
    val typography = ChartKitTheme.typography
    val colors = ChartKitTheme.colors

    val stateLabel = if (entry.visible) "shown" else "hidden"
    val base = Modifier.semantics {
        contentDescription = "${entry.name}, $stateLabel"
    }
    val interactive = if (onToggle != null) {
        base
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .toggleable(
                value = entry.visible,
                role = Role.Checkbox,
                onValueChange = { onToggle(entry.seriesId) },
            )
            .padding(horizontal = 2.dp, vertical = 2.dp)
    } else {
        base
    }

    Row(
        modifier = interactive,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensions.labelPadding),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(dimensions.legendIndicatorSize)
                .clip(CircleShape)
                // A hidden series keeps its slot and its colour, dimmed, so the
                // reader can see what they turned off and turn it back on.
                .background(if (entry.visible) entry.color else colors.emptyContent.copy(alpha = 0.3f)),
        )
        Text(
            text = entry.name,
            style = typography.legendLabel,
            color = if (entry.visible) colors.axisLabel else colors.emptyContent.copy(alpha = 0.6f),
        )
    }
}
