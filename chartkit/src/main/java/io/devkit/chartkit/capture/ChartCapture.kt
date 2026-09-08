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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

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
/**
 * How a capture is rasterised.
 *
 * @param scale multiplies the output's pixel dimensions. `1` captures at the
 *   chart's on-screen size; `3` produces an image that still looks sharp in a
 *   PDF or on a print page, where a screen-resolution capture does not. The
 *   chart is *re-rasterised* at the larger size rather than upscaled, so the
 *   text and the strokes are genuinely sharper.
 * @param background what is drawn behind the chart. `null` leaves it
 *   transparent, which is right for compositing into a document and wrong for
 *   anything that will be viewed on an unknown surface — a chart drawn in
 *   dark-theme colours on a transparent background is invisible on white paper.
 * @param maxDimensionPx a ceiling on either output dimension. A capture at
 *   `scale = 8` of a full-screen chart is a bitmap of tens of megabytes, and an
 *   `OutOfMemoryError` from a share button is a poor way to find that out.
 */
data class ChartCaptureOptions(
    val scale: Float = 1f,
    val background: androidx.compose.ui.graphics.Color? = null,
    val maxDimensionPx: Int = DEFAULT_MAX_DIMENSION,
) {
    init {
        require(scale > 0f && scale.isFinite()) { "Capture scale must be positive, was $scale" }
        require(maxDimensionPx > 0) { "maxDimensionPx must be positive, was $maxDimensionPx" }
    }

    companion object {
        /** Roughly a 4K edge: large enough for print, small enough to allocate. */
        const val DEFAULT_MAX_DIMENSION: Int = 4096

        /** The chart exactly as it appears, transparent behind. */
        val Default: ChartCaptureOptions = ChartCaptureOptions()

        /** Three times the screen resolution, for print and PDF. */
        val HighResolution: ChartCaptureOptions = ChartCaptureOptions(scale = 3f)
    }
}

@Stable
class ChartCaptureState internal constructor() {

    internal var layer: GraphicsLayer? by mutableStateOf(null)
    internal var density: Density? by mutableStateOf(null)
    internal var layoutDirection: LayoutDirection by mutableStateOf(LayoutDirection.Ltr)

    /** True once the chart has drawn at least once and can be captured. */
    val isReady: Boolean get() = layer != null

    /**
     * The chart as an [ImageBitmap].
     *
     * Suspends because rasterising a recording is genuine work and is done off
     * the composition. Throws [IllegalStateException] when the chart has not
     * drawn yet — a capture requested before the first frame has nothing to
     * record, and returning an empty bitmap would hide that rather than report
     * it.
     */
    suspend fun capture(options: ChartCaptureOptions = ChartCaptureOptions.Default): ImageBitmap {
        val recorded = layer ?: error(
            "This chart has not drawn yet, so there is nothing to capture. Capture after the " +
                "first frame — from a button, an effect, or once `isReady` is true.",
        )
        return rasterise(recorded, options)
    }

    /** The image, or `null` when the chart has not drawn yet. */
    suspend fun captureOrNull(
        options: ChartCaptureOptions = ChartCaptureOptions.Default,
    ): ImageBitmap? = layer?.let { rasterise(it, options) }

    /**
     * Draws the recording into a bitmap of the requested size.
     *
     * At `scale = 1` with no background this is the layer's own rasterisation,
     * which is both faster and exact. Anything else needs a canvas of its own:
     * the recording is replayed into it under a scale transform, so the result
     * is re-rendered at the larger size rather than an upscaled screenshot.
     */
    private suspend fun rasterise(
        recorded: GraphicsLayer,
        options: ChartCaptureOptions,
    ): ImageBitmap {
        val recordedSize = recorded.size
        if (recordedSize.width <= 0 || recordedSize.height <= 0) {
            error("This chart measured to zero size, so there is nothing to capture.")
        }
        if (options.scale == 1f && options.background == null) {
            return recorded.toImageBitmap()
        }

        // Capped on the larger edge, so the aspect ratio survives the clamp.
        val requestedWidth = recordedSize.width * options.scale
        val requestedHeight = recordedSize.height * options.scale
        val limit = options.maxDimensionPx.toFloat()
        val fit = minOf(1f, limit / maxOf(requestedWidth, requestedHeight))
        val effectiveScale = options.scale * fit
        val width = (recordedSize.width * effectiveScale).toInt().coerceAtLeast(1)
        val height = (recordedSize.height * effectiveScale).toInt().coerceAtLeast(1)

        val bitmap = ImageBitmap(width, height)
        val canvas = Canvas(bitmap)
        val drawDensity = density ?: Density(1f)
        CanvasDrawScope().draw(
            density = drawDensity,
            layoutDirection = layoutDirection,
            canvas = canvas,
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            options.background?.let { drawRect(it) }
            scale(effectiveScale, effectiveScale, pivot = Offset.Zero) {
                drawLayer(recorded)
            }
        }
        return bitmap
    }
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
    // The density and layout direction the chart was recorded at, kept so a
    // rescaled capture is drawn in the same terms — a re-rasterisation at a
    // different density would move every `Dp`-sized stroke.
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    state.layer = layer
    state.density = density
    state.layoutDirection = direction
    return this.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
}
