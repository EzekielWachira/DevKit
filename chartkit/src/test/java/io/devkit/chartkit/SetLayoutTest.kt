package io.devkit.chartkit

import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.set.RegionGeometryIndex
import io.devkit.chartkit.set.SetAnalyzer
import io.devkit.chartkit.set.SetContainment
import io.devkit.chartkit.set.SetDefinition
import io.devkit.chartkit.set.SetDiagramLayout
import io.devkit.chartkit.set.SetIntersection
import io.devkit.chartkit.set.SetLayoutConfig
import io.devkit.chartkit.set.SetRelationship
import io.devkit.chartkit.set.SetShape
import io.devkit.chartkit.set.SetSizing
import io.devkit.chartkit.set.engine
import io.devkit.chartkit.set.membershipAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The layout engines: which regions they produce, where they put things, and
 * that they put them there every time.
 *
 * Assertions are about **relationships and counts**, never about pixel
 * positions. A test that pinned a circle to `x = 137.4` would fail the first
 * time anybody improved the solver, and would have told nobody anything about
 * whether the diagram was right.
 */
class SetLayoutTest {

    private fun venn(
        sets: List<SetDefinition>,
        intersections: List<SetIntersection> = emptyList(),
        sizing: SetSizing = SetSizing.Conceptual,
    ) = SetDiagramLayout.Venn(sizing)
        .engine()
        .layout(SetAnalyzer.analyze(sets, intersections), SetLayoutConfig.Default)

    private fun euler(
        sets: List<SetDefinition>,
        intersections: List<SetIntersection> = emptyList(),
        containments: List<SetContainment> = emptyList(),
        sizing: SetSizing = SetSizing.Proportional,
    ) = SetDiagramLayout.Euler(sizing)
        .engine()
        .layout(SetAnalyzer.analyze(sets, intersections, containments), SetLayoutConfig.Default)

    private fun sets(vararg pairs: Pair<String, Double>): List<SetDefinition> =
        pairs.map { SetDefinition(it.first, it.first.uppercase(), it.second) }

    /** Every combination a laid-out arrangement actually produces. */
    private fun memberships(layout: io.devkit.chartkit.set.SetLayout): Set<Set<String>> =
        RegionGeometryIndex.of(layout, resolution = 260).regions.keys

    // ---- Venn ------------------------------------------------------------

    @Test
    fun aTwoSetVennProducesThreeRegions() {
        val found = memberships(venn(sets("a" to 100.0, "b" to 80.0)))

        assertEquals(
            setOf(setOf("a"), setOf("b"), setOf("a", "b")),
            found,
        )
    }

    @Test
    fun aThreeSetVennProducesAllSevenRegions() {
        val found = memberships(venn(sets("a" to 1.0, "b" to 1.0, "c" to 1.0)))

        // The whole point of a Venn diagram: every combination is drawable,
        // whether or not the data puts anything in it.
        assertEquals(7, found.size)
        assertTrue(setOf("a", "b", "c") in found)
    }

    @Test
    fun aFourSetVennProducesAllFifteenRegions() {
        val found = memberships(venn(sets("a" to 1.0, "b" to 1.0, "c" to 1.0, "d" to 1.0)))

        // No four circles can do this, which is why the engine switches to
        // ellipses at four sets. If this ever fails, the ellipse construction
        // has been broken and the diagram is silently losing regions.
        assertEquals(15, found.size)
        assertTrue(setOf("a", "b", "c", "d") in found)
        assertTrue(setOf("a", "d") in found)
    }

    @Test
    fun aFourSetVennUsesEllipsesAndSaysSo() {
        val layout = venn(sets("a" to 1.0, "b" to 1.0, "c" to 1.0, "d" to 1.0))

        assertTrue(layout.shapes.values.all { it is SetShape.Ellipse })
        assertEquals(1.0, layout.quality.regionCoverage, 1e-9)
    }

    @Test
    fun aFiveSetVennReportsItsActualCoverageRatherThanClaimingCompleteness() {
        val layout = venn(sets("a" to 1.0, "b" to 1.0, "c" to 1.0, "d" to 1.0, "e" to 1.0))

        // Whatever the rosette manages, it must be *measured*. A five-set
        // arrangement that quietly reported full coverage would be the library
        // lying about the hardest case it supports.
        assertTrue(layout.quality.regionCoverage > 0.0)
        assertTrue(layout.quality.regionCoverage <= 1.0)
        assertEquals(memberships(layout).size / 31.0, layout.quality.regionCoverage, 0.08)
    }

