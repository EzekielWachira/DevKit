package io.devkit.chartkit

import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.AxisScale
import io.devkit.chartkit.three.Cartesian3DCoordinates
import io.devkit.chartkit.three.Chart3DPlotBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three scales, and the one property that makes them a *3D Cartesian*
 * system rather than two axes and a depth setting: each is derived from its own
 * field and normalised on its own.
 *
 * The failure these tests exist to catch is not an exception. It is a chart
 * that derives one range across all three variables, draws, looks plausible,
 * and quietly flattens two of the three onto the floor of the volume.
 */
class Cartesian3DCoordinatesTest {

    private val box = Chart3DPlotBox(width = 400.0, height = 300.0, depth = 200.0)

    private fun scale(min: Double, max: Double) = LinearScale(
        domain = NumericDomain(min, max),
        rangeStart = 0f,
        rangeEnd = 1f,
    )

    /** Age 18..80, income 20k..200k, satisfaction 0..100. §41's own example. */
    private fun coordinates() = Cartesian3DCoordinates(
        xScale = scale(18.0, 80.0),
        yScale = scale(20_000.0, 200_000.0),
        zScale = scale(0.0, 100.0),
        box = box,
    )

    @Test
    fun `each domain minimum lands at its own origin corner`() {
        val world = coordinates().worldOf(18.0, 20_000.0, 0.0)
        assertNotNull(world)
        assertEquals(0.0, world!!.x, 1e-9)
        assertEquals(0.0, world.y, 1e-9)
        assertEquals(0.0, world.z, 1e-9)
    }

    @Test
    fun `each domain maximum reaches the far corner of the box`() {
        val world = coordinates().worldOf(80.0, 200_000.0, 100.0)!!
        assertEquals(box.width, world.x, 1e-9)
        assertEquals(box.height, world.y, 1e-9)
        assertEquals(box.depth, world.z, 1e-9)
    }

    @Test
    fun `each midpoint lands at the centre of its own extent`() {
        val world = coordinates().worldOf(49.0, 110_000.0, 50.0)!!
        assertEquals(box.width / 2.0, world.x, 1e-9)
        assertEquals(box.height / 2.0, world.y, 1e-9)
        assertEquals(box.depth / 2.0, world.z, 1e-9)
    }

    /**
     * The one that catches a shared range.
     *
     * Three variables whose magnitudes differ by four orders of magnitude, each
     * at the same relative position in its own domain, must land at the same
     * relative position in the box. A single combined domain would put the two
     * small ones within a rounding error of zero.
     */
    @Test
    fun `wildly different magnitudes normalise to the same fraction`() {
        val world = coordinates().worldOf(
            x = 18.0 + (80.0 - 18.0) * 0.25,
            y = 20_000.0 + 180_000.0 * 0.25,
            z = 25.0,
        )!!
        assertEquals(box.width * 0.25, world.x, 1e-9)
        assertEquals(box.height * 0.25, world.y, 1e-9)
        assertEquals(box.depth * 0.25, world.z, 1e-9)
    }

    @Test
    fun `a domain that straddles zero maps its negative half below the middle`() {
        val coordinates = Cartesian3DCoordinates(
            xScale = scale(0.0, 10.0),
            yScale = scale(100.0, 200.0),
            zScale = scale(-50.0, 50.0),
            box = box,
        )
        assertEquals(0.0, coordinates.zFraction(-50.0), 1e-9)
        assertEquals(0.5, coordinates.zFraction(0.0), 1e-9)
        assertEquals(1.0, coordinates.zFraction(50.0), 1e-9)
        assertEquals(box.depth * 0.25, coordinates.worldOf(5.0, 150.0, -25.0)!!.z, 1e-9)
    }

    @Test
    fun `three independent domains are three independent normalisations`() {
        val coordinates = Cartesian3DCoordinates(
            xScale = scale(0.0, 10.0),
            yScale = scale(100.0, 200.0),
            zScale = scale(-50.0, 50.0),
            box = box,
        )
        // The same raw number, 0, means three different things.
        assertEquals(0.0, coordinates.xFraction(0.0), 1e-9)
        assertTrue(
            "y=0 is below its own domain and must not be reported at its minimum",
            coordinates.yFraction(0.0) < 0.0,
        )
        assertEquals(0.5, coordinates.zFraction(0.0), 1e-9)
    }

    @Test
    fun `an observation missing a coordinate has no world position`() {
        val coordinates = coordinates()
        assertNull(coordinates.worldOf(Double.NaN, 100_000.0, 50.0))
        assertNull(coordinates.worldOf(40.0, Double.NaN, 50.0))
        assertNull(coordinates.worldOf(40.0, 100_000.0, Double.NaN))
    }

    /**
     * A log Z axis is the same class with a different transform, exactly as a
     * log Y axis is — which is the whole argument for Z being an ordinary
     * scale rather than a depth setting.
     */
    @Test
    fun `a logarithmic depth axis places a decade at each equal step`() {
        val coordinates = Cartesian3DCoordinates(
            xScale = scale(0.0, 1.0),
            yScale = scale(0.0, 1.0),
            zScale = LinearScale(
                domain = NumericDomain(1.0, 1000.0),
                rangeStart = 0f,
                rangeEnd = 1f,
                transform = AxisScale.Log().transform(),
            ),
            box = box,
        )
        assertEquals(0.0, coordinates.zFraction(1.0), 1e-9)
        assertEquals(1.0 / 3.0, coordinates.zFraction(10.0), 1e-9)
        assertEquals(2.0 / 3.0, coordinates.zFraction(100.0), 1e-9)
        assertEquals(1.0, coordinates.zFraction(1000.0), 1e-9)
    }

    @Test
    fun `a value outside a fixed domain is placed outside the box, not clamped into it`() {
        val coordinates = coordinates()
        val world = coordinates.worldOf(90.0, 100_000.0, 50.0)!!
        assertTrue(
            "a value past the axis maximum must be drawn past the wall, at ${world.x}",
            world.x > box.width,
        )
    }

    @Test
    fun `a box with no extent still has a volume the frame can stand in`() {
        val flat = Chart3DPlotBox(width = 0.0, height = 0.0, depth = 0.0)
        assertTrue(flat.volume.width > 0.0)
        assertTrue(flat.volume.height > 0.0)
        assertTrue(flat.volume.depth > 0.0)
    }
}
