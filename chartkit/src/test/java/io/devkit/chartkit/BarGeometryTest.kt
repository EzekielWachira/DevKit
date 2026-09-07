package io.devkit.chartkit

import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.geometry.BarStacking
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.geometry.computeBarSlices
import io.devkit.chartkit.scale.CategoryScale
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The value-space half of bar geometry: stacking, signs and normalisation. */
class BarStackingTest {

    @Test
    fun `an ungrouped bar runs from zero to its value`() {
        val bounds = BarStacking.bounds(listOf(listOf(10.0, -5.0)), BarGrouping.Grouped)
        assertEquals(0.0..10.0, bounds[0][0])
        assertEquals(-5.0..0.0, bounds[0][1])
    }

    @Test
    fun `stacking is additive`() {
        val bounds = BarStacking.bounds(
            listOf(listOf(10.0, 20.0), listOf(5.0, 5.0)),
            BarGrouping.Stacked,
        )
        assertEquals(0.0..10.0, bounds[0][0])
        assertEquals(10.0..15.0, bounds[1][0])
        assertEquals(0.0..20.0, bounds[0][1])
        assertEquals(20.0..25.0, bounds[1][1])
    }

    @Test
    fun `stacking accumulates each sign separately`() {
        // A category holding +3 and -1 must draw a segment above the baseline
        // and one below it, not a single bar of net height 2.
        val bounds = BarStacking.bounds(
            listOf(listOf(3.0), listOf(-1.0), listOf(2.0)),
            BarGrouping.Stacked,
        )
        assertEquals(0.0..3.0, bounds[0][0])
        assertEquals(-1.0..0.0, bounds[1][0])
        assertEquals(3.0..5.0, bounds[2][0])
    }

    @Test
    fun `a missing value leaves a hole rather than a zero-height bar`() {
        val bounds = BarStacking.bounds(listOf(listOf(10.0, null)), BarGrouping.Grouped)
        assertNotNull(bounds[0][0])
        assertNull(bounds[0][1])
    }

    @Test
    fun `percent stacking normalises each category to its own total`() {
        val percent = BarStacking.toPercent(listOf(listOf(30.0), listOf(70.0)), categoryCount = 1)
        assertEquals(0.3, percent[0][0]!!, 1e-9)
        assertEquals(0.7, percent[1][0]!!, 1e-9)
    }

    @Test
    fun `percent stacking segments sum to one`() {
        val bounds = BarStacking.bounds(
            listOf(listOf(2.0, 1.0), listOf(3.0, 9.0)),
            BarGrouping.StackedPercent,
        )
        assertEquals(1.0, bounds[1][0]!!.endInclusive, 1e-9)
        assertEquals(1.0, bounds[1][1]!!.endInclusive, 1e-9)
    }

    @Test
    fun `a zero-total category normalises to zero rather than dividing by zero`() {
        val percent = BarStacking.toPercent(listOf(listOf(0.0), listOf(0.0)), categoryCount = 1)
        assertEquals(0.0, percent[0][0]!!, 1e-9)
        assertTrue(percent.flatten().filterNotNull().all { it.isFinite() })
    }

    @Test
    fun `an entirely empty category normalises without producing NaN`() {
        val percent = BarStacking.toPercent(listOf(listOf<Double?>(null)), categoryCount = 1)
        assertNull(percent[0][0])
    }

    @Test
    fun `percent stacking over mixed signs uses absolute magnitudes`() {
        val percent = BarStacking.toPercent(listOf(listOf(3.0), listOf(-1.0)), categoryCount = 1)
        assertEquals(0.75, percent[0][0]!!, 1e-9)
        assertEquals(-0.25, percent[1][0]!!, 1e-9)
    }

    @Test
    fun `domainOf spans every extent`() {
        val bounds = BarStacking.bounds(
            listOf(listOf(10.0, -4.0), listOf(5.0, -2.0)),
            BarGrouping.Stacked,
        )
        val domain = BarStacking.domainOf(bounds)!!
        assertEquals(-6.0, domain.min, 1e-9)
        assertEquals(15.0, domain.max, 1e-9)
    }
}

/** The pixel half: rectangles, orientation, grouping and the reveal animation. */
class BarSliceTest {

