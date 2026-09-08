package io.devkit.chartkit.set

import androidx.compose.runtime.Immutable
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How a set diagram decides where its shapes go.
 *
 * ```text
 * Venn   every combination is drawn, whether or not anything is in it
 * Euler  only the relationships that actually occur are drawn
 * Custom the caller places the shapes
 * ```
 *
 * The distinction is the whole point of having both. A Venn diagram of
 * *Mammals, Animals, Plants* draws all seven regions even though no mammal is a
 * plant; an Euler diagram of the same data nests Mammals inside Animals and puts
 * Plants beside them, showing nothing that is not there. Neither is a better
 * picture in general — a Venn diagram is a template for discussing possibility,
 * an Euler diagram is a report of fact.
 */
sealed interface SetDiagramLayout {

    /** How the shapes are sized. */
    val sizing: SetSizing

    /**
     * All theoretical combinations, drawn.
     *
     * Uses the canonical arrangements: one circle, two circles, three circles in
     * a triangle, four congruent ellipses. Above four the arrangement is
     * best-effort and its measured region coverage is reported — see
     * [SetLayoutQuality.regionCoverage].
     */
    data class Venn(override val sizing: SetSizing = SetSizing.Conceptual) : SetDiagramLayout

    /**
     * Only the relationships present in the data.
     *
     * Containment nests, disjointness separates, overlap is solved for the
     * stated intersection area.
     */
    data class Euler(override val sizing: SetSizing = SetSizing.Proportional) : SetDiagramLayout

    /**
     * Shapes the caller placed, in unit space.
     *
     * For the diagram whose arrangement *is* the message — a marketing scope
     * chart where five circles are positioned to create exactly the ten overlaps
     * being talked about, and no solver should be second-guessing them. The
     * geometry is still fitted to the plot and still drives hit testing, labels
     * and regions, so everything else works unchanged.
     *
     * Sets with no shape here fall back to [fallback].
     */
    data class Custom(
        val shapes: Map<String, SetShape>,
        val fallback: SetDiagramLayout = Venn(),
    ) : SetDiagramLayout {
        override val sizing: SetSizing get() = fallback.sizing
    }

    companion object {
        /** All combinations, sized for readability. The default for [VennDiagram]. */
        val Venn: SetDiagramLayout = Venn(SetSizing.Conceptual)

        /** All combinations, sized by cardinality. */
        val VennProportional: SetDiagramLayout = Venn(SetSizing.Proportional)

        /** Actual relationships, sized by cardinality. The default for [EulerDiagram]. */
        val Euler: SetDiagramLayout = Euler(SetSizing.Proportional)

        /** Actual relationships, sized for readability. */
        val EulerConceptual: SetDiagramLayout = Euler(SetSizing.Conceptual)
    }
}

/** How a layout chooses shape sizes. */
enum class SetSizing {

    /**
     * Equal, readable shapes that show the *structure* of the relationships.
     *
     * What a teaching diagram, a marketing-scope diagram or an icon-group
     * diagram wants: the reader is being shown which things overlap, not how
     * many of each there are, and sizing by a cardinality nobody is reading
     * costs label space for nothing.
     */
    Conceptual,

    /**
     * Area approximately proportional to cardinality.
     *
     * A set twice the size is drawn with twice the area — not twice the radius,
     * which would be four times the area and the commonest way an
     * area-proportional chart lies. Overlaps are solved for the stated
     * intersection areas as closely as circles allow; see
     * [SetLayoutQuality] for how closely that turned out to be.
     */
    Proportional,
}

/**
 * How well a layout reproduced the data it was given.
 *
 * ### Why this is measured rather than assumed
 *
 * Area-proportional set diagrams are not always possible. Three circles have six
 * degrees of freedom and a three-set system has seven quantities to reproduce;
 * four circles cannot produce all fifteen regions at all. Every honest
 * implementation is therefore an approximation, and the only question is whether
 * it says so. This is where it says so.
 *
 * @param meanAreaError the mean absolute difference between each desired
 *   intersection's share of the union and the share the geometry actually
 *   produced, in `0..1`. Zero is exact.
 * @param worstAreaError the largest such difference, and the combination it
 *   belongs to.
 * @param regionCoverage the fraction of combinations that the layout was asked
 *   to show and actually produced as a visible region. Below `1.0` a Venn
 *   layout is not showing every theoretical region — which above four sets is
 *   expected, and is reported rather than glossed over.
 * @param iterations how many solver sweeps ran. Bounded by
 *   [SetLayoutConfig.maxIterations].
 */
