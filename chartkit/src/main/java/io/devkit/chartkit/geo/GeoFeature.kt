package io.devkit.chartkit.geo

/**
 * A GeoJSON property value.
 *
 * Typed rather than `Any?`, so a caller reading a join key or a label gets a
 * compile-time answer instead of a cast. GeoJSON properties are ordinary JSON,
 * so the vocabulary is JSON's: a string, a number, a boolean, null, and the two
 * containers.
 *
 * The accessors on [GeoProperties] are what call sites actually use;
 * pattern-matching on these is for the cases that genuinely need it.
 */
sealed interface GeoValue {

    @JvmInline
    value class Text(val value: String) : GeoValue

    @JvmInline
    value class Number(val value: Double) : GeoValue

    @JvmInline
    value class Bool(val value: Boolean) : GeoValue

    /** JSON `null`. Distinct from the property being absent. */
    data object Null : GeoValue

    @JvmInline
    value class Array(val values: List<GeoValue>) : GeoValue

    @JvmInline
    value class Object(val values: Map<String, GeoValue>) : GeoValue
}

/**
 * A feature's properties, with typed lookups.
 *
 * ```kotlin
 * featureKey = { it.properties.string("county_code") }
 * featureLabel = { it.properties.string("name") ?: "Unnamed" }
 * ```
 *
 * Nothing is stripped. A consumer joining on an ISO code, labelling with a
 * name, colouring by a category and drilling down through a parent id needs all
 * four, and a parser that kept only the ones it recognised would make the last
 * three impossible.
 *
 * ### Numbers and strings are not kept apart
 *
 * [string] renders a numeric property as a string, and [number] parses a
 * numeric string. Real administrative datasets are inconsistent about which
 * they use for the same code — `"047"` in one file and `47` in another — and a
 * join that failed silently over that would be the single most frustrating
 * thing about this API. [rawString] is there for a caller who needs to know
 * which it actually was.
 */
class GeoProperties(private val values: Map<String, GeoValue>) {

    val keys: Set<String> get() = values.keys

    val isEmpty: Boolean get() = values.isEmpty()

    /** The raw value, or `null` when the key is absent. */
    operator fun get(key: String): GeoValue? = values[key]

    operator fun contains(key: String): Boolean = key in values

    /**
     * [key] as a string, converting a number or boolean.
     *
     * `null` when the key is absent or holds JSON `null`, an array or an
     * object — none of which is a sensible join key or label.
     */
    fun string(key: String): String? = when (val value = values[key]) {
        is GeoValue.Text -> value.value
        is GeoValue.Number -> formatNumber(value.value)
        is GeoValue.Bool -> value.value.toString()
        else -> null
    }

    /** [key] only when it genuinely was a JSON string. */
    fun rawString(key: String): String? = (values[key] as? GeoValue.Text)?.value

    /** [key] as a number, parsing a numeric string. */
    fun number(key: String): Double? = when (val value = values[key]) {
        is GeoValue.Number -> value.value
        is GeoValue.Text -> value.value.trim().toDoubleOrNull()
        else -> null
    }

    fun boolean(key: String): Boolean? = when (val value = values[key]) {
        is GeoValue.Bool -> value.value
        is GeoValue.Text -> value.value.toBooleanStrictOrNull()
        else -> null
    }

    /** Everything, for a caller that wants to iterate. */
    fun asMap(): Map<String, GeoValue> = values

    override fun toString(): String = "GeoProperties(${values.keys.joinToString()})"

    companion object {
        val Empty: GeoProperties = GeoProperties(emptyMap())

        /**
         * A whole number is rendered without a decimal point.
         *
         * `47` rather than `47.0`, because a code read out of a numeric JSON
         * field is being compared against a string somewhere, and `"47.0"`
         * matches nothing.
         */
        private fun formatNumber(value: Double): String =
            if (value.isFinite() && value == value.toLong().toDouble()) {
                value.toLong().toString()
            } else {
                value.toString()
            }
    }
}

/**
 * One geographic thing: an id, its properties, and its shape.
 *
 * The unit a choropleth colours, selects and announces. A feature is *one*
 * thematic region however many disconnected polygons it is drawn from — an
 * archipelago is one country, and selecting any island selects the country.
 *
 * @param id the GeoJSON `id` member, when the file had one. Usually absent;
 *   the join key normally comes out of [properties] instead.
 */
class GeoFeature(
    val id: String?,
    val properties: GeoProperties,
    val geometry: GeoGeometry,
) {
    /** The feature's own bounding box, computed once. */
    val bounds: GeoBounds? by lazy(LazyThreadSafetyMode.NONE) { geometry.bounds() }

    /** True when there is a shape a map could draw. */
    val isDrawable: Boolean get() = geometry.isDrawable

    override fun toString(): String = "GeoFeature(id=$id, ${geometry::class.simpleName})"
}

/**
 * A collection of features, as a GeoJSON `FeatureCollection`.
 *
 * @param skipped features the parser could not use, and why. Reported rather
 *   than hidden: a map quietly missing three counties because their geometry
 *   was malformed looks like a map, and the reader has no way to tell.
 */
class GeoFeatureCollection(
    val features: List<GeoFeature>,
    val skipped: List<GeoParseIssue> = emptyList(),
) {
    val size: Int get() = features.size

    val isEmpty: Boolean get() = features.none { it.isDrawable }

    /** The box containing every feature, or `null` when there is nothing to fit. */
    val bounds: GeoBounds? by lazy(LazyThreadSafetyMode.NONE) {
        GeoBounds.union(features.mapNotNull { it.bounds })
    }

    /** The feature whose [GeoFeature.id] matches, or `null`. */
    fun byId(id: String): GeoFeature? = features.firstOrNull { it.id == id }

    companion object {
        val Empty: GeoFeatureCollection = GeoFeatureCollection(emptyList())
    }
}

/**
 * Something in the source that could not be turned into geometry.
 *
 * @param index the feature's position in the source collection.
 * @param reason a sentence naming what was wrong, for a log or a diagnostic
 *   panel — not for an end user.
 */
data class GeoParseIssue(val index: Int, val identifier: String?, val reason: String)
