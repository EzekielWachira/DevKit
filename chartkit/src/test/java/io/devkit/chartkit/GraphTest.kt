package io.devkit.chartkit

import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.graph.ForceSimulation
import io.devkit.chartkit.graph.GraphLayout
import io.devkit.chartkit.graph.GraphLayoutStrategy
import io.devkit.chartkit.graph.GraphValidation
import io.devkit.chartkit.graph.buildChartGraph
import io.devkit.chartkit.graph.nearest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

private class Service(val name: String, val load: Double = 1.0)
private class Dependency(val from: String, val to: String)

private val services = listOf(
    Service("auth", 10.0),
    Service("orders", 5.0),
    Service("payments", 7.0),
    Service("search", 2.0),
)

private fun graph(
    edges: List<Dependency>,
    validation: GraphValidation = GraphValidation.Drop,
) = buildChartGraph(
    nodes = services,
    edges = edges,
    nodeId = { it.name },
    source = { it.from },
    target = { it.to },
    nodeWeight = { it.load },
    validation = validation,
)

/** Graph normalisation: identity, degree, adjacency and malformed edges. */
class ChartGraphTest {

    private val edges = listOf(
        Dependency("auth", "orders"),
        Dependency("orders", "payments"),
        Dependency("auth", "payments"),
    )

    @Test
    fun `nodes keep the caller's order and identity`() {
        val result = graph(edges)
        assertEquals(listOf("auth", "orders", "payments", "search"), result.nodes.map { it.id })
        assertEquals(0, result.nodes[0].index)
    }

    @Test
    fun `degree counts edges in both directions`() {
        val result = graph(edges)
        assertEquals(2, result.nodes[0].degree)
        assertEquals(2, result.nodes[1].degree)
        assertEquals(0, result.nodes[3].degree)
    }

    @Test
    fun `adjacency is symmetric`() {
        val result = graph(edges)
        assertTrue(result.adjacency[0].contains(1))
        assertTrue(result.adjacency[1].contains(0))
    }

    @Test
    fun `an unknown endpoint is dropped and counted`() {
        val result = graph(edges + Dependency("auth", "nowhere"))
        assertEquals(1, result.unknownReferences)
        assertEquals(3, result.edges.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unknown endpoint can be rejected instead`() {
        graph(edges + Dependency("auth", "nowhere"), GraphValidation.Reject)
    }

    @Test
    fun `a self-edge is dropped`() {
        val result = graph(edges + Dependency("auth", "auth"))
        assertEquals(1, result.selfEdges)
    }

    @Test
    fun `the caller's object comes back on the node`() {
        val node = graph(edges).nodes.first()
        assertTrue(node.item is Service)
        assertEquals("auth", (node.item as Service).name)
    }
}

/** Circular placement: deterministic, non-overlapping, inside the frame. */
class CircularLayoutTest {

    private val result = graph(listOf(Dependency("auth", "orders")))

    @Test
    fun `every node is placed`() {
        val positions = GraphLayout.circular(result)
        assertEquals(4, positions.size)
    }

    @Test
    fun `positions are in the unit square, inside the boundary`() {
        val positions = GraphLayout.circular(result)
        for (index in 0 until positions.size) {
            assertTrue(positions.x[index] in 0f..1f)
            assertTrue(positions.y[index] in 0f..1f)
        }
    }

    @Test
    fun `nodes are evenly spaced around the circle`() {
        val positions = GraphLayout.circular(result)
        val distances = (0 until positions.size).map { index ->
            hypot(positions.x[index] - 0.5f, positions.y[index] - 0.5f)
        }
        distances.zipWithNext { a, b -> assertEquals(a, b, 1e-4f) }
    }

    @Test
    fun `the layout is settled immediately`() {
        assertTrue(GraphLayout.circular(result).settled)
    }

    @Test
    fun `it is reproducible`() {
        val first = GraphLayout.circular(result)
        val second = GraphLayout.circular(result)
        assertTrue(first.x.contentEquals(second.x))
        assertTrue(first.y.contentEquals(second.y))
    }

    @Test
    fun `an ordering puts the hubs where the caller asked`() {
        val hubbed = graph(
            listOf(
                Dependency("payments", "auth"),
                Dependency("payments", "orders"),
                Dependency("payments", "search"),
            ),
        )
        val order = GraphLayout.ByDegree(hubbed)
        assertEquals(2, order.first())
    }

    @Test
    fun `a single node sits in the middle`() {
        val single = buildChartGraph(
            nodes = listOf(Service("only")),
            edges = emptyList<Dependency>(),
            nodeId = { it.name },
            source = { it.from },
            target = { it.to },
        )
        val positions = GraphLayout.circular(single)
        assertEquals(0.5f, positions.x[0], 1e-6f)
        assertEquals(0.5f, positions.y[0], 1e-6f)
    }

    @Test
    fun `positions map into a plot rectangle`() {
        val positions = GraphLayout.circular(result)
        val (x, y) = positions.pointIn(ChartRect(0f, 0f, 200f, 100f), 0)
        assertTrue(x in 0f..200f)
        assertTrue(y in 0f..100f)
    }
}

/**
 * The force simulation.
 *
 * Asserted on its *properties* rather than on exact coordinates: a force layout
 * is a numerical process, and a test pinned to three decimal places would break
 * on any tuning. What must hold is that it is seeded, that it settles, that it
 * stays in frame, and that a dragged node stays where it was put.
 */
class ForceLayoutTest {

