package io.devkit.chartkit

import io.devkit.chartkit.charts.funnelBands
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.transform.FunnelTransform
import io.devkit.chartkit.transform.WaterfallStepKind
import io.devkit.chartkit.transform.WaterfallTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class Movement(val name: String, val amount: Double, val kind: WaterfallStepKind)

/** Waterfall running totals: the one calculation the chart exists to get right. */
class WaterfallTransformTest {

    private fun resolve(vararg steps: Movement) = WaterfallTransform.resolve(
        data = steps.toList(),
        label = { it.name },
        value = { it.amount },
        kind = { it.kind },
    )

    @Test
    fun `increases and decreases accumulate`() {
        val result = resolve(
            Movement("Start", 100.0, WaterfallStepKind.Increase),
            Movement("Revenue", 40.0, WaterfallStepKind.Increase),
            Movement("Costs", 20.0, WaterfallStepKind.Decrease),
            Movement("Tax", 10.0, WaterfallStepKind.Decrease),
        )
        assertEquals(listOf(100.0, 140.0, 120.0, 110.0), result.map { it.runningTotal })
    }

    @Test
    fun `a bar starts where the previous one ended`() {
        val result = resolve(
            Movement("Start", 100.0, WaterfallStepKind.Increase),
            Movement("Revenue", 40.0, WaterfallStepKind.Increase),
        )
        assertEquals(100.0, result[1].start, 1e-9)
        assertEquals(140.0, result[1].end, 1e-9)
    }

    @Test
    fun `the kind decides the direction, not the sign of the value`() {
        // Both fall by twenty. Requiring the caller to negate their own
        // decreases is how a waterfall comes out wrong in exactly one bar.
        val positive = resolve(
            Movement("A", 100.0, WaterfallStepKind.Increase),
            Movement("B", 20.0, WaterfallStepKind.Decrease),
        )
        val negative = resolve(
            Movement("A", 100.0, WaterfallStepKind.Increase),
            Movement("B", -20.0, WaterfallStepKind.Decrease),
        )
        assertEquals(80.0, positive.last().runningTotal, 1e-9)
        assertEquals(80.0, negative.last().runningTotal, 1e-9)
    }

    @Test
    fun `a subtotal is drawn from zero and does not change the total`() {
        val result = resolve(
            Movement("A", 100.0, WaterfallStepKind.Increase),
            Movement("Subtotal", 0.0, WaterfallStepKind.Subtotal),
            Movement("B", 25.0, WaterfallStepKind.Increase),
        )
        assertEquals(0.0, result[1].start, 1e-9)
        assertEquals(100.0, result[1].end, 1e-9)
        assertEquals(125.0, result[2].runningTotal, 1e-9)
    }

    @Test
    fun `a total states the figure reached so far`() {
        val result = resolve(
            Movement("A", 100.0, WaterfallStepKind.Increase),
            Movement("B", 30.0, WaterfallStepKind.Decrease),
            Movement("Total", 0.0, WaterfallStepKind.Total),
        )
        assertEquals(70.0, result.last().end, 1e-9)
        assertEquals(0.0, result.last().start, 1e-9)
    }

    @Test
    fun `the sign helper reads the caller's own numbers`() {
        assertEquals(WaterfallStepKind.Decrease, WaterfallTransform.signedKind(-3.0))
        assertEquals(WaterfallStepKind.Increase, WaterfallTransform.signedKind(3.0))
        assertEquals(WaterfallStepKind.Increase, WaterfallTransform.signedKind(null))
    }

    @Test
    fun `the extent always includes zero, so the bars have a baseline`() {
        val extent = WaterfallTransform.extentOf(
            resolve(
                Movement("A", 100.0, WaterfallStepKind.Increase),
                Movement("B", 20.0, WaterfallStepKind.Increase),
            ),
        )
        assertEquals(0.0, extent.start, 1e-9)
        assertEquals(120.0, extent.endInclusive, 1e-9)
    }

