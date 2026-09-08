package io.devkit.chartkit

import io.devkit.chartkit.geo.GeoGeometry
import io.devkit.chartkit.geo.GeoJson
import io.devkit.chartkit.geo.GeoJsonException
import io.devkit.chartkit.geo.GeoParsePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GeoJSON reader.
 *
 * Every fixture here is written out in full rather than loaded from a file:
 * these are the cases that decide whether a caller's boundary file draws or
 * silently vanishes, and a test that reads its input from disk hides which
 * byte was the one under test.
 */
class GeoJsonTest {

    private val square = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "id": "a",
              "properties": { "name": "Alpha", "code": "AL", "pop": 47 },
              "geometry": {
                "type": "Polygon",
                "coordinates": [[[0,0],[2,0],[2,2],[0,2],[0,0]]]
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun aFeatureCollectionParsesItsFeaturesAndProperties() {
        val collection = GeoJson.parse(square)

        assertEquals(1, collection.features.size)
        val feature = collection.features.first()
        assertEquals("a", feature.id)
        assertEquals("Alpha", feature.properties.string("name"))
        assertEquals("AL", feature.properties.string("code"))
        assertEquals(47.0, feature.properties.number("pop")!!, 1e-9)
    }

    @Test
    fun aNumericPropertyMatchesAsAStringWithoutItsDecimalPoint() {
        // The join is by string, and a FIPS code written as a JSON number must
        // match the string "47" rather than "47.0" — which is the single most
        // common reason a choropleth comes out blank.
        val collection = GeoJson.parse(square)

        assertEquals("47", collection.features.first().properties.string("pop"))
    }

    @Test
    fun theClosingPointOfARingIsDropped() {
        val collection = GeoJson.parse(square)
        val geometry = collection.features.first().geometry as GeoGeometry.Polygon

        // Five positions in, four vertices out: the repeated closing point is
        // GeoJSON's requirement, not a vertex, and keeping it would make every
        // area, centroid and hit test iterate one degenerate edge.
        assertEquals(4, geometry.polygon.outer.points.size)
    }

    @Test
    fun aPolygonWithAHoleKeepsTheHole() {
        val text = """
            {
              "type": "FeatureCollection",
              "features": [{
                "type": "Feature",
                "properties": {},
                "geometry": {
                  "type": "Polygon",
                  "coordinates": [
                    [[0,0],[10,0],[10,10],[0,10],[0,0]],
                    [[4,4],[6,4],[6,6],[4,6],[4,4]]
                  ]
                }
              }]
            }
        """.trimIndent()

        val geometry = GeoJson.parse(text).features.first().geometry as GeoGeometry.Polygon

        assertEquals(1, geometry.polygon.holes.size)
        assertEquals(4, geometry.polygon.holes.first().points.size)
    }

    @Test
    fun aMultiPolygonKeepsEveryComponent() {
        val text = """
            {
              "type": "FeatureCollection",
              "features": [{
                "type": "Feature",
                "properties": { "name": "Islands" },
                "geometry": {
                  "type": "MultiPolygon",
                  "coordinates": [
                    [[[0,0],[1,0],[1,1],[0,0]]],
                    [[[5,5],[7,5],[7,7],[5,5]]]
                  ]
                }
              }]
            }
        """.trimIndent()

        val geometry = GeoJson.parse(text).features.first().geometry as GeoGeometry.MultiPolygon

        assertEquals(2, geometry.polygons.size)
    }

    @Test
    fun pointAndMultiPointParse() {
        val text = """
            {
              "type": "FeatureCollection",
              "features": [
                { "type": "Feature", "properties": {},
                  "geometry": { "type": "Point", "coordinates": [3, 4] } },
                { "type": "Feature", "properties": {},
                  "geometry": { "type": "MultiPoint", "coordinates": [[1,1],[2,2]] } }
              ]
            }
        """.trimIndent()

        val features = GeoJson.parse(text).features

        val point = features[0].geometry as GeoGeometry.Point
        assertEquals(3.0, point.coordinate.longitude, 1e-9)
        assertEquals(4.0, point.coordinate.latitude, 1e-9)
        assertEquals(2, (features[1].geometry as GeoGeometry.MultiPoint).points.size)
    }

    @Test
    fun aBareFeatureIsAcceptedAsACollectionOfOne() {
        val text = """
            { "type": "Feature", "properties": { "name": "Solo" },
              "geometry": { "type": "Polygon",
                "coordinates": [[[0,0],[1,0],[1,1],[0,0]]] } }
        """.trimIndent()

        val collection = GeoJson.parse(text)

        assertEquals(1, collection.features.size)
        assertEquals("Solo", collection.features.first().properties.string("name"))
    }

    @Test
    fun aBareGeometryIsAcceptedAsAFeatureWithNoProperties() {
        val text = """
            { "type": "Polygon", "coordinates": [[[0,0],[1,0],[1,1],[0,0]]] }
        """.trimIndent()

        val collection = GeoJson.parse(text)

        assertEquals(1, collection.features.size)
        assertNull(collection.features.first().properties.string("name"))
    }

    @Test
    fun anUnsupportedGeometryIsSkippedAndReported() {
        val text = """
            {
              "type": "FeatureCollection",
              "features": [
                { "type": "Feature", "id": "line", "properties": {},
                  "geometry": { "type": "LineString", "coordinates": [[0,0],[1,1]] } },
                { "type": "Feature", "id": "ok", "properties": {},
                  "geometry": { "type": "Polygon",
                    "coordinates": [[[0,0],[1,0],[1,1],[0,0]]] } }
              ]
            }
        """.trimIndent()

        val collection = GeoJson.parse(text)

        // The usable feature survives. A file with one road in it is still a
        // usable set of regions, and refusing the whole file would be a worse
        // answer than drawing what it does contain and saying what it skipped.
        assertEquals(1, collection.features.size)
        assertEquals("ok", collection.features.first().id)
        assertEquals(1, collection.skipped.size)
        assertEquals("line", collection.skipped.first().identifier)
    }

    @Test
    fun rejectPolicyThrowsOnTheSameFile() {
        val text = """
            { "type": "FeatureCollection", "features": [
              { "type": "Feature", "properties": {},
                "geometry": { "type": "LineString", "coordinates": [[0,0],[1,1]] } }
            ]}
        """.trimIndent()

        val failure = runCatching { GeoJson.parse(text, GeoParsePolicy.Reject) }.exceptionOrNull()

        assertTrue(failure is GeoJsonException)
    }

    @Test
    fun malformedJsonThrowsRatherThanReturningNothing() {
        val failure = runCatching { GeoJson.parse("{ \"type\": ") }.exceptionOrNull()

        // A truncated file and an empty file are different problems, and a
        // parser that returned an empty collection for both would leave a
        // caller staring at a blank map with nothing to go on.
        assertTrue(failure is GeoJsonException)
    }

    @Test
    fun escapesAndUnicodeSurviveTheReader() {
        val text = """
            { "type": "FeatureCollection", "features": [
              { "type": "Feature",
                "properties": { "name": "Côte d'Ivoire", "note": "a\tb\\c\"d" },
                "geometry": { "type": "Polygon",
                  "coordinates": [[[0,0],[1,0],[1,1],[0,0]]] } }
            ]}
        """.trimIndent()

        val properties = GeoJson.parse(text).features.first().properties

        assertEquals("Côte d'Ivoire", properties.string("name"))
        assertEquals("a\tb\\c\"d", properties.string("note"))
    }

    @Test
    fun aRingWithTooFewPositionsIsNotAPolygon() {
        val text = """
            { "type": "FeatureCollection", "features": [
              { "type": "Feature", "id": "sliver", "properties": {},
                "geometry": { "type": "Polygon", "coordinates": [[[0,0],[1,1],[0,0]]] } }
            ]}
        """.trimIndent()

        val collection = GeoJson.parse(text)

        // Two distinct vertices enclose no area. Drawing it would produce an
        // invisible region that still answers hit tests.
        assertTrue(collection.features.isEmpty())
        assertEquals(1, collection.skipped.size)
    }

    @Test
    fun boundsCoverEveryFeature() {
        val text = """
            { "type": "FeatureCollection", "features": [
              { "type": "Feature", "properties": {},
                "geometry": { "type": "Polygon",
                  "coordinates": [[[-5,-5],[0,-5],[0,0],[-5,0],[-5,-5]]] } },
              { "type": "Feature", "properties": {},
                "geometry": { "type": "Polygon",
                  "coordinates": [[[1,1],[4,1],[4,3],[1,3],[1,1]]] } }
            ]}
        """.trimIndent()

        val features = GeoJson.parse(text).features
        val first = features[0].bounds
        assertNotNull(first)

        assertEquals(-5.0, first!!.minLongitude, 1e-9)
        assertEquals(0.0, first.maxLatitude, 1e-9)
        assertEquals(4.0, features[1].bounds!!.maxLongitude, 1e-9)
    }

    @Test
    fun anEmptyCollectionParsesToNothingRatherThanFailing() {
        val collection = GeoJson.parse("""{ "type": "FeatureCollection", "features": [] }""")

        assertTrue(collection.features.isEmpty())
        assertTrue(collection.skipped.isEmpty())
    }
}
