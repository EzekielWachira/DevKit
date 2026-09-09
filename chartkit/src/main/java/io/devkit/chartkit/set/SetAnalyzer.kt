package io.devkit.chartkit.set

import kotlin.math.abs
import kotlin.math.max

/**
 * A set and the items actually in it.
 *
 * The alternative input to [SetDefinition]: instead of stating cardinalities and
 * intersections by hand, hand over the collections and let [SetAnalyzer] count.
 *
 * ```kotlin
 * SetItems("android", "Android", androidUsers)
 * ```
 *
 * @param items the caller's own objects, read once. They are never retained by
 *   the diagram — see [SetAnalyzer.fromItems].
 */
class SetItems<T>(
    val id: String,
    val label: String,
    val items: List<T>,
    val metadata: Map<String, Any?> = emptyMap(),
    val style: SetStyle? = null,
)

/**
 * Turns set definitions into logical regions.
 *
 * ```text
 * cardinalities ─┐
 *                ├─▶ totals I(S) ─▶ inclusion–exclusion ─▶ exclusive E(S) ─▶ regions
 * collections ───┘
 * ```
 *
 * ### The two quantities, and why confusing them ruins a diagram
 *
 * `I(S)` — the **total** — is how many items are in *at least* every set in `S`.
 * It is what a caller states: "seventy users have both Android and iOS",
 * including any who also have Web.
 *
 * `E(S)` — the **exclusive** value — is how many items are in exactly `S` and
 * nothing more. It is what a *region* of the picture actually contains, and it
 * is what a tooltip over that region must report. The two coincide only for the
 * largest combination in a diagram.
 *
 * Converting between them is inclusion–exclusion:
 *
 * ```text
 * E(S) = Σ over T ⊇ S of (−1)^(|T|−|S|) · I(T)
 * I(S) = Σ over T ⊇ S of E(T)
 * ```
 *
 * Both directions are implemented, because both inputs occur: cardinalities
 * arrive as totals and need exclusives, collections produce exclusives directly
 * and need totals for validation and for the layout solver.
 *
 * ### Everything here is pure Kotlin
 *
 * No Compose, no Android, no canvas. This is arithmetic over the caller's
 * numbers, it is where every claim the diagram makes originates, and it is
 * verified directly on the JVM rather than by looking at pixels.
 */
object SetAnalyzer {

    /**
     * The most sets [fromItems] will analyse.
     *
     * The intersection lattice has `2^n − 1` members, so the work is exponential
     * in the number of sets rather than in the number of items. Twenty is far
     * past the point where a Venn or Euler picture is readable and still returns
     * promptly; beyond it the answer is a different visualisation — an UpSet
     * plot — not a faster loop.
     */
    const val MAX_ANALYZED_SETS: Int = 20

    /**
     * How many sets one item may belong to before its rarer combinations stop
     * being enumerated.
     *
     * An item in `k` sets contributes to `2^k − 1` totals. At sixteen that is
     * 65,535 increments for one item, which is already generous; past it the
     * combination counts are recorded up to size three and a diagnostic says so,
     * rather than the analysis quietly taking minutes.
     */
    const val MAX_ITEM_MEMBERSHIPS: Int = 16

    /**
     * Analyses stated cardinalities.
     *
     * @param intersections combinations of two or more sets. A single-set
     *   "intersection" is accepted and treated as a restatement of that set's
     *   value, because that is what it means.
     */
    fun analyze(
        sets: List<SetDefinition>,
        intersections: List<SetIntersection> = emptyList(),
        containments: List<SetContainment> = emptyList(),
        mode: SetValidationMode = SetValidationMode.Strict,
    ): SetDiagramData {
        val diagnostics = ArrayList<SetDiagnostic>()
        val normalized = normalizeSets(sets, diagnostics)
        if (normalized.isEmpty()) return finish(SetDiagramData.Empty, diagnostics, mode)

        val ids = normalized.map { it.id }.toSet()
        val totals = LinkedHashMap<Set<String>, Double>()
        normalized.forEach { totals[setOf(it.id)] = it.value }

        val parents = containmentClosure(containments, ids, diagnostics)

        intersections.forEach { intersection ->
            val unknown = intersection.sets - ids
            if (unknown.isNotEmpty()) {
                diagnostics += SetDiagnostic(
                    severity = SetDiagnosticSeverity.Error,
                    message = "Intersection names unknown set ids ${unknown.sorted()}",
                    sets = intersection.sets,
                )
                return@forEach
            }
            if (!intersection.value.isFinite()) {
                diagnostics += SetDiagnostic(
                    SetDiagnosticSeverity.Error,
                    "Intersection ${label(intersection.sets)} has a non-finite value",
                    intersection.sets,
                )
                return@forEach
            }
            // A repeated combination is summed nowhere and replaced nowhere: it
            // is a contradiction, and picking one silently would hide it.
            val previous = totals.put(intersection.sets, intersection.value)
            if (previous != null && abs(previous - intersection.value) > TOLERANCE) {
                diagnostics += SetDiagnostic(
                    SetDiagnosticSeverity.Error,
                    "Intersection ${label(intersection.sets)} was stated twice, " +
                        "as $previous and as ${intersection.value}",
                    intersection.sets,
                )
            }
        }

        // Applied after the explicit intersections so a caller can still state
        // a combination by hand; applied before validation so the derived
        // combinations are checked like any other.
        applyContainment(totals, parents, normalized)

        diagnostics += SetValidator.validate(normalized, totals)

        val regions = regionsFromTotals(normalized, totals)
        val union = regions.sumOf { it.value }
        val data = SetDiagramData(
            sets = normalized,
            regions = regions,
            totals = totals,
            union = union,
            relationships = SetRelationshipGraph.of(normalized, totalOf = { totals[it] ?: 0.0 }),
        )
        return finish(data, diagnostics, mode)
    }

