package io.devkit.chartkit

import io.devkit.chartkit.geo.GeoCoordinate
import io.devkit.chartkit.geo.GeoGeometry
import io.devkit.chartkit.geo.GeoJson
import io.devkit.chartkit.geo.GeoParsePolicy
import io.devkit.chartkit.geo.TopoJson
import io.devkit.chartkit.geo.TopoJsonException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TopoJSON decoder.
 *
 * ### The fixture
 *
 * Two unit squares sharing their middle edge — the smallest arrangement that
 * exercises what TopoJSON exists for:
 *
 * ```text
 * (0,1)───(1,1)───(2,1)
 *   │   A   │   B   │
 * (0,0)───(1,0)───(2,0)
 * ```
 *
 * The shared edge is stored **once**, as arc 0. `A` walks it forwards and `B`
 * walks it backwards, through the negative index that is the whole reason
 * TopoJSON can halve a boundary file. Quantised with a scale of `0.5`, so every
 * stored integer is twice the coordinate it means and the transform is
 * demonstrably being applied rather than accidentally being the identity.
 */
class TopoJsonTest {

    /**
     * ```text
     * arc 0  (1,0) → (1,1)              the shared edge
     * arc 1  (1,1) → (0,1) → (0,0) → (1,0)   A's outside
     * arc 2  (1,0) → (2,0) → (2,1) → (1,1)   B's outside
     * ```
     */
    private val quantised = """
        {
          "type": "Topology",
          "transform": { "scale": [0.5, 0.5], "translate": [0, 0] },
          "objects": {
            "regions": {
              "type": "GeometryCollection",
              "geometries": [
                { "type": "Polygon", "id": "A", "arcs": [[0, 1]],
                  "properties": { "name": "Alpha", "code": 7 } },
                { "type": "Polygon", "id": "B", "arcs": [[-1, 2]],
                  "properties": { "name": "Bravo" } }
              ]
            }
          },
          "arcs": [
            [[2, 0], [0, 2]],
            [[2, 2], [-2, 0], [0, -2], [2, 0]],
            [[2, 0], [2, 0], [0, 2], [-2, 0]]
          ]
        }
    """.trimIndent()

    private fun ringOf(geometry: GeoGeometry): List<GeoCoordinate> =
        (geometry as GeoGeometry.Polygon).polygon.outer.points

    @Test
    fun aQuantisedTopologyDecodesToRealCoordinates() {
        val collection = TopoJson.parse(quantised, objectName = "regions")

        assertEquals(2, collection.features.size)
        // Every stored integer was doubled. A decoder that forgot the transform
        // would produce a map twice the size in the right shape, which looks
        // fine until it is put beside anything else.
        assertEquals(
            listOf(
                GeoCoordinate(1.0, 0.0),
                GeoCoordinate(1.0, 1.0),
                GeoCoordinate(0.0, 1.0),
                GeoCoordinate(0.0, 0.0),
            ),
            ringOf(collection.features[0].geometry),
        )
    }

    @Test
    fun deltasAccumulateRatherThanStandingAlone() {
        // Arc 1's stored deltas are [2,2], [-2,0], [0,-2], [2,0]. Read as
        // absolute positions those would be (1,1), (-1,0), (0,-1), (1,0) — a
        // shape that is not a square and not anywhere near one.
        val ring = ringOf(TopoJson.parse(quantised, "regions").features[0].geometry)

        assertTrue("no vertex may be negative", ring.all { it.longitude >= 0.0 && it.latitude >= 0.0 })
        assertEquals(1.0, ring.maxOf { it.longitude }, 1e-12)
        assertEquals(1.0, ring.maxOf { it.latitude }, 1e-12)
    }

    @Test
    fun aNegativeArcReferenceWalksTheSharedEdgeBackwards() {
        val collection = TopoJson.parse(quantised, "regions")
        val b = ringOf(collection.features[1].geometry)

        // `-1` is `~0`: arc 0 reversed. Forwards it runs (1,0) → (1,1); B needs
        // it the other way round, and a decoder that ignored the sign would
        // produce a self-crossing bowtie rather than a square.
        assertEquals(GeoCoordinate(1.0, 1.0), b.first())
        assertEquals(GeoCoordinate(1.0, 0.0), b[1])
        assertEquals(4, b.size)
        assertEquals(2.0, b.maxOf { it.longitude }, 1e-12)
        assertEquals(1.0, b.minOf { it.longitude }, 1e-12)
    }

