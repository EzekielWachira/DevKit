package io.devkit.chartkit

import io.devkit.chartkit.geo.GeoGeometryMath
import io.devkit.chartkit.geo.GeoJson
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.geo.GeoSpatialIndex
import io.devkit.chartkit.geo.ProjectedGeometry
import io.devkit.chartkit.geo.ProjectedPoint
import io.devkit.chartkit.geo.ProjectedPolygon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Point-in-polygon, centroids, simplification and the spatial index.
 *
 * All in projected space and all pure, which is why they are verified here
 * rather than through a rendered chart: a hit test that is wrong is wrong
 * silently, and the only way to see it on a device is to tap the wrong county.
 */
class GeoGeometryTest {

    private fun ring(vararg points: Pair<Double, Double>): List<ProjectedPoint> =
        points.map { ProjectedPoint(it.first, it.second) }

    private val unitSquare = ring(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0)

    @Test
    fun aPointInsideARingIsInside() {
        assertTrue(GeoGeometryMath.ringContains(unitSquare, 5.0, 5.0))
    }

    @Test
    fun aPointOutsideARingIsOutside() {
        assertFalse(GeoGeometryMath.ringContains(unitSquare, 15.0, 5.0))
        assertFalse(GeoGeometryMath.ringContains(unitSquare, 5.0, -1.0))
    }

    @Test
    fun aConcaveRingDoesNotClaimItsNotch() {
        // A U shape. A bounding-box test would say the notch is inside; ray
        // casting says it is not, which is the entire reason for the ray cast.
        val u = ring(
            0.0 to 0.0,
            10.0 to 0.0,
            10.0 to 10.0,
            7.0 to 10.0,
            7.0 to 3.0,
            3.0 to 3.0,
            3.0 to 10.0,
            0.0 to 10.0,
        )

        assertTrue(GeoGeometryMath.ringContains(u, 1.0, 8.0))
        assertFalse(GeoGeometryMath.ringContains(u, 5.0, 8.0))
    }

    @Test
    fun aSharedBorderBelongsToExactlyOneRegion() {
        val left = ring(0.0 to 0.0, 5.0 to 0.0, 5.0 to 10.0, 0.0 to 10.0)
        val right = ring(5.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 5.0 to 10.0)

        val inLeft = GeoGeometryMath.ringContains(left, 5.0, 5.0)
        val inRight = GeoGeometryMath.ringContains(right, 5.0, 5.0)

        // Both claiming it would make selection flicker along every boundary;
        // neither claiming it would leave a dead pixel column between counties.
        assertTrue(inLeft != inRight)
    }

    @Test
    fun aHoleIsNotPartOfItsPolygon() {
        val polygon = ProjectedPolygon(
            outer = unitSquare,
            holes = listOf(ring(4.0 to 4.0, 6.0 to 4.0, 6.0 to 6.0, 4.0 to 6.0)),
        )

        assertTrue(GeoGeometryMath.polygonContains(polygon, 2.0, 2.0))
        // An enclave: the point is inside South Africa's outer ring and inside
        // Lesotho, and South Africa must not be selected for it.
        assertFalse(GeoGeometryMath.polygonContains(polygon, 5.0, 5.0))
    }

    @Test
    fun theCentroidOfASquareIsItsCentre() {
        val centroid = GeoGeometryMath.centroid(unitSquare)

        assertNotNull(centroid)
        assertEquals(5.0, centroid!!.x, 1e-9)
        assertEquals(5.0, centroid.y, 1e-9)
    }

    @Test
    fun theCentroidIgnoresVertexDensity() {
        // The right edge subdivided twenty times. A vertex mean would be
        // dragged towards it; the area centroid is not.
        val ordered = ring(0.0 to 0.0, 10.0 to 0.0) +
            (1..19).map { ProjectedPoint(10.0, it * 0.5) } +
            ring(10.0 to 10.0, 0.0 to 10.0)

        val centroid = GeoGeometryMath.centroid(ordered)

        assertNotNull(centroid)
        assertTrue(abs(centroid!!.x - 5.0) < 1e-6)
    }

    @Test
    fun aLabelGoesInTheLargestComponentOfAMultiPolygon() {
        val mainland = ProjectedPolygon(unitSquare, emptyList())
        val island = ProjectedPolygon(
            ring(100.0 to 100.0, 101.0 to 100.0, 101.0 to 101.0, 100.0 to 101.0),
            emptyList(),
        )

        val label = GeoGeometryMath.labelPoint(listOf(island, mainland))

        // Not the mean of both, which would put the country's name in the
        // ocean between them.
        assertNotNull(label)
        assertEquals(5.0, label!!.x, 1e-6)
    }

    @Test
    fun simplificationDropsCollinearVerticesAndKeepsCorners() {
        val line = ring(
            0.0 to 0.0,
            1.0 to 0.0,
            2.0 to 0.0,
            3.0 to 0.0,
            3.0 to 3.0,
        )

        val simplified = GeoGeometryMath.simplify(line, tolerance = 0.5)

        assertEquals(3, simplified.size)
        assertEquals(0.0, simplified.first().x, 1e-9)
        assertEquals(3.0, simplified.last().y, 1e-9)
    }

    @Test
    fun simplificationKeepsAtLeastADrawableRing() {
        val tiny = ring(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0)

        // A tolerance larger than the shape would otherwise reduce it to two
        // points, which encloses nothing and cannot be drawn.
        val simplified = GeoGeometryMath.simplify(tiny, tolerance = 1000.0)

        assertEquals(3, simplified.size)
    }

