package io.devkit.chartdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.BarChart
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.interaction.ChartSelectionMode
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.state.rememberChartState

/**
 * Selection, tooltips and scrubbing, with the selection state hoisted so the
 * screen can show what the chart reported.
 *
 * The tooltip on the second chart is the sample's own composable receiving the
 * sample's own `Revenue` object — the check that a selection really does carry
 * the caller's data rather than a copy of two numbers.
 */
@Composable
fun ChartInteractionScreen(modifier: Modifier = Modifier) {
    var lastTap by remember { mutableStateOf<ChartSelection<ChartDemoData.Revenue>?>(null) }
    val scrubState = rememberChartState<ChartDemoData.Revenue>()
    val money = remember { ChartNumberFormatters.compact() }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Interaction", style = MaterialTheme.typography.headlineSmall)

        Text("Tap a bar", style = MaterialTheme.typography.titleSmall)
        Text(
            "The callback receives a typed selection carrying the original Revenue object.",
            style = MaterialTheme.typography.bodySmall,
        )
        BarChart(
            data = ChartDemoData.revenue,
            category = { it.month },
            value = { it.amount },
            valueFormatter = money,
            animation = ChartAnimation.Default,
            onSelectionChanged = { lastTap = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .testTag(ChartDemoTestTags.Chart),
        )
        SelectionReadout(
            label = "Tapped",
            selection = lastTap,
            modifier = Modifier.testTag(ChartDemoTestTags.Selection),
        )

        HorizontalDivider()

        Text("Scrub a line", style = MaterialTheme.typography.titleSmall)
        Text(
            "Drag across the plot. The selection follows the nearest month, and a guide " +
                "line marks it. Dragging is detected horizontally only, so this screen still " +
                "scrolls vertically.",
            style = MaterialTheme.typography.bodySmall,
        )
        LineChart(
            data = ChartDemoData.revenue,
            x = { it.month },
            y = { it.amount },
            valueFormatter = money,
            selectionMode = ChartSelectionMode.TapAndScrub,
            state = scrubState,
            animation = ChartAnimation.Default,
            // A custom tooltip, given the sample's own type.
            tooltip = { selection ->
                Card(shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(selection.item.month, fontWeight = FontWeight.SemiBold)
                        Text(money.format(selection.item.amount))
                        Text(
                            "point ${selection.pointIndex + 1} of ${ChartDemoData.revenue.size}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(220.dp),
        )
        SelectionReadout(label = "Scrubbed", selection = scrubState.selection)

        HorizontalDivider()

        Text("Legend toggling", style = MaterialTheme.typography.titleSmall)
        Text(
            "Tapping a legend entry hides its series. Hidden series keep their palette slot, " +
                "so nothing else changes colour.",
            style = MaterialTheme.typography.bodySmall,
        )
        val legendState = rememberChartState<ChartDemoData.Revenue>()
        LineChart(
            series = ChartDemoData.revenueSeries(),
            x = { it.month },
            y = { it.amount },
            valueFormatter = money,
            legendTogglesSeries = true,
            state = legendState,
            animation = ChartAnimation.Default,
            modifier = Modifier.fillMaxWidth().height(240.dp),
        )
        Text(
            text = if (legendState.hiddenSeriesIds.isEmpty()) {
                "All series shown"
            } else {
                "Hidden: ${legendState.hiddenSeriesIds.sorted().joinToString()}"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SelectionReadout(
    label: String,
    selection: ChartSelection<ChartDemoData.Revenue>?,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
        text = selection?.let {
            "$label: ${it.item.month} — ${it.item.amount} (series ${it.seriesName.ifBlank { "default" }}, " +
                "index ${it.pointIndex})"
        } ?: "$label: nothing selected yet",
        style = MaterialTheme.typography.bodySmall,
    )
}
