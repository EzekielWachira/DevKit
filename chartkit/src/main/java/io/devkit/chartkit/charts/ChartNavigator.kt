package io.devkit.chartkit.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.state.ChartViewportState
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.viewport.ChartViewport
import kotlin.math.abs

/**
 * A small overview chart that drives a large one's viewport.
 *
 * ```kotlin
 * val viewport = rememberChartViewportState()
 *
 * LineChart(
 *     data = readings, x = { it.at }, y = { it.value },
 *     interaction = ChartInteraction.Explore,
 *     viewportState = viewport,
 *     modifier = Modifier.fillMaxWidth().height(240.dp),
 * )
 * ChartNavigator(
 *     data = readings, x = { it.at }, y = { it.value },
 *     viewportState = viewport,
 * )
 * ```
 *
 * ```text
 * MAIN
 * ──────────────────────────
 *
 * OVERVIEW
 * ──────[██████]────────────
 * ```
 *
 * ### The same viewport state, not a copy
 *
 * The navigator writes the *same* [ChartViewportState] the main chart reads.
 * There is no second viewport, no synchronisation and no callback bouncing —
 * dragging the window changes one value that the main chart is already
 * observing.
 *
 * ### Aggressive downsampling
 *
 * The overview shows the whole dataset in a strip a few dozen pixels tall, so
 * it draws with [ChartPerformance.Dense]: every pixel column can hold one
 * point, and drawing fifty thousand of them into fifty of those columns is
 * fifty thousand path segments nobody can see.
 *
 * @param handles whether the window's edges can be dragged to resize it. On by
 *   default; turn it off for a very short navigator, where the two handles
 *   would take most of the window.
 */
@Suppress("LongParameterList")
@Composable
fun <T> ChartNavigator(
    data: List<T>,
    x: (T) -> Any?,
    y: (T) -> Number?,
    viewportState: ChartViewportState,
    modifier: Modifier = Modifier,
    height: Dp? = null,
    handles: Boolean = true,
    xResolver: ChartXResolver = ChartXResolver.Default,
    onWindowChange: ((ChartViewport) -> Unit)? = null,
) {
    ChartNavigator(
        viewportState = viewportState,
        modifier = modifier,
        height = height,
        handles = handles,
        onWindowChange = onWindowChange,
    ) {
        // Axes off, legend off, no interaction of its own: the overview is a
        // shape, not a chart to be read, and any gesture it consumed would be
        // one the navigator's window could not have.
        AreaChart(
            data = data,
            x = x,
            y = y,
            modifier = Modifier.fillMaxSize(),
            xAxis = ChartAxis.Hidden,
            yAxis = ChartAxis.Hidden,
            grid = ChartGrid.None,
            animation = ChartAnimation.None,
            interaction = ChartInteraction.None,
            performance = ChartPerformance.Dense,
            xResolver = xResolver,
            accessibility = ChartAccessibility.Concise,
            tooltip = null,
        )
    }
}

/**
 * A navigator over any overview content.
 *
 * The general form: the window, the mask and the gestures are here, and what is
 * behind them is whatever the caller draws. Useful when the overview should be
 * a different chart from the main one — a candlestick chart navigated by a
 * volume strip, say.
 *
 * @param overview the content the window is drawn over. Should fill the
 *   navigator and take no pointer input of its own.
 */
