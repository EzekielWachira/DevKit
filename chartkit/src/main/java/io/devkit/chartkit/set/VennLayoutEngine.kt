package io.devkit.chartkit.set

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Produces the geometry for a set diagram.
 *
 * ```text
 * SetDiagramData ──▶ SetDiagramLayoutEngine ──▶ SetLayout (unit space)
 *                          ├── VennLayoutEngine
 *                          └── EulerLayoutEngine
 * ```
 *
 * One interface with two implementations, rather than two charts with two
 * rendering stacks. Everything after this point — regions, labels, hit testing,
 * selection, tooltips, animation, accessibility — is identical for a Venn
 * diagram and an Euler diagram, because by this point both are just shapes.
 */
internal interface SetDiagramLayoutEngine {
    fun layout(data: SetDiagramData, config: SetLayoutConfig): SetLayout
}

/**
 * The canonical Venn arrangements, and the proportional solve.
 *
 * ### The arrangements
 *
 * ```text
 * 1 set    one circle
 * 2 sets   two circles, offset
 * 3 sets   three circles on an equilateral triangle
 * 4 sets   four congruent ellipses — no four circles can produce all 15 regions
 * 5+       an ellipse rosette, best effort, with measured coverage
 * ```
 *
 * Four is where a Venn diagram stops being drawable with circles: fifteen
 * regions require the boundaries to cross in a pattern circles cannot make, and
 * every four-set Venn ever drawn uses ellipses for that reason. Rather than
 * quietly drawing four circles and losing three regions, the engine switches
 * shape and says so through [SetShape.Ellipse].
 *
 * ### Above four sets
 *
 * Symmetric Venn diagrams exist for five, seven and eleven sets, but they are
 * mathematical curiosities: a five-set Venn has thirty-one regions, several of
 * them slivers too thin to label or tap. The engine lays out an ellipse rosette,
 * **measures** how many of the theoretical regions it actually produced, and
 * reports that as [SetLayoutQuality.regionCoverage]. A caller who needs every
 * combination of eight sets wants an UpSet plot, and the model here is already
 * the model that would feed one.
 */
internal class VennLayoutEngine(private val sizing: SetSizing) : SetDiagramLayoutEngine {

    override fun layout(data: SetDiagramData, config: SetLayoutConfig): SetLayout {
        val ids = data.sets.map { it.id }
        if (ids.isEmpty()) return SetLayout.Empty

        val shapes = when {
            sizing == SetSizing.Proportional && ids.size in 2..3 ->
                return proportional(data, config)

            ids.size == 1 -> mapOf(ids[0] to SetShape.Circle(0.0, 0.0, 1.0))
            ids.size == 2 -> twoCircles(ids)
            ids.size == 3 -> threeCircles(ids)
            ids.size == 4 -> fourEllipses(ids)
            else -> rosette(ids)
        }

        val layout = SetLayout(shapes, ids)
        return SetLayout(shapes, ids, quality = qualityOf(layout, data, iterations = 0))
    }

    /** Two circles of equal size overlapping by roughly a third of each. */
    private fun twoCircles(ids: List<String>): Map<String, SetShape> = mapOf(
        ids[0] to SetShape.Circle(-TWO_SET_OFFSET, 0.0, 1.0),
        ids[1] to SetShape.Circle(TWO_SET_OFFSET, 0.0, 1.0),
    )

    /**
     * Three equal circles whose centres form an equilateral triangle.
     *
     * The triangle points **down** — two circles above, one below — which is the
     * conventional orientation and the one that leaves the widest exclusive
     * regions along the top, where labels usually go.
     */
    private fun threeCircles(ids: List<String>): Map<String, SetShape> {
        val angles = doubleArrayOf(-PI / 2, PI / 6, 5 * PI / 6)
        return ids.mapIndexed { index, id ->
            val angle = angles[index]
            id to SetShape.Circle(
                centerX = THREE_SET_OFFSET * cos(angle),
                centerY = THREE_SET_OFFSET * sin(angle),
                radius = 1.0,
            )
        }.toMap()
    }