    /**
     * Analyses real collections, counting the intersections itself.
     *
     * ```kotlin
     * SetAnalyzer.fromItems(
     *     sets = listOf(SetItems("android", "Android", androidUsers), …),
     *     key = User::id,
     * )
     * ```
     *
     * ### These are sets, not multisets
     *
     * An item whose [key] appears twice inside one collection counts **once**.
     * A "set" containing the same user twice is a list, and treating it as a
     * multiset would make the diagram's regions overlap-count: the union would
     * exceed the number of distinct people, and every percentage derived from
     * it would be wrong. Deduplicate before calling if repetition is meaningful
     * to you — it cannot be meaningful here.
     *
     * ### The items are not retained
     *
     * They are read once, reduced to counts, and dropped. The renderer never
     * holds a hundred thousand user records, which is the difference between a
     * diagram that recomposes cheaply and one that does not.
     */
    fun <T> fromItems(
        sets: List<SetItems<T>>,
        key: (T) -> Any?,
        mode: SetValidationMode = SetValidationMode.Strict,
    ): SetDiagramData {
        val diagnostics = ArrayList<SetDiagnostic>()
        if (sets.size > MAX_ANALYZED_SETS) {
            throw SetDataException(
                "Analysing ${sets.size} collections is beyond SetAnalyzer's limit of " +
                    "$MAX_ANALYZED_SETS — the intersection lattice is exponential in the " +
                    "number of sets. Aggregate first, or use fewer sets.",
                emptyList(),
            )
        }

        val definitions = ArrayList<SetDefinition>(sets.size)
        val seen = HashSet<String>(sets.size)
        val used = ArrayList<SetItems<T>>(sets.size)
        sets.forEach { source ->
            if (!seen.add(source.id)) {
                diagnostics += SetDiagnostic(
                    SetDiagnosticSeverity.Error,
                    "Duplicate set id \"${source.id}\"; the later one was dropped",
                    setOf(source.id),
                )
                return@forEach
            }
            used += source
        }
        if (used.isEmpty()) return finish(SetDiagramData.Empty, diagnostics, mode)

        // key → the bitmask of sets it belongs to. Repeating a key inside one
        // collection sets the same bit again, which is how deduplication falls
        // out of the representation rather than needing a pass of its own.
        val memberships = HashMap<Any?, Long>()
        used.forEachIndexed { index, source ->
            val bit = 1L shl index
            source.items.forEach { item ->
                val itemKey = key(item)
                memberships[itemKey] = (memberships[itemKey] ?: 0L) or bit
            }
        }

        val exclusiveByMask = HashMap<Long, Double>()
        memberships.values.forEach { mask ->
            if (mask == 0L) return@forEach
            exclusiveByMask[mask] = (exclusiveByMask[mask] ?: 0.0) + 1.0
        }

        val totalsByMask = HashMap<Long, Double>()
        var truncated = false
        exclusiveByMask.forEach { (mask, count) ->
            if (java.lang.Long.bitCount(mask) > MAX_ITEM_MEMBERSHIPS) {
                truncated = true
                addSmallSubsets(totalsByMask, mask, count)
            } else {
                // Every non-empty submask of `mask` gains this region's count,
                // because an item in exactly {a,b,c} is also in {a,b}, {a} and
                // so on. Iterating submasks directly is the standard trick and
                // avoids building the power set as objects.
                var sub = mask
                while (sub != 0L) {
                    totalsByMask[sub] = (totalsByMask[sub] ?: 0.0) + count
                    sub = (sub - 1) and mask
                }
            }
        }
        if (truncated) {
            diagnostics += SetDiagnostic(
                SetDiagnosticSeverity.Warning,
                "Some items belong to more than $MAX_ITEM_MEMBERSHIPS sets; combinations " +
                    "larger than three were not enumerated for them",
            )
        }

        used.forEachIndexed { index, source ->
            definitions += SetDefinition(
                id = source.id,
                label = source.label,
                value = totalsByMask[1L shl index] ?: 0.0,
                metadata = source.metadata,
                style = source.style,
            )
        }

        val idOf = used.map { it.id }
        fun namesOf(mask: Long): Set<String> {
            val result = LinkedHashSet<String>(java.lang.Long.bitCount(mask))
            idOf.forEachIndexed { index, id -> if (mask and (1L shl index) != 0L) result += id }
            return result
        }

        val totals = LinkedHashMap<Set<String>, Double>()
        totalsByMask.entries
            .sortedWith(compareBy({ java.lang.Long.bitCount(it.key) }, { it.key }))
            .forEach { (mask, value) -> totals[namesOf(mask)] = value }

        val regions = exclusiveByMask.entries
            .map { (mask, value) ->
                val names = namesOf(mask)
                SetRegion(
                    memberships = names,
                    kind = if (names.size == 1) SetRegionKind.Exclusive else SetRegionKind.Intersection,
                    value = value,
                    totalValue = totalsByMask[mask] ?: value,
                )
            }
            .sortedWith(compareBy({ it.size }, { it.id }))

        val data = SetDiagramData(
            sets = definitions,
            regions = regions,
            totals = totals,
            union = regions.sumOf { it.value },
            relationships = SetRelationshipGraph.of(definitions, totalOf = { totals[it] ?: 0.0 }),
            diagnostics = emptyList(),
        )
        return finish(data, diagnostics, mode)
    }