    private val plot = ChartRect(0f, 0f, 300f, 200f)
    private val categories = CategoryScale(listOf("A", "B", "C"), 0f, 300f, categoryPadding = 0.0)

    /** Inverted, like a real vertical value axis: bigger values, smaller y. */
    private val verticalValues = LinearScale(NumericDomain(0.0, 100.0), 200f, 0f)

    @Test
    fun `a vertical bar rises from the baseline`() {
        val slices = slices(listOf(listOf(50.0, null, null)), BarGrouping.Grouped)
        val bar = slices.single()
        assertEquals(200f, bar.rect.bottom, 0.01f)
        assertEquals(100f, bar.rect.top, 0.01f)
    }

    @Test
    fun `a negative vertical bar hangs below the baseline`() {
        val scale = LinearScale(NumericDomain(-100.0, 100.0), 200f, 0f)
        val bounds = BarStacking.bounds(listOf(listOf(-50.0)), BarGrouping.Grouped)
        val slices = computeBarSlices(
            values = listOf(listOf(-50.0)),
            pointIndices = listOf(listOf(0)),
            paletteIndices = listOf(0),
            bounds = bounds,
            categoryScale = CategoryScale(listOf("A"), 0f, 100f, 0.0),
            valueScale = scale,
            orientation = ChartOrientation.Vertical,
            grouping = BarGrouping.Grouped,
            plotArea = plot,
        )
        val bar = slices.single()
        assertEquals(100f, bar.rect.top, 0.01f)
        assertEquals(150f, bar.rect.bottom, 0.01f)
    }

    @Test
    fun `a horizontal bar extends from the baseline across`() {
        val valueScale = LinearScale(NumericDomain(0.0, 100.0), 0f, 300f)
        val bounds = BarStacking.bounds(listOf(listOf(50.0)), BarGrouping.Grouped)
        val slices = computeBarSlices(
            values = listOf(listOf(50.0)),
            pointIndices = listOf(listOf(0)),
            paletteIndices = listOf(0),
            bounds = bounds,
            categoryScale = CategoryScale(listOf("A"), 0f, 200f, 0.0),
            valueScale = valueScale,
            orientation = ChartOrientation.Horizontal,
            grouping = BarGrouping.Grouped,
            plotArea = plot,
        )
        val bar = slices.single()
        assertEquals(0f, bar.rect.left, 0.01f)
        assertEquals(150f, bar.rect.right, 0.01f)
    }

    @Test
    fun `grouped series sit side by side inside one band`() {
        val slices = slices(
            listOf(listOf(50.0, 50.0, 50.0), listOf(50.0, 50.0, 50.0)),
            BarGrouping.Grouped,
        )
        val first = slices.first { it.seriesIndex == 0 && it.categoryIndex == 0 }
        val second = slices.first { it.seriesIndex == 1 && it.categoryIndex == 0 }
        assertTrue("grouped bars must not overlap", first.rect.right <= second.rect.left + 0.01f)
        // Both remain inside their band.
        assertTrue(first.rect.left >= 0f && second.rect.right <= 100f)
    }

    @Test
    fun `stacked series share the full band width`() {
        val slices = slices(
            listOf(listOf(20.0, null, null), listOf(30.0, null, null)),
            BarGrouping.Stacked,
        )
        val lower = slices.first { it.seriesIndex == 0 }
        val upper = slices.first { it.seriesIndex == 1 }
        assertEquals(lower.rect.left, upper.rect.left, 0.01f)
        assertEquals(lower.rect.right, upper.rect.right, 0.01f)
        // The upper segment starts where the lower one ends.
        assertEquals(lower.rect.top, upper.rect.bottom, 0.01f)
    }

    @Test
    fun `bars are empty at the start of the reveal and full at the end`() {
        val bounds = BarStacking.bounds(listOf(listOf(50.0)), BarGrouping.Grouped)
        fun at(fraction: Float) = computeBarSlices(
            values = listOf(listOf(50.0)),
            pointIndices = listOf(listOf(0)),
            paletteIndices = listOf(0),
            bounds = bounds,
            categoryScale = CategoryScale(listOf("A"), 0f, 100f, 0.0),
            valueScale = verticalValues,
            orientation = ChartOrientation.Vertical,
            grouping = BarGrouping.Grouped,
            plotArea = plot,
            animationFraction = fraction,
        ).single()

        assertEquals(0f, at(0f).rect.height, 0.01f)
        assertEquals(50f, at(0.5f).rect.height, 0.01f)
        assertEquals(100f, at(1f).rect.height, 0.01f)
    }

