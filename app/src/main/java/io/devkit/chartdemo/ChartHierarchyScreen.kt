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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.charts.HierarchyInteraction
import io.devkit.chartkit.charts.SunburstChart
import io.devkit.chartkit.charts.Treemap
import io.devkit.chartkit.components.breadcrumb.ChartBreadcrumbs
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.layer.hierarchy.SunburstLabels
import io.devkit.chartkit.layer.hierarchy.TreemapLabels
import io.devkit.chartkit.state.rememberHierarchyChartState

/**
 * Treemap and sunburst over one hierarchy — and one navigation state.
 *
 * The point of the screen is the last part. Both charts read the same
 * `ChartHierarchyState`, so drilling into Engineering in either one drills the
 * other, and the breadcrumb bar above them navigates both. Nothing here
 * synchronises anything: there is one piece of state and two charts observing
 * it.
 */
@Composable
fun ChartHierarchyScreen(modifier: Modifier = Modifier) {
    var showSunburst by rememberSaveable { mutableStateOf(true) }
    var drillOnTap by rememberSaveable { mutableStateOf(false) }
    var labels by rememberSaveable { mutableStateOf(true) }
    var readout by remember { mutableStateOf("Tap a rectangle") }

    val hierarchy = rememberHierarchyChartState()
    val money = remember { ChartNumberFormatters.compact() }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Hierarchy", style = MaterialTheme.typography.titleLarge)
        Text(
            "One normalised tree behind both charts. A treemap encodes value as area and a " +
                "sunburst encodes it as angle; the traversal, the identity, the aggregation, " +
                "the drill-down and the accessibility text are the same code.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = showSunburst,
                onCheckedChange = { showSunburst = it },
                modifier = Modifier.testTag("sunburst"),
            )
            Text("  Show the sunburst too", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = drillOnTap,
                onCheckedChange = { drillOnTap = it },
                modifier = Modifier.testTag("drill-on-tap"),
            )
            Text("  Tap drills in (otherwise double tap)", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = labels, onCheckedChange = { labels = it })
            Text("  Labels", style = MaterialTheme.typography.bodyMedium)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ChartBreadcrumbs(hierarchy, Modifier.weight(1f).testTag("breadcrumbs"))
            if (hierarchy.canDrillUp) {
                TextButton(onClick = { hierarchy.drillUp() }, modifier = Modifier.testTag("up")) {
                    Text("Up")
                }
            }
        }

        val interaction = if (drillOnTap) {
            HierarchyInteraction.DrillOnTap
        } else {
            HierarchyInteraction.DrillOnDoubleTap
        }

        Treemap(
            data = ChartDemoData.company,
            children = { it.teams },
            value = { it.revenue },
            label = { it.name },
            // Stable ids, so drilling and selection survive a data refresh.
            key = { it.id },
            maxDepth = 2,
            labels = if (labels) TreemapLabels.LabelAndPercentage else TreemapLabels.None,
            interaction = interaction,
            valueFormatter = money,
            hierarchyState = hierarchy,
            onSelectionChanged = { selection ->
                readout = selection?.item?.let { "${it.name}: ${money.format(selection.y)}" }
                    ?: "Tap a rectangle"
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .testTag("treemap"),
        )
        Text(readout, style = MaterialTheme.typography.bodyMedium)

        if (showSunburst) {
            HorizontalDivider()
            Text("The same tree, the same state", style = MaterialTheme.typography.titleMedium)
            SunburstChart(
                data = ChartDemoData.company,
                children = { it.teams },
                value = { it.revenue },
                label = { it.name },
                key = { it.id },
                maxDepth = 3,
                labels = if (labels) SunburstLabels.Label else SunburstLabels.None,
                interaction = interaction,
                valueFormatter = money,
                hierarchyState = hierarchy,
                centerContent = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            hierarchy.currentRoot?.label.orEmpty(),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            money.format(hierarchy.currentRoot?.value ?: 0.0),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .testTag("sunburst-chart"),
            )
        }

        HorizontalDivider()
        Text(
            "Double-tapping the centre of the sunburst, or empty space in the treemap, goes " +
                "back up a level — so the charts are navigable without the breadcrumb bar.",
            style = MaterialTheme.typography.bodySmall,
        )
        FilterChip(
            selected = false,
            onClick = { hierarchy.reset() },
            label = { Text("Reset to the whole company") },
            modifier = Modifier.testTag("reset"),
        )
    }
}
