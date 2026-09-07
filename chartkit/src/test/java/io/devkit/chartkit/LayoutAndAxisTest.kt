package io.devkit.chartkit

import io.devkit.chartkit.axis.AxisPosition
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.axis.selectLabelIndices
import io.devkit.chartkit.geometry.ChartInsets
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.layout.AxisMetrics
import io.devkit.chartkit.layout.computeChartLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartLayoutTest {

    private fun axis(
        position: AxisPosition,
        labelExtent: Float = 20f,
        visible: Boolean = true,
        title: Float = 0f,
    ) = AxisMetrics(
        position = position,
        visible = visible,
        labelExtent = labelExtent,
        tickLength = 4f,
        labelPadding = 4f,
        titleExtent = title,
    )

    @Test
    fun `axis gutters are carved out of the plot, not out of the labels`() {
        val layout = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            axes = listOf(axis(AxisPosition.Bottom), axis(AxisPosition.Start, labelExtent = 40f)),
        )
        // start gutter = 40 + 4 + 4 = 48; bottom gutter = 20 + 4 + 4 = 28
        assertEquals(48f, layout.plotArea.left, 0.01f)
        assertEquals(300f - 28f, layout.plotArea.bottom, 0.01f)
    }

    @Test
    fun `a hidden axis takes no space`() {
        val withAxis = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            axes = listOf(axis(AxisPosition.Start)),
        )
        val without = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            axes = listOf(axis(AxisPosition.Start, visible = false)),
        )
        assertTrue(without.plotArea.width > withAxis.plotArea.width)
        assertEquals(0f, without.plotArea.left, 0.01f)
    }

    @Test
    fun `an axis title widens the gutter`() {
        val plain = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            axes = listOf(axis(AxisPosition.Start)),
        )
        val titled = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            axes = listOf(axis(AxisPosition.Start, title = 16f)),
        )
        assertEquals(16f, titled.plotArea.left - plain.plotArea.left, 0.01f)
    }

    @Test
    fun `label overhang keeps the first horizontal label on screen`() {
        val layout = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            axes = listOf(axis(AxisPosition.Bottom)),
            labelOverhang = 30f,
        )
        assertEquals(30f, layout.plotArea.left, 0.01f)
        assertEquals(400f - 30f, layout.plotArea.right, 0.01f)
    }

    @Test
    fun `overhang does not double-count a vertical axis gutter`() {
        val layout = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            axes = listOf(axis(AxisPosition.Start, labelExtent = 100f)),
            labelOverhang = 30f,
        )
        // The start gutter is already 108, comfortably more than the overhang.
        assertEquals(108f, layout.plotArea.left, 0.01f)
    }

    @Test
    fun `content padding is applied before the axes`() {
        val layout = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            contentPadding = ChartInsets(8f, 8f, 8f, 8f),
            axes = listOf(axis(AxisPosition.Start)),
        )
        assertEquals(8f + 28f, layout.plotArea.left, 0.01f)
        assertEquals(8f, layout.plotArea.top, 0.01f)
    }

    @Test
    fun `a chart smaller than its axes reports an empty plot rather than a negative one`() {
        val layout = computeChartLayout(
            bounds = ChartRect(0f, 0f, 20f, 20f),
            axes = listOf(
                axis(AxisPosition.Start, labelExtent = 100f),
                axis(AxisPosition.Bottom, labelExtent = 100f),
            ),
        )
        assertTrue(layout.plotArea.isEmpty)
        assertTrue(!layout.isDrawable)
    }

    @Test
    fun `a zero-sized chart produces a zero plot`() {
        val layout = computeChartLayout(bounds = ChartRect.Zero, axes = emptyList())
        assertTrue(layout.plotArea.isEmpty)
    }
}

class AxisLabelThinningTest {

    @Test
    fun `every label is kept when they all fit`() {
        assertEquals(listOf(0, 1, 2, 3), selectLabelIndices(4, available = 400f, labelExtent = 50f))
    }

    @Test
    fun `labels are dropped at a uniform stride when they do not fit`() {
        val kept = selectLabelIndices(12, available = 300f, labelExtent = 100f)
        assertTrue("expected fewer than 12, got $kept", kept.size < 12)
        val strides = kept.zipWithNext { a, b -> b - a }.distinct()
        assertTrue("stride should be uniform, was $strides", strides.size <= 2)
    }

    @Test
    fun `the first and last labels survive, because they orient the reader`() {
        val kept = selectLabelIndices(20, available = 200f, labelExtent = 60f)
        assertEquals(0, kept.first())
        assertEquals(19, kept.last())
    }

    @Test
    fun `maxLabels is a hard cap`() {
        val kept = selectLabelIndices(50, available = 5000f, labelExtent = 10f, maxLabels = 4)
        assertTrue("got ${kept.size}", kept.size <= 4)
    }

    @Test
    fun `no room at all keeps a single label rather than none`() {
        assertEquals(listOf(0), selectLabelIndices(10, available = 10f, labelExtent = 500f))
    }

    @Test
    fun `degenerate inputs do not crash`() {
        assertTrue(selectLabelIndices(0, 100f, 10f).isEmpty())
        assertEquals(listOf(0), selectLabelIndices(1, 100f, 10f))
        // A non-finite width means the chart has not been measured yet.
        // Keeping one label is the safe answer; keeping all of them would draw
        // a pile of overlapping text on the first frame.
        assertEquals(listOf(0), selectLabelIndices(2, Float.NaN, 10f))
        assertEquals(listOf(0), selectLabelIndices(2, 100f, Float.NaN).take(1))
    }
}

class AxisConfigTest {

    @Test(expected = IllegalArgumentException::class)
    fun `fewer than two ticks is rejected`() {
        ChartAxis(tickCount = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero label cap is rejected`() {
        ChartAxis(maxLabels = 0)
    }

    @Test
    fun `positionOr falls back only when unset`() {
        assertEquals(AxisPosition.Bottom, ChartAxis().positionOr(AxisPosition.Bottom))
        assertEquals(
            AxisPosition.Top,
            ChartAxis(position = AxisPosition.Top).positionOr(AxisPosition.Bottom),
        )
    }

    @Test
    fun `grid directions decompose correctly`() {
        assertTrue(ChartGrid.Both.hasHorizontal && ChartGrid.Both.hasVertical)
        assertTrue(ChartGrid.Horizontal.hasHorizontal && !ChartGrid.Horizontal.hasVertical)
        assertTrue(!ChartGrid.None.hasHorizontal && !ChartGrid.None.hasVertical)
    }
}
