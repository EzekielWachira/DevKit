package io.devkit.chartkit

import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.set.SetAnalyzer
import io.devkit.chartkit.set.SetDefinition
import io.devkit.chartkit.set.SetGeometryUtils
import io.devkit.chartkit.set.SetHitTester
import io.devkit.chartkit.set.SetIntersection
import io.devkit.chartkit.set.SetLayout
import io.devkit.chartkit.set.SetShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Circle and ellipse arithmetic, against geometry whose answer is known.
 *
 * The layout solver minimises error against these numbers, so an intersection
 * area that is subtly wrong produces a diagram that looks plausible and states
 * the wrong cardinalities. Every case here has an answer derivable by hand.
 */
class SetGeometryTest {

    // ---- circle intersection area ----------------------------------------

    @Test
    fun circlesThatDoNotReachEachOtherShareNothing() {
        val area = SetGeometryUtils.circleIntersectionArea(0.0, 0.0, 1.0, 5.0, 0.0, 1.0)

        assertEquals(0.0, area, 1e-12)
    }

    @Test
    fun circlesThatTouchAtOnePointShareNothingAndProduceNoNaN() {
        val area = SetGeometryUtils.circleIntersectionArea(0.0, 0.0, 1.0, 2.0, 0.0, 1.0)

        // Tangency is where the closed form's `acos` argument reaches exactly
        // one. Off by a rounding step and it returns NaN, which then travels
        // into the solver and out to the canvas.
        assertEquals(0.0, area, 1e-9)
        assertTrue(area.isFinite())
    }

    @Test
    fun aCircleInsideAnotherSharesItsWholeArea() {
        val area = SetGeometryUtils.circleIntersectionArea(0.0, 0.0, 5.0, 1.0, 0.0, 2.0)

        assertEquals(PI * 4.0, area, 1e-9)
    }

    @Test
    fun coincidentCirclesShareEverythingWithoutDividingByZero() {
        val area = SetGeometryUtils.circleIntersectionArea(0.0, 0.0, 3.0, 0.0, 0.0, 3.0)

        assertEquals(PI * 9.0, area, 1e-9)
        assertTrue(area.isFinite())
    }

    @Test
    fun twoEqualCirclesWhoseCentresTouchGiveTheKnownLensArea() {
        // Two unit circles at distance 1. The lens area is
        // 2r²·cos⁻¹(d/2r) − (d/2)·√(4r² − d²) = 2π/3 − √3/2.
        val area = SetGeometryUtils.circleIntersectionArea(0.0, 0.0, 1.0, 1.0, 0.0, 1.0)
        val expected = 2 * PI / 3 - sqrt(3.0) / 2

        assertEquals(expected, area, 1e-9)
    }

    @Test
    fun theIntersectionAreaIsSymmetric() {
        val forwards = SetGeometryUtils.circleIntersectionArea(0.0, 0.0, 2.0, 1.5, 0.7, 1.2)
        val backwards = SetGeometryUtils.circleIntersectionArea(1.5, 0.7, 1.2, 0.0, 0.0, 2.0)

        assertEquals(forwards, backwards, 1e-12)
    }

    @Test
    fun aZeroRadiusCircleSharesNothing() {
        assertEquals(0.0, SetGeometryUtils.circleIntersectionArea(0.0, 0.0, 0.0, 0.0, 0.0, 1.0), 0.0)
    }

    @Test
    fun overlapShrinksMonotonicallyWithDistance() {
        val distances = listOf(0.0, 0.5, 1.0, 1.5, 1.9, 2.0)
        val areas = distances.map {
            SetGeometryUtils.circleIntersectionArea(0.0, 0.0, 1.0, it, 0.0, 1.0)
        }

        // The property `distanceForOverlap` bisects on. If it ever stopped
        // holding, the solver's root finding would be searching a function with
        // no unique answer.
        areas.zipWithNext().forEach { (larger, smaller) ->
            assertTrue("$larger should exceed $smaller", larger >= smaller - 1e-12)
        }
    }

    // ---- solving for a distance ------------------------------------------

    @Test
    fun theSolvedDistanceProducesTheRequestedOverlap() {
        val target = 1.2
        val distance = SetGeometryUtils.distanceForOverlap(1.5, 1.0, target)

        val achieved = SetGeometryUtils.circleIntersectionArea(0.0, 0.0, 1.5, distance, 0.0, 1.0)
        assertEquals(target, achieved, 1e-6)
    }

