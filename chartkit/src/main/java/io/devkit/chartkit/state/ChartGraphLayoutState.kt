package io.devkit.chartkit.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.devkit.chartkit.graph.ChartGraph
import io.devkit.chartkit.graph.ForceSimulation
import io.devkit.chartkit.graph.GraphLayout
import io.devkit.chartkit.graph.GraphLayoutStrategy
import io.devkit.chartkit.graph.GraphPositions

/**
 * A network graph's node positions, and the simulation that produces them.
 *
 * ```kotlin
 * val layout = rememberGraphLayoutState(GraphLayoutStrategy.ForceDirected())
 *
 * NetworkGraph(nodes = services, edges = calls, nodeId = { it.name },
 *              source = { it.from }, target = { it.to }, layoutState = layout)
 * Button(onClick = { layout.strategy = GraphLayoutStrategy.Circular() }) { Text("Circular") }
 * ```
 *
 * ### Positions live here, not on the caller's nodes
 *
 * Parallel arrays indexed by node position, so the consumer's own list stays
 * immutable and a graph can be laid out from a `List<Service>` nobody owns.
 *
 * ### The simulation does not run in composition
 *
 * A force layout is a loop over every pair of nodes. Run inside a draw pass or
 * a recomposition it would drop frames; run once per iteration into Compose
 * state it would recompose four hundred times to show a picture nobody can read
 * until the end. So it steps in batches on a background dispatcher and
 * publishes a snapshot at a fixed interval, and it stops as soon as it settles.
 *
 * Changing the graph or the strategy cancels the outstanding work — that is
 * structured concurrency doing its job, not something this class arranges.
 */
@Stable
class ChartGraphLayoutState internal constructor(
    initialStrategy: GraphLayoutStrategy,
) {
    /** How the nodes are placed. Changing it restarts the layout. */
    var strategy: GraphLayoutStrategy by mutableStateOf(initialStrategy)

    /**
     * The current positions, in a unit square.
     *
     * Unit coordinates rather than pixels, so resizing the chart does not
     * restart the simulation and a node dragged to the right stays on the
     * right.
     */
    var positions: GraphPositions by mutableStateOf(GraphPositions.Empty)
        internal set

    /** True once the layout has stopped moving. */
    val isSettled: Boolean get() = positions.settled

    /** The node currently being dragged, or `null`. */
    var draggedIndex: Int? by mutableStateOf(null)
        internal set

    /**
     * Bumped whenever the layout needs to start moving again.
     *
     * Read by the chart's effect, which restarts its stepping loop. A counter
     * rather than a boolean because two drags in quick succession must both
     * wake it, and a boolean already set to true would swallow the second.
     */
    internal var wakeCount by mutableIntStateOf(0)
        private set

    private var simulation: ForceSimulation? = null
    private var simulationKey: Pair<ChartGraph, GraphLayoutStrategy>? = null

    /**
     * The simulation for this graph and strategy, reusing the running one where
     * nothing has changed.
     *
     * Reuse is what makes dragging work: pinning a node has to disturb the
     * layout the reader is looking at, not start a fresh one that throws every
     * other node somewhere else.
     */
    internal fun simulationFor(graph: ChartGraph, spec: GraphLayoutStrategy.ForceDirected): ForceSimulation {
        val key = graph to (spec as GraphLayoutStrategy)
        val existing = simulation
        if (existing != null && simulationKey == key) return existing
        val created = ForceSimulation(graph, spec)
        simulation = created
        simulationKey = key
        return created
    }

    internal fun publish(snapshot: GraphPositions) {
        positions = snapshot
    }

    /** Places the circular fallback, for a graph too large to simulate. */
    internal fun placeCircular(graph: ChartGraph, spec: GraphLayoutStrategy.Circular) {
        simulation = null
        simulationKey = null
        positions = GraphLayout.circular(graph, spec)
    }

    /**
     * Starts dragging [index], holding it wherever [dragTo] puts it.
     *
     * The simulation keeps running around a pinned node, so a drag rearranges
     * the neighbourhood rather than tearing a hole in it.
     */
    fun beginDrag(index: Int) {
        draggedIndex = index
        simulation?.pin(index, positions.x.getOrElse(index) { 0.5f }, positions.y.getOrElse(index) { 0.5f })
        wakeCount++
    }

    /** Moves the dragged node to a unit-space position. */
    fun dragTo(x: Float, y: Float) {
        val index = draggedIndex ?: return
        val active = simulation
        if (active != null) {
            active.pin(index, x, y)
            // Published immediately so the node tracks the finger, rather than
            // waiting for the next simulation batch — a drag that lags by a
            // frame interval feels broken however smooth the rest is.
            positions = active.snapshot()
        } else {
            // A circular layout has no simulation. Moving the node is still
            // reasonable, and is the only thing a drag can mean there.
            val moved = positions.copy()
            if (index in 0 until moved.size) {
                moved.x[index] = x.coerceIn(0f, 1f)
                moved.y[index] = y.coerceIn(0f, 1f)
                positions = moved
            }
        }
    }

    /**
     * Ends the drag, leaving the node pinned where it was dropped.
     *
     * Pinned rather than released, because a node that sprang back the instant
     * it was let go would make dragging pointless. [release] and [releaseAll]
     * hand nodes back to the simulation.
     */
    fun endDrag() {
        draggedIndex = null
        wakeCount++
    }

    /** Hands [index] back to the simulation. */
    fun release(index: Int) {
        simulation?.release(index)
        wakeCount++
    }

    /** Hands every pinned node back. */
    fun releaseAll() {
        val active = simulation ?: return
        for (index in 0 until positions.size) active.release(index)
        wakeCount++
    }

    /** True when [index] is being held in place. */
    fun isPinned(index: Int): Boolean = simulation?.isPinned(index) == true

    /** Restarts the layout from scratch. */
    fun restart() {
        simulation = null
        simulationKey = null
        wakeCount++
    }
}

