package io.devkit.chartkit

import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.three.Column3DArrangement
import io.devkit.chartkit.three.Column3DDepth
import io.devkit.chartkit.three.Column3DLayoutEngine
import io.devkit.chartkit.three.Column3DSeries
import io.devkit.chartkit.three.Chart3DException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge from the 2D stack layout to 3D boxes.
 *
 * These are the tests that would catch a second stack implementation creeping
 * in. Every expectation here is about *where the boxes are*, in world units,
 * with no camera in sight — because the arrangement is a property of the data
 * and the layout, and a camera would only make the assertions harder to read
 * without making them stronger.
 */
class Column3DLayoutTest {

    private val categories = listOf("Jan", "Feb", "Mar")
    private val tolerance = 1e-9

    /** The plot is 300 wide and 200 tall, with three bands of 100 each. */
    private val centres = listOf(50f, 150f, 250f)
    private val band = 80f
    private val plotWidth = 300f
    private val plotHeight = 200f

    /** A domain of 0..100 mapped straight onto `0..1`. */
    private val fraction: (Double) -> Double = { it / 100.0 }

    // ---- grouping ---------------------------------------------------------

    @Test
    fun `grouped series get separate footprints in the same band`() {
        val layout = layout(
            series = listOf(
                series("a", listOf(10.0, 20.0, 30.0)),
                series("b", listOf(40.0, 50.0, 60.0)),
            ),
            grouping = BarGrouping.Grouped,
        )
        val jan = layout.segments.filter { it.key.categoryIndex == 0 }
        assertEquals(2, jan.size)
        val a = jan.first { it.key.seriesId == "a" }.cuboid.bounds
        val b = jan.first { it.key.seriesId == "b" }.cuboid.bounds
        assertTrue("grouped columns must not overlap in x", a.maxX <= b.minX + tolerance)
        assertEquals("and must sit at the same depth", a.minZ, b.minZ, tolerance)
        // Both start at the floor: grouping does not stack.
        assertEquals(0.0, a.minY, tolerance)
        assertEquals(0.0, b.minY, tolerance)
    }

    @Test
    fun `every grouped column stands on the baseline`() {
        val layout = layout(
            series = listOf(series("a", listOf(10.0, 20.0, 30.0))),
            grouping = BarGrouping.Grouped,
        )
        layout.segments.forEach { assertEquals(0.0, it.cuboid.bounds.minY, tolerance) }
        assertEquals(20.0, layout.segments[0].cuboid.bounds.maxY, tolerance)
        assertEquals(40.0, layout.segments[1].cuboid.bounds.maxY, tolerance)
        assertEquals(60.0, layout.segments[2].cuboid.bounds.maxY, tolerance)
    }

    // ---- stacking ---------------------------------------------------------

    @Test
    fun `stacked series share a footprint and accumulate upward`() {
        val layout = layout(
            series = listOf(
                series("a", listOf(10.0, 0.0, 0.0), stack = "one"),
                series("b", listOf(30.0, 0.0, 0.0), stack = "one"),
            ),
            grouping = BarGrouping.Stacked,
        )
        val jan = layout.segments.filter { it.key.categoryIndex == 0 }
        val a = jan.first { it.key.seriesId == "a" }.cuboid.bounds
        val b = jan.first { it.key.seriesId == "b" }.cuboid.bounds
        assertEquals("the same footprint in x", a.minX, b.minX, tolerance)
        assertEquals(a.maxX, b.maxX, tolerance)
        assertEquals("and in z", a.minZ, b.minZ, tolerance)
        assertEquals(0.0, a.minY, tolerance)
        assertEquals(20.0, a.maxY, tolerance)
        assertEquals("no gap between the segments", a.maxY, b.minY, tolerance)
        assertEquals(80.0, b.maxY, tolerance)
    }

    @Test
    fun `two stacks are two piles, not one`() {
        val layout = layout(
            series = listOf(
                series("john", listOf(10.0, 0.0, 0.0), stack = "male"),
                series("jane", listOf(10.0, 0.0, 0.0), stack = "female"),
            ),
            grouping = BarGrouping.Stacked,
        )
        val jan = layout.segments.filter { it.key.categoryIndex == 0 }
        // Both start at the floor. One global stack would put the second at 20.
        jan.forEach { assertEquals(0.0, it.cuboid.bounds.minY, tolerance) }
        assertEquals(listOf("male", "female"), layout.stackIds)
    }