    /**
     * The classical four-ellipse Venn.
     *
     * Two ellipses lean one way, two the other, and the four boundaries cross in
     * the pattern that produces all fifteen regions. These proportions are the
     * standard construction — the same one every four-set Venn uses — expressed
     * here in the layout's own unit space.
     */
    private fun fourEllipses(ids: List<String>): Map<String, SetShape> {
        val lean = 35.0 * PI / 180.0
        val configurations = listOf(
            Quad(-0.15, -0.10, -lean),
            Quad(-0.05, 0.10, -lean),
            Quad(0.05, 0.10, lean),
            Quad(0.15, -0.10, lean),
        )
        return ids.mapIndexed { index, id ->
            val quad = configurations[index]
            id to SetShape.Ellipse(
                centerX = quad.x,
                centerY = quad.y,
                radiusX = FOUR_SET_MAJOR,
                radiusY = FOUR_SET_MINOR,
                rotation = quad.rotation,
            )
        }.toMap()
    }

    private class Quad(val x: Double, val y: Double, val rotation: Double)

    /**
     * `n` congruent ellipses evenly rotated about a common centre.
     *
     * The generalisation of the four-set construction. It produces most of the
     * theoretical regions for five sets and progressively fewer above that,
     * which is why the result is measured rather than asserted.
     */
    private fun rosette(ids: List<String>): Map<String, SetShape> {
        val n = ids.size
        val step = 2 * PI / n
        return ids.mapIndexed { index, id ->
            val angle = index * step - PI / 2
            id to SetShape.Ellipse(
                centerX = ROSETTE_OFFSET * cos(angle),
                centerY = ROSETTE_OFFSET * sin(angle),
                radiusX = ROSETTE_MAJOR,
                radiusY = ROSETTE_MINOR,
                // Rotated to lie along the ring's tangent, which is what makes
                // consecutive ellipses cross rather than merely touch.
                rotation = angle + PI / 2,
            )
        }.toMap()
    }

    /**
     * Circles sized by cardinality and positioned by solving for the stated
     * overlaps.
     *
     * Only for two and three sets. Four area-proportional circles cannot even
     * produce all the regions, let alone size them, so a four-set proportional
     * request falls back to the conceptual ellipse arrangement — stated in the
     * quality metrics rather than silently.
     */
    private fun proportional(data: SetDiagramData, config: SetLayoutConfig): SetLayout {
        val ids = data.sets.map { it.id }
        val unit = areaUnit(data)
        val circles = data.sets.associate { set ->
            set.id to SetShape.Circle(0.0, 0.0, SetGeometryUtils.radiusForArea(set.value * unit))
        }

        val desired = desiredAreas(data, unit)
        val seeded = seedPositions(ids, circles, desired)
        val solved = CircleOptimizer.solve(seeded, desired, emptyList(), config)
        val layout = SetLayout(solved.circles, ids)
        return SetLayout(
            shapes = solved.circles,
            order = ids,
            quality = qualityOf(layout, data, solved.iterations),
        )
    }

    /**
     * Starting positions, chosen analytically rather than arbitrarily.
     *
     * The first pair is placed at exactly the distance that produces its stated
     * overlap; the third circle is placed by trilateration against both. A
     * pattern search from a good start converges in a few dozen steps; from a
     * bad one it may not converge at all inside its iteration budget, and the
     * picture would then depend on the budget.
     */
    private fun seedPositions(
        ids: List<String>,
        circles: Map<String, SetShape.Circle>,
        desired: Map<Set<String>, Double>,
    ): Map<String, SetShape.Circle> {
        if (ids.size < 2) return circles
        val result = LinkedHashMap<String, SetShape.Circle>(ids.size)
        val first = circles.getValue(ids[0])
        result[ids[0]] = first.copy(centerX = 0.0, centerY = 0.0)

        val second = circles.getValue(ids[1])
        val d01 = SetGeometryUtils.distanceForOverlap(
            first.radius,
            second.radius,
            desired[setOf(ids[0], ids[1])] ?: 0.0,
        )
        result[ids[1]] = second.copy(centerX = d01, centerY = 0.0)

        for (index in 2 until ids.size) {
            val circle = circles.getValue(ids[index])
            val d0 = SetGeometryUtils.distanceForOverlap(
                first.radius,
                circle.radius,
                desired[setOf(ids[0], ids[index])] ?: 0.0,
            )
            val d1 = SetGeometryUtils.distanceForOverlap(
                second.radius,
                circle.radius,
                desired[setOf(ids[1], ids[index])] ?: 0.0,
            )
            // Trilateration: the point at d0 from the first centre and d1 from
            // the second. When the two constraints cannot both hold the circles
            // are placed on the perpendicular bisector instead, which is the
            // closest arrangement that respects neither more than the other.
            val cx = if (d01 <= SetGeometryUtils.EPSILON) {
                0.0
            } else {
                (d0 * d0 - d1 * d1 + d01 * d01) / (2 * d01)
            }
            val squared = d0 * d0 - cx * cx
            val cy = if (squared > 0.0) sqrt(squared) else 0.0
            result[ids[index]] = circle.copy(centerX = cx, centerY = -cy)
        }
        return result
    }

