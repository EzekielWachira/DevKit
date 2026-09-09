package io.devkit.chartkit

import io.devkit.chartkit.three.ArcTessellator3D
import io.devkit.chartkit.three.AnnularSector3D
import io.devkit.chartkit.three.Chart3DDepth
import io.devkit.chartkit.three.Chart3DException
import io.devkit.chartkit.three.Chart3DQuality
import io.devkit.chartkit.three.FaceSide
import io.devkit.chartkit.three.Point3D
import io.devkit.chartkit.three.RadialSector3D
import io.devkit.chartkit.three.Sector3D
import io.devkit.chartkit.three.Vector3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The radial geometry, on its own.
 *
 * Every assertion here is about a number that cannot be seen. A face wound
 * backwards, a normal pointing into the solid, an arc that stops a hundredth of
 * a degree short of its wall — none of these throws, and all of them reach the
 * screen as something a reader would call a rendering glitch rather than as
 * something a developer would call a bug. This is where they are caught.
 */
class Radial3DGeometryTest {

    private val tolerance = 1e-9

    // ---- sector geometry --------------------------------------------------

    @Test
    fun `a quarter slice spans exactly its own quarter`() {
        val sector = Sector3D(
            outerRadius = 1.0, startAngle = 0.0, sweepAngle = 90.0,
            baseY = -0.1, topY = 0.1, segments = 8,
        )
        // Chart angles: 0 is twelve o'clock, 90 is three o'clock. The disc is
        // horizontal, so on screen those are the far side and the right side —
        // in world terms +z and +x.
        val corners = sector.faces.flatMap { it.vertices }
        assertTrue("the slice must reach +z", corners.any { near(it.x, 0.0) && near(it.z, 1.0) })
        assertTrue("the slice must reach +x", corners.any { near(it.x, 1.0) && near(it.z, 0.0) })
        assertTrue("nothing may cross into -x", corners.all { it.x >= -tolerance })
        assertTrue("nothing may cross into -z", corners.all { it.z >= -tolerance })
    }

    @Test
    fun `a half slice covers half the circle and a full one covers all of it`() {
        val half = Sector3D(1.0, 0.0, 180.0, -0.1, 0.1, segments = 12)
        val full = Sector3D(1.0, 0.0, 360.0, -0.1, 0.1, segments = 24)
        assertTrue(
            "a half pie stays on one side",
            half.faces.flatMap { it.vertices }.all { it.x >= -tolerance },
        )
        assertEquals(-1.0, full.bounds.minX, 1e-2)
        assertEquals(1.0, full.bounds.maxX, 1e-2)
        assertEquals(-1.0, full.bounds.minZ, 1e-2)
        assertEquals(1.0, full.bounds.maxZ, 1e-2)
    }

    @Test
    fun `a full circle has no radial walls and a partial one has two`() {
        val full = Sector3D(1.0, 0.0, 360.0, -0.1, 0.1, segments = 16)
        val partial = Sector3D(1.0, 0.0, 359.0, -0.1, 0.1, segments = 16)
        assertEquals(0, full.faces.count { it.side == FaceSide.Start || it.side == FaceSide.End })
        assertEquals(1, partial.faces.count { it.side == FaceSide.Start })
        assertEquals(1, partial.faces.count { it.side == FaceSide.End })
    }

    @Test
    fun `a custom start angle rotates the slice and nothing else`() {
        val at0 = Sector3D(1.0, 0.0, 60.0, -0.1, 0.1, segments = 6)
        val at90 = Sector3D(1.0, 90.0, 60.0, -0.1, 0.1, segments = 6)
        assertEquals(at0.faces.size, at90.faces.size)
        assertEquals(
            "rotating a slice cannot change how much of the circle it covers",
            at0.sweepAngle,
            at90.sweepAngle,
            tolerance,
        )
        // The first outer vertex of the second slice sits where the first
        // slice's would after a quarter turn: (sin, cos) of 90 onto (x, z) is
        // (1, 0).
        val first = at90.faces.first { it.side == FaceSide.Top }.vertices.first()
        assertEquals(1.0, first.x, 1e-9)
        assertEquals(0.0, first.z, 1e-9)
    }

