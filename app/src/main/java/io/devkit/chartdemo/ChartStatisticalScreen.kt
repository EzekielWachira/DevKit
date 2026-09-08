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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.charts.BoxPlot
import io.devkit.chartkit.charts.BubbleChart
import io.devkit.chartkit.charts.Histogram
import io.devkit.chartkit.charts.ScatterChart
import io.devkit.chartkit.charts.ViolinPlot
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.layer.statistical.ViolinOverlay
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.stats.BoxStatistics
import io.devkit.chartkit.stats.HistogramBins
import io.devkit.chartkit.stats.HistogramMetric

/** Which statistical chart the screen is showing. */
private enum class StatKind(val label: String) {
    Scatter("Scatter"),
    Bubble("Bubble"),
    Histogram("Histogram"),
    Box("Box plot"),
    Violin("Violin"),
}

/**
 * The statistical charts, with the parameters that change what they claim.
 *
 * The controls are chosen for the decisions that are easy to get wrong rather
 * than for the ones that are merely visible: the bin strategy, the histogram
 * metric, and whether a box plot is computed here or supplied from elsewhere.
 */
@Composable
fun ChartStatisticalScreen(modifier: Modifier = Modifier) {
    var kind by rememberSaveable { mutableStateOf(StatKind.Scatter) }
    var bins by rememberSaveable { mutableStateOf(1) }
    var metric by rememberSaveable { mutableStateOf(HistogramMetric.Count) }
    var precomputed by rememberSaveable { mutableStateOf(false) }
    var overlay by rememberSaveable { mutableStateOf(ViolinOverlay.Box) }
    var readout by rememberSaveable { mutableStateOf("Tap a mark") }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Statistical charts", style = MaterialTheme.typography.titleLarge)
        Text(
            "Scatter and bubble plot the caller's own model through two or three lambdas. " +
                "Histogram, box plot and violin take raw observations and do the statistics " +
                "themselves — or accept summaries that already exist.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatKind.entries.forEach { candidate ->
                FilterChip(
                    selected = kind == candidate,
                    onClick = { kind = candidate },
                    label = { Text(candidate.label) },
                    modifier = Modifier.testTag("stat-${candidate.name}"),
                )
            }
        }

        HorizontalDivider()

        when (kind) {
            StatKind.Scatter -> {
                Text("Two cohorts, plotted from the app's own `Person`.", style = MaterialTheme.typography.bodySmall)
                ScatterChart(
                    series = listOf(
                        ChartSeries(
                            id = "a",
                            name = "Cohort A",
                            data = ChartDemoData.people.filter { it.group == "Cohort A" },
                        ),
                        ChartSeries(
                            id = "b",
                            name = "Cohort B",
                            data = ChartDemoData.people.filter { it.group == "Cohort B" },
                        ),
                    ),
                    x = { it.heightCm },
                    y = { it.weightKg },
                    xAxis = ChartAxis(title = "Height (cm)"),
                    yAxis = ChartAxis(title = "Weight (kg)"),
                    onSelectionChanged = { selection ->
                        readout = selection?.item?.let {
                            "${selection.seriesName}: ${it.heightCm.toInt()} cm, " +
                                "${it.weightKg.toInt()} kg"
                        } ?: "Tap a mark"
                    },
                    modifier = Modifier.fillMaxWidth().height(300.dp).testTag(CHART),
                )
            }

            StatKind.Bubble -> {
                Text(
                    "Size carries age, mapped to bubble *area* — a value twice as large " +
                        "occupies twice the area, which is what a reader sees.",
                    style = MaterialTheme.typography.bodySmall,
                )
                BubbleChart(
                    data = ChartDemoData.people.filter { it.group == "Cohort A" },
                    x = { it.heightCm },
                    y = { it.weightKg },
                    size = { it.ageYears },
                    xAxis = ChartAxis(title = "Height (cm)"),
                    yAxis = ChartAxis(title = "Weight (kg)"),
                    onSelectionChanged = { selection ->
                        readout = selection?.item?.let {
                            "${it.heightCm.toInt()} cm, ${it.weightKg.toInt()} kg, " +
                                "age ${it.ageYears.toInt()}"
                        } ?: "Tap a mark"
                    },
                    modifier = Modifier.fillMaxWidth().height(300.dp).testTag(CHART),
                )
            }

            StatKind.Histogram -> {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("Auto" to 1, "10 bins" to 10, "25 bins" to 25, "50 ms wide" to -50)
                        .forEach { (label, value) ->
                            FilterChip(
                                selected = bins == value,
                                onClick = { bins = value },
                                label = { Text(label) },
                                modifier = Modifier.testTag("bins-$value"),
                            )
                        }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HistogramMetric.entries.forEach { candidate ->
                        FilterChip(
                            selected = metric == candidate,
                            onClick = { metric = candidate },
                            label = { Text(candidate.name) },
                            modifier = Modifier.testTag("metric-${candidate.name}"),
                        )
                    }
                }
                Histogram(
                    data = ChartDemoData.responseTimes,
                    value = { it },
                    bins = when {
                        bins == 1 -> HistogramBins.Auto
                        bins < 0 -> HistogramBins.Width(-bins.toDouble())
                        else -> HistogramBins.Count(bins)
                    },
                    metric = metric,
                    xAxis = ChartAxis(title = "Response time (ms)"),
                    valueFormatter = if (metric == HistogramMetric.Percentage) {
                        ChartNumberFormatters.fraction(0)
                    } else {
                        null
                    },
                    onSelectionChanged = { selection ->
                        readout = selection?.let {
                            "${it.x.let { x -> (x as? io.devkit.chartkit.model.ChartX.Category)?.label }}" +
                                " ms — ${it.item.size} observations"
                        } ?: "Tap a bar"
                    },
                    modifier = Modifier.fillMaxWidth().height(280.dp).testTag(CHART),
                )
            }

            StatKind.Box -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !precomputed,
                        onClick = { precomputed = false },
                        label = { Text("From samples") },
                        modifier = Modifier.testTag("box-samples"),
                    )
                    FilterChip(
                        selected = precomputed,
                        onClick = { precomputed = true },
                        label = { Text("From statistics") },
                        modifier = Modifier.testTag("box-precomputed"),
                    )
                }
                Text(
                    if (precomputed) {
                        "Summaries supplied by the caller. ChartKit recomputes nothing and " +
                            "applies no outlier rule — these numbers came from somewhere else."
                    } else {
                        "Quartiles computed by ChartKit, by the documented linear-interpolation " +
                            "method, with a 1.5 × IQR outlier fence."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (precomputed) {
                    BoxPlot(
                        data = ChartDemoData.endpoints,
                        label = { it.path },
                        statistics = { BoxStatistics.from(it.latencies) },
                        valueAxis = ChartAxis(title = "ms"),
                        onSelectionChanged = { selection ->
                            readout = selection?.let { "${it.xLabel}: median ${it.y.toInt()} ms" }
                                ?: "Tap a box"
                        },
                        modifier = Modifier.fillMaxWidth().height(300.dp).testTag(CHART),
                    )
                } else {
                    BoxPlot(
                        data = ChartDemoData.endpoints,
                        label = { it.path },
                        values = { it.latencies },
                        valueAxis = ChartAxis(title = "ms"),
                        onSelectionChanged = { selection ->
                            readout = selection?.let { "${it.xLabel}: median ${it.y.toInt()} ms" }
                                ?: "Tap a box"
                        },
                        modifier = Modifier.fillMaxWidth().height(300.dp).testTag(CHART),
                    )
                }
            }

            StatKind.Violin -> {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ViolinOverlay.entries.forEach { candidate ->
                        FilterChip(
                            selected = overlay == candidate,
                            onClick = { overlay = candidate },
                            label = { Text(candidate.name) },
                            modifier = Modifier.testTag("overlay-${candidate.name}"),
                        )
                    }
                }
                Text(
                    "Every violin is scaled by the widest in the chart, so a narrower one " +
                        "genuinely means a more concentrated distribution.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ViolinPlot(
                    data = ChartDemoData.endpoints,
                    label = { it.path },
                    values = { it.latencies },
                    overlay = overlay,
                    valueAxis = ChartAxis(title = "ms"),
                    onSelectionChanged = { selection ->
                        readout = selection?.let { "${it.xLabel}: median ${it.y.toInt()} ms" }
                            ?: "Tap a violin"
                    },
                    modifier = Modifier.fillMaxWidth().height(300.dp).testTag(CHART),
                )
            }
        }

        HorizontalDivider()
        Text(readout, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(READOUT))
    }
}

private const val CHART = "statistical-chart"
private const val READOUT = "statistical-readout"
