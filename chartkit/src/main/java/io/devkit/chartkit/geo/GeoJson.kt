package io.devkit.chartkit.geo

/**
 * What to do with a feature whose geometry cannot be drawn.
 *
 * Malformed geography is normal. Public boundary files contain empty rings,
 * two-point "polygons", coordinates outside the earth and features with a null
 * geometry, and a library that crashed on any of them would be unusable against
 * real data.
 */
enum class GeoParsePolicy {

    /**
     * Drop the feature and record why. The default.
     *
     * The map draws, and [GeoFeatureCollection.skipped] says exactly what was
     * left out — which is what makes the omission discoverable instead of
     * mysterious.
     */
    Skip,

    /**
     * Throw, naming the first problem found.
     *
     * For a build-time check over a dataset that is supposed to be clean.
     */
    Reject,
}

/** A GeoJSON document that could not be read at all. */
class GeoJsonException(message: String) : IllegalArgumentException(message)

/**
 * Reads GeoJSON into [GeoFeatureCollection].
 *
 * ```kotlin
 * val counties = GeoJson.parse(context.assets.open("counties.geojson").reader().readText())
 * ```
 *
 * ### Why a hand-written reader
 *
 * ChartKit is a **release-safe runtime** library: it reaches every consumer's
 * production build. A JSON library would be a few hundred kilobytes in every
 * one of those APKs for a feature most of them will not use, and
 * `kotlinx.serialization` additionally needs its compiler plugin applied to the
 * module. `org.json` is free on Android but absent from the JVM unit-test
 * classpath this module tests on, which would leave the parser — the part most
 * worth testing — untestable.
 *
 * GeoJSON's grammar is small and fixed, so the reader below is a few hundred
 * lines of plain Kotlin with no dependency, no reflection and no code
 * generation, and it is verified directly on the JVM. The parsed model is a
 * plain Kotlin one, so a caller who would rather parse with their own library
 * can construct [GeoFeature] values themselves and never touch this.
 *
 * ### What is supported
 *
 * ```text
 * FeatureCollection · Feature
 * Point · MultiPoint · LineString · MultiLineString
 * Polygon · MultiPolygon · GeometryCollection
 * ```
 *
 * Every GeoJSON geometry type, in other words. A bare geometry object is also
 * accepted and read as a single unlabelled feature.
 *
 * Which of them a given *layer* draws is a separate question: a choropleth
 * shades areas and ignores lines, and a route layer does the reverse. That
 * split is deliberate — the parser's job is to preserve what the file said, and
 * a file carrying both country outlines and shipping lanes should not have to
 * be loaded twice.
 *
 * ### Parse once, off the composition
 *
 * Parsing is ordinary blocking work over a potentially large string. Do it in a
 * repository, a `ViewModel`, or a `remember` keyed on the source — never inside
 * a draw pass, and never unkeyed in composition where every recomposition would
 * repeat it.
 */
object GeoJson {

    /**
     * Parses a GeoJSON document.
     *
     * @throws GeoJsonException when the text is not JSON, or is JSON that is
     *   not GeoJSON. That is a programming or asset error rather than a data
     *   condition, so it throws under both policies.
     */
    fun parse(text: String, policy: GeoParsePolicy = GeoParsePolicy.Skip): GeoFeatureCollection {
        val root = JsonReader(text).readValue()
        return fromJson(root, policy)
    }

    /** Parses a document that has already been read into a JSON tree. */
    internal fun fromJson(root: JsonValue, policy: GeoParsePolicy): GeoFeatureCollection {
        val obj = root as? JsonValue.Obj
            ?: throw GeoJsonException("GeoJSON must be a JSON object, was ${root.describe()}")

        return when (val type = (obj["type"] as? JsonValue.Text)?.value) {
            "FeatureCollection" -> {
                val array = obj["features"] as? JsonValue.Arr
                    ?: throw GeoJsonException("A FeatureCollection needs a \"features\" array")
                readFeatures(array.values, policy)
            }

            "Feature" -> readFeatures(listOf(obj), policy)

            null -> throw GeoJsonException("GeoJSON needs a \"type\" member")

            // A bare geometry is valid GeoJSON and a common way to hand over a
            // single shape. It has no properties, so it can carry no join key —
            // which is worth allowing but not worth pretending otherwise about.
            else -> {
                val geometry = readGeometry(obj)
                    ?: throw GeoJsonException("Unsupported GeoJSON type \"$type\"")
                GeoFeatureCollection(
                    listOf(GeoFeature(null, GeoProperties.Empty, geometry)),
                )
            }
        }
    }

