package io.devkit.chartkit.set

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Moves circles until their overlaps match the data as closely as circles can.
 *
 * ```text
 * minimise   Σ over pairs  weight · (desiredArea − actualArea)²
 *          + Σ over nested containmentPenalty
 *          + ε · compactness
 * ```
 *
 * ### Why not `radius = sqrt(value)` and fixed centres
 *
 * Because that reproduces the *set* sizes and nothing else. The overlaps — which
 * are the entire reason the diagram exists — would come out wherever the
 * arbitrary centres happened to put them, and the picture would state
 * intersection cardinalities nobody supplied. Radii do come from the values
 * (area, not radius, proportional to cardinality), and then the centres are
 * solved for the intersections.
 *
 * ### Why a pattern search and not gradient descent
 *
 * The objective is not differentiable in any convenient closed form: the
 * lens-area function has a kink where circles become tangent and another where
 * one swallows the other, and both are exactly where a set diagram spends its
 * time. A derivative-free pattern search — try a fixed set of offsets, keep what
 * improves, halve the step when nothing does — walks through those kinks without
 * special cases, is trivially bounded, and is **completely deterministic**.
 *
 * That determinism is a requirement, not a convenience: a diagram that reshuffled
 * itself on every recomposition would be unusable, and a solver with a random
 * restart would have needed a seed, a documented distribution and tests that
 * tolerate jitter. This needs none of them.
 */
internal object CircleOptimizer {

    /** The offsets tried at each step: four axes and four diagonals. */
    private val DIRECTIONS: Array<DoubleArray> = arrayOf(
        doubleArrayOf(1.0, 0.0),
        doubleArrayOf(-1.0, 0.0),
        doubleArrayOf(0.0, 1.0),
        doubleArrayOf(0.0, -1.0),
        doubleArrayOf(0.7071, 0.7071),
        doubleArrayOf(-0.7071, 0.7071),
        doubleArrayOf(0.7071, -0.7071),
        doubleArrayOf(-0.7071, -0.7071),
    )

    /** How heavily a containment violation counts against a pair-area error. */
    private const val CONTAINMENT_WEIGHT: Double = 60.0

    /** How far inside its container's edge a nested set is kept, as a fraction. */
    private const val CONTAINMENT_MARGIN: Double = 0.02

    /** Just enough pull towards the centroid to stop disjoint sets drifting apart. */
    private const val COMPACTNESS_WEIGHT: Double = 1e-3

    /** How heavily two sets that share nothing are stopped from touching. */
    private const val DISJOINT_WEIGHT: Double = 8.0

    /** The visible gap kept between two sets that share nothing, as a fraction
     * of the smaller radius. */
    private const val DISJOINT_GAP: Double = 0.06

    /** How far a targeted move travels, as a fraction of the distance to its target. */
    private const val TARGETED_REACH: Double = 0.35

    class Result(
        val circles: Map<String, SetShape.Circle>,
        val iterations: Int,
        val objective: Double,
    )

