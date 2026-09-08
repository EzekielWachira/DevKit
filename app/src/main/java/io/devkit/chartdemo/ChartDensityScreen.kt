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
import io.devkit.chartkit.charts.CalendarHeatmap
import io.devkit.chartkit.charts.Heatmap
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.layer.heatmap.HeatmapCellLabels
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.theme.ChartColorScales
import java.util.TimeZone

/**
 * Heatmaps, and the colour scales behind them.
 *
 * The switch worth having here is "show missing": the grid contains two cells
 * nobody measured, and turning the treatment off is the fastest way to see why
 * painting them as zero would be a different claim.
 */
@Composable
fun ChartDensityScreen(modifier: Modifier = Modifier) {
    var calendar by rememberSaveable { mutableStateOf(false) }
    var showMissing by rememberSaveable { mutableStateOf(true) }
    var labels by rememberSaveable { mutableStateOf(false) }
    var scaleKind by rememberSaveable { mutableStateOf(0) }
    var readout by rememberSaveable { mutableStateOf("Tap a cell") }

    val continuous = ChartColorScales.continuous(
        io.devkit.chartkit.scale.NumericDomain(
            0.0,
            ChartDemoData.trafficGrid.mapNotNull { it.requests }.maxOrNull() ?: 1.0,
        ),
    )
    val quantized = ChartColorScales.quantized(
        io.devkit.chartkit.scale.NumericDomain(
            0.0,
            ChartDemoData.trafficGrid.mapNotNull { it.requests }.maxOrNull() ?: 1.0,
        ),
        steps = 5,
    )
    val threshold: ColorScale = ChartColorScales.threshold(
        thresholds = listOf(200.0, 600.0, 1_000.0),
        labels = listOf("quiet", "steady", "busy", "peak"),
    )

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Heatmaps", style = MaterialTheme.typography.titleLarge)
        Text(
            "Two categorical axes and a colour scale. A cell nobody measured is painted in " +
                "the theme's \"no data\" colour, which is deliberately not the low end of the " +
                "ramp — \"closed on Sunday\" and \"open with no visitors\" are different facts.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !calendar,
                onClick = { calendar = false },
                label = { Text("Grid") },
                modifier = Modifier.testTag("density-grid"),
            )
            FilterChip(
                selected = calendar,
                onClick = { calendar = true },
                label = { Text("Calendar") },
                modifier = Modifier.testTag("density-calendar"),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = showMissing,
                onCheckedChange = { showMissing = it },
                modifier = Modifier.testTag("show-missing"),
            )
            Text("  Show cells with no data", style = MaterialTheme.typography.bodyMedium)
        }

        if (!calendar) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = labels,
                    onCheckedChange = { labels = it },
                    modifier = Modifier.testTag("cell-labels"),
                )
                Text("  Write values into cells that fit", style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Continuous", "Quantized", "Threshold").forEachIndexed { index, label ->
                    FilterChip(
                        selected = scaleKind == index,
                        onClick = { scaleKind = index },
                        label = { Text(label) },
                        modifier = Modifier.testTag("scale-$index"),
                    )
                }
            }
        }

        HorizontalDivider()

        if (calendar) {
            Text(
                "Weeks across, weekdays down, with the first day of the week taken from the " +
                    "device locale rather than assumed. July is genuinely absent, not zero.",
                style = MaterialTheme.typography.bodySmall,
            )
            CalendarHeatmap(
                data = ChartDemoData.dailyActivity,
                date = { it.dateMillis },
                value = { it.commits },
                timeZone = TimeZone.getTimeZone("UTC"),
                showMissing = showMissing,
                onSelectionChanged = { selection ->
                    readout = selection?.let { "${it.xLabel}: ${it.y.toInt()} commits" }
                        ?: "Tap a day"
                },
                // Sized so the cells come out roughly square, the way a
                // contribution grid is read.
                modifier = Modifier.fillMaxWidth().height(150.dp).testTag(CHART),
            )
        } else {
            Heatmap(
                data = ChartDemoData.trafficGrid,
                x = { it.day },
                y = { it.hour },
                value = { it.requests },
                colorScale = when (scaleKind) {
                    1 -> quantized
                    2 -> threshold
                    else -> continuous
                },
                showMissing = showMissing,
                cellLabels = if (labels) HeatmapCellLabels.Auto else HeatmapCellLabels.None,
                valueFormatter = ChartNumberFormatters.compact(),
                onSelectionChanged = { selection ->
                    readout = selection?.let {
                        val value = if (it.y.isNaN()) "no data" else "${it.y.toInt()} requests"
                        "${it.xLabel} — $value"
                    } ?: "Tap a cell"
                },
                modifier = Modifier.fillMaxWidth().height(380.dp).testTag(CHART),
            )
        }

        HorizontalDivider()
        Text(readout, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(READOUT))
    }
}

private const val CHART = "density-chart"
private const val READOUT = "density-readout"
