package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geo.GeoBounds
import io.devkit.chartkit.geo.GeoFeature
import io.devkit.chartkit.geo.GeoFeatureCollection
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.geo.ProjectedGeometry
import io.devkit.chartkit.geo.toBounds
import io.devkit.chartkit.layer.geo.ChoroplethLayer
import io.devkit.chartkit.layer.geo.FeatureStyle
import io.devkit.chartkit.layer.geo.GeoLabels
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.state.ChartGeoViewportState
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartGeoViewportState
import io.devkit.chartkit.state.rememberChartState

/**
 * What to do when two records claim the same region.
 *
 * Silently taking one of them is the wrong default in every direction: a
 * duplicate is usually a join key that is not as unique as the caller believed,
 * and the map would then show one arbitrary row's value with no sign that
 * another existed.
 */
enum class GeoDuplicatePolicy {

    /** Keep the first record for each key, and report the rest. */
    First,

    /** Keep the last, and report the rest. */
    Last,

    /** Sum the values. Right for counts, wrong for rates — hence not the default. */
    Sum,

    /** Throw, naming the key. */
    Reject,
}

/**
 * What the join found, for a caller who wants to know.
 *
 * Every field is a *diagnostic*, not an error: a map with unmatched data is
 * still drawable, and refusing to draw it would be worse than drawing it and
 * saying so. The single most common cause of a blank choropleth is a key
 * mismatch — `"CA"` against `"California"`, `"06"` against `"6"` — and this is
 * how a caller finds that out without guessing.
 *
 * @param unmatchedDataKeys keys in the data with no feature. Usually a key
 *   format mismatch.
 * @param unmatchedFeatureKeys features with no record. Sometimes expected —
 *   a national map with data for one region — and sometimes the same mismatch
 *   seen from the other side.
 * @param duplicateKeys keys that appeared more than once in the data.
 */
data class GeoJoinReport(
    val matched: Int,
    val unmatchedDataKeys: List<String>,
    val unmatchedFeatureKeys: List<String>,
    val duplicateKeys: List<String>,
) {
    /** True when every record found a region and every region found a record. */
    val isComplete: Boolean
        get() = unmatchedDataKeys.isEmpty() && unmatchedFeatureKeys.isEmpty()

    companion object {
        val Empty: GeoJoinReport = GeoJoinReport(0, emptyList(), emptyList(), emptyList())
    }
}