    @Test
    fun aOneSetVennIsOneCircle() {
        val layout = venn(sets("a" to 10.0))

        assertEquals(1, layout.shapes.size)
        assertTrue(layout.shapes.values.single() is SetShape.Circle)
    }

    @Test
    fun aProportionalTwoSetVennSizesByAreaNotRadius() {
        val layout = venn(
            sets("a" to 400.0, "b" to 100.0),
            listOf(SetIntersection(setOf("a", "b"), 0.0)),
            SetSizing.Proportional,
        )

        val a = layout.shapes.getValue("a") as SetShape.Circle
        val b = layout.shapes.getValue("b") as SetShape.Circle

        // Four times the cardinality is four times the *area*, so twice the
        // radius. Sizing radius by value directly would have made it sixteen
        // times the area, which is the classic bubble-chart lie.
        assertEquals(2.0, a.radius / b.radius, 0.05)
    }

    @Test
    fun aProportionalTwoSetVennReproducesTheStatedOverlap() {
        val data = SetAnalyzer.analyze(
            sets("a" to 100.0, "b" to 80.0),
            listOf(SetIntersection(setOf("a", "b"), 30.0)),
        )
        val layout = SetDiagramLayout.Venn(SetSizing.Proportional)
            .engine()
            .layout(data, SetLayoutConfig.Default)

        val a = layout.shapes.getValue("a") as SetShape.Circle
        val b = layout.shapes.getValue("b") as SetShape.Circle
        val overlap = io.devkit.chartkit.set.SetGeometryUtils.circleIntersectionArea(
            a.centerX, a.centerY, a.radius,
            b.centerX, b.centerY, b.radius,
        )
        val expected = 30.0 * io.devkit.chartkit.set.areaUnit(data)

        assertEquals(expected, overlap, expected * 0.05)
    }

    @Test
    fun aProportionalThreeSetVennGetsCloseAndReportsHowClose() {
        val data = SetAnalyzer.analyze(
            sets("a" to 200.0, "b" to 160.0, "c" to 240.0),
            listOf(
                SetIntersection(setOf("a", "b"), 70.0),
                SetIntersection(setOf("a", "c"), 100.0),
                SetIntersection(setOf("b", "c"), 80.0),
                SetIntersection(setOf("a", "b", "c"), 45.0),
            ),
        )
        val layout = SetDiagramLayout.Venn(SetSizing.Proportional)
            .engine()
            .layout(data, SetLayoutConfig.Default)

        // Three circles have six degrees of freedom and this system has seven
        // quantities, so exactness is not on offer. What is on offer is a small,
        // *reported* error.
        assertTrue(
            "worst pairwise error was ${layout.quality.worstAreaError}",
            layout.quality.worstAreaError < 0.15,
        )
        assertTrue(layout.quality.iterations > 0)
    }

    @Test
    fun proportionalLayoutIsDeterministic() {
        val definitions = sets("a" to 200.0, "b" to 160.0, "c" to 240.0)
        val intersections = listOf(
            SetIntersection(setOf("a", "b"), 70.0),
            SetIntersection(setOf("a", "c"), 100.0),
            SetIntersection(setOf("b", "c"), 80.0),
            SetIntersection(setOf("a", "b", "c"), 45.0),
        )

        val first = venn(definitions, intersections, SetSizing.Proportional)
        val second = venn(definitions, intersections, SetSizing.Proportional)

        // Same input, same picture — on every recomposition, on every device.
        // A solver with a random restart could not promise this.
        first.shapes.forEach { (id, shape) ->
            val other = second.shapes.getValue(id)
            assertEquals(shape.centerX, other.centerX, 0.0)
            assertEquals(shape.centerY, other.centerY, 0.0)
        }
    }

    @Test
    fun inputOrderDoesNotChangeTheArrangement() {
        val forwards = venn(sets("a" to 100.0, "b" to 100.0, "c" to 100.0))
        val backwards = venn(sets("c" to 100.0, "b" to 100.0, "a" to 100.0))

        // Each set keeps its slot in the canonical arrangement, so the *set of
        // shapes* is the same even though the ids move between them. What must
        // not happen is the picture changing shape.
        assertEquals(forwards.shapes.size, backwards.shapes.size)
        assertEquals(
            forwards.shapes.values.map { it.area }.sorted(),
            backwards.shapes.values.map { it.area }.sorted(),
        )
    }