    @Test
    fun theSharedEdgeIsIdenticalInBothRegions() {
        val collection = TopoJson.parse(quantised, "regions")
        val a = ringOf(collection.features[0].geometry)
        val b = ringOf(collection.features[1].geometry)

        // Not "within a rounding error of each other" — the same values, because
        // they came from the same arc. That is the property that stops two
        // neighbours disagreeing about where their border is, and it is not
        // something a GeoJSON file can promise.
        val shared = listOf(GeoCoordinate(1.0, 0.0), GeoCoordinate(1.0, 1.0))
        assertTrue(a.containsAll(shared))
        assertTrue(b.containsAll(shared))
    }

    @Test
    fun theSharedArcIsDecodedOnceForTheWholeTopology() {
        val topology = TopoJson.topology(quantised)

        // Three arcs for two regions of four sides each: the point of the
        // format. A decoder that expanded every reference would still draw
        // correctly and would have thrown away the reason to use TopoJSON.
        assertEquals(3, topology.arcCount)
        assertEquals(listOf("regions"), topology.objectNames)
    }

    @Test
    fun oneTopologyServesSeveralObjectsFromTheSameArcs() {
        val topology = TopoJson.topology(quantised)

        val first = topology.feature("regions")
        val second = topology.feature("regions")

        // Asking twice re-stitches, but does not re-decode: the arcs are held
        // by the topology. Both answers are equal, which is the observable part.
        assertEquals(
            ringOf(first.features[0].geometry),
            ringOf(second.features[0].geometry),
        )
    }

    @Test
    fun propertiesAndIdsSurvive() {
        val collection = TopoJson.parse(quantised, "regions")

        assertEquals("A", collection.features[0].id)
        assertEquals("Alpha", collection.features[0].properties.string("name"))
        // The same numeric-property rule GeoJSON follows: `7`, not `7.0`,
        // because the join key on the other side is a string.
        assertEquals("7", collection.features[0].properties.string("code"))
    }

    @Test
    fun anUnquantisedTopologyIsReadAsPlainDegrees() {
        val text = """
            {
              "type": "Topology",
              "objects": { "land": { "type": "Polygon", "arcs": [[0]] } },
              "arcs": [ [[10, 20], [11, 20], [11, 21], [10, 20]] ]
            }
        """.trimIndent()

        val collection = TopoJson.parse(text)
        val ring = ringOf(collection.features.single().geometry)

        // No transform member, so positions are absolute and must not be
        // treated as deltas — which would have made the third vertex (32, 61).
        assertEquals(GeoCoordinate(10.0, 20.0), ring[0])
        assertEquals(GeoCoordinate(11.0, 20.0), ring[1])
        assertEquals(GeoCoordinate(11.0, 21.0), ring[2])
    }

    @Test
    fun aSingleShapeObjectIsReadAsOneFeature() {
        val text = """
            {
              "type": "Topology",
              "objects": { "land": { "type": "Polygon", "id": "L", "arcs": [[0]] } },
              "arcs": [ [[0, 0], [1, 0], [1, 1], [0, 0]] ]
            }
        """.trimIndent()

        // The top-level object is normally a GeometryCollection, but a bare
        // geometry is legal and common for a single landmass.
        assertEquals("L", TopoJson.parse(text).features.single().id)
    }

    @Test
    fun lineStringsAndPointsDecodeAlongsideAreas() {
        val text = """
            {
              "type": "Topology",
              "transform": { "scale": [2, 2], "translate": [1, 1] },
              "objects": {
                "mixed": {
                  "type": "GeometryCollection",
                  "geometries": [
                    { "type": "LineString", "id": "river", "arcs": [0] },
                    { "type": "Point", "id": "city", "coordinates": [3, 4] },
                    { "type": "MultiLineString", "id": "rail", "arcs": [[0], [0]] }
                  ]
                }
              },
              "arcs": [ [[0, 0], [1, 1], [1, 1]] ]
            }
        """.trimIndent()

        val collection = TopoJson.parse(text, "mixed")
        val river = collection.features[0].geometry as GeoGeometry.LineString
        // Deltas 0,0 → 1,1 → 2,2 quantised; × 2 + 1 gives (1,1), (3,3), (5,5).
        assertEquals(
            listOf(GeoCoordinate(1.0, 1.0), GeoCoordinate(3.0, 3.0), GeoCoordinate(5.0, 5.0)),
            river.line.points,
        )

        // A point is quantised but **not** delta-encoded: each position stands
        // alone, so (3,4) becomes (7,9) rather than accumulating with anything.
        val city = collection.features[1].geometry as GeoGeometry.Point
        assertEquals(GeoCoordinate(7.0, 9.0), city.coordinate)

        val rail = collection.features[2].geometry as GeoGeometry.MultiLineString
        assertEquals(2, rail.lines.size)
    }

