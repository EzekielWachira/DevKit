package io.devkit.chartkit.set

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Lays sets out according to the relationships they actually have.
 *
 * ```text
 * Animals ⊃ Mammals            →  Mammals drawn inside Animals
 * Plants  ∩ Animals = ∅        →  Plants drawn beside them, touching nothing
 * A ∩ B = 30 of 100 and 80     →  solved to the distance that produces 30
 * ```
 *
 * ### Nesting is not overlap
 *
 * The mistake this engine exists to avoid is representing containment as a very
 * large overlap. `B ⊆ A` drawn as two circles crossing near their edges is
 * *wrong*: it shows a region for "B but not A", and there is no such region. So
 * containment is laid out as containment — the child goes inside the parent —
 * and the picture has no region the data does not have.
 *
 * ### The algorithm
 *
 * ```text
 * 1  every set's smallest strict container becomes its parent   → a forest
 * 2  bottom-up: pack each node's children, then grow the node to hold them
 * 3  siblings that overlap are solved to their stated overlap; siblings that
 *    do not are packed apart
 * 4  the whole arrangement is refined by the pattern search, with a penalty
 *    for any nesting the refinement would break
 * ```
 *
 * Step 4 is what handles the relationships a tree cannot express on its own —
 * a set that overlaps its uncle, say, which is exactly the shape of the British
 * Isles: Ireland-the-island is inside the British Isles, contains Northern
 * Ireland, and overlaps the United Kingdom, which lives in a different branch.
 * The forest gets it close and deterministic; the solver reconciles the rest;
 * the residual error is reported rather than hidden.
 */
internal class EulerLayoutEngine(private val sizing: SetSizing) : SetDiagramLayoutEngine {

    override fun layout(data: SetDiagramData, config: SetLayoutConfig): SetLayout {
        val ids = data.sets.map { it.id }
        if (ids.isEmpty()) return SetLayout.Empty
        if (ids.size == 1) {
            return SetLayout(mapOf(ids[0] to SetShape.Circle(0.0, 0.0, 1.0)), ids)
        }

        val unit = areaUnit(data)
        val relationships = data.relationships
        val valueOf = data.sets.associate { it.id to it.value }

        // Equal sets are collapsed onto one representative. Two sets that share
        // every item are geometrically indistinguishable, and letting each try
        // to contain the other would make the forest cyclic.
        val representative = equalityRepresentatives(ids, relationships)

        val parents = HashMap<String, String?>(ids.size)
        ids.forEach { id ->
            parents[id] = relationships
                .containers(id)
                .filter { representative[it] != representative[id] }
                .minByOrNull { valueOf[it] ?: 0.0 }
        }

        val children = HashMap<String, MutableList<String>>(ids.size)
        val roots = ArrayList<String>()
        ids.forEach { id ->
            val parent = parents[id]
            if (parent == null) roots += id else children.getOrPut(parent) { ArrayList() } += id
        }

        val baseRadius = ids.associateWith { id ->
            when (sizing) {
                SetSizing.Proportional ->
                    max(MIN_RADIUS, SetGeometryUtils.radiusForArea((valueOf[id] ?: 0.0) * unit))

                SetSizing.Conceptual -> CONCEPTUAL_LEAF_RADIUS
            }
        }

        val placed = HashMap<String, SetShape.Circle>(ids.size)
        // Deterministic traversal order: largest first, then by id. Input order
        // must not change the picture, and two sets of equal size must not swap
        // places between runs.
        val orderedRoots = roots.sortedWith(
            compareByDescending<String> { valueOf[it] ?: 0.0 }.thenBy { it },
        )
        orderedRoots.forEach { root -> build(root, children, baseRadius, data, unit, placed) }
        packGroup(orderedRoots, placed, 0.0, 0.0, data, unit)

        val containment = ids.mapNotNull { id -> parents[id]?.let { it to id } }
        val desired = desiredAreas(data, unit)
        arrangeChildren(placed, children, desired, containment, data)
        val refined = CircleOptimizer.solve(placed, desired, containment, config)

        val shapes = refined.circles.toMap()
        val quality = CircleOptimizer.measure(
            shapes = shapes,
            desiredAreas = desired,
            unionArea = max(1e-9, data.union * unit),
            iterations = refined.iterations,
        )
        return SetLayout(shapes, ids, quality)
    }