    /**
     * Every containment relation, made transitive.
     *
     * Stating `A ⊃ B` and `B ⊃ C` implies `A ⊃ C`, and a caller who wrote the
     * first two should not also have to write the third. Closure is a fixed
     * number of passes over a relation that only grows, so it terminates; a
     * cycle — `A ⊃ B ⊃ A` — is impossible for genuine containment and is
     * reported rather than followed.
     */
    private fun containmentClosure(
        containments: List<SetContainment>,
        ids: Set<String>,
        diagnostics: MutableList<SetDiagnostic>,
    ): Map<String, Set<String>> {
        if (containments.isEmpty()) return emptyMap()
        val parents = HashMap<String, MutableSet<String>>()
        containments.forEach { relation ->
            if (relation.parent !in ids || relation.child !in ids) {
                diagnostics += SetDiagnostic(
                    SetDiagnosticSeverity.Error,
                    "Containment names an unknown set: " +
                        "\"${relation.parent}\" contains \"${relation.child}\"",
                    setOf(relation.parent, relation.child),
                )
                return@forEach
            }
            parents.getOrPut(relation.child) { LinkedHashSet() } += relation.parent
        }

        repeat(ids.size) {
            var changed = false
            parents.keys.toList().forEach { child ->
                val direct = parents.getValue(child).toList()
                direct.forEach { parent ->
                    parents[parent]?.forEach { grandparent ->
                        if (grandparent != child && parents.getValue(child).add(grandparent)) {
                            changed = true
                        }
                    }
                }
            }
            if (!changed) return@repeat
        }

        // Mutual containment is the cycle that actually gets written — "A
        // contains B" and "B contains A", usually a copy-paste. Detected as a
        // pair rather than as a self-reference, because the closure deliberately
        // refuses to make a set its own ancestor and so would never produce one.
        parents.forEach { (child, ancestors) ->
            ancestors.forEach { ancestor ->
                if (parents[ancestor]?.contains(child) == true) {
                    diagnostics += SetDiagnostic(
                        SetDiagnosticSeverity.Error,
                        "Containment is circular: \"$child\" and \"$ancestor\" each " +
                            "contain the other",
                        setOf(child, ancestor),
                    )
                }
            }
        }
        return parents.mapValues { (child, ancestors) -> ancestors - child }
    }

