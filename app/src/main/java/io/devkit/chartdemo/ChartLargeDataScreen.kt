package io.devkit.chartdemo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import io.devkit.chartkit.charts.ChartPerformance
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.data.ChartDownsampling
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.layer.line.PointMode
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.state.rememberChartViewportState
import io.devkit.chartkit.viewport.ChartViewport

/**
 * The same series at three sizes, under each sampling strategy.
 *
 * Everything on this screen is a control over what the chart *draws*; none of
 * it changes what the chart *knows*. Scrubbing at any setting still selects the
 * original reading nearest the finger, which is the property that makes
 * downsampling acceptable rather than merely fast.
 */
@Composable
fun ChartLargeDataScreen(modifier: Modifier = Modifier) {
    var size by rememberSaveable { mutableStateOf(10_000) }
    var strategy by rememberSaveable { mutableStateOf(0) }
    var cull by rememberSaveable { mutableStateOf(true) }
    var markers by rememberSaveable { mutableStateOf(false) }
    var animate by rememberSaveable { mutableStateOf(false) }
    var readout by rememberSaveable { mutableStateOf("Drag across the chart") }

    val viewport = rememberChartViewportState()
    val readings = remember(size) { ChartDemoData.denseReadings(size) }

    val performance = ChartPerformance(
        pointMarkerThreshold = if (markers) Int.MAX_VALUE else 0,
        maxAnimatedPoints = if (animate) Int.MAX_VALUE else 0,
        downsampling = when (strategy) {
            1 -> ChartDownsampling.None
            2 -> ChartDownsampling.MinMax(1_000)
            3 -> ChartDownsampling.Lttb(1_000)
            else -> ChartDownsampling.Auto()
        },
        cullToViewport = cull,
    )

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Large datasets", style = MaterialTheme.typography.titleLarge)
        Text(
            "${"%,d".format(readings.size)} readings, one a minute. Sampling and culling change " +
                "what is drawn; selection, tooltips and the accessibility summary continue to " +
                "refer to the original observations.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(1_000, 10_000, 50_000, 100_000).forEach { candidate ->
                FilterChip(
                    selected = size == candidate,
                    onClick = { size = candidate },
                    label = { Text("%,d".format(candidate)) },
                    modifier = Modifier.testTag("size-$candidate"),
                )
            }
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("Auto", "None", "Min/Max", "LTTB").forEachIndexed { index, label ->
                FilterChip(
                    selected = strategy == index,
                    onClick = { strategy = index },
                    label = { Text(label) },
                    modifier = Modifier.testTag("strategy-$index"),
                )
            }
        }

        Text(
            when (strategy) {
                1 -> "Every point is drawn. Correct, and the slowest — try it at 100,000."
                2 -> "Each bucket's minimum and maximum. Cannot lose a spike; looks noisier."
                3 -> "Largest-Triangle-Three-Buckets. Keeps the shape; can miss a lone spike."
                else -> "Sampled only when the data outruns the plot's width, at about two " +
                    "points per pixel."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = cull, onCheckedChange = { cull = it }, modifier = Modifier.testTag("cull"))
            Text("  Skip off-screen data when zoomed", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = markers, onCheckedChange = { markers = it }, modifier = Modifier.testTag("markers"))
            Text("  Force point markers", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = animate, onCheckedChange = { animate = it }, modifier = Modifier.testTag("animate"))
            Text("  Animate data changes", style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewport.viewport = ChartViewport.trailing(0.02) },
                modifier = Modifier.testTag("zoom-in"),
            ) { Text("Last 2%") }
            Button(onClick = { viewport.reset() }, modifier = Modifier.testTag("reset")) {
                Text("Reset")
            }
        }

        HorizontalDivider()

        LineChart(
            data = readings,
            x = { it.atMillis },
            y = { it.celsius },
            xResolver = ChartXResolver.Time,
            pointMode = if (markers) PointMode.Always else PointMode.Auto,
            performance = performance,
            interaction = ChartInteraction.Explorable,
            crosshair = CrosshairConfig.Vertical,
            viewportState = viewport,
            onSelectionChanged = { selection ->
                readout = selection?.let {
                    "Reading ${it.pointIndex} of ${"%,d".format(readings.size)} — " +
                        "${"%.2f".format(it.y)}"
                } ?: "Drag across the chart"
            },
            modifier = Modifier.fillMaxWidth().height(280.dp).testTag(CHART),
        )

        HorizontalDivider()
        Text(readout, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(READOUT))
        Text(
            "Zoom ${"%.1f".format(viewport.zoom)}×",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag(ZOOM),
        )
        Text(
            "The index in the readout is the position in the source list, not in the sampled " +
                "one — selection resolves against the whole series.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private const val CHART = "large-chart"
private const val READOUT = "large-readout"
private const val ZOOM = "large-zoom"