    @Test
    fun `an empty plot area produces no geometry rather than infinities`() {
        val slices = computeBarSlices(
            values = listOf(listOf(50.0)),
            pointIndices = listOf(listOf(0)),
            paletteIndices = listOf(0),
            bounds = BarStacking.bounds(listOf(listOf(50.0)), BarGrouping.Grouped),
            categoryScale = categories,
            valueScale = verticalValues,
            orientation = ChartOrientation.Vertical,
            grouping = BarGrouping.Grouped,
            plotArea = ChartRect.Zero,
        )
        assertTrue(slices.isEmpty())
    }

    @Test
    fun `a tiny but real value is still drawn`() {
        val bounds = BarStacking.bounds(listOf(listOf(0.001)), BarGrouping.Grouped)
        val bar = computeBarSlices(
            values = listOf(listOf(0.001)),
            pointIndices = listOf(listOf(0)),
            paletteIndices = listOf(0),
            bounds = bounds,
            categoryScale = CategoryScale(listOf("A"), 0f, 100f, 0.0),
            valueScale = verticalValues,
            orientation = ChartOrientation.Vertical,
            grouping = BarGrouping.Grouped,
            plotArea = plot,
        ).single()
        assertTrue("a real value must not vanish", bar.rect.height > 0f)
    }

    @Test
    fun `every rectangle is finite for every grouping`() {
        BarGrouping.entries.forEach { grouping ->
            slices(listOf(listOf(10.0, -5.0, 0.0), listOf(0.0, 7.0, null)), grouping).forEach {
                assertTrue("$grouping produced ${it.rect}", it.rect.isFinite)
            }
        }
    }

    private fun slices(values: List<List<Double?>>, grouping: BarGrouping) = computeBarSlices(
        values = values,
        pointIndices = values.map { series -> series.indices.toList() },
        paletteIndices = values.indices.toList(),
        bounds = BarStacking.bounds(values, grouping),
        categoryScale = categories,
        valueScale = verticalValues,
        orientation = ChartOrientation.Vertical,
        grouping = grouping,
        plotArea = plot,
        groupPadding = 0.0,
    )
}

/** Which segment of a stack gets its corners rounded. */
class BarCornerTest {

    private val plot = ChartRect(0f, 0f, 200f, 200f)
    private val categories = CategoryScale(listOf("A"), 0f, 200f, categoryPadding = 0.0)
    private val values = LinearScale(NumericDomain(-100.0, 100.0), 200f, 0f)

    private fun slices(data: List<List<Double?>>, grouping: BarGrouping) = computeBarSlices(
        values = data,
        pointIndices = data.map { series -> series.indices.toList() },
        paletteIndices = data.indices.toList(),
        bounds = BarStacking.bounds(data, grouping),
        categoryScale = categories,
        valueScale = values,
        orientation = ChartOrientation.Vertical,
        grouping = grouping,
        plotArea = plot,
    )

    @Test
    fun `an ungrouped bar is its own end`() {
        assertTrue(slices(listOf(listOf(50.0)), BarGrouping.Grouped).single().isBarEnd)
    }

    @Test
    fun `every grouped bar is its own end`() {
        val result = slices(listOf(listOf(50.0), listOf(30.0)), BarGrouping.Grouped)
        assertTrue(result.all { it.isBarEnd })
    }

    @Test
    fun `only the outermost stack segment is rounded`() {
        val result = slices(listOf(listOf(20.0), listOf(30.0), listOf(10.0)), BarGrouping.Stacked)
        assertEquals(1, result.count { it.isBarEnd })
        // The last-declared series sits on top, so it is the end.
        assertEquals(2, result.first { it.isBarEnd }.seriesIndex)
    }

    @Test
    fun `a stack with both signs has an end in each direction`() {
        val result = slices(
            listOf(listOf(20.0), listOf(-30.0), listOf(10.0)),
            BarGrouping.Stacked,
        )
        assertEquals(2, result.count { it.isBarEnd })
    }
}
