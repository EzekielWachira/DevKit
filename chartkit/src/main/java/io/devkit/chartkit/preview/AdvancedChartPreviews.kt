package io.devkit.chartkit.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.annotation.eventMarker
import io.devkit.chartkit.annotation.horizontalRule
import io.devkit.chartkit.annotation.valueRange
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.charts.BoxPlot
import io.devkit.chartkit.charts.BubbleChart
import io.devkit.chartkit.charts.CalendarHeatmap
import io.devkit.chartkit.charts.CandlestickChart
import io.devkit.chartkit.charts.Heatmap
import io.devkit.chartkit.charts.Histogram
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.charts.OhlcChart
import io.devkit.chartkit.charts.RadarChart
import io.devkit.chartkit.charts.ScatterChart
import io.devkit.chartkit.charts.ViolinPlot
import io.devkit.chartkit.charts.VolumeChart
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.layer.heatmap.HeatmapCellLabels
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.stats.HistogramBins
import java.util.TimeZone

/**
 * Previews of the statistical, density and financial charts.
 *
 * Animation is off throughout, for the same reason it is off in
 * [ChartPreviews]: a preview renders one frame, and an animating chart is
 * caught part-drawn.
 */
private val NoAnimation = ChartAnimation.None

@Preview(name = "Scatter", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun ScatterPreview() {
    PreviewSurface {
        ScatterChart(
            data = ChartKitPreviewData.observations,
            x = { it.height },
            y = { it.weight },
            animation = NoAnimation,
            xAxis = ChartAxis(title = "Height (cm)"),
            yAxis = ChartAxis(title = "Weight (kg)"),
            modifier = Modifier.fillMaxWidth().height(230.dp),
        )
    }
}

@Preview(name = "Bubble", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun BubblePreview() {
    PreviewSurface {
        BubbleChart(
            data = ChartKitPreviewData.observations.take(30),
            x = { it.height },
            y = { it.weight },
            size = { it.age },
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(230.dp),
        )
    }
}

@Preview(name = "Histogram", showBackground = true, widthDp = 360, heightDp = 240)
@Composable
private fun HistogramPreview() {
    PreviewSurface {
        Histogram(
            data = ChartKitPreviewData.responseTimes,
            value = { it },
            bins = HistogramBins.Count(20),
            animation = NoAnimation,
            xAxis = ChartAxis(title = "Response time (ms)"),
            modifier = Modifier.fillMaxWidth().height(210.dp),
        )
    }
}

@Preview(name = "Box plot", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun BoxPlotPreview() {
    PreviewSurface {
        BoxPlot(
            data = ChartKitPreviewData.latencies,
            label = { it.name },
            values = { it.samples },
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(230.dp),
        )
    }
}

@Preview(name = "Violin", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun ViolinPreview() {
    PreviewSurface {
        ViolinPlot(
            data = ChartKitPreviewData.latencies,
            label = { it.name },
            values = { it.samples },
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(230.dp),
        )
    }
}

@Preview(name = "Heatmap", showBackground = true, widthDp = 360, heightDp = 280)
@Composable
private fun HeatmapPreview() {
    PreviewSurface {
        Heatmap(
            data = ChartKitPreviewData.trafficGrid,
            x = { it.day },
            y = { it.hour },
            value = { it.requests },
            cellLabels = HeatmapCellLabels.Auto,
            valueFormatter = ChartNumberFormatters.compact(),
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(250.dp),
        )
    }
}

@Preview(name = "Calendar heatmap", showBackground = true, widthDp = 360, heightDp = 180)
@Composable
private fun CalendarHeatmapPreview() {
    PreviewSurface {
        CalendarHeatmap(
            data = ChartKitPreviewData.dailyActivity,
            date = { it.dateMillis },
            value = { it.count },
            timeZone = TimeZone.getTimeZone("UTC"),
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )
    }
}

@Preview(name = "Radar", showBackground = true, widthDp = 360, heightDp = 320)
@Composable
private fun RadarPreview() {
    PreviewSurface {
        RadarChart(
            series = listOf(
                ChartSeries("q1", "Q1", ChartKitPreviewData.profileQ1),
                ChartSeries("q2", "Q2", ChartKitPreviewData.profileQ2),
            ),
            metric = { it.skill },
            value = { it.value },
            valueRange = 0.0..100.0,
            legend = LegendPosition.Bottom,
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(290.dp),
        )
    }
}

@Preview(name = "Candlestick", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun CandlestickPreview() {
    PreviewSurface {
        CandlestickChart(
            data = ChartKitPreviewData.prices,
            x = { it.timeMillis },
            open = { it.open },
            high = { it.high },
            low = { it.low },
            close = { it.close },
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(230.dp),
        )
    }
}

@Preview(name = "OHLC", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun OhlcPreview() {
    PreviewSurface {
        OhlcChart(
            data = ChartKitPreviewData.prices.take(40),
            x = { it.timeMillis },
            open = { it.open },
            high = { it.high },
            low = { it.low },
            close = { it.close },
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(230.dp),
        )
    }
}

@Preview(name = "Price and volume", showBackground = true, widthDp = 360, heightDp = 320)
@Composable
private fun PriceAndVolumePreview() {
    PreviewSurface {
        CandlestickChart(
            data = ChartKitPreviewData.prices,
            x = { it.timeMillis },
            open = { it.open },
            high = { it.high },
            low = { it.low },
            close = { it.close },
            xAxis = ChartAxis.Hidden,
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
        VolumeChart(
            data = ChartKitPreviewData.prices,
            x = { it.timeMillis },
            volume = { it.volume },
            open = { it.open },
            close = { it.close },
            valueFormatter = ChartNumberFormatters.compact(),
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(90.dp),
        )
    }
}

@Preview(name = "Annotations", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
private fun AnnotationsPreview() {
    PreviewSurface {
        LineChart(
            data = ChartKitPreviewData.revenue,
            x = { it.month },
            y = { it.amount },
            annotations = listOf(
                horizontalRule(value = 40_000.0, label = "Target"),
                valueRange(from = 30_000.0, to = 40_000.0, label = "On track"),
                eventMarker(at = "Apr", value = 39_800.0, label = "Launch"),
            ),
            valueFormatter = ChartNumberFormatters.compact(),
            animation = NoAnimation,
            modifier = Modifier.fillMaxWidth().height(230.dp),
        )
    }
}

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface {
            Column(Modifier.padding(12.dp)) { content() }
        }
    }
}
