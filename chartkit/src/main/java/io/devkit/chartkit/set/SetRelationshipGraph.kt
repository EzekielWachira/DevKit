package io.devkit.chartkit.set

import androidx.compose.runtime.Immutable

/**
 * How two sets stand to one another.
 *
 * Derived from cardinalities alone — never from labels. "Scotland is inside
 * Great Britain" is a fact about the numbers the caller supplied, not something
 * inferred from the words, and an accessibility layer that guessed geography
 * from a name would eventually announce something false.
 */
enum class SetRelationship {

    /** They share nothing. `|A ∩ B| = 0`. */
    Disjoint,

    /** They share some but not all of either. */
    Overlaps,

    /** Every item of the second is in the first. `B ⊆ A`, and `A ≠ B`. */
    Contains,

    /** The mirror of [Contains]. */
    ContainedBy,

    /** Same cardinality and complete overlap. Indistinguishable by the numbers. */
    Equal,
}

/**
 * Every pairwise relationship in a set system.
 *
 * ```text
 * Animals
 * ├── contains  Mammals
 * └── disjoint  Plants
 * ```
 *
 * This is what makes an Euler diagram possible. A Venn layout does not need it
 * — it draws all combinations regardless — but an Euler layout is *defined* by
 * it: containment becomes nesting, disjointness becomes separation, and overlap
 * becomes a solved distance. Computing it once here rather than inside the
 * layout keeps the relationships testable without a canvas, and leaves them
 * available to the accessibility layer, which describes them in words.
 */
@Immutable
class SetRelationshipGraph internal constructor(
    private val relations: Map<String, Map<String, SetRelationship>>,
    val ids: List<String>,
) {
    /** How [a] stands to [b], or [SetRelationship.Disjoint] for unknown ids. */
    fun between(a: String, b: String): SetRelationship =
        relations[a]?.get(b) ?: SetRelationship.Disjoint

    /** The sets [id] wholly contains, directly or transitively. */
    fun contained(id: String): List<String> =
        ids.filter { it != id && between(id, it) == SetRelationship.Contains }

    /** The sets that wholly contain [id]. */
    fun containers(id: String): List<String> =
        ids.filter { it != id && between(id, it) == SetRelationship.ContainedBy }

    /** The sets [id] partially overlaps. */
    fun overlapping(id: String): List<String> =
        ids.filter { it != id && between(id, it) == SetRelationship.Overlaps }

    /** The sets [id] shares nothing with. */
    fun disjoint(id: String): List<String> =
        ids.filter { it != id && between(id, it) == SetRelationship.Disjoint }

    /** The sets indistinguishable from [id] by cardinality. */
    fun equal(id: String): List<String> =
        ids.filter { it != id && between(id, it) == SetRelationship.Equal }

    /**
     * The **immediate** container of [id]: the smallest set that contains it.
     *
     * What nesting needs. Placing Scotland directly inside the British Isles
     * rather than inside Great Britain would be true and useless — the picture
     * has to show the tightest containment, and that is the smallest container.
     */
    fun parent(id: String, valueOf: (String) -> Double): String? =
        containers(id).minByOrNull { valueOf(it) }

    /** True when no two sets share anything. */
    val allDisjoint: Boolean
        get() = ids.all { a -> ids.all { b -> a == b || between(a, b) == SetRelationship.Disjoint } }

    companion object {
        val Empty: SetRelationshipGraph = SetRelationshipGraph(emptyMap(), emptyList())

        /**
         * Derives every pairwise relationship from cardinalities.
         *
         * ```text
         * |A ∩ B| = 0              → disjoint
         * |A ∩ B| = |A| = |B|      → equal
         * |A ∩ B| = |B| < |A|      → A contains B
         * otherwise                → overlaps
         * ```
         *
         * Containment is decided by cardinality, which is the only evidence
         * available: two sets of ten sharing ten items are indistinguishable
         * from one set counted twice, and claiming otherwise would need
         * identity information that a diagram built from counts does not have.
         * The collection-driven path has that information and produces the same
         * answers from it.
         */
        internal fun of(
            sets: List<SetDefinition>,
            totalOf: (Set<String>) -> Double,
            tolerance: Double = 1e-9,
        ): SetRelationshipGraph {
            val ids = sets.map { it.id }
            val valueOf = sets.associate { it.id to it.value }
            val relations = HashMap<String, MutableMap<String, SetRelationship>>(ids.size)

            ids.forEach { a ->
                val row = HashMap<String, SetRelationship>(ids.size)
                ids.forEach { b ->
                    if (a == b) return@forEach
                    val shared = totalOf(setOf(a, b))
                    val sizeA = valueOf[a] ?: 0.0
                    val sizeB = valueOf[b] ?: 0.0
                    row[b] = when {
                        shared <= tolerance -> SetRelationship.Disjoint

                        // Equality first: without it a pair of identical sets
                        // would be reported as each containing the other, and
                        // the Euler layout would try to nest each inside the
                        // other for ever.
                        near(shared, sizeA, tolerance) && near(shared, sizeB, tolerance) ->
                            SetRelationship.Equal

                        near(shared, sizeB, tolerance) -> SetRelationship.Contains
                        near(shared, sizeA, tolerance) -> SetRelationship.ContainedBy
                        else -> SetRelationship.Overlaps
                    }
                }
                relations[a] = row
            }
            return SetRelationshipGraph(relations, ids)
        }

        private fun near(a: Double, b: Double, tolerance: Double): Boolean =
            kotlin.math.abs(a - b) <= tolerance * kotlin.math.max(1.0, kotlin.math.abs(b))
    }
}
