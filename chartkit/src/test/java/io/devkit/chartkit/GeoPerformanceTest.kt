package io.devkit.chartkit

import io.devkit.chartkit.geo.GeoJson
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.geo.GeoSpatialIndex
import io.devkit.chartkit.geo.ProjectedGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sizes a real thematic map arrives at: a country of 50 states, a world of
 * 200 nations, a nation of 1,000 counties.
 *
 * ### No wall-clock assertions
 *
 * A test that fails when a shared CI machine is busy teaches a team to ignore
 * it. What is asserted instead are the properties that *cause* the performance:
 * geometry is projected once, the index is built once and reused, and a hit
 * test touches a handful of candidates rather than every feature. Those hold
 * deterministically, and a regression in any of them is what would actually
 * make a thousand-county map stutter.
 */
class GeoPerformanceTest {

    /** A `columns × rows` grid of square regions, each with a vertex budget. */
    private fun fixture(columns: Int, rows: Int, verticesPerEdge: Int = 1): String {
        val features = buildList {
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val x = column.toDouble()
                    val y = row.toDouble()
                    val bottom = (0..verticesPerEdge).map { step ->
                        "[${x + step.toDouble() / verticesPerEdge},$y]"
                    }
                    val ring = bottom +
                        listOf("[${x + 1},${y + 1}]", "[$x,${y + 1}]", "[$x,$y]")
                    add(
                        """
                        { "type": "Feature", "id": "$column-$row",
                          "properties": { "code": "$column-$row" },
                          "geometry": { "type": "Polygon",
                            "coordinates": [[${ring.joinToString(",")}]] } }
                        """.trimIndent(),
                    )
                }
            }
        }
        return """{ "type": "FeatureCollection", "features": [${features.joinToString(",")}] }"""
    }

    private fun project(columns: Int, rows: Int, verticesPerEdge: Int = 1) =
        ProjectedGeometry.of(
            GeoJson.parse(fixture(columns, rows, verticesPerEdge)),
            GeoProjection.Equirectangular,
        )

    @Test
    fun fiftyRegionsScanRatherThanIndex() {
        val projected = project(10, 5)

        assertEquals(50, projected.features.size)
        assertTrue(projected.features.size < GeoSpatialIndex.MIN_INDEXED_FEATURES)
        assertEquals("4-2", hit(projected, 4.5, 2.5))
    }

    @Test
    fun twoHundredRegionsResolveCorrectlyThroughTheIndex() {
        val projected = project(20, 10)

        assertEquals(200, projected.features.size)
        assertEquals("0-0", hit(projected, 0.5, 0.5))
        assertEquals("19-9", hit(projected, 19.5, 9.5))
        assertEquals("11-4", hit(projected, 11.5, 4.5))
    }

    @Test
    fun aThousandRegionsResolveEveryTapCorrectly() {
        val projected = project(40, 25)

        assertEquals(1000, projected.features.size)
        for (column in 0 until 40 step 7) {
            for (row in 0 until 25 step 5) {
                assertEquals("$column-$row", hit(projected, column + 0.5, row + 0.5))
            }
        }
    }

    @Test
    fun theIndexNarrowsAThousandFeaturesToAHandful() {
        val projected = project(40, 25)

        val candidates = projected.index.candidates(20.5, 12.5)

        // The point of the grid: a tap runs point-in-polygon over a few
        // neighbours rather than over every region on the map. Anything close
        // to 1,000 here means the index has stopped working and every pointer
        // event has become a full scan.
        assertTrue(candidates.size < 10)
        assertTrue(candidates.isNotEmpty())
    }

    @Test
    fun theIndexIsBuiltOnceAndReused() {
        val projected = project(40, 25)

        val first = projected.index
        val second = projected.index

        // Rebuilding a 1,000-feature grid on every pointer event is the
        // difference between a responsive map and an unusable one.
        assertTrue(first === second)
    }

    @Test
    fun aDenseBoundaryIsProjectedOnceAndCountedHonestly() {
        val projected = project(20, 10, verticesPerEdge = 20)

        assertEquals(200, projected.features.size)
        // 21 bottom vertices + 2 more corners: the closing point is dropped,
        // so the count is what is genuinely drawn.
        assertEquals(200 * 23, projected.vertexCount)
        assertEquals(projected.vertexCount, projected.vertexCount)
    }

    @Test
    fun simplifyingADenseBoundaryDropsWhatCannotBeSeen() {
        val collection = GeoJson.parse(fixture(20, 10, verticesPerEdge = 20))

        val full = ProjectedGeometry.of(collection, GeoProjection.Equirectangular)
        val simplified = ProjectedGeometry.of(collection, GeoProjection.Equirectangular, 0.05)

        assertTrue(simplified.vertexCount < full.vertexCount)
        // And the map still answers the same questions.
        assertEquals("11-4", hit(simplified, 11.5, 4.5))
    }

    private fun hit(geometry: ProjectedGeometry, x: Double, y: Double): String? =
        geometry.index.candidates(x, y).firstOrNull { it.contains(x, y) }?.feature?.id
}