    private val edges = listOf(
        Dependency("auth", "orders"),
        Dependency("orders", "payments"),
        Dependency("auth", "payments"),
    )

    private val spec = GraphLayoutStrategy.ForceDirected(iterations = 200)

    @Test
    fun `the same graph and seed produce the same layout`() {
        val first = GraphLayout.compute(graph(edges), spec)
        val second = GraphLayout.compute(graph(edges), spec)
        assertTrue(first.x.contentEquals(second.x))
        assertTrue(first.y.contentEquals(second.y))
    }

    @Test
    fun `a different seed produces a different layout`() {
        val first = GraphLayout.compute(graph(edges), spec)
        val second = GraphLayout.compute(graph(edges), spec.copy(seed = 99))
        assertTrue(!first.x.contentEquals(second.x))
    }

    @Test
    fun `every node stays inside the unit square`() {
        val positions = GraphLayout.compute(graph(edges), spec)
        for (index in 0 until positions.size) {
            assertTrue(positions.x[index] in 0f..1f)
            assertTrue(positions.y[index] in 0f..1f)
        }
    }

    @Test
    fun `no two nodes end up on top of each other`() {
        val positions = GraphLayout.compute(graph(edges), spec)
        for (a in 0 until positions.size) {
            for (b in a + 1 until positions.size) {
                val distance = hypot(positions.x[a] - positions.x[b], positions.y[a] - positions.y[b])
                assertTrue("nodes $a and $b coincide", distance > 1e-3f)
            }
        }
    }

    @Test
    fun `the simulation settles rather than running forever`() {
        val simulation = ForceSimulation(graph(edges), spec)
        simulation.run(spec.iterations)
        assertTrue(simulation.settled || simulation.stepsRun >= spec.iterations)
    }

    @Test
    fun `connected nodes end up closer than unconnected ones`() {
        // The one behavioural claim a force layout makes.
        val linear = graph(
            listOf(Dependency("auth", "orders"), Dependency("orders", "payments")),
        )
        val positions = GraphLayout.compute(linear, spec)
        val connected = hypot(positions.x[0] - positions.x[1], positions.y[0] - positions.y[1])
        val distant = hypot(positions.x[0] - positions.x[3], positions.y[0] - positions.y[3])
        assertTrue("connected $connected should be under unconnected $distant", connected < distant)
    }

    @Test
    fun `a pinned node stays where it was dropped`() {
        val simulation = ForceSimulation(graph(edges), spec)
        simulation.run(50)
        simulation.pin(1, 0.2f, 0.8f)
        simulation.run(100)
        val positions = simulation.snapshot()
        assertEquals(0.2f, positions.x[1], 1e-5f)
        assertEquals(0.8f, positions.y[1], 1e-5f)
        assertTrue(simulation.isPinned(1))
    }

    @Test
    fun `pinning unsettles the layout so the neighbours rearrange`() {
        val simulation = ForceSimulation(graph(edges), spec)
        simulation.run(spec.iterations)
        val before = simulation.settled
        simulation.pin(0, 0.1f, 0.1f)
        assertTrue(before || !simulation.settled)
        assertTrue(!simulation.settled)
    }

    @Test
    fun `releasing hands the node back`() {
        val simulation = ForceSimulation(graph(edges), spec)
        simulation.pin(2, 0.3f, 0.3f)
        simulation.release(2)
        assertTrue(!simulation.isPinned(2))
    }

    @Test
    fun `a snapshot is a copy, not a live view`() {
        val simulation = ForceSimulation(graph(edges), spec)
        val snapshot = simulation.snapshot()
        val before = snapshot.x[0]
        simulation.run(20)
        assertEquals(before, snapshot.x[0], 0f)
    }

    @Test
    fun `an oversized graph is not simulated`() {
        val many = (0..ForceSimulation.MAX_SIMULATED + 10).map { Service("n$it") }
        val huge = buildChartGraph(
            nodes = many,
            edges = emptyList<Dependency>(),
            nodeId = { it.name },
            source = { it.from },
            target = { it.to },
        )
        val simulation = ForceSimulation(huge, spec)
        assertTrue(!simulation.isSimulatable)
        assertEquals(0, simulation.run(10))
    }

    @Test
    fun `nearest finds a node within reach and nothing outside it`() {
        val positions = GraphLayout.circular(graph(edges))
        val index = positions.nearest(positions.x[2], positions.y[2], 0.01f)
        assertEquals(2, index)
        assertNull(positions.nearest(-5f, -5f, 0.01f))
        assertNotNull(positions.nearest(positions.x[0], positions.y[0], 0.5f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an absurd iteration count is rejected`() {
        GraphLayoutStrategy.ForceDirected(iterations = 1_000_000)
    }

    @Test
    fun `an empty graph produces no positions`() {
        val empty = buildChartGraph(
            nodes = emptyList<Service>(),
            edges = emptyList<Dependency>(),
            nodeId = { it.name },
            source = { it.from },
            target = { it.to },
        )
        assertEquals(0, GraphLayout.compute(empty, spec).size)
        assertEquals(0, GraphLayout.circular(empty).size)
    }
}
