package io.devkit.chartkit

import io.devkit.chartkit.set.SetAnalyzer
import io.devkit.chartkit.set.SetContainment
import io.devkit.chartkit.set.SetDataException
import io.devkit.chartkit.set.SetDefinition
import io.devkit.chartkit.set.SetDiagnosticSeverity
import io.devkit.chartkit.set.SetIntersection
import io.devkit.chartkit.set.SetItems
import io.devkit.chartkit.set.SetRegionKind
import io.devkit.chartkit.set.SetRelationship
import io.devkit.chartkit.set.SetValidationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A consumer's own record, deliberately not a ChartKit type. */
private data class Person(val id: String, val name: String)

/**
 * The arithmetic every claim a set diagram makes rests on.
 *
 * Pure functions over numbers, so these run on the JVM and say precisely which
 * rule broke. A diagram whose regions do not add up looks fine and is wrong,
 * which is why the analysis is tested here rather than by looking at a picture.
 */
class SetAnalyzerTest {

    private fun sets(vararg pairs: Pair<String, Double>) =
        pairs.map { SetDefinition(it.first, it.first.uppercase(), it.second) }

    // ---- exclusive regions -----------------------------------------------

    @Test
    fun twoSetsProduceThreeRegionsThatAddUpToTheUnion() {
        val data = SetAnalyzer.analyze(
            sets("a" to 100.0, "b" to 80.0),
            listOf(SetIntersection(setOf("a", "b"), 30.0)),
        )

        assertEquals(70.0, data.exclusiveValue("a"), 1e-9)
        assertEquals(50.0, data.exclusiveValue("b"), 1e-9)
        assertEquals(30.0, data.region(setOf("a", "b"))!!.value, 1e-9)
        // The property that makes the picture honest: the regions partition the
        // union, so the numbers in it sum to the number of things there are.
        assertEquals(150.0, data.union, 1e-9)
        assertEquals(data.union, data.regions.sumOf { it.value }, 1e-9)
    }

    @Test
    fun aSetsTotalIsNotItsExclusiveRegion() {
        val data = SetAnalyzer.analyze(
            sets("a" to 100.0, "b" to 80.0),
            listOf(SetIntersection(setOf("a", "b"), 30.0)),
        )

        // The distinction the whole model turns on. "A" is 100; "A only" is 70.
        assertEquals(100.0, data.set("a")!!.value, 1e-9)
        assertEquals(70.0, data.exclusiveValue("a"), 1e-9)
        assertEquals(SetRegionKind.Exclusive, data.region(setOf("a"))!!.kind)
    }

    @Test
    fun threeSetsUseInclusionExclusionThroughout() {
        val data = SetAnalyzer.analyze(
            sets("android" to 200.0, "ios" to 160.0, "web" to 240.0),
            listOf(
                SetIntersection(setOf("android", "ios"), 70.0),
                SetIntersection(setOf("android", "web"), 100.0),
                SetIntersection(setOf("ios", "web"), 80.0),
                SetIntersection(setOf("android", "ios", "web"), 45.0),
            ),
        )

        // Android only = 200 − 70 − 100 + 45
        assertEquals(75.0, data.exclusiveValue("android"), 1e-9)
        assertEquals(55.0, data.exclusiveValue("ios"), 1e-9)
        assertEquals(105.0, data.exclusiveValue("web"), 1e-9)
        // Android and iOS but not Web = 70 − 45
        assertEquals(25.0, data.region(setOf("android", "ios"))!!.value, 1e-9)
        assertEquals(55.0, data.region(setOf("android", "web"))!!.value, 1e-9)
        assertEquals(35.0, data.region(setOf("ios", "web"))!!.value, 1e-9)
        assertEquals(45.0, data.region(setOf("android", "ios", "web"))!!.value, 1e-9)
        assertEquals(395.0, data.union, 1e-9)
    }

    @Test
    fun aPairwiseRegionKeepsBothItsExclusiveAndItsTotalValue() {
        val data = SetAnalyzer.analyze(
            sets("a" to 100.0, "b" to 100.0, "c" to 100.0),
            listOf(
                SetIntersection(setOf("a", "b"), 40.0),
                SetIntersection(setOf("a", "b", "c"), 10.0),
            ),
        )
        val region = data.region(setOf("a", "b"))!!

        // 40 people have both A and B; 30 of them have *only* A and B. A tooltip
        // over the region must say 30, and a caller asking about the
        // intersection must get 40.
        assertEquals(30.0, region.value, 1e-9)
        assertEquals(40.0, region.totalValue, 1e-9)
    }

