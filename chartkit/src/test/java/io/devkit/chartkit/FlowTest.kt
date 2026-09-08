package io.devkit.chartkit

import io.devkit.chartkit.flow.SankeyLayout
import io.devkit.chartkit.flow.SankeyLayoutSpec
import io.devkit.chartkit.flow.SankeyValidation
import io.devkit.chartkit.flow.buildSankeyGraph
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.transform.FunnelTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FlowNode(val id: String, val name: String = id)
private class Flow(val from: String, val to: String, val amount: Double)

private val nodes = listOf(
    FlowNode("search", "Search"),
    FlowNode("product", "Product"),
    FlowNode("checkout", "Checkout"),
    FlowNode("purchase", "Purchase"),
)

private fun graph(
    links: List<Flow>,
    validation: SankeyValidation = SankeyValidation.Drop,
) = buildSankeyGraph(
    nodes = nodes,
    links = links,
    nodeId = { it.id },
    nodeLabel = { it.name },
    source = { it.from },
    target = { it.to },
    value = { it.amount },
    validation = validation,
)

/** Sankey normalisation: columns, weights, and every way the input can be wrong. */
class SankeyGraphTest {

    private val chain = listOf(
        Flow("search", "product", 100.0),
        Flow("product", "checkout", 60.0),
        Flow("checkout", "purchase", 40.0),
    )

    @Test
    fun `nodes are assigned to columns by distance from a source`() {
        val result = graph(chain)
        assertEquals(0, result.depths[0])
        assertEquals(1, result.depths[1])
        assertEquals(2, result.depths[2])
        assertEquals(3, result.depths[3])
        assertEquals(4, result.columnCount)
    }

    @Test
    fun `a node's throughput is the larger of its two sides`() {
        val result = graph(chain)
        // Search only sends, so its throughput is its outflow. Product takes
        // 100 in and sends 60 on, so it is sized by the larger of the two —
        // which is what stops a node that loses flow being drawn narrower than
        // the band arriving at it.
        assertEquals(100.0, result.nodes[0].throughput, 1e-9)
        assertEquals(0.0, result.nodes[0].incoming, 1e-9)
        assertEquals(100.0, result.nodes[1].incoming, 1e-9)
        assertEquals(60.0, result.nodes[1].outgoing, 1e-9)
        assertEquals(100.0, result.nodes[1].throughput, 1e-9)
        assertEquals(60.0, result.nodes[2].incoming, 1e-9)
    }

    @Test
    fun `a skipping link does not drag its target backwards`() {
        // Longest path, not shortest: without it, "search -> purchase" would put
        // Purchase in column 1 and the diagram would run right to left.
        val result = graph(chain + Flow("search", "purchase", 5.0))
        assertEquals(3, result.depths[3])
    }