    @Test
    fun simplificationOfAHugeRingDoesNotRecurseIntoTheStack() {
        // Thirty thousand vertices on a sawtooth: the recursive form of
        // Douglas–Peucker overflows here, the iterative one does not.
        val many = (0 until 30_000).map { ProjectedPoint(it.toDouble(), (it % 2).toDouble()) }

        val simplified = GeoGeometryMath.simplify(many, tolerance = 0.001)

        assertTrue(simplified.isNotEmpty())
    }

    // Projected geometry and the index ------------------------------------

    private fun grid(columns: Int, rows: Int): String {
        val features = buildList {
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val x = column.toDouble()
                    val y = row.toDouble()
                    add(
                        """
                        { "type": "Feature", "id": "c$column-r$row",
                          "properties": { "code": "$column:$row" },
                          "geometry": { "type": "Polygon", "coordinates":
                            [[[$x,$y],[${x + 1},$y],[${x + 1},${y + 1}],[$x,${y + 1}],[$x,$y]]] } }
                        """.trimIndent(),
                    )
                }
            }
        }
        return """{ "type": "FeatureCollection", "features": [${features.joinToString(",")}] }"""
    }

    @Test
    fun projectedGeometryCoversEveryFeature() {
        val collection = GeoJson.parse(grid(4, 3))

        val projected = ProjectedGeometry.of(collection, GeoProjection.Equirectangular)

        assertEquals(12, projected.features.size)
        assertEquals(0.0, projected.bounds.minX, 1e-9)
        assertEquals(4.0, projected.bounds.maxX, 1e-9)
        assertEquals(3.0, projected.bounds.maxY, 1e-9)
    }

    @Test
    fun aSmallCollectionScansRatherThanIndexing() {
        val projected = ProjectedGeometry.of(GeoJson.parse(grid(4, 3)), GeoProjection.Equirectangular)

        // Twelve features, well under the threshold: the grid would cost more
        // to build than the scan it replaces.
        assertTrue(projected.features.size < GeoSpatialIndex.MIN_INDEXED_FEATURES)
        val hit = projected.index.candidates(2.5, 1.5).firstOrNull { it.contains(2.5, 1.5) }
        assertEquals("c2-r1", hit?.feature?.id)
    }

    @Test
    fun aLargeCollectionIndexesAndStillFindsTheRightRegion() {
        val projected = ProjectedGeometry.of(
            GeoJson.parse(grid(20, 20)),
            GeoProjection.Equirectangular,
        )

        assertEquals(400, projected.features.size)
        val hit = projected.index.candidates(13.5, 7.5).firstOrNull { it.contains(13.5, 7.5) }
        assertEquals("c13-r7", hit?.feature?.id)
    }

    @Test
    fun theIndexAgreesWithABruteForceScanEverywhere() {
        val projected = ProjectedGeometry.of(
            GeoJson.parse(grid(10, 10)),
            GeoProjection.Equirectangular,
        )

        for (column in 0 until 10) {
            for (row in 0 until 10) {
                val x = column + 0.5
                val y = row + 0.5
                val indexed = projected.index.candidates(x, y).firstOrNull { it.contains(x, y) }
                val scanned = projected.features.firstOrNull { it.contains(x, y) }
                assertEquals(scanned?.feature?.id, indexed?.feature?.id)
            }
        }
    }

    @Test
    fun aTapOutsideEveryRegionHitsNothing() {
        val projected = ProjectedGeometry.of(GeoJson.parse(grid(4, 3)), GeoProjection.Equirectangular)

        assertTrue(projected.index.candidates(50.0, 50.0).none { it.contains(50.0, 50.0) })
    }

    @Test
    fun aMultiPolygonFeatureIsSelectedFromEitherComponent() {
        val text = """
            { "type": "FeatureCollection", "features": [{
              "type": "Feature", "id": "archipelago", "properties": {},
              "geometry": { "type": "MultiPolygon", "coordinates": [
                [[[0,0],[2,0],[2,2],[0,2],[0,0]]],
                [[[10,10],[12,10],[12,12],[10,12],[10,10]]]
              ]}
            }]}
        """.trimIndent()

        val projected = ProjectedGeometry.of(GeoJson.parse(text), GeoProjection.Equirectangular)
        val feature = projected.features.single()

        // Tapping an island selects the country, because the country is the
        // feature and the island is one of its components.
        assertTrue(feature.contains(1.0, 1.0))
        assertTrue(feature.contains(11.0, 11.0))
        assertFalse(feature.contains(6.0, 6.0))
    }

    @Test
    fun simplificationReducesVerticesButKeepsTheFeature() {
        val text = """
            { "type": "FeatureCollection", "features": [{
              "type": "Feature", "properties": {},
              "geometry": { "type": "Polygon", "coordinates": [[
                [0,0],[1,0.001],[2,0],[3,0.001],[4,0],[4,4],[0,4],[0,0]
              ]]}
            }]}
        """.trimIndent()

        val full = ProjectedGeometry.of(GeoJson.parse(text), GeoProjection.Equirectangular)
        val simplified = ProjectedGeometry.of(
            GeoJson.parse(text),
            GeoProjection.Equirectangular,
            simplification = 0.1,
        )

        assertTrue(simplified.vertexCount < full.vertexCount)
        assertTrue(simplified.features.single().contains(2.0, 2.0))
    }
}
