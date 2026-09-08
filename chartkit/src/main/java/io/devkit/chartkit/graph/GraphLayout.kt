package io.devkit.chartkit.graph

import io.devkit.chartkit.geometry.ChartRect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Node positions, in a unit square.
 *
 * Unit coordinates rather than pixels, so a layout survives the chart being
 * resized: the simulation does not restart when the composable's width changes,
 * and a node dragged to the right stays on the right. The chart maps them into
 * its plot rectangle, and the viewport zooms them.
 *
 * @param x one entry per node, indexed by [GraphNode.index].
 * @param settled true when the layout has reached its stopping condition and
 *   will not move further. A circular layout is settled immediately.
 */
class GraphPositions(
    val x: FloatArray,
    val y: FloatArray,
    val settled: Boolean = true,
) {
    val size: Int get() = x.size

    /** A copy, so a simulation stepping in place cannot mutate a published snapshot. */
    fun copy(settled: Boolean = this.settled): GraphPositions =
        GraphPositions(x.copyOf(), y.copyOf(), settled)

    /** The position of [index] mapped into [bounds]. */
    fun pointIn(bounds: ChartRect, index: Int): Pair<Float, Float> {
        val fx = x.getOrElse(index) { 0.5f }
        val fy = y.getOrElse(index) { 0.5f }
        return (bounds.left + fx * bounds.width) to (bounds.top + fy * bounds.height)
    }

    companion object {
        val Empty: GraphPositions = GraphPositions(FloatArray(0), FloatArray(0))
    }
}

/**
 * Where a graph's nodes go.
 *
 * A strategy rather than a flag on the renderer, and a value rather than an
 * enum, so a caller can tune the simulation without ChartKit growing a
 * parameter per force.
 */
sealed interface GraphLayoutStrategy {

    /**
     * Nodes evenly spaced around a circle, in the caller's own order.
     *
     * Deterministic, instant, and never overlapping — which makes it the right
     * default for a small graph, the right fallback while a simulation warms
     * up, and the only sensible choice for a static or exported render, where
     * "wherever the simulation happened to stop" is not a layout anyone can
     * reproduce.
     *
     * @param order an optional reordering, e.g. by degree so hubs are spread
     *   apart rather than adjacent.
     */
    data class Circular(val order: ((ChartGraph) -> List<Int>)? = null) : GraphLayoutStrategy

    /**
     * A force simulation: nodes repel, edges pull, and a weak centring force
     * keeps the whole thing in frame.
     *
     * @param seed the initial placement's random seed. Fixed by default, so the
     *   same graph lays out the same way every time — a graph that reshuffled
     *   itself on every recomposition would be unusable, and an unseeded layout
     *   cannot be tested.
     * @param iterations how many steps to run before declaring the layout
     *   settled. Bounded, because a simulation that never stops is a chart that
     *   never stops recomposing.
     * @param repulsion how hard nodes push each other apart.
     * @param attraction how hard an edge pulls its endpoints together.
     * @param centering how strongly nodes are drawn toward the middle.
     * @param damping the fraction of velocity carried into the next step.
     * @param settleThreshold the total movement below which the layout is
     *   considered settled and stepping stops early.
     */
    data class ForceDirected(
        val seed: Int = 20_240_101,
        val iterations: Int = 400,
        val repulsion: Double = 0.015,
        val attraction: Double = 0.35,
        val centering: Double = 0.012,
        val damping: Double = 0.82,
        val settleThreshold: Double = 1e-4,
    ) : GraphLayoutStrategy {
        init {
            require(iterations in 1..MAX_ITERATIONS) {
                "A force layout runs between 1 and $MAX_ITERATIONS iterations, was $iterations"
            }
            require(damping in 0.0..1.0) { "Damping must be in [0, 1], was $damping" }
        }

        private companion object {
            /** Beyond this the layout has long since stopped improving. */
            const val MAX_ITERATIONS = 5_000
        }
    }
}

/**
 * Node placement for a [ChartGraph].
 *
 * ### Pure Kotlin, and off the composition
 *
 * Nothing here touches Compose. A force simulation is a loop over every pair of
 * nodes, which is exactly the kind of work that must not happen inside a draw
 * pass or a recomposition — so it is an ordinary function over arrays, driven
 * by [io.devkit.chartkit.state.ChartGraphLayoutState] on a background
 * dispatcher and published as immutable snapshots.
 *
 * ### The consumer's nodes are never touched
 *
 * Positions live in parallel arrays indexed by [GraphNode.index]. The caller's
 * own objects are read for their id and their label and are not written to,
 * which is what lets a graph be laid out from an immutable list.
 */
object GraphLayout {