    @Test
    fun `an unknown node reference is dropped and counted`() {
        val result = graph(chain + Flow("search", "nowhere", 1.0))
        assertEquals(1, result.unknownReferences)
        assertEquals(3, result.links.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unknown reference can be rejected instead`() {
        graph(chain + Flow("search", "nowhere", 1.0), SankeyValidation.Reject)
    }

    @Test
    fun `a self-link is dropped`() {
        val result = graph(chain + Flow("product", "product", 3.0))
        assertEquals(1, result.selfLinks)
    }

    @Test
    fun `a non-positive weight is dropped`() {
        val result = graph(chain + Flow("search", "checkout", 0.0))
        assertEquals(1, result.invalidValues)
    }

    @Test
    fun `a negative weight is dropped`() {
        val result = graph(chain + Flow("search", "checkout", -4.0))
        assertEquals(1, result.invalidValues)
    }

    @Test
    fun `a cycle is cut and the rest still lays out`() {
        val result = graph(chain + Flow("purchase", "search", 2.0))
        assertEquals(1, result.cyclicLinks)
        assertEquals(3, result.links.size)
        assertEquals(4, result.columnCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a cycle can be rejected instead`() {
        graph(chain + Flow("purchase", "search", 2.0), SankeyValidation.Reject)
    }

    @Test
    fun `cutting a cycle is deterministic`() {
        val cyclic = chain + Flow("purchase", "search", 2.0)
        val first = graph(cyclic).links.map { it.index }
        val second = graph(cyclic).links.map { it.index }
        assertEquals(first, second)
    }

    @Test
    fun `neighbours are found in both directions`() {
        val result = graph(chain)
        assertEquals(setOf(0, 2), result.neighbours(1))
    }

    @Test
    fun `an empty graph is empty rather than an error`() {
        assertTrue(graph(emptyList()).isEmpty)
    }
}

/** Sankey layout: node sizes, band widths, and the geometry a tap resolves against. */
class SankeyLayoutTest {

    private val bounds = ChartRect(0f, 0f, 400f, 200f)

    private val result = graph(
        listOf(
            Flow("search", "product", 100.0),
            Flow("product", "checkout", 60.0),
            Flow("checkout", "purchase", 40.0),
        ),
    )

    private val geometry = SankeyLayout.layout(
        graph = result,
        bounds = bounds,
        spec = SankeyLayoutSpec(nodeWidth = 10f, nodePadding = 8f, iterations = 4),
    )

    @Test
    fun `every node gets a box`() {
        assertEquals(4, geometry.boxes.size)
    }

    @Test
    fun `every link gets a band`() {
        assertEquals(3, geometry.bands.size)
    }

    @Test
    fun `columns are evenly spaced across the bounds`() {
        val xs = geometry.boxes.sortedBy { it.column }.map { it.bounds.left }
        assertEquals(bounds.left, xs.first(), 0.01f)
        assertEquals(bounds.right - 10f, xs.last(), 0.01f)
    }

    @Test
    fun `box height is proportional to throughput`() {
        val search = geometry.boxOf(0)!!
        val checkout = geometry.boxOf(2)!!
        // Search carries 100, Checkout 60.
        assertEquals(100.0 / 60.0, (search.bounds.height / checkout.bounds.height).toDouble(), 0.02)
    }

    @Test
    fun `band thickness matches its weight at the same scale as the boxes`() {
        val search = geometry.boxOf(0)!!
        val band = geometry.bands.first { it.linkIndex == 0 }
        assertEquals(search.bounds.height, band.thicknessAtSource, 0.5f)
    }

    @Test
    fun `boxes stay inside the bounds`() {
        geometry.boxes.forEach { box ->
            assertTrue(box.bounds.top >= bounds.top - 0.5f)
            assertTrue(box.bounds.bottom <= bounds.bottom + 0.5f)
        }
    }

    @Test
    fun `the layout is stable across runs`() {
        val again = SankeyLayout.layout(
            graph = result,
            bounds = bounds,
            spec = SankeyLayoutSpec(nodeWidth = 10f, nodePadding = 8f, iterations = 4),
        )
        assertEquals(
            geometry.boxes.map { it.bounds },
            again.boxes.map { it.bounds },
        )
    }

    @Test
    fun `a band is hit at its own centre and missed well away from it`() {
        val band = geometry.bands.first()
        val midX = (band.sourceX + band.targetX) / 2f
        val centre = SankeyLayout.smoothstep(band.sourceCenter, band.targetCenter, 0.5f)
        assertNotNull(SankeyLayout.hitTestBand(geometry.bands, midX, centre))
        assertNull(SankeyLayout.hitTestBand(geometry.bands, midX, centre + 500f))
    }

    @Test
    fun `an empty graph produces no geometry`() {
        val empty = SankeyLayout.layout(graph(emptyList()), bounds)
        assertTrue(empty.boxes.isEmpty())
    }
}

/** Funnel metrics: the four figures, including the awkward ones. */
class FunnelTransformTest {

    private class Stage(val name: String, val users: Double)

    private fun stages(vararg values: Pair<String, Double>) =
        FunnelTransform.resolve(
            data = values.map { Stage(it.first, it.second) },
            label = { it.name },
            value = { it.users },
        )

    @Test
    fun `the first stage is the whole and converts from nothing`() {
        val first = stages("Visited" to 1000.0, "Signed up" to 400.0).first()
        assertEquals(1.0, first.fractionOfFirst, 1e-9)
        assertNull(first.conversionFromPrevious)
        assertNull(first.dropOffFromPrevious)
    }

    @Test
    fun `each stage's share is measured against the first`() {
        val result = stages("A" to 1000.0, "B" to 400.0, "C" to 100.0)
        assertEquals(0.4, result[1].fractionOfFirst, 1e-9)
        assertEquals(0.1, result[2].fractionOfFirst, 1e-9)
    }

    @Test
    fun `conversion is measured against the previous stage`() {
        val result = stages("A" to 1000.0, "B" to 400.0, "C" to 100.0)
        assertEquals(0.4, result[1].conversionFromPrevious!!, 1e-9)
        assertEquals(0.25, result[2].conversionFromPrevious!!, 1e-9)
    }

    @Test
    fun `drop-off is the complement of conversion, in both forms`() {
        val result = stages("A" to 1000.0, "B" to 400.0)
        assertEquals(0.6, result[1].dropOffFromPrevious!!, 1e-9)
        assertEquals(600.0, result[1].dropOffCount!!, 1e-9)
    }

    @Test
    fun `an increasing stage is reported honestly rather than clamped`() {
        // Real funnels go up. Clamping would report a loss where there was a gain.
        val result = stages("A" to 100.0, "B" to 150.0)
        assertEquals(1.5, result[1].conversionFromPrevious!!, 1e-9)
        assertEquals(-0.5, result[1].dropOffFromPrevious!!, 1e-9)
        assertTrue(result[1].dropOffCount!! < 0.0)
    }

    @Test
    fun `a zero previous stage has no conversion rather than an infinity`() {
        val result = stages("A" to 0.0, "B" to 10.0)
        assertNull(result[1].conversionFromPrevious)
    }

    @Test
    fun `a zero first stage leaves every share at zero rather than NaN`() {
        val result = stages("A" to 0.0, "B" to 0.0)
        result.forEach { assertEquals(0.0, it.fractionOfFirst, 1e-9) }
    }

    @Test
    fun `monotonicity is reported, not assumed`() {
        assertTrue(FunnelTransform.isMonotonic(stages("A" to 10.0, "B" to 5.0)))
        assertTrue(!FunnelTransform.isMonotonic(stages("A" to 10.0, "B" to 15.0)))
    }

    @Test
    fun `overall conversion is the last stage over the first`() {
        val result = stages("A" to 1000.0, "B" to 400.0, "C" to 100.0)
        assertEquals(0.1, FunnelTransform.overallConversion(result)!!, 1e-9)
    }

    @Test
    fun `an empty funnel has no stages`() {
        assertTrue(stages().isEmpty())
        assertNull(FunnelTransform.overallConversion(emptyList()))
    }
}
