package io.devkit.chartkit

import io.devkit.chartkit.scale.CategoryScale
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.TimeScale
import io.devkit.chartkit.scale.apply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scale layer is the part of ChartKit everything else divides by, so these
 * tests are mostly about the inputs that would otherwise produce a `NaN`.
 */
class LinearScaleTest {

    @Test
    fun `maps a simple domain across the range`() {
        val scale = LinearScale(NumericDomain(0.0, 100.0), 0f, 800f)
        assertEquals(0f, scale.scale(0.0), TOLERANCE)
        assertEquals(200f, scale.scale(25.0), TOLERANCE)
        assertEquals(800f, scale.scale(100.0), TOLERANCE)
    }

    @Test
    fun `maps an inverted range, which is how a vertical value axis is built`() {
        val scale = LinearScale(NumericDomain(0.0, 100.0), 400f, 0f)
        assertEquals(400f, scale.scale(0.0), TOLERANCE)
        assertEquals(0f, scale.scale(100.0), TOLERANCE)
        assertEquals(200f, scale.scale(50.0), TOLERANCE)
    }

    @Test
    fun `maps a wholly negative domain`() {
        val scale = LinearScale(NumericDomain(-10.0, -5.0), 0f, 100f)
        assertEquals(0f, scale.scale(-10.0), TOLERANCE)
        assertEquals(100f, scale.scale(-5.0), TOLERANCE)
        assertEquals(50f, scale.scale(-7.5), TOLERANCE)
    }

    @Test
    fun `maps a domain straddling zero`() {
        val scale = LinearScale(NumericDomain(-20.0, 20.0), 0f, 200f)
        assertEquals(100f, scale.scale(0.0), TOLERANCE)
        assertEquals(0f, scale.scale(-20.0), TOLERANCE)
    }

    @Test
    fun `a zero-width domain is widened rather than dividing by zero`() {
        val scale = LinearScale(NumericDomain(5.0, 5.0), 0f, 100f)
        assertTrue("domain should have been widened", scale.domain.span > 0.0)
        val position = scale.scale(5.0)
        assertTrue("position must be finite", position.isFinite())
        // The constant value lands in the middle of the plot.
        assertEquals(50f, position, TOLERANCE)
    }

    @Test
    fun `a zero-valued constant domain is widened around zero`() {
        val scale = LinearScale(NumericDomain(0.0, 0.0), 0f, 100f)
        assertEquals(50f, scale.scale(0.0), TOLERANCE)
        assertTrue(scale.scale(0.0).isFinite())
    }

    @Test
    fun `a non-finite value maps to the range start rather than to NaN`() {
        val scale = LinearScale(NumericDomain(0.0, 10.0), 0f, 100f)
        assertTrue(scale.scale(Double.NaN).isFinite())
        assertTrue(scale.scale(Double.POSITIVE_INFINITY).isFinite())
    }

    @Test
    fun `a zero-width pixel range still produces finite positions`() {
        val scale = LinearScale(NumericDomain(0.0, 10.0), 50f, 50f)
        assertEquals(50f, scale.scale(0.0), TOLERANCE)
        assertEquals(50f, scale.scale(10.0), TOLERANCE)
    }

    @Test
    fun `clamping keeps out-of-domain values inside the range`() {
        val clamped = LinearScale(NumericDomain(0.0, 10.0), 0f, 100f, clamp = true)
        assertEquals(100f, clamped.scale(50.0), TOLERANCE)
        assertEquals(0f, clamped.scale(-50.0), TOLERANCE)
    }

    @Test
    fun `without clamping, values outside the domain map outside the range`() {
        val open = LinearScale(NumericDomain(0.0, 10.0), 0f, 100f)
        assertEquals(500f, open.scale(50.0), TOLERANCE)
    }

    @Test
    fun `invert is the inverse of scale`() {
        val scale = LinearScale(NumericDomain(-40.0, 160.0), 0f, 500f)
        listOf(-40.0, 0.0, 37.5, 160.0).forEach { value ->
            assertEquals(value, scale.invert(scale.scale(value)), 1e-4)
        }
    }

