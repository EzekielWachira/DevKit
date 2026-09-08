package io.devkit.chartdemo

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ValueAxisBinding
import io.devkit.chartkit.capture.ChartCaptureOptions
import io.devkit.chartkit.capture.chartCapture
import io.devkit.chartkit.capture.rememberChartCaptureState
import io.devkit.chartkit.charts.CartesianChart
import io.devkit.chartkit.charts.ExperimentalChartKitApi
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.export.ChartSvg
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.layer.custom.CustomLayerItem
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.scale.AxisScale
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.scene.rememberChartSceneState
import kotlinx.coroutines.launch

/**
 * The extension points: custom layers, alternative scales, a second axis, a
 * deterministic report render and export.
 *
 * The custom-layer section is the one that matters most. It draws a shaded
 * target band and a threshold line using nothing but the public
 * `CartesianLayerScope` — no ChartKit source was changed to add it, and it stays
 * aligned with the data through a zoom because it shares the chart's scales.
 */
@OptIn(ExperimentalChartKitApi::class)
@Composable
fun ChartAdvancedScreen(modifier: Modifier = Modifier) {
    var logScale by remember { mutableStateOf(true) }
    var staticMode by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf<ImageBitmap?>(null) }
    var svgSummary by remember { mutableStateOf<String?>(null) }

    val capture = rememberChartCaptureState()
    val scene = rememberChartSceneState()
    val scope = rememberCoroutineScope()
    val money = remember { ChartNumberFormatters.compact() }
    val plain = remember { ChartNumberFormatters.integer() }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Custom layers", style = MaterialTheme.typography.titleLarge)
        Text(
            "A layer written entirely at the call site. It shares the chart's scales, " +
                "viewport, theme and animation clock, so it stays on the values it names " +
                "through a zoom — which is exactly what a mark computing its own positions " +
                "could not do.",
            style = MaterialTheme.typography.bodyMedium,
        )

        CartesianChart(
            valueDomain = DomainPolicy.Auto(),
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .testTag("custom-layer"),
        ) {
            // Declared *before* the data, so it draws underneath it.
            customLayer(
                id = "target-band",
                seriesName = "Target band",
                describe = {
                    listOf(CustomLayerItem("Target band", detail = "Target band: 24,000 to 32,000"))
                },
            ) {
                val top = positionOfValue(32_000.0)
                val bottom = positionOfValue(24_000.0)
                drawRect(
                    color = colors.annotation.region,
                    topLeft = Offset(plotArea.left, minOf(top, bottom)),
                    size = Size(plotArea.width, kotlin.math.abs(bottom - top)),
                )
                val target = positionOfValue(28_000.0)
                drawLine(
                    color = colors.annotation.line,
                    start = Offset(plotArea.left, target),
                    end = Offset(plotArea.right, target),
                    strokeWidth = px(1.dp),
                )
            }
            line(
                series = listOf(
                    io.devkit.chartkit.model.ChartSeries(
                        id = "revenue",
                        name = "Revenue",
                        data = ChartDemoData.revenue,
                    ),
                ),
                x = { it.month },
                y = { it.amount },
            )
        }

        HorizontalDivider()
        Text("Log and symmetric-log scales", style = MaterialTheme.typography.titleMedium)
        Text(
            "A log axis is a linear mapping of transformed values, so the grid, the ticks, " +
                "the hit testing and the crosshair all work on it unchanged. Latency " +
                "percentiles span four orders of magnitude: on a linear axis, everything " +
                "below p99 is a flat line at the bottom.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = logScale,
                onCheckedChange = { logScale = it },
                modifier = Modifier.testTag("log-scale"),
            )
            Text("  Logarithmic value axis", style = MaterialTheme.typography.bodyMedium)
        }
        LineChart(
            data = ChartDemoData.latencyPercentiles,
            x = { it.month },
            y = { it.amount },
            yAxis = ChartAxis(
                title = "Latency (ms)",
                scale = if (logScale) AxisScale.Log() else AxisScale.Linear,
                valueFormatter = plain,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .testTag("log-chart"),
        )

        HorizontalDivider()
        Text("Secondary axis", style = MaterialTheme.typography.titleMedium)
        Text(
            "Revenue in pounds and conversion in percent. The binding is explicit — ChartKit " +
                "will not guess from the magnitudes, because a chart that silently moved a " +
                "series to the other axis when its numbers changed would be wrong " +
                "intermittently and invisibly.",
            style = MaterialTheme.typography.bodyMedium,
        )
        CartesianChart(
            valueAxis = ChartAxis(title = "Revenue", valueFormatter = money),
            secondaryValueAxis = ChartAxis(title = "Conversion %", valueFormatter = plain),
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .testTag("secondary-axis"),
        ) {
            bars(
                series = listOf(
                    io.devkit.chartkit.model.ChartSeries("revenue", "Revenue", ChartDemoData.revenue),
                ),
                category = { it.month },
                value = { it.amount },
            )
            line(
                series = listOf(
                    io.devkit.chartkit.model.ChartSeries(
                        "conversion",
                        "Conversion %",
                        ChartDemoData.conversion,
                    ),
                ),
                x = { it.month },
                y = { it.amount },
                valueAxis = ValueAxisBinding.Secondary,
            )
        }

        HorizontalDivider()
        Text("Static rendering and export", style = MaterialTheme.typography.titleMedium)
        Text(
            "Static mode installs no pointer input, draws the animation settled and " +
                "suppresses the crosshair and tooltip — so two captures of the same chart at " +
                "the same size are identical.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = staticMode,
                onCheckedChange = { staticMode = it },
                modifier = Modifier.testTag("static-mode"),
            )
            Text(
                if (staticMode) "  Static (report) render" else "  Interactive",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        LineChart(
            data = ChartDemoData.revenue,
            x = { it.month },
            y = { it.amount },
            yAxis = ChartAxis(valueFormatter = money),
            renderMode = if (staticMode) ChartRenderMode.Static else ChartRenderMode.Interactive,
            staticOptions = ChartStaticOptions.Default,
            sceneState = scene,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .chartCapture(capture)
                .testTag("export-chart"),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    scope.launch {
                        captured = capture.capture(ChartCaptureOptions.HighResolution)
                    }
                },
                modifier = Modifier.testTag("capture"),
            ) {
                Text("Capture at 3×")
            }
            TextButton(
                onClick = {
                    val current = scene.scene
                    svgSummary = when {
                        current == null -> "No scene yet — the chart has not drawn."
                        else -> {
                            val svg = ChartSvg.render(current, title = "Monthly revenue")
                            val note = if (current.isComplete) {
                                "every layer exported"
                            } else {
                                "missing: ${current.unexportedLayers.joinToString()}"
                            }
                            "${svg.length} characters of SVG, $note."
                        }
                    }
                },
                modifier = Modifier.testTag("svg"),
            ) {
                Text("Render SVG")
            }
        }

        svgSummary?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("svg-summary"))
        }

        captured?.let { bitmap ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Captured ${bitmap.width}×${bitmap.height} — re-rasterised at three " +
                            "times the screen resolution, not upscaled. Nothing is written to " +
                            "storage, so the demo needs no permission.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Captured chart",
                        modifier = Modifier.fillMaxWidth().testTag("capture-preview"),
                    )
                }
            }
        }

        FilterChip(
            selected = false,
            onClick = { captured = null; svgSummary = null },
            label = { Text("Clear the preview") },
        )
    }
}