    @Test
    fun anImpossibleOverlapSolvesToFullContainment() {
        // More overlap than the smaller circle has area. The closest the
        // geometry can get is one circle wholly inside the other.
        val distance = SetGeometryUtils.distanceForOverlap(3.0, 1.0, 1000.0)

        assertEquals(2.0, distance, 1e-9)
    }

    @Test
    fun zeroOverlapSolvesToTangency() {
        val distance = SetGeometryUtils.distanceForOverlap(2.0, 1.0, 0.0)

        assertEquals(3.0, distance, 1e-9)
    }

    @Test
    fun theRadiusForAnAreaRoundTrips() {
        val radius = SetGeometryUtils.radiusForArea(PI * 16)

        assertEquals(4.0, radius, 1e-9)
    }

    // ---- shapes ----------------------------------------------------------

    @Test
    fun aCircleContainsItsOwnCentreAndNotAPointOutside() {
        val circle = SetShape.Circle(1.0, 2.0, 3.0)

        assertTrue(circle.contains(1.0, 2.0))
        assertTrue(circle.contains(4.0, 2.0))
        assertFalse(circle.contains(4.5, 2.0))
    }

    @Test
    fun anEllipseTestsInNormalisedCoordinates() {
        val ellipse = SetShape.Ellipse(0.0, 0.0, radiusX = 4.0, radiusY = 1.0)

        assertTrue(ellipse.contains(3.9, 0.0))
        assertFalse(ellipse.contains(4.1, 0.0))
        assertTrue(ellipse.contains(0.0, 0.9))
        // The point a circular test would wrongly include.
        assertFalse(ellipse.contains(0.0, 1.1))
    }

    @Test
    fun aRotatedEllipseRotatesItsContainment() {
        val upright = SetShape.Ellipse(0.0, 0.0, 4.0, 1.0)
        val turned = SetShape.Ellipse(0.0, 0.0, 4.0, 1.0, rotation = PI / 2)

        assertTrue(upright.contains(3.5, 0.0))
        assertFalse(turned.contains(3.5, 0.0))
        assertTrue(turned.contains(0.0, 3.5))
    }

    @Test
    fun aRotatedEllipseReportsItsRealBounds() {
        val turned = SetShape.Ellipse(0.0, 0.0, 4.0, 1.0, rotation = PI / 2)
        val box = turned.bounds()

        // Not the unrotated box: a rotated ellipse's extent is
        // sqrt((rx·cos)² + (ry·sin)²), and using the unrotated one would cull
        // half the shape out of the diagram's fit.
        assertEquals(-1.0, box[0], 1e-9)
        assertEquals(1.0, box[2], 1e-9)
        assertEquals(-4.0, box[1], 1e-9)
        assertEquals(4.0, box[3], 1e-9)
    }

    @Test
    fun aCircleArea() {
        assertEquals(PI * 25, SetShape.Circle(0.0, 0.0, 5.0).area, 1e-9)
        assertEquals(PI * 6, SetShape.Ellipse(0.0, 0.0, 3.0, 2.0).area, 1e-9)
    }

    @Test
    fun transformingAShapeScalesAndMovesIt() {
        val moved = SetShape.Circle(1.0, 1.0, 2.0).transformed(3.0, 10.0, 20.0) as SetShape.Circle

        assertEquals(13.0, moved.centerX, 1e-9)
        assertEquals(23.0, moved.centerY, 1e-9)
        assertEquals(6.0, moved.radius, 1e-9)
    }

    @Test
    fun sampledIntersectionAgreesWithTheClosedFormForCircles() {
        val a = SetShape.Circle(0.0, 0.0, 1.0)
        val b = SetShape.Circle(1.0, 0.0, 1.0)

        val exact = SetGeometryUtils.circleIntersectionArea(0.0, 0.0, 1.0, 1.0, 0.0, 1.0)
        val sampled = SetGeometryUtils.sampledIntersectionArea(listOf(a, b), resolution = 400)

        assertEquals(exact, sampled, exact * 0.02)
    }

    @Test
    fun samplingIsDeterministic() {
        val shapes = listOf(
            SetShape.Circle(0.0, 0.0, 1.0),
            SetShape.Ellipse(0.6, 0.2, 1.1, 0.7, 0.4),
        )

        // A Monte Carlo estimate would answer differently each call, and a
        // solver minimising a noisy objective cannot converge.
        assertEquals(
            SetGeometryUtils.sampledIntersectionArea(shapes),
            SetGeometryUtils.sampledIntersectionArea(shapes),
            0.0,
        )
    }

