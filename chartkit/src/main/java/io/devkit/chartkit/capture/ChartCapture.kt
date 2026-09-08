package io.devkit.chartkit.capture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

/**
 * Captures a chart as an image.
 *
 * ```kotlin
 * val capture = rememberChartCaptureState()
 * val scope = rememberCoroutineScope()
 *
 * LineChart(
 *     data = revenue, x = { it.month }, y = { it.amount },
 *     modifier = Modifier.fillMaxWidth().height(240.dp).chartCapture(capture),
 * )
 * Button(onClick = { scope.launch { share(capture.capture()) } }) { Text("Share") }
 * ```
 *
 * ### Compose records it; nothing is screenshotted
 *
 * The capture goes through Compose's own `GraphicsLayer`: the composable is
 * recorded as it draws and the recording is rasterised on demand. Nothing reads
 * the window, nothing needs a `View`, nothing depends on the chart being on
 * screen unobscured, and no permission is involved. The brittle alternatives —
 * `PixelCopy` over the window, drawing a `View` into a `Canvas`, a
 * `MediaProjection` — all fail differently on different manufacturers' builds,
 * and all of them capture whatever happens to be in front of the chart.
 *
 * ### A modifier, not a chart parameter
 *
 * Because it is a `Modifier`, it captures **whatever it is applied to**: one
 * chart, a chart with its own title and legend around it, or a whole dashboard
 * of them. A `capture = true` parameter on every chart would have limited it to
 * exactly one chart and would have needed adding to each of them.
 *
 * ### What is in the image
 *
 * Everything the modified composable draws: the plot, the axes, the
 * annotations, and the legend when it is inside the same composable — which for
 * ChartKit's own charts it is, since the legend is part of the chart. A tooltip
 * or a dropdown rendered in a `Popup` or a `Dialog` is drawn in a **separate
 * window** and is not part of this composable, so it is not captured. Content
 * outside the modified composable is likewise not captured.
 *
 * @see chartCapture
 */
@Stable
class ChartCaptureState internal constructor() {

    internal var layer: GraphicsLayer? by mutableStateOf(null)

    /** True once the chart has drawn at least once and can be captured. */
    val isReady: Boolean get() = layer != null

    /**
     * The chart as an [ImageBitmap], at its on-screen size and density.
     *
     * Suspends because rasterising a recording is genuine work and is done off
     * the composition. Throws [IllegalStateException] when the chart has not
     * drawn yet — a capture requested before the first frame has nothing to
     * record, and returning an empty bitmap would hide that rather than report
     * it.
     */
    suspend fun capture(): ImageBitmap {
        val recorded = layer ?: error(
            "This chart has not drawn yet, so there is nothing to capture. Capture after the " +
                "first frame — from a button, an effect, or once `isReady` is true.",
        )
        return recorded.toImageBitmap()
    }

    /** The image, or `null` when the chart has not drawn yet. */
    suspend fun captureOrNull(): ImageBitmap? = layer?.toImageBitmap()
}

/** Remembers a [ChartCaptureState]. */
@Composable
fun rememberChartCaptureState(): ChartCaptureState = remember { ChartCaptureState() }

/**
 * Records what this composable draws, so [ChartCaptureState.capture] can
 * rasterise it.
 *
 * The content is drawn into a layer and the layer is drawn to the screen, so
 * the chart looks and behaves exactly as it would without the modifier — the
 * recording is a side effect of drawing, not a second pass.
 */
@Composable
fun Modifier.chartCapture(state: ChartCaptureState): Modifier {
    val layer = rememberGraphicsLayer()
    state.layer = layer
    return this.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
}