    @Test
    fun intersectionOrderDoesNotMatter() {
        val forwards = SetAnalyzer.analyze(
            sets("a" to 10.0, "b" to 10.0),
            listOf(SetIntersection(setOf("a", "b"), 4.0)),
        )
        val backwards = SetAnalyzer.analyze(
            sets("a" to 10.0, "b" to 10.0),
            listOf(SetIntersection(setOf("b", "a"), 4.0)),
        )

        assertEquals(forwards.union, backwards.union, 1e-9)
        assertEquals(
            forwards.region(setOf("b", "a"))!!.value,
            backwards.region(setOf("a", "b"))!!.value,
            1e-9,
        )
    }

    @Test
    fun anUnstatedIntersectionIsEmptyRatherThanUnknown() {
        val data = SetAnalyzer.analyze(sets("a" to 10.0, "b" to 10.0))

        assertEquals(0.0, data.total(setOf("a", "b")), 1e-9)
        assertEquals(20.0, data.union, 1e-9)
    }

    @Test
    fun aRegionTheModelDoesNotCarryIsReportedAsEmptyNotAsMissing() {
        val data = SetAnalyzer.analyze(sets("a" to 10.0, "b" to 10.0, "c" to 10.0))

        // Venn semantics draw this region; a reader tapping it deserves "0".
        val triple = data.regionOrEmpty(setOf("a", "b", "c"))
        assertEquals(0.0, triple.value, 1e-9)
        assertTrue(triple.isTheoretical)
        assertNull(data.region(setOf("a", "b", "c")))
    }

    // ---- relationships ---------------------------------------------------

    @Test
    fun relationshipsAreDerivedFromCardinalitiesAlone() {
        val data = SetAnalyzer.analyze(
            sets("animals" to 100.0, "mammals" to 40.0, "plants" to 60.0),
            listOf(SetIntersection(setOf("animals", "mammals"), 40.0)),
        )

        assertEquals(SetRelationship.Contains, data.relationships.between("animals", "mammals"))
        assertEquals(SetRelationship.ContainedBy, data.relationships.between("mammals", "animals"))
        assertEquals(SetRelationship.Disjoint, data.relationships.between("animals", "plants"))
    }

    @Test
    fun partialOverlapIsNotContainment() {
        val data = SetAnalyzer.analyze(
            sets("a" to 100.0, "b" to 80.0),
            listOf(SetIntersection(setOf("a", "b"), 79.0)),
        )

        assertEquals(SetRelationship.Overlaps, data.relationships.between("a", "b"))
    }

    @Test
    fun twoSetsSharingEverythingAreEqualNotMutuallyContaining() {
        val data = SetAnalyzer.analyze(
            sets("a" to 50.0, "b" to 50.0),
            listOf(SetIntersection(setOf("a", "b"), 50.0)),
        )

        // Reported as containment in both directions, the Euler forest would be
        // cyclic and the layout would recurse for ever.
        assertEquals(SetRelationship.Equal, data.relationships.between("a", "b"))
        assertEquals(SetRelationship.Equal, data.relationships.between("b", "a"))
    }

    @Test
    fun theSmallestContainerIsTheParent() {
        val data = SetAnalyzer.analyze(
            sets("world" to 100.0, "country" to 50.0, "city" to 10.0),
            containments = listOf(
                SetContainment("world", "country"),
                SetContainment("country", "city"),
            ),
        )

        // The city is inside both, and nesting must use the tighter one.
        assertEquals("country", data.relationships.parent("city") { data.set(it)!!.value })
        assertEquals(listOf("country", "world"), data.relationships.containers("city").sorted())
    }

    // ---- containment -----------------------------------------------------