    @Test
    fun boundsOfNothingIsNullRatherThanAZeroBox() {
        assertNull(SetGeometryUtils.boundsOf(emptyList()))
        assertNotNull(SetGeometryUtils.boundsOf(listOf(SetShape.Circle(0.0, 0.0, 1.0))))
    }

    // ---- hit testing -----------------------------------------------------

    private val data = SetAnalyzer.analyze(
        listOf(
            SetDefinition("a", "A", 100.0),
            SetDefinition("b", "B", 100.0),
            SetDefinition("c", "C", 100.0),
        ),
        listOf(
            SetIntersection(setOf("a", "b"), 40.0),
            SetIntersection(setOf("a", "c"), 40.0),
            SetIntersection(setOf("b", "c"), 40.0),
            SetIntersection(setOf("a", "b", "c"), 20.0),
        ),
    )

    /** Three unit circles in a triangle: the canonical three-set arrangement. */
    private val layout = SetLayout(
        shapes = mapOf(
            "a" to SetShape.Circle(0.0, -0.6, 1.0),
            "b" to SetShape.Circle(0.52, 0.3, 1.0),
            "c" to SetShape.Circle(-0.52, 0.3, 1.0),
        ),
        order = listOf("a", "b", "c"),
    )

    private fun regionAt(x: Double, y: Double) =
        SetHitTester.regionAt(ChartOffset(x.toFloat(), y.toFloat()), layout, data)

    @Test
    fun aTapInOneCircleAloneSelectsThatSetsExclusiveRegion() {
        val region = regionAt(0.0, -1.4)

        assertNotNull(region)
        // "A only", not "A": the exclusive region has its own value, and
        // reporting the set's total for it would be off by everything shared.
        assertEquals(setOf("a"), region!!.memberships)
        assertEquals(40.0, region.value, 1e-9)
    }

    @Test
    fun aTapWhereTwoCirclesCrossSelectsThePair() {
        // Out along the A–B axis, past C's edge: inside two circles and outside
        // the third, which is what makes it the pairwise region rather than the
        // triple one.
        val region = regionAt(0.477, -0.275)

        assertNotNull(region)
        assertEquals(setOf("a", "b"), region!!.memberships)
        assertEquals(20.0, region.value, 1e-9)
    }

    @Test
    fun aTapInTheMiddleSelectsTheTripleIntersection() {
        val region = regionAt(0.0, 0.0)

        // Not one of the pairs, and not whichever circle was drawn last: the
        // point is in all three, so the region is all three.
        assertNotNull(region)
        assertEquals(setOf("a", "b", "c"), region!!.memberships)
        assertEquals(20.0, region.value, 1e-9)
    }

    @Test
    fun everyPairwiseRegionIsReachable() {
        assertEquals(setOf("a", "b"), regionAt(0.477, -0.275)!!.memberships)
        assertEquals(setOf("a", "c"), regionAt(-0.477, -0.275)!!.memberships)
        assertEquals(setOf("b", "c"), regionAt(0.0, 0.55)!!.memberships)
    }

    @Test
    fun aTapOutsideEverySetSelectsNothing() {
        // Outside is a real answer. Snapping to the nearest region would select
        // something the finger is not touching.
        assertNull(regionAt(5.0, 5.0))
    }

    @Test
    fun drawOrderDoesNotChangeWhatIsSelected() {
        val reversed = SetLayout(layout.shapes, layout.order.reversed())

        assertEquals(
            SetHitTester.regionAt(ChartOffset(0f, 0f), layout, data)!!.memberships,
            SetHitTester.regionAt(ChartOffset(0f, 0f), reversed, data)!!.memberships,
        )
    }

    @Test
    fun anEmptyTheoreticalRegionStillAnswers() {
        val sparse = SetAnalyzer.analyze(
            listOf(
                SetDefinition("a", "A", 10.0),
                SetDefinition("b", "B", 10.0),
                SetDefinition("c", "C", 10.0),
            ),
        )

        val region = SetHitTester.regionAt(ChartOffset(0f, 0f), layout, sparse)

        // A Venn diagram draws this region; the reader who taps it is told it
        // holds nothing, rather than the tap doing nothing at all.
        assertNotNull(region)
        assertEquals(0.0, region!!.value, 1e-9)
        assertTrue(region.isTheoretical)
    }
}