    /**
     * Tries each child subtree in a handful of places inside its parent.
     *
     * ### The move the per-circle search cannot make
     *
     * Where a child sits *inside* its parent is free, and that freedom decides
     * whether something buried in one branch can reach an overlap in another.
     * The British Isles is exactly that: the United Kingdom has to meet the
     * island of Ireland, and whether it can depends on which side of the British
     * Islands the Isle of Man happens to be sitting on.
     *
     * A search that moves one circle at a time cannot fix it. Sliding the United
     * Kingdom across makes it swallow the Isle of Man long before it reaches
     * Ireland, so every individual step is worse than standing still even though
     * the destination is much better. Moving a whole subtree at once is the move
     * that gets there, and it has to be tried as a whole.
     *
     * So each child is offered a small polar grid of positions inside its
     * parent — its current one, the centre, and eight directions at two radii —
     * and the best-scoring placement is kept. Largest child first, because it
     * has the least room to move and the most influence on everything else.
     *
     * Bounded and deterministic: a fixed grid, scored, best kept. No randomness,
     * no restarts, the same answer every time.
     */
    private fun arrangeChildren(
        placed: MutableMap<String, SetShape.Circle>,
        children: Map<String, List<String>>,
        desired: Map<Set<String>, Double>,
        containment: List<Pair<String, String>>,
        data: SetDiagramData,
    ) {
        val parents = children.keys
            .filter { children[it].orEmpty().isNotEmpty() }
            // Outermost first: where a branch sits is only worth deciding once
            // the branch it hangs from has settled.
            .sortedWith(compareByDescending<String> { data.set(it)?.value ?: 0.0 }.thenBy { it })

        parents.forEach { parent ->
            val container = placed[parent] ?: return@forEach
            val kids = children.getValue(parent).sortedWith(
                compareByDescending<String> { placed[it]?.radius ?: 0.0 }.thenBy { it },
            )

            kids.forEach { child ->
                val circle = placed[child] ?: return@forEach
                val subtree = listOf(child) + descendants(child, data)
                val snapshot = subtree.associateWith { placed.getValue(it) }
                val room = (container.radius * (1.0 - NESTING_MARGIN) - circle.radius)
                    .coerceAtLeast(0.0)
                if (room <= 0.0) return@forEach

                var bestScore = CircleOptimizer.score(placed, desired, containment)
                var bestX = circle.centerX
                var bestY = circle.centerY

                candidateOffsets(room).forEach { (dx, dy) ->
                    val targetX = container.centerX + dx
                    val targetY = container.centerY + dy
                    val shiftX = targetX - circle.centerX
                    val shiftY = targetY - circle.centerY
                    subtree.forEach { id ->
                        val original = snapshot.getValue(id)
                        placed[id] = original.copy(
                            centerX = original.centerX + shiftX,
                            centerY = original.centerY + shiftY,
                        )
                    }
                    val score = CircleOptimizer.score(placed, desired, containment)
                    if (score < bestScore - 1e-12) {
                        bestScore = score
                        bestX = targetX
                        bestY = targetY
                    }
                }

                val shiftX = bestX - circle.centerX
                val shiftY = bestY - circle.centerY
                subtree.forEach { id ->
                    val original = snapshot.getValue(id)
                    placed[id] = original.copy(
                        centerX = original.centerX + shiftX,
                        centerY = original.centerY + shiftY,
                    )
                }
            }
        }
    }

    /** The centre, then eight directions at two radii inside the parent. */
    private fun candidateOffsets(room: Double): List<Pair<Double, Double>> {
        val result = ArrayList<Pair<Double, Double>>(1 + PLACEMENT_ANGLES * 2)
        result += 0.0 to 0.0
        listOf(room * 0.55, room).forEach { radius ->
            for (step in 0 until PLACEMENT_ANGLES) {
                val angle = step * 2 * PI / PLACEMENT_ANGLES
                result += (radius * cos(angle)) to (radius * sin(angle))
            }
        }
        return result
    }

