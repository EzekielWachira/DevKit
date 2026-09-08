package io.devkit.chartkit

import io.devkit.chartkit.data.VisibleRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locating the visible window of an ordered series.
 *
 * The interesting cases are all at the edges: a window before the data, after
 * it, containing all of it, containing none of it, and falling between two
 * adjacent points.
 */
class VisibleRangeTest {

    private val values = DoubleArray(100) { it.toDouble() }

    @Test
    fun `a window inside the data selects it plus one point either side`() {
        val range = VisibleRange.of(values, 30.0, 40.0)
        // One beyond each edge, so the line enters and leaves the plot rather
        // than appearing to start partway in.
        assertEquals(29, range.first)
        assertEquals(41, range.last)
    }

    @Test
    fun `a window covering everything selects everything`() {
        val range = VisibleRange.of(values, -10.0, 200.0)
        assertEquals(0, range.first)
        assertEquals(99, range.last)
    }

    @Test
    fun `a window entirely before the data selects nothing`() {
        assertTrue(VisibleRange.of(values, -50.0, -10.0).isEmpty)
    }

    @Test
    fun `a window entirely after the data selects nothing`() {
        assertTrue(VisibleRange.of(values, 200.0, 300.0).isEmpty)
    }

    @Test
    fun `a window between two adjacent points still selects them`() {
        val sparse = doubleArrayOf(0.0, 100.0)
        val range = VisibleRange.of(sparse, 40.0, 60.0)
        assertEquals(0, range.first)
        assertEquals(1, range.last)
    }

    @Test
    fun `an empty dataset gives an empty range`() {
        assertTrue(VisibleRange.of(DoubleArray(0), 0.0, 10.0).isEmpty)
    }

    @Test
    fun `a single item is selected when the window reaches it`() {
        val one = doubleArrayOf(5.0)
        assertEquals(1, VisibleRange.of(one, 0.0, 10.0).size)
        assertTrue(VisibleRange.of(one, 10.0, 20.0).isEmpty)
    }

    @Test
    fun `a non-finite window falls back to the whole dataset`() {
        val range = VisibleRange.of(values, Double.NaN, 10.0)
        assertEquals(0, range.first)
        assertEquals(99, range.last)
    }

    @Test
    fun `an inverted window is read in either order`() {
        assertEquals(VisibleRange.of(values, 30.0, 40.0), VisibleRange.of(values, 40.0, 30.0))
    }

    @Test
    fun `overscan widens the range and clamps at the ends`() {
        val range = VisibleRange.of(values, 30.0, 40.0, overscan = 5)
        assertEquals(24, range.first)
        assertEquals(46, range.last)
        val atStart = VisibleRange.of(values, 0.0, 5.0, overscan = 20)
        assertEquals(0, atStart.first)
    }

    @Test
    fun `lower bound finds the first index at or above the target`() {
        assertEquals(0, VisibleRange.lowerBound(values, -5.0))
        assertEquals(50, VisibleRange.lowerBound(values, 50.0))
        assertEquals(51, VisibleRange.lowerBound(values, 50.5))
        assertEquals(100, VisibleRange.lowerBound(values, 500.0))
    }

    @Test
    fun `upper bound finds the last index at or below the target`() {
        assertEquals(-1, VisibleRange.upperBound(values, -5.0))
        assertEquals(50, VisibleRange.upperBound(values, 50.0))
        assertEquals(50, VisibleRange.upperBound(values, 50.5))
        assertEquals(99, VisibleRange.upperBound(values, 500.0))
    }

    @Test
    fun `ascending detection handles duplicates and reversals`() {
        assertTrue(VisibleRange.isAscending(doubleArrayOf(1.0, 1.0, 2.0)))
        assertTrue(!VisibleRange.isAscending(doubleArrayOf(1.0, 3.0, 2.0)))
        assertTrue(VisibleRange.isAscending(DoubleArray(0)))
    }

    @Test
    fun `overscan is proportional and bounded`() {
        assertEquals(0, VisibleRange.overscanFor(100, 0f))
        assertEquals(10, VisibleRange.overscanFor(100, 0.1f))
        assertTrue(VisibleRange.overscanFor(1_000_000, 0.5f) <= 512)
    }
}
