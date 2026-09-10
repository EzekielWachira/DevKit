package io.devkit.chartkit

import androidx.compose.ui.graphics.Color
import io.devkit.chartkit.coordinate.GeoCoordinates
import io.devkit.chartkit.geo.GeoCoordinate
import io.devkit.chartkit.geo.GeoGeometryMath
import io.devkit.chartkit.geo.GeoJson
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.geo.ProjectedGeometry
import io.devkit.chartkit.geo.ProjectedPoint
import io.devkit.chartkit.geo.ProjectedPolygon
import io.devkit.chartkit.geo.toBounds
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.SizeScale
import io.devkit.chartkit.scale.SizeScaleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Label anchors, line geometry, point encodings — the parts of the map engine
 * that are not about polygons being filled.
 */
class GeoLayersTest {

    private fun ring(vararg points: Pair<Double, Double>): List<ProjectedPoint> =
        points.map { ProjectedPoint(it.first, it.second) }

    // ---- interior label points (§77, §78) --------------------------------

    /**
     * A horseshoe. Its area centroid falls in the gap between the arms, which
     * is exactly the failure a "put the label at the centroid" rule produces on
     * a real crescent-shaped county.
     *
     * ```text
     * ██        ██
     * ██        ██
     * ██████████��█
     * ```
     */
    private val horseshoe = ProjectedPolygon(
        outer = ring(
            0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 8.0 to 10.0, 8.0 to 2.0,
            2.0 to 2.0, 2.0 to 10.0, 0.0 to 10.0,
        ),
        holes = emptyList(),
    )

    @Test
    fun theCentroidOfAHorseshoeIsOutsideIt() {
        val centroid = GeoGeometryMath.centroid(horseshoe.outer)

        assertNotNull(centroid)
        // Stated rather than assumed: the fallback below exists because of this.
        assertTrue(
            "the centroid was unexpectedly inside",
            !GeoGeometryMath.polygonContains(horseshoe, centroid!!.x, centroid.y),
        )
    }

    @Test
    fun theInteriorPointOfAHorseshoeIsInsideIt() {
        val point = GeoGeometryMath.interiorPoint(horseshoe)

        assertNotNull(point)
        assertTrue(
            "interior point $point was not inside the polygon",
            GeoGeometryMath.polygonContains(horseshoe, point!!.x, point.y),
        )
    }

    @Test
    fun aLabelForAHorseshoeGoesSomewhereInsideIt() {
        val point = GeoGeometryMath.labelPoint(listOf(horseshoe))

        assertNotNull(point)
        assertTrue(
            "the label anchor $point escaped the region it names",
            GeoGeometryMath.polygonContains(horseshoe, point!!.x, point.y),
        )
    }

    @Test
    fun aLabelForADoughnutGoesInTheRingRatherThanTheHole() {
        val doughnut = ProjectedPolygon(
            outer = ring(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0),
            holes = listOf(ring(3.0 to 3.0, 7.0 to 3.0, 7.0 to 7.0, 3.0 to 7.0)),
        )

        val point = GeoGeometryMath.labelPoint(listOf(doughnut))

        assertNotNull(point)
        // The centroid of a doughnut is the middle of its hole — which for an
        // enclave is a different country entirely.
        assertTrue(
            "the label landed in the hole at $point",
            GeoGeometryMath.polygonContains(doughnut, point!!.x, point.y),
        )
    }

    @Test
    fun aConvexRegionStillUsesItsCentroid() {
        val square = ProjectedPolygon(
            outer = ring(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0),
            holes = emptyList(),
        )

        val point = GeoGeometryMath.labelPoint(listOf(square))

        // The cheap path, taken for the overwhelming majority of regions: the
        // grid search runs only where the centroid demonstrably fails.
        assertEquals(5.0, point!!.x, 1e-9)
        assertEquals(5.0, point.y, 1e-9)
    }

    @Test
    fun aLabelAnchorIsAlwaysFiniteOrAbsent() {
        val degenerate = ProjectedPolygon(
            outer = ring(1.0 to 1.0, 1.0 to 1.0, 1.0 to 1.0),
            holes = emptyList(),
        )

        val point = GeoGeometryMath.labelPoint(listOf(degenerate))

        // Either nowhere, or somewhere real. Never NaN — a NaN anchor draws a
        // label at an unpredictable place rather than nowhere.
        if (point != null) assertTrue(point.isFinite)
    }

    @Test
    fun signedDistanceIsPositiveInsideAndNegativeOutside() {
        val square = ProjectedPolygon(
            outer = ring(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0),
            holes = emptyList(),
        )

        assertEquals(5.0, GeoGeometryMath.signedDistance(square, 5.0, 5.0), 1e-9)
        assertEquals(1.0, GeoGeometryMath.signedDistance(square, 1.0, 5.0), 1e-9)
        assertEquals(-2.0, GeoGeometryMath.signedDistance(square, -2.0, 5.0), 1e-9)
    }