    /** Everything [id] contains, at any depth. */
    private fun descendants(id: String, data: SetDiagramData): List<String> {
        val found = LinkedHashSet<String>()
        fun walk(current: String) {
            data.relationships.contained(current).forEach { child ->
                if (found.add(child)) walk(child)
            }
        }
        walk(id)
        return found.toList()
    }

    /**
     * Sizes a node to hold its children, depth first.
     *
     * The node's own radius is the larger of what its cardinality asks for and
     * what its contents require. Growing to fit is not a distortion to
     * apologise for — a set that contains another set *is* at least as large,
     * and drawing it smaller would be the falsehood.
     */
    private fun build(
        id: String,
        children: Map<String, List<String>>,
        baseRadius: Map<String, Double>,
        data: SetDiagramData,
        unit: Double,
        into: MutableMap<String, SetShape.Circle>,
    ) {
        val kids = children[id].orEmpty().sortedWith(
            compareByDescending<String> { data.set(it)?.value ?: 0.0 }.thenBy { it },
        )
        kids.forEach { build(it, children, baseRadius, data, unit, into) }

        val required = if (kids.isEmpty()) {
            0.0
        } else {
            packGroup(kids, into, 0.0, 0.0, data, unit)
            kids.maxOf { child ->
                val circle = into.getValue(child)
                hypot(circle.centerX, circle.centerY) + circle.radius
            } + CHILD_MARGIN
        }

        val radius = max(baseRadius[id] ?: MIN_RADIUS, required)
        into[id] = SetShape.Circle(0.0, 0.0, radius)
    }

    /**
     * Places a group of sets around a common centre, relative to it.
     *
     * Sets that overlap one another are placed at the distance that produces
     * their stated overlap; sets that do not are pushed apart. Groups that
     * overlap nothing are arranged on a ring wide enough that they cannot touch.
     *
     * @return the radius the group occupies from its centre.
     */
    private fun packGroup(
        ids: List<String>,
        placed: MutableMap<String, SetShape.Circle>,
        centreX: Double,
        centreY: Double,
        data: SetDiagramData,
        unit: Double,
    ): Double {
        if (ids.isEmpty()) return 0.0
        if (ids.size == 1) {
            val only = placed[ids[0]] ?: return 0.0
            // Everything inside this subtree moves with it, so a nested branch
            // stays assembled rather than being torn apart by the parent's move.
            translateSubtree(ids[0], placed, centreX - only.centerX, centreY - only.centerY, data)
            return only.radius
        }

        val components = overlapComponents(ids, data)
            .map { component -> component to layoutComponent(component, placed, data, unit) }
            // Largest first, and ties broken by the group's own membership, so
            // the arrangement is a function of the data rather than of the order
            // the sets arrived in.
            .sortedWith(compareByDescending<Pair<List<String>, Double>> { it.second }
                .thenBy { it.first.sorted().joinToString(" ") })

        fun move(component: List<String>, targetX: Double, targetY: Double) {
            val shift = shiftOf(component, placed)
            component.forEach { id ->
                translateSubtree(id, placed, targetX - shift[0], targetY - shift[1], data)
            }
        }

        // The largest group takes the middle and the rest go round it. Putting
        // every group on one ring — which is the obvious thing — sizes that ring
        // by the *largest* member, so a parent holding one big child and two
        // specks is forced to be three times the size it needs. The British
        // Isles is exactly that shape: a large United Kingdom beside a tiny Isle
        // of Man.
        val largest = components.first()
        move(largest.first, centreX, centreY)
        var occupied = largest.second

        val satellites = components.drop(1)
        if (satellites.isEmpty()) return occupied

        satellites.forEachIndexed { index, (component, radius) ->
            val angle = index * 2 * PI / satellites.size - PI / 2
            val distance = (largest.second + radius) * SATELLITE_SLACK
            move(
                component,
                centreX + distance * cos(angle),
                centreY + distance * sin(angle),
            )
            occupied = max(occupied, distance + radius)
        }
        return occupied
    }