    @Test
    fun `grouped and stacked puts two piles side by side, each stacked inside`() {
        val layout = layout(
            series = listOf(
                series("john", listOf(5.0, 0.0, 0.0), stack = "male"),
                series("joe", listOf(15.0, 0.0, 0.0), stack = "male"),
                series("jane", listOf(10.0, 0.0, 0.0), stack = "female"),
                series("janet", listOf(20.0, 0.0, 0.0), stack = "female"),
            ),
            grouping = BarGrouping.Stacked,
        )
        val jan = layout.segments.filter { it.key.categoryIndex == 0 }.associateBy { it.key.seriesId }
        val john = jan.getValue("john").cuboid.bounds
        val joe = jan.getValue("joe").cuboid.bounds
        val jane = jan.getValue("jane").cuboid.bounds
        val janet = jan.getValue("janet").cuboid.bounds

        // Stacked within each pile.
        assertEquals(0.0, john.minY, tolerance)
        assertEquals(john.maxY, joe.minY, tolerance)
        assertEquals(0.0, jane.minY, tolerance)
        assertEquals(jane.maxY, janet.minY, tolerance)
        // Grouped between piles.
        assertEquals(john.minX, joe.minX, tolerance)
        assertEquals(jane.minX, janet.minX, tolerance)
        assertTrue("the two piles must not overlap", john.maxX <= jane.minX + tolerance)
    }

    @Test
    fun `the depth arrangement puts each stack in its own row`() {
        val layout = layout(
            series = listOf(
                series("john", listOf(10.0, 0.0, 0.0), stack = "male"),
                series("jane", listOf(10.0, 0.0, 0.0), stack = "female"),
            ),
            grouping = BarGrouping.Stacked,
            arrangement = Column3DArrangement.Depth,
        )
        val jan = layout.segments.filter { it.key.categoryIndex == 0 }.associateBy { it.key.seriesId }
        val male = jan.getValue("john").cuboid.bounds
        val female = jan.getValue("jane").cuboid.bounds
        assertEquals("depth rows share the whole band", male.minX, female.minX, tolerance)
        assertTrue("and are separated in z", male.maxZ <= female.minZ + tolerance)
    }

    @Test
    fun `stack order follows declaration and not the values`() {
        val ascending = layout(
            series = listOf(
                series("a", listOf(1.0, 0.0, 0.0), stack = "left"),
                series("b", listOf(99.0, 0.0, 0.0), stack = "right"),
            ),
            grouping = BarGrouping.Stacked,
        )
        val descending = layout(
            series = listOf(
                series("a", listOf(99.0, 0.0, 0.0), stack = "left"),
                series("b", listOf(1.0, 0.0, 0.0), stack = "right"),
            ),
            grouping = BarGrouping.Stacked,
        )
        assertEquals(ascending.stackIds, descending.stackIds)
        assertEquals(
            ascending.segments.first { it.key.seriesId == "a" }.cuboid.bounds.minX,
            descending.segments.first { it.key.seriesId == "a" }.cuboid.bounds.minX,
            tolerance,
        )
    }

    // ---- percent stacking -------------------------------------------------

    @Test
    fun `percent stacking normalises each stack to its own total`() {
        val layout = layout(
            series = listOf(
                series("a", listOf(25.0, 0.0, 0.0), stack = "one"),
                series("b", listOf(75.0, 0.0, 0.0), stack = "one"),
            ),
            grouping = BarGrouping.StackedPercent,
            // Percent stacking plots fractions, so the value axis runs 0..1.
            fraction = { it },
        )
        val jan = layout.segments.filter { it.key.categoryIndex == 0 }.associateBy { it.key.seriesId }
        val a = jan.getValue("a").cuboid.bounds
        val b = jan.getValue("b").cuboid.bounds
        assertEquals(0.0, a.minY, 1e-6)
        assertEquals(0.25 * plotHeight, a.maxY, 1e-6)
        assertEquals(plotHeight.toDouble(), b.maxY, 1e-6)
        // The raw values are still reported, so a tooltip says 25 and not 0.25.
        assertEquals(25.0, jan.getValue("a").value, tolerance)
    }