    /**
     * Fills in the combinations that containment forces.
     *
     * If `B ⊆ A` then any group of sets containing `B` has exactly the same
     * members as that group plus `A` — every item in the first is in `B`, so it
     * is in `A` too. The totals are therefore copied upward until nothing
     * changes, which is what lets a nine-level nesting be declared in nine lines.
     */
    private fun applyContainment(
        totals: LinkedHashMap<Set<String>, Double>,
        parents: Map<String, Set<String>>,
        sets: List<SetDefinition>,
    ) {
        if (parents.isEmpty()) return
        sets.forEach { set ->
            parents[set.id]?.forEach { parent ->
                totals.putIfAbsent(setOf(parent, set.id), set.value)
            }
        }
        repeat(MAX_CLOSURE_PASSES) {
            var changed = false
            totals.keys.toList().forEach { combination ->
                val value = totals.getValue(combination)
                combination.forEach { member ->
                    parents[member]?.forEach { ancestor ->
                        if (ancestor in combination) return@forEach

                        // Upward: adding an ancestor changes nothing, because
                        // everything in the child is already in it.
                        //     |S ∪ {B} ∪ {A}| = |S ∪ {B}|   when B ⊆ A
                        if (totals.putIfAbsent(combination + ancestor, value) == null) {
                            changed = true
                        }

                        // Downward: replacing a set with something that contains
                        // it can only make the group larger.
                        //     |S ∪ {A}| ≥ |S ∪ {B}|         when B ⊆ A
                        // Without this rule, Northern Ireland being in both the
                        // United Kingdom and the island of Ireland would not
                        // imply that the British Islands and Ireland overlap —
                        // and the layout would place two sets apart that
                        // demonstrably share ten items.
                        val relaxed = combination - member + ancestor
                        if (relaxed.size >= 2) {
                            val existing = totals[relaxed]
                            if (existing == null || existing < value - TOLERANCE) {
                                totals[relaxed] = value
                                changed = true
                            }
                        }
                    }
                }
            }
            if (!changed) return
        }
    }

    /**
     * The exclusive value of every stated combination.
     *
     * Only stated combinations can be non-empty. An unstated one has `I(S) = 0`
     * by the documented convention, and `E(S) ≤ I(S)`, so enumerating the whole
     * `2^n − 1` lattice would produce a great many provably empty regions. A
     * Venn layout still *draws* those regions — [SetDiagramData] synthesises an
     * empty one on demand — but the model does not carry them.
     */
    private fun regionsFromTotals(
        sets: List<SetDefinition>,
        totals: Map<Set<String>, Double>,
    ): List<SetRegion> {
        val keys = totals.keys.toList()
        return keys
            .map { subset ->
                var exclusive = 0.0
                keys.forEach { superset ->
                    if (superset.containsAll(subset)) {
                        val sign = if ((superset.size - subset.size) % 2 == 0) 1.0 else -1.0
                        exclusive += sign * (totals[superset] ?: 0.0)
                    }
                }
                SetRegion(
                    memberships = subset,
                    kind = if (subset.size == 1) SetRegionKind.Exclusive else SetRegionKind.Intersection,
                    // Clamped at zero: a negative exclusive value means the
                    // input was inconsistent, which the validator has already
                    // reported. Drawing a negative region is not a thing.
                    value = max(0.0, exclusive),
                    totalValue = totals[subset] ?: 0.0,
                )
            }
            .filter { region -> region.memberships.all { id -> sets.any { it.id == id } } }
            .sortedWith(compareBy({ it.size }, { it.id }))
    }

    /** Totals for subsets of size 1..3 only, for an item in implausibly many sets. */
    private fun addSmallSubsets(into: HashMap<Long, Double>, mask: Long, count: Double) {
        val bits = ArrayList<Int>(java.lang.Long.bitCount(mask))
        for (bit in 0 until Long.SIZE_BITS) if (mask and (1L shl bit) != 0L) bits += bit
        for (i in bits.indices) {
            val a = 1L shl bits[i]
            into[a] = (into[a] ?: 0.0) + count
            for (j in i + 1 until bits.size) {
                val ab = a or (1L shl bits[j])
                into[ab] = (into[ab] ?: 0.0) + count
                for (k in j + 1 until bits.size) {
                    val abc = ab or (1L shl bits[k])
                    into[abc] = (into[abc] ?: 0.0) + count
                }
            }
        }
    }