/**
 * A thematic map: regions shaded by a statistic.
 *
 * ```kotlin
 * ChoroplethMap(
 *     geometry = counties,                 // parsed once, from GeoJson.parse
 *     data = unemployment,
 *     featureKey = { it.properties.string("fips") },
 *     dataKey = { it.fips },
 *     value = { it.rate },
 *     modifier = Modifier.fillMaxWidth().height(320.dp),
 * )
 * ```
 *
 * ### What this is, and what it is not
 *
 * A **statistical** chart whose category axis happens to be geography. It
 * renders boundaries you supply, shades them by value, and lets a reader tap
 * one. It is not a map viewer: there are no tiles, no basemap, no satellite
 * imagery, no routing, no search and no GPS. Those need a mapping SDK, and a
 * charting library that pretended to offer them would be lying about all of
 * them.
 *
 * ### Geometry is yours
 *
 * ChartKit ships no world geography. A usable set of national boundaries is
 * several megabytes; a county file is tens. Bundling one would put that in
 * every consumer's APK — including every consumer who only wanted a bar chart —
 * and would still be the wrong file for anyone mapping sales territories,
 * postcodes or delivery zones. Parse yours with
 * [io.devkit.chartkit.geo.GeoJson.parse] and hold it across recompositions:
 *
 * ```kotlin
 * val counties = remember { GeoJson.parse(assets.readText("counties.geojson")) }
 * ```
 *
 * ### The join
 *
 * `featureKey` reads a key from each region's GeoJSON properties; `dataKey`
 * reads the matching key from each record. Both are strings, compared exactly,
 * and the result is reported through [onJoin]. Records and regions are matched
 * through a hash map, not by scanning: a thousand counties against a thousand
 * rows is a thousand lookups here and a million comparisons if done naively.
 *
 * ### Missing is not zero
 *
 * A region with no record is drawn in the theme's "no data" colour, not in the
 * colour of zero, and its tooltip says so. Painting an unmeasured county with
 * the low end of the scale asserts a measurement nobody took — see
 * [ColorScale] for the same rule applied to heatmaps.
 *
 * @param geometry the boundaries, already parsed. Hold it in `remember`.
 * @param scale the colour scale. Defaults to a quantile scale over the joined
 *   values, which is what makes a skewed statistic — income, population,
 *   incidence — readable at all; an equal-width scale over the same data paints
 *   nine regions the same shade and one dark.
 * @param simplification vertex tolerance in projected units. `0`, the default,
 *   keeps every vertex: silently discarding a caller's geographic fidelity is
 *   not a decision a chart should make for them.
 * @param onJoin called with a [GeoJoinReport] whenever the join runs. Log it in
 *   development; a blank map is nearly always a key mismatch.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <T> ChoroplethMap(
    geometry: GeoFeatureCollection,
    data: List<T>,
    featureKey: (GeoFeature) -> String?,
    dataKey: (T) -> String?,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    featureLabel: (GeoFeature) -> String = { feature ->
        feature.properties.string("name") ?: feature.id.orEmpty()
    },
    projection: GeoProjection = GeoProjection.Default,
    scale: ColorScale? = null,
    labels: GeoLabels = GeoLabels.None,
    duplicatePolicy: GeoDuplicatePolicy = GeoDuplicatePolicy.First,
    simplification: Double = 0.0,
    legend: LegendPosition = LegendPosition.Bottom,
    legendTitle: String? = null,
    missingLabel: String? = "No data",
    zoomEnabled: Boolean = true,
    panEnabled: Boolean = true,
    tapSelects: Boolean = true,
    clearOnTapOutside: Boolean = true,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    animation: ChartAnimation = ChartAnimation.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    viewportState: ChartGeoViewportState = rememberChartGeoViewportState(),
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    onJoin: ((GeoJoinReport) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter, showSeriesNames = false)
    },
    seriesId: String = ChartDefaults.SINGLE_SERIES_ID,
    seriesName: String = "",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent("No geography") },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    // Projected once per geometry-and-projection pair. Nothing below this line
    // rebuilds it: a new value, a new scale, a selection, a pan, a zoom and an
    // animation frame all reuse the same vertices, which is what makes a
    // thousand-region map affordable at all.
    val projected: ProjectedGeometry = remember(geometry, projection, simplification) {
        ProjectedGeometry.of(geometry, projection, simplification)
    }

    val join = remember(geometry, data, featureKey, dataKey, value, duplicatePolicy) {
        joinGeoData(geometry, data, featureKey, dataKey, value, duplicatePolicy)
    }
    LaunchedEffect(join.report, onJoin) { onJoin?.invoke(join.report) }

    val values = remember(join) { join.rows.map { it?.value } }
    val heatmap = io.devkit.chartkit.theme.ChartKitTheme.colors.heatmap
    // A quantile scale by default. Geographic statistics are nearly always
    // skewed — one city holds a fifth of a country's population — and an
    // equal-width ramp over that paints every region but one the same shade.
    val effectiveScale = remember(scale, values, heatmap) {
        scale ?: ColorScale.Quantile(values, listOf(heatmap.low, heatmap.high))
    }

    val styles: Map<Int, FeatureStyle> = remember(projected, join, effectiveScale, featureLabel) {
        buildMap {
            projected.features.forEach { feature ->
                val row = join.rows.getOrNull(feature.index)
                put(
                    feature.index,
                    FeatureStyle(
                        value = row?.value,
                        fill = effectiveScale.colorAt(row?.value),
                        label = featureLabel(feature.feature),
                        key = row?.key ?: featureKey(feature.feature).orEmpty(),
                        item = row?.item,
                    ),
                )
            }
        }
    }

    val extent = remember(projected) { projected.bounds.toBounds() }

    // Published so a caller can focus a region by name without holding the
    // projection themselves.
    LaunchedEffect(geometry) {
        viewportState.geometryBounds = GeoBounds.union(geometry.features.mapNotNull { it.bounds })
    }

    GeoChartCore(
        layers = {
            listOf(
                ChoroplethLayer(
                    id = "choropleth",
                    geometry = projected,
                    styles = styles,
                    seriesId = seriesId,
                    seriesName = seriesName,
                    labels = labels,
                    valueFormatter = valueFormatter,
                ),
            )
        },
        projection = projection,
        extent = extent,
        viewportState = viewportState,
        modifier = modifier,
        legend = legend,
        colorScale = effectiveScale,
        legendTitle = legendTitle,
        missingLabel = missingLabel,
        animation = animation,
        tapSelects = tapSelects,
        clearOnTapOutside = clearOnTapOutside,
        panEnabled = panEnabled,
        zoomEnabled = zoomEnabled,
        state = state.asErased(),
        valueFormatter = valueFormatter,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        renderMode = renderMode,
        staticOptions = staticOptions,
        isEmpty = projected.isEmpty,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        onSelectionChanged = onSelectionChanged?.let { callback ->
            { erased -> callback(erased?.asTyped()) }
        },
        tooltip = tooltip?.let { slot -> { erased -> slot(erased.asTypedTooltip()) } },
    )
}

/** One region's joined record. */
internal class GeoJoinRow(
    val key: String,
    val value: Double?,
    val item: Any?,
)