    // ---- Euler -----------------------------------------------------------

    @Test
    fun eulerNestsAContainedSetInsideItsContainer() {
        val layout = euler(
            sets("animals" to 100.0, "mammals" to 40.0),
            containments = listOf(SetContainment("animals", "mammals")),
        )

        val animals = layout.shapes.getValue("animals") as SetShape.Circle
        val mammals = layout.shapes.getValue("mammals") as SetShape.Circle
        val separation = hypot(animals.centerX - mammals.centerX, animals.centerY - mammals.centerY)

        // Wholly inside: the far edge of the inner circle is still within the
        // outer one. Drawn as a large overlap instead, the picture would show a
        // region for "a mammal that is not an animal".
        assertTrue(
            "mammals should sit inside animals, separation=$separation",
            separation + mammals.radius <= animals.radius + 1e-6,
        )
    }

    @Test
    fun eulerKeepsDisjointSetsApart() {
        val layout = euler(
            sets("animals" to 100.0, "plants" to 60.0),
            emptyList(),
        )

        val animals = layout.shapes.getValue("animals") as SetShape.Circle
        val plants = layout.shapes.getValue("plants") as SetShape.Circle
        val separation = hypot(animals.centerX - plants.centerX, animals.centerY - plants.centerY)

        assertTrue(
            "disjoint sets must not touch, separation=$separation",
            separation >= animals.radius + plants.radius - 1e-6,
        )
        assertTrue(memberships(layout).none { it.size > 1 })
    }

    @Test
    fun theVennAndEulerSemanticsGenuinelyDiffer() {
        val definitions = sets("animals" to 100.0, "mammals" to 40.0, "plants" to 60.0)
        val containments = listOf(SetContainment("animals", "mammals"))
        val data = SetAnalyzer.analyze(definitions, emptyList(), containments)

        val vennRegions = memberships(
            SetDiagramLayout.Venn(SetSizing.Conceptual).engine()
                .layout(data, SetLayoutConfig.Default),
        )
        val eulerRegions = memberships(
            SetDiagramLayout.Euler(SetSizing.Proportional).engine()
                .layout(data, SetLayoutConfig.Default),
        )

        // The reference comparison, as a test. Venn draws a region for "a
        // mammal that is a plant"; Euler does not, because there is not one.
        assertTrue(setOf("mammals", "plants") in vennRegions)
        assertTrue(setOf("mammals", "plants") !in eulerRegions)
        assertTrue(setOf("animals", "mammals") in eulerRegions)
    }

    @Test
    fun eulerNestsThreeLevelsDeep() {
        val layout = euler(
            sets("world" to 100.0, "country" to 50.0, "city" to 10.0),
            containments = listOf(
                SetContainment("world", "country"),
                // Transitive: nothing says world contains city, and nothing has to.
                SetContainment("country", "city"),
            ),
        )

        val world = layout.shapes.getValue("world") as SetShape.Circle
        val country = layout.shapes.getValue("country") as SetShape.Circle
        val city = layout.shapes.getValue("city") as SetShape.Circle

        fun nested(outer: SetShape.Circle, inner: SetShape.Circle) =
            hypot(outer.centerX - inner.centerX, outer.centerY - inner.centerY) + inner.radius <=
                outer.radius + 1e-6

        assertTrue("country inside world", nested(world, country))
        assertTrue("city inside country", nested(country, city))
    }

    @Test
    fun eulerHandlesPartialOverlapWithoutNesting() {
        val layout = euler(
            sets("a" to 100.0, "b" to 80.0),
            listOf(SetIntersection(setOf("a", "b"), 30.0)),
        )

        val found = memberships(layout)
        assertTrue(setOf("a") in found)
        assertTrue(setOf("b") in found)
        assertTrue(setOf("a", "b") in found)
    }

