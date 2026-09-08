package io.devkit.chartkit.stream

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/**
 * Collects a flow into a bounded window and publishes it on an interval.
 *
 * Extracted from the composable so the windowing, throttling and aggregation
 * rules can be exercised directly — with virtual time, and without composing
 * anything or sleeping.
 *
 * ### Two coroutines, deliberately
 *
 * One collects at the producer's full rate and never suspends on the display;
 * the other publishes on the interval. Collecting and publishing in one loop
 * would mean either dropping the collector's throughput to the frame rate —
 * which pushes backpressure onto a sensor or a socket that has nowhere to put
 * it — or publishing on every emission, which is the problem being solved.
 */
internal class ChartStreamCollector<T>(
    private val state: StreamingChartDataState<T>,
    private val window: ChartWindow,
    private val policy: ChartUpdatePolicy,
    private val timestamp: ((T) -> Long)?,
) {
    private val buffer = ChartStreamBuffer<T>(initialCapacity(window))

    /** Values received since the last publication. */
    private val pending = ArrayList<T>()

    private val lock = Any()

    suspend fun run(flow: Flow<T>) {
        when (policy) {
            ChartUpdatePolicy.Immediate -> flow.collect { value ->
                if (state.isPaused) return@collect
                synchronized(lock) {
                    state.receivedCount++
                    accept(listOf(value))
                }
                publish()
            }

            else -> coroutineScope {
                val interval = policy.intervalMillis()
                launch {
                    flow.collect { value ->
                        if (state.isPaused) return@collect
                        // Counted on arrival, not on publication: comparing
                        // what arrived against what is drawn is how a caller
                        // sees the throttle working.
                        synchronized(lock) {
                            state.receivedCount++
                            pending += value
                        }
                    }
                }
                // Publishes on the interval whether or not anything arrived; a
                // tick with nothing pending costs one comparison.
                while (currentCoroutineContext().isActive) {
                    delay(interval)
                    val batch = synchronized(lock) {
                        if (pending.isEmpty()) {
                            emptyList()
                        } else {
                            val copy = ArrayList(pending)
                            pending.clear()
                            copy
                        }
                    }
                    if (batch.isEmpty()) continue
                    synchronized(lock) { accept(reduce(batch)) }
                    publish()
                }
            }
        }
    }

    /** The values an interval's worth of emissions contributes to the window. */
    private fun reduce(batch: List<T>): List<T> = when (policy) {
        ChartUpdatePolicy.Immediate -> batch
        // The newest supersedes the rest: right for a measurement, and the
        // reason `Batch` exists for streams where it is not.
        is ChartUpdatePolicy.Throttle -> listOfNotNull(batch.lastOrNull())
        is ChartUpdatePolicy.Batch -> batch
        is ChartUpdatePolicy.Aggregate -> aggregate(batch, policy.aggregation)
    }

    @Suppress("UNCHECKED_CAST")
    private fun aggregate(batch: List<T>, aggregation: ChartAggregation): List<T> = when (aggregation) {
        ChartAggregation.Latest -> listOfNotNull(batch.lastOrNull())
        // Average and MinMax need a numeric view of `T`, which a generic
        // adapter does not have. Rather than demand an accessor on every
        // stream — including the ones that never aggregate — the generic
        // fallbacks pick real samples: the middle one for Average, the first
        // and last for MinMax. A stream that needs true arithmetic supplies
        // `ChartAggregation.Custom`, which is exact and typed.
        ChartAggregation.Average -> listOfNotNull(batch.getOrNull(batch.size / 2))
        ChartAggregation.MinMax ->
            if (batch.size <= 1) batch else listOf(batch.first(), batch.last())
        is ChartAggregation.Custom<*> -> (aggregation as ChartAggregation.Custom<T>).reduce(batch)
    }

    /** Appends to the window and evicts whatever falls outside it. */
    private fun accept(values: List<T>) {
        if (values.isEmpty()) return
        buffer.addAll(values)

        if (window is ChartWindow.Duration) {
            val reader = timestamp ?: return
            val newest = buffer.last()?.let(reader) ?: return
            // Measured from the newest value's own time, not the wall clock: a
            // stalled stream keeps showing its last minute rather than emptying
            // itself, and a replayed stream behaves like a live one.
            val cutoff = newest - window.duration.inWholeMilliseconds
            buffer.evictWhile { reader(it) < cutoff }
        }
    }

    private fun publish() {
        state.items = buffer.snapshot()
    }

    private fun initialCapacity(window: ChartWindow): Int = when (window) {
        is ChartWindow.Count -> window.size
        // A duration window is bounded by time rather than by count, so the
        // buffer is sized generously and trimmed by timestamp. The cap is what
        // stops a misconfigured stream — a duration window with no timestamp
        // lambda — from growing without limit.
        is ChartWindow.Duration -> DURATION_WINDOW_CAPACITY
        ChartWindow.Unbounded -> UNBOUNDED_CAPACITY
    }

    private fun ChartUpdatePolicy.intervalMillis(): Long = when (this) {
        ChartUpdatePolicy.Immediate -> 0L
        is ChartUpdatePolicy.Throttle -> interval.inWholeMilliseconds.coerceAtLeast(1L)
        is ChartUpdatePolicy.Batch -> interval.inWholeMilliseconds.coerceAtLeast(1L)
        is ChartUpdatePolicy.Aggregate -> interval.inWholeMilliseconds.coerceAtLeast(1L)
    }

    private companion object {
        /** Far more than a duration window will hold at any sane rate. */
        const val DURATION_WINDOW_CAPACITY = 20_000

        /**
         * The ceiling on [ChartWindow.Unbounded].
         *
         * "Unbounded" is bounded, because a chart that grows until the process
         * dies is not a feature. It is set well past what any chart can render
         * meaningfully, and the eviction is documented rather than silent.
         */
        const val UNBOUNDED_CAPACITY = 200_000
    }
}