    // ---- lines (§53, §54, §169) ------------------------------------------

    @Test
    fun distanceToAPathIsThePerpendicularOne() {
        val path = ring(0.0 to 0.0, 10.0 to 0.0)

        assertEquals(3.0, GeoGeometryMath.distanceToPath(path, 5.0, 3.0), 1e-9)
        // Past the end, the nearest point is the endpoint — not the infinite
        // line, which would report zero for somewhere the route never goes.
        assertEquals(5.0, GeoGeometryMath.distanceToPath(path, 15.0, 0.0), 1e-9)
        assertEquals(
            sqrt(2.0),
            GeoGeometryMath.distanceToPath(path, -1.0, 1.0),
            1e-9,
        )
    }

    @Test
    fun anEmptyPathIsInfinitelyFarAwayRatherThanZero() {
        assertEquals(
            Double.POSITIVE_INFINITY,
            GeoGeometryMath.distanceToPath(emptyList(), 0.0, 0.0),
            0.0,
        )
    }

    @Test
    fun aLineFeatureProjectsAndIsSelectableByProximity() {
        val json = """
            { "type": "FeatureCollection", "features": [
              { "type": "Feature", "id": "route", "properties": {},
                "geometry": { "type": "LineString",
                  "coordinates": [[0,0],[10,0],[10,10]] } }
            ]}
        """.trimIndent()

        val projected = ProjectedGeometry.of(
            GeoJson.parse(json),
            GeoProjection.Equirectangular,
        )
        val route = projected.features.single()

        assertEquals(1, route.lines.size)
        assertEquals(3, route.lines.single().size)
        assertTrue(route.isDrawable)
        // A line has no interior, so containment is always false and proximity
        // is the only meaningful question.
        assertTrue(!route.contains(5.0, 0.0))
        assertEquals(2.0, route.distanceToStrokes(5.0, 2.0), 1e-9)
    }

    @Test
    fun aLineFeatureIsLabelledOnItsOwnPath() {
        val json = """
            { "type": "Feature", "id": "river", "properties": {},
              "geometry": { "type": "LineString", "coordinates": [[0,0],[4,0],[8,0]] } }
        """.trimIndent()

        val projected = ProjectedGeometry.of(
            GeoJson.parse(json),
            GeoProjection.Equirectangular,
        )
        val anchor = projected.features.single().labelPoint

        assertNotNull(anchor)
        // A feature with no area is labelled at the middle of its path, not
        // nowhere — a route with a name still needs somewhere to put it.
        assertEquals(4.0, anchor!!.x, 1e-9)
    }

    @Test
    fun aGeometryCollectionContributesEveryKindItHolds() {
        val json = """
            { "type": "Feature", "id": "mixed", "properties": {},
              "geometry": { "type": "GeometryCollection", "geometries": [
                { "type": "Polygon", "coordinates": [[[0,0],[4,0],[4,4],[0,0]]] },
                { "type": "LineString", "coordinates": [[10,0],[14,0]] },
                { "type": "Point", "coordinates": [20, 20] }
              ]}}
        """.trimIndent()

        val feature = ProjectedGeometry.of(
            GeoJson.parse(json),
            GeoProjection.Equirectangular,
        ).features.single()

        assertEquals(1, feature.polygons.size)
        assertEquals(1, feature.lines.size)
        assertEquals(1, feature.markers.size)
        // The bounding box covers all three, so a fit frames the whole feature
        // rather than only its polygon.
        assertEquals(20.0, feature.bounds.maxX, 1e-9)
        assertEquals(20.0, feature.bounds.maxY, 1e-9)
        // Areas outrank lines and markers for the label.
        assertTrue(feature.labelPoint!!.x < 5.0)
    }

    // ---- points and bubbles (§57, §58, §168, §170) ------------------------

    @Test
    fun aBubblesValueDrivesItsAreaAndNotItsRadius() {
        val scale = SizeScale(
            domain = NumericDomain(0.0, 100.0),
            minSize = 0f,
            maxSize = 10f,
        )

        // A value a quarter of the way up the domain covers a quarter of the
        // area, which is half the radius. Mapping straight onto radius would
        // have given 2.5 here, and made a city of 25 look a quarter the size of
        // one of 100 rather than a quarter the area.
        assertEquals(5f, scale.size(25.0), 1e-4f)
        assertEquals(10f, scale.size(100.0), 1e-4f)
        assertEquals(0f, scale.size(0.0), 1e-4f)
    }