    @Test
    fun eulerSurvivesTwoIdenticalSets() {
        val layout = euler(
            sets("a" to 50.0, "b" to 50.0),
            listOf(SetIntersection(setOf("a", "b"), 50.0)),
        )

        // Indistinguishable by cardinality, so they are drawn coincident rather
        // than one of them vanishing or the nesting recursing for ever.
        assertEquals(
            SetRelationship.Equal,
            SetAnalyzer.analyze(
                sets("a" to 50.0, "b" to 50.0),
                listOf(SetIntersection(setOf("a", "b"), 50.0)),
            ).relationships.between("a", "b"),
        )
        assertEquals(2, layout.shapes.size)
        layout.shapes.values.forEach { assertTrue(it.area > 0.0) }
    }

    @Test
    fun eulerHandlesOneSetOverlappingTwoDisjointOnes() {
        val layout = euler(
            sets("a" to 100.0, "b" to 60.0, "c" to 60.0),
            listOf(
                SetIntersection(setOf("a", "b"), 20.0),
                SetIntersection(setOf("a", "c"), 20.0),
            ),
        )
        val found = memberships(layout)

        // B ↔ A ↔ C, and crucially *not* B ∩ C: the layout must not invent an
        // intersection the data does not have.
        assertTrue(setOf("a", "b") in found)
        assertTrue(setOf("a", "c") in found)
        assertTrue("b and c must not overlap", setOf("b", "c") !in found)
    }

    @Test
    fun aDeeplyNestedEulerDiagramKeepsEveryContainment() {
        // The shape of the British Isles: four levels, siblings, and a set that
        // overlaps across branches.
        val definitions = sets(
            "isles" to 100.0,
            "islands" to 70.0,
            "uk" to 60.0,
            "britain" to 50.0,
            "ireland" to 30.0,
        )
        val containments = listOf(
            SetContainment("isles", "islands"),
            SetContainment("islands", "uk"),
            SetContainment("uk", "britain"),
            SetContainment("isles", "ireland"),
        )
        // The one relationship the nesting does not express: Northern Ireland is
        // in both the United Kingdom and the island of Ireland, so those two
        // overlap across branches of the tree.
        val intersections = listOf(SetIntersection(setOf("uk", "ireland"), 10.0))
        val data = SetAnalyzer.analyze(definitions, intersections, containments)
        val layout = SetDiagramLayout.Euler(SetSizing.Proportional)
            .engine()
            .layout(data, SetLayoutConfig.Default)

        fun nested(outer: String, inner: String): Boolean {
            val o = layout.shapes.getValue(outer) as SetShape.Circle
            val i = layout.shapes.getValue(inner) as SetShape.Circle
            return hypot(o.centerX - i.centerX, o.centerY - i.centerY) + i.radius <= o.radius + 0.02
        }

        assertTrue("islands inside isles", nested("isles", "islands"))
        assertTrue("uk inside islands", nested("islands", "uk"))
        assertTrue("britain inside uk", nested("uk", "britain"))
        // Northern Ireland's region is *not* `{uk, ireland}`: those points are
        // also inside the British Islands and the British Isles, because those
        // contain the UK. The region is the full membership, which is exactly
        // what a reader tapping there is in.
        assertTrue(
            "the UK and the island of Ireland must overlap",
            memberships(layout).any { "uk" in it && "ireland" in it },
        )
        assertTrue(
            "that overlap is inside both containers",
            memberships(layout)
                .filter { "uk" in it && "ireland" in it }
                .all { "islands" in it && "isles" in it },
        )
    }

    // ---- fitting and hit testing -----------------------------------------

    @Test
    fun fittingPreservesAspectRatio() {
        val layout = venn(sets("a" to 1.0, "b" to 1.0, "c" to 1.0))
        val wide = layout.fitInto(ChartRect(0f, 0f, 600f, 200f))

        val circles = wide.shapes.values.map { it as SetShape.Circle }
        val radii = circles.map { it.radius }

        // Three equal circles must still be three equal circles. Fitting each
        // axis independently would have made them ellipses of different widths,
        // and every area in the diagram would then be a different lie.
        assertTrue(radii.max() - radii.min() < 1e-6)
    }

    @Test
    fun fittingKeepsTheDiagramInsideThePlot() {
        val layout = venn(sets("a" to 1.0, "b" to 1.0, "c" to 1.0))
            .fitInto(ChartRect(0f, 0f, 400f, 300f), padding = 10f)

        layout.shapes.values.forEach { shape ->
            val box = shape.bounds()
            assertTrue(box[0] >= 9.0)
            assertTrue(box[1] >= 9.0)
            assertTrue(box[2] <= 391.0)
            assertTrue(box[3] <= 291.0)
        }
    }

