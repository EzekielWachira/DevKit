package io.devkit.chartdemo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.DonutChart
import io.devkit.chartkit.charts.PieChart
import io.devkit.chartkit.charts.RadialBarChart
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.layer.polar.SliceLabelContent
import io.devkit.chartkit.layer.polar.SliceLabelPosition
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.state.rememberChartState

/**
 * Pie, donut and radial bar — the polar half of the engine.
 *
 * The controls are here for the same reason they are on the Cartesian gallery:
 * turning a parameter on and watching what happens is the fastest way to find
 * out whether it does what its name says.
 */
@Composable
fun ChartPolarScreen(modifier: Modifier = Modifier) {
    var kind by rememberSaveable { mutableStateOf(PolarKind.Pie) }
    var labels by rememberSaveable { mutableStateOf(false) }
    var gaps by rememberSaveable { mutableStateOf(true) }
    var animate by rememberSaveable { mutableStateOf(true) }

    val money = remember { ChartNumberFormatters.compact() }
    val pieState = rememberChartState<ChartDemoData.Expense>()
    val metricState = rememberChartState<ChartDemoData.Metric>()
    val animation = if (animate) ChartAnimation.Default else ChartAnimation.None

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Polar charts", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pie, donut and radial bar share one polar coordinate system, and share the " +
                "theme, animation, legend, tooltip and accessibility systems with the " +
                "Cartesian charts.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PolarKind.entries.forEach { entry ->
                FilterChip(
                    selected = kind == entry,
                    onClick = { kind = entry },
                    label = { Text(entry.label) },
                    modifier = Modifier.testTag("chartdemo:polar:${entry.name}"),
                )
            }
        }

        Text(kind.description, style = MaterialTheme.typography.bodySmall)

        when (kind) {
            PolarKind.Pie -> PieChart(
                data = ChartDemoData.expenseBreakdown,
                value = { it.amount },
                label = { it.category },
                sliceGap = if (gaps) 1.5f else 0f,
                labelPosition = if (labels) SliceLabelPosition.Inside else SliceLabelPosition.None,
                labelContent = SliceLabelContent.Percentage,
                valueFormatter = money,
                animation = animation,
                state = pieState,
                accessibility = ChartAccessibility(title = "Monthly expenses"),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .testTag(ChartDemoTestTags.Chart),
            )

            PolarKind.Donut -> DonutChart(
                data = ChartDemoData.expenseBreakdown,
                value = { it.amount },
                label = { it.category },
                sliceGap = if (gaps) 1.5f else 0f,
                labelPosition = if (labels) SliceLabelPosition.Outside else SliceLabelPosition.None,
                labelContent = SliceLabelContent.Label,
                valueFormatter = money,
                animation = animation,
                state = pieState,
                accessibility = ChartAccessibility(title = "Monthly expenses"),
                // Arbitrary Compose content in the hole — laid out by Compose,
                // readable by a screen reader, and inert to pointer input so
                // the slices keep their taps.
                centerContent = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = pieState.selection?.item?.category ?: "Total",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = money.format(
                                pieState.selection?.item?.amount ?: ChartDemoData.expenseTotal,
                            ),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .testTag(ChartDemoTestTags.Chart),
            )

            PolarKind.RadialBar -> RadialBarChart(
                data = ChartDemoData.systemMetrics,
                value = { it.value },
                label = { it.name },
                maxValue = 100.0,
                animation = animation,
                state = metricState,
                accessibility = ChartAccessibility(title = "System utilisation"),
                tooltip = { data ->
                    Card(shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(data.item.name, fontWeight = FontWeight.SemiBold)
                            Text("${data.item.value.toInt()}${data.item.unit} of 100%")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .testTag(ChartDemoTestTags.Chart),
            )

            PolarKind.Gauge -> RadialBarChart(
                data = ChartDemoData.systemMetrics.take(3),
                value = { it.value },
                label = { it.name },
                maxValue = 100.0,
                // A partial sweep turns the same chart into a gauge: the engine
                // is unchanged, only the arc it is drawn within.
                startAngle = 225f,
                sweepAngle = 270f,
                animation = animation,
                state = metricState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .testTag(ChartDemoTestTags.Chart),
            )
        }

        SelectionLine(kind, pieState.selection, metricState.selection)

        HorizontalDivider()
        Text("Controls", style = MaterialTheme.typography.titleSmall)
        DemoToggle("Slice labels", labels, enabled = kind != PolarKind.RadialBar) { labels = it }
        DemoToggle("Slice gaps", gaps, enabled = kind == PolarKind.Pie || kind == PolarKind.Donut) {
            gaps = it
        }
        DemoToggle("Animation", animate) { animate = it }

        HorizontalDivider()
        Text("Values a pie cannot represent", style = MaterialTheme.typography.titleSmall)
        Text(
            "A share of a whole is never negative. Invalid entries keep their legend row and " +
                "their colour, and draw no slice — they are not silently made positive.",
            style = MaterialTheme.typography.bodySmall,
        )
        PieChart(
            data = remember {
                listOf(
                    ChartDemoData.Expense("Valid", 40.0),
                    ChartDemoData.Expense("Negative", -10.0),
                    ChartDemoData.Expense("Zero", 0.0),
                    ChartDemoData.Expense("Also valid", 60.0),
                )
            },
            value = { it.amount },
            label = { it.category },
            legend = LegendPosition.Bottom,
            animation = ChartAnimation.None,
            modifier = Modifier.fillMaxWidth().height(240.dp),
        )
    }
}

@Composable
private fun SelectionLine(
    kind: PolarKind,
    slice: ChartSelection<ChartDemoData.Expense>?,
    metric: ChartSelection<ChartDemoData.Metric>?,
) {
    val text = when (kind) {
        PolarKind.RadialBar, PolarKind.Gauge -> metric?.let {
            "${it.item.name}: ${it.item.value}${it.item.unit} " +
                "(${(it.polar?.fraction ?: 0.0).times(100).toInt()}% of the range)"
        }
        else -> slice?.let {
            "${it.item.category}: ${it.item.amount} " +
                "(${(it.polar?.fraction ?: 0.0).times(100).toInt()}% of the total)"
        }
    }
    Text(
        text = text ?: "Tap a slice or a ring",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag(ChartDemoTestTags.Selection),
    )
}

private enum class PolarKind(val label: String, val description: String) {
    Pie("Pie", "One series, normalised. Values need not sum to anything in particular."),
    Donut("Donut", "The same slices with an inner radius, and Compose content in the hole."),
    RadialBar("Radial bars", "Concentric rings, each measured against a configurable range."),
    Gauge("Gauge", "The same radial bars drawn within a 270° sweep."),
}