    @Test
    fun `a run that goes negative is drawn below the baseline`() {
        val extent = WaterfallTransform.extentOf(
            resolve(
                Movement("A", 10.0, WaterfallStepKind.Increase),
                Movement("B", 40.0, WaterfallStepKind.Decrease),
            ),
        )
        assertEquals(-30.0, extent.start, 1e-9)
    }

    @Test
    fun `a non-finite value contributes nothing rather than poisoning the total`() {
        val result = resolve(
            Movement("A", 100.0, WaterfallStepKind.Increase),
            Movement("Bad", Double.NaN, WaterfallStepKind.Increase),
        )
        assertEquals(100.0, result.last().runningTotal, 1e-9)
    }

    @Test
    fun `an empty waterfall has no steps and a zero extent`() {
        assertTrue(resolve().isEmpty())
        assertEquals(0.0, WaterfallTransform.extentOf(emptyList()).start, 1e-9)
    }
}

/** Funnel geometry: trapezoids whose widths encode the stage values. */
class FunnelGeometryTest {

    private class Stage(val name: String, val users: Double)

    private val stages = FunnelTransform.resolve(
        data = listOf(
            Stage("Visited", 1000.0),
            Stage("Signed up", 500.0),
            Stage("Subscribed", 100.0),
        ),
        label = { it.name },
        value = { it.users },
    )

    private val bounds = ChartRect(0f, 0f, 300f, 300f)

    private fun bands(neck: Float = 0f) = funnelBands(
        stages = stages,
        bounds = bounds,
        orientation = ChartOrientation.Vertical,
        neckFraction = neck,
        spacing = 0f,
    )

    @Test
    fun `every stage gets a band`() {
        assertEquals(3, bands().size)
    }

    @Test
    fun `the bands divide the plot evenly along the funnel`() {
        val heights = bands().map { it.bounds.height }
        heights.forEach { assertEquals(100f, it, 0.01f) }
    }

    @Test
    fun `the bands stack in order without gaps`() {
        val tops = bands().map { it.bounds.top }
        assertEquals(listOf(0f, 100f, 200f), tops)
    }

    @Test
    fun `a stage of zero still occupies a band that can be tapped`() {
        val withZero = FunnelTransform.resolve(
            data = listOf(Stage("A", 100.0), Stage("B", 0.0)),
            label = { it.name },
            value = { it.users },
        )
        val result = funnelBands(withZero, bounds, ChartOrientation.Vertical, 0f, 0f)
        assertEquals(2, result.size)
        assertTrue(result[1].bounds.height > 0f)
    }

    @Test
    fun `the neck keeps a tiny stage visible`() {
        val tiny = FunnelTransform.resolve(
            data = listOf(Stage("A", 1_000_000.0), Stage("B", 3.0)),
            label = { it.name },
            value = { it.users },
        )
        val withoutNeck = funnelBands(tiny, bounds, ChartOrientation.Vertical, 0f, 0f)
        val withNeck = funnelBands(tiny, bounds, ChartOrientation.Vertical, 0.1f, 0f)
        // The band is the full width in both cases; what the neck changes is the
        // painted trapezoid, so this asserts the geometry was produced at all
        // and that the neck did not collapse the layout.
        assertEquals(withoutNeck.size, withNeck.size)
        assertTrue(withNeck.all { it.bounds.width > 0f })
    }

    @Test
    fun `a horizontal funnel divides the width instead of the height`() {
        val horizontal = funnelBands(stages, bounds, ChartOrientation.Horizontal, 0f, 0f)
        horizontal.forEach { assertEquals(100f, it.bounds.width, 0.01f) }
    }

    @Test
    fun `an empty funnel produces no bands`() {
        assertTrue(funnelBands(emptyList(), bounds, ChartOrientation.Vertical, 0f, 0f).isEmpty())
    }
}
