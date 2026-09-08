package io.devkit.chartkit

import io.devkit.chartkit.geometry.OhlcPolicy
import io.devkit.chartkit.geometry.PriceDirection
import io.devkit.chartkit.geometry.normalizeOhlc
import io.devkit.chartkit.geometry.periodWidth
import io.devkit.chartkit.stats.MovingAverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Bar(
    val time: Double,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val close: Double?,
    val volume: Double? = null,
)

/** OHLC normalisation, direction, period width and the two moving averages. */
class FinancialTest {

    private fun normalize(bars: List<Bar>, policy: OhlcPolicy = OhlcPolicy.Repair) = normalizeOhlc(
        count = bars.size,
        domainValue = { bars[it].time },
        open = { bars[it].open },
        high = { bars[it].high },
        low = { bars[it].low },
        close = { bars[it].close },
        volume = { bars[it].volume },
        policy = policy,
    )

    @Test
    fun `a rising period is an increase and a falling one a decrease`() {
        val points = normalize(
            listOf(
                Bar(1.0, 10.0, 12.0, 9.0, 11.0),
                Bar(2.0, 11.0, 11.5, 8.0, 9.0),
                Bar(3.0, 9.0, 10.0, 8.5, 9.0),
            ),
        )
        assertEquals(PriceDirection.Increase, points[0].direction)
        assertEquals(PriceDirection.Decrease, points[1].direction)
        assertEquals(PriceDirection.Neutral, points[2].direction)
    }

    @Test
    fun `change is close minus open`() {
        val point = normalize(listOf(Bar(1.0, 10.0, 12.0, 9.0, 11.5))).single()
        assertEquals(1.5, point.change, 1e-9)
        assertEquals(0.15, point.changeFraction!!, 1e-9)
    }

    @Test
    fun `a zero open has no change fraction rather than an infinite one`() {
        val point = normalize(listOf(Bar(1.0, 0.0, 2.0, 0.0, 1.0))).single()
        assertNull(point.changeFraction)
    }

    @Test
    fun `repair widens the extremes to contain the open and close`() {
        // high below the close, low above the open: impossible as supplied.
        val point = normalize(listOf(Bar(1.0, 10.0, 10.5, 9.8, 12.0))).single()
        assertEquals(12.0, point.high, 1e-9)
        assertEquals(9.8, point.low, 1e-9)
        // The traded prices themselves are never moved.
        assertEquals(10.0, point.open, 1e-9)
        assertEquals(12.0, point.close, 1e-9)
    }

    @Test
    fun `repair leaves a consistent period untouched`() {
        val point = normalize(listOf(Bar(1.0, 10.0, 13.0, 9.0, 12.0))).single()
        assertEquals(13.0, point.high, 1e-9)
        assertEquals(9.0, point.low, 1e-9)
    }

    @Test
    fun `high below low is repaired rather than drawn`() {
        val point = normalize(listOf(Bar(1.0, 10.0, 8.0, 12.0, 11.0))).single()
        assertTrue(point.high >= point.low)
        assertTrue(point.high >= point.open && point.high >= point.close)
        assertTrue(point.low <= point.open && point.low <= point.close)
    }

    @Test
    fun `skip drops an inconsistent period and leaves a gap`() {
        val points = normalize(
            listOf(
                Bar(1.0, 10.0, 12.0, 9.0, 11.0),
                Bar(2.0, 10.0, 8.0, 12.0, 11.0),
                Bar(3.0, 11.0, 13.0, 10.0, 12.0),
            ),
            OhlcPolicy.Skip,
        )
        assertEquals(2, points.size)
        assertEquals(listOf(0, 2), points.map { it.sourceIndex })
    }

