package io.devkit.chartdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.formatter.ChartDateFormatters
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.interaction.ChartDragMode
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.model.ChartRangeSelection
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.state.rememberChartViewportState
import io.devkit.chartkit.viewport.ChartViewport
import kotlinx.coroutines.launch

/**
 * Zoom, pan, crosshair and range selection on one long time series.
 *
 * Two years of daily readings: at full extent the line is a smear, which is the
 * point — the chart only becomes readable once zoomed, and that is what makes
 * the viewport worth having rather than a demo of a gesture.
 */
@Composable
fun ChartExploreScreen(modifier: Modifier = Modifier) {
    var mode by rememberSaveable { mutableStateOf(ExploreMode.ZoomPan) }
    var range by remember { mutableStateOf<ChartRangeSelection<ChartDemoData.Reading>?>(null) }

    val viewport = rememberChartViewportState()
    val scope = rememberCoroutineScope()
    val dateLabel = remember { ChartDateFormatters.pattern("d MMM yyyy") }
    val degrees = remember { ChartNumberFormatters.decimal(1) }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Zoom, pan and range", style = MaterialTheme.typography.headlineSmall)
        Text(
            "730 daily readings. Pinch to zoom, drag to pan once zoomed, or switch to range " +
                "mode and drag out an interval. Zoom changes the domain the scales map, so the " +
                "axis relabels itself as you go.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExploreMode.entries.forEach { entry ->
                FilterChip(
                    selected = mode == entry,
                    onClick = { mode = entry; range = null },
                    label = { Text(entry.label) },
                    modifier = Modifier.testTag("chartdemo:mode:${entry.name}"),
                )
            }
        }

        LineChart(
            data = ChartDemoData.dailyReadings,
            x = { it.atMillis },
            y = { it.celsius },
            xResolver = ChartXResolver.Time,
            interaction = mode.interaction,
            crosshair = mode.crosshair,
            viewportState = viewport,
            xAxis = ChartAxis(tickCount = 4),
            yAxis = ChartAxis(title = "°C"),
            valueFormatter = degrees,
            animation = ChartAnimation.None,
            onRangeSelectionChanged = { range = it },
            tooltip = { data ->
                Card(shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(data.xLabel, fontWeight = FontWeight.SemiBold)
                        data.entries.forEach { entry ->
                            Text("${degrees.format(entry.value)} °C")
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .testTag(ChartDemoTestTags.Chart),
        )

        // The viewport in logical values, not fractions — the chart publishes
        // its full domain so a caller can read "which dates am I showing".
        val visible = viewport.visibleDomain
        Readout(
            text = if (visible == null || viewport.isFullyZoomedOut) {
                "Showing everything · zoom ${"%.1f".format(viewport.zoom)}×"
            } else {
                "Showing ${dateLabel.format(visible.min.toLong())} – " +
                    "${dateLabel.format(visible.max.toLong())} · " +
                    "zoom ${"%.1f".format(viewport.zoom)}×"
            },
            testTag = ChartDemoTestTags.Viewport,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { scope.launch { viewport.animateToFull() } },
                modifier = Modifier.testTag(ChartDemoTestTags.ResetZoom),
            ) {
                Text("Reset zoom")
            }
            OutlinedButton(onClick = { scope.launch { viewport.animateTo(ChartViewport.trailing(0.08)) } }) {
                Text("Last 60 days")
            }
            if (range != null) {
                Button(onClick = {
                    // A range is stated in full-domain fractions, so "zoom to
                    // the selection" is a viewport built directly from it.
                    scope.launch {
                        viewport.animateTo(
                            ChartViewport.between(range!!.startFraction, range!!.endFraction),
                        )
                    }
                }) {
                    Text("Zoom to range")
                }
            }
        }

        if (mode == ExploreMode.Range) {
            Readout(
                text = range?.let {
                    "Selected ${dateLabel.format((it.start as? io.devkit.chartkit.model.ChartX.Time)?.epochMillis ?: 0L)} – " +
                        dateLabel.format((it.end as? io.devkit.chartkit.model.ChartX.Time)?.epochMillis ?: 0L) +
                        " · ${it.items.size} readings"
                } ?: "Drag across the chart to select an interval",
                testTag = ChartDemoTestTags.Range,
            )
        }

        HorizontalDivider()
        Text("Crosshair over several series", style = MaterialTheme.typography.titleSmall)
        Text(
            "One x position, every series' value. The crosshair reuses the same nearest-point " +
                "search as scrubbing, and the tooltip is the shared overlay.",
            style = MaterialTheme.typography.bodySmall,
        )
        LineChart(
            series = ChartDemoData.revenueSeries(),
            x = { it.month },
            y = { it.amount },
            crosshair = CrosshairConfig.Vertical,
            valueFormatter = remember { ChartNumberFormatters.compact() },
            animation = ChartAnimation.None,
            modifier = Modifier.fillMaxWidth().height(260.dp),
        )
    }
}

@Composable
private fun Readout(text: String, testTag: String) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp)
            .testTag(testTag),
        text = text,
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * The two drag behaviours a single finger can have, made an explicit choice.
 *
 * A drag cannot pan and select a range at the same time, so the demo switches
 * modes rather than offering both as independent switches.
 */
private enum class ExploreMode(
    val label: String,
    val interaction: ChartInteraction,
    val crosshair: CrosshairConfig,
) {
    ZoomPan(
        "Zoom & pan",
        ChartInteraction.Explorable,
        CrosshairConfig.Vertical,
    ),
    Range(
        "Select range",
        ChartInteraction(dragMode = ChartDragMode.Range, zoomEnabled = true),
        CrosshairConfig.None,
    ),
}