    @Test
    fun `invert of a non-finite position returns the domain minimum`() {
        val scale = LinearScale(NumericDomain(2.0, 8.0), 0f, 100f)
        assertEquals(2.0, scale.invert(Float.NaN), TOLERANCE.toDouble())
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}

class DomainPolicyTest {

    @Test
    fun `Auto pads both ends of the data`() {
        val domain = DomainPolicy.Auto(padding = 0.1).apply(NumericDomain(0.0, 100.0))
        assertEquals(-10.0, domain.min, 1e-9)
        assertEquals(110.0, domain.max, 1e-9)
    }

    @Test
    fun `IncludeZero pulls a positive domain down to the baseline`() {
        val domain = DomainPolicy.IncludeZero().apply(NumericDomain(98.0, 100.0))
        assertEquals(0.0, domain.min, 1e-9)
        assertTrue(domain.max >= 100.0)
    }

    @Test
    fun `IncludeZero does not lift the baseline off the axis`() {
        // Padding is applied away from zero only: a bar chart whose zero line
        // floats above the axis is reporting a length from nowhere.
        val domain = DomainPolicy.IncludeZero(padding = 0.2).apply(NumericDomain(10.0, 50.0))
        assertEquals(0.0, domain.min, 1e-9)
    }

    @Test
    fun `IncludeZero on negative data pads downward only`() {
        val domain = DomainPolicy.IncludeZero(padding = 0.2).apply(NumericDomain(-50.0, -10.0))
        assertEquals(0.0, domain.max, 1e-9)
        assertTrue(domain.min < -50.0)
    }

    @Test
    fun `Fixed ignores the data entirely`() {
        val domain = DomainPolicy.Fixed(0.0, 10.0).apply(NumericDomain(-500.0, 500.0))
        assertEquals(0.0, domain.min, 1e-9)
        assertEquals(10.0, domain.max, 1e-9)
    }

    @Test
    fun `Bounded pins one end and fits the other`() {
        val domain = DomainPolicy.Bounded(min = 0.0).apply(NumericDomain(20.0, 80.0))
        assertEquals(0.0, domain.min, 1e-9)
        assertEquals(80.0, domain.max, 1e-9)
    }