    @Test
    fun `reject reports an inconsistent period at the call site`() {
        val failure = runCatching {
            normalize(listOf(Bar(1.0, 10.0, 8.0, 12.0, 11.0)), OhlcPolicy.Reject)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a period with a missing price is dropped, never fabricated`() {
        val points = normalize(
            listOf(
                Bar(1.0, 10.0, 12.0, 9.0, 11.0),
                Bar(2.0, null, 12.0, 9.0, 11.0),
                Bar(3.0, 10.0, 12.0, 9.0, Double.NaN),
            ),
        )
        assertEquals(1, points.size)
        assertEquals(0, points.single().sourceIndex)
    }

    @Test
    fun `source indices survive dropped periods`() {
        val points = normalize(
            listOf(
                Bar(1.0, null, null, null, null),
                Bar(2.0, 10.0, 12.0, 9.0, 11.0),
            ),
        )
        assertEquals(1, points.single().sourceIndex)
    }

    @Test
    fun `volume travels on the candle it belongs to`() {
        val points = normalize(listOf(Bar(1.0, 10.0, 12.0, 9.0, 11.0, volume = 4200.0)))
        assertEquals(4200.0, points.single().volume!!, 1e-9)
    }

    @Test
    fun `a non-finite volume is dropped rather than plotted`() {
        val points = normalize(listOf(Bar(1.0, 10.0, 12.0, 9.0, 11.0, volume = Double.NaN)))
        assertNull(points.single().volume)
    }

    @Test
    fun `period width uses the median gap, not the mean`() {
        // Four periods a day apart, then a long weekend gap. The mean spacing
        // would be dragged wide and the candles would overlap.
        val positions = floatArrayOf(0f, 10f, 20f, 30f, 200f)
        val width = periodWidth(positions, fraction = 0.7f, minimum = 1f, fallback = 500f)
        assertEquals(7f, width, 1e-3f)
    }

    @Test
    fun `period width falls back for a single period and respects its floor`() {
        assertEquals(70f, periodWidth(floatArrayOf(5f), 0.7f, 1f, 100f), 1e-3f)
        assertEquals(2f, periodWidth(floatArrayOf(0f, 0.5f), 0.7f, 2f, 100f), 1e-3f)
    }
}

/** The two moving averages, which are lines rather than analysis. */
class MovingAverageTest {

    @Test
    fun `a simple average is parallel to its input with a null warm-up`() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val result = MovingAverage.simple(values, 3)
        assertEquals(5, result.size)
        assertNull(result[0])
        assertNull(result[1])
        assertEquals(2.0, result[2]!!, 1e-9)
        assertEquals(3.0, result[3]!!, 1e-9)
        assertEquals(4.0, result[4]!!, 1e-9)
    }

    @Test
    fun `a period of one is the series itself`() {
        val values = listOf(3.0, 1.0, 4.0)
        assertEquals(values, MovingAverage.simple(values, 1))
    }

    @Test
    fun `a missing sample nulls every window containing it`() {
        val values = listOf(1.0, 2.0, null, 4.0, 5.0, 6.0)
        val result = MovingAverage.simple(values, 3)
        assertNull(result[2])
        assertNull(result[3])
        assertNull(result[4])
        assertEquals(5.0, result[5]!!, 1e-9)
    }

    @Test
    fun `an exponential average warms up on the simple average of its period`() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val result = MovingAverage.exponential(values, 3)
        assertNull(result[1])
        assertEquals(2.0, result[2]!!, 1e-9)
        // alpha = 0.5: 4 * 0.5 + 2 * 0.5
        assertEquals(3.0, result[3]!!, 1e-9)
        assertEquals(4.0, result[4]!!, 1e-9)
    }

    @Test
    fun `an exponential average restarts after a gap rather than carrying a stale value`() {
        val values = listOf(1.0, 2.0, 3.0, null, 10.0, 11.0, 12.0)
        val result = MovingAverage.exponential(values, 3)
        assertEquals(2.0, result[2]!!, 1e-9)
        assertNull(result[3])
        assertNull(result[4])
        assertNull(result[5])
        assertEquals(11.0, result[6]!!, 1e-9)
    }

    @Test
    fun `an empty series averages to nothing`() {
        assertTrue(MovingAverage.simple(emptyList(), 5).isEmpty())
        assertTrue(MovingAverage.exponential(emptyList(), 5).isEmpty())
    }

    @Test
    fun `a period longer than the series produces no averages`() {
        assertTrue(MovingAverage.simple(listOf(1.0, 2.0), 5).all { it == null })
    }
}