    @Test
    fun aMultiPolygonKeepsEveryComponentAndItsHoles() {
        val text = """
            {
              "type": "Topology",
              "objects": {
                "islands": {
                  "type": "GeometryCollection",
                  "geometries": [
                    { "type": "MultiPolygon", "id": "M", "arcs": [ [[0], [1]], [[2]] ] }
                  ]
                }
              },
              "arcs": [
                [[0, 0], [10, 0], [10, 10], [0, 10], [0, 0]],
                [[3, 3], [7, 3], [7, 7], [3, 7], [3, 3]],
                [[20, 0], [24, 0], [24, 4], [20, 0]]
              ]
            }
        """.trimIndent()

        val multi = TopoJson.parse(text, "islands").features.single().geometry
            as GeoGeometry.MultiPolygon

        assertEquals(2, multi.polygons.size)
        // The second ring of the first component is a hole, not another island.
        assertEquals(1, multi.polygons[0].holes.size)
        assertEquals(0, multi.polygons[1].holes.size)
    }

    @Test
    fun namingTheWrongObjectSaysWhatIsActuallyThere() {
        val failure = runCatching { TopoJson.parse(quantised, "counties") }.exceptionOrNull()

        assertTrue(failure is TopoJsonException)
        // The message names the objects the file *does* hold. A blank map
        // because the object name was `countries` and the file says `countries1`
        // is otherwise an afternoon of guessing.
        assertTrue(failure!!.message!!.contains("regions"))
    }

    @Test
    fun omittingTheNameOfAmbiguousObjectsIsRefused() {
        val text = """
            {
              "type": "Topology",
              "objects": {
                "land": { "type": "Polygon", "arcs": [[0]] },
                "lakes": { "type": "Polygon", "arcs": [[0]] }
              },
              "arcs": [ [[0, 0], [1, 0], [1, 1], [0, 0]] ]
            }
        """.trimIndent()

        val failure = runCatching { TopoJson.parse(text) }.exceptionOrNull()

        // Guessing between `land` and `lakes` would silently draw the wrong map,
        // which is worse than refusing.
        assertTrue(failure is TopoJsonException)
    }

    @Test
    fun somethingThatIsNotATopologyIsRefusedByName() {
        val geoJson = """{ "type": "FeatureCollection", "features": [] }"""

        val failure = runCatching { TopoJson.parse(geoJson) }.exceptionOrNull()

        assertTrue(failure is TopoJsonException)
        assertTrue(failure!!.message!!.contains("FeatureCollection"))
    }

    @Test
    fun anUnknownGeometryIsSkippedAndReported() {
        val text = """
            {
              "type": "Topology",
              "objects": { "o": { "type": "GeometryCollection", "geometries": [
                { "type": "Circle", "id": "blob", "arcs": [[0]] },
                { "type": "Polygon", "id": "ok", "arcs": [[0]] }
              ] } },
              "arcs": [ [[0, 0], [1, 0], [1, 1], [0, 0]] ]
            }
        """.trimIndent()

        val collection = TopoJson.parse(text, "o")

        assertEquals(1, collection.features.size)
        assertEquals("blob", collection.skipped.single().identifier)

        val strict = runCatching { TopoJson.parse(text, "o", GeoParsePolicy.Reject) }
        assertTrue(strict.exceptionOrNull() is TopoJsonException)
    }

    /**
     * §150: the same geography, in both formats, must normalise identically.
     *
     * This is the property that lets everything downstream — projection, fit,
     * layers, hit test, labels — be unaware of which format was loaded.
     */
    @Test
    fun theSameGeographyReadsIdenticallyFromGeoJsonAndTopoJson() {
        val geoJson = """
            { "type": "FeatureCollection", "features": [
              { "type": "Feature", "id": "A", "properties": { "name": "Alpha" },
                "geometry": { "type": "Polygon",
                  "coordinates": [[[1,0],[1,1],[0,1],[0,0],[1,0]]] } },
              { "type": "Feature", "id": "B", "properties": { "name": "Bravo" },
                "geometry": { "type": "Polygon",
                  "coordinates": [[[1,1],[1,0],[2,0],[2,1],[1,1]]] } }
            ]}
        """.trimIndent()

        val fromGeoJson = GeoJson.parse(geoJson)
        val fromTopoJson = TopoJson.parse(quantised, "regions")

        assertEquals(fromGeoJson.features.size, fromTopoJson.features.size)
        fromGeoJson.features.zip(fromTopoJson.features).forEach { (a, b) ->
            assertEquals(a.id, b.id)
            assertEquals(a.properties.string("name"), b.properties.string("name"))
            assertEquals(ringOf(a.geometry), ringOf(b.geometry))
            assertNotNull(a.bounds)
            assertEquals(a.bounds, b.bounds)
        }
    }
}
