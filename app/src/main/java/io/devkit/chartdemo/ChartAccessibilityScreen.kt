package io.devkit.chartdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.accessibility.ChartDataTableView
import io.devkit.chartkit.accessibility.chartDataTable
import io.devkit.chartkit.accessibility.ohlcDataTable
import io.devkit.chartkit.capture.chartCapture
import io.devkit.chartkit.capture.rememberChartCaptureState
import io.devkit.chartkit.charts.CandlestickChart
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.model.ChartSeries
import kotlinx.coroutines.launch

/**
 * What a chart tells assistive technology, and the escape hatch when a summary
 * is not enough.
 *
 * The summary makes a `Canvas` describable; a table makes it navigable. The
 * table is opt-in rather than always present, because a hidden table attached
 * to every chart would double the semantics tree of every screen for a facility
 * most of them do not need.
 */
@Composable
fun ChartAccessibilityScreen(modifier: Modifier = Modifier) {
    var showTable by rememberSaveable { mutableStateOf(false) }
    var showPriceTable by rememberSaveable { mutableStateOf(false) }
    var captureNote by rememberSaveable { mutableStateOf("") }

    val capture = rememberChartCaptureState()
    val scope = rememberCoroutineScope()

    val series = remember {
        listOf(
            ChartSeries("revenue", "Revenue", ChartDemoData.revenue),
            ChartSeries("expenses", "Expenses", ChartDemoData.expenses),
        )
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Accessibility", style = MaterialTheme.typography.titleLarge)
        Text(
            "Turn on TalkBack and focus the chart. The description is generated from the data " +
                "and is strictly factual — counts, values, ranges, and what is selected. It " +
                "never says \"trending upward\", because that is a claim ChartKit has not " +
                "computed and a confidently wrong one is worse than none.",
            style = MaterialTheme.typography.bodyMedium,
        )

        LineChart(
            series = series,
            x = { it.month },
            y = { it.amount },
            accessibility = ChartAccessibility(
                title = "Revenue and expenses, first half",
                description = "Monthly, in pounds",
            ),
            valueFormatter = ChartNumberFormatters.compact(),
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .chartCapture(capture)
                .testTag(CHART),
        )

        TextButton(
            onClick = { showTable = !showTable },
            modifier = Modifier.testTag("toggle-table"),
        ) {
            Text(if (showTable) "Hide data table" else "View data table")
        }

        if (showTable) {
            ChartDataTableView(
                table = chartDataTable(
                    series = series,
                    category = { it.month },
                    value = { it.amount },
                    valueFormatter = ChartNumberFormatters.integer(),
                    caption = "Revenue and expenses, first half",
                ),
                modifier = Modifier.testTag(TABLE),
            )
            Text(
                "Each row is one semantics node reading \"Category: Jan, Series: Revenue, " +
                    "Value: 24,000\". A node per cell would be richer and worse — the column " +
                    "name would be somewhere else by the time the reader reached the number.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()

        Text("Typed adapters for specialised data", style = MaterialTheme.typography.titleSmall)
        Text(
            "A candle is four numbers, so a generic \"category, value\" table would have to " +
                "choose one of them. The adapters are typed lambdas, like every other accessor " +
                "in ChartKit — no reflection anywhere.",
            style = MaterialTheme.typography.bodyMedium,
        )

        CandlestickChart(
            data = ChartDemoData.prices.takeLast(20),
            x = { it.timeMillis },
            open = { it.open },
            high = { it.high },
            low = { it.low },
            close = { it.close },
            accessibility = ChartAccessibility(title = "Daily prices, last four weeks"),
            modifier = Modifier.fillMaxWidth().height(220.dp).testTag(PRICE),
        )

        TextButton(
            onClick = { showPriceTable = !showPriceTable },
            modifier = Modifier.testTag("toggle-price-table"),
        ) {
            Text(if (showPriceTable) "Hide price table" else "View price table")
        }

        if (showPriceTable) {
            ChartDataTableView(
                table = ohlcDataTable(
                    data = ChartDemoData.prices.takeLast(20),
                    date = { java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                        .format(java.util.Date(it.timeMillis)) },
                    open = { it.open },
                    high = { it.high },
                    low = { it.low },
                    close = { it.close },
                    valueFormatter = ChartNumberFormatters.decimal(2),
                    caption = "Daily prices, last four weeks",
                ),
                modifier = Modifier.testTag(PRICE_TABLE),
            )
        }

        HorizontalDivider()

        Text("Capture", style = MaterialTheme.typography.titleSmall)
        Text(
            "The chart above is recorded through Compose's own graphics layer — not " +
                "screenshotted — so it does not matter what is in front of it or whether it is " +
                "fully on screen.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        val bitmap = capture.captureOrNull()
                        captureNote = bitmap
                            ?.let { "Captured ${it.width} × ${it.height} pixels" }
                            ?: "The chart has not drawn yet"
                    }
                },
                modifier = Modifier.testTag("capture"),
            ) { Text("Capture chart") }
        }
        if (captureNote.isNotEmpty()) {
            Text(captureNote, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(NOTE))
        }
    }
}

private const val CHART = "a11y-chart"
private const val TABLE = "a11y-table"
private const val PRICE = "a11y-price"
private const val PRICE_TABLE = "a11y-price-table"
private const val NOTE = "a11y-note"
