package io.devkit.chartkit

import io.devkit.chartkit.components.overlay.ChartOverlayPlacement
import io.devkit.chartkit.components.overlay.resolveOverlayOffset
import io.devkit.chartkit.coordinate.CartesianCoordinates
import io.devkit.chartkit.coordinate.DomainAxis
import io.devkit.chartkit.geometry.ChartInsets
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.scale.CategoryScale
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartRectTest {

    @Test
    fun `a rectangle knows its own size and centre`() {
        val rect = ChartRect(10f, 20f, 110f, 70f)
        assertEquals(100f, rect.width, 0.001f)
        assertEquals(50f, rect.height, 0.001f)
        assertEquals(60f, rect.centerX, 0.001f)
        assertEquals(45f, rect.centerY, 0.001f)
    }

    @Test
    fun `an inverted rectangle normalises, which bar geometry relies on`() {
        val inverted = ChartRect(0f, 100f, 50f, 20f).normalized
        assertEquals(20f, inverted.top, 0.001f)
        assertEquals(100f, inverted.bottom, 0.001f)
    }

    @Test
    fun `containment uses the normalised rectangle`() {
        val rect = ChartRect(0f, 100f, 50f, 20f)
        assertTrue(rect.contains(ChartOffset(25f, 50f)))
        assertTrue(!rect.contains(ChartOffset(25f, 150f)))
    }

    @Test
    fun `a non-finite rectangle is empty rather than drawable`() {
        assertTrue(ChartRect(0f, 0f, Float.NaN, 10f).isEmpty)
        assertTrue(ChartRect(0f, 0f, 0f, 0f).isEmpty)
    }

    @Test
    fun `insetting never produces a negative size`() {
        val rect = ChartRect(0f, 0f, 10f, 10f).inset(ChartInsets(20f, 20f, 20f, 20f))
        assertTrue(rect.width >= 0f && rect.height >= 0f)
    }
}

class CartesianCoordinatesTest {

    private val plot = ChartRect(0f, 0f, 200f, 100f)

    private fun vertical() = CartesianCoordinates(
        plotArea = plot,
        domainAxis = DomainAxis.Categories(CategoryScale(listOf("A", "B"), 0f, 200f, 0.0)),
        valueScale = LinearScale(NumericDomain(0.0, 100.0), 100f, 0f),
        orientation = ChartOrientation.Vertical,
    )

    private fun horizontal() = CartesianCoordinates(
        plotArea = plot,
        domainAxis = DomainAxis.Categories(CategoryScale(listOf("A", "B"), 0f, 100f, 0.0)),
        valueScale = LinearScale(NumericDomain(0.0, 100.0), 0f, 200f),
        orientation = ChartOrientation.Horizontal,
    )

    @Test
    fun `orientation decides which screen axis carries the domain`() {
        assertEquals(ChartOffset(30f, 70f), vertical().pointAt(domainPosition = 30f, valuePosition = 70f))
        assertEquals(ChartOffset(70f, 30f), horizontal().pointAt(domainPosition = 30f, valuePosition = 70f))
    }

    @Test
    fun `domainOf and valueOf invert pointAt`() {
        listOf(vertical(), horizontal()).forEach { coordinates ->
            val point = coordinates.pointAt(30f, 70f)
            assertEquals(30f, coordinates.domainOf(point), 0.001f)
            assertEquals(70f, coordinates.valueOf(point), 0.001f)
        }
    }

    @Test
    fun `the baseline is where zero sits on the value axis`() {
        assertEquals(100f, vertical().baseline, 0.001f)
        assertEquals(0f, horizontal().baseline, 0.001f)
    }

    @Test
    fun `a category axis exposes bands and not a continuous scale`() {
        val coordinates = vertical()
        assertNotNull(coordinates.categories)
        assertNull(coordinates.continuousDomain)
        assertNull(coordinates.positionOfDomain(1.0))
    }

    @Test
    fun `a continuous axis can be inverted, which is what scrubbing needs`() {
        val coordinates = CartesianCoordinates(
            plotArea = plot,
            domainAxis = DomainAxis.Continuous(LinearScale(NumericDomain(0.0, 10.0), 0f, 200f)),
            valueScale = LinearScale(NumericDomain(0.0, 100.0), 100f, 0f),
            orientation = ChartOrientation.Vertical,
        )
        assertNotNull(coordinates.continuousDomain)
        assertEquals(100f, coordinates.positionOfDomain(5.0)!!, 0.001f)
        assertEquals(5.0, coordinates.continuousDomain!!.invert(100f), 1e-6)
    }
}

class OverlayPlacementTest {

    private val plot = ChartRect(0f, 0f, 300f, 200f)

    @Test
    fun `the tooltip sits above the anchor when there is room`() {
        val offset = resolveOverlayOffset(150f, 100f, 80, 40, plot, gap = 8f)
        assertEquals(150 - 40, offset.x)
        assertEquals(100 - 40 - 8, offset.y)
    }

    @Test
    fun `it flips below the anchor when there is no room above`() {
        val offset = resolveOverlayOffset(150f, 10f, 80, 40, plot, gap = 8f)
        assertEquals(18, offset.y)
    }

    @Test
    fun `it never leaves the chart at the first point`() {
        val offset = resolveOverlayOffset(0f, 100f, 120, 40, plot, gap = 8f)
        assertTrue("tooltip started at ${offset.x}", offset.x >= 0)
    }

    @Test
    fun `it never leaves the chart at the last point`() {
        val offset = resolveOverlayOffset(300f, 100f, 120, 40, plot, gap = 8f)
        assertTrue("tooltip ended at ${offset.x + 120}", offset.x + 120 <= 300)
    }

    @Test
    fun `a tooltip taller than the plot is clamped rather than throwing`() {
        val offset = resolveOverlayOffset(150f, 100f, 80, 500, plot, gap = 8f)
        assertEquals(0, offset.y)
    }

    @Test
    fun `a tooltip wider than the plot is clamped rather than throwing`() {
        val offset = resolveOverlayOffset(150f, 100f, 500, 40, plot, gap = 8f)
        assertEquals(0, offset.x)
    }

    @Test
    fun `an explicit side placement is honoured when it fits`() {
        val offset = resolveOverlayOffset(
            anchorX = 150f, anchorY = 100f, width = 40, height = 20,
            bounds = plot, gap = 8f, placement = ChartOverlayPlacement.End,
        )
        assertEquals(158, offset.x)
    }

    @Test
    fun `a side placement with no room flips to the other side`() {
        val offset = resolveOverlayOffset(
            anchorX = 295f, anchorY = 100f, width = 60, height = 20,
            bounds = plot, gap = 8f, placement = ChartOverlayPlacement.End,
        )
        assertTrue("expected a flip to the leading side, got ${offset.x}", offset.x < 295)
    }

    @Test
    fun `a non-finite anchor produces the origin, not a crash`() {
        assertEquals(0, resolveOverlayOffset(Float.NaN, 10f, 80, 40, plot, gap = 8f).x)
    }
}
