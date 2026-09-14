package io.devkit.chartkit

import io.devkit.chartkit.geometry.HexCell
import io.devkit.chartkit.geometry.HexGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** Hexagonal binning: the arithmetic a hexbin map stands on. */
class HexGridTest {

    private val radius = 10.0

    @Test
    fun `the origin falls in the origin cell`() {
        assertEquals(HexCell(0, 0), HexGrid.cellAt(0.0, 0.0, radius))
    }

    @Test
    fun `a cell's centre round-trips back to that cell`() {
        // The property everything else depends on: binning and unbinning must
        // agree, or a hexagon would be drawn somewhere other than where its
        // points were counted.
        val cells = listOf(
            HexCell(0, 0), HexCell(1, 0), HexCell(0, 1), HexCell(-1, 2),
            HexCell(3, -2), HexCell(-4, -1), HexCell(7, 5),
        )
        cells.forEach { cell ->
            val (x, y) = HexGrid.centerOf(cell, radius)
            assertEquals(cell, HexGrid.cellAt(x, y, radius))
        }
    }

    @Test
    fun `a point near a centre bins to that cell`() {
        val cell = HexCell(2, -1)
        val (x, y) = HexGrid.centerOf(cell, radius)

        listOf(0.1, -0.1).forEach { nudge ->
            assertEquals(cell, HexGrid.cellAt(x + nudge, y + nudge, radius))
        }
    }

    @Test
    fun `rounding is done in cube space, so boundaries pick the nearer cell`() {
        // Rounding each axial coordinate independently picks the wrong cell
        // near a boundary, because the axes are not orthogonal. Walking a line
        // between two centres, every sample must land in one or the other.
        val a = HexCell(0, 0)
        val b = HexCell(1, 0)
        val (ax, ay) = HexGrid.centerOf(a, radius)
        val (bx, by) = HexGrid.centerOf(b, radius)

        (0..20).forEach { step ->
            val t = step / 20.0
            val found = HexGrid.cellAt(ax + (bx - ax) * t, ay + (by - ay) * t, radius)
            assertTrue("at t=$t got $found", found == a || found == b)
        }
    }

    @Test
    fun `every cell keeps the cube invariant`() {
        (-50..50).forEach { x ->
            val cell = HexGrid.cellAt(x * 3.7, x * -2.1, radius)
            assertEquals(0, cell.q + cell.r + cell.s)
        }
    }

    @Test
    fun `neighbouring rows are offset by half a width`() {
        val (x0, y0) = HexGrid.centerOf(HexCell(0, 0), radius)
        val (x1, y1) = HexGrid.centerOf(HexCell(0, 1), radius)

        assertEquals(radius * 1.5, y1 - y0, 1e-9)
        assertEquals(radius * sqrt(3.0) / 2.0, x1 - x0, 1e-9)
    }

    @Test
    fun `a hexagon has six corners, all at the radius`() {
        val corners = HexGrid.corners(0f, 0f, 10f)

        assertEquals(6, corners.size)
        corners.forEach { corner ->
            val distance = sqrt((corner.x * corner.x + corner.y * corner.y).toDouble())
            assertEquals(10.0, distance, 1e-4)
        }
    }

    @Test
    fun `the first corner is directly above the centre`() {
        val first = HexGrid.corners(0f, 0f, 10f).first()

        assertEquals(0.0, first.x.toDouble(), 1e-4)
        assertEquals(-10.0, first.y.toDouble(), 1e-4)
    }

    @Test
    fun `containment accepts the centre and refuses a point well outside`() {
        assertTrue(HexGrid.contains(0f, 0f, 10f, 0f, 0f))
        assertFalse(HexGrid.contains(0f, 0f, 10f, 100f, 100f))
    }

    @Test
    fun `containment is exact at the corners and the edges`() {
        // A circular approximation either refuses taps on the corners, which
        // reach the full radius, or accepts taps in the gaps beyond the edges,
        // which only reach √3/2 of it. Both are visible to anyone who tries.
        val justInsideTop = HexGrid.contains(0f, 0f, 10f, 0f, -9.9f)
        val justOutsideTop = HexGrid.contains(0f, 0f, 10f, 0f, -10.1f)
        // Due east is an edge midpoint, at √3/2 of the radius.
        val edge = (10.0 * sqrt(3.0) / 2.0).toFloat()
        val justInsideEdge = HexGrid.contains(0f, 0f, 10f, edge - 0.1f, 0f)
        val beyondEdge = HexGrid.contains(0f, 0f, 10f, edge + 0.1f, 0f)

        assertTrue(justInsideTop)
        assertFalse(justOutsideTop)
        assertTrue(justInsideEdge)
        assertFalse(beyondEdge)
    }