    // ---- signs ------------------------------------------------------------

    @Test
    fun `positive and negative stacks stay on their own side of the baseline`() {
        val layout = layout(
            series = listOf(
                series("up", listOf(30.0, 0.0, 0.0), stack = "one"),
                series("down", listOf(-20.0, 0.0, 0.0), stack = "one"),
            ),
            grouping = BarGrouping.Stacked,
            // A domain of -100..100, so zero sits halfway up the plot.
            fraction = { (it + 100.0) / 200.0 },
        )
        val jan = layout.segments.filter { it.key.categoryIndex == 0 }.associateBy { it.key.seriesId }
        val baseline = 0.5 * plotHeight
        val up = jan.getValue("up").cuboid.bounds
        val down = jan.getValue("down").cuboid.bounds
        assertEquals(baseline, up.minY, 1e-6)
        assertTrue("a positive column rises", up.maxY > baseline)
        assertEquals(baseline, down.maxY, 1e-6)
        assertTrue("a negative column falls", down.minY < baseline)
        assertTrue(jan.getValue("down").cuboid.isNegative)
    }

    // ---- missing and zero -------------------------------------------------

    @Test
    fun `a null value produces no box at all`() {
        val layout = layout(
            series = listOf(series("a", listOf(10.0, null, 30.0))),
            grouping = BarGrouping.Grouped,
        )
        assertEquals(2, layout.segments.size)
        assertNull(layout.segments.firstOrNull { it.key.categoryIndex == 1 })
    }

    @Test
    fun `a zero value keeps its place in the data`() {
        val layout = layout(
            series = listOf(series("a", listOf(10.0, 0.0, 30.0))),
            grouping = BarGrouping.Grouped,
        )
        assertEquals(3, layout.segments.size)
        val feb = layout.segments.first { it.key.categoryIndex == 1 }
        assertEquals("zero is a reading, not an absence", 0.0, feb.value, tolerance)
        assertEquals(0.0, feb.cuboid.bounds.height, tolerance)
        assertEquals("Feb", feb.key.category)
    }

    @Test
    fun `a non-finite value is filtered out like a missing one`() {
        val layout = layout(
            series = listOf(series("a", listOf(10.0, Double.NaN, Double.POSITIVE_INFINITY))),
            grouping = BarGrouping.Grouped,
        )
        assertEquals(1, layout.segments.size)
        assertEquals(0, layout.segments.single().key.categoryIndex)
    }

    // ---- animation --------------------------------------------------------

    @Test
    fun `a stack stays contiguous at every frame of the reveal`() {
        listOf(0f, 0.1f, 0.37f, 0.5f, 0.99f, 1f).forEach { reveal ->
            val layout = layout(
                series = listOf(
                    series("a", listOf(20.0, 0.0, 0.0), stack = "one"),
                    series("b", listOf(30.0, 0.0, 0.0), stack = "one"),
                ),
                grouping = BarGrouping.Stacked,
                reveal = reveal,
            )
            val jan = layout.segments.filter { it.key.categoryIndex == 0 }
                .associateBy { it.key.seriesId }
            val a = jan.getValue("a").cuboid.bounds
            val b = jan.getValue("b").cuboid.bounds
            assertEquals("gap at reveal $reveal", a.maxY, b.minY, 1e-9)
            assertEquals("floating at reveal $reveal", 0.0, a.minY, 1e-9)
        }
    }

    @Test
    fun `a reveal of zero leaves the columns flat on the floor`() {
        val layout = layout(
            series = listOf(series("a", listOf(50.0, 0.0, 0.0))),
            grouping = BarGrouping.Grouped,
            reveal = 0f,
        )
        assertEquals(0.0, layout.segments.first().cuboid.bounds.height, tolerance)
    }

    // ---- depth ------------------------------------------------------------

    @Test
    fun `auto depth follows the footprint and relative depth scales it`() {
        val auto = layout(listOf(series("a", listOf(10.0, 0.0, 0.0)))).segments.first()
        val square = layout(
            listOf(series("a", listOf(10.0, 0.0, 0.0))),
            depth = Column3DDepth.Relative(1.0),
        ).segments.first()
        assertEquals(band.toDouble(), square.cuboid.bounds.depth, tolerance)
        assertEquals(band * Column3DDepth.AUTO_FRACTION, auto.cuboid.bounds.depth, 1e-6)
    }

