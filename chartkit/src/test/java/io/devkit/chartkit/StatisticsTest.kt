package io.devkit.chartkit

import io.devkit.chartkit.stats.BoxStatistics
import io.devkit.chartkit.stats.ChartStatistics
import io.devkit.chartkit.stats.OutlierPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The descriptive statistics behind the box plot and the violin.
 *
 * Verified against the R type-7 definition ChartKit documents, and against the
 * edge cases that are the whole reason the guards exist: an empty sample, one
 * observation, a constant run, negatives, and non-finite entries.
 */
class StatisticsTest {

    @Test
    fun `quartiles follow the documented linear interpolation`() {
        // R: quantile(1:9) -> 1, 3, 5, 7, 9
        val sorted = ChartStatistics.finiteSorted((1..9).map { it.toDouble() })
        val quartiles = ChartStatistics.quartiles(sorted)
        assertEquals(3.0, quartiles.q1, 1e-9)
        assertEquals(5.0, quartiles.median, 1e-9)
        assertEquals(7.0, quartiles.q3, 1e-9)
    }

    @Test
    fun `quartiles interpolate between order statistics`() {
        // R: quantile(c(1,2,3,4)) -> 1, 1.75, 2.5, 3.25, 4
        val sorted = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(1.75, ChartStatistics.quantile(sorted, 0.25), 1e-9)
        assertEquals(2.5, ChartStatistics.quantile(sorted, 0.5), 1e-9)
        assertEquals(3.25, ChartStatistics.quantile(sorted, 0.75), 1e-9)
    }

    @Test
    fun `median of an even sample is the mean of the middle pair`() {
        assertEquals(2.5, ChartStatistics.median(listOf(1.0, 2.0, 3.0, 4.0)), 1e-9)
    }

    @Test
    fun `median of an odd sample is the middle value`() {
        assertEquals(3.0, ChartStatistics.median(listOf(5.0, 1.0, 3.0)), 1e-9)
    }

    @Test
    fun `an empty sample has no quantile`() {
        assertTrue(ChartStatistics.quantile(DoubleArray(0), 0.5).isNaN())
    }

    @Test
    fun `a one-element sample is its own every quantile`() {
        val sorted = doubleArrayOf(7.0)
        assertEquals(7.0, ChartStatistics.quantile(sorted, 0.0), 1e-9)
        assertEquals(7.0, ChartStatistics.quantile(sorted, 0.5), 1e-9)
        assertEquals(7.0, ChartStatistics.quantile(sorted, 1.0), 1e-9)
    }

    @Test
    fun `non-finite values are filtered out before anything is computed`() {
        val sorted = ChartStatistics.finiteSorted(
            listOf(1.0, Double.NaN, 3.0, Double.POSITIVE_INFINITY, 5.0),
        )
        assertEquals(3, sorted.size)
        assertEquals(3.0, ChartStatistics.median(sorted), 1e-9)
    }

    @Test
    fun `sorting does not disturb the caller's list`() {
        val original = listOf(3.0, 1.0, 2.0)
        ChartStatistics.finiteSorted(original)
        assertEquals(listOf(3.0, 1.0, 2.0), original)
    }

    @Test
    fun `a constant sample has no spread and therefore no outliers`() {
        val sorted = ChartStatistics.finiteSorted(List(20) { 5.0 })
        assertEquals(0.0, ChartStatistics.interquartileRange(sorted), 1e-9)
        assertEquals(0, ChartStatistics.outliers(sorted).size)
        assertEquals(0.0, ChartStatistics.standardDeviation(sorted), 1e-9)
    }

    @Test
    fun `negative values are ordinary observations`() {
        val sorted = ChartStatistics.finiteSorted(listOf(-10.0, -5.0, 0.0, 5.0, 10.0))
        assertEquals(0.0, ChartStatistics.median(sorted), 1e-9)
        assertEquals(-5.0, ChartStatistics.quartiles(sorted).q1, 1e-9)
    }