    @Test
    fun containmentIsTransitiveWithoutBeingRestated() {
        val data = SetAnalyzer.analyze(
            sets("a" to 100.0, "b" to 50.0, "c" to 20.0),
            containments = listOf(SetContainment("a", "b"), SetContainment("b", "c")),
        )

        assertEquals(SetRelationship.Contains, data.relationships.between("a", "c"))
        assertEquals(20.0, data.total(setOf("a", "c")), 1e-9)
        assertEquals(20.0, data.total(setOf("a", "b", "c")), 1e-9)
    }

    @Test
    fun containmentPropagatesAnOverlapUpTheTree() {
        // Northern Ireland is in the UK and in the island of Ireland; the UK is
        // in the British Islands. It follows that the British Islands and the
        // island of Ireland share at least those ten — and a layout that did
        // not know it would draw them apart.
        val data = SetAnalyzer.analyze(
            sets("islands" to 70.0, "uk" to 60.0, "ireland" to 30.0),
            listOf(SetIntersection(setOf("uk", "ireland"), 10.0)),
            listOf(SetContainment("islands", "uk")),
        )

        assertEquals(10.0, data.total(setOf("islands", "ireland")), 1e-9)
        assertEquals(SetRelationship.Overlaps, data.relationships.between("islands", "ireland"))
    }

    @Test
    fun nestedDataNeedsNoHandWrittenLattice() {
        // Four levels stated in three lines. Without derived containment this
        // would need every triple and quadruple written out by hand.
        val data = SetAnalyzer.analyze(
            sets("a" to 100.0, "b" to 70.0, "c" to 50.0, "d" to 20.0),
            containments = listOf(
                SetContainment("a", "b"),
                SetContainment("b", "c"),
                SetContainment("c", "d"),
            ),
        )

        assertTrue(data.diagnostics.none { it.severity == SetDiagnosticSeverity.Error })

        // Nesting means no set has an exclusive region except the outermost:
        // everything in B is also in A, so "B only" is empty and the ring
        // between them is the region `{a, b}`.
        assertEquals(30.0, data.exclusiveValue("a"), 1e-9)
        assertEquals(0.0, data.exclusiveValue("b"), 1e-9)
        assertEquals(20.0, data.region(setOf("a", "b"))!!.value, 1e-9)
        assertEquals(30.0, data.region(setOf("a", "b", "c"))!!.value, 1e-9)
        assertEquals(20.0, data.region(setOf("a", "b", "c", "d"))!!.value, 1e-9)
        assertEquals(100.0, data.union, 1e-9)
    }

    @Test
    fun circularContainmentIsReportedRatherThanFollowed() {
        val failure = runCatching {
            SetAnalyzer.analyze(
                sets("a" to 10.0, "b" to 10.0),
                containments = listOf(SetContainment("a", "b"), SetContainment("b", "a")),
            )
        }.exceptionOrNull()

        assertTrue(failure is SetDataException)
        assertTrue(failure!!.message!!.contains("circular"))
    }

    // ---- validation ------------------------------------------------------

    @Test
    fun anIntersectionLargerThanItsSetsIsRejected() {
        val failure = runCatching {
            SetAnalyzer.analyze(
                sets("a" to 10.0, "b" to 20.0),
                listOf(SetIntersection(setOf("a", "b"), 50.0)),
            )
        }.exceptionOrNull()

        assertTrue(failure is SetDataException)
        assertTrue(failure!!.message!!.contains("exceeds"))
    }

    @Test
    fun aTripleLargerThanAPairIsRejected() {
        val failure = runCatching {
            SetAnalyzer.analyze(
                sets("a" to 100.0, "b" to 100.0, "c" to 100.0),
                listOf(
                    SetIntersection(setOf("a", "b"), 10.0),
                    SetIntersection(setOf("a", "b", "c"), 20.0),
                ),
            )
        }.exceptionOrNull()

        // Adding a set to a combination can only remove items from it.
        assertTrue(failure is SetDataException)
    }

    @Test
    fun overlapsThatIndividuallyFitButTogetherCannotAreRejected() {
        val failure = runCatching {
            SetAnalyzer.analyze(
                sets("a" to 100.0, "b" to 100.0, "c" to 100.0),
                listOf(
                    SetIntersection(setOf("a", "b"), 90.0),
                    SetIntersection(setOf("a", "c"), 90.0),
                    SetIntersection(setOf("b", "c"), 90.0),
                ),
            )
        }.exceptionOrNull()

        // Every pair fits its sets; together they demand more items than exist.
        // Monotonicity alone would have let this through.
        assertTrue(failure is SetDataException)
        assertTrue(failure!!.message!!.contains("impossible"))
    }

