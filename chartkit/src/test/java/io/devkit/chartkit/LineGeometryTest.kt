package io.devkit.chartkit

import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.LinePoint
import io.devkit.chartkit.geometry.isXOrdered
import io.devkit.chartkit.geometry.monotoneControlPoints
import io.devkit.chartkit.geometry.nearestPointIndex
import io.devkit.chartkit.geometry.segmentLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class LineGeometryTest {

    private fun point(x: Float, y: Float, index: Int = 0) =
        LinePoint(ChartOffset(x, y), index, y.toDouble())

    @Test
    fun `a run with no gaps is a single segment`() {
        val segments = segmentLine(listOf(point(0f, 0f), point(1f, 1f), point(2f, 2f)))
        assertEquals(1, segments.size)
        assertEquals(3, segments.single().points.size)
    }

    @Test
    fun `a missing value breaks the line into two segments`() {
        val segments = segmentLine(listOf(point(0f, 0f), null, point(2f, 2f)))
        assertEquals(2, segments.size)
        assertEquals(1, segments[0].points.size)
        assertEquals(1, segments[1].points.size)
    }

    @Test
    fun `leading and trailing gaps produce no empty segments`() {
        val segments = segmentLine(listOf(null, point(1f, 1f), null))
        assertEquals(1, segments.size)
    }

    @Test
    fun `an all-missing series has no segments`() {
        assertTrue(segmentLine(listOf(null, null)).isEmpty())
    }

    @Test
    fun `a single point is its own segment`() {
        assertEquals(1, segmentLine(listOf(point(0f, 0f))).size)
    }

    @Test
    fun `monotone control points do not overshoot a local maximum`() {
        // 10, 90, 10 is the classic overshoot case: a naive spline arcs above
        // 90 and below 10, inventing values the data never held.
        val points = listOf(point(0f, 10f), point(100f, 90f), point(200f, 10f))
        val (c0, c1) = monotoneControlPoints(points, 0)!!
        val (c2, c3) = monotoneControlPoints(points, 1)!!
        val lowest = minOf(c0.y, c1.y, c2.y, c3.y)
        val highest = maxOf(c0.y, c1.y, c2.y, c3.y)
        assertTrue("control point above the data: $highest", highest <= 90f + 1e-3f)
        assertTrue("control point below the data: $lowest", lowest >= 10f - 1e-3f)
    }

    @Test
    fun `monotone control points stay within a monotone run`() {
        val points = listOf(point(0f, 0f), point(10f, 10f), point(20f, 20f), point(30f, 30f))
        for (index in 0 until points.size - 1) {
            val (c0, c1) = monotoneControlPoints(points, index)!!
            val lower = min(points[index].position.y, points[index + 1].position.y)
            val upper = max(points[index].position.y, points[index + 1].position.y)
            assertTrue(c0.y >= lower - 1e-3f && c0.y <= upper + 1e-3f)
            assertTrue(c1.y >= lower - 1e-3f && c1.y <= upper + 1e-3f)
        }
    }

    @Test
    fun `coincident x produces no control points, so the caller draws straight`() {
        val points = listOf(point(10f, 0f), point(10f, 50f))
        assertNull(monotoneControlPoints(points, 0))
    }

    @Test
    fun `control points are always finite`() {
        val points = listOf(point(0f, 0f), point(1f, 1000000f), point(2f, -1000000f), point(3f, 0f))
        for (index in 0 until points.size - 1) {
            monotoneControlPoints(points, index)?.let { (a, b) ->
                assertTrue(a.isFinite && b.isFinite)
            }
        }
    }

    @Test
    fun `nearest point on sorted data uses binary search and finds the right one`() {
        val points = (0..100).map { point(it * 10f, 0f, it) }
        assertEquals(0, nearestPointIndex(points, 0f, sorted = true))
        assertEquals(50, nearestPointIndex(points, 502f, sorted = true))
        assertEquals(100, nearestPointIndex(points, 5000f, sorted = true))
    }

    @Test
    fun `sorted and unsorted searches agree on sorted data`() {
        val points = (0..50).map { point(it * 7f, 0f, it) }
        listOf(-10f, 0f, 3f, 88f, 350f, 1000f).forEach { x ->
            assertEquals(
                "disagreement at $x",
                nearestPointIndex(points, x, sorted = false),
                nearestPointIndex(points, x, sorted = true),
            )
        }
    }

    @Test
    fun `an empty series has no nearest point`() {
        assertEquals(-1, nearestPointIndex(emptyList(), 0f, sorted = true))
    }

    @Test
    fun `x ordering is detected`() {
        assertTrue(isXOrdered(listOf(point(0f, 0f), point(1f, 0f), point(2f, 0f))))
        assertTrue(!isXOrdered(listOf(point(0f, 0f), point(2f, 0f), point(1f, 0f))))
        assertTrue(isXOrdered(emptyList()))
    }
}
