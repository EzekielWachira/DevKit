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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.charts.ParallelCoordinatesChart
import io.devkit.chartkit.charts.ParallelDimension
import io.devkit.chartkit.charts.RadarChart
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.layer.polar.RadarNormalization
import io.devkit.chartkit.layer.comparison.ParallelRowSelection
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.state.rememberParallelBrushState

/**
 * Radar charts, on the same polar coordinates the pie charts use.
 *
 * The normalisation switch is the one that matters: per-axis scaling is right
 * for metrics that are not comparable, and a shared scale is right when they
 * are — and choosing wrongly is the usual way a radar chart misleads.
 */
@Composable
fun ChartRadarScreen(modifier: Modifier = Modifier) {
    var compare by rememberSaveable { mutableStateOf(true) }
    var shared by rememberSaveable { mutableStateOf(true) }
    var filled by rememberSaveable { mutableStateOf(true) }
    var readout by rememberSaveable { mutableStateOf("Tap a vertex") }
    val brushes = rememberParallelBrushState()
    var parallelReadout by rememberSaveable { mutableStateOf("Tap a line, or drag an axis") }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Radar", style = MaterialTheme.typography.titleLarge)
        Text(
            "A polar chart whose radius carries a value and whose angle carries a category. " +
                "It reuses PolarCoordinates, the polar layout and the polar interaction model — " +
                "the same ones behind pie, donut and radial bar.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = compare,
                onCheckedChange = { compare = it },
                modifier = Modifier.testTag("compare"),
            )
            Text("  Compare two quarters", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = filled,
                onCheckedChange = { filled = it },
                modifier = Modifier.testTag("filled"),
            )
            Text("  Fill the polygons", style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = shared,
                onClick = { shared = true },
                label = { Text("Shared 0–100") },
                modifier = Modifier.testTag("shared-scale"),
            )
            FilterChip(
                selected = !shared,
                onClick = { shared = false },
                label = { Text("Per axis") },
                modifier = Modifier.testTag("per-axis-scale"),
            )
        }
        Text(
            if (shared) {
                "One scale for every spoke. Right here, because all six ratings are out of 100 " +
                    "— so the polygon's shape means something."
            } else {
                "Each spoke scaled to its own metric's range. Right when the metrics are not " +
                    "comparable, and the reason the area of a radar polygon is never a total."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        RadarChart(
            series = buildList {
                add(ChartSeries("q2", "This quarter", ChartDemoData.profileThisQuarter))
                if (compare) {
                    add(ChartSeries("q1", "Last quarter", ChartDemoData.profileLastQuarter))
                }
            },
            metric = { it.aspect },
            value = { it.score },
            normalization = if (shared) RadarNormalization.Shared else RadarNormalization.PerAxis,
            valueRange = if (shared) 0.0..100.0 else null,
            filled = filled,
            legend = LegendPosition.Bottom,
            onSelectionChanged = { selection ->
                readout = selection?.let { "${it.seriesName} — ${it.xLabel}: ${it.y.toInt()}" }
                    ?: "Tap a vertex"
            },
            modifier = Modifier.fillMaxWidth().height(360.dp).testTag(CHART),
        )

        HorizontalDivider()
        Text(readout, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(READOUT))

        HorizontalDivider()
        Text("Parallel coordinates", style = MaterialTheme.typography.titleMedium)
        Text(
            "The other multivariate view, and the one that keeps working past six metrics. " +
                "Every axis carries its own scale, because the units differ — so height is " +
                "comparable down an axis and not across them. Drag down an axis to filter to " +
                "a range; the excluded rows are muted rather than removed, so the subset is " +
                "read against the whole. Tap a brushed axis to clear it, or the chip " +
                "above to clear them all.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = !brushes.isEmpty,
                onClick = { brushes.clearAll() },
                label = { Text(if (brushes.isEmpty) "No filters" else "Clear ${brushes.activeCount}") },
                modifier = Modifier.testTag("parallel-clear"),
            )
        }
        ParallelCoordinatesChart(
            data = ChartDemoData.cars,
            dimensions = listOf(
                ParallelDimension("Price") { it.price },
                ParallelDimension("MPG") { it.economy },
                ParallelDimension("Power") { it.power },
                ParallelDimension("Weight") { it.weight },
                ParallelDimension("Range") { it.range },
            ),
            group = { it.origin },
            brushState = brushes,
            onSelectionChanged = { selection ->
                parallelReadout = (selection?.item as? ParallelRowSelection)?.let { row ->
                    ChartDemoData.cars.getOrNull(row.rowIndex)?.let { car ->
                        "${car.name} (${car.origin})"
                    }
                } ?: "Tap a line, or drag an axis"
            },
            modifier = Modifier.fillMaxWidth().height(320.dp).testTag("parallel"),
        )
        Text(
            parallelReadout,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("parallel-readout"),
        )
    }
}

private const val CHART = "radar-chart"
private const val READOUT = "radar-readout"