    /** One connected group of mutually overlapping sets, placed relative to itself. */
    private fun layoutComponent(
        component: List<String>,
        placed: MutableMap<String, SetShape.Circle>,
        data: SetDiagramData,
        unit: Double,
    ): Double {
        if (component.size == 1) return placed[component[0]]?.radius ?: 0.0

        val anchor = component.first()
        val anchorCircle = placed[anchor] ?: return 0.0
        translateSubtree(anchor, placed, -anchorCircle.centerX, -anchorCircle.centerY, data)

        var angle = 0.0
        component.drop(1).forEach { id ->
            val circle = placed[id] ?: return@forEach
            val overlap = data.total(setOf(anchor, id)) * unit
            val distance = SetGeometryUtils.distanceForOverlap(
                placed.getValue(anchor).radius,
                circle.radius,
                overlap,
            )
            val targetX = distance * cos(angle)
            val targetY = distance * sin(angle)
            translateSubtree(id, placed, targetX - circle.centerX, targetY - circle.centerY, data)
            angle += 2 * PI / max(1, component.size - 1)
        }
        return component.maxOf { id ->
            val circle = placed.getValue(id)
            hypot(circle.centerX, circle.centerY) + circle.radius
        }
    }

    /** The centre of a group's bounding circle. */
    private fun shiftOf(component: List<String>, placed: Map<String, SetShape.Circle>): DoubleArray {
        var x = 0.0
        var y = 0.0
        component.forEach { id ->
            val circle = placed[id] ?: return@forEach
            x += circle.centerX
            y += circle.centerY
        }
        return doubleArrayOf(x / component.size, y / component.size)
    }

    /**
     * Moves a set and everything it contains.
     *
     * Containment is transitive, so the whole subtree moves together. Moving a
     * parent without its children is how a nested diagram comes apart.
     */
    private fun translateSubtree(
        id: String,
        placed: MutableMap<String, SetShape.Circle>,
        dx: Double,
        dy: Double,
        data: SetDiagramData,
    ) {
        if (dx == 0.0 && dy == 0.0) return
        val moved = HashSet<String>()
        fun move(target: String) {
            if (!moved.add(target)) return
            val circle = placed[target] ?: return
            placed[target] = circle.copy(centerX = circle.centerX + dx, centerY = circle.centerY + dy)
            data.relationships.contained(target).forEach(::move)
        }
        move(id)
    }

    /** Sibling groups joined by overlap, in a deterministic order. */
    private fun overlapComponents(ids: List<String>, data: SetDiagramData): List<List<String>> {
        val remaining = LinkedHashSet(ids)
        val components = ArrayList<List<String>>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.first()
            val component = ArrayList<String>()
            val queue = ArrayDeque<String>()
            queue += seed
            remaining -= seed
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component += current
                ids.forEach { other ->
                    if (other in remaining &&
                        data.relationships.between(current, other) == SetRelationship.Overlaps
                    ) {
                        remaining -= other
                        queue += other
                    }
                }
            }
            components += component
        }
        return components
    }

    /** One id per group of mutually equal sets, so the forest cannot cycle. */
    private fun equalityRepresentatives(
        ids: List<String>,
        relationships: SetRelationshipGraph,
    ): Map<String, String> {
        val representative = HashMap<String, String>(ids.size)
        ids.forEach { id ->
            if (id in representative) return@forEach
            val group = (listOf(id) + relationships.equal(id)).sorted()
            val head = group.first()
            group.forEach { representative[it] = head }
        }
        return representative
    }

    private companion object {
        const val MIN_RADIUS: Double = 0.05
        const val CONCEPTUAL_LEAF_RADIUS: Double = 0.5

        /** The gap a parent keeps around the children it holds. */
        const val CHILD_MARGIN: Double = 0.12

        /** How far apart a satellite group sits from the central one. */
        const val SATELLITE_SLACK: Double = 1.04

        /** How many directions a child subtree is offered inside its parent. */
        const val PLACEMENT_ANGLES: Int = 12

        /**
         * How far inside its container's edge a nested set is kept.
         *
         * The same margin the solver's containment penalty uses, so the
         * placement pass never proposes a position the solver would immediately
         * penalise.
         */
        const val NESTING_MARGIN: Double = 0.02
    }
}