    private fun normalizeSets(
        sets: List<SetDefinition>,
        diagnostics: MutableList<SetDiagnostic>,
    ): List<SetDefinition> {
        val seen = HashSet<String>(sets.size)
        val result = ArrayList<SetDefinition>(sets.size)
        sets.forEach { set ->
            when {
                set.id.isBlank() -> diagnostics += SetDiagnostic(
                    SetDiagnosticSeverity.Error,
                    "A set has a blank id; ids are identity and cannot be empty",
                )

                !seen.add(set.id) -> diagnostics += SetDiagnostic(
                    SetDiagnosticSeverity.Error,
                    "Duplicate set id \"${set.id}\"; the later one was dropped",
                    setOf(set.id),
                )

                !set.value.isFinite() || set.value < 0.0 -> diagnostics += SetDiagnostic(
                    SetDiagnosticSeverity.Error,
                    "Set \"${set.id}\" has an invalid cardinality ${set.value}",
                    setOf(set.id),
                )

                else -> result += set
            }
        }
        return result
    }

    private fun finish(
        data: SetDiagramData,
        diagnostics: List<SetDiagnostic>,
        mode: SetValidationMode,
    ): SetDiagramData {
        val errors = diagnostics.filter { it.severity == SetDiagnosticSeverity.Error }
        if (mode == SetValidationMode.Strict && errors.isNotEmpty()) {
            throw SetDataException(
                "This set system cannot exist:\n" + errors.joinToString("\n") { " • ${it.message}" },
                diagnostics,
            )
        }
        return data.copy(diagnostics = data.diagnostics + diagnostics)
    }

    /** `{a, b}` as `"a & b"`, for a diagnostic. Never for display — see the formatter. */
    internal fun label(sets: Set<String>): String = sets.sorted().joinToString(" & ")

    internal const val TOLERANCE: Double = 1e-9

    /**
     * How many times the containment closure may sweep the totals.
     *
     * Each pass can only add combinations, and there are finitely many, so the
     * loop terminates on its own — the ceiling is there so that a pathological
     * declaration cannot make it take a noticeable amount of time first.
     */
    private const val MAX_CLOSURE_PASSES: Int = 12
}

/**
 * Checks that a set system can exist.
 *
 * ```text
 * A = 10, B = 20, A ∩ B = 50   ← impossible: the shared part cannot exceed either whole
 * ```
 *
 * ### The two rules
 *
 * **Monotonicity.** `I(T) ≤ I(S)` whenever `S ⊆ T`. Adding a set to a
 * combination can only remove items from it, so a triple intersection can never
 * exceed any pair inside it, and no intersection can exceed any of its sets.
 *
 * **Non-negative regions.** Every `E(S)` derived by inclusion–exclusion must be
 * at least zero. This catches inconsistencies monotonicity alone misses — three
 * pairwise overlaps that individually fit but together demand more items than
 * the sets contain.
 *
 * Both are checked, because either alone lets an impossible diagram through.
 */
object SetValidator {

    internal fun validate(
        sets: List<SetDefinition>,
        totals: Map<Set<String>, Double>,
    ): List<SetDiagnostic> {
        val diagnostics = ArrayList<SetDiagnostic>()
        val keys = totals.keys.toList()

        keys.forEach { subset ->
            val subsetValue = totals[subset] ?: 0.0
            if (subsetValue < 0.0) {
                diagnostics += SetDiagnostic(
                    SetDiagnosticSeverity.Error,
                    "${SetAnalyzer.label(subset)} has a negative cardinality $subsetValue",
                    subset,
                )
            }
            keys.forEach { superset ->
                if (superset.size <= subset.size || !superset.containsAll(subset)) return@forEach
                val supersetValue = totals[superset] ?: 0.0
                if (supersetValue > subsetValue + SetAnalyzer.TOLERANCE) {
                    diagnostics += SetDiagnostic(
                        SetDiagnosticSeverity.Error,
                        "${SetAnalyzer.label(superset)} is $supersetValue, which exceeds " +
                            "${SetAnalyzer.label(subset)} at $subsetValue — a combination " +
                            "cannot contain more than any part of it",
                        superset,
                    )
                }
            }
        }

        keys.forEach { subset ->
            var exclusive = 0.0
            keys.forEach { superset ->
                if (superset.containsAll(subset)) {
                    val sign = if ((superset.size - subset.size) % 2 == 0) 1.0 else -1.0
                    exclusive += sign * (totals[superset] ?: 0.0)
                }
            }
            if (exclusive < -SetAnalyzer.TOLERANCE) {
                diagnostics += SetDiagnostic(
                    SetDiagnosticSeverity.Error,
                    "The region for ${SetAnalyzer.label(subset)} alone works out at " +
                        "${round(exclusive)}, which is impossible — the stated overlaps " +
                        "need more items than the sets contain",
                    subset,
                )
            }
        }

        if (sets.isEmpty()) {
            diagnostics += SetDiagnostic(
                SetDiagnosticSeverity.Warning,
                "The diagram has no sets to draw",
            )
        }
        return diagnostics
    }

    private fun round(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value)
}
