package io.devkit.chartkit

import io.devkit.chartkit.coordinate.GeoCoordinates
import io.devkit.chartkit.geo.AntimeridianProcessor
import io.devkit.chartkit.geo.GeoCoordinate
import io.devkit.chartkit.geo.GeoFeature
import io.devkit.chartkit.geo.GeoFeatureCollection
import io.devkit.chartkit.geo.GeoGeometry
import io.devkit.chartkit.geo.GeoJson
import io.devkit.chartkit.geo.GeoLine
import io.devkit.chartkit.geo.GeoPolygon
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.geo.GeoProperties
import io.devkit.chartkit.geo.GeoRing
import io.devkit.chartkit.geo.ProjectedGeometry
import io.devkit.chartkit.geo.toBounds
import io.devkit.chartkit.geometry.ChartRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Geometry that crosses ±180°.
 *
 * The defect these exist to prevent is the most recognisable bug in mapping:
 * two vertices of Fiji half a degree apart on the ground, and the entire width
 * of the map apart in `x`, drawn as a stripe straight across the Pacific,
 * through Africa, and back.
 *
 * ```text
 * ┌─────────────────────────────────┐
 * │▓                               ▓│   correct: two pieces, one at each edge
 * └─────────────────────────────────┘
 * ┌─────────────────────────────────┐
 * │▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓│   the bug
 * └─────────────────────────────────┘
 * ```
 */
class AntimeridianTest {

    /** A square straddling the antimeridian: 175°E to 175°W. */
    private val straddlingRing = listOf(
        GeoCoordinate(175.0, -5.0),
        GeoCoordinate(-175.0, -5.0),
        GeoCoordinate(-175.0, 5.0),
        GeoCoordinate(175.0, 5.0),
    )

    private fun feature(geometry: GeoGeometry, id: String = "f") =
        GeoFeature(id, GeoProperties.Empty, geometry)

    /** The widest longitude gap between neighbouring vertices of a closed ring. */
    private fun widestClosedGap(points: List<GeoCoordinate>): Double {
        var widest = 0.0
        for (index in points.indices) {
            val next = points[(index + 1) % points.size]
            widest = maxOf(widest, abs(next.longitude - points[index].longitude))
        }
        return widest
    }

    @Test
    fun aRingCrossingTheAntimeridianIsSplitIntoTwoPieces() {
        val pieces = AntimeridianProcessor.splitRing(straddlingRing)

        assertEquals(2, pieces.size)
        // One piece against each edge of the map.
        val spans = pieces.map { piece ->
            piece.minOf { it.longitude } to piece.maxOf { it.longitude }
        }
        assertTrue(
            "expected a piece hugging +180 and one hugging -180, got $spans",
            spans.any { it.first >= 174.0 && it.second <= 180.0001 } &&
                spans.any { it.first >= -180.0001 && it.second <= -174.0 },
        )
    }

    @Test
    fun noPieceContainsASegmentSpanningTheWholeMap() {
        val pieces = AntimeridianProcessor.splitRing(straddlingRing)

        // The assertion that names the bug. Before splitting, the ring holds a
        // 350° step; after it, no neighbouring pair may be further apart than
        // the geometry genuinely is — five degrees, here.
        pieces.forEach { piece ->
            assertTrue(
                "a piece still spans ${widestClosedGap(piece)}° between neighbours",
                widestClosedGap(piece) <= 10.0,
            )
        }
        // And the unrepaired ring really does hold the 350° step this is about.
        assertEquals(350.0, widestClosedGap(straddlingRing), 1e-9)
    }

    @Test
    fun everyVertexStaysInsideTheGlobe() {
        AntimeridianProcessor.splitRing(straddlingRing).flatten().forEach { point ->
            assertTrue(
                "${point.longitude} is outside [-180, 180]",
                point.longitude >= -180.0001 && point.longitude <= 180.0001,
            )
        }
    }

