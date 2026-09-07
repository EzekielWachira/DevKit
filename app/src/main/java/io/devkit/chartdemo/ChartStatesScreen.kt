package io.devkit.chartdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.BarChart
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.formatter.ChartNumberFormatters

/**
 * Loading, empty and error content, edge-case datasets, and the accessibility
 * semantics a screen reader receives.
 *
 * The edge cases below are the ones that produce a `NaN` in a naive chart
 * engine: an empty list, a single point, a constant series, and mixed signs.
 * They are on the demo screen rather than only in the test suite because they
 * are also the ones a developer wants to *see* handled before adopting a
 * library.
 */
@Composable
fun ChartStatesScreen(modifier: Modifier = Modifier) {
    var state by rememberSaveable { mutableStateOf(DemoState.Data) }
    val money = remember { ChartNumberFormatters.compact() }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("States and accessibility", style = MaterialTheme.typography.headlineSmall)

        Text("Loading, empty and error", style = MaterialTheme.typography.titleSmall)
        Text(
            "Each is a composable slot. ChartKit provides a plain default and never owns " +
                "retry — that belongs to the application, with its own backoff and navigation.",
            style = MaterialTheme.typography.bodySmall,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DemoState.entries.forEach { entry ->
                OutlinedButton(
                    onClick = { state = entry },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chartdemo:state:${entry.name}"),
                ) {
                    Text(entry.label)
                }
            }
        }

        LineChart(
            data = if (state == DemoState.Empty) emptyList() else ChartDemoData.revenue,
            x = { it.month },
            y = { it.amount },
            valueFormatter = money,
            isLoading = state == DemoState.Loading,
            error = if (state == DemoState.Error) IllegalStateException("Could not reach the server") else null,
            animation = ChartAnimation.None,
            loadingContent = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            },
            emptyContent = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No revenue recorded yet", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Axes are not drawn for an empty dataset",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            errorContent = { error ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error.message.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .testTag(ChartDemoTestTags.Chart),
        )

        HorizontalDivider()
        Text("Edge cases", style = MaterialTheme.typography.titleSmall)

        Text("A single data point", style = MaterialTheme.typography.labelLarge)
        BarChart(
            data = ChartDemoData.revenue.take(1),
            category = { it.month },
            value = { it.amount },
            valueFormatter = money,
            animation = ChartAnimation.None,
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )

        Text("A constant series", style = MaterialTheme.typography.labelLarge)
        Text(
            "Every value is the same, so the data span is zero. The domain is widened rather " +
                "than divided by.",
            style = MaterialTheme.typography.bodySmall,
        )
        LineChart(
            data = remember { ChartDemoData.revenue.map { it.copy(amount = 42.0) } },
            x = { it.month },
            y = { it.amount },
            animation = ChartAnimation.None,
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )

        Text("A gap in the data", style = MaterialTheme.typography.labelLarge)
        Text(
            "A null reading breaks the line. It is never quietly turned into a zero.",
            style = MaterialTheme.typography.bodySmall,
        )
        LineChart(
            data = ChartDemoData.readings,
            x = { it.atMillis },
            y = { it.celsius },
            xResolver = io.devkit.chartkit.model.ChartXResolver.Time,
            animation = ChartAnimation.None,
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )

        Text("Five thousand points", style = MaterialTheme.typography.labelLarge)
        Text(
            "Markers switch themselves off past the Auto threshold, and the path is built " +
                "once per layout rather than once per frame.",
            style = MaterialTheme.typography.bodySmall,
        )
        LineChart(
            data = remember { ChartDemoData.dense(5_000) },
            x = { it.month.toInt() },
            y = { it.amount },
            animation = ChartAnimation.None,
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )

        HorizontalDivider()
        Text("Accessibility", style = MaterialTheme.typography.titleSmall)
        Text(
            "Turn on TalkBack and focus the chart below. It announces its title, its point " +
                "count and its values — and nothing it has not computed: no trend claims, no " +
                "correlations.",
            style = MaterialTheme.typography.bodySmall,
        )
        BarChart(
            data = ChartDemoData.revenue,
            category = { it.month },
            value = { it.amount },
            valueFormatter = money,
            accessibility = ChartAccessibility(
                title = "Monthly revenue",
                description = "Six months, in Kenyan shillings",
            ),
            animation = ChartAnimation.None,
            modifier = Modifier.fillMaxWidth().height(220.dp),
        )

        Text("A custom summary", style = MaterialTheme.typography.labelLarge)
        BarChart(
            data = ChartDemoData.revenue,
            category = { it.month },
            value = { it.amount },
            accessibilitySummary = {
                "Revenue by month. Highest in May at 44,100; lowest in January at 24,000."
            },
            animation = ChartAnimation.None,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
    }
}

private enum class DemoState(val label: String) {
    Data("Show data"),
    Loading("Show loading"),
    Empty("Show empty"),
    Error("Show error"),
}
