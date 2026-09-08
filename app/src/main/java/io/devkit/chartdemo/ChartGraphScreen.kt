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
import io.devkit.chartkit.accessibility.ChartDataTableView
import io.devkit.chartkit.accessibility.graphDataTable
import io.devkit.chartkit.charts.NetworkGraph
import io.devkit.chartkit.graph.GraphLayout
import io.devkit.chartkit.graph.GraphLayoutStrategy
import io.devkit.chartkit.graph.buildChartGraph
import io.devkit.chartkit.layer.graph.GraphLabels
import io.devkit.chartkit.layer.graph.GraphEdgeSelection
import io.devkit.chartkit.layer.graph.GraphNodeSelection
import io.devkit.chartkit.state.rememberGraphLayoutState
import io.devkit.chartkit.state.rememberPlanarViewportState

/**
 * A service dependency graph, laid out two ways.
 *
 * The force layout is seeded, so the same graph draws the same picture every
 * time; it steps on a background dispatcher and stops as soon as it settles.
 * Dragging a node pins it and lets the neighbourhood rearrange around it.
 */
@Composable
fun ChartGraphScreen(modifier: Modifier = Modifier) {
    var force by remember { mutableStateOf(true) }
    var labelAll by remember { mutableStateOf(false) }
    var showTable by remember { mutableStateOf(false) }
    var readout by remember { mutableStateOf("Tap a service") }

    val layout = rememberGraphLayoutState()
    val viewport = rememberPlanarViewportState()

    layout.strategy = if (force) {
        GraphLayoutStrategy.ForceDirected()
    } else {
        GraphLayoutStrategy.Circular(GraphLayout.ByDegree)
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Relationships", style = MaterialTheme.typography.titleLarge)
        Text(
            "Nodes sized by request volume and coloured by selection. Both layouts are pure " +
                "Kotlin over parallel position arrays — the consumer's own list is never " +
                "written to, so a graph can be laid out from an immutable model.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = force,
                onClick = { force = true },
                label = { Text("Force directed") },
                modifier = Modifier.testTag("force"),
            )
            FilterChip(
                selected = !force,
                onClick = { force = false },
                label = { Text("Circular") },
                modifier = Modifier.testTag("circular"),
            )
            FilterChip(
                selected = labelAll,
                onClick = { labelAll = !labelAll },
                label = { Text("Label everything") },
                modifier = Modifier.testTag("labels"),
            )
        }

        NetworkGraph(
            nodes = ChartDemoData.services,
            edges = ChartDemoData.serviceCalls,
            nodeId = { it.name },
            source = { it.from },
            target = { it.to },
            nodeWeight = { it.requests },
            edgeWeight = { it.calls },
            labels = if (labelAll) GraphLabels.All else GraphLabels.Selected,
            layoutState = layout,
            viewportState = viewport,
            onSelectionChanged = { selection ->
                readout = when (val item = selection?.item) {
                    is GraphNodeSelection ->
                        "${item.label}: ${item.degree} connections — " +
                            item.neighbours.joinToString(", ")

                    is GraphEdgeSelection -> "${item.sourceLabel} → ${item.targetLabel}"
                    else -> "Tap a service"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .testTag("graph"),
        )
        Text(readout, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("graph-readout"))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { layout.releaseAll() }, modifier = Modifier.testTag("release")) {
                Text("Release pinned nodes")
            }
            TextButton(onClick = { layout.restart() }) { Text("Restart layout") }
            TextButton(onClick = { viewport.reset() }) { Text("Reset view") }
        }
        Text(
            if (layout.isSettled) "Layout settled." else "Layout still moving…",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = showTable,
                onClick = { showTable = !showTable },
                label = { Text("Data table") },
                modifier = Modifier.testTag("graph-table"),
            )
        }
        if (showTable) {
            // A graph is the case where a summary genuinely cannot carry the
            // content: the table is how a reader gets at the connections.
            val graph = remember {
                buildChartGraph(
                    nodes = ChartDemoData.services,
                    edges = ChartDemoData.serviceCalls,
                    nodeId = { it.name },
                    source = { it.from },
                    target = { it.to },
                    nodeWeight = { it.requests },
                )
            }
            ChartDataTableView(graphDataTable(graph, caption = "Service dependencies"))
        }
    }
}