    @Test
    fun theRadiusModeIsAvailableAndDiffersFromArea() {
        val area = SizeScale(NumericDomain(0.0, 100.0), 0f, 10f, SizeScaleMode.Area)
        val radius = SizeScale(NumericDomain(0.0, 100.0), 0f, 10f, SizeScaleMode.Radius)

        assertEquals(5f, area.size(25.0), 1e-4f)
        assertEquals(2.5f, radius.size(25.0), 1e-4f)
    }

    @Test
    fun aMissingSizeNeverProducesAnInvisibleBubble() {
        val scale = SizeScale(NumericDomain(10.0, 100.0), 4f, 20f)

        // The scale's own answer for a missing value is its minimum, not zero:
        // a bubble of radius zero is an absent bubble, and a reader cannot tell
        // "not drawn" from "not measured".
        assertEquals(4f, scale.size(null), 1e-4f)
        assertEquals(4f, scale.size(Double.NaN), 1e-4f)

        // The geo defaults keep that minimum well clear of zero, so the rule
        // holds for a bubble map built without any explicit sizing.
        assertTrue(io.devkit.chartkit.theme.ChartDimensions().geoMinBubbleRadius.value > 0f)
    }

    @Test
    fun aMarkWithNoSizeValueKeepsTheFixedRadius() {
        // `GeoChartScope.points` does not hand a missing value to the size
        // scale at all — it uses the layer's fixed radius instead, so an
        // unmeasured city is drawn as a plain point rather than as the smallest
        // bubble on the map. This is that decision written down: the two radii
        // differ, and which one is used depends on whether a value exists.
        val scale = SizeScale(NumericDomain(10.0, 100.0), 4f, 20f)
        val fixed = 9f
        val sizeValue: Double? = null

        val radius = scale.size(sizeValue).takeIf { sizeValue != null } ?: fixed

        assertEquals(fixed, radius, 1e-4f)
        assertEquals(4f, scale.size(sizeValue), 1e-4f)
    }

    @Test
    fun aMarkProjectsAndFollowsTheViewport() {
        val extent = ProjectedGeometry.of(
            GeoJson.parse(
                """{ "type": "Feature", "properties": {}, "geometry":
                   { "type": "Polygon", "coordinates": [[[-10,-10],[10,-10],[10,10],[-10,10],[-10,-10]]] } }""",
            ),
            GeoProjection.Equirectangular,
        ).bounds.toBounds()

        val unzoomed = GeoCoordinates(
            plotArea = ChartRect(0f, 0f, 200f, 200f),
            projection = GeoProjection.Equirectangular,
            extent = extent!!,
        )
        val centre = unzoomed.screenOf(GeoCoordinate(0.0, 0.0))

        // The middle of the extent lands in the middle of the plot.
        assertEquals(100f, centre.x, 1e-3f)
        assertEquals(100f, centre.y, 1e-3f)

        // North is up: a mark further north is drawn higher on the screen.
        assertTrue(unzoomed.screenOf(GeoCoordinate(0.0, 5.0)).y < centre.y)

        val zoomed = GeoCoordinates(
            plotArea = ChartRect(0f, 0f, 200f, 200f),
            projection = GeoProjection.Equirectangular,
            extent = extent,
            zoom = 2f,
        )
        val near = zoomed.screenOf(GeoCoordinate(5.0, 0.0))

        // Zooming moves the mark away from the centre, by exactly the factor.
        assertEquals(
            2.0f * (unzoomed.screenOf(GeoCoordinate(5.0, 0.0)).x - 100f),
            near.x - 100f,
            1e-3f,
        )
    }

    @Test
    fun aMarkAndItsRegionAgreeAboutWhereTheyAre() {
        // The guarantee that makes layer composition safe: both go through the
        // same coordinate system, so a bubble over a county centre and the
        // county's own label anchor cannot disagree.
        val json = """
            { "type": "Feature", "id": "r", "properties": {}, "geometry":
              { "type": "Polygon", "coordinates": [[[0,0],[10,0],[10,10],[0,10],[0,0]]] } }
        """.trimIndent()
        val projected = ProjectedGeometry.of(GeoJson.parse(json), GeoProjection.Equirectangular)
        val coordinates = GeoCoordinates(
            plotArea = ChartRect(0f, 0f, 100f, 100f),
            projection = GeoProjection.Equirectangular,
            extent = projected.bounds.toBounds()!!,
        )

        val labelAnchor = coordinates.screenOf(projected.features.single().labelPoint!!)
        val mark = coordinates.screenOf(GeoCoordinate(5.0, 5.0))

        assertEquals(mark.x, labelAnchor.x, 1e-3f)
        assertEquals(mark.y, labelAnchor.y, 1e-3f)
    }

    // ---- colour scales over map values (§22, §163) ------------------------

