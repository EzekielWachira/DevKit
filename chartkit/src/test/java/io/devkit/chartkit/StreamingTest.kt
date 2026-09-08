package io.devkit.chartkit

import io.devkit.chartkit.stream.ChartAggregation
import io.devkit.chartkit.stream.ChartStreamBuffer
import io.devkit.chartkit.stream.ChartStreamCollector
import io.devkit.chartkit.stream.ChartUpdatePolicy
import io.devkit.chartkit.stream.ChartWindow
import io.devkit.chartkit.stream.StreamingChartDataState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private data class Reading(val at: Long, val value: Double)

/** The bounded ring buffer behind every streaming window. */
class ChartStreamBufferTest {

    @Test
    fun `values come back in arrival order`() {
        val buffer = ChartStreamBuffer<Int>(10)
        buffer.addAll(listOf(1, 2, 3))
        assertEquals(listOf(1, 2, 3), buffer.snapshot())
    }

    @Test
    fun `the oldest value is evicted once the buffer is full`() {
        val buffer = ChartStreamBuffer<Int>(3)
        buffer.addAll(listOf(1, 2, 3, 4, 5))
        assertEquals(listOf(3, 4, 5), buffer.snapshot())
        assertEquals(3, buffer.size)
    }

    @Test
    fun `a snapshot is a copy, not a view`() {
        val buffer = ChartStreamBuffer<Int>(5)
        buffer.addAll(listOf(1, 2))
        val snapshot = buffer.snapshot()
        buffer.add(3)
        assertEquals(listOf(1, 2), snapshot)
    }

    @Test
    fun `eviction from the front stops at the first value that stays`() {
        val buffer = ChartStreamBuffer<Int>(10)
        buffer.addAll(listOf(1, 2, 3, 4, 5))
        buffer.evictWhile { it < 3 }
        assertEquals(listOf(3, 4, 5), buffer.snapshot())
    }

    @Test
    fun `growing keeps everything and shrinking keeps the newest`() {
        val buffer = ChartStreamBuffer<Int>(3)
        buffer.addAll(listOf(1, 2, 3))
        buffer.resize(5)
        assertEquals(listOf(1, 2, 3), buffer.snapshot())
        buffer.resize(2)
        assertEquals(listOf(2, 3), buffer.snapshot())
    }

    @Test
    fun `an empty buffer has no last value and clears cleanly`() {
        val buffer = ChartStreamBuffer<Int>(4)
        assertNull(buffer.last())
        buffer.addAll(listOf(1, 2))
        buffer.clear()
        assertTrue(buffer.isEmpty)
        assertEquals(emptyList<Int>(), buffer.snapshot())
    }
}