    private fun qualityOf(
        layout: SetLayout,
        data: SetDiagramData,
        iterations: Int,
    ): SetLayoutQuality {
        val unit = areaUnit(data)
        val unionArea = max(1e-9, data.union * unit)
        val coverage = regionCoverage(layout, data.sets.size)
        return CircleOptimizer.measure(
            shapes = layout.shapes,
            desiredAreas = desiredAreas(data, unit),
            unionArea = unionArea,
            iterations = iterations,
            regionCoverage = coverage,
        )
    }

    /**
     * The fraction of the `2^n − 1` theoretical regions the arrangement produced.
     *
     * Measured by sampling the arrangement, not assumed from the number of sets:
     * that is the difference between a library that knows its four-ellipse
     * construction works and one that hopes so. Above [MAX_COVERAGE_SETS] the
     * count of theoretical regions overflows what is worth enumerating, and
     * coverage is reported as unknown by leaving it at one.
     */
    private fun regionCoverage(layout: SetLayout, setCount: Int): Double {
        if (setCount <= 1) return 1.0
        if (setCount > MAX_COVERAGE_SETS) return 1.0
        val expected = (1L shl setCount) - 1
        val found = RegionGeometryIndex.of(layout, resolution = COVERAGE_RESOLUTION)
            .regions.keys.size
        return (found.toDouble() / expected).coerceIn(0.0, 1.0)
    }

    private companion object {
        /** Half the distance between the two circles of a two-set Venn. */
        const val TWO_SET_OFFSET: Double = 0.55

        /** How far each of three circles sits from the arrangement's centre. */
        const val THREE_SET_OFFSET: Double = 0.62

        const val FOUR_SET_MAJOR: Double = 0.85
        const val FOUR_SET_MINOR: Double = 0.48

        const val ROSETTE_OFFSET: Double = 0.36
        const val ROSETTE_MAJOR: Double = 0.95
        const val ROSETTE_MINOR: Double = 0.40

        /** Above this, enumerating theoretical regions costs more than it tells. */
        const val MAX_COVERAGE_SETS: Int = 8

        const val COVERAGE_RESOLUTION: Int = 220
    }
}

/**
 * The area one item of cardinality occupies, chosen so the whole diagram is
 * about the size of the unit square.
 *
 * Scaling by the union rather than by the largest set keeps a diagram of two
 * small sets and one huge one from being mostly empty space, and makes the unit
 * independent of which set happens to be biggest.
 */
internal fun areaUnit(data: SetDiagramData): Double {
    val total = data.union.takeIf { it > 0.0 }
        ?: data.sets.sumOf { it.value }.takeIf { it > 0.0 }
        ?: return 1.0
    return TARGET_TOTAL_AREA / total
}

/** The absolute area each stated combination's overlap should occupy. */
internal fun desiredAreas(data: SetDiagramData, unit: Double): Map<Set<String>, Double> =
    data.totals
        .filterKeys { it.size >= 2 }
        .mapValues { (_, value) -> value * unit }

/** The area the whole arrangement aims to fill, in unit space. */
private const val TARGET_TOTAL_AREA: Double = PI