    @Test
    fun latitudeIsInterpolatedAtTheCut() {
        // A triangle whose crossing segment slopes, so the latitude at ±180 is
        // not simply one of the endpoints. RFC 7946 defines a segment as a
        // straight line in longitude/latitude space, so the answer is the
        // linear one and not a great-circle one.
        val sloping = listOf(
            GeoCoordinate(170.0, 0.0),
            GeoCoordinate(-170.0, 20.0),
            GeoCoordinate(170.0, 40.0),
        )
        val pieces = AntimeridianProcessor.splitRing(sloping)

        val onTheMeridian = pieces.flatten().filter { abs(abs(it.longitude) - 180.0) < 1e-9 }
        assertTrue("expected vertices on the cut", onTheMeridian.isNotEmpty())
        // Half way from 170 to 190 in the unwrapped frame is 180, at latitude 10.
        assertTrue(
            "expected a cut vertex at 10°, got ${onTheMeridian.map { it.latitude }}",
            onTheMeridian.any { abs(it.latitude - 10.0) < 1e-9 },
        )
    }

    @Test
    fun aRingThatDoesNotCrossIsReturnedUnchanged() {
        val ordinary = listOf(
            GeoCoordinate(10.0, 10.0),
            GeoCoordinate(20.0, 10.0),
            GeoCoordinate(20.0, 20.0),
        )

        val pieces = AntimeridianProcessor.splitRing(ordinary)

        assertEquals(1, pieces.size)
        // The same list, not an equal one: the ordinary case must allocate
        // nothing, because it is every ring of every map that is not Pacific.
        assertSame(ordinary, pieces.single())
    }

