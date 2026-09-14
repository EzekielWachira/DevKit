package io.devkit.chartkit

import io.devkit.chartkit.geometry.PolarAreaGeometry
import io.devkit.chartkit.geometry.PolarAreaScaling
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/** Wedge placement, and the scaling decision that makes or breaks the chart. */
class PolarAreaGeometryTest {

    private val values = listOf(10.0, 20.0, 40.0, 5.0)

    private fun slices(
        values: List<Double?> = this.values,
        outerRadius: Float = 100f,
        startAngle: Float = 0f,
        sweepAngle: Float = PolarGeometry.FULL_CIRCLE,
        direction: PolarDirection = PolarDirection.Clockwise,
        scaling: PolarAreaScaling = PolarAreaScaling.Area,
        maxValue: Double? = null,
    ) = PolarAreaGeometry.slices(
        values, outerRadius, startAngle, sweepAngle, direction, scaling, maxValue,
    )

    @Test
    fun `every wedge takes the same angle`() {
        val placed = slices()

        placed.forEach { assertEquals(90f, abs(it.sweepAngle), 1e-3f) }
    }

    @Test
    fun `the wedges tile the sweep without gaps`() {
        val placed = slices()

        assertEquals(0f, placed.first().startAngle, 1e-3f)
        placed.zipWithNext { a, b -> assertEquals(a.startAngle + a.sweepAngle, b.startAngle, 1e-3f) }
        assertEquals(360f, placed.last().startAngle + placed.last().sweepAngle, 1e-3f)
    }

    @Test
    fun `the largest value reaches the full radius`() {
        val placed = slices()

        assertEquals(100f, placed[2].radius, 1e-3f)
    }

    @Test
    fun `under Area scaling a wedge's area is its value`() {
        // The chart's whole correctness claim. Wedge area is
        // (sweep / 360) * pi * r^2, and every sweep is equal here, so area per
        // unit of value must be constant.
        val placed = slices()
        val perUnit = placed.mapIndexedNotNull { index, slice ->
            val value = values[index]
            val area = abs(slice.sweepAngle) / 360.0 * PI * slice.radius * slice.radius
            area / value
        }

        perUnit.zipWithNext { a, b ->
            assertTrue("area per unit differs: $a vs $b", abs(a - b) < 1e-6)
        }
    }

    @Test
    fun `under Area scaling the radius is the square root of the share`() {
        val placed = slices()

        // 10 of 40 is a quarter, so half the radius.
        assertEquals(100f * sqrt(0.25).toFloat(), placed[0].radius, 1e-3f)
        assertEquals(50f, placed[0].radius, 1e-3f)
    }

    @Test
    fun `Radius scaling puts the value in the radius instead`() {
        val placed = slices(scaling = PolarAreaScaling.Radius)

        assertEquals(25f, placed[0].radius, 1e-3f)
        assertEquals(100f, placed[2].radius, 1e-3f)
    }

    @Test
    fun `Radius scaling exaggerates, which is why it is not the default`() {
        // A value four times another covers sixteen times the ink.
        val placed = slices(scaling = PolarAreaScaling.Radius)
        val small = placed[0].radius * placed[0].radius
        val large = placed[2].radius * placed[2].radius

        assertEquals(16.0, (large / small).toDouble(), 1e-3)
    }

    @Test
    fun `a fixed maximum makes two roses comparable`() {
        // Without it each chart rescales to itself and two roses of very
        // different magnitudes look identical.
        val small = slices(listOf(1.0, 2.0), maxValue = 100.0)
        val large = slices(listOf(50.0, 100.0), maxValue = 100.0)

        assertTrue(small.maxOf { it.radius } < large.maxOf { it.radius })
        assertEquals(100f, large[1].radius, 1e-3f)
    }

    @Test
    fun `a missing value draws nothing`() {
        val placed = slices(listOf(10.0, null, 40.0))

        assertEquals(0f, placed[1].radius, 1e-6f)
        assertFalse(placed[1].isDrawable)
    }

    @Test
    fun `a zero and a missing value are indistinguishable in the geometry`() {
        // Documented rather than papered over: a radius encoding has nowhere to
        // put the difference, and only the announcement keeps them apart.
        val placed = slices(listOf(0.0, null))

        assertEquals(placed[0].radius, placed[1].radius, 1e-6f)
    }

    @Test
    fun `negative and non-finite values draw nothing`() {
        val placed = slices(listOf(-5.0, Double.NaN, Double.POSITIVE_INFINITY, 10.0))

        assertFalse(placed[0].isDrawable)
        assertFalse(placed[1].isDrawable)
        assertFalse(placed[2].isDrawable)
        assertTrue(placed[3].isDrawable)
    }

    @Test
    fun `data with nothing positive in it draws nothing rather than dividing by zero`() {
        val placed = slices(listOf(0.0, -1.0, null))

        assertTrue(placed.none { it.isDrawable })
        assertTrue(placed.all { it.radius.isFinite() })
    }

    @Test
    fun `a partial sweep divides only what it was given`() {
        val placed = slices(sweepAngle = 180f)

        placed.forEach { assertEquals(45f, abs(it.sweepAngle), 1e-3f) }
        assertEquals(180f, placed.last().startAngle + placed.last().sweepAngle, 1e-3f)
    }

    @Test
    fun `counter-clockwise mirrors the sweeps`() {
        val forward = slices()
        val backward = slices(direction = PolarDirection.CounterClockwise)

        forward.zip(backward).forEach { (a, b) ->
            assertEquals(a.sweepAngle, -b.sweepAngle, 1e-3f)
            assertEquals(a.radius, b.radius, 1e-3f)
        }
    }

    @Test
    fun `an empty dataset or a zero radius places nothing`() {
        assertTrue(slices(emptyList()).isEmpty())
        assertTrue(slices(outerRadius = 0f).isEmpty())
    }

    @Test
    fun `a tap inside a wedge selects it`() {
        val placed = slices()
        placed.filter { it.isDrawable }.forEach { slice ->
            val found = PolarAreaGeometry.sliceAt(
                placed, slice.midAngle, slice.radius * 0.5f, PolarDirection.Clockwise,
            )
            assertEquals(slice.index, found)
        }
    }

    @Test
    fun `a tap beyond a short wedge's arc selects nothing`() {
        // The empty space out there is not part of the mark; letting it select
        // would tell a reader they had hit a small value from well outside it.
        val placed = slices()
        val short = placed[3]
        val found = PolarAreaGeometry.sliceAt(
            placed, short.midAngle, short.radius + 10f, PolarDirection.Clockwise,
        )

        assertEquals(-1, found)
    }

    @Test
    fun `the layout is deterministic`() {
        val first = slices()
        val second = slices()

        first.zip(second).forEach { (a, b) ->
            assertEquals(a.radius, b.radius, 1e-9f)
            assertEquals(a.startAngle, b.startAngle, 1e-9f)
        }
    }
}