    @Test
    fun `a tiny slice still has faces and no NaN anywhere`() {
        val sliver = Sector3D(1.0, 12.0, 0.05, -0.1, 0.1, segments = 2)
        assertTrue("a sliver still has geometry", sliver.faces.isNotEmpty())
        sliver.faces.forEach { face ->
            assertTrue("a vertex must be finite", face.vertices.all { it.isFinite })
            assertTrue("a normal must be finite", face.normal.isFinite)
        }
    }

    @Test
    fun `a zero sweep produces no geometry rather than a degenerate face`() {
        val nothing = Sector3D(1.0, 0.0, 0.0, -0.1, 0.1, segments = 8)
        assertTrue(nothing.isDegenerate)
        assertTrue(nothing.faces.isEmpty())
    }

    // ---- annular geometry -------------------------------------------------

    @Test
    fun `a donut segment has an inner wall and a pie slice does not`() {
        val pie = Sector3D(1.0, 0.0, 90.0, -0.1, 0.1, segments = 8)
        val donut = AnnularSector3D(0.6, 1.0, 0.0, 90.0, -0.1, 0.1, segments = 8)
        assertEquals(0, pie.faces.count { it.side == FaceSide.Inner })
        assertEquals(8, donut.faces.count { it.side == FaceSide.Inner })
        assertTrue(donut.isAnnular)
        assertTrue(!pie.isAnnular)
    }

    @Test
    fun `a donut segment keeps its hole clear`() {
        val donut = AnnularSector3D(0.6, 1.0, 0.0, 90.0, -0.1, 0.1, segments = 16)
        val radii = donut.faces.flatMap { it.vertices }.map { kotlin.math.hypot(it.x, it.z) }
        assertTrue("no vertex may fall inside the hole", radii.all { it >= 0.6 - 1e-9 })
        assertTrue("no vertex may pass the rim", radii.all { it <= 1.0 + 1e-9 })
    }

    @Test
    fun `a pie cap is a triangle and a donut cap is a quad`() {
        val pie = Sector3D(1.0, 0.0, 90.0, -0.1, 0.1, segments = 4)
        val donut = AnnularSector3D(0.5, 1.0, 0.0, 90.0, -0.1, 0.1, segments = 4)
        assertEquals(3, pie.faces.first { it.side == FaceSide.Top }.vertices.size)
        assertEquals(4, donut.faces.first { it.side == FaceSide.Top }.vertices.size)
    }

    @Test
    fun `inner radius zero and a pie slice are the same shape`() {
        val viaPie = Sector3D(1.0, 30.0, 70.0, -0.2, 0.2, segments = 6)
        val viaAnnulus = AnnularSector3D(0.0, 1.0, 30.0, 70.0, -0.2, 0.2, segments = 6)
        assertEquals(viaPie.faces.size, viaAnnulus.faces.size)
        viaPie.faces.zip(viaAnnulus.faces).forEach { (a, b) ->
            assertEquals(a.side, b.side)
            assertEquals(a.vertices.size, b.vertices.size)
        }
    }

    // ---- normals ----------------------------------------------------------

    @Test
    fun `the top surface faces up and the underside faces down`() {
        // The property the whole orientation rests on. Extruded the other way —
        // a disc standing upright, pushed away from the reader — these normals
        // are ∓z instead, the rim then projects *above* the surface, and the
        // chart reads as the underside of a plate.
        val sector = AnnularSector3D(0.4, 1.0, 20.0, 100.0, -0.15, 0.15, segments = 10)
        sector.faces.filter { it.side == FaceSide.Top }.forEach {
            assertEquals("the top surface's normal is +y", 1.0, it.normal.y, 1e-9)
        }
        sector.faces.filter { it.side == FaceSide.Bottom }.forEach {
            assertEquals("the underside's normal is -y", -1.0, it.normal.y, 1e-9)
        }
    }

