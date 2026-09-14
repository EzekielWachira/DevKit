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
import io.devkit.chartkit.charts.FunnelChart
import io.devkit.chartkit.charts.ChordDiagram
import io.devkit.chartkit.charts.SankeyChart
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.layer.flow.FunnelLabels
import io.devkit.chartkit.layer.flow.ChordFlowSelection
import io.devkit.chartkit.layer.flow.ChordGroupSelection
import io.devkit.chartkit.layer.flow.ChordLabels
import io.devkit.chartkit.layer.flow.SankeyLabels
import io.devkit.chartkit.layer.flow.SankeyLinkSelection
import io.devkit.chartkit.layer.flow.SankeyNodeSelection
import io.devkit.chartkit.transform.FunnelTransform

/**
 * Flows and drop-off: where people go, and where they stop.
 *
 * The Sankey's selection is the part worth watching. Tapping a node emphasises
 * every flow touching it and lists them in the tooltip; tapping a band selects
 * that one flow. Both hand back a typed selection carrying the caller's own
 * object.
 */
@Composable
fun ChartFlowScreen(modifier: Modifier = Modifier) {
    var labels by rememberSaveable { mutableStateOf(true) }
    var readout by remember { mutableStateOf("Tap a node or a flow") }
    var funnelLabels by rememberSaveable { mutableStateOf(FunnelLabels.LabelAndConversion.name) }

    val count = remember { ChartNumberFormatters.integer() }
    val stages = remember {
        FunnelTransform.resolve(
            data = ChartDemoData.funnelSteps,
            label = { it.name },
            value = { it.users },
        )
    }

    var chordValues by remember { mutableStateOf(false) }
    var chordReadout by remember { mutableStateOf("Tap a group or a ribbon") }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Flow", style = MaterialTheme.typography.titleLarge)
        Text(
            "A Sankey diagram's claim is that a band's width is its weight, so the links are " +
                "filled ribbons rather than strokes. Columns come from each node's distance " +
                "from a source; a link that closed a cycle would have no column, so it is cut " +
                "and reported rather than laid out arbitrarily.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = labels,
                onClick = { labels = !labels },
                label = { Text("Node labels") },
                modifier = Modifier.testTag("sankey-labels"),
            )
        }

        SankeyChart(
            nodes = ChartDemoData.flowStages,
            links = ChartDemoData.flowSteps,
            nodeId = { it.id },
            nodeLabel = { it.name },
            source = { it.from },
            target = { it.to },
            value = { it.users },
            labels = if (labels) SankeyLabels.Outside else SankeyLabels.None,
            valueFormatter = count,
            onSelectionChanged = { selection ->
                readout = when (val item = selection?.item) {
                    is SankeyNodeSelection ->
                        "${item.label}: ${count.format(selection.y)} through"

                    is SankeyLinkSelection ->
                        "${item.sourceLabel} → ${item.targetLabel}: ${count.format(selection.y)}"

                    else -> "Tap a node or a flow"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .testTag("sankey"),
        )
        Text(readout, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("flow-readout"))

        HorizontalDivider()
        Text("Chord", style = MaterialTheme.typography.titleMedium)
        Text(
            "The same claim as a Sankey — width is weight — with no left and no right. A " +
                "circle has no upstream, so two groups can exchange in both directions and a " +
                "group can flow into itself; Europe's loop back to Europe is internal " +
                "movement. Each group's arc is everything touching it, in and out, so a " +
                "region that mostly receives is still drawn at the size of what it receives.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = chordValues,
                onClick = { chordValues = !chordValues },
                label = { Text("Group totals") },
                modifier = Modifier.testTag("chord-values"),
            )
        }

        ChordDiagram(
            groups = ChartDemoData.regions,
            flows = ChartDemoData.migrations,
            groupId = { it.code },
            groupLabel = { it.name },
            source = { it.from },
            target = { it.to },
            value = { it.people },
            labels = if (chordValues) ChordLabels.OutsideWithValue else ChordLabels.Outside,
            valueFormatter = count,
            onSelectionChanged = { selection ->
                chordReadout = when (val item = selection?.item) {
                    is ChordGroupSelection ->
                        "${item.label}: ${count.format(selection.y)} in and out"

                    is ChordFlowSelection ->
                        "${item.sourceLabel} \u2192 ${item.targetLabel}: ${count.format(selection.y)}"

                    else -> "Tap a group or a ribbon"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .testTag("chord"),
        )
        Text(
            chordReadout,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("chord-readout"),
        )

        HorizontalDivider()
        Text("Funnel", style = MaterialTheme.typography.titleMedium)
        Text(
            "Each stage narrows toward the next, so the slope between two bands is the " +
                "drop-off. The absolute count, the share of the first stage, the conversion " +
                "and the number lost are all computed whether or not they are drawn.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                FunnelLabels.LabelAndValue,
                FunnelLabels.LabelAndPercentage,
                FunnelLabels.LabelAndConversion,
            ).forEach { option ->
                FilterChip(
                    selected = funnelLabels == option.name,
                    onClick = { funnelLabels = option.name },
                    label = {
                        Text(
                            when (option) {
                                FunnelLabels.LabelAndValue -> "Count"
                                FunnelLabels.LabelAndPercentage -> "Of first"
                                else -> "Conversion"
                            },
                        )
                    },
                    modifier = Modifier.testTag("funnel-${option.name}"),
                )
            }
        }

        FunnelChart(
            data = ChartDemoData.funnelSteps,
            label = { it.name },
            value = { it.users },
            labels = FunnelLabels.valueOf(funnelLabels),
            valueFormatter = count,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .testTag("funnel"),
        )

        val overall = FunnelTransform.overallConversion(stages)
        Text(
            "Overall conversion: ${overall?.let { "${Math.round(it * 100)}%" } ?: "—"}. " +
                if (FunnelTransform.isMonotonic(stages)) {
                    "Every stage is smaller than the one before it."
                } else {
                    "One stage grew — reported as it is, not clamped."
                },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