    @Test
    fun aCollectionWithNothingToRepairComesBackByIdentity() {
        val collection = GeoFeatureCollection(
            listOf(
                feature(
                    GeoGeometry.Polygon(
                        GeoPolygon(
                            GeoRing(
                                listOf(
                                    GeoCoordinate(0.0, 0.0),
                                    GeoCoordinate(1.0, 0.0),
                                    GeoCoordinate(1.0, 1.0),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertSame(collection, AntimeridianProcessor.process(collection))
    }

    @Test
    fun holesTravelWithThePieceTheyAreInside() {
        // A straddling square with a hole entirely on the eastern side of the
        // antimeridian. Assigning it to the wrong piece would punch a hole in
        // the wrong half of the country.
        val outer = GeoRing(straddlingRing)
        val hole = GeoRing(
            listOf(
                GeoCoordinate(176.0, -2.0),
                GeoCoordinate(178.0, -2.0),
                GeoCoordinate(178.0, 2.0),
                GeoCoordinate(176.0, 2.0),
            ),
        )

        val split = AntimeridianProcessor.splitPolygon(GeoPolygon(outer, listOf(hole)))

        assertEquals(2, split.size)
        val withHole = split.filter { it.holes.isNotEmpty() }
        assertEquals(1, withHole.size)
        // And it is the eastern piece.
        assertTrue(withHole.single().outer.points.all { it.longitude > 0.0 })
    }

    @Test
    fun aLineCrossingIsSplitAtTheMeridianAndMeetsBothEdges() {
        // Tokyo to Los Angeles, roughly. One route, two strokes.
        val flight = listOf(GeoCoordinate(139.7, 35.7), GeoCoordinate(-118.2, 34.1))

        val pieces = AntimeridianProcessor.splitLine(flight)

        assertEquals(2, pieces.size)
        // Each piece ends on the meridian, so the stroke reaches the edge of the
        // map rather than stopping short of it and leaving a visible gap.
        assertEquals(180.0, pieces[0].last().longitude, 1e-9)
        assertEquals(-180.0, pieces[1].first().longitude, 1e-9)
        // And the two cut vertices are the same place, at the same latitude.
        assertEquals(pieces[0].last().latitude, pieces[1].first().latitude, 1e-9)
    }

    @Test
    fun aLineThatDoesNotCrossIsLeftAlone() {
        val ordinary = listOf(GeoCoordinate(0.0, 0.0), GeoCoordinate(30.0, 10.0))

        assertSame(ordinary, AntimeridianProcessor.splitLine(ordinary).single())
    }

    @Test
    fun aLineStringGeometryBecomesAMultiLineStringWhenItSplits() {
        val geometry = GeoGeometry.LineString(
            GeoLine(listOf(GeoCoordinate(170.0, 0.0), GeoCoordinate(-170.0, 0.0))),
        )

        val processed = AntimeridianProcessor.process(geometry)

        assertTrue(processed is GeoGeometry.MultiLineString)
        assertEquals(2, (processed as GeoGeometry.MultiLineString).lines.size)
    }

    @Test
    fun pointGeometriesAreNeverTouched() {
        val point = GeoGeometry.Point(GeoCoordinate(179.9, 0.0))
        val multi = GeoGeometry.MultiPoint(
            listOf(GeoCoordinate(179.9, 0.0), GeoCoordinate(-179.9, 0.0)),
        )

        // A position cannot cross anything — there is no segment. Two marks
        // either side of the meridian are two marks, not a line.
        //
        // Compared by value rather than by identity because these are inline
        // value classes: asking `assertSame` of one boxes it twice and compares
        // the boxes, which says nothing about the geometry.
        assertEquals(point, AntimeridianProcessor.process(point))
        assertEquals(multi, AntimeridianProcessor.process(multi))
    }

    @Test
    fun aMultiPolygonSplitsEveryComponentThatNeedsIt() {
        val safe = GeoPolygon(
            GeoRing(
                listOf(
                    GeoCoordinate(0.0, 0.0),
                    GeoCoordinate(1.0, 0.0),
                    GeoCoordinate(1.0, 1.0),
                ),
            ),
        )
        val geometry = GeoGeometry.MultiPolygon(listOf(safe, GeoPolygon(GeoRing(straddlingRing))))

        val processed = AntimeridianProcessor.process(geometry) as GeoGeometry.MultiPolygon

        // One component untouched, one become two.
        assertEquals(3, processed.polygons.size)
    }

    /**
     * §158, end to end: the repair survives parsing, projection and fitting.
     */
    @Test
    fun aStraddlingCountryDrawsAsTwoShapesAtOppositeEdgesOfTheScreen() {
        val json = """
            { "type": "FeatureCollection", "features": [
              { "type": "Feature", "id": "fiji", "properties": {},
                "geometry": { "type": "Polygon", "coordinates":
                  [[[175,-5],[-175,-5],[-175,5],[175,5],[175,-5]]] } },
              { "type": "Feature", "id": "world-edge", "properties": {},
                "geometry": { "type": "Polygon", "coordinates":
                  [[[-180,-60],[180,-60],[180,-55],[-180,-55],[-180,-60]]] } }
            ]}
        """.trimIndent()

        val projected = ProjectedGeometry.of(
            GeoJson.parse(json),
            GeoProjection.Equirectangular,
        )
        val extent = projected.bounds.toBounds()
        assertNotNull(extent)
        val coordinates = GeoCoordinates(
            plotArea = ChartRect(0f, 0f, 360f, 180f),
            projection = GeoProjection.Equirectangular,
            extent = extent!!,
        )

        val fiji = projected.features.first { it.feature.id == "fiji" }
        // Two components, one against each edge, rather than one 350°-wide box.
        assertEquals(2, fiji.polygons.size)

        // The decisive check, in screen space: no edge of the drawn shape may
        // stretch across a large fraction of the plot.
        val plotWidth = coordinates.plotArea.width
        fiji.polygons.forEach { polygon ->
            val screen = polygon.outer.map { coordinates.screenOf(it) }
            for (index in screen.indices) {
                val next = screen[(index + 1) % screen.size]
                assertTrue(
                    "a drawn edge spans ${abs(next.x - screen[index].x)} of $plotWidth px",
                    abs(next.x - screen[index].x) < plotWidth / 2f,
                )
            }
        }
    }

    @Test
    fun aFeatureThatTouchesTheMeridianWithoutCrossingIsLeftWhole() {
        // The full-width strip in the fixture above runs -180 to 180 without
        // ever stepping more than 180° between neighbours, so it is not a wrap
        // and must not be cut in half.
        val strip = listOf(
            GeoCoordinate(-180.0, -60.0),
            GeoCoordinate(0.0, -60.0),
            GeoCoordinate(180.0, -60.0),
            GeoCoordinate(180.0, -55.0),
            GeoCoordinate(0.0, -55.0),
            GeoCoordinate(-180.0, -55.0),
        )

        assertSame(strip, AntimeridianProcessor.splitRing(strip).single())
    }

    // ---- the two shapes real boundary files actually contain ---------------

    /**
     * Natural Earth stores Fiji as **one** ring stitched across the seam, not as
     * two polygons: it runs down the western strip to −180, jumps to 179.4, runs
     * back through the eastern strip to +180, and closes. The 359° step in the
     * middle is the thing that must be recognised as a wrap.
     */
    @Test
    fun aRingStitchedAcrossTheSeamSplitsBackIntoItsTwoHalves() {
        val fiji = listOf(
            GeoCoordinate(-180.0, -16.067),
            GeoCoordinate(-179.795, -16.021),
            GeoCoordinate(-179.917, -16.502),
            GeoCoordinate(-180.0, -16.556),
            GeoCoordinate(179.363, -16.801),
            GeoCoordinate(178.726, -17.011),
            GeoCoordinate(178.596, -16.639),
            GeoCoordinate(179.096, -16.434),
            GeoCoordinate(179.413, -16.378),
            GeoCoordinate(180.0, -16.067),
        )

        val pieces = AntimeridianProcessor.splitRing(fiji)

        assertEquals(2, pieces.size)
        pieces.forEach { piece ->
            assertTrue(
                "a piece spans ${widestClosedGap(piece)}° between neighbours",
                widestClosedGap(piece) <= 20.0,
            )
            piece.forEach {
                assertTrue(it.longitude >= -180.0001 && it.longitude <= 180.0001)
            }
        }
        // One against each edge of the map.
        assertTrue(pieces.any { piece -> piece.all { it.longitude > 0.0 } })
        assertTrue(pieces.any { piece -> piece.all { it.longitude < 0.0 } })
    }

    /**
     * Antarctica is the opposite case, and the one a naive "split on any 360°
     * step" rule gets wrong.
     *
     * Its ring runs the whole way round the continent from −180 to +180 and then
     * closes along the bottom of the map — a seam that *is* the shape, not a
     * wrap in it. Splitting there would tear the continent into fragments and
     * leave a gap across the map's foot.
     *
     * The repair leaves it whole because the unwrapped ring still fits inside
     * one 360° band: there is no piece of it living outside `[−180, 180]`.
     */
    @Test
    fun aRingThatCirclesThePoleAndClosesAlongTheSeamStaysWhole() {
        // The shape of Natural Earth's Antarctica, at a tenth of the vertices:
        // a coastline of varying latitude from -180 to +180, then the closing
        // edge along -85.6 back to the start.
        val coast = (-180..180 step 20).map { longitude ->
            GeoCoordinate(
                longitude.toDouble(),
                // A wobbling coast, so the ring is not a rectangle.
                -70.0 + 6.0 * kotlin.math.sin(longitude / 30.0),
            )
        }
        val antarctica = coast + listOf(GeoCoordinate(-180.0, -85.6))

        val pieces = AntimeridianProcessor.splitRing(antarctica)

        assertEquals("the continent must not be torn apart", 1, pieces.size)
        val piece = pieces.single()
        // And it still reaches both edges, which is what makes the fill close
        // across the foot of the map instead of leaving a gap there.
        assertEquals(-180.0, piece.minOf { it.longitude }, 1e-9)
        assertEquals(180.0, piece.maxOf { it.longitude }, 1e-9)
        assertEquals(antarctica.size, piece.size)
    }

    @Test
    fun aRingThatCirclesThePoleKeepsEveryVertexItStartedWith() {
        // The stronger form of the previous test: not merely one piece of the
        // right width, but the same ring. A clip that quietly dropped or
        // duplicated vertices would still pass a bounds check.
        val ring = listOf(
            GeoCoordinate(-180.0, -60.0),
            GeoCoordinate(-90.0, -65.0),
            GeoCoordinate(0.0, -62.0),
            GeoCoordinate(90.0, -68.0),
            GeoCoordinate(180.0, -60.0),
            GeoCoordinate(180.0, -85.0),
            GeoCoordinate(-180.0, -85.0),
        )

        val piece = AntimeridianProcessor.splitRing(ring).single()

        assertEquals(ring, piece)
    }
}
