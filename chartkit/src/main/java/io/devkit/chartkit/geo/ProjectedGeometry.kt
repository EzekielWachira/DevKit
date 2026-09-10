package io.devkit.chartkit.geo

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One feature after projection: its polygons, its markers, its box and where
 * its label goes.
 *
 * Computed **once** per geometry-and-projection pair and reused for every
 * frame, every selection change and every value change. That split is the whole
 * reason [io.devkit.chartkit.layer.geo.ChoroplethLayer] can animate a colour
 * transition without touching a single coordinate: the geography is here, and
 * the thematic style is somewhere else entirely.
 *
 * @param index the feature's position in the source collection, which is what
 *   the join, the selection and the spatial index all address it by.
 */
internal class ProjectedFeature(
    val index: Int,
    val feature: GeoFeature,
    val polygons: List<ProjectedPolygon>,
    val markers: List<ProjectedPoint>,
    val lines: List<List<ProjectedPoint>> = emptyList(),
) {
    val bounds: ProjectedBox = ProjectedBox.union(
        polygons.map { it.bounds } + lines.map { ProjectedBox.of(it) } +
            listOf(ProjectedBox.of(markers)),
    )

    /**
     * Where a label for this feature belongs, or `null` when there is nowhere.
     *
     * Areas first, then a line's midpoint, then a marker. A feature with all
     * three — a country, its coastline and its capital — is labelled on the
     * country, which is what a reader expects.
     */
    val labelPoint: ProjectedPoint? by lazy(LazyThreadSafetyMode.NONE) {
        GeoGeometryMath.labelPoint(polygons)
            ?: lines.firstOrNull { it.isNotEmpty() }?.let { it[it.size / 2] }
            ?: markers.firstOrNull()
    }

    val vertexCount: Int
        get() = polygons.sumOf { it.vertexCount } + lines.sumOf { it.size } + markers.size

    val isDrawable: Boolean
        get() = polygons.any { it.isValid } || markers.isNotEmpty() ||
            lines.any { it.size >= GeoLine.MIN_LINE_POINTS }

    /**
     * True when [x], [y] is inside any of the feature's polygons and outside
     * every hole.
     *
     * "Any" is what makes a multi-polygon one region: tapping an island selects
     * the country it belongs to, because the country is the feature and the
     * island is one of its components.
     */
    fun contains(x: Double, y: Double): Boolean {
        if (!bounds.contains(x, y)) return false
        return polygons.any { GeoGeometryMath.polygonContains(it, x, y) }
    }

    /**
     * How far the nearest line or marker of this feature is from the point.
     *
     * A line has no interior, so "inside" is not a question that can be asked
     * of a route — it is selected by being **near** the pointer instead, which
     * is why this returns a distance and [contains] returns a verdict. The
     * caller supplies the tolerance, because how near is near enough is a
     * question about fingers and pixels, not about geometry.
     *
     * [Double.POSITIVE_INFINITY] when the feature has neither lines nor
     * markers, so a caller can compare the result without a null check.
     */
    fun distanceToStrokes(x: Double, y: Double): Double {
        var best = Double.POSITIVE_INFINITY
        lines.forEach { line ->
            val distance = GeoGeometryMath.distanceToPath(line, x, y)
            if (distance < best) best = distance
        }
        markers.forEach { marker ->
            val dx = marker.x - x
            val dy = marker.y - y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (distance < best) best = distance
        }
        return best
    }
}

/**
 * A whole feature collection, projected.
 *
 * ### Why this is a separate object from the collection
 *
 * Geography and thematic data change at completely different rates. A county
 * boundary file is loaded once and never changes; the statistic painted onto it
 * changes whenever the reader picks a different year, metric or filter.
 * Rebuilding a hundred thousand projected vertices because a number changed
 * would be the single most expensive mistake this feature could make, so the
 * projection result lives here and the values live in the layer.
 *
 * @param simplificationTolerance the tolerance actually applied, for
 *   diagnostics — `0` when nothing was simplified.
 */