/** The join's output: a row per feature, in feature order, plus diagnostics. */
internal class GeoJoinResult(
    val rows: List<GeoJoinRow?>,
    val report: GeoJoinReport,
)

/**
 * Matches records to regions by key.
 *
 * ```text
 * data ──→ Map<key, record>
 *                    ↓ lookup per feature
 * features ─────────────────→ rows[featureIndex]
 * ```
 *
 * A hash map and one lookup per feature, not a scan per feature: the naive form
 * is O(features × records), which for a thousand counties against a thousand
 * rows is a million comparisons on every recomposition that changes the data.
 *
 * Pure and internal so the join's rules — duplicates, missing values, unmatched
 * keys on both sides — are verified on the JVM without a Composable.
 */
@Suppress("LongParameterList")
internal fun <T> joinGeoData(
    geometry: GeoFeatureCollection,
    data: List<T>,
    featureKey: (GeoFeature) -> String?,
    dataKey: (T) -> String?,
    value: (T) -> Number?,
    duplicatePolicy: GeoDuplicatePolicy,
): GeoJoinResult {
    val byKey = HashMap<String, GeoJoinRow>(data.size)
    val duplicates = LinkedHashSet<String>()

    data.forEach { item ->
        val key = dataKey(item) ?: return@forEach
        // A non-finite number is not a measurement. It becomes `null`, which is
        // drawn as "no data" rather than as a colour, for the same reason a
        // heatmap does not paint a `NaN` cell.
        val raw = value(item)?.toDouble()?.takeIf { it.isFinite() }
        val existing = byKey[key]
        if (existing == null) {
            byKey[key] = GeoJoinRow(key, raw, item)
            return@forEach
        }
        duplicates += key
        when (duplicatePolicy) {
            GeoDuplicatePolicy.First -> Unit
            GeoDuplicatePolicy.Last -> byKey[key] = GeoJoinRow(key, raw, item)
            GeoDuplicatePolicy.Sum -> byKey[key] = GeoJoinRow(
                key = key,
                // Two absent values sum to absent, not to zero.
                value = when {
                    existing.value == null -> raw
                    raw == null -> existing.value
                    else -> existing.value + raw
                },
                item = item,
            )
            GeoDuplicatePolicy.Reject -> error(
                "Duplicate key \"$key\" in choropleth data. Pass a GeoDuplicatePolicy " +
                    "to choose how repeats are handled.",
            )
        }
    }

    val used = HashSet<String>(byKey.size)
    val unmatchedFeatures = ArrayList<String>()
    val rows = geometry.features.map { feature ->
        val key = featureKey(feature)
        if (key == null) {
            unmatchedFeatures += feature.id.orEmpty()
            return@map null
        }
        val row = byKey[key]
        if (row == null) {
            unmatchedFeatures += key
        } else {
            used += key
        }
        row
    }

    return GeoJoinResult(
        rows = rows,
        report = GeoJoinReport(
            matched = used.size,
            unmatchedDataKeys = byKey.keys.filterNot { it in used },
            unmatchedFeatureKeys = unmatchedFeatures,
            duplicateKeys = duplicates.toList(),
        ),
    )
}