    @Test
    fun `outliers are the values beyond the Tukey fences`() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 100.0)
        val sorted = ChartStatistics.finiteSorted(values)
        val outliers = ChartStatistics.outliers(sorted).toList()
        assertEquals(listOf(100.0), outliers)
    }

    @Test
    fun `the multiplier widens the fences`() {
        val sorted = ChartStatistics.finiteSorted(
            listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 100.0),
        )
        // IQR is 4.5 here, so the upper fence at 1.5x is 14.5 and 100 is beyond
        // it; at 25x it is 120.25 and 100 is not.
        assertEquals(1, ChartStatistics.outliers(sorted, 1.5).size)
        assertEquals(0, ChartStatistics.outliers(sorted, 25.0).size)
    }

    @Test
    fun `whiskers stop at real observations, never at the fence`() {
        val sorted = ChartStatistics.finiteSorted(
            listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 100.0),
        )
        val whiskers = ChartStatistics.whiskers(sorted)
        assertEquals(1.0, whiskers.lower, 1e-9)
        assertEquals(9.0, whiskers.upper, 1e-9)
    }

    @Test
    fun `a box summary is valid and ordered`() {
        val summary = BoxStatistics.from((1..100).map { it.toDouble() })!!
        assertTrue(summary.isValid)
        assertEquals(100, summary.sampleCount)
        assertTrue(summary.minimum <= summary.q1)
        assertTrue(summary.q1 <= summary.median)
        assertTrue(summary.median <= summary.q3)
        assertTrue(summary.q3 <= summary.maximum)
    }

    @Test
    fun `a sample with nothing usable has no summary`() {
        assertNull(BoxStatistics.from(listOf(Double.NaN, Double.NEGATIVE_INFINITY)))
        assertNull(BoxStatistics.from(emptyList()))
    }

    @Test
    fun `the no-outlier policy runs the whiskers to the extremes`() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 100.0)
        val summary = BoxStatistics.from(values, OutlierPolicy.None)!!
        assertEquals(100.0, summary.maximum, 1e-9)
        assertTrue(summary.outliers.isEmpty())
    }

    @Test
    fun `the display range covers the outliers as well as the whiskers`() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 100.0)
        val summary = BoxStatistics.from(values)!!
        assertEquals(100.0, summary.displayRange.endInclusive, 1e-9)
    }

    @Test
    fun `a precomputed summary is used exactly as supplied`() {
        val supplied = BoxStatistics(
            minimum = 100.0, q1 = 160.0, median = 210.0, q3 = 280.0, maximum = 430.0,
        )
        assertTrue(supplied.isValid)
        assertEquals(120.0, supplied.iqr, 1e-9)
        assertEquals(0, supplied.sampleCount)
    }

    @Test
    fun `an inconsistent supplied summary reports itself invalid rather than throwing`() {
        // Constructing it is the caller's business; drawing it is not, and the
        // layer skips what `isValid` rejects.
        val backwards = BoxStatistics(
            minimum = 400.0, q1 = 160.0, median = 210.0, q3 = 280.0, maximum = 100.0,
        )
        assertTrue(!backwards.isValid)
    }

    @Test
    fun `the standard deviation is the sample form`() {
        // sd(c(2,4,4,4,5,5,7,9)) = 2.13809 with n-1; 2.0 with n.
        val sorted = ChartStatistics.finiteSorted(
            listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0),
        )
        assertEquals(2.13809, ChartStatistics.standardDeviation(sorted), 1e-5)
    }

    @Test
    fun `one observation has no spread`() {
        assertEquals(0.0, ChartStatistics.standardDeviation(doubleArrayOf(4.0)), 1e-9)
        assertEquals(0.0, ChartStatistics.silvermanBandwidth(doubleArrayOf(4.0)), 1e-9)
    }

    @Test
    fun `the mean ignores non-finite entries and reports NaN when there are none left`() {
        assertEquals(2.0, ChartStatistics.mean(listOf(1.0, Double.NaN, 3.0)), 1e-9)
        assertTrue(ChartStatistics.mean(listOf(Double.NaN)).isNaN())
    }
}