    @Test
    fun lenientModeDrawsWhatItCanAndSaysWhatWasWrong() {
        val data = SetAnalyzer.analyze(
            sets("a" to 10.0, "b" to 20.0),
            listOf(SetIntersection(setOf("a", "b"), 50.0)),
            mode = SetValidationMode.Lenient,
        )

        assertTrue(data.diagnostics.any { it.severity == SetDiagnosticSeverity.Error })
        // Still drawable: a developer looking for the bad number is better served
        // by a diagram and a diagnostic than by a blank screen.
        assertEquals(2, data.sets.size)
        assertTrue(data.regions.all { it.value >= 0.0 })
    }

    @Test
    fun aNegativeCardinalityIsRejected() {
        val failure = runCatching {
            SetAnalyzer.analyze(sets("a" to -5.0))
        }.exceptionOrNull()

        assertTrue(failure is SetDataException)
    }

    @Test
    fun aNonFiniteValueIsRejected() {
        val failure = runCatching {
            SetAnalyzer.analyze(
                sets("a" to 10.0, "b" to 10.0),
                listOf(SetIntersection(setOf("a", "b"), Double.NaN)),
            )
        }.exceptionOrNull()

        assertTrue(failure is SetDataException)
    }

    @Test
    fun aDuplicateSetIdIsRejected() {
        val failure = runCatching {
            SetAnalyzer.analyze(
                listOf(
                    SetDefinition("a", "First", 10.0),
                    SetDefinition("a", "Second", 20.0),
                ),
            )
        }.exceptionOrNull()

        // Ids are identity. Two sets sharing one would make every intersection
        // ambiguous.
        assertTrue(failure is SetDataException)
        assertTrue(failure!!.message!!.contains("Duplicate"))
    }

    @Test
    fun anUnknownSetIdInAnIntersectionIsRejected() {
        val failure = runCatching {
            SetAnalyzer.analyze(
                sets("a" to 10.0),
                listOf(SetIntersection(setOf("a", "ghost"), 5.0)),
            )
        }.exceptionOrNull()

        assertTrue(failure is SetDataException)
        assertTrue(failure!!.message!!.contains("ghost"))
    }

