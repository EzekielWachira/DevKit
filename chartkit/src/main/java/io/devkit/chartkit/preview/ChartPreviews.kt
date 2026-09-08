package io.devkit.chartkit.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.charts.AreaChart
import io.devkit.chartkit.charts.BarChart
import io.devkit.chartkit.charts.DonutChart
import io.devkit.chartkit.charts.PieChart
import io.devkit.chartkit.charts.RadialBarChart
import io.devkit.chartkit.charts.HorizontalBarChart
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.layer.polar.SliceLabelContent
import io.devkit.chartkit.layer.polar.SliceLabelPosition
import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.geometry.LineInterpolation
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.preview.ChartKitPreviewData.MonthlyValue

/**
 * Previews of every chart type.
 *
 * Animation is off throughout. A preview renders a single frame, so an
 * animating chart is caught part-drawn — which looks like a rendering bug and
 * makes the preview useless for judging a layout.
 */
private val NoAnimation = ChartAnimation.None

@Preview(name = "Line", showBackground = true, widthDp = 360, heightDp = 220)
@Composable
private fun LineChartPreview() {
    PreviewSurface {
        LineChart(
            data = ChartKitPreviewData.revenue,
            x = { it.month },
            y = { it.amount },
            animation = NoAnimation,
            valueFormatter = ChartNumberFormatters.compact(),
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
    }
}

@Preview(name = "Line — smooth", showBackground = true, widthDp = 360, heightDp = 220)
@Composable
private fun SmoothLineChartPreview() {
    PreviewSurface {
        LineChart(
            data = ChartKitPreviewData.revenue,
            x = { it.month },
            y = { it.amount },
            interpolation = LineInterpolation.Smooth,
            animation = NoAnimation,
            valueFormatter = ChartNumberFormatters.compact(),
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
    }
}

@Preview(name = "Line — multi-series", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun MultiSeriesLineChartPreview() {
    PreviewSurface {
        LineChart(
            series = listOf(
                ChartSeries("revenue", "Revenue", ChartKitPreviewData.revenue),
                ChartSeries("expenses", "Expenses", ChartKitPreviewData.expenses),
            ),
            x = { it.month },
            y = { it.amount },
            animation = NoAnimation,
            valueFormatter = ChartNumberFormatters.compact(),
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
    }
}

@Preview(name = "Area", showBackground = true, widthDp = 360, heightDp = 220)
@Composable
private fun AreaChartPreview() {
    PreviewSurface {
        AreaChart(
            data = ChartKitPreviewData.revenue,
            x = { it.month },
            y = { it.amount },
            animation = NoAnimation,
            valueFormatter = ChartNumberFormatters.compact(),
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
    }
}

@Preview(name = "Bar", showBackground = true, widthDp = 360, heightDp = 220)
@Composable
private fun BarChartPreview() {
    PreviewSurface {
        BarChart(
            data = ChartKitPreviewData.revenue,
            category = { it.month },
            value = { it.amount },
            animation = NoAnimation,
            valueFormatter = ChartNumberFormatters.compact(),
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
    }
}

@Preview(name = "Bar — negative values", showBackground = true, widthDp = 360, heightDp = 220)
@Composable
private fun NegativeBarChartPreview() {
    PreviewSurface {
        BarChart(
            data = ChartKitPreviewData.netMargin,
            category = { it.month },
            value = { it.amount },
            animation = NoAnimation,
            valueFormatter = ChartNumberFormatters.compact(),
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
    }
}

@Preview(name = "Bar — horizontal", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun HorizontalBarChartPreview() {
    PreviewSurface {
        HorizontalBarChart(
            data = ChartKitPreviewData.productLines,
            category = { it.month },
            value = { it.amount },
            animation = NoAnimation,
            valueFormatter = ChartNumberFormatters.compact(),
            modifier = Modifier.fillMaxWidth().height(220.dp),
        )
    }
}

@Preview(name = "Bar — grouped", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun GroupedBarChartPreview() {
    PreviewSurface {
        BarChart(
            series = cohortSeries(),
            category = { it.month },
            value = { it.amount },
            grouping = BarGrouping.Grouped,
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
    }
}

@Preview(name = "Bar — stacked", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun StackedBarChartPreview() {
    PreviewSurface {
        BarChart(
            series = cohortSeries(),
            category = { it.month },
            value = { it.amount },
            grouping = BarGrouping.Stacked,
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
    }
}

@Preview(name = "Bar — 100% stacked", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun PercentStackedBarChartPreview() {
    PreviewSurface {
        BarChart(
            series = cohortSeries(),
            category = { it.month },
            value = { it.amount },
            grouping = BarGrouping.StackedPercent,
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
    }
}

@Preview(name = "Time axis with a gap", showBackground = true, widthDp = 360, heightDp = 220)
@Composable
private fun TimeSeriesPreview() {
    PreviewSurface {
        LineChart(
            data = ChartKitPreviewData.sensorReadings,
            x = { it.atMillis },
            y = { it.value },
            xResolver = io.devkit.chartkit.model.ChartXResolver.Time,
            xAxis = ChartAxis(tickCount = 4),
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
    }
}

@Preview(name = "Pie", showBackground = true, widthDp = 360, heightDp = 320)
@Composable
private fun PieChartPreview() {
    PreviewSurface {
        PieChart(
            data = ChartKitPreviewData.expenseShares,
            value = { it.amount },
            label = { it.category },
            sliceGap = 1f,
            animation = NoAnimation,
            valueFormatter = ChartNumberFormatters.compact(),
            modifier = Modifier.fillMaxWidth().height(260.dp),
        )
    }
}

@Preview(name = "Pie — labelled slices", showBackground = true, widthDp = 360, heightDp = 320)
@Composable
private fun LabelledPieChartPreview() {
    PreviewSurface {
        PieChart(
            data = ChartKitPreviewData.expenseShares,
            value = { it.amount },
            label = { it.category },
            labelPosition = SliceLabelPosition.Inside,
            labelContent = SliceLabelContent.Percentage,
            legend = LegendPosition.None,
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(280.dp),
        )
    }
}

@Preview(name = "Donut with centre content", showBackground = true, widthDp = 360, heightDp = 320)
@Composable
private fun DonutChartPreview() {
    PreviewSurface {
        DonutChart(
            data = ChartKitPreviewData.expenseShares,
            value = { it.amount },
            label = { it.category },
            sliceGap = 1.5f,
            animation = NoAnimation,
            valueFormatter = ChartNumberFormatters.compact(),
            centerContent = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text("3,600", style = MaterialTheme.typography.titleLarge)
                    Text("total", style = MaterialTheme.typography.labelSmall)
                }
            },
            modifier = Modifier.fillMaxWidth().height(260.dp),
        )
    }
}

@Preview(name = "Radial bars", showBackground = true, widthDp = 360, heightDp = 320)
@Composable
private fun RadialBarChartPreview() {
    PreviewSurface {
        RadialBarChart(
            data = ChartKitPreviewData.systemMetrics,
            value = { it.value },
            label = { it.name },
            maxValue = 100.0,
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(260.dp),
        )
    }
}

@Preview(name = "Crosshair", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun CrosshairPreview() {
    PreviewSurface {
        LineChart(
            series = listOf(
                ChartSeries("revenue", "Revenue", ChartKitPreviewData.revenue),
                ChartSeries("expenses", "Expenses", ChartKitPreviewData.expenses),
            ),
            x = { it.month },
            y = { it.amount },
            crosshair = CrosshairConfig.Vertical,
            animation = NoAnimation,
            valueFormatter = ChartNumberFormatters.compact(),
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
    }
}

@Preview(name = "Empty", showBackground = true, widthDp = 360, heightDp = 200)
@Composable
private fun EmptyChartPreview() {
    PreviewSurface {
        LineChart(
            data = emptyList<MonthlyValue>(),
            x = { it.month },
            y = { it.amount },
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
    }
}

@Preview(name = "Dark", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun DarkChartPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LineChart(
                    series = listOf(
                        ChartSeries("revenue", "Revenue", ChartKitPreviewData.revenue),
                        ChartSeries("expenses", "Expenses", ChartKitPreviewData.expenses),
                    ),
                    x = { it.month },
                    y = { it.amount },
                    animation = NoAnimation,
                    valueFormatter = ChartNumberFormatters.compact(),
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
            }
        }
    }
}

private fun cohortSeries(): List<ChartSeries<MonthlyValue>> = listOf(
    ChartSeries("new", "New", ChartKitPreviewData.newCustomers),
    ChartSeries("returning", "Returning", ChartKitPreviewData.returningCustomers),
)

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface {
            Column(Modifier.padding(12.dp)) { content() }
        }
    }
}