/**
 * The streaming adapter, on virtual time.
 *
 * No sleeping: `runTest` advances the clock, so a test of a 16-millisecond
 * throttle over a thousand emissions finishes instantly and deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingTest {

    private fun collector(
        state: StreamingChartDataState<Reading>,
        window: ChartWindow,
        policy: ChartUpdatePolicy,
    ) = ChartStreamCollector(state, window, policy, timestamp = { it.at })

    @Test
    fun `an immediate policy publishes every emission`() = runTest {
        val state = StreamingChartDataState<Reading>()
        val source = flow {
            repeat(5) { emit(Reading(it.toLong(), it.toDouble())) }
        }
        collector(state, ChartWindow.Count(10), ChartUpdatePolicy.Immediate).run(source)
        assertEquals(5, state.items.size)
        assertEquals(5L, state.receivedCount)
    }

    @Test
    fun `a count window keeps only the newest values`() = runTest {
        val state = StreamingChartDataState<Reading>()
        val source = flow {
            repeat(100) { emit(Reading(it.toLong(), it.toDouble())) }
        }
        collector(state, ChartWindow.Count(10), ChartUpdatePolicy.Immediate).run(source)
        assertEquals(10, state.items.size)
        assertEquals(90.0, state.items.first().value, 1e-9)
        assertEquals(99.0, state.items.last().value, 1e-9)
    }

    @Test
    fun `a duration window drops values older than the newest minus the duration`() = runTest {
        val state = StreamingChartDataState<Reading>()
        val source = flow {
            // One reading a second for two minutes.
            repeat(120) { emit(Reading(it * 1_000L, it.toDouble())) }
        }
        collector(state, ChartWindow.Duration(30.seconds), ChartUpdatePolicy.Immediate).run(source)
        // Newest is at 119s; the cutoff is 89s, so 89..119 inclusive.
        assertEquals(31, state.items.size)
        assertEquals(89.0, state.items.first().value, 1e-9)
    }

    @Test
    fun `a stalled duration window keeps its data instead of emptying`() = runTest {
        val state = StreamingChartDataState<Reading>()
        val source = flow { repeat(10) { emit(Reading(it * 1_000L, it.toDouble())) } }
        collector(state, ChartWindow.Duration(30.seconds), ChartUpdatePolicy.Immediate).run(source)
        // Measured from the newest sample's own time, not the wall clock, so
        // nothing ages out simply because the test's clock moved on.
        advanceTimeBy(10 * 60 * 1000L)
        assertEquals(10, state.items.size)
    }

    @Test
    fun `throttling publishes the newest value and discards the rest`() = runTest {
        val state = StreamingChartDataState<Reading>()
        val source = MutableSharedFlow<Reading>(extraBufferCapacity = 1000)
        val job = launch {
            collector(state, ChartWindow.Count(100), ChartUpdatePolicy.Throttle(50.milliseconds))
                .run(source)
        }
        advanceTimeBy(1)
        repeat(20) { source.emit(Reading(it.toLong(), it.toDouble())) }
        advanceTimeBy(60)
        // Twenty emissions inside one interval publish one point, the newest.
        assertEquals(1, state.items.size)
        assertEquals(19.0, state.items.single().value, 1e-9)
        job.cancel()
    }

    @Test
    fun `batching keeps every emission of the interval`() = runTest {
        val state = StreamingChartDataState<Reading>()
        val source = MutableSharedFlow<Reading>(extraBufferCapacity = 1000)
        val job = launch {
            collector(state, ChartWindow.Count(100), ChartUpdatePolicy.Batch(50.milliseconds))
                .run(source)
        }
        advanceTimeBy(1)
        repeat(20) { source.emit(Reading(it.toLong(), it.toDouble())) }
        advanceTimeBy(60)
        // Discrete events: nothing may be dropped, or the chart undercounts.
        assertEquals(20, state.items.size)
        job.cancel()
    }

    @Test
    fun `a fast source does not produce one update per emission`() = runTest {
        val state = StreamingChartDataState<Reading>()
        val source = MutableSharedFlow<Reading>(extraBufferCapacity = 5000)
        val job = launch {
            collector(state, ChartWindow.Count(5000), ChartUpdatePolicy.Throttle(16.milliseconds))
                .run(source)
        }
        advanceTimeBy(1)
        // A thousand events across a second, at roughly one per millisecond.
        repeat(1000) {
            source.emit(Reading(it.toLong(), it.toDouble()))
            delay(1)
        }
        // No `advanceUntilIdle` here: the publisher is an intentionally endless
        // loop on the virtual clock, so "idle" never arrives. Advancing the
        // clock through the emissions is what the test is actually about.
        job.cancel()
        assertEquals(1000L, state.receivedCount)
        // About one published point per 16ms interval, not one per event.
        assertTrue("published ${state.items.size}", state.items.size < 100)
    }

    @Test
    fun `min-max aggregation keeps two samples per interval`() = runTest {
        val state = StreamingChartDataState<Reading>()
        val source = MutableSharedFlow<Reading>(extraBufferCapacity = 1000)
        val job = launch {
            collector(
                state,
                ChartWindow.Count(100),
                ChartUpdatePolicy.Aggregate(50.milliseconds, ChartAggregation.MinMax),
            ).run(source)
        }
        advanceTimeBy(1)
        repeat(10) { source.emit(Reading(it.toLong(), it.toDouble())) }
        advanceTimeBy(60)
        assertEquals(2, state.items.size)
        job.cancel()
    }

    @Test
    fun `custom aggregation reduces the interval with the caller's own function`() = runTest {
        val state = StreamingChartDataState<Reading>()
        val source = MutableSharedFlow<Reading>(extraBufferCapacity = 1000)
        val sum = ChartAggregation.Custom<Reading> { batch ->
            listOf(Reading(batch.last().at, batch.sumOf { it.value }))
        }
        val job = launch {
            collector(
                state,
                ChartWindow.Count(100),
                ChartUpdatePolicy.Aggregate(50.milliseconds, sum),
            ).run(source)
        }
        advanceTimeBy(1)
        repeat(5) { source.emit(Reading(it.toLong(), (it + 1).toDouble())) }
        advanceTimeBy(60)
        assertEquals(1, state.items.size)
        assertEquals(15.0, state.items.single().value, 1e-9)
        job.cancel()
    }

    @Test
    fun `a paused stream drops what arrives rather than buffering it`() = runTest {
        val state = StreamingChartDataState<Reading>()
        state.pause()
        val source = MutableSharedFlow<Reading>(extraBufferCapacity = 100)
        val job = launch {
            collector(state, ChartWindow.Count(100), ChartUpdatePolicy.Throttle(10.milliseconds))
                .run(source)
        }
        advanceTimeBy(1)
        repeat(5) { source.emit(Reading(it.toLong(), it.toDouble())) }
        advanceTimeBy(30)
        assertEquals(0, state.items.size)

        // Resuming does not flush a backlog: a live chart that jumped forward
        // through data the reader never saw would be worse than one that lost it.
        state.resume()
        source.emit(Reading(99L, 99.0))
        advanceTimeBy(30)
        assertEquals(1, state.items.size)
        job.cancel()
    }

    @Test
    fun `following the latest data can be turned off and back on`() {
        val state = StreamingChartDataState<Reading>()
        assertTrue(state.followLatest)
        state.stopFollowing()
        assertTrue(!state.followLatest)
        state.jumpToLatest()
        assertTrue(state.followLatest)
    }

    @Test
    fun `clearing empties the window without stopping the stream`() {
        val state = StreamingChartDataState(listOf(Reading(1L, 1.0)))
        state.clear()
        assertEquals(0, state.items.size)
        assertTrue(!state.isPaused)
    }
}
