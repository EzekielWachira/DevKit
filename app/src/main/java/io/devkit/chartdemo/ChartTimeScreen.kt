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
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.charts.GanttChart
import io.devkit.chartkit.charts.RangeChart
import io.devkit.chartkit.charts.TimelineChart
import io.devkit.chartkit.formatter.ChartDateFormatters
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.layer.timeline.IntervalLabels
import io.devkit.chartkit.state.rememberChartViewportState

/**
 * Events, durations and tasks — one layer, three charts.
 *
 * A point event is an interval with no end and a task is an interval with a
 * progress overlay, so all three are the same geometry with different lambdas.
 * Because the time axis is an ordinary continuous domain axis, everything the
 * Cartesian engine does applies: pinch to zoom, drag to pan, a crosshair that
 * reads out the time under the pointer.
 */
@Composable
fun ChartTimeScreen(modifier: Modifier = Modifier) {
    var labels by remember { mutableStateOf(true) }
    var readout by remember { mutableStateOf("Tap a task") }
    val viewport = rememberChartViewportState()

    val dayFormat = remember { ChartDateFormatters.pattern("d MMM") }
    val timeFormat = remember { ChartDateFormatters.pattern("HH:mm") }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Timeline", style = MaterialTheme.typography.titleLarge)
        Text(
            "Point events in lanes. Two entries in one lane that overlap in time are stacked " +
                "onto separate rows rather than drawn over each other.",
            style = MaterialTheme.typography.bodyMedium,
        )

        TimelineChart(
            data = ChartDemoData.incidents,
            at = { it.startMillis },
            label = { it.name },
            lane = { it.stream },
            milestone = { it.milestone },
            timeAxis = ChartAxis(timeFormatter = timeFormat),
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .testTag("timeline"),
        )

        HorizontalDivider()
        Text("Durations", style = MaterialTheme.typography.titleMedium)
        Text(
            "The same layer with an end accessor. Not tied to project management: bookings, " +
                "shifts, machine uptime and appointments are the same shape.",
            style = MaterialTheme.typography.bodyMedium,
        )
        RangeChart(
            data = ChartDemoData.incidents,
            start = { it.startMillis },
            end = { it.endMillis },
            label = { it.name },
            lane = { it.stream },
            milestone = { it.milestone },
            labels = if (labels) IntervalLabels.Inside else IntervalLabels.None,
            timeAxis = ChartAxis(timeFormatter = timeFormat),
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .testTag("range"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = labels,
                onClick = { labels = !labels },
                label = { Text("Labels inside the bars") },
                modifier = Modifier.testTag("interval-labels"),
            )
        }

        HorizontalDivider()
        Text("Gantt", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tasks with progress, milestones and lanes. Pinch to zoom and drag to pan — the " +
                "time axis is the ordinary one, so the viewport works here as it does on a " +
                "line chart.",
            style = MaterialTheme.typography.bodyMedium,
        )
        GanttChart(
            data = ChartDemoData.roadmap,
            start = { it.startMillis },
            end = { it.endMillis },
            label = { it.name },
            lane = { it.stream },
            progress = { it.progress },
            milestone = { it.milestone },
            timeAxis = ChartAxis(timeFormatter = dayFormat),
            interaction = ChartInteraction.Explorable,
            viewportState = viewport,
            onSelectionChanged = { selection ->
                readout = selection?.item?.let { task ->
                    val done = task.progress?.let { " · ${Math.round(it * 100)}% complete" }.orEmpty()
                    "${task.name} (${task.stream})$done"
                } ?: "Tap a task"
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .testTag("gantt"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(readout, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (!viewport.isFullyZoomedOut) {
                TextButton(onClick = { viewport.reset() }, modifier = Modifier.testTag("reset-zoom")) {
                    Text("Reset zoom")
                }
            }
        }
    }
}