    @Test
    fun `absolute depth is honoured exactly`() {
        val fixed = layout(
            listOf(series("a", listOf(10.0, 0.0, 0.0))),
            depth = Column3DDepth.Absolute(24f),
        ).segments.first()
        assertEquals(24.0, fixed.cuboid.bounds.depth, tolerance)
    }

    @Test
    fun `an impossible depth is rejected rather than clamped away`() {
        assertThrows { Column3DDepth.Relative(0.0) }
        assertThrows { Column3DDepth.Relative(Double.NaN) }
        assertThrows { Column3DDepth.Absolute(-4f) }
    }

    @Test
    fun `a negative depth gap is rejected`() {
        assertThrows {
            layout(listOf(series("a", listOf(10.0, 0.0, 0.0))), depthGap = -1.0)
        }
    }

    // ---- identity and totals ----------------------------------------------

    @Test
    fun `every segment carries the identity a tooltip needs`() {
        val layout = layout(
            series = listOf(
                series("a", listOf(10.0, 20.0, 30.0), stack = "one"),
                series("b", listOf(40.0, 50.0, 60.0), stack = "one"),
            ),
            grouping = BarGrouping.Stacked,
        )
        val keys = layout.segments.map { it.key }
        assertEquals("keys must be unique", keys.size, keys.distinct().size)
        val feb = layout.segments.first { it.key.categoryIndex == 1 && it.key.seriesId == "b" }
        assertEquals("Feb", feb.key.category)
        assertEquals("one", feb.key.stackId)
        assertEquals(1, feb.key.pointIndex)
        assertEquals(50.0, feb.value, tolerance)
        assertEquals("the whole column, not just this segment", 70.0, feb.stackTotal, tolerance)
    }

    @Test
    fun `a stack total is the signed sum, so mixed signs do not inflate it`() {
        val layout = layout(
            series = listOf(
                series("up", listOf(30.0, 0.0, 0.0), stack = "one"),
                series("down", listOf(-10.0, 0.0, 0.0), stack = "one"),
            ),
            grouping = BarGrouping.Stacked,
            fraction = { (it + 100.0) / 200.0 },
        )
        assertEquals(20.0, layout.segments.first().stackTotal, tolerance)
    }

    @Test
    fun `the plot volume spans the whole plot however deep the columns are`() {
        val layout = layout(listOf(series("a", listOf(10.0, 0.0, 0.0))))
        assertEquals(0.0, layout.volume.minX, tolerance)
        assertEquals(plotWidth.toDouble(), layout.volume.maxX, tolerance)
        assertEquals(plotHeight.toDouble(), layout.volume.maxY, tolerance)
        assertTrue(layout.volume.depth > 0.0)
    }

    // ---- helpers ----------------------------------------------------------

    private fun series(id: String, values: List<Double?>, stack: String? = null) = Column3DSeries(
        seriesId = id,
        seriesName = id.replaceFirstChar { it.uppercase() },
        paletteIndex = 0,
        colorOverride = null,
        stackId = stack ?: id,
        values = values,
        sourceIndices = values.indices.toList(),
    )

    @Suppress("LongParameterList")
    private fun layout(
        series: List<Column3DSeries>,
        grouping: BarGrouping = BarGrouping.Grouped,
        arrangement: Column3DArrangement = Column3DArrangement.Side,
        depth: Column3DDepth = Column3DDepth.Auto,
        depthGap: Double = 0.25,
        reveal: Float = 1f,
        fraction: (Double) -> Double = this.fraction,
    ) = Column3DLayoutEngine.layout(
        categories = categories,
        series = series,
        items = emptyMap(),
        grouping = grouping,
        arrangement = arrangement,
        depth = depth,
        categoryCentres = centres,
        bandWidth = band,
        valueFraction = fraction,
        plotWidth = plotWidth,
        plotHeight = plotHeight,
        groupPadding = 0.1,
        depthGap = depthGap,
        reveal = reveal,
    )

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (expected: Chart3DException) {
            return
        }
        throw AssertionError("Expected a Chart3DException")
    }
}
