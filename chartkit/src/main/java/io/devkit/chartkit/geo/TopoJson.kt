package io.devkit.chartkit.geo

/** A TopoJSON document that could not be read at all. */
class TopoJsonException(message: String) : IllegalArgumentException(message)

/**
 * Reads TopoJSON into the same [GeoFeatureCollection] GeoJSON produces.
 *
 * ```kotlin
 * val world = TopoJson.parse(assets.readText("world.topojson"), objectName = "countries")
 * WorldMap(geometry = world)
 * ```
 *
 * ### Why bother, when GeoJSON already works
 *
 * TopoJSON stores each shared boundary **once**. Two counties that meet along a
 * river are described by one arc that both reference, rather than by two copies
 * of the same few thousand vertices. For an administrative boundary file that
 * is routinely an 80% reduction in size, and the file a caller ships in their
 * APK or downloads over a mobile connection is the file that matters.
 *
 * It also removes a class of visual defect: because the shared boundary is
 * literally the same coordinates, two neighbouring regions cannot disagree
 * about where their border is by a rounding error.
 *
 * ### The pipeline
 *
 * ```text
 * Topology
 *    ↓  decode arcs      (delta-encoded integers → running totals)
 *    ↓  apply transform  (× scale + translate)
 *    ↓  assemble         (arc references stitched into rings and paths)
 * GeoFeatureCollection
 * ```
 *
 * After the last step nothing downstream can tell which format the geometry
 * came from — the projection, the fit, the layers, the hit test and the label
 * placement all see the same model. That is the point of normalising rather
 * than carrying a second geometry type through the renderer.
 *
 * ### Arcs are decoded once
 *
 * A topology's arcs are decoded a single time into shared coordinate lists, and
 * assembling a geometry copies **references** to those coordinates rather than
 * re-running the delta arithmetic. A file whose thousand regions reference four
 * thousand arcs decodes four thousand arcs, not a hundred thousand. Holding the
 * [TopoJsonTopology] and asking it for several objects — countries, then lakes,
 * then rivers — reuses that decoding across all of them.
 *
 * ### No JavaScript, at build time or at run time
 *
 * The decoder is plain Kotlin over the same hand-written [JsonReader] GeoJSON
 * uses. There is no `topojson-client`, no Node step and no dependency; see
 * [GeoJson] for why ChartKit parses JSON itself.
 */
object TopoJson {

    /**
     * Decodes a document and builds one object's features.
     *
     * @param objectName which member of the topology's `objects` to read. May
     *   be omitted only when the file has exactly one — guessing between
     *   `countries` and `land` would silently draw the wrong map.
     * @throws TopoJsonException when the text is not a topology, or names an
     *   object the file does not contain.
     */
    fun parse(
        text: String,
        objectName: String? = null,
        policy: GeoParsePolicy = GeoParsePolicy.Skip,
    ): GeoFeatureCollection = topology(text).feature(objectName, policy)

    /**
     * Decodes a document without assembling any geometry yet.
     *
     * For a file holding several objects. The arcs are decoded once, here, and
     * every [TopoJsonTopology.feature] call reuses them.
     */
    fun topology(text: String): TopoJsonTopology {
        val root = JsonReader(text).readValue() as? JsonValue.Obj
            ?: throw TopoJsonException("TopoJSON must be a JSON object")

        val type = (root["type"] as? JsonValue.Text)?.value
        if (type != "Topology") {
            throw TopoJsonException(
                "TopoJSON needs \"type\": \"Topology\", found ${type?.let { "\"$it\"" } ?: "nothing"}",
            )
        }

        val objects = root["objects"] as? JsonValue.Obj
            ?: throw TopoJsonException("A Topology needs an \"objects\" member")
        val rawArcs = root["arcs"] as? JsonValue.Arr
            ?: throw TopoJsonException("A Topology needs an \"arcs\" array")

        val transform = (root["transform"] as? JsonValue.Obj)?.let { readTransform(it) }
        val arcs = rawArcs.values.map { decodeArc(it as? JsonValue.Arr, transform) }

        return TopoJsonTopology(objects.values, arcs, transform)
    }

    /**
     * The quantisation a topology was written with.
     *
     * Absent from an unquantised file, in which case positions are ordinary
     * absolute degrees and nothing needs scaling.
     */
    private fun readTransform(obj: JsonValue.Obj): TopoTransform? {
        val scale = obj["scale"] as? JsonValue.Arr
        val translate = obj["translate"] as? JsonValue.Arr
        val scaleX = (scale?.values?.getOrNull(0) as? JsonValue.Number)?.value ?: return null
        val scaleY = (scale.values.getOrNull(1) as? JsonValue.Number)?.value ?: return null
        val translateX = (translate?.values?.getOrNull(0) as? JsonValue.Number)?.value ?: 0.0
        val translateY = (translate?.values?.getOrNull(1) as? JsonValue.Number)?.value ?: 0.0
        return TopoTransform(scaleX, scaleY, translateX, translateY)
    }

    /**
     * One arc, delta-decoded and un-quantised.
     *
     * A quantised arc stores its first position absolutely and every later one
     * as an offset from the one before, in integer quantisation units. The
     * running totals are accumulated **before** scaling, because that is the
     * space the deltas are expressed in — scaling each delta first and summing
     * afterwards accumulates a different rounding error at every vertex.
     */
    private fun decodeArc(array: JsonValue.Arr?, transform: TopoTransform?): List<GeoCoordinate> {
        if (array == null) return emptyList()
        val points = ArrayList<GeoCoordinate>(array.values.size)
        var x = 0.0
        var y = 0.0
        array.values.forEach { element ->
            val position = element as? JsonValue.Arr ?: return@forEach
            val first = (position.values.getOrNull(0) as? JsonValue.Number)?.value ?: return@forEach
            val second = (position.values.getOrNull(1) as? JsonValue.Number)?.value ?: return@forEach
            if (transform == null) {
                points += GeoCoordinate(first, second)
            } else {
                x += first
                y += second
                points += transform.apply(x, y)
            }
        }
        return points
    }
}