    private fun readFeatures(
        raw: List<JsonValue>,
        policy: GeoParsePolicy,
    ): GeoFeatureCollection {
        val features = ArrayList<GeoFeature>(raw.size)
        val skipped = ArrayList<GeoParseIssue>()

        raw.forEachIndexed { index, element ->
            val obj = element as? JsonValue.Obj
            if (obj == null) {
                record(policy, skipped, index, null, "feature was not an object")
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

            val geometryObject = obj["geometry"] as? JsonValue.Obj
            if (geometryObject == null) {
                record(policy, skipped, index, id, "feature has no geometry")
                return@forEachIndexed
            }

            val geometryType = (geometryObject["type"] as? JsonValue.Text)?.value
            val geometry = readGeometry(geometryObject)
            when {
                geometry == null ->
                    record(policy, skipped, index, id, "unsupported geometry \"$geometryType\"")

                !geometry.isDrawable ->
                    record(policy, skipped, index, id, "geometry has no drawable rings or points")

                else -> features += GeoFeature(id, properties, geometry)
            }
        }
        return GeoFeatureCollection(features, skipped)
    }

    private fun record(
        policy: GeoParsePolicy,
        into: MutableList<GeoParseIssue>,
        index: Int,
        id: String?,
        reason: String,
    ) {
        if (policy == GeoParsePolicy.Reject) {
            throw GeoJsonException("Feature ${id ?: "#$index"}: $reason")
        }
        into += GeoParseIssue(index, id, reason)
    }

    /** One geometry object, or `null` when its type is not a GeoJSON geometry. */
    internal fun readGeometry(obj: JsonValue.Obj): GeoGeometry? {
        val type = (obj["type"] as? JsonValue.Text)?.value

        // A GeometryCollection has "geometries" where every other type has
        // "coordinates", so it is answered before the coordinates are required.
        if (type == "GeometryCollection") {
            val members = obj["geometries"] as? JsonValue.Arr ?: return null
            val parsed = members.values.mapNotNull { element ->
                readGeometry(element as? JsonValue.Obj ?: return@mapNotNull null)
            }
            return if (parsed.isEmpty()) null else GeoGeometry.Collection(parsed)
        }

        val coordinates = obj["coordinates"] as? JsonValue.Arr ?: return null
        return when (type) {
            "Point" -> readPosition(coordinates)?.let(GeoGeometry::Point)

            "MultiPoint" -> GeoGeometry.MultiPoint(
                coordinates.values.mapNotNull { readPosition(it as? JsonValue.Arr ?: return@mapNotNull null) },
            )

            "LineString" -> readLine(coordinates)?.let(GeoGeometry::LineString)

            "MultiLineString" -> GeoGeometry.MultiLineString(
                coordinates.values.mapNotNull { element ->
                    readLine(element as? JsonValue.Arr ?: return@mapNotNull null)
                },
            )

            "Polygon" -> readPolygon(coordinates)?.let(GeoGeometry::Polygon)

            "MultiPolygon" -> GeoGeometry.MultiPolygon(
                coordinates.values.mapNotNull { element ->
                    readPolygon(element as? JsonValue.Arr ?: return@mapNotNull null)
                },
            )

            else -> null
        }
    }

    /** One open path. `null` when it has fewer than two usable positions. */
    private fun readLine(array: JsonValue.Arr): GeoLine? {
        val line = GeoLine.of(
            array.values.mapNotNull { readPosition(it as? JsonValue.Arr ?: return@mapNotNull null) },
        )
        return line.takeIf { it.isValid }
    }

    /**
     * `[longitude, latitude]`, ignoring any third element.
     *
     * GeoJSON positions may carry an elevation. A thematic map has nothing to
     * do with it, and dropping it here is better than carrying a dimension
     * nothing reads.
     */
    private fun readPosition(array: JsonValue.Arr): GeoCoordinate? {
        val longitude = (array.values.getOrNull(0) as? JsonValue.Number)?.value ?: return null
        val latitude = (array.values.getOrNull(1) as? JsonValue.Number)?.value ?: return null
        if (!longitude.isFinite() || !latitude.isFinite()) return null
        return GeoCoordinate(longitude, latitude)
    }

    /** A polygon's rings: the first is the outer boundary, the rest are holes. */
    private fun readPolygon(array: JsonValue.Arr): GeoPolygon? {
        val rings = array.values.mapNotNull { element ->
            val ring = element as? JsonValue.Arr ?: return@mapNotNull null
            GeoRing.of(ring.values.mapNotNull { readPosition(it as? JsonValue.Arr ?: return@mapNotNull null) })
        }
        val outer = rings.firstOrNull() ?: return null
        if (!outer.isValid) return null
        return GeoPolygon(outer, rings.drop(1).filter { it.isValid })
    }

    private fun formatId(value: Double): String =
        if (value.isFinite() && value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
}

/** A parsed JSON value. Internal: the public model is [GeoFeature] and friends. */
internal sealed interface JsonValue {

    fun describe(): String = when (this) {
        is Obj -> "an object"
        is Arr -> "an array"
        is Text -> "a string"
        is Number -> "a number"
        is Bool -> "a boolean"
        Null -> "null"
    }

    class Obj(val values: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = values[key]
    }

    class Arr(val values: List<JsonValue>) : JsonValue

    class Text(val value: String) : JsonValue

    class Number(val value: Double) : JsonValue

    class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

/** Converts a parsed JSON value to the public property model. */
internal fun JsonValue.toGeoValue(): GeoValue = when (this) {
    is JsonValue.Text -> GeoValue.Text(value)
    is JsonValue.Number -> GeoValue.Number(value)
    is JsonValue.Bool -> GeoValue.Bool(value)
    JsonValue.Null -> GeoValue.Null
    is JsonValue.Arr -> GeoValue.Array(values.map { it.toGeoValue() })
    is JsonValue.Obj -> GeoValue.Object(values.mapValues { it.value.toGeoValue() })
}

/**
 * A minimal recursive-descent JSON reader.
 *
 * Enough of RFC 8259 to read GeoJSON correctly, and no more: objects, arrays,
 * strings with escapes, numbers, the three literals. It is not a general JSON
 * library and does not try to be — it exists so that ChartKit can read a
 * boundary file without putting a dependency into every consumer's release
 * build.
 *
 * Iterative where it matters. Coordinate arrays nest only four deep in the
 * worst case (`MultiPolygon` → polygon → ring → position), so the recursion is
 * bounded by the *grammar* rather than by the file, and a ten-megabyte boundary
 * file cannot overflow the stack.
 */
internal class JsonReader(private val source: String) {

    private var index = 0

    fun readValue(): JsonValue {
        skipWhitespace()
        val value = readAny()
        skipWhitespace()
        if (index < source.length) {
            fail("unexpected trailing content")
        }
        return value
    }

    private fun readAny(): JsonValue {
        skipWhitespace()
        if (index >= source.length) fail("unexpected end of input")
        return when (source[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Text(readString())
            't' -> readLiteral("true").let { JsonValue.Bool(true) }
            'f' -> readLiteral("false").let { JsonValue.Bool(false) }
            'n' -> readLiteral("null").let { JsonValue.Null }
            else -> JsonValue.Number(readNumber())
        }
    }

    private fun readObject(): JsonValue.Obj {
        expect('{')
        skipWhitespace()
        // LinkedHashMap: GeoJSON property order is meaningful to a human
        // reading a diagnostic, and costs nothing to preserve.
        val values = LinkedHashMap<String, JsonValue>()
        if (peek() == '}') {
            index++
            return JsonValue.Obj(values)
        }
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            expect(':')
            values[key] = readAny()
            skipWhitespace()
            when (val character = peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return JsonValue.Obj(values)
                }
                else -> fail("expected ',' or '}' but found '$character'")
            }
        }
    }

    private fun readArray(): JsonValue.Arr {
        expect('[')
        skipWhitespace()
        val values = ArrayList<JsonValue>()
        if (peek() == ']') {
            index++
            return JsonValue.Arr(values)
        }
        while (true) {
            values += readAny()
            skipWhitespace()
            when (val character = peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return JsonValue.Arr(values)
                }
                else -> fail("expected ',' or ']' but found '$character'")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val start = index
        // The common case — no escape anywhere — is a substring rather than a
        // character-by-character copy. Property values are short, but a large
        // boundary file has a great many of them.
        while (index < source.length) {
            when (source[index]) {
                '"' -> {
                    val value = source.substring(start, index)
                    index++
                    return value
                }
                '\\' -> return readEscapedString(start)
                else -> index++
            }
        }
        fail("unterminated string")
    }

    private fun readEscapedString(start: Int): String {
        val builder = StringBuilder().append(source, start, index)
        while (index < source.length) {
            when (val character = source[index]) {
                '"' -> {
                    index++
                    return builder.toString()
                }
                '\\' -> {
                    index++
                    if (index >= source.length) fail("unterminated escape")
                    when (val escape = source[index]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            if (index + 4 >= source.length) fail("truncated unicode escape")
                            val hex = source.substring(index + 1, index + 5)
                            val code = hex.toIntOrNull(16) ?: fail("bad unicode escape \\u$hex")
                            builder.append(code.toChar())
                            index += 4
                        }
                        else -> fail("unknown escape '\\$escape'")
                    }
                    index++
                }
                else -> {
                    builder.append(character)
                    index++
                }
            }
        }
        fail("unterminated string")
    }

    private fun readNumber(): Double {
        val start = index
        if (peek() == '-' || peek() == '+') index++
        while (index < source.length) {
            val character = source[index]
            if (character.isDigit() || character == '.' || character == 'e' ||
                character == 'E' || character == '+' || character == '-'
            ) {
                index++
            } else {
                break
            }
        }
        if (start == index) fail("expected a number")
        val text = source.substring(start, index)
        return text.toDoubleOrNull() ?: fail("'$text' is not a number")
    }

    private fun readLiteral(literal: String) {
        if (!source.startsWith(literal, index)) fail("expected '$literal'")
        index += literal.length
    }

    private fun peek(): Char =
        if (index < source.length) source[index] else fail("unexpected end of input")

    private fun expect(character: Char) {
        if (index >= source.length || source[index] != character) {
            fail("expected '$character'")
        }
        index++
    }

    private fun skipWhitespace() {
        while (index < source.length) {
            when (source[index]) {
                ' ', '\t', '\n', '\r' -> index++
                else -> return
            }
        }
    }

    /**
     * Fails with the offset, which is the only thing that makes a parse error
     * in a ten-megabyte file actionable.
     */
    private fun fail(reason: String): Nothing =
        throw GeoJsonException("Invalid JSON at offset $index: $reason")
}
