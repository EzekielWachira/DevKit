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
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.charts.CandlestickChart
import io.devkit.chartkit.charts.CartesianChart
import io.devkit.chartkit.charts.ExperimentalChartKitApi
import io.devkit.chartkit.charts.OhlcChart
import io.devkit.chartkit.charts.VolumeChart
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.state.rememberChartInteractionGroup
import io.devkit.chartkit.stats.MovingAverage
import io.devkit.chartkit.viewport.ChartViewport

private data class MaPoint(val timeMillis: Long, val value: Double)

/**
 * Price and volume, sharing one viewport and one crosshair.
 *
 * The point of the screen is the pair, not either chart: dragging or pinching
 * one moves the other, and scrubbing one reports a value on both — because the
 * two are given the same two pieces of state, not because either knows the
 * other exists.
 */
@OptIn(ExperimentalChartKitApi::class)
@Composable
fun ChartFinancialScreen(modifier: Modifier = Modifier) {
    var ohlcBars by rememberSaveable { mutableStateOf(false) }
    var showVolume by rememberSaveable { mutableStateOf(true) }
    var movingAverage by rememberSaveable { mutableStateOf(false) }
    var readout by rememberSaveable { mutableStateOf("Scrub the chart") }

    val group = rememberChartInteractionGroup()
    val prices = ChartDemoData.prices

    val ma20 = remember(prices) {
        MovingAverage.simple(ChartDemoData.closes, 20)
            .mapIndexedNotNull { index, value ->
                value?.let { MaPoint(prices[index].timeMillis, it) }
            }
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Financial charts", style = MaterialTheme.typography.titleLarge)
        Text(
            "Five lambdas over the app's own `Candle`. Rising and falling take the theme's " +
                "semantic increase and decrease colours — nothing here knows that up is green, " +
                "which is not a universal convention and is invisible to a reader with " +
                "red-green colour vision deficiency.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !ohlcBars,
                onClick = { ohlcBars = false },
                label = { Text("Candles") },
                modifier = Modifier.testTag("mark-candle"),
            )
            FilterChip(
                selected = ohlcBars,
                onClick = { ohlcBars = true },
                label = { Text("OHLC bars") },
                modifier = Modifier.testTag("mark-ohlc"),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = showVolume,
                onCheckedChange = { showVolume = it },
                modifier = Modifier.testTag("show-volume"),
            )
            Text("  Linked volume chart", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = movingAverage,
                onCheckedChange = { movingAverage = it },
                modifier = Modifier.testTag("show-ma"),
            )
            Text("  20-period moving average", style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { group.viewport.viewport = ChartViewport.trailing(0.25) },
                modifier = Modifier.testTag("zoom-recent"),
            ) { Text("Last quarter") }
            Button(
                onClick = { group.viewport.reset() },
                modifier = Modifier.testTag("reset-zoom"),
            ) { Text("Full range") }
        }

        HorizontalDivider()

        when {
            movingAverage -> {
                // Candles and a line in one chart: one plot area, one pair of
                // scales, one hit test. The combined-chart grammar, applied to
                // a financial series.
                Text(
                    "Candles and a moving-average line as two layers of one chart.",
                    style = MaterialTheme.typography.bodySmall,
                )
                CartesianChart(
                    domainAxis = if (showVolume) ChartAxis.Hidden else ChartAxis.Default,
                    valueAxis = ChartAxis(title = "Price"),
                    viewportState = group.viewport,
                    sharedCrosshair = group.crosshair,
                    interaction = ChartInteraction.Explorable,
                    crosshair = io.devkit.chartkit.interaction.CrosshairConfig.Both,
                    onSelectionChanged = { selection ->
                        readout = selection?.let { "${it.seriesName}: ${"%.2f".format(it.y)}" }
                            ?: "Scrub the chart"
                    },
                    modifier = Modifier.fillMaxWidth().height(300.dp).testTag(PRICE),
                ) {
                    candles(
                        data = prices,
                        x = { it.timeMillis },
                        open = { it.open },
                        high = { it.high },
                        low = { it.low },
                        close = { it.close },
                        volume = { it.volume },
                    )
                    line(
                        series = listOf(ChartSeries("ma20", "MA 20", ma20)),
                        x = { it.timeMillis },
                        y = { it.value },
                        xResolver = io.devkit.chartkit.model.ChartXResolver.Time,
                    )
                }
            }

            ohlcBars -> OhlcChart(
                data = prices,
                x = { it.timeMillis },
                open = { it.open },
                high = { it.high },
                low = { it.low },
                close = { it.close },
                viewportState = group.viewport,
                sharedCrosshair = group.crosshair,
                yAxis = ChartAxis(title = "Price"),
                valueFormatter = ChartNumberFormatters.decimal(2),
                onSelectionChanged = { selection ->
                    readout = selection?.item?.let {
                        "O ${"%.2f".format(it.open)}  H ${"%.2f".format(it.high)}  " +
                            "L ${"%.2f".format(it.low)}  C ${"%.2f".format(it.close)}"
                    } ?: "Scrub the chart"
                },
                modifier = Modifier.fillMaxWidth().height(300.dp).testTag(PRICE),
            )

            else -> CandlestickChart(
                data = prices,
                x = { it.timeMillis },
                open = { it.open },
                high = { it.high },
                low = { it.low },
                close = { it.close },
                volume = { it.volume },
                viewportState = group.viewport,
                sharedCrosshair = group.crosshair,
                yAxis = ChartAxis(title = "Price"),
                xAxis = if (showVolume) ChartAxis.Hidden else ChartAxis.Default,
                onSelectionChanged = { selection ->
                    readout = selection?.item?.let {
                        "O ${"%.2f".format(it.open)}  H ${"%.2f".format(it.high)}  " +
                            "L ${"%.2f".format(it.low)}  C ${"%.2f".format(it.close)}"
                    } ?: "Scrub the chart"
                },
                modifier = Modifier.fillMaxWidth().height(300.dp).testTag(PRICE),
            )
        }

        if (showVolume) {
            VolumeChart(
                data = prices,
                x = { it.timeMillis },
                volume = { it.volume },
                open = { it.open },
                close = { it.close },
                viewportState = group.viewport,
                sharedCrosshair = group.crosshair,
                valueFormatter = ChartNumberFormatters.compact(),
                modifier = Modifier.fillMaxWidth().height(110.dp).testTag(VOLUME),
            )
            Text(
                "Both charts read one ChartViewportState and one shared crosshair. Zoom, pan " +
                    "or scrub either and the other follows — they are aligned by date, not by " +
                    "pixel, so a gap in one does not push the other out of step.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()
        Text(readout, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(READOUT))
        Text(
            "Zoom ${"%.1f".format(group.viewport.zoom)}×",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag(ZOOM),
        )
    }
}

private const val PRICE = "financial-price"
private const val VOLUME = "financial-volume"
private const val READOUT = "financial-readout"
private const val ZOOM = "financial-zoom"
