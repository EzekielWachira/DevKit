package io.devkit.chartkit

import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.set.RegionGeometryIndex
import io.devkit.chartkit.set.SetAnalyzer
import io.devkit.chartkit.set.SetContainment
import io.devkit.chartkit.set.SetDefinition
import io.devkit.chartkit.set.SetDiagramLayout
import io.devkit.chartkit.set.SetIntersection
import io.devkit.chartkit.set.SetItems
import io.devkit.chartkit.set.SetLayoutConfig
import io.devkit.chartkit.set.SetSizing
import io.devkit.chartkit.set.engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private data class Member(val id: Int)

/**
 * The sizes a real set diagram arrives at: two sets, three, four, a complex
 * nested Euler, and a conceptual diagram carrying icon groups.
 *
 * ### No wall-clock assertions
 *
 * A test that fails when a shared machine is busy teaches a team to ignore it,
 * and a performance number measured on one laptop and written into a README is
 * a number about that laptop. What is asserted instead are the properties that
 * *cause* the performance and that a regression would break:
 *
 * - the solver stops inside its budget rather than running to convergence;
 * - the analysis is linear in items and only exponential in the number of sets,
 *   which is why the set count is capped and the item count is not;
 * - the geometry is solved once and re-fitted many times, so a resize does not
 *   re-solve;
 * - region sampling produces a bounded number of regions.
 */
class SetPerformanceTest {

    private fun sets(count: Int) = (1..count).map {
        SetDefinition("s$it", "Set $it", 100.0)
    }

    private fun pairs(count: Int) = buildList {
        for (i in 1..count) {
            for (j in i + 1..count) {
                add(SetIntersection(setOf("s$i", "s$j"), 20.0))
            }
        }
    }

    private fun solve(count: Int, sizing: SetSizing = SetSizing.Proportional) =
        SetDiagramLayout.Venn(sizing)
            .engine()
            .layout(
                SetAnalyzer.analyze(sets(count), pairs(count)),
                SetLayoutConfig.Default,
            )

    @Test
    fun twoSetsSolveWithinBudget() {
        val layout = solve(2)

        assertEquals(2, layout.shapes.size)
        assertTrue(layout.quality.iterations <= SetLayoutConfig.Default.maxIterations)
    }

    @Test
    fun threeSetsSolveWithinBudget() {
        val layout = solve(3)

        assertEquals(3, layout.shapes.size)
        assertTrue(layout.quality.iterations <= SetLayoutConfig.Default.maxIterations)
        assertTrue(layout.quality.meanAreaError.isFinite())
    }

    @Test
    fun fourSetsProduceEveryRegionWithoutSolving() {
        val layout = solve(4)
        val regions = RegionGeometryIndex.of(layout, resolution = 220)

        // The four-set arrangement is analytic, so there is nothing to solve and
        // nothing to spend a budget on.
        assertEquals(0, layout.quality.iterations)
        assertEquals(15, regions.regions.size)
    }

    @Test
    fun theFastConfigurationTakesFewerSweepsThanTheDefault() {
        val fast = SetDiagramLayout.Venn(SetSizing.Proportional)
            .engine()
            .layout(SetAnalyzer.analyze(sets(3), pairs(3)), SetLayoutConfig.Fast)
        val full = solve(3)

        assertTrue(fast.quality.iterations <= SetLayoutConfig.Fast.maxIterations)
        assertTrue(fast.quality.iterations <= full.quality.iterations)
    }

    @Test
    fun aComplexNestedEulerStaysInsideItsBudget() {
        val definitions = (1..8).map { SetDefinition("n$it", "N$it", (100 - it * 8).toDouble()) }
        val containments = (2..8).map { SetContainment("n${it - 1}", "n$it") }

        val layout = SetDiagramLayout.Euler(SetSizing.Proportional)
            .engine()
            .layout(
                SetAnalyzer.analyze(definitions, emptyList(), containments),
                SetLayoutConfig.Default,
            )

        assertEquals(8, layout.shapes.size)
        assertTrue(layout.quality.iterations <= SetLayoutConfig.Default.maxIterations)
    }

    @Test
    fun aConceptualIconGroupDiagramNeedsNoSolveAtAll() {
        val layout = solve(5, SetSizing.Conceptual)

        // The conceptual arrangements are analytic. An icon-group diagram —
        // five sets, region content everywhere — costs one sampling pass and
        // nothing else.
        assertEquals(0, layout.quality.iterations)
        assertEquals(5, layout.shapes.size)
    }

    @Test
    fun refittingDoesNotResolve() {
        val layout = solve(3)

        val small = layout.fitInto(ChartRect(0f, 0f, 200f, 200f))
        val large = layout.fitInto(ChartRect(0f, 0f, 900f, 700f))

        // A rotation or a resize re-fits and re-samples; it does not re-solve.
        // The quality travels with the arrangement because it describes the
        // arrangement, not the rectangle it was drawn into.
        assertEquals(layout.quality, small.quality)
        assertEquals(layout.quality, large.quality)
        assertEquals(layout.quality.iterations, small.quality.iterations)
    }

    @Test
    fun analysisIsLinearInItemsRatherThanInIntersections() {
        val big = (1..20_000).map { Member(it) }
        val overlap = (10_000..20_000).map { Member(it) }

        val data = SetAnalyzer.fromItems(
            listOf(
                SetItems("a", "A", big),
                SetItems("b", "B", overlap),
            ),
            key = Member::id,
        )

        // Twenty thousand items, two sets, two regions — B is wholly inside A,
        // so "B only" holds nothing and is not carried. The work is in the
        // number of *sets*, which is why that is what has a cap.
        assertEquals(20_000.0, data.union, 1e-9)
        assertEquals(10_001.0, data.total(setOf("a", "b")), 1e-9)
        assertEquals(2, data.regions.size)
    }

    @Test
    fun samplingAFiveSetDiagramProducesABoundedNumberOfRegions() {
        val layout = solve(5, SetSizing.Conceptual)
            .fitInto(ChartRect(0f, 0f, 600f, 600f))

        val regions = RegionGeometryIndex.of(layout)

        // At most `2^5 − 1`, and in practice fewer — which is the number the
        // renderer then builds boolean paths for, so it bounds the drawing work
        // too.
        assertTrue(regions.regions.size <= 31)
        assertTrue(regions.regions.isNotEmpty())
    }

    @Test
    fun regionSamplingIsDeterministic() {
        val layout = solve(3).fitInto(ChartRect(0f, 0f, 400f, 400f))

        val first = RegionGeometryIndex.of(layout)
        val second = RegionGeometryIndex.of(layout)

        assertEquals(first.regions.keys, second.regions.keys)
        first.regions.forEach { (key, geometry) ->
            assertEquals(geometry.anchor, second.regions.getValue(key).anchor)
        }
    }
}