    /**
     * @param desiredAreas the absolute area each pair's overlap should have, in
     *   the same units as the circles. A pair that shares nothing belongs here
     *   with a desired area of zero — omitting it would let the solver overlap
     *   two sets that do not meet, at no cost.
     * @param containment `(outer, inner)` pairs the geometry must nest. Modelled
     *   as a penalty rather than a hard constraint, because a hard constraint
     *   plus an unsatisfiable dataset is a solver that never terminates.
     */
    fun solve(
        initial: Map<String, SetShape.Circle>,
        desiredAreas: Map<Set<String>, Double>,
        containment: List<Pair<String, String>> = emptyList(),
        config: SetLayoutConfig = SetLayoutConfig.Default,
    ): Result {
        val ids = initial.keys.toList()
        if (ids.size < 2) return Result(initial, 0, 0.0)

        val radii = DoubleArray(ids.size) { initial.getValue(ids[it]).radius }
        val x = DoubleArray(ids.size) { initial.getValue(ids[it]).centerX }
        val y = DoubleArray(ids.size) { initial.getValue(ids[it]).centerY }
        val index = ids.withIndex().associate { (i, id) -> id to i }

        // Disjointness is normalised against the *diagram's* scale, not the
        // pair's. Normalising by the smaller circle made a speck's separation
        // matter hundreds of times more than a large pair's stated overlap, so
        // one tiny set standing in the way could veto a relationship the data
        // actually has.
        val scale = max(1e-9, radii.average() * radii.average())

        val nestOuter = containment.mapNotNull { index[it.first] }
        val nestInner = containment.mapNotNull { index[it.second] }
        // A nested pair is scored on *structure*, never on area. When a parent
        // has grown to hold its children, its overlap with each child is that
        // child's whole area — which is more than the child's cardinality asked
        // for. Left in the objective, that surplus makes the solver push the
        // child out of its parent to shrink the overlap, breaking the one thing
        // an Euler diagram exists to show.
        val nestedPairs = containment.mapTo(HashSet()) { setOf(it.first, it.second) }

        // Pairs are flattened into parallel arrays once. The objective is
        // evaluated thousands of times, and walking a map of sets per evaluation
        // would dominate the solve.
        val pairA = ArrayList<Int>()
        val pairB = ArrayList<Int>()
        val pairTarget = ArrayList<Double>()
        val pairWeight = ArrayList<Double>()
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val key = setOf(ids[i], ids[j])
                if (key in nestedPairs) continue
                val target = desiredAreas[key] ?: 0.0
                pairA += i
                pairB += j
                pairTarget += target
                // Normalised by the smaller circle, so a large pair's absolute
                // error does not drown out a small pair's proportional one.
                pairWeight += 1.0 / max(1e-9, min(radii[i], radii[j]).let { it * it })
            }
        }


        fun objective(): Double {
            var total = 0.0
            for (p in pairA.indices) {
                val i = pairA[p]
                val j = pairB[p]
                if (pairTarget[p] <= 0.0) {
                    // Disjoint pairs are scored on *penetration*, not on the
                    // area they share. Near tangency the shared area is
                    // vanishingly small while the overlap is plainly visible,
                    // so an area term barely resists it — and the compactness
                    // term then happily pushes two sets that share nothing into
                    // one another. Distance is the honest signal here.
                    // A little more than touching. Two circles resting exactly
                    // tangent read as sharing a boundary, which for sets that
                    // share nothing is the wrong impression by a hair.
                    val wanted = radii[i] + radii[j] +
                        DISJOINT_GAP * min(radii[i], radii[j])
                    val gap = wanted - hypot(x[i] - x[j], y[i] - y[j])
                    if (gap > 0.0) total += DISJOINT_WEIGHT * gap * gap / scale
                    continue
                }
                val actual = SetGeometryUtils.circleIntersectionArea(
                    x[i], y[i], radii[i],
                    x[j], y[j], radii[j],
                )
                val error = actual - pairTarget[p]
                total += pairWeight[p] * error * error
            }
            for (n in nestOuter.indices) {
                val outer = nestOuter[n]
                val inner = nestInner[n]
                // A margin, so the child sits *inside* rather than resting on
                // the boundary: a circle touching its container's edge from the
                // inside reads as crossing it.
                val slack = hypot(x[outer] - x[inner], y[outer] - y[inner]) +
                    radii[inner] - radii[outer] * (1.0 - CONTAINMENT_MARGIN)
                if (slack > 0.0) {
                    total += CONTAINMENT_WEIGHT * slack * slack /
                        max(1e-9, radii[outer] * radii[outer])
                }
            }
            var centroidX = 0.0
            var centroidY = 0.0
            for (i in ids.indices) {
                centroidX += x[i]
                centroidY += y[i]
            }
            centroidX /= ids.size
            centroidY /= ids.size
            for (i in ids.indices) {
                val dx = x[i] - centroidX
                val dy = y[i] - centroidY
                total += COMPACTNESS_WEIGHT * (dx * dx + dy * dy) / max(1e-9, radii[i] * radii[i])
            }
            return total
        }

        var best = objective()
        var step = radii.average() * 0.5
        var iterations = 0

        // One iteration is one **sweep** over every circle, not one candidate
        // move. Counting candidates would have given a seven-circle diagram
        // seven sweeps out of a four-hundred budget, which is not enough for the
        // search to get anywhere — and the number of sweeps is what actually
        // determines whether it converges.
        while (iterations < config.maxIterations && step > radii.average() * 1e-4) {
            iterations++
            var improved = false
            for (i in ids.indices) {
                fun tryMove(dx: Double, dy: Double): Boolean {
                    val originalX = x[i]
                    val originalY = y[i]
                    x[i] = originalX + dx
                    y[i] = originalY + dy
                    val candidate = objective()
                    if (candidate < best - 1e-12) {
                        best = candidate
                        return true
                    }
                    x[i] = originalX
                    y[i] = originalY
                    return false
                }

                for (direction in DIRECTIONS) {
                    if (tryMove(direction[0] * step, direction[1] * step)) improved = true
                }

                // Moves aimed straight at another set, and straight away from
                // it. The eight compass directions are enough to descend a
                // smooth basin but not to cross one: a set that must reach an
                // overlap on the far side of a third set will never get there by
                // small axis-aligned steps, because every one of them makes
                // things worse before any makes them better. Aiming a move at
                // the partner is the escape, and it is the only move that knows
                // what the diagram is trying to achieve.
                for (j in ids.indices) {
                    if (j == i) continue
                    val dx = x[j] - x[i]
                    val dy = y[j] - y[i]
                    val length = hypot(dx, dy)
                    if (length < 1e-9) continue
                    val ux = dx / length
                    val uy = dy / length
                    val reach = max(step, length * TARGETED_REACH)
                    if (tryMove(ux * reach, uy * reach)) improved = true
                    if (tryMove(-ux * reach, -uy * reach)) improved = true
                }
            }
            if (best <= config.tolerance) break
            // No direction helped at this scale, so look more finely. Halving
            // is what makes the search terminate: the step is bounded below and
            // the loop exits when it gets there.
            if (!improved) step /= 2
        }

        val solved = ids.indices.associate { i ->
            ids[i] to SetShape.Circle(x[i], y[i], radii[i])
        }
        return Result(solved, iterations, best)
    }

    /**
     * The objective, for an arrangement the search did not produce.
     *
     * Exposed so a caller can compare whole arrangements — the rotation pass in
     * [EulerLayoutEngine] scores a dozen orientations of a subtree against each
     * other, which is a coordinated move the per-circle search cannot express.
     */
    fun score(
        circles: Map<String, SetShape.Circle>,
        desiredAreas: Map<Set<String>, Double>,
        containment: List<Pair<String, String>> = emptyList(),
    ): Double {
        val ids = circles.keys.toList()
        if (ids.size < 2) return 0.0
        val nested = containment.mapTo(HashSet()) { setOf(it.first, it.second) }
        val scale = max(1e-9, circles.values.map { it.radius }.average().let { it * it })
        var total = 0.0

        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val key = setOf(ids[i], ids[j])
                if (key in nested) continue
                val a = circles.getValue(ids[i])
                val b = circles.getValue(ids[j])
                val target = desiredAreas[key] ?: 0.0
                if (target <= 0.0) {
                    val gap = a.radius + b.radius +
                        DISJOINT_GAP * min(a.radius, b.radius) -
                        hypot(a.centerX - b.centerX, a.centerY - b.centerY)
                    if (gap > 0.0) total += DISJOINT_WEIGHT * gap * gap / scale
                    continue
                }
                val actual = SetGeometryUtils.circleIntersectionArea(
                    a.centerX, a.centerY, a.radius,
                    b.centerX, b.centerY, b.radius,
                )
                val error = actual - target
                total += error * error / max(1e-9, min(a.radius, b.radius).let { it * it })
            }
        }
        containment.forEach { (outer, inner) ->
            val o = circles[outer] ?: return@forEach
            val i = circles[inner] ?: return@forEach
            val slack = hypot(o.centerX - i.centerX, o.centerY - i.centerY) +
                i.radius - o.radius * (1.0 - CONTAINMENT_MARGIN)
            if (slack > 0.0) {
                total += CONTAINMENT_WEIGHT * slack * slack / max(1e-9, o.radius * o.radius)
            }
        }
        return total
    }

    /**
     * How far the geometry ended up from the data.
     *
     * Errors are expressed as a fraction of the union's area rather than in
     * absolute units, so the number means the same thing for a diagram of ten
     * items and a diagram of ten million.
     */
    fun measure(
        shapes: Map<String, SetShape>,
        desiredAreas: Map<Set<String>, Double>,
        unionArea: Double,
        iterations: Int,
        regionCoverage: Double = 1.0,
    ): SetLayoutQuality {
        if (shapes.size < 2 || unionArea <= 0.0) {
            return SetLayoutQuality(regionCoverage = regionCoverage, iterations = iterations)
        }
        var sum = 0.0
        var count = 0
        var worst = 0.0
        var worstKey = emptySet<String>()
        val ids = shapes.keys.toList()
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val key = setOf(ids[i], ids[j])
                val desired = desiredAreas[key] ?: 0.0
                val actual = SetGeometryUtils.intersectionArea(
                    shapes.getValue(ids[i]),
                    shapes.getValue(ids[j]),
                )
                val error = abs(desired - actual) / unionArea
                sum += error
                count++
                if (error > worst) {
                    worst = error
                    worstKey = key
                }
            }
        }
        return SetLayoutQuality(
            meanAreaError = if (count == 0) 0.0 else sum / count,
            worstAreaError = worst,
            worstCombination = worstKey,
            regionCoverage = regionCoverage,
            iterations = iterations,
        )
    }
}