@Immutable
data class SetLayoutQuality(
    val meanAreaError: Double = 0.0,
    val worstAreaError: Double = 0.0,
    val worstCombination: Set<String> = emptySet(),
    val regionCoverage: Double = 1.0,
    val iterations: Int = 0,
) {
    /** True when the geometry reproduces the data closely enough to be read as exact. */
    val isExact: Boolean get() = worstAreaError <= 0.01 && regionCoverage >= 1.0
}

/**
 * Bounds on the layout solver.
 *
 * ### Deterministic by construction
 *
 * There is no seed, because there is no randomness. The solver is a pattern
 * search from a fixed analytic starting arrangement: it tries a fixed set of
 * offsets, keeps improvements, and halves its step when none help. Same data,
 * same configuration, same picture — on every recomposition, on every device, in
 * every test. A stochastic solver would have needed a seed, an explanation, and
 * a test that tolerates jitter; this needs none of the three.
 *
 * @param maxIterations the hard ceiling on solver **sweeps** — one sweep tries
 *   every circle in every direction. Reached rather than exceeded: an
 *   inconsistent dataset must not be able to spin a layout for ever.
 * @param tolerance the objective value below which the solve stops early.
 */
@Immutable
data class SetLayoutConfig(
    val maxIterations: Int = 220,
    val tolerance: Double = 1e-4,
) {
    init {
        require(maxIterations > 0) { "maxIterations must be positive" }
    }

    companion object {
        val Default: SetLayoutConfig = SetLayoutConfig()

        /** Fewer steps, for a diagram being dragged or animated. */
        val Fast: SetLayoutConfig = SetLayoutConfig(maxIterations = 80)
    }
}

/**
 * Where every set ended up.
 *
 * Produced once per data-and-configuration change and then reused: a selection,
 * a hover, a tooltip, an animation frame and a recomposition all read this
 * without re-solving anything. The layout is in **unit space** until [fitInto]
 * maps it into a plot, which is what lets the same solved arrangement serve a
 * phone, a tablet and a resize without running the solver again.
 */
@Immutable
class SetLayout(
    val shapes: Map<String, SetShape>,
    val order: List<String>,
    val quality: SetLayoutQuality = SetLayoutQuality(),
) {
    val isEmpty: Boolean get() = shapes.isEmpty()

    fun shape(id: String): SetShape? = shapes[id]

    /**
     * The same arrangement fitted into [plot], with **one** scale for both axes.
     *
     * Independent x and y scales would fill the rectangle and turn every circle
     * into an ellipse, which for a diagram whose meaning is carried by *area and
     * overlap* is not a stretch of the picture but a change to the data it
     * claims. So the arrangement is scaled uniformly and centred, and a wide
     * plot around a tall diagram keeps space at the sides.
     */
    fun fitInto(plot: ChartRect, padding: Float = 0f): SetLayout {
        if (isEmpty || plot.isEmpty) return this
        val box = SetGeometryUtils.boundsOf(shapes.values) ?: return this
        val available = ChartRect(
            left = plot.left + padding,
            top = plot.top + padding,
            right = plot.right - padding,
            bottom = plot.bottom - padding,
        )
        if (available.isEmpty) return this

        val width = box[2] - box[0]
        val height = box[3] - box[1]
        if (width <= 0.0 || height <= 0.0) return this

        val scale = min(available.width / width, available.height / height).toDouble()
        val centreX = (box[0] + box[2]) / 2
        val centreY = (box[1] + box[3]) / 2
        val offsetX = available.centerX - centreX * scale
        // The y axis is *not* flipped. A set diagram has no notion of up, so
        // there is no north to preserve and no sign to get wrong — unlike the
        // geographic coordinates, which flip exactly once for exactly that
        // reason.
        val offsetY = available.centerY - centreY * scale

        return SetLayout(
            shapes = shapes.mapValues { (_, shape) -> shape.transformed(scale, offsetX, offsetY) },
            order = order,
            quality = quality,
        )
    }

    override fun toString(): String = "SetLayout(${order.size} sets, quality=$quality)"

    companion object {
        val Empty: SetLayout = SetLayout(emptyMap(), emptyList())
    }
}