    @Test
    fun `no data falls back to a usable default`() {
        val domain = DomainPolicy.Auto().apply(null)
        assertTrue(domain.span > 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a fixed domain with min above max is rejected`() {
        DomainPolicy.Fixed(10.0, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative padding is rejected`() {
        DomainPolicy.Auto(padding = -0.1)
    }

    @Test
    fun `NumericDomain of ignores non-finite values`() {
        val domain = NumericDomain.of(listOf(1.0, Double.NaN, 5.0, Double.POSITIVE_INFINITY))
        assertEquals(NumericDomain(1.0, 5.0), domain)
    }

    @Test
    fun `NumericDomain of returns null when nothing is usable`() {
        assertEquals(null, NumericDomain.of(listOf(Double.NaN)))
    }
}

class CategoryScaleTest {

    @Test
    fun `positions bands evenly and centres them`() {
        val scale = CategoryScale(listOf("A", "B", "C"), 0f, 300f, categoryPadding = 0.0)
        assertEquals(50f, scale.positionAt(0), 0.001f)
        assertEquals(150f, scale.positionAt(1), 0.001f)
        assertEquals(250f, scale.positionAt(2), 0.001f)
    }

    @Test
    fun `placement follows list order, not the label text`() {
        val forward = CategoryScale(listOf("Zebra", "Apple"), 0f, 200f)
        // Sorted alphabetically, "Apple" would come first. It must not.
        assertTrue(forward.scale("Zebra") < forward.scale("Apple"))
    }

    @Test
    fun `renaming a category does not move its neighbours`() {
        val before = CategoryScale(listOf("Q1", "Q2"), 0f, 200f)
        val after = CategoryScale(listOf("Quarter one", "Q2"), 0f, 200f)
        assertEquals(before.positionAt(1), after.positionAt(1), 0.001f)
    }

    @Test
    fun `padding narrows the content band but not the spacing`() {
        val padded = CategoryScale(listOf("A", "B"), 0f, 200f, categoryPadding = 0.5)
        assertEquals(100f, padded.bandWidth, 0.001f)
        assertEquals(50f, padded.innerBandWidth, 0.001f)
        assertEquals(50f, padded.positionAt(0), 0.001f)
    }

    @Test
    fun `indexAt resolves a position to its band`() {
        val scale = CategoryScale(listOf("A", "B", "C"), 0f, 300f)
        assertEquals(0, scale.indexAt(10f))
        assertEquals(1, scale.indexAt(150f))
        assertEquals(2, scale.indexAt(299f))
    }

    @Test
    fun `indexAt rejects a position outside the axis`() {
        val scale = CategoryScale(listOf("A", "B"), 0f, 200f)
        assertEquals(-1, scale.indexAt(-5f))
        assertEquals(-1, scale.indexAt(400f))
    }

    @Test
    fun `nearestIndex clamps rather than rejecting`() {
        val scale = CategoryScale(listOf("A", "B"), 0f, 200f)
        assertEquals(0, scale.nearestIndex(-500f))
        assertEquals(1, scale.nearestIndex(500f))
    }

    @Test
    fun `an empty category list produces no width and no crash`() {
        val scale = CategoryScale(emptyList(), 0f, 200f)
        assertEquals(0, scale.count)
        assertEquals(0f, scale.bandWidth, 0.001f)
        assertEquals(-1, scale.indexAt(50f))
        assertTrue(scale.positionAt(0).isFinite())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `padding of one or more is rejected`() {
        CategoryScale(listOf("A"), 0f, 100f, categoryPadding = 1.0)
    }
}

class TimeScaleTest {

    private val hour = 60 * 60 * 1000L
    private val start = 1_700_000_000_000L

    @Test
    fun `positions instants chronologically`() {
        val scale = TimeScale(NumericDomain(start.toDouble(), (start + 4 * hour).toDouble()), 0f, 400f)
        assertEquals(0f, scale.scale(start), 0.001f)
        assertEquals(400f, scale.scale(start + 4 * hour), 0.001f)
        assertTrue(scale.scale(start + hour) < scale.scale(start + 2 * hour))
    }

    @Test
    fun `spacing is proportional to elapsed time, not to sample count`() {
        val scale = TimeScale(NumericDomain(start.toDouble(), (start + 10 * hour).toDouble()), 0f, 1000f)
        val oneHourGap = scale.scale(start + hour) - scale.scale(start)
        val fiveHourGap = scale.scale(start + 10 * hour) - scale.scale(start + 5 * hour)
        assertEquals(oneHourGap * 5, fiveHourGap, 0.01f)
    }

    @Test
    fun `invert round-trips to the same instant`() {
        val scale = TimeScale(NumericDomain(start.toDouble(), (start + 6 * hour).toDouble()), 0f, 600f)
        val instant = start + 3 * hour
        assertEquals(instant, scale.invert(scale.scale(instant)))
    }

    @Test
    fun `ticks cover the domain and stay inside it`() {
        val scale = TimeScale(NumericDomain(start.toDouble(), (start + 12 * hour).toDouble()), 0f, 600f)
        val ticks = scale.ticks(4)
        assertTrue(ticks.isNotEmpty())
        assertTrue(ticks.all { it >= start && it <= start + 12 * hour })
        assertEquals(ticks.sorted(), ticks)
    }

    @Test
    fun `a single instant does not produce an empty or infinite tick list`() {
        val scale = TimeScale(NumericDomain(start.toDouble(), start.toDouble()), 0f, 100f)
        val ticks = scale.ticks(5)
        assertTrue(ticks.isNotEmpty())
        assertTrue(ticks.size < 50)
    }

    @Test
    fun `a multi-year span steps in years rather than in milliseconds`() {
        val fiveYears = 5L * 365 * 24 * hour
        val scale = TimeScale(NumericDomain(start.toDouble(), (start + fiveYears).toDouble()), 0f, 600f)
        val ticks = scale.ticks(5)
        assertTrue("expected a handful of ticks, got ${ticks.size}", ticks.size in 2..12)
        assertNotEquals(ticks.first(), ticks.last())
    }
}