    @Test
    fun everyScaleKindPlacesAValueAndRefusesAMissingOne() {
        val low = Color.Blue
        val high = Color.Red
        val scales = listOf<ColorScale>(
            ColorScale.Continuous(NumericDomain(0.0, 10.0), listOf(low, high)),
            ColorScale.Threshold(listOf(2.0, 5.0), listOf(low, Color.Green, high)),
            ColorScale.Quantized(NumericDomain(0.0, 10.0), listOf(low, high), steps = 4),
            ColorScale.Quantile(listOf(1.0, 2.0, 3.0, 8.0), listOf(low, high)),
            ColorScale.Categorical(listOf("a", "b"), listOf(low, high)),
        )

        scales.forEach { scale ->
            assertNotNull("${scale::class.simpleName} placed nothing", scale.colorAt(1.0))
            // The rule that makes "missing is not zero" work at all: a scale
            // must decline, so the layer can draw the theme's no-data colour
            // rather than the colour of the domain's floor.
            assertNull("${scale::class.simpleName} coloured a missing value", scale.colorAt(null))
            assertNull(scale.colorAt(Double.NaN))
        }
    }

    @Test
    fun zeroIsAValueAndGetsTheScalesColour() {
        val scale = ColorScale.Continuous(NumericDomain(0.0, 10.0), listOf(Color.Blue, Color.Red))

        // The other half of the same rule. A county that genuinely measured
        // zero is measured, and must be shaded.
        assertEquals(Color.Blue, scale.colorAt(0.0))
        assertNull(scale.colorAt(null))
    }

    // ---- hit ordering (§68, §72) -----------------------------------------

    @Test
    fun aTapInsideNestedRegionsOffersTheSmallerFirst() {
        val json = """
            { "type": "FeatureCollection", "features": [
              { "type": "Feature", "id": "state", "properties": {}, "geometry":
                { "type": "Polygon", "coordinates": [[[0,0],[100,0],[100,100],[0,100],[0,0]]] } },
              { "type": "Feature", "id": "city", "properties": {}, "geometry":
                { "type": "Polygon", "coordinates": [[[40,40],[60,40],[60,60],[40,60],[40,40]]] } }
            ]}
        """.trimIndent()

        val projected = ProjectedGeometry.of(GeoJson.parse(json), GeoProjection.Equirectangular)
        val candidates = projected.index.candidates(50.0, 50.0)

        // Geography nests. A tap inside a city that is inside a state should
        // offer the city, and the caller takes the first that contains the
        // point.
        assertEquals("city", candidates.first { it.contains(50.0, 50.0) }.feature.id)
    }

    @Test
    fun everyProjectedVertexIsFinite() {
        val json = """
            { "type": "FeatureCollection", "features": [
              { "type": "Feature", "id": "polar", "properties": {}, "geometry":
                { "type": "Polygon", "coordinates": [[[-180,-90],[180,-90],[180,-60],[-180,-60],[-180,-90]]] } }
            ]}
        """.trimIndent()

        listOf(GeoProjection.Equirectangular, GeoProjection.Mercator, GeoProjection.EqualEarth)
            .forEach { projection ->
                val projected = ProjectedGeometry.of(GeoJson.parse(json), projection)
                projected.features.flatMap { it.polygons }.flatMap { it.outer }.forEach { point ->
                    assertTrue(
                        "${projection.name} produced $point",
                        point.isFinite && abs(point.x) < 1e6 && abs(point.y) < 1e6,
                    )
                }
            }
    }

    @Test
    fun aTapWellOutsideEveryFeatureSelectsNothing() {
        val json = """
            { "type": "Feature", "id": "r", "properties": {}, "geometry":
              { "type": "Polygon", "coordinates": [[[0,0],[1,0],[1,1],[0,0]]] } }
        """.trimIndent()
        val projected = ProjectedGeometry.of(GeoJson.parse(json), GeoProjection.Equirectangular)

        assertTrue(
            projected.index.candidates(50.0, 50.0)
                .none { it.contains(50.0, 50.0) },
        )
    }

    @Test
    fun aScreenPointRoundTripsToTheSameProjectedPoint() {
        val extent = ProjectedGeometry.of(
            GeoJson.parse(
                """{ "type": "Feature", "properties": {}, "geometry":
                   { "type": "Polygon", "coordinates": [[[0,0],[10,0],[10,10],[0,10],[0,0]]] } }""",
            ),
            GeoProjection.EqualEarth,
        ).bounds.toBounds()!!

        val coordinates = GeoCoordinates(
            plotArea = ChartRect(0f, 0f, 240f, 160f),
            projection = GeoProjection.EqualEarth,
            extent = extent,
            zoom = 3f,
            panX = 0.2f,
            panY = -0.1f,
        )

        val point = ChartOffset(97f, 61f)
        val back = coordinates.screenOf(coordinates.projectedAt(point))

        // What makes hit testing correct after a zoom rather than approximately
        // correct.
        assertEquals(point.x, back.x, 1e-3f)
        assertEquals(point.y, back.y, 1e-3f)
    }
}