/**
 * One logical region's geometry: where it is, how big it is, and how much room
 * it has.
 *
 * ### Why an anchor is not a centroid
 *
 * The area centroid of a crescent — which is the shape of most exclusive regions
 * in a Venn diagram — lies outside the crescent. A label placed there sits in
 * the neighbouring region and names the wrong thing. So the anchor is the point
 * of the region *furthest from any of its boundaries*: the pole of
 * inaccessibility, found by sampling. It is inside by construction, and
 * [clearance] is the radius of the largest circle that fits around it, which is
 * exactly what a label or a group of icons needs to know before deciding whether
 * it fits.
 *
 * @param area the region's area in pixels, measured from the same samples.
 * @param clearance the distance from [anchor] to the nearest boundary, in pixels.
 */
@Immutable
data class RegionGeometry(
    val memberships: Set<String>,
    val anchor: ChartOffset,
    val clearance: Float,
    val area: Float,
    val bounds: ChartRect,
) {
    val id: String = memberships.sorted().joinToString(" ")

    /** True when the region has enough room to put something in it. */
    val isDrawable: Boolean get() = clearance > 0f && area > 0f
}

/**
 * Every visible region of a laid-out diagram, found by sampling.
 *
 * ```text
 * for each sample point
 *     membership = the sets whose shape contains it
 *     clearance  = distance to the nearest boundary that defines the region
 *     keep the sample with the greatest clearance per membership
 * ```
 *
 * ### Why sampling, and not boolean path arithmetic
 *
 * Both are used, for different jobs. The renderer *draws* regions with real path
 * intersection and difference, because a filled shape must have exact edges.
 * This computes *where things go* — label anchors, available space, region
 * areas, which combinations are visible at all — and for that a grid is
 * better: it is one pass for every region at once rather than a boolean
 * operation per region, it degrades gracefully on tangent and coincident
 * geometry where path arithmetic returns empty or throws, and its answers are
 * continuous in the inputs, so an animating diagram's labels move smoothly
 * rather than jumping when a path operation changes its mind.
 *
 * The grid is deterministic — no random sampling — so the same layout always
 * produces the same anchors.
 */
