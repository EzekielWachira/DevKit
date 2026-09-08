package io.devkit.chartdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.charts.BarChart
import io.devkit.chartkit.charts.ChartNavigator
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.charts.VolumeChart
import io.devkit.chartkit.charts.CandlestickChart
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.state.ChartFilter
import io.devkit.chartkit.state.rememberChartFilterState
import io.devkit.chartkit.state.rememberChartInteractionGroup

/**
 * A dashboard: linked viewports, a shared crosshair, aligned plots, an overview
 * navigator and cross-filtering.
 *
 * ### What is shared, and what is not
 *
 * The candlestick and volume charts share a viewport, a crosshair position and
 * a plot alignment. They do **not** share a selection: a price of 182 and a
 * volume of 4.1 million are not the same selection, and asserting they were
 * would be the chart lying about what the reader did.
 *
 * ### Cross-filtering is coordinated, not performed
 *
 * Tapping a region publishes a `ChartFilter`; this screen then filters its own
 * list with its own semantics. ChartKit never touches the data — "filter orders
 * by region" means joining a table in one app and re-querying a server in
 * another.
 */
@Composable
fun ChartDashboardScreen(modifier: Modifier = Modifier) {
    val group = rememberChartInteractionGroup()
    val filters = rememberChartFilterState()
    var align by remember { mutableStateOf(true) }
    var navigator by remember { mutableStateOf(true) }

    val money = remember { ChartNumberFormatters.compact() }
    val chartId = remember { Any() }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Linked charts", style = MaterialTheme.typography.titleLarge)
        Text(
            "One viewport state and one crosshair position, read by both charts. Drag either " +
                "one and both move; the crosshair is shared as a domain value, so the guides " +
                "land on the same date rather than the same pixel.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = align,
                onCheckedChange = { align = it },
                modifier = Modifier.testTag("align"),
            )
            Text("  Align the plot edges", style = MaterialTheme.typography.bodyMedium)
        }

        val alignment = if (align) group.alignment else null

        CandlestickChart(
            data = ChartDemoData.prices,
            x = { it.timeMillis },
            open = { it.open },
            high = { it.high },
            low = { it.low },
            close = { it.close },
            interaction = ChartInteraction.Explorable,
            viewportState = group.viewport,
            sharedCrosshair = group.crosshair,
            plotAlignment = alignment,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .testTag("price"),
        )
        VolumeChart(
            data = ChartDemoData.prices,
            x = { it.timeMillis },
            volume = { it.volume },
            open = { it.open },
            close = { it.close },
            interaction = ChartInteraction.Explorable,
            viewportState = group.viewport,
            sharedCrosshair = group.crosshair,
            plotAlignment = alignment,
            yAxis = ChartAxis(valueFormatter = money),
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .testTag("volume"),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = navigator,
                onCheckedChange = { navigator = it },
                modifier = Modifier.testTag("navigator-toggle"),
            )
            Text("  Overview navigator", style = MaterialTheme.typography.bodyMedium)
        }
        if (navigator) {
            Text(
                "Drag the window, drag its edges, or tap to recentre. It writes the same " +
                    "viewport state the charts above read — there is no second viewport.",
                style = MaterialTheme.typography.bodySmall,
            )
            ChartNavigator(
                data = ChartDemoData.prices,
                x = { it.timeMillis },
                y = { it.close },
                viewportState = group.viewport,
                xResolver = ChartXResolver.Time,
                modifier = Modifier.fillMaxWidth().testTag("navigator"),
            )
        }
        if (!group.viewport.isFullyZoomedOut) {
            TextButton(
                onClick = { group.viewport.reset() },
                modifier = Modifier.testTag("reset-viewport"),
            ) {
                Text("Show the whole period")
            }
        }

        HorizontalDivider()
        Text("Cross-filtering", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tapping a bar publishes a selection; this screen filters its own data and the " +
                "line chart redraws. Tapping the same bar again clears it.",
            style = MaterialTheme.typography.bodyMedium,
        )

        BarChart(
            data = ChartDemoData.productLines,
            category = { it.month },
            value = { it.amount },
            valueAxis = ChartAxis(valueFormatter = money),
            onSelectionChanged = { selection ->
                filters.toggle(
                    ChartFilter(
                        dimension = "product",
                        key = selection?.item?.month,
                        label = selection?.item?.month,
                        source = chartId,
                    ),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .testTag("filter-source"),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filters.filters.forEach { filter ->
                FilterChip(
                    selected = true,
                    onClick = { filters.toggle(filter) },
                    label = { Text(filter.label ?: filter.key.toString()) },
                )
            }
            if (filters.isEmpty) {
                Text("No filter — showing everything", style = MaterialTheme.typography.bodySmall)
            }
        }

        val filtered = remember(filters.filters) {
            filters.apply(ChartDemoData.revenue, "product") { it.month }
        }
        LineChart(
            data = filtered.ifEmpty { ChartDemoData.revenue },
            x = { it.month },
            y = { it.amount },
            yAxis = ChartAxis(valueFormatter = money),
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .testTag("filter-target"),
        )
    }
}