    @Test
    fun membershipIsDecidedByGeometryNotByDrawOrder() {
        val layout = venn(sets("a" to 1.0, "b" to 1.0, "c" to 1.0))
            .fitInto(ChartRect(0f, 0f, 300f, 300f))
        val regions = RegionGeometryIndex.of(layout)

        val triple = regions.geometry(setOf("a", "b", "c"))
        assertNotNull(triple)

        // The point at the centre of a triple overlap is in all three sets, and
        // must report all three — not whichever circle happens to be last.
        assertEquals(
            setOf("a", "b", "c"),
            layout.membershipAt(triple!!.anchor.x, triple.anchor.y),
        )
    }

    @Test
    fun aPointOutsideEverySetHasNoMembership() {
        val layout = venn(sets("a" to 1.0, "b" to 1.0))
            .fitInto(ChartRect(0f, 0f, 300f, 300f))

        assertTrue(layout.membershipAt(1f, 1f).isEmpty())
    }

    @Test
    fun everyRegionAnchorIsInsideItsOwnRegion() {
        val layout = venn(sets("a" to 1.0, "b" to 1.0, "c" to 1.0, "d" to 1.0))
            .fitInto(ChartRect(0f, 0f, 500f, 500f))
        val regions = RegionGeometryIndex.of(layout)

        // The property that makes anchors usable for labels: a centroid would
        // fail this for every crescent-shaped exclusive region.
        regions.regions.forEach { (memberships, geometry) ->
            assertEquals(
                "anchor for $memberships",
                memberships,
                layout.membershipAt(geometry.anchor.x, geometry.anchor.y),
            )
        }
    }

    @Test
    fun aCustomLayoutIsUsedExactlyAsGiven() {
        val shapes = mapOf<String, SetShape>(
            "a" to SetShape.Circle(-1.0, 0.0, 1.0),
            "b" to SetShape.Circle(1.0, 0.0, 1.0),
        )
        val layout = SetDiagramLayout.Custom(shapes)
            .engine()
            .layout(SetAnalyzer.analyze(sets("a" to 1.0, "b" to 1.0)), SetLayoutConfig.Default)

        assertEquals(shapes, layout.shapes)
    }

    @Test
    fun aCustomLayoutFallsBackForSetsItDoesNotPlace() {
        val layout = SetDiagramLayout.Custom(mapOf("a" to SetShape.Circle(0.0, 0.0, 1.0)))
            .engine()
            .layout(SetAnalyzer.analyze(sets("a" to 1.0, "b" to 1.0)), SetLayoutConfig.Default)

        assertEquals(2, layout.shapes.size)
        assertTrue(layout.shapes.containsKey("b"))
    }

    @Test
    fun theSolverStopsAtItsIterationCeiling() {
        val data = SetAnalyzer.analyze(
            sets("a" to 100.0, "b" to 100.0, "c" to 100.0),
            listOf(
                SetIntersection(setOf("a", "b"), 60.0),
                SetIntersection(setOf("a", "c"), 60.0),
                SetIntersection(setOf("b", "c"), 60.0),
                SetIntersection(setOf("a", "b", "c"), 40.0),
            ),
        )
        val layout = SetDiagramLayout.Venn(SetSizing.Proportional)
            .engine()
            .layout(data, SetLayoutConfig(maxIterations = 32))

        // A hard ceiling, reached rather than exceeded: an inconsistent dataset
        // must not be able to spin the solver.
        assertTrue(layout.quality.iterations <= 32)
    }

    @Test
    fun anEmptyDiagramLaysOutToNothing() {
        val layout = venn(emptyList())

        assertTrue(layout.isEmpty)
        assertTrue(RegionGeometryIndex.of(layout).regions.isEmpty())
    }

    @Test
    fun aTinySetStillGetsAVisibleShape() {
        val layout = euler(
            sets("big" to 10_000.0, "tiny" to 1.0),
            emptyList(),
        )

        val tiny = layout.shapes.getValue("tiny") as SetShape.Circle
        assertTrue("a tiny set must still be drawable", tiny.radius > 0.0)
        assertTrue(abs(tiny.radius) < (layout.shapes.getValue("big") as SetShape.Circle).radius)
    }
}