    @Test
    fun `a point in the corner gap of the bounding box is outside`() {
        // Top-right of the bounding box: inside the box, outside the hexagon.
        assertFalse(HexGrid.contains(0f, 0f, 10f, 8f, -9f))
    }

    @Test
    fun `containment and binning agree`() {
        // If a point bins to a cell, it must be inside that cell's hexagon —
        // otherwise a tap and a count would disagree about which bin a point
        // belongs to.
        var checked = 0
        for (x in -40..40 step 3) {
            for (y in -40..40 step 3) {
                val cell = HexGrid.cellAt(x.toDouble(), y.toDouble(), radius)
                val (cx, cy) = HexGrid.centerOf(cell, radius)
                assertTrue(
                    "($x, $y) binned to $cell but is outside it",
                    HexGrid.contains(cx.toFloat(), cy.toFloat(), radius.toFloat(), x.toFloat(), y.toFloat()),
                )
                checked++
            }
        }
        assertTrue(checked > 500)
    }

    @Test
    fun `distance counts steps between cells`() {
        assertEquals(0, HexGrid.distance(HexCell(0, 0), HexCell(0, 0)))
        assertEquals(1, HexGrid.distance(HexCell(0, 0), HexCell(1, 0)))
        assertEquals(1, HexGrid.distance(HexCell(0, 0), HexCell(0, 1)))
        assertEquals(2, HexGrid.distance(HexCell(0, 0), HexCell(2, 0)))
        assertEquals(3, HexGrid.distance(HexCell(-1, 0), HexCell(2, 0)))
    }

    @Test
    fun `a zero radius degenerates rather than dividing by zero`() {
        assertEquals(HexCell(0, 0), HexGrid.cellAt(5.0, 5.0, 0.0))
        assertFalse(HexGrid.contains(0f, 0f, 0f, 0f, 0f))
    }

    @Test
    fun `binning is deterministic`() {
        val first = (0..100).map { HexGrid.cellAt(it * 1.7, it * -0.9, radius) }
        val second = (0..100).map { HexGrid.cellAt(it * 1.7, it * -0.9, radius) }

        assertEquals(first, second)
    }

    @Test
    fun `cells tile, and only a boundary point is in more than one`() {
        // A point strictly inside a cell is inside exactly that one. A point
        // lying exactly on an edge is inside both of its neighbours, which is
        // deliberate — see `contains`, where testing exactly would leave a dead
        // hairline along every boundary.
        for (x in -30..30 step 7) {
            for (y in -30..30 step 7) {
                // Nudged off the lattice, so no sample lands on a boundary.
                val px = x + 0.37
                val py = y + 0.11
                val owner = HexGrid.cellAt(px, py, radius)
                val inside = listOf(
                    owner,
                    HexCell(owner.q + 1, owner.r), HexCell(owner.q - 1, owner.r),
                    HexCell(owner.q, owner.r + 1), HexCell(owner.q, owner.r - 1),
                ).count { cell ->
                    val (cx, cy) = HexGrid.centerOf(cell, radius)
                    HexGrid.contains(
                        cx.toFloat(), cy.toFloat(), radius.toFloat(), px.toFloat(), py.toFloat(),
                    )
                }
                assertEquals("($px, $py) is in $inside cells", 1, inside)
            }
        }
    }

    @Test
    fun `a point on an edge is inside the cell it binned to`() {
        // The invariant that actually matters, and the one an exact test
        // breaks: binning and hit testing must never disagree, or a tap lands
        // on nothing where a point was counted.
        val onBoundary = listOf(0.0 to 15.0, 15.0 to 0.0, -15.0 to 15.0, 8.66 to 5.0)
        onBoundary.forEach { (x, y) ->
            val cell = HexGrid.cellAt(x, y, radius)
            val (cx, cy) = HexGrid.centerOf(cell, radius)
            assertTrue(
                "($x, $y) binned to $cell but is not inside it",
                HexGrid.contains(cx.toFloat(), cy.toFloat(), radius.toFloat(), x.toFloat(), y.toFloat()),
            )
        }
    }
}
