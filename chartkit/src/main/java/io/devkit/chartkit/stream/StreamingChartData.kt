package io.devkit.chartkit.stream

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.devkit.chartkit.state.ChartViewportState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * The data a live chart is currently showing, and the controls over it.
 *
 * ```kotlin
 * val stream = rememberStreamingChartData(
 *     flow = sensor.readings,
 *     window = ChartWindow.Duration(60.seconds),
 *     timestamp = { it.atMillis },
 * )
 *
 * LineChart(data = stream.items, x = { it.atMillis }, y = { it.value })
 * ```
 *
 * ### Streaming is an addition, never a replacement
 *
 * `stream.items` is an ordinary `List<T>` and the chart is an ordinary chart.
 * Nothing about `LineChart` knows a stream exists, and a chart of a plain list
 * needs none of this. That is deliberate: a streaming variant of every chart
 * would have doubled the public surface and halved the confidence that the two
 * behave identically.
 *
 * ### Snapshots, not shared mutation
 *
 * [items] is replaced with a new immutable list, never mutated in place. A
 * sample arriving while a path is being built therefore cannot change the data
 * underneath it — which is a rare, intermittent crash rather than a wrong
 * chart, and so is worth ruling out structurally.
 */
@Stable
class StreamingChartDataState<T> internal constructor(
    initial: List<T> = emptyList(),
) {
    /** The values currently in the window, oldest first. */
    var items: List<T> by mutableStateOf(initial)
        internal set

    /**
     * Whether new values are being accepted.
     *
     * Pausing keeps what is on screen and drops what arrives, which is what a
     * reader wants when they have stopped to look at something. It does **not**
     * buffer: a paused live chart that flushed a backlog on resume would jump
     * forward through data the reader never saw.
     */
    var isPaused: Boolean by mutableStateOf(false)

    /**
     * Whether a zoomed viewport follows the newest data.
     *
     * Turns itself off when the reader pans back into the history — following
     * would otherwise drag them forward again on the next sample, which makes a
     * live chart impossible to read. [jumpToLatest] turns it back on.
     */
    var followLatest: Boolean by mutableStateOf(true)
        internal set

    /** How many values have arrived since the state was created. */
    var receivedCount: Long by mutableLongStateOf(0L)
        internal set

    /** The most recent value, or `null`. */
    val latest: T? get() = items.lastOrNull()

    /** Stops accepting new values. */
    fun pause() {
        isPaused = true
    }

    /** Starts accepting them again. */
    fun resume() {
        isPaused = false
    }

    /** Empties the window. */
    fun clear() {
        items = emptyList()
    }

    /**
     * Returns to the newest data and resumes following it.
     *
     * The way back after exploring the history — the "jump to latest" a live
     * chart needs once panning has turned following off.
     */
    fun jumpToLatest() {
        followLatest = true
    }

    /** Stops following without pausing the stream. */
    fun stopFollowing() {
        followLatest = false
    }
}

/**
 * Collects [flow] into a bounded, throttled window a chart can draw.
 *
 * ```kotlin
 * val stream = rememberStreamingChartData(
 *     flow = viewModel.prices,
 *     window = ChartWindow.Count(500),
 *     policy = ChartUpdatePolicy.Throttle(50.milliseconds),
 * )
 * ```
 *
 * The adapter, not the chart, collects — so a chart composable never becomes a
 * collector, and swapping a live source for a static list changes one line.
 *
 * ### Backpressure
 *
 * Under [ChartUpdatePolicy.Throttle], [ChartUpdatePolicy.Batch] and
 * [ChartUpdatePolicy.Aggregate] the collector keeps consuming at full rate and
 * only *publishes* on the interval. That matters: suspending the collector
 * instead would apply backpressure to the producer, which for a sensor or a
 * socket means either a growing queue somewhere else or dropped events nobody
 * accounted for. Emissions between publications are handled according to the
 * policy — discarded, kept, or summarised — and the choice is explicit.
 *
 * ### The duration window's "now"
 *
 * A [ChartWindow.Duration] measures back from the **newest value's own
 * timestamp**, not from the wall clock. A stream that stalls therefore keeps
 * showing its last minute of data rather than emptying itself, and a replayed
 * or virtual-time stream behaves identically to a live one — which is also what
 * makes it testable without sleeping.
 *
 * @param timestamp reads a value's own time, in epoch milliseconds. Required by
 *   [ChartWindow.Duration] and ignored otherwise. Never inferred from arrival
 *   order: an event that arrived late still happened when it happened.
 * @param viewport optional. When supplied, a zoomed chart follows the newest
 *   data until the reader pans back into the history.
 * @param key restarts collection when it changes — a switched symbol, a new
 *   sensor. Without it a changed flow would be collected alongside the old one.
 */
@Suppress("LongParameterList")
@Composable
fun <T> rememberStreamingChartData(
    flow: Flow<T>,
    window: ChartWindow = ChartWindow.Default,
    policy: ChartUpdatePolicy = ChartUpdatePolicy.Default,
    timestamp: ((T) -> Long)? = null,
    viewport: ChartViewportState? = null,
    key: Any? = Unit,
): StreamingChartDataState<T> {
    val state = remember(key) { StreamingChartDataState<T>() }

    LaunchedEffect(flow, window, policy, timestamp, key) {
        val collector = ChartStreamCollector(state, window, policy, timestamp)
        collector.run(flow)
    }

    // Follow-latest, expressed entirely in terms of the viewport's public
    // window. A viewport that is no longer trailing is one the reader panned,
    // and following it forward again on the next sample would make the history
    // unreadable — so following turns itself off instead.
    if (viewport != null) {
        LaunchedEffect(state.items, state.followLatest, viewport) {
            if (viewport.isFullyZoomedOut) return@LaunchedEffect
            if (!state.followLatest) return@LaunchedEffect
            if (!viewport.viewport.isTrailing) {
                state.stopFollowing()
                return@LaunchedEffect
            }
            viewport.showTrailing(viewport.viewport.width)
        }
        LaunchedEffect(state.followLatest, viewport) {
            if (state.followLatest && !viewport.isFullyZoomedOut) {
                viewport.showTrailing(viewport.viewport.width)
            }
        }
    }

    return state
}

/** Convenience for a `StateFlow`, whose current value is already available. */
@Suppress("LongParameterList")
@Composable
fun <T> rememberStreamingChartData(
    flow: kotlinx.coroutines.flow.StateFlow<T>,
    window: ChartWindow = ChartWindow.Default,
    policy: ChartUpdatePolicy = ChartUpdatePolicy.Default,
    timestamp: ((T) -> Long)? = null,
    viewport: ChartViewportState? = null,
    key: Any? = Unit,
): StreamingChartDataState<T> = rememberStreamingChartData(
    flow = flow as Flow<T>,
    window = window,
    policy = policy,
    timestamp = timestamp,
    viewport = viewport,
    key = key,
)