@Immutable
class RegionGeometryIndex private constructor(
    val regions: Map<Set<String>, RegionGeometry>,
) {
    fun geometry(memberships: Set<String>): RegionGeometry? = regions[memberships]

    /** Regions with room for content, largest first. */
    fun drawable(): List<RegionGeometry> =
        regions.values.filter { it.isDrawable }.sortedByDescending { it.area }

    companion object {
        val Empty: RegionGeometryIndex = RegionGeometryIndex(emptyMap())

        /** How many samples across the longer axis of the diagram's box. */
        const val DEFAULT_RESOLUTION: Int = 140

        /**
         * Samples [layout] and returns every region it actually produces.
         *
         * @param resolution samples across the longer axis. The cost is
         *   quadratic in it and linear in the number of sets, and it is run once
         *   per layout rather than per frame.
         */
        fun of(layout: SetLayout, resolution: Int = DEFAULT_RESOLUTION): RegionGeometryIndex {
            if (layout.isEmpty) return Empty
            val box = SetGeometryUtils.boundsOf(layout.shapes.values) ?: return Empty
            val width = box[2] - box[0]
            val height = box[3] - box[1]
            if (width <= 0.0 || height <= 0.0) return Empty

            val columns = max(2, (resolution * (width / max(width, height))).toInt())
            val rows = max(2, (resolution * (height / max(width, height))).toInt())
            val stepX = width / columns
            val stepY = height / rows
            val cellArea = (stepX * stepY).toFloat()

            val ids = layout.order.filter { layout.shapes.containsKey(it) }
            val shapes = ids.map { layout.shapes.getValue(it) }

            val best = HashMap<Set<String>, MutableRegion>()

            for (row in 0 until rows) {
                val y = box[1] + (row + 0.5) * stepY
                for (column in 0 until columns) {
                    val x = box[0] + (column + 0.5) * stepX
                    var membership: MutableSet<String>? = null
                    shapes.forEachIndexed { index, shape ->
                        if (shape.contains(x, y)) {
                            val target = membership ?: LinkedHashSet<String>(2).also { membership = it }
                            target += ids[index]
                        }
                    }
                    val inside = membership ?: continue

                    // The clearance is bounded by every shape at once: how far
                    // inside its members the point is, and how far outside its
                    // non-members. That single number is what makes the anchor
                    // the deepest point of the region rather than merely a point
                    // in it.
                    var clearance = Double.MAX_VALUE
                    shapes.forEachIndexed { index, shape ->
                        val member = ids[index] in inside
                        val signed = signedClearance(shape, x, y)
                        val value = if (member) signed else -signed
                        if (value < clearance) clearance = value
                    }

                    val entry = best.getOrPut(inside) { MutableRegion() }
                    entry.area += cellArea
                    entry.minX = min(entry.minX, x)
                    entry.minY = min(entry.minY, y)
                    entry.maxX = max(entry.maxX, x)
                    entry.maxY = max(entry.maxY, y)
                    if (clearance > entry.clearance) {
                        entry.clearance = clearance
                        entry.anchorX = x
                        entry.anchorY = y
                    }
                }
            }

            val result = best.mapValues { (memberships, entry) ->
                RegionGeometry(
                    memberships = memberships,
                    anchor = ChartOffset(entry.anchorX.toFloat(), entry.anchorY.toFloat()),
                    clearance = max(0.0, entry.clearance).toFloat(),
                    area = entry.area,
                    bounds = ChartRect(
                        left = entry.minX.toFloat(),
                        top = entry.minY.toFloat(),
                        right = entry.maxX.toFloat(),
                        bottom = entry.maxY.toFloat(),
                    ),
                )
            }
            return RegionGeometryIndex(result)
        }

        /** Positive inside the shape, negative outside, in the same units. */
        private fun signedClearance(shape: SetShape, x: Double, y: Double): Double = when (shape) {
            is SetShape.Circle ->
                shape.radius - kotlin.math.hypot(x - shape.centerX, y - shape.centerY)

            is SetShape.Ellipse -> {
                // Exact distance to an ellipse needs a quartic solve. The
                // normalised radial residual scaled by the smaller semi-axis is
                // a good approximation, is continuous, and has the right sign —
                // which is all an anchor search needs.
                val cosR = kotlin.math.cos(-shape.rotation)
                val sinR = kotlin.math.sin(-shape.rotation)
                val dx = x - shape.centerX
                val dy = y - shape.centerY
                val localX = (dx * cosR - dy * sinR) / max(shape.radiusX, 1e-9)
                val localY = (dx * sinR + dy * cosR) / max(shape.radiusY, 1e-9)
                val radial = kotlin.math.hypot(localX, localY)
                (1.0 - radial) * min(shape.radiusX, shape.radiusY)
            }
        }

        private class MutableRegion {
            var area: Float = 0f
            var clearance: Double = -Double.MAX_VALUE
            var anchorX: Double = 0.0
            var anchorY: Double = 0.0
            var minX: Double = Double.MAX_VALUE
            var minY: Double = Double.MAX_VALUE
            var maxX: Double = -Double.MAX_VALUE
            var maxY: Double = -Double.MAX_VALUE
        }
    }
}

/** The membership of a point, for hit testing and for region lookup. */
internal fun SetLayout.membershipAt(x: Float, y: Float): Set<String> {
    val result = LinkedHashSet<String>(2)
    order.forEach { id ->
        val shape = shapes[id] ?: return@forEach
        if (shape.contains(x.toDouble(), y.toDouble())) result += id
    }
    return result
}

/** True when two layouts describe the same arrangement to within a pixel. */
internal fun SetLayout.approximatelyEquals(other: SetLayout, tolerance: Double = 0.5): Boolean {
    if (shapes.keys != other.shapes.keys) return false
    return shapes.all { (id, shape) ->
        val mine = shape.bounds()
        val theirs = other.shapes.getValue(id).bounds()
        mine.indices.all { abs(mine[it] - theirs[it]) <= tolerance }
    }
}