/** A topology's quantisation: multiply, then shift. */
internal class TopoTransform(
    val scaleX: Double,
    val scaleY: Double,
    val translateX: Double,
    val translateY: Double,
) {
    fun apply(x: Double, y: Double): GeoCoordinate =
        GeoCoordinate(x * scaleX + translateX, y * scaleY + translateY)
}

/**
 * A decoded topology: its arcs, and the objects that reference them.
 *
 * Hold one when a file carries several layers. The arcs — the expensive part —
 * are decoded once by [TopoJson.topology], and each [feature] call only stitches
 * references to them.
 */
class TopoJsonTopology internal constructor(
    private val objects: Map<String, JsonValue>,
    private val arcs: List<List<GeoCoordinate>>,
    @Suppress("unused") private val transform: TopoTransform?,
) {
    /** The names in the topology's `objects` member, in file order. */
    val objectNames: List<String> get() = objects.keys.toList()

    /** How many arcs the file describes. Useful in a diagnostic. */
    val arcCount: Int get() = arcs.size

    /**
     * One object's features.
     *
     * @param objectName may be omitted only when there is exactly one object.
     */
    fun feature(
        objectName: String? = null,
        policy: GeoParsePolicy = GeoParsePolicy.Skip,
    ): GeoFeatureCollection {
        val name = objectName ?: objects.keys.singleOrNull() ?: throw TopoJsonException(
            "This topology holds ${objects.size} objects (${objects.keys.joinToString()}); " +
                "name the one to read.",
        )
        val root = objects[name] as? JsonValue.Obj ?: throw TopoJsonException(
            "No object named \"$name\". This topology holds ${objects.keys.joinToString()}.",
        )

        val features = ArrayList<GeoFeature>()
        val skipped = ArrayList<GeoParseIssue>()

        // A topology's top-level object is normally a GeometryCollection whose
        // members are the regions; a single-shape object is legal too, and is
        // read as one feature rather than rejected.
        val members = if ((root["type"] as? JsonValue.Text)?.value == "GeometryCollection") {
            (root["geometries"] as? JsonValue.Arr)?.values.orEmpty()
        } else {
            listOf(root)
        }

        members.forEachIndexed { index, element ->
            val obj = element as? JsonValue.Obj
            if (obj == null) {
                record(policy, skipped, index, null, "geometry was not an object")
                return@forEachIndexed
            }
            val id = when (val value = obj["id"]) {
                is JsonValue.Text -> value.value
                is JsonValue.Number -> formatId(value.value)
                else -> null
            }
            val properties = (obj["properties"] as? JsonValue.Obj)
                ?.let { GeoProperties(it.values.mapValues { entry -> entry.value.toGeoValue() }) }
                ?: GeoProperties.Empty

            val type = (obj["type"] as? JsonValue.Text)?.value
            val geometry = readGeometry(obj)
            when {
                geometry == null ->
                    record(policy, skipped, index, id, "unsupported geometry \"$type\"")
                !geometry.isDrawable ->
                    record(policy, skipped, index, id, "geometry has no drawable rings or points")
                else -> features += GeoFeature(id, properties, geometry)
            }
        }
        return GeoFeatureCollection(features, skipped)
    }

    /** One topology geometry, or `null` when its type is not one TopoJSON defines. */
    private fun readGeometry(obj: JsonValue.Obj): GeoGeometry? {
        return when ((obj["type"] as? JsonValue.Text)?.value) {
            // Points carry coordinates directly: they reference no arc, because
            // a single position shares no boundary with anything.
            "Point" -> readPosition(obj["coordinates"] as? JsonValue.Arr)?.let(GeoGeometry::Point)

            "MultiPoint" -> {
                val positions = (obj["coordinates"] as? JsonValue.Arr)?.values.orEmpty()
                GeoGeometry.MultiPoint(
                    positions.mapNotNull { readPosition(it as? JsonValue.Arr) },
                )
            }

            "LineString" -> stitch(obj["arcs"] as? JsonValue.Arr)
                .takeIf { it.size >= GeoLine.MIN_LINE_POINTS }
                ?.let { GeoGeometry.LineString(GeoLine(it)) }

            "MultiLineString" -> {
                val lines = (obj["arcs"] as? JsonValue.Arr)?.values.orEmpty()
                    .map { stitch(it as? JsonValue.Arr) }
                    .filter { it.size >= GeoLine.MIN_LINE_POINTS }
                    .map { GeoLine(it) }
                if (lines.isEmpty()) null else GeoGeometry.MultiLineString(lines)
            }

            "Polygon" -> readPolygon(obj["arcs"] as? JsonValue.Arr)?.let(GeoGeometry::Polygon)

            "MultiPolygon" -> {
                val polygons = (obj["arcs"] as? JsonValue.Arr)?.values.orEmpty()
                    .mapNotNull { readPolygon(it as? JsonValue.Arr) }
                if (polygons.isEmpty()) null else GeoGeometry.MultiPolygon(polygons)
            }

            "GeometryCollection" -> {
                val members = (obj["geometries"] as? JsonValue.Arr)?.values.orEmpty()
                    .mapNotNull { readGeometry(it as? JsonValue.Obj ?: return@mapNotNull null) }
                if (members.isEmpty()) null else GeoGeometry.Collection(members)
            }

            else -> null
        }
    }

    /** A polygon's rings: the first is the outer boundary, the rest are holes. */
    private fun readPolygon(array: JsonValue.Arr?): GeoPolygon? {
        val rings = array?.values.orEmpty()
            .map { GeoRing.of(stitch(it as? JsonValue.Arr)) }
        val outer = rings.firstOrNull() ?: return null
        if (!outer.isValid) return null
        return GeoPolygon(outer, rings.drop(1).filter { it.isValid })
    }

    /**
     * One ring or path, stitched from its arc references.
     *
     * ### Negative indices
     *
     * A reference of `-1` means "arc 0, backwards"; `-2` means arc 1 backwards,
     * and so on — the one's complement, `~i`. This is how a shared boundary
     * serves both regions that meet along it: one traverses it clockwise and
     * the other anticlockwise, from the same stored coordinates.
     *
     * ### The shared endpoint
     *
     * Consecutive arcs in a ring meet at a position both of them contain, so
     * the first point of every arc after the first is dropped. Keeping it would
     * put a duplicate vertex at every arc junction — harmless to look at, and
     * quietly wrong for any winding, area or simplification calculation that
     * follows.
     */
    private fun stitch(references: JsonValue.Arr?): List<GeoCoordinate> {
        val values = references?.values.orEmpty()
        if (values.isEmpty()) return emptyList()
        val result = ArrayList<GeoCoordinate>()
        values.forEach { element ->
            val raw = (element as? JsonValue.Number)?.value ?: return@forEach
            val index = raw.toInt()
            val arc = if (index < 0) {
                arcs.getOrNull(index.inv())?.asReversed()
            } else {
                arcs.getOrNull(index)
            } ?: return@forEach
            if (arc.isEmpty()) return@forEach
            if (result.isEmpty()) result += arc else result += arc.subList(1, arc.size)
        }
        return result
    }

    private fun readPosition(array: JsonValue.Arr?): GeoCoordinate? {
        val first = (array?.values?.getOrNull(0) as? JsonValue.Number)?.value ?: return null
        val second = (array.values.getOrNull(1) as? JsonValue.Number)?.value ?: return null
        if (!first.isFinite() || !second.isFinite()) return null
        // A quantised topology quantises its points as well as its arcs, but
        // does not delta-encode them: each position stands alone.
        return transform?.apply(first, second) ?: GeoCoordinate(first, second)
    }

    private fun record(
        policy: GeoParsePolicy,
        into: MutableList<GeoParseIssue>,
        index: Int,
        id: String?,
        reason: String,
    ) {
        if (policy == GeoParsePolicy.Reject) {
            throw TopoJsonException("Geometry ${id ?: "#$index"}: $reason")
        }
        into += GeoParseIssue(index, id, reason)
    }
}

private fun formatId(value: Double): String =
    if (value.isFinite() && value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        value.toString()
    }