    @Test
    fun `the outer wall points away from the axis`() {
        val sector = AnnularSector3D(0.4, 1.0, 0.0, 140.0, -0.15, 0.15, segments = 12)
        sector.faces.filter { it.side == FaceSide.Outer }.forEach { face ->
            val centroid = face.centroid
            // The radial direction at the face's own position. A wall that
            // points outward has a normal with a positive component along it.
            val radial = Vector3D(centroid.x, 0.0, centroid.z).normalized()
            assertTrue(
                "an outer wall must point away from the axis, was ${face.normal}",
                (face.normal dot radial) > 0.9,
            )
            assertEquals("an outer wall is horizontal", 0.0, face.normal.y, 1e-9)
        }
    }

    @Test
    fun `the inner wall points toward the axis`() {
        val sector = AnnularSector3D(0.4, 1.0, 0.0, 140.0, -0.15, 0.15, segments = 12)
        val inner = sector.faces.filter { it.side == FaceSide.Inner }
        assertTrue("a donut has an inner wall", inner.isNotEmpty())
        inner.forEach { face ->
            val centroid = face.centroid
            val radial = Vector3D(centroid.x, 0.0, centroid.z).normalized()
            assertTrue(
                "an inner wall must point toward the axis, was ${face.normal}",
                (face.normal dot radial) < -0.9,
            )
        }
    }

    @Test
    fun `the radial walls point along their own angular boundaries`() {
        val start = 30.0
        val sweep = 80.0
        val sector = AnnularSector3D(0.4, 1.0, start, sweep, -0.15, 0.15, segments = 8)

        val startFace = sector.faces.first { it.side == FaceSide.Start }
        // The outward direction of the wall at the sector's first edge is a
        // quarter turn *back* from that edge's own radius.
        val expectedStart = unitAt(start - 90.0)
        assertEquals(expectedStart.x, startFace.normal.x, 1e-9)
        assertEquals(expectedStart.z, startFace.normal.z, 1e-9)

        val endFace = sector.faces.first { it.side == FaceSide.End }
        val expectedEnd = unitAt(start + sweep + 90.0)
        assertEquals(expectedEnd.x, endFace.normal.x, 1e-9)
        assertEquals(expectedEnd.z, endFace.normal.z, 1e-9)
    }

    @Test
    fun `no face of a well formed sector has a zero normal`() {
        val sector = AnnularSector3D(0.35, 1.0, 12.0, 200.0, -0.2, 0.2, segments = 20)
        sector.faces.forEach { face ->
            assertTrue("a face with no normal is a face that is never drawn", !face.normal.isZero)
            assertEquals("a normal is a unit vector", 1.0, face.normal.length, 1e-9)
        }
    }

    // ---- depth ------------------------------------------------------------

    @Test
    fun `the surfaces are separated by exactly the stated depth`() {
        val sector = Sector3D(1.0, 0.0, 90.0, -0.3, 0.45, segments = 6)
        assertEquals(0.75, sector.depth, tolerance)
        assertEquals(-0.3, sector.bounds.minY, tolerance)
        assertEquals(0.45, sector.bounds.maxY, tolerance)
        val top = sector.faces.first { it.side == FaceSide.Top }
        val bottom = sector.faces.first { it.side == FaceSide.Bottom }
        assertTrue(top.vertices.all { near(it.y, 0.45) })
        assertTrue(bottom.vertices.all { near(it.y, -0.3) })
    }

    // ---- explode ----------------------------------------------------------