internal class ProjectedGeometry(
    val features: List<ProjectedFeature>,
    val bounds: ProjectedBox,
    val simplificationTolerance: Double = 0.0,
) {
    val isEmpty: Boolean get() = features.none { it.isDrawable } || bounds.isEmpty

    /** Total vertices across every feature — what the performance note reports. */
    val vertexCount: Int by lazy(LazyThreadSafetyMode.NONE) {
        features.sumOf { it.vertexCount }
    }

    /** The spatial index over these features, built once. */
    val index: GeoSpatialIndex by lazy(LazyThreadSafetyMode.NONE) {
        GeoSpatialIndex.build(features, bounds)
    }

    companion object {
        val Empty: ProjectedGeometry = ProjectedGeometry(emptyList(), ProjectedBox.Empty)

        /**
         * Projects every feature in [collection].
         *
         * @param simplification vertices closer than this to the line they sit
         *   on are dropped, in **projected units**. Zero keeps every vertex.
         */
        fun of(
            collection: GeoFeatureCollection,
            projection: GeoProjection,
            simplification: Double = 0.0,
        ): ProjectedGeometry {
            if (collection.features.isEmpty()) return Empty

            // Repaired **before** projection, because by the time a ring is
            // projected the fact that two vertices were neighbours is gone, and
            // with it any chance of telling a wrap from a journey. Features
            // that do not cross ±180° come back by identity, so the ordinary
            // case pays one subtraction per vertex and allocates nothing.
            val safe = AntimeridianProcessor.process(collection)

            val projected = safe.features.mapIndexedNotNull { index, feature ->
                val polygons = feature.geometry.areaPolygons().mapNotNull { polygon ->
                    val outer = projection.projectRing(polygon.outer).simplifiedTo(simplification)
                    if (outer.size < GeoRing.MIN_RING_POINTS) {
                        null
                    } else {
                        ProjectedPolygon(
                            outer = outer,
                            holes = polygon.holes
                                .map { projection.projectRing(it).simplifiedTo(simplification) }
                                .filter { it.size >= GeoRing.MIN_RING_POINTS },
                        )
                    }
                }
                val lines = feature.geometry.lineStrings().mapNotNull { line ->
                    val points = line.points
                        .map(projection::project)
                        .filter { it.isFinite }
                        .simplifiedTo(simplification)
                    points.takeIf { it.size >= GeoLine.MIN_LINE_POINTS }
                }

                val markers = feature.geometry.markerPoints()
                    .map(projection::project)
                    .filter { it.isFinite }

                if (polygons.isEmpty() && lines.isEmpty() && markers.isEmpty()) {
                    null
                } else {
                    ProjectedFeature(index, feature, polygons, markers, lines)
                }
            }

            return ProjectedGeometry(
                features = projected,
                bounds = ProjectedBox.union(projected.map { it.bounds }),
                simplificationTolerance = simplification,
            )
        }

        private fun List<ProjectedPoint>.simplifiedTo(tolerance: Double): List<ProjectedPoint> =
            if (tolerance <= 0.0) this else GeoGeometryMath.simplify(this, tolerance)
    }
}

/**
 * Finds the features whose bounding box could contain a point.
 *
 * ```text
 * pointer
 *    ↓  grid cell lookup
 * a handful of candidates
 *    ↓  point-in-polygon
 * the selected feature
 * ```
 *
 * A uniform grid rather than an R-tree or a quadtree. Administrative
 * boundaries are the well-behaved case for a grid: regions tile the plane, are
 * broadly similar in size, and do not overlap — which is precisely the
 * distribution a grid handles well and the distribution that makes a balanced
 * tree's extra machinery unnecessary.
 *
 * The abstraction is the point. Everything above it asks one question — *which
 * features might contain this point* — so a dataset that a grid handles badly
 * (a few enormous features overlapping thousands of tiny ones) can be given a
 * tree implementation without touching the layer, the chart or the hit test.
 *
 * ### Falling back to a scan
 *
 * Below [MIN_INDEXED_FEATURES] the grid costs more to build than the linear
 * scan it replaces, so a small map does not build one. A world map of 200
 * countries scans 200 bounding boxes per tap, which is nothing.
 */