    /** Positions for [graph] under [strategy], run to completion. */
    fun compute(graph: ChartGraph, strategy: GraphLayoutStrategy): GraphPositions =
        when (strategy) {
            is GraphLayoutStrategy.Circular -> circular(graph, strategy)
            is GraphLayoutStrategy.ForceDirected -> {
                val simulation = ForceSimulation(graph, strategy)
                simulation.run(strategy.iterations)
                simulation.snapshot()
            }
        }

    /** Evenly spaced around a circle, in the caller's order or a supplied one. */
    fun circular(
        graph: ChartGraph,
        strategy: GraphLayoutStrategy.Circular = GraphLayoutStrategy.Circular(),
    ): GraphPositions {
        val count = graph.nodes.size
        if (count == 0) return GraphPositions.Empty
        val x = FloatArray(count)
        val y = FloatArray(count)
        if (count == 1) {
            x[0] = 0.5f
            y[0] = 0.5f
            return GraphPositions(x, y)
        }
        val order = strategy.order?.invoke(graph) ?: graph.nodes.indices.toList()
        order.forEachIndexed { position, node ->
            if (node !in 0 until count) return@forEachIndexed
            val angle = 2.0 * PI * position / order.size
            // Radius 0.42 rather than 0.5: a node drawn *on* the boundary has
            // half of itself outside the plot.
            x[node] = (0.5 + RADIUS * sin(angle)).toFloat()
            y[node] = (0.5 - RADIUS * cos(angle)).toFloat()
        }
        return GraphPositions(x, y)
    }

    /** Orders nodes by degree, descending — hubs spread rather than clustered. */
    val ByDegree: (ChartGraph) -> List<Int> = { graph ->
        graph.nodes.sortedByDescending { it.degree }.map { it.index }
    }

    private const val RADIUS = 0.42
}

/**
 * A stepped force simulation over a graph.
 *
 * Stepped rather than run-to-completion so a caller can publish intermediate
 * snapshots at a sensible rate — a chart that recomposed once per iteration
 * would spend four hundred frames drawing a graph nobody can read yet — and so
 * the work can be cancelled the moment the data changes.
 *
 * The forces are the usual three:
 *
 * ```text
 * repulsion    every pair pushes apart, ~ 1/d²
 * attraction   every edge pulls together, ~ d
 * centering    every node drifts toward the middle
 * ```
 *
 * Repulsion is `O(n²)` per step. That is honest for the graph sizes a phone
 * screen can show anything useful of; a Barnes–Hut tree would be the answer for
 * tens of thousands of nodes, and tens of thousands of nodes is not a picture.
 * [MAX_SIMULATED] caps it, and beyond the cap the circular layout is used
 * instead of quietly taking a second per frame.
 */
