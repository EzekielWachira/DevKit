package io.devkit.chartkit

import io.devkit.chartkit.geo.GeoHexbin
import io.devkit.chartkit.geo.HexAggregate
import io.devkit.chartkit.geo.ProjectedPoint
import io.devkit.chartkit.geometry.HexGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Binning projected points, and what each bin comes to. */
class GeoHexbinTest {

    private val radius = 10.0

    private fun points(vararg pairs: Pair<Double, Double>) =
        pairs.map { (x, y) -> ProjectedPoint(x, y) }

    @Test
    fun `points near each other land in one bin`() {
        val bins = GeoHexbin.bin(points(0.0 to 0.0, 1.0 to 1.0, -1.0 to 0.5), radius)

        assertEquals(1, bins.size)
        assertEquals(3, bins.first().count)
    }

    @Test
    fun `points far apart land in different bins`() {
        val bins = GeoHexbin.bin(points(0.0 to 0.0, 100.0 to 100.0, -80.0 to 40.0), radius)

        assertEquals(3, bins.size)
        bins.forEach { assertEquals(1, it.count) }
    }

    @Test
    fun `every point is counted exactly once`() {
        val scattered = (0 until 200).map {
            ProjectedPoint((it * 7 % 97).toDouble(), (it * 13 % 89).toDouble())
        }
        val bins = GeoHexbin.bin(scattered, radius)

        assertEquals(200, bins.sumOf { it.count })
        assertEquals(200, bins.flatMap { it.indices }.distinct().size)
    }

    @Test
    fun `a bin's centre is the cell's centre, so the hexagon sits where its points were counted`() {
        val bins = GeoHexbin.bin(points(23.0 to 17.0), radius)
        val bin = bins.single()
        val (x, y) = HexGrid.centerOf(bin.cell, radius)

        assertEquals(x, bin.center.x, 1e-9)
        assertEquals(y, bin.center.y, 1e-9)
    }

    @Test
    fun `Count needs no values and reports how many fell in`() {
        val bins = GeoHexbin.bin(points(0.0 to 0.0, 1.0 to 1.0), radius, HexAggregate.Count)

        assertEquals(2.0, bins.single().value!!, 1e-9)
    }

    @Test
    fun `Sum, Mean and Max read the values`() {
        val p = points(0.0 to 0.0, 1.0 to 1.0, -1.0 to 1.0)
        val values = listOf(10.0, 20.0, 60.0)

        assertEquals(90.0, GeoHexbin.bin(p, radius, HexAggregate.Sum, values).single().value!!, 1e-9)
        assertEquals(30.0, GeoHexbin.bin(p, radius, HexAggregate.Mean, values).single().value!!, 1e-9)
        assertEquals(60.0, GeoHexbin.bin(p, radius, HexAggregate.Max, values).single().value!!, 1e-9)
    }

    @Test
    fun `a bin with no usable value has no total rather than a zero`() {
        // Reporting zero would put it at the bottom of the colour scale beside
        // the genuinely low places.
        val p = points(0.0 to 0.0, 1.0 to 1.0)
        val bins = GeoHexbin.bin(p, radius, HexAggregate.Sum, listOf(null, null))

        assertNull(bins.single().value)
        // It is still a bin, and it still counts its records.
        assertEquals(2, bins.single().count)
    }

    @Test
    fun `a bin with some values missing aggregates the ones it has`() {
        val p = points(0.0 to 0.0, 1.0 to 1.0, -1.0 to 1.0)
        val bins = GeoHexbin.bin(p, radius, HexAggregate.Mean, listOf(10.0, null, 30.0))

        assertEquals(20.0, bins.single().value!!, 1e-9)
        assertEquals(3, bins.single().count)
    }

    @Test
    fun `non-finite values are ignored by the aggregates`() {
        val p = points(0.0 to 0.0, 1.0 to 1.0)
        val bins = GeoHexbin.bin(p, radius, HexAggregate.Sum, listOf(5.0, Double.NaN))

        assertEquals(5.0, bins.single().value!!, 1e-9)
    }

    @Test
    fun `Count is unaffected by missing values`() {
        val p = points(0.0 to 0.0, 1.0 to 1.0)
        val bins = GeoHexbin.bin(p, radius, HexAggregate.Count, listOf(null, null))

        assertEquals(2.0, bins.single().value!!, 1e-9)
    }

    @Test
    fun `a non-finite point is dropped rather than binned somewhere`() {
        val p = listOf(
            ProjectedPoint(0.0, 0.0),
            ProjectedPoint(Double.NaN, 0.0),
            ProjectedPoint(0.0, Double.POSITIVE_INFINITY),
        )
        val bins = GeoHexbin.bin(p, radius)

        assertEquals(1, bins.sumOf { it.count })
    }

    @Test
    fun `bins carry the caller's own record positions`() {
        val bins = GeoHexbin.bin(points(0.0 to 0.0, 100.0 to 0.0, 1.0 to 1.0), radius)
        val crowded = bins.first { it.count == 2 }

        assertEquals(listOf(0, 2), crowded.indices)
    }

    @Test
    fun `the bin order is stable across runs`() {
        // What makes the draw order, and so the overlap of any partially
        // transparent fill, reproducible.
        val p = (0 until 50).map { ProjectedPoint((it * 11 % 71).toDouble(), (it * 3 % 53).toDouble()) }
        val first = GeoHexbin.bin(p, radius).map { it.cell }
        val second = GeoHexbin.bin(p, radius).map { it.cell }

        assertEquals(first, second)
    }

    @Test
    fun `no points, no radius and a non-finite radius all bin to nothing`() {
        assertTrue(GeoHexbin.bin(emptyList(), radius).isEmpty())
        assertTrue(GeoHexbin.bin(points(0.0 to 0.0), 0.0).isEmpty())
        assertTrue(GeoHexbin.bin(points(0.0 to 0.0), Double.NaN).isEmpty())
    }

    @Test
    fun `a larger radius produces fewer bins`() {
        val scattered = (0 until 100).map {
            ProjectedPoint((it * 7 % 97).toDouble(), (it * 13 % 89).toDouble())
        }
        val fine = GeoHexbin.bin(scattered, 5.0)
        val coarse = GeoHexbin.bin(scattered, 40.0)

        assertTrue("$fine vs $coarse", fine.size > coarse.size)
        // And the same records are accounted for either way.
        assertEquals(fine.sumOf { it.count }, coarse.sumOf { it.count })
    }

    @Test
    fun `every point is inside the hexagon it was binned into`() {
        // Binning and hit testing must agree, or a tap and a count would
        // disagree about which bin a record belongs to.
        val scattered = (0 until 120).map {
            ProjectedPoint((it * 7 % 97).toDouble(), (it * 13 % 89).toDouble())
        }
        val bins = GeoHexbin.bin(scattered, radius)
        bins.forEach { bin ->
            bin.indices.forEach { index ->
                val point = scattered[index]
                assertTrue(
                    "point $index is not inside its own bin",
                    HexGrid.contains(
                        bin.center.x.toFloat(), bin.center.y.toFloat(), radius.toFloat(),
                        point.x.toFloat(), point.y.toFloat(),
                    ),
                )
            }
        }
        assertNotNull(bins.firstOrNull())
    }
}