internal class GeoSpatialIndex private constructor(
    private val features: List<ProjectedFeature>,
    private val bounds: ProjectedBox,
    private val columns: Int,
    private val rows: Int,
    private val cells: Array<IntArray>?,
) {
    /**
     * The features whose box contains the point, innermost-first by area.
     *
     * Smallest first, because geography nests: a tap inside a city that sits
     * inside a county that sits inside a state should offer the city first.
     * The caller then runs the precise test in that order and takes the first
     * hit.
     */
    fun candidates(x: Double, y: Double): List<ProjectedFeature> {
        val cellIndices = cells?.let { grid ->
            val cell = cellIndexOf(x, y) ?: return emptyList()
            grid[cell]
        }

        val candidates = if (cellIndices == null) {
            features.filter { it.bounds.contains(x, y) }
        } else {
            // An `IntArray` walked directly rather than boxed through a
            // sequence: this runs on every pointer event of a drag.
            val hits = ArrayList<ProjectedFeature>(cellIndices.size)
            for (index in cellIndices) {
                val candidate = features.getOrNull(index) ?: continue
                if (candidate.bounds.contains(x, y)) hits += candidate
            }
            hits
        }
        if (candidates.size <= 1) return candidates
        return candidates.sortedBy { boxArea(it.bounds) }
    }

    private fun cellIndexOf(x: Double, y: Double): Int? {
        if (!bounds.contains(x, y)) return null
        val column = (((x - bounds.minX) / (bounds.maxX - bounds.minX)) * columns)
            .toInt().coerceIn(0, columns - 1)
        val row = (((y - bounds.minY) / (bounds.maxY - bounds.minY)) * rows)
            .toInt().coerceIn(0, rows - 1)
        return row * columns + column
    }

    companion object {

        /** Below this, a linear scan is cheaper than the grid that replaces it. */
        const val MIN_INDEXED_FEATURES: Int = 64

        /** Roughly one cell per feature, capped so the grid stays a small array. */
        private const val MAX_AXIS_CELLS: Int = 128

        fun build(features: List<ProjectedFeature>, bounds: ProjectedBox): GeoSpatialIndex {
            if (features.size < MIN_INDEXED_FEATURES || bounds.isEmpty ||
                bounds.maxX <= bounds.minX || bounds.maxY <= bounds.minY
            ) {
                return GeoSpatialIndex(features, bounds, 0, 0, null)
            }

            // One cell per feature on average, squared out over the two axes.
            val axis = sqrt(features.size.toDouble()).toInt().coerceIn(1, MAX_AXIS_CELLS)
            val columns = axis
            val rows = axis
            val buckets = Array(columns * rows) { ArrayList<Int>() }

            val width = bounds.maxX - bounds.minX
            val height = bounds.maxY - bounds.minY

            features.forEachIndexed { index, feature ->
                if (feature.bounds.isEmpty) return@forEachIndexed
                // A feature is registered in every cell its box overlaps, so a
                // large region is found from anywhere inside it rather than
                // only from the cell its centre happens to fall in.
                val fromColumn = (((feature.bounds.minX - bounds.minX) / width) * columns)
                    .toInt().coerceIn(0, columns - 1)
                val toColumn = (((feature.bounds.maxX - bounds.minX) / width) * columns)
                    .toInt().coerceIn(0, columns - 1)
                val fromRow = (((feature.bounds.minY - bounds.minY) / height) * rows)
                    .toInt().coerceIn(0, rows - 1)
                val toRow = (((feature.bounds.maxY - bounds.minY) / height) * rows)
                    .toInt().coerceIn(0, rows - 1)

                for (row in fromRow..toRow) {
                    for (column in fromColumn..toColumn) {
                        buckets[row * columns + column] += index
                    }
                }
            }

            return GeoSpatialIndex(
                features = features,
                bounds = bounds,
                columns = columns,
                rows = rows,
                cells = Array(buckets.size) { buckets[it].toIntArray() },
            )
        }

        private fun boxArea(box: ProjectedBox): Double =
            if (box.isEmpty) Double.MAX_VALUE else abs(box.maxX - box.minX) * abs(box.maxY - box.minY)
    }
}

/**
 * The same box as a [ProjectedBounds].
 *
 * [ProjectedBox] is the internal, allocation-light form used per polygon;
 * [ProjectedBounds] is the public one [io.devkit.chartkit.coordinate.GeoCoordinates]
 * is built from. The conversion happens once per fit, not per frame.
 */
internal fun ProjectedBox.toBounds(): ProjectedBounds? =
    if (isEmpty) null else ProjectedBounds(minX, minY, maxX, maxY)