@Suppress("LongMethod")
@Composable
fun ChartNavigator(
    viewportState: ChartViewportState,
    modifier: Modifier = Modifier,
    height: Dp? = null,
    handles: Boolean = true,
    onWindowChange: ((ChartViewport) -> Unit)? = null,
    overview: @Composable () -> Unit,
) {
    val theme = ChartKitTheme.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val handleWidth = with(androidx.compose.ui.platform.LocalDensity.current) {
        theme.dimensions.navigatorHandleWidth.toPx()
    }

    // What a drag is currently doing. Decided once, when the finger lands, and
    // held for the whole gesture: re-deciding per frame would let a drag that
    // started on a handle become a move as soon as the window slid out from
    // under the finger.
    var mode by remember { mutableStateOf(NavigatorDrag.None) }
    var grabOffset by remember { mutableStateOf(0f) }

    fun fractionAt(positionX: Float): Double {
        val width = size.width.toFloat()
        if (width <= 0f) return 0.0
        return (positionX / width).toDouble().coerceIn(0.0, 1.0)
    }

    fun publish(window: ChartViewport) {
        viewportState.viewport = window
        onWindowChange?.invoke(window)
    }

    Box(
        modifier
            .then(if (height != null) Modifier.height(height) else Modifier.height(theme.dimensions.navigatorHeight))
            .onSizeChanged { size = it },
    ) {
        // The overview is drawn with no content padding, so its plot area is
        // exactly this box — which is what lets the window's pixel positions be
        // computed from the box width rather than from a plot rectangle the
        // navigator would otherwise have to be told about.
        ChartKitTheme(dimensions = theme.dimensions.copy(contentPadding = 0.dp)) {
            overview()
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(size, handles) {
                    detectTapGestures { offset ->
                        // A tap outside the window recentres it there, which is
                        // the fastest way to move a long way.
                        val window = viewportState.viewport
                        val centre = fractionAt(offset.x)
                        publish(ChartViewport.centredOn(centre, window.width))
                    }
                }
                .pointerInput(size, handles) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val window = viewportState.viewport
                            val width = size.width.toFloat()
                            val startX = (window.start * width).toFloat()
                            val endX = (window.end * width).toFloat()
                            mode = when {
                                handles && abs(offset.x - startX) <= handleWidth ->
                                    NavigatorDrag.ResizeStart

                                handles && abs(offset.x - endX) <= handleWidth ->
                                    NavigatorDrag.ResizeEnd

                                offset.x in startX..endX -> NavigatorDrag.Move
                                else -> NavigatorDrag.Move
                            }
                            grabOffset = (fractionAt(offset.x) - window.start).toFloat()
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val window = viewportState.viewport
                            val at = fractionAt(change.position.x)
                            when (mode) {
                                NavigatorDrag.Move ->
                                    publish(ChartViewport.startingAt(at - grabOffset, window.width))

                                NavigatorDrag.ResizeStart ->
                                    publish(ChartViewport.between(at, window.end))

                                NavigatorDrag.ResizeEnd ->
                                    publish(ChartViewport.between(window.start, at))

                                NavigatorDrag.None -> Unit
                            }
                        },
                        onDragEnd = { mode = NavigatorDrag.None },
                        onDragCancel = { mode = NavigatorDrag.None },
                    )
                }
                // One description for the strip, not one per handle: what a
                // reader needs is which part of the data is on screen, and the
                // main chart announces its own viewport too.
                .clearAndSetSemantics {
                    val window = viewportState.viewport
                    contentDescription = "Overview. Showing " +
                        "${Math.round(window.start * 100)} to " +
                        "${Math.round(window.end * 100)} percent of the data."
                },
        ) {
            val window = viewportState.viewport
            val width = this.size.width
            val startX = (window.start * width).toFloat()
            val endX = (window.end * width).toFloat()
            val colours = theme.colors.navigator

            // The *outside* is dimmed rather than the inside tinted, so the data
            // inside the window keeps its true colours — that is the part the
            // reader is about to look at.
            if (startX > 0f) {
                drawRect(colours.mask, Offset.Zero, Size(startX, this.size.height))
            }
            if (endX < width) {
                drawRect(colours.mask, Offset(endX, 0f), Size(width - endX, this.size.height))
            }

            drawRect(colours.window, Offset(startX, 0f), Size(endX - startX, this.size.height))
            drawRect(
                color = colours.windowBorder,
                topLeft = Offset(startX, 0f),
                size = Size(endX - startX, this.size.height),
                style = Stroke(width = 1.dp.toPx()),
            )

            if (handles) {
                val handle = theme.dimensions.navigatorHandleWidth.toPx()
                drawRect(colours.handle, Offset(startX - handle / 2f, 0f), Size(handle, this.size.height))
                drawRect(colours.handle, Offset(endX - handle / 2f, 0f), Size(handle, this.size.height))
            }
        }
    }
}

/** What a drag on the navigator is doing. */
private enum class NavigatorDrag {
    None,
    Move,
    ResizeStart,
    ResizeEnd,
}