    @Test
    fun theSameCombinationStatedTwiceWithDifferentValuesIsRejected() {
        val failure = runCatching {
            SetAnalyzer.analyze(
                sets("a" to 10.0, "b" to 10.0),
                listOf(
                    SetIntersection(setOf("a", "b"), 4.0),
                    SetIntersection(setOf("b", "a"), 6.0),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is SetDataException)
    }

    @Test
    fun anEmptyDiagramAnalysesToNothingAndWarns() {
        val data = SetAnalyzer.analyze(emptyList(), mode = SetValidationMode.Lenient)

        assertTrue(data.isEmpty)
        assertEquals(0.0, data.union, 1e-9)
    }

    // ---- collections -----------------------------------------------------

    private fun people(vararg ids: String) = ids.map { Person(it, it.uppercase()) }

    @Test
    fun intersectionsAreCountedFromRealCollections() {
        val data = SetAnalyzer.fromItems(
            listOf(
                SetItems("android", "Android", people("a", "b", "c", "d")),
                SetItems("ios", "iOS", people("c", "d", "e")),
            ),
            key = Person::id,
        )

        assertEquals(4.0, data.set("android")!!.value, 1e-9)
        assertEquals(3.0, data.set("ios")!!.value, 1e-9)
        assertEquals(2.0, data.total(setOf("android", "ios")), 1e-9)
        assertEquals(2.0, data.exclusiveValue("android"), 1e-9)
        assertEquals(1.0, data.exclusiveValue("ios"), 1e-9)
        assertEquals(5.0, data.union, 1e-9)
    }

    @Test
    fun theseAreSetsNotMultisets() {
        val data = SetAnalyzer.fromItems(
            listOf(SetItems("a", "A", people("x", "x", "x", "y"))),
            key = Person::id,
        )

        // Counting the repeats would make the union exceed the number of
        // distinct people, and every percentage derived from it would be wrong.
        assertEquals(2.0, data.set("a")!!.value, 1e-9)
        assertEquals(2.0, data.union, 1e-9)
    }

    @Test
    fun theOrderTheSetsAreGivenInDoesNotChangeTheCounts() {
        val forwards = SetAnalyzer.fromItems(
            listOf(
                SetItems("a", "A", people("1", "2", "3")),
                SetItems("b", "B", people("2", "3", "4")),
            ),
            key = Person::id,
        )
        val backwards = SetAnalyzer.fromItems(
            listOf(
                SetItems("b", "B", people("2", "3", "4")),
                SetItems("a", "A", people("1", "2", "3")),
            ),
            key = Person::id,
        )

        assertEquals(forwards.union, backwards.union, 1e-9)
        assertEquals(
            forwards.total(setOf("a", "b")),
            backwards.total(setOf("a", "b")),
            1e-9,
        )
    }

    @Test
    fun anEmptyCollectionIsASetWithNothingInIt() {
        val data = SetAnalyzer.fromItems(
            listOf(
                SetItems("a", "A", people("1", "2")),
                SetItems("b", "B", emptyList()),
            ),
            key = Person::id,
        )

        assertEquals(0.0, data.set("b")!!.value, 1e-9)
        assertEquals(2.0, data.union, 1e-9)
        assertEquals(SetRelationship.Disjoint, data.relationships.between("a", "b"))
    }

    @Test
    fun anItemInEverySetProducesOneFullIntersection() {
        val data = SetAnalyzer.fromItems(
            listOf(
                SetItems("a", "A", people("1")),
                SetItems("b", "B", people("1")),
                SetItems("c", "C", people("1")),
            ),
            key = Person::id,
        )

        assertEquals(1.0, data.union, 1e-9)
        assertEquals(1.0, data.total(setOf("a", "b", "c")), 1e-9)
        assertEquals(1.0, data.region(setOf("a", "b", "c"))!!.value, 1e-9)
        assertEquals(0.0, data.exclusiveValue("a"), 1e-9)
    }

    @Test
    fun disjointCollectionsShareNothing() {
        val data = SetAnalyzer.fromItems(
            listOf(
                SetItems("a", "A", people("1", "2")),
                SetItems("b", "B", people("3", "4")),
            ),
            key = Person::id,
        )

        assertEquals(0.0, data.total(setOf("a", "b")), 1e-9)
        assertNull(data.region(setOf("a", "b")))
        assertEquals(4.0, data.union, 1e-9)
    }

    @Test
    fun aCollectionDiagramAndACardinalityDiagramAgree() {
        val counted = SetAnalyzer.fromItems(
            listOf(
                SetItems("a", "A", people("1", "2", "3", "4")),
                SetItems("b", "B", people("3", "4", "5")),
            ),
            key = Person::id,
        )
        val stated = SetAnalyzer.analyze(
            sets("a" to 4.0, "b" to 3.0),
            listOf(SetIntersection(setOf("a", "b"), 2.0)),
        )

        // Two routes into the same model. If they ever disagreed, one of the two
        // inclusion–exclusion directions would be wrong.
        assertEquals(stated.union, counted.union, 1e-9)
        assertEquals(stated.exclusiveValue("a"), counted.exclusiveValue("a"), 1e-9)
        assertEquals(
            stated.region(setOf("a", "b"))!!.value,
            counted.region(setOf("a", "b"))!!.value,
            1e-9,
        )
    }

    @Test
    fun aDuplicateSetIdInCollectionsIsReported() {
        val failure = runCatching {
            SetAnalyzer.fromItems(
                listOf(
                    SetItems("a", "First", people("1")),
                    SetItems("a", "Second", people("2")),
                ),
                key = Person::id,
            )
        }.exceptionOrNull()

        assertTrue(failure is SetDataException)
    }

    @Test
    fun tooManySetsIsRefusedWithAnExplanation() {
        val many = (1..SetAnalyzer.MAX_ANALYZED_SETS + 1).map {
            SetItems("s$it", "S$it", people("x$it"))
        }

        val failure = runCatching { SetAnalyzer.fromItems(many, key = Person::id) }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("exponential"))
    }
}