/** Remembers a [ChartGraphLayoutState]. */
@Composable
fun rememberGraphLayoutState(
    strategy: GraphLayoutStrategy = GraphLayoutStrategy.ForceDirected(),
): ChartGraphLayoutState = remember { ChartGraphLayoutState(strategy) }

/**
 * A pan-and-zoom window over a two-dimensional plot.
 *
 * ### Why not [ChartViewportState]
 *
 * That one is a window along **one** axis — a `[start, end]` fraction of a
 * domain — because that is what zooming a time series means, and every
 * Cartesian chart, the shared-viewport machinery and the navigator are built on
 * it. A graph has no domain axis and zooms in two directions at once, so
 * reusing the one-dimensional type would have meant either widening it with a
 * second axis every Cartesian chart ignores, or pretending a graph has an x
 * domain. This is a different thing, and it says so.
 *
 * Positions are in unit space, so a viewport survives the chart being resized.
 */
@Stable
class ChartPlanarViewportState internal constructor(
    initialScale: Float = 1f,
) {
    /** Magnification. `1` shows the whole layout. */
    var scale: Float by mutableStateOf(initialScale)
        private set

    /** The pan offset, in unit-space fractions. */
    var offsetX: Float by mutableStateOf(0f)
        private set

    var offsetY: Float by mutableStateOf(0f)
        private set

    val isFullyZoomedOut: Boolean
        get() = scale <= 1f + ZOOM_EPSILON && offsetX == 0f && offsetY == 0f

    /** Zooms by [factor] about the unit-space point ([focusX], [focusY]). */
    fun zoomBy(factor: Float, focusX: Float = 0.5f, focusY: Float = 0.5f) {
        val next = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (next == scale) return
        // The focus point stays under the fingers: the offset absorbs the
        // difference the scale change would otherwise introduce.
        offsetX += (focusX - 0.5f) * (1f / scale - 1f / next)
        offsetY += (focusY - 0.5f) * (1f / scale - 1f / next)
        scale = next
        clamp()
    }

    /** Pans by a unit-space delta. */
    fun panBy(deltaX: Float, deltaY: Float) {
        offsetX += deltaX / scale
        offsetY += deltaY / scale
        clamp()
    }

    /** Shows the whole layout again. */
    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    /** A unit-space position as seen through this viewport. */
    fun transformX(x: Float): Float = 0.5f + (x - 0.5f + offsetX) * scale

    fun transformY(y: Float): Float = 0.5f + (y - 0.5f + offsetY) * scale

    /** The inverse, for turning a pointer position back into layout space. */
    fun untransformX(x: Float): Float = (x - 0.5f) / scale + 0.5f - offsetX

    fun untransformY(y: Float): Float = (y - 0.5f) / scale + 0.5f - offsetY

    /** Keeps at least some of the layout on screen at every zoom level. */
    private fun clamp() {
        val limit = (1f - 1f / scale) / 2f + PAN_SLACK
        offsetX = offsetX.coerceIn(-limit, limit)
        offsetY = offsetY.coerceIn(-limit, limit)
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 8f
        const val ZOOM_EPSILON = 1e-4f

        /** A little room past the edge, so a node on the boundary can be centred. */
        const val PAN_SLACK = 0.15f
    }
}

/** Remembers a [ChartPlanarViewportState]. */
@Composable
fun rememberPlanarViewportState(): ChartPlanarViewportState =
    remember { ChartPlanarViewportState() }