    @Test
    fun `an exploded slice moves every one of its vertices by the same amount`() {
        val at = Sector3D(1.0, 40.0, 60.0, -0.1, 0.1, segments = 8)
        val moved = Sector3D(1.0, 40.0, 60.0, -0.1, 0.1, offsetX = 0.2, offsetZ = -0.3, segments = 8)
        val before = at.faces.flatMap { it.vertices }
        val after = moved.faces.flatMap { it.vertices }
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (a, b) ->
            assertEquals(0.2, b.x - a.x, 1e-12)
            // Across the table, never off it: an exploded slice keeps its height.
            assertEquals(0.0, b.y - a.y, 1e-12)
            assertEquals(-0.3, b.z - a.z, 1e-12)
        }
    }

    @Test
    fun `an explode does not change any normal`() {
        val at = Sector3D(1.0, 40.0, 60.0, -0.1, 0.1, segments = 8)
        val moved = Sector3D(1.0, 40.0, 60.0, -0.1, 0.1, offsetX = 0.4, offsetZ = 0.1, segments = 8)
        at.faces.zip(moved.faces).forEach { (a, b) ->
            assertEquals(a.normal.x, b.normal.x, 1e-12)
            assertEquals(a.normal.y, b.normal.y, 1e-12)
            assertEquals(a.normal.z, b.normal.z, 1e-12)
        }
    }

    // ---- tessellation -----------------------------------------------------

    @Test
    fun `segments never fall below the floor or rise above the ceiling`() {
        assertEquals(
            ArcTessellator3D.MIN_SEGMENTS,
            ArcTessellator3D.segmentsFor(0.4, 20.0, Chart3DQuality.Low),
        )
        assertTrue(
            ArcTessellator3D.segmentsFor(360.0, 100_000.0, Chart3DQuality.High) <=
                ArcTessellator3D.MAX_SEGMENTS,
        )
        assertEquals(
            "a chart measured at zero size is a layout state, not an error",
            ArcTessellator3D.MIN_SEGMENTS,
            ArcTessellator3D.segmentsFor(90.0, 0.0, Chart3DQuality.Auto),
        )
    }

    @Test
    fun `a larger radius needs more segments at the same quality`() {
        val small = ArcTessellator3D.segmentsFor(90.0, 60.0, Chart3DQuality.Auto)
        val large = ArcTessellator3D.segmentsFor(90.0, 600.0, Chart3DQuality.Auto)
        assertTrue("a bigger arc is more visibly faceted: $small vs $large", large > small)
    }

    @Test
    fun `a finer quality needs at least as many segments as a coarser one`() {
        val radius = 300.0
        val low = ArcTessellator3D.segmentsFor(120.0, radius, Chart3DQuality.Low)
        val medium = ArcTessellator3D.segmentsFor(120.0, radius, Chart3DQuality.Medium)
        val auto = ArcTessellator3D.segmentsFor(120.0, radius, Chart3DQuality.Auto)
        val high = ArcTessellator3D.segmentsFor(120.0, radius, Chart3DQuality.High)
        assertTrue(low <= medium)
        assertTrue(medium <= auto)
        assertTrue(auto <= high)
    }

    @Test
    fun `the chosen segment count keeps the chord within its own tolerance`() {
        val radius = 400.0
        listOf(Chart3DQuality.Low, Chart3DQuality.Medium, Chart3DQuality.Auto, Chart3DQuality.High)
            .forEach { quality ->
                val segments = ArcTessellator3D.segmentsFor(90.0, radius, quality)
                val error = ArcTessellator3D.sagitta(radius, 90.0 / segments)
                assertTrue(
                    "$quality chose $segments segments, which is ${error}px off its arc",
                    error <= quality.tolerancePx + 1e-9,
                )
            }
    }

    @Test
    fun `sampled angles start and end exactly on the boundaries`() {
        val angles = ArcTessellator3D.anglesFor(37.5, 82.25, 7)
        assertEquals(8, angles.size)
        assertEquals(37.5, angles.first(), 1e-12)
        // Exactly, not nearly: a wall built at the last angle and an arc that
        // stopped short of it would leave a hairline of background between them.
        assertEquals(37.5 + 82.25, angles.last(), 0.0)
    }

    @Test
    fun `adjacent segments share their edge, so the surface has no gaps`() {
        val sector = AnnularSector3D(0.5, 1.0, 15.0, 130.0, -0.1, 0.1, segments = 9)
        val outer = sector.faces.filter { it.side == FaceSide.Outer }
        assertEquals(9, outer.size)
        outer.zipWithNext().forEach { (first, second) ->
            // The wall quads are wound base, base, top, top — so one quad's
            // second base vertex is the next quad's first, and its second top
            // vertex is the next quad's last.
            assertEquals(first.vertices[1], second.vertices[0])
            assertEquals(first.vertices[2], second.vertices[3])
        }
    }

    @Test
    fun `the tessellation reaches the sector's own edges`() {
        val sector = Sector3D(1.0, 20.0, 55.0, -0.1, 0.1, segments = 5)
        val outer = sector.faces.filter { it.side == FaceSide.Outer }
        val firstEdge = outer.first().vertices[0]
        val lastEdge = outer.last().vertices[1]
        assertEquals(unitAt(20.0).x, firstEdge.x, 1e-9)
        assertEquals(unitAt(20.0).z, firstEdge.z, 1e-9)
        assertEquals(unitAt(75.0).x, lastEdge.x, 1e-9)
        assertEquals(unitAt(75.0).z, lastEdge.z, 1e-9)
    }

    @Test
    fun `the fit points are the sector's own rim rather than its bounding box`() {
        val sector = Sector3D(1.0, 0.0, 360.0, -0.1, 0.1, segments = 24)
        val points = sector.fitPoints()
        assertTrue(points.isNotEmpty())
        assertTrue(
            "every fit point sits on the rim, never at a box corner",
            points.all { abs(kotlin.math.hypot(it.x, it.z) - 1.0) < 1e-9 },
        )
    }

    // ---- validation -------------------------------------------------------

    @Test
    fun `a sector rejects the configurations it cannot be drawn from`() {
        assertThrows { RadialSector3D(-0.1, 1.0, 0.0, 90.0, -0.1, 0.1) }
        assertThrows { RadialSector3D(0.9, 0.5, 0.0, 90.0, -0.1, 0.1) }
        assertThrows { RadialSector3D(0.0, 1.0, Double.NaN, 90.0, -0.1, 0.1) }
        assertThrows { RadialSector3D(0.0, 1.0, 0.0, -5.0, -0.1, 0.1) }
        assertThrows { RadialSector3D(0.0, 1.0, 0.0, 90.0, 0.4, 0.1) }
    }

    @Test
    fun `depth rejects what it cannot mean`() {
        assertThrows { Chart3DDepth.Relative(0.0) }
        assertThrows { Chart3DDepth.Relative(Double.NaN) }
        assertThrows { Chart3DDepth.Absolute(-3f) }
        assertEquals(0.35, (Chart3DDepth.Relative(0.35) as Chart3DDepth.Relative).fraction, tolerance)
        assertEquals(12f, (Chart3DDepth.Absolute(12f) as Chart3DDepth.Absolute).pixels)
    }

    @Test
    fun `a sector reports the anchors a label and a tooltip hang from`() {
        val sector = AnnularSector3D(0.5, 1.0, 0.0, 90.0, -0.2, 0.2, segments = 8)
        val anchor = sector.anchor()
        assertNotNull(anchor)
        assertEquals("an anchor sits on the top surface", 0.2, anchor.y, tolerance)
        assertEquals(
            "an anchor sits halfway across the ring",
            0.75,
            kotlin.math.hypot(anchor.x, anchor.z),
            1e-9,
        )
        val rim = sector.rimPoint()
        assertEquals("a leader leaves from the middle of the rim", 0.0, rim.y, tolerance)
        assertEquals(1.0, kotlin.math.hypot(rim.x, rim.z), 1e-9)
    }

    // ---- helpers ----------------------------------------------------------

    private fun near(a: Double, b: Double) = abs(a - b) < 1e-9

    /** The world direction at a chart angle, on the horizontal disc: (sin, 0, cos). */
    private fun unitAt(degrees: Double): Point3D {
        val radians = Math.toRadians(degrees)
        return Point3D(sin(radians), 0.0, cos(radians))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            org.junit.Assert.fail("expected a Chart3DException")
        } catch (expected: Chart3DException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }
}
