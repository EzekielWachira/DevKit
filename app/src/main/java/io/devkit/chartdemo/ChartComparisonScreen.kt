package io.devkit.chartdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.devkit.chartkit.charts.BulletChart
import io.devkit.chartkit.charts.DumbbellChart
import io.devkit.chartkit.charts.GaugeChart
import io.devkit.chartkit.charts.GaugeShape
import io.devkit.chartkit.charts.LollipopChart
import io.devkit.chartkit.charts.WaterfallChart
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.layer.comparison.BulletRange
import io.devkit.chartkit.layer.polar.GaugeBand
import io.devkit.chartkit.layer.polar.GaugeIndicator
import io.devkit.chartkit.transform.WaterfallStepKind

/**
 * The comparison family: waterfall, dumbbell, lollipop, bullet and gauge.
 *
 * All five are built on engines that already existed. The four Cartesian ones
 * share the axes, the scales, the grid, the tooltip and the viewport with every
 * bar chart; the gauge is an arc on the same polar coordinates as the pie.
 */
@Composable
fun ChartComparisonScreen(modifier: Modifier = Modifier) {
    var connectors by rememberSaveable { mutableStateOf(true) }
    var needle by rememberSaveable { mutableStateOf(false) }
    var readout by remember { mutableStateOf("Tap a bar") }

    val money = remember { ChartNumberFormatters.compact() }
    val plain = remember { ChartNumberFormatters.integer() }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Waterfall", style = MaterialTheme.typography.titleLarge)
        Text(
            "Contributions that accumulate. A bar starts where the previous one ended, a " +
                "subtotal is measured from zero, and the step's kind — not the sign of its " +
                "value — decides the direction.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = connectors,
                onCheckedChange = { connectors = it },
                modifier = Modifier.testTag("connectors"),
            )
            Text("  Connectors", style = MaterialTheme.typography.bodyMedium)
        }

        WaterfallChart(
            data = ChartDemoData.movements,
            label = { it.name },
            value = { it.amount },
            kind = {
                when (it.kind) {
                    "decrease" -> WaterfallStepKind.Decrease
                    "subtotal" -> WaterfallStepKind.Subtotal
                    "total" -> WaterfallStepKind.Total
                    else -> WaterfallStepKind.Increase
                }
            },
            showConnectors = connectors,
            valueAxis = io.devkit.chartkit.axis.ChartAxis(valueFormatter = money),
            onSelectionChanged = { selection ->
                readout = selection?.let { "${it.xLabel}: ${money.format(it.y)}" } ?: "Tap a bar"
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .testTag("waterfall"),
        )
        Text(readout, style = MaterialTheme.typography.bodyMedium)

        HorizontalDivider()
        Text("Dumbbell", style = MaterialTheme.typography.titleMedium)
        Text(
            "Two values per category, joined. The distance is the reading — two bars side by " +
                "side show the same numbers and leave the reader to subtract.",
            style = MaterialTheme.typography.bodyMedium,
        )
        DumbbellChart(
            data = ChartDemoData.teamChanges,
            category = { it.team },
            start = { it.before },
            end = { it.after },
            startLabel = "Q1",
            endLabel = "Q2",
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .testTag("dumbbell"),
        )

        HorizontalDivider()
        Text("Lollipop", style = MaterialTheme.typography.titleMedium)
        Text(
            "A bar chart with the ink removed — the same layer as the dumbbell, with the " +
                "stem starting at the baseline instead of at a second value.",
            style = MaterialTheme.typography.bodyMedium,
        )
        LollipopChart(
            data = ChartDemoData.teamChanges,
            category = { it.team },
            value = { it.after },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .testTag("lollipop"),
        )

        HorizontalDivider()
        Text("Bullet", style = MaterialTheme.typography.titleMedium)
        Text(
            "A measure against a target, on qualitative ranges. Several stack into the space " +
                "one dial would take, and because they share an axis they can be compared.",
            style = MaterialTheme.typography.bodyMedium,
        )
        BulletChart(
            data = ChartDemoData.kpis,
            label = { it.name },
            actual = { it.actual },
            target = { it.target },
            ranges = {
                listOf(
                    BulletRange(0.0, 50.0, "Below"),
                    BulletRange(50.0, 75.0, "On track"),
                    BulletRange(75.0, 100.0, "Ahead"),
                )
            },
            valueAxis = io.devkit.chartkit.axis.ChartAxis(
                valueFormatter = plain,
                domain = io.devkit.chartkit.scale.DomainPolicy.Fixed(0.0, 100.0),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .testTag("bullet"),
        )

        HorizontalDivider()
        Text("Gauge", style = MaterialTheme.typography.titleMedium)
        Text(
            "One value against a range, on the polar engine. The bands are intervals the " +
                "caller names — ChartKit does not know whether a high number is good.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = needle,
                onCheckedChange = { needle = it },
                modifier = Modifier.testTag("needle"),
            )
            Text("  Needle as well as the arc", style = MaterialTheme.typography.bodyMedium)
        }
        GaugeChart(
            value = 72.0,
            min = 0.0,
            max = 100.0,
            label = "Revenue to target",
            bands = listOf(
                GaugeBand(0.0, 50.0, "Below target"),
                GaugeBand(50.0, 75.0, "On target"),
                GaugeBand(75.0, 100.0, "Ahead"),
            ),
            shape = GaugeShape.ThreeQuarter,
            indicator = if (needle) GaugeIndicator.ArcAndNeedle else GaugeIndicator.Arc,
            valueFormatter = plain,
            centerContent = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("72", style = MaterialTheme.typography.headlineMedium)
                    Text("of 100", style = MaterialTheme.typography.bodySmall)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .testTag("gauge"),
        )
    }
}