class ForceSimulation internal constructor(
    private val graph: ChartGraph,
    private val spec: GraphLayoutStrategy.ForceDirected,
) {
    private val count = graph.nodes.size
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)

    /** Nodes the caller has dragged, which the simulation moves around rather than moves. */
    private val pinned = HashSet<Int>()

    /** True once movement fell below the threshold. */
    var settled: Boolean = false
        private set

    var stepsRun: Int = 0
        private set

    init {
        // Seeded, so the layout is reproducible. An unseeded initial placement
        // makes the same graph look different on every launch, and makes the
        // layout untestable.
        val random = Random(spec.seed)
        for (index in 0 until count) {
            // Started on a circle rather than uniformly at random: a random
            // cloud frequently places two nodes almost on top of each other,
            // where the repulsion term is enormous and the first step throws
            // them off screen.
            val angle = 2.0 * PI * index / max(1, count)
            val jitter = 0.04 * (random.nextDouble() - 0.5)
            x[index] = (0.5 + (0.3 + jitter) * sin(angle)).toFloat()
            y[index] = (0.5 - (0.3 + jitter) * cos(angle)).toFloat()
        }
    }

    /** True when this graph is small enough for the simulation to be worth running. */
    val isSimulatable: Boolean get() = count in 1..MAX_SIMULATED

    /**
     * Runs up to [steps] iterations, stopping early once settled.
     *
     * Returns how many were actually run, so a caller driving the simulation in
     * slices can tell a settled layout from an interrupted one.
     */
    fun run(steps: Int): Int {
        if (!isSimulatable) {
            settled = true
            return 0
        }
        var run = 0
        while (run < steps && !settled && stepsRun < spec.iterations) {
            step()
            run++
        }
        return run
    }

    /** One iteration. */
    @Suppress("NestedBlockDepth")
    fun step() {
        if (settled || count == 0) return
        stepsRun++

        val fx = DoubleArray(count)
        val fy = DoubleArray(count)

        // Repulsion, over each unordered pair once.
        for (a in 0 until count) {
            for (b in a + 1 until count) {
                var dx = (x[a] - x[b]).toDouble()
                var dy = (y[a] - y[b]).toDouble()
                var distanceSquared = dx * dx + dy * dy
                if (distanceSquared < MIN_SEPARATION_SQUARED) {
                    // Two coincident nodes have no direction to separate along.
                    // Nudging them deterministically by their indices keeps the
                    // layout reproducible where a random nudge would not.
                    dx = ((a - b) % 7 + 1) * 1e-3
                    dy = ((a + b) % 5 + 1) * 1e-3
                    distanceSquared = dx * dx + dy * dy
                }
                val distance = sqrt(distanceSquared)
                val force = spec.repulsion / distanceSquared
                val ux = dx / distance
                val uy = dy / distance
                fx[a] += ux * force
                fy[a] += uy * force
                fx[b] -= ux * force
                fy[b] -= uy * force
            }
        }

        // Attraction along the edges, proportional to length: a spring.
        graph.edges.forEach { edge ->
            val a = edge.sourceIndex
            val b = edge.targetIndex
            val dx = (x[b] - x[a]).toDouble()
            val dy = (y[b] - y[a]).toDouble()
            val pull = spec.attraction * edge.weight
            fx[a] += dx * pull
            fy[a] += dy * pull
            fx[b] -= dx * pull
            fy[b] -= dy * pull
        }

        // A weak pull toward the middle, which is what stops disconnected
        // components drifting apart forever under repulsion alone.
        for (index in 0 until count) {
            fx[index] += (0.5 - x[index]) * spec.centering
            fy[index] += (0.5 - y[index]) * spec.centering
        }

        var movement = 0.0
        for (index in 0 until count) {
            if (index in pinned) {
                vx[index] = 0f
                vy[index] = 0f
                continue
            }
            val nvx = (vx[index] + fx[index]) * spec.damping
            val nvy = (vy[index] + fy[index]) * spec.damping
            // Velocity is capped so a pathological configuration cannot throw a
            // node to infinity in one step and take the whole layout with it.
            vx[index] = nvx.coerceIn(-MAX_VELOCITY, MAX_VELOCITY).toFloat()
            vy[index] = nvy.coerceIn(-MAX_VELOCITY, MAX_VELOCITY).toFloat()
            x[index] = (x[index] + vx[index]).coerceIn(MARGIN, 1f - MARGIN)
            y[index] = (y[index] + vy[index]).coerceIn(MARGIN, 1f - MARGIN)
            movement += abs(vx[index]) + abs(vy[index])
        }

        if (movement / count < spec.settleThreshold) settled = true
    }

    /**
     * Moves [index] to a position and holds it there.
     *
     * The simulation keeps running around it, so dragging a node rearranges its
     * neighbours rather than tearing a hole in the layout. [release] hands the
     * node back.
     */
    fun pin(index: Int, positionX: Float, positionY: Float) {
        if (index !in 0 until count) return
        pinned += index
        x[index] = positionX.coerceIn(MARGIN, 1f - MARGIN)
        y[index] = positionY.coerceIn(MARGIN, 1f - MARGIN)
        vx[index] = 0f
        vy[index] = 0f
        // A drag disturbs the configuration, so the layout is no longer settled
        // however still it had become.
        settled = false
    }

    /** Hands [index] back to the simulation. */
    fun release(index: Int) {
        pinned -= index
    }

    /** True when [index] is being held in place. */
    fun isPinned(index: Int): Boolean = index in pinned

    /** An immutable copy of the current positions. */
    fun snapshot(): GraphPositions = GraphPositions(x.copyOf(), y.copyOf(), settled)

    internal companion object {
        /**
         * Above this, the pairwise repulsion costs more than the picture is
         * worth and the circular layout is used instead.
         */
        const val MAX_SIMULATED = 1_200

        const val MIN_SEPARATION_SQUARED = 1e-8
        const val MAX_VELOCITY = 0.05
        const val MARGIN = 0.02f
    }
}

/** The node nearest [x], [y] within [radius], or `null`. */
fun GraphPositions.nearest(x: Float, y: Float, radius: Float): Int? {
    var best = -1
    var bestDistance = radius * radius
    for (index in 0 until size) {
        val dx = this.x[index] - x
        val dy = this.y[index] - y
        val distance = dx * dx + dy * dy
        if (distance <= bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return best.takeIf { it >= 0 }
}

/** The smaller of two floats, named so the intent reads at the call site. */
internal fun smallerOf(a: Float, b: Float): Float = min(a, b)
