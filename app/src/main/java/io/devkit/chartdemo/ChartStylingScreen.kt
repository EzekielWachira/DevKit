package io.devkit.chartdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.charts.BarChart
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.formatter.ChartDateFormatters
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.theme.ChartDimensions
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.theme.materialDerivedChartColors

/**
 * Theming and formatting: the two things every team customises first.
 *
 * The three levels of precedence — a per-chart parameter, a `ChartKitTheme`,
 * and the Material-derived default — are all on screen at once here, which is
 * the quickest way to confirm that overriding one does not disturb the others.
 */
@Composable
fun ChartStylingScreen(modifier: Modifier = Modifier) {
    var dark by rememberSaveable { mutableStateOf(false) }
    var brandTheme by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Theming and formatting", style = MaterialTheme.typography.headlineSmall)
        Text(
            "With no configuration, charts take their colours from the app's Material scheme. " +
                "A ChartKitTheme states an organisation's chart styling once; a per-chart " +
                "parameter still wins over it.",
            style = MaterialTheme.typography.bodyMedium,
        )

        DemoToggle("Dark scheme", dark) { dark = it }
        DemoToggle("Brand ChartKitTheme", brandTheme) { brandTheme = it }

        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface(tonalElevation = 1.dp) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val body: @Composable () -> Unit = {
                        LineChart(
                            series = ChartDemoData.revenueSeries(),
                            x = { it.month },
                            y = { it.amount },
                            valueFormatter = ChartNumberFormatters.compact(),
                            animation = ChartAnimation.None,
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                        )
                    }
                    if (brandTheme) {
                        ChartKitTheme(
                            colors = materialDerivedChartColors(isDark = dark).copy(
                                palette = listOf(
                                    Color(0xFF00695C),
                                    Color(0xFFEF6C00),
                                    Color(0xFF6A1B9A),
                                ),
                            ),
                            dimensions = ChartDimensions(lineWidth = 4.dp, pointRadius = 5.dp),
                            content = body,
                        )
                    } else {
                        body()
                    }
                }
            }
        }

        HorizontalDivider()
        Text("Formatting", style = MaterialTheme.typography.titleSmall)
        Text(
            "Formatters follow the device locale. Currency takes an explicit code — there is " +
                "no sensible default one.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("Compact numbers", style = MaterialTheme.typography.labelLarge)
        BarChart(
            data = ChartDemoData.revenue,
            category = { it.month },
            value = { it.amount },
            valueFormatter = remember { ChartNumberFormatters.compact() },
            valueLabels = true,
            animation = ChartAnimation.None,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )

        Text("Currency", style = MaterialTheme.typography.labelLarge)
        BarChart(
            data = ChartDemoData.revenue,
            category = { it.month },
            value = { it.amount },
            valueFormatter = remember { ChartNumberFormatters.currency("KES") },
            animation = ChartAnimation.None,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )

        Text("Percentages, from a 100% stacked chart", style = MaterialTheme.typography.labelLarge)
        BarChart(
            series = ChartDemoData.cohortSeries(),
            category = { it.quarter },
            value = { it.customers },
            grouping = io.devkit.chartkit.geometry.BarGrouping.StackedPercent,
            animation = ChartAnimation.None,
            modifier = Modifier.fillMaxWidth().height(220.dp),
        )

        Text("Dates on a time axis", style = MaterialTheme.typography.labelLarge)
        LineChart(
            data = ChartDemoData.readings,
            x = { it.atMillis },
            y = { it.celsius },
            xResolver = ChartXResolver.Time,
            xAxis = ChartAxis(
                tickCount = 4,
                timeFormatter = remember { ChartDateFormatters.pattern("HH:mm") },
            ),
            animation = ChartAnimation.None,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
    }
}
