package io.devkit.chartkit

import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.interaction.ChartDragMode
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.model.ChartRangeSelection
import io.devkit.chartkit.model.ChartRangeSelectionPhase
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSelectionDetails
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection model is shared across coordinate systems, so these tests are
 * mostly about that sharing holding up: a polar selection has to be usable
 * everywhere a Cartesian one is.
 */
class ChartSelectionModelTest {

    private fun cartesian() = ChartSelection(
        seriesId = "revenue",
        seriesName = "Revenue",
        seriesIndex = 0,
        pointIndex = 2,
        x = ChartX.Category("Mar"),
        y = 28_200.0,
        item = "Mar row",
        position = ChartOffset(10f, 20f),
    )

    @Test
    fun `a Cartesian selection carries no polar detail`() {
        val selection = cartesian()
        assertEquals(ChartSelectionDetails.Cartesian, selection.details)
        assertNull(selection.polar)
    }

    @Test
    fun `a polar selection adds its share without losing the common shape`() {
        val selection = cartesian().copy(
            details = ChartSelectionDetails.Polar(
                fraction = 0.25,
                label = "Rent",
                startAngle = 0f,
                sweepAngle = 90f,
            ),
        )
        // Everything a tooltip, a callback or the accessibility layer reads is
        // still there — that is what makes one overlay engine serve both.
        assertEquals("revenue", selection.seriesId)
        assertEquals(28_200.0, selection.y, 1e-9)
        assertEquals("Mar row", selection.item)
        assertEquals(0.25, selection.polar!!.fraction, 1e-9)
        assertEquals("Rent", selection.polar!!.label)
    }

    @Test
    fun `the x label follows the domain type`() {
        assertEquals("Mar", cartesian().xLabel)
        assertEquals("42.0", cartesian().copy(x = ChartX.Numeric(42.0)).xLabel)
        assertEquals("1000", cartesian().copy(x = ChartX.Time(1000L)).xLabel)
    }
}

class ChartTooltipDataTest {

    private fun entry(id: String, value: Double, palette: Int) =
        ChartTooltipEntry(id, id.replaceFirstChar(Char::uppercase), value, "$id item", palette)

    private fun data(vararg entries: ChartTooltipEntry<String>) = ChartTooltipData(
        selection = ChartSelection(
            seriesId = entries.first().seriesId,
            seriesName = entries.first().seriesName,
            seriesIndex = 0,
            pointIndex = 0,
            x = ChartX.Category("Jan"),
            y = entries.first().value,
            item = entries.first().item,
            position = ChartOffset.Zero,
        ),
        entries = entries.toList(),
        anchor = ChartOffset(5f, 5f),
        xLabel = "Jan",
    )

    @Test
    fun `a single entry is not a multi-series tooltip`() {
        val tooltip = data(entry("revenue", 30_000.0, 0))
        assertFalse(tooltip.isMultiSeries)
        assertEquals("revenue item", tooltip.item)
    }

    @Test
    fun `several entries share one domain label`() {
        val tooltip = data(
            entry("revenue", 30_000.0, 0),
            entry("expenses", 21_000.0, 1),
            entry("profit", 9_000.0, 2),
        )
        assertTrue(tooltip.isMultiSeries)
        assertEquals("Jan", tooltip.xLabel)
        assertEquals(listOf(0, 1, 2), tooltip.entries.map { it.paletteIndex })
    }

    @Test
    fun `the nearest series is still identified among many`() {
        val tooltip = data(entry("revenue", 30_000.0, 0), entry("expenses", 21_000.0, 1))
        assertEquals("revenue", tooltip.selection.seriesId)
    }
}

class ChartRangeSelectionTest {

    private fun range(
        startFraction: Double,
        endFraction: Double,
        phase: ChartRangeSelectionPhase = ChartRangeSelectionPhase.Completed,
    ) = ChartRangeSelection(
        start = ChartX.Category("Mar"),
        end = ChartX.Category("Jul"),
        startFraction = startFraction,
        endFraction = endFraction,
        items = listOf("a", "b"),
        phase = phase,
    )

    @Test
    fun `a range reads as its two domain values`() {
        assertEquals("Mar to Jul", range(0.2, 0.6).label)
    }

    @Test
    fun `a collapsed range is empty`() {
        assertTrue(range(0.4, 0.4).isEmpty)
        assertFalse(range(0.4, 0.5).isEmpty)
    }

    @Test
    fun `phases are distinguishable, so a drag is not mistaken for a decision`() {
        assertEquals(ChartRangeSelectionPhase.InProgress, range(0.1, 0.2, ChartRangeSelectionPhase.InProgress).phase)
        assertEquals(ChartRangeSelectionPhase.Completed, range(0.1, 0.2).phase)
    }

    @Test
    fun `a range carries the items it covers`() {
        assertEquals(listOf("a", "b"), range(0.1, 0.9).items)
    }
}

class ChartInteractionConfigTest {

    @Test
    fun `the defaults tap and scrub, and do not zoom`() {
        val interaction = ChartInteraction.Default
        assertTrue(interaction.tapSelects)
        assertEquals(ChartDragMode.Scrub, interaction.dragMode)
        assertFalse(interaction.zoomEnabled)
    }

    @Test
    fun `an inert configuration consumes no pointer input`() {
        assertTrue(ChartInteraction.None.isInert)
        assertFalse(ChartInteraction.Default.isInert)
        assertFalse(ChartInteraction.TapOnly.isInert)
    }

    @Test
    fun `only configurations that can move the viewport say so`() {
        assertFalse(ChartInteraction.Default.movesViewport)
        assertTrue(ChartInteraction.Explorable.movesViewport)
        assertTrue(ChartInteraction(dragMode = ChartDragMode.Pan).movesViewport)
        assertTrue(ChartInteraction(zoomEnabled = true).movesViewport)
    }

    @Test
    fun `a tap survives every drag mode, so selection is never lost to a gesture`() {
        ChartDragMode.entries.forEach { mode ->
            assertTrue(ChartInteraction(dragMode = mode).tapSelects)
        }
    }

    @Test
    fun `range selection still allows a pinch`() {
        assertEquals(ChartDragMode.Range, ChartInteraction.RangeSelect.dragMode)
        assertTrue(ChartInteraction.RangeSelect.zoomEnabled)
    }

    @Test
    fun `crosshair presets differ in their guides, not in whether they work`() {
        assertFalse(CrosshairConfig.None.enabled)
        assertTrue(CrosshairConfig.Vertical.enabled)
        assertTrue(CrosshairConfig.Vertical.vertical)
        assertFalse(CrosshairConfig.Vertical.horizontal)
        assertTrue(CrosshairConfig.Both.horizontal)
    }
}
