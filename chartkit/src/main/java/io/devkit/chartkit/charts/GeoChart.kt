package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geo.GeoBounds
import io.devkit.chartkit.geo.GeoCoordinate
import io.devkit.chartkit.geo.GeoFeature
import io.devkit.chartkit.geo.GeoFeatureCollection
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.geo.ProjectedBounds
import io.devkit.chartkit.geo.ProjectedGeometry
import io.devkit.chartkit.geo.toBounds
import io.devkit.chartkit.interaction.GeoInteraction
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.geo.GeoFeatureStyle
import io.devkit.chartkit.layer.geo.GeoLabels
import io.devkit.chartkit.layer.geo.GeoLineLayer
import io.devkit.chartkit.layer.geo.GeoPointLayer
import io.devkit.chartkit.layer.geo.GeoStyle
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.SizeScale
import io.devkit.chartkit.scale.SizeScaleMode
import io.devkit.chartkit.state.ChartGeoViewportState
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartGeoViewportState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.theme.ChartTheme

/** One declared map layer, before anything is projected. */
internal sealed interface GeoLayerSpec {
    val id: String
    val seriesName: String
}

/** Features drawn from a collection: a base map, or a choropleth over one. */
internal class GeoFeatureSpec(
    override val id: String,
    override val seriesName: String,
    val geometry: GeoFeatureCollection,
    val simplification: Double,
    val labels: GeoLabels,
    val colorScale: ColorScale?,
    val styles: List<GeoFeatureStyle>,
) : GeoLayerSpec

/** One mark placed by longitude and latitude. */
internal data class GeoMarkSpec(
    val coordinate: GeoCoordinate,
    val radius: Float,
    val color: Color,
    val label: String,
    val sizeValue: Double?,
    val colorValue: Double?,
    val item: Any?,
)

/** Marks drawn over the map. */
internal class GeoPointSpec(
    override val id: String,
    override val seriesName: String,
    val marks: List<GeoMarkSpec>,
    val colorScale: ColorScale?,
    val outlineWidth: Dp,
    val slop: Dp,
) : GeoLayerSpec

/** One route. */
internal data class GeoPathSpec(
    val coordinates: List<GeoCoordinate>,
    val color: Color,
    val width: Dp,
    val label: String,
    val value: Double?,
    val item: Any?,
)

/** Routes drawn over the map. */
internal class GeoLineSpec(
    override val id: String,
    override val seriesName: String,
    val paths: List<GeoPathSpec>,
    val slop: Dp,
) : GeoLayerSpec

/**
 * Declares the layers of a [GeoChart].
 *
 * ```kotlin
 * GeoChart(projection = GeoProjection.EqualEarth) {
 *     map(world)
 *     choropleth(world, population, featureKey = { … }, dataKey = { … }, value = { … })
 *     points(capitals, longitude = { it.lon }, latitude = { it.lat }, label = { it.name })
 *     lines(routes, path = { it.waypoints })
 * }
 * ```
 *
 * Layers draw in the order declared, and are hit-tested in the reverse of it —
 * so a mark on top of a region is what a tap over both selects, and the base map
 * beneath a choropleth never steals one.
 *
 * ### No layer knows about any other
 *
 * Every one is given the same [io.devkit.chartkit.coordinate.GeoCoordinates] and
 * asked to draw itself. That is why the composition is open-ended: a future
 * hexbin or contour layer joins the list without any existing layer changing.
 */
@Suppress("TooManyFunctions")
class GeoChartScope internal constructor(
    private val density: Density,
    private val theme: ChartTheme,
) {
    internal val specs = ArrayList<GeoLayerSpec>()

    /**
     * The geography itself, unshaded.
     *
     * A base map: borders, coastlines, whatever the collection describes. It
     * carries no data, so its regions announce themselves by name and report no
     * value — which is the honest reading of an outline map.
     */
    @Suppress("LongParameterList")
    fun map(
        geometry: GeoFeatureCollection,
        fill: Color? = null,
        stroke: Color? = null,
        strokeWidth: Dp? = null,
        labels: GeoLabels = GeoLabels.None,
        featureLabel: (GeoFeature) -> String = DefaultFeatureLabel,
        simplification: Double = 0.0,
        seriesName: String = "",
        id: String = "map",
        /**
         * Per-feature styling, read from the feature's own properties.
         *
         * Overrides the flat [fill], [stroke] and [strokeWidth] for whichever
         * features it answers for; `null` for a feature leaves those in force.
         * See [GeoStyle] for what a dataset that distinguishes boundary kinds
         * looks like through it.
         */
        style: ((GeoFeature) -> GeoStyle?)? = null,
    ) {
        val land = fill ?: theme.colors.geo.land
        specs += GeoFeatureSpec(
            id = id,
            seriesName = seriesName,
            geometry = geometry,
            simplification = simplification,
            labels = labels,
            colorScale = null,
            styles = geometry.features.map { feature ->
                val resolved = style?.invoke(feature)
                GeoFeatureStyle(
                    // Absent, not zero: an outline map is not missing a
                    // measurement, it never claimed to have one.
                    value = null,
                    fill = resolved?.fill ?: land,
                    label = featureLabel(feature),
                    key = feature.id.orEmpty(),
                    item = null,
                    stroke = resolved?.stroke ?: stroke,
                    strokeWidth = resolved?.strokeWidth ?: strokeWidth,
                    opacity = resolved?.opacity ?: 1f,
                )
            },
        )
    }

    /**
     * Regions shaded by a statistic joined to them.
     *
     * The same join, the same duplicate policy and the same "missing is not
     * zero" rule as [ChoroplethMap] — because it is the same code; that chart
     * is this layer with a legend and a camera around it.
     */
    @Suppress("LongParameterList")
    fun <T> choropleth(
        geometry: GeoFeatureCollection,
        data: List<T>,
        featureKey: (GeoFeature) -> String?,
        dataKey: (T) -> String?,
        value: (T) -> Number?,
        scale: ColorScale? = null,
        featureLabel: (GeoFeature) -> String = DefaultFeatureLabel,
        labels: GeoLabels = GeoLabels.None,
        duplicatePolicy: GeoDuplicatePolicy = GeoDuplicatePolicy.First,
        simplification: Double = 0.0,
        stroke: Color? = null,
        strokeWidth: Dp? = null,
        seriesName: String = "",
        id: String = "choropleth",
        onJoin: ((GeoJoinReport) -> Unit)? = null,
        /**
         * Per-feature styling, as on [map].
         *
         * A [GeoStyle.fill] here **replaces** the colour scale's answer for that
         * feature, which is how a region is greyed out or flagged without
         * bending the scale around it. Leave it `null` to keep the shading.
         */
        style: ((GeoFeature) -> GeoStyle?)? = null,
    ) {
        val join = joinGeoData(geometry, data, featureKey, dataKey, value, duplicatePolicy)
        onJoin?.invoke(join.report)
        val values = join.rows.map { it?.value }
        val heatmap = theme.colors.heatmap
        val effective = scale ?: ColorScale.Quantile(values, listOf(heatmap.low, heatmap.high))

        specs += GeoFeatureSpec(
            id = id,
            seriesName = seriesName,
            geometry = geometry,
            simplification = simplification,
            labels = labels,
            colorScale = effective,
            styles = geometry.features.mapIndexed { index, feature ->
                val row = join.rows.getOrNull(index)
                val resolved = style?.invoke(feature)
                GeoFeatureStyle(
                    value = row?.value,
                    fill = resolved?.fill ?: effective.colorAt(row?.value),
                    label = featureLabel(feature),
                    key = row?.key ?: featureKey(feature).orEmpty(),
                    item = row?.item,
                    stroke = resolved?.stroke ?: stroke,
                    strokeWidth = resolved?.strokeWidth ?: strokeWidth,
                    opacity = resolved?.opacity ?: 1f,
                )
            },
        )
    }

    /**
     * Marks placed by longitude and latitude.
     *
     * ```kotlin
     * points(capitals, longitude = { it.lon }, latitude = { it.lat }, label = { it.name })
     * ```
     *
     * Your own records, read through lambdas. There is no `GeoPointEntry` to
     * convert to — the same rule every other ChartKit chart follows.
     *
     * @param size an optional number to encode as the mark's **area**. Supplying
     *   it makes this a bubble map; see [bubbles], which is this function with
     *   the parameter made required.
     * @param color an optional number to encode as the mark's fill, through
     *   [colorScale].
     */
    @Suppress("LongParameterList")
    fun <T> points(
        data: List<T>,
        longitude: (T) -> Double?,
        latitude: (T) -> Double?,
        label: (T) -> String = { "" },
        size: ((T) -> Number?)? = null,
        color: ((T) -> Number?)? = null,
        colorScale: ColorScale? = null,
        radius: Dp? = null,
        minRadius: Dp? = null,
        maxRadius: Dp? = null,
        sizeMode: SizeScaleMode = SizeScaleMode.Area,
        fill: Color? = null,
        seriesName: String = "",
        id: String = "points",
    ) {
        val dimensions = theme.dimensions
        val geo = theme.colors.geo

        val sizeValues = size?.let { encode -> data.map { encode(it)?.toDouble()?.takeIf(Double::isFinite) } }
        val colorValues = color?.let { encode -> data.map { encode(it)?.toDouble()?.takeIf(Double::isFinite) } }

        val sizeScale = sizeValues?.let { values ->
            val domain = NumericDomain.of(values.filterNotNull()) ?: return@let null
            with(density) {
                SizeScale(
                    domain = domain,
                    minSize = (minRadius ?: dimensions.geoMinBubbleRadius).toPx(),
                    maxSize = (maxRadius ?: dimensions.geoMaxBubbleRadius).toPx(),
                    mode = sizeMode,
                )
            }
        }
        val effectiveColorScale = colorValues?.let { values ->
            colorScale ?: ColorScale.Quantile(values, listOf(theme.colors.heatmap.low, theme.colors.heatmap.high))
        }
        val fixedRadius = with(density) { (radius ?: dimensions.geoPointRadius).toPx() }
        val baseColor = fill ?: geo.overlayPoint

        val marks = data.mapIndexedNotNull { index, item ->
            val lon = longitude(item) ?: return@mapIndexedNotNull null
            val lat = latitude(item) ?: return@mapIndexedNotNull null
            if (!lon.isFinite() || !lat.isFinite()) return@mapIndexedNotNull null
            val sizeValue = sizeValues?.getOrNull(index)
            val colorValue = colorValues?.getOrNull(index)
            GeoMarkSpec(
                coordinate = GeoCoordinate(lon, lat),
                // A mark whose size value is missing falls back to the fixed
                // radius rather than to zero: an absent measurement should not
                // masquerade as the smallest one.
                radius = sizeScale?.size(sizeValue)?.takeIf { sizeValue != null } ?: fixedRadius,
                color = effectiveColorScale?.colorAt(colorValue) ?: baseColor,
                label = label(item),
                sizeValue = sizeValue,
                colorValue = colorValue,
                item = item,
            )
        }

        specs += GeoPointSpec(
            id = id,
            seriesName = seriesName,
            marks = marks,
            colorScale = effectiveColorScale,
            outlineWidth = dimensions.geoPointOutlineWidth,
            slop = dimensions.geoSelectionSlop,
        )
    }

    /**
     * Marks whose **area** carries a number: a bubble map.
     *
     * [points] with [points]'s `size` made required, and nothing else. It is
     * one function because it is one layer: a second renderer would have been a
     * second place to get the area-versus-radius mapping wrong.
     */
    @Suppress("LongParameterList")
    fun <T> bubbles(
        data: List<T>,
        longitude: (T) -> Double?,
        latitude: (T) -> Double?,
        size: (T) -> Number?,
        label: (T) -> String = { "" },
        color: ((T) -> Number?)? = null,
        colorScale: ColorScale? = null,
        minRadius: Dp? = null,
        maxRadius: Dp? = null,
        sizeMode: SizeScaleMode = SizeScaleMode.Area,
        fill: Color? = null,
        seriesName: String = "",
        id: String = "bubbles",
    ) {
        points(
            data = data,
            longitude = longitude,
            latitude = latitude,
            label = label,
            size = size,
            color = color,
            colorScale = colorScale,
            minRadius = minRadius,
            maxRadius = maxRadius,
            sizeMode = sizeMode,
            fill = fill,
            seriesName = seriesName,
            id = id,
        )
    }

    /**
     * Paths drawn on the map: routes, flows, links.
     *
     * ```kotlin
     * lines(flights, path = { listOf(it.from, it.to) }, label = { it.code })
     * ```
     *
     * Each record supplies its own list of coordinates. A path crossing ±180°
     * is split before projection, so a Tokyo–Los Angeles leg is drawn off one
     * edge of the map and onto the other rather than backwards across Asia.
     */
    @Suppress("LongParameterList")
    fun <T> lines(
        data: List<T>,
        path: (T) -> List<GeoCoordinate>,
        label: (T) -> String = { "" },
        value: ((T) -> Number?)? = null,
        color: ((T) -> Color?)? = null,
        width: Dp? = null,
        seriesName: String = "",
        id: String = "lines",
    ) {
        val geo = theme.colors.geo
        val strokeWidth = width ?: theme.dimensions.geoRouteWidth
        specs += GeoLineSpec(
            id = id,
            seriesName = seriesName,
            paths = data.mapNotNull { item ->
                val coordinates = path(item).filter { it.longitude.isFinite() && it.latitude.isFinite() }
                if (coordinates.size < 2) return@mapNotNull null
                GeoPathSpec(
                    coordinates = coordinates,
                    color = color?.invoke(item) ?: geo.route,
                    width = strokeWidth,
                    label = label(item),
                    value = value?.invoke(item)?.toDouble()?.takeIf(Double::isFinite),
                    item = item,
                )
            },
            slop = theme.dimensions.geoSelectionSlop,
        )
    }

    internal companion object {
        /** A feature's `name` property, falling back to its id. */
        val DefaultFeatureLabel: (GeoFeature) -> String = { feature ->
            feature.properties.string("name") ?: feature.id.orEmpty()
        }
    }
}

/**
 * A map of composable layers.
 *
 * ```kotlin
 * GeoChart(
 *     projection = GeoProjection.EqualEarth,
 *     viewportState = camera,
 *     modifier = Modifier.fillMaxWidth().height(360.dp),
 * ) {
 *     map(world)
 *     bubbles(cities, longitude = { it.lon }, latitude = { it.lat }, size = { it.population })
 *     lines(flights, path = { it.waypoints })
 * }
 * ```
 *
 * The low-level geographic API, and the one the others are built on:
 * [ChoroplethMap] is this with a single `choropleth` layer, and [WorldMap] is
 * this with a single `map` layer and global defaults.
 *
 * ### One coordinate system for every layer
 *
 * Each layer is handed the same [io.devkit.chartkit.coordinate.GeoCoordinates],
 * so a bubble over Nairobi and the county Nairobi sits in cannot disagree about
 * where Nairobi is. That is the same guarantee a combined bar-and-line chart
 * gets from sharing Cartesian coordinates, and it is why the composition is
 * safe to extend.
 *
 * ### Draw order and hit order
 *
 * Layers draw in declaration order — a base map first, then shading, then
 * routes, then marks — and are hit-tested in the reverse. Render frontness and
 * selection frontness are therefore the same order read from opposite ends,
 * rather than two rules that could drift apart.
 *
 * ### Fitting
 *
 * The extent is the union of everything declared, marks and routes included, so
 * a map of nothing but cities still frames those cities. The fit uses one scale
 * for both axes: geography is never stretched to fill the plot.
 *
 * @param projection defaults to [GeoProjection.World] — Equal Earth — because a
 *   composed geographic chart is usually a world or continental one, where
 *   equal area is what makes shaded regions comparable.
 * @param background painted behind the geography inside the plot. Transparent
 *   by default, so a chart in a card does not paint over the card.
 * @param colorScale the scale the legend explains. Taken from the first layer
 *   that has one when left `null`, which is nearly always what a caller means.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun GeoChart(
    modifier: Modifier = Modifier,
    projection: GeoProjection = GeoProjection.World,
    legend: LegendPosition = LegendPosition.None,
    colorScale: ColorScale? = null,
    legendTitle: String? = null,
    missingLabel: String? = "No data",
    background: Color = Color.Unspecified,
    interaction: GeoInteraction = GeoInteraction.Default,
    clearOnTapOutside: Boolean = true,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    animation: ChartAnimation = ChartAnimation.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    viewportState: ChartGeoViewportState = rememberChartGeoViewportState(),
    state: ChartState<Any?> = rememberChartState(),
    onSelectionChanged: ((AnyChartSelection?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<Any?>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter, showSeriesNames = false)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent("No geography") },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
    content: GeoChartScope.() -> Unit,
) {
    val theme = ChartKitTheme.current
    val density = LocalDensity.current

    // Declaring layers is plain data — lambdas over the caller's own lists — so
    // it runs each composition. Everything expensive below is keyed on what it
    // actually depends on.
    val specs = GeoChartScope(density, theme).apply(content).specs

    // Geography is projected once per collection-and-projection pair. Keyed on
    // the collection **reference**, which is why the documentation is insistent
    // that a caller hold their parsed geometry in `remember`: a collection
    // reparsed each frame would be a different reference each frame, and this
    // cache would never hit.
    val featureSpecs = specs.filterIsInstance<GeoFeatureSpec>()
    val geometryKey = featureSpecs.map { Triple(it.id, it.geometry, it.simplification) }
    val projectedGeometry: List<ProjectedGeometry> = remember(geometryKey, projection) {
        featureSpecs.map { ProjectedGeometry.of(it.geometry, projection, it.simplification) }
    }

    val pointSpecs = specs.filterIsInstance<GeoPointSpec>()
    val markKey = pointSpecs.map { spec -> spec.marks.map { it.coordinate } }
    val projectedMarks: List<List<io.devkit.chartkit.geo.ProjectedPoint>> =
        remember(markKey, projection) {
            pointSpecs.map { spec -> spec.marks.map { projection.project(it.coordinate) } }
        }

    val lineSpecs = specs.filterIsInstance<GeoLineSpec>()
    val pathKey = lineSpecs.map { spec -> spec.paths.map { it.coordinates } }
    val projectedPaths: List<List<List<List<io.devkit.chartkit.geo.ProjectedPoint>>>> =
        remember(pathKey, projection) {
            lineSpecs.map { spec ->
                spec.paths.map { route ->
                    // Split before projection, for the same reason a polygon is:
                    // after projection the wrap is indistinguishable from a very
                    // long leg.
                    io.devkit.chartkit.geo.AntimeridianProcessor.splitLine(route.coordinates)
                        .map { piece ->
                            piece.map(projection::project).filter { it.isFinite }
                        }
                        .filter { it.size >= 2 }
                }
            }
        }

    val renderers: List<ChartLayerRenderer> = remember(
        specs.map { it.id },
        projectedGeometry,
        projectedMarks,
        projectedPaths,
        specs,
        valueFormatter,
    ) {
        buildGeoRenderers(
            specs = specs,
            projectedGeometry = projectedGeometry,
            projectedMarks = projectedMarks,
            projectedPaths = projectedPaths,
            valueFormatter = valueFormatter,
        )
    }

    // The union of everything declared. A points-only map has no polygons to
    // fit, and must still frame its points.
    val extent: ProjectedBounds? = remember(projectedGeometry, projectedMarks, projectedPaths) {
        val boxes = ArrayList<ProjectedBounds>()
        projectedGeometry.forEach { geometry -> geometry.bounds.toBounds()?.let { boxes += it } }
        projectedMarks.forEach { marks -> ProjectedBounds.of(marks)?.let { boxes += it } }
        projectedPaths.forEach { routes ->
            routes.forEach { pieces ->
                pieces.forEach { piece -> ProjectedBounds.of(piece)?.let { boxes += it } }
            }
        }
        // Opened out if it is degenerate, so a map of cities along one parallel
        // — or of a single city — still fits rather than reporting itself empty.
        ProjectedBounds.union(boxes)?.nonDegenerate()
    }

    val effectiveScale = colorScale
        ?: featureSpecs.firstNotNullOfOrNull { it.colorScale }
        ?: pointSpecs.firstNotNullOfOrNull { it.colorScale }

    // The legend explains a "no data" colour only when something is actually
    // drawn in it. A swatch for a state that does not occur on this map invites
    // the reader to go looking for it.
    val anyMissing = featureSpecs.any { spec ->
        spec.colorScale != null && spec.styles.any { it.fill == null }
    } || pointSpecs.any { spec ->
        spec.colorScale != null && spec.marks.any { it.colorValue == null }
    }

    LaunchedEffect(geometryKey, markKey) {
        val boxes = ArrayList<GeoBounds>()
        featureSpecs.forEach { spec -> spec.geometry.bounds?.let { boxes += it } }
        pointSpecs.forEach { spec ->
            GeoBounds.of(spec.marks.map { it.coordinate })?.let { boxes += it }
        }
        viewportState.geometryBounds = GeoBounds.union(boxes)
    }

    val resolvedBackground =
        if (background == Color.Unspecified) theme.colors.geo.background else background

    GeoChartCore(
        layers = { renderers },
        projection = projection,
        extent = extent,
        viewportState = viewportState,
        modifier = modifier,
        legend = legend,
        colorScale = effectiveScale,
        legendTitle = legendTitle,
        missingLabel = missingLabel.takeIf { anyMissing },
        animation = animation,
        interaction = interaction,
        clearOnTapOutside = clearOnTapOutside,
        background = resolvedBackground,
        state = state,
        valueFormatter = valueFormatter,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        renderMode = renderMode,
        staticOptions = staticOptions,
        isEmpty = renderers.isEmpty() || extent == null,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip?.let { slot -> { erased -> slot(erased.asTypedTooltip()) } },
    )
}

/**
 * Turns declarations into renderers, in declaration order.
 *
 * Pure and separate from the composable so the wiring — which spec becomes
 * which layer, and in what order — is verified on the JVM rather than only by
 * looking at a screen.
 */
private fun buildGeoRenderers(
    specs: List<GeoLayerSpec>,
    projectedGeometry: List<ProjectedGeometry>,
    projectedMarks: List<List<io.devkit.chartkit.geo.ProjectedPoint>>,
    projectedPaths: List<List<List<List<io.devkit.chartkit.geo.ProjectedPoint>>>>,
    valueFormatter: ChartValueFormatter,
): List<ChartLayerRenderer> {
    var featureIndex = 0
    var pointIndex = 0
    var lineIndex = 0
    return specs.mapNotNull { spec ->
        when (spec) {
            is GeoFeatureSpec -> {
                val geometry = projectedGeometry.getOrNull(featureIndex++) ?: return@mapNotNull null
                io.devkit.chartkit.layer.geo.MapLayer(
                    id = spec.id,
                    geometry = geometry,
                    styles = geometry.features.associate { feature ->
                        feature.index to spec.styles[feature.index]
                    },
                    seriesId = spec.id,
                    seriesName = spec.seriesName,
                    labels = spec.labels,
                    valueFormatter = valueFormatter,
                )
            }

            is GeoPointSpec -> {
                val projected = projectedMarks.getOrNull(pointIndex++) ?: return@mapNotNull null
                GeoPointLayer(
                    id = spec.id,
                    marks = spec.marks.mapIndexedNotNull { index, mark ->
                        val point = projected.getOrNull(index)?.takeIf { it.isFinite }
                            ?: return@mapIndexedNotNull null
                        io.devkit.chartkit.layer.geo.GeoMark(
                            index = index,
                            coordinate = mark.coordinate,
                            projected = point,
                            radius = mark.radius,
                            color = mark.color,
                            label = mark.label,
                            sizeValue = mark.sizeValue,
                            colorValue = mark.colorValue,
                            item = mark.item,
                        )
                    },
                    seriesId = spec.id,
                    seriesName = spec.seriesName,
                    valueFormatter = valueFormatter,
                    outlineWidth = spec.outlineWidth,
                    slop = spec.slop,
                )
            }

            is GeoLineSpec -> {
                val projected = projectedPaths.getOrNull(lineIndex++) ?: return@mapNotNull null
                GeoLineLayer(
                    id = spec.id,
                    routes = spec.paths.mapIndexedNotNull { index, route ->
                        val pieces = projected.getOrNull(index).orEmpty()
                        if (pieces.isEmpty()) return@mapIndexedNotNull null
                        io.devkit.chartkit.layer.geo.GeoRoute(
                            index = index,
                            paths = pieces,
                            source = route.coordinates,
                            color = route.color,
                            width = route.width,
                            label = route.label,
                            value = route.value,
                            item = route.item,
                        )
                    },
                    seriesId = spec.id,
                    seriesName = spec.seriesName,
                    valueFormatter = valueFormatter,
                    slop = spec.slop,
                )
            }
        }
    }
}

/**
 * A map of whatever geography you hand it.
 *
 * ```kotlin
 * val world = remember { TopoJson.parse(json, objectName = "countries") }
 *
 * WorldMap(geometry = world, modifier = Modifier.fillMaxWidth().height(320.dp))
 * ```
 *
 * ### The name is about the defaults, not about the data
 *
 * Nothing here knows what a country is. `WorldMap` is [GeoChart] with one `map`
 * layer and the settings a **global** map wants — Equal Earth, so shaded areas
 * stay comparable across the planet, and conservative labelling. Hand it a
 * county file and it draws counties; the only thing that would be wrong is the
 * name of the function you called.
 *
 * ChartKit ships no geography of its own. See [ChoroplethMap] for why, and
 * where to get some.
 *
 * @param labels off by default. Two hundred country names do not fit on a phone,
 *   and [GeoLabels.Auto] is the setting that draws the ones that do.
 */
@Suppress("LongParameterList")
@Composable
fun WorldMap(
    geometry: GeoFeatureCollection,
    modifier: Modifier = Modifier,
    projection: GeoProjection = GeoProjection.World,
    fill: Color? = null,
    stroke: Color? = null,
    background: Color = Color.Unspecified,
    labels: GeoLabels = GeoLabels.None,
    featureLabel: (GeoFeature) -> String = GeoChartScope.DefaultFeatureLabel,
    simplification: Double = 0.0,
    interaction: GeoInteraction = GeoInteraction.Default,
    animation: ChartAnimation = ChartAnimation.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    viewportState: ChartGeoViewportState = rememberChartGeoViewportState(),
    state: ChartState<Any?> = rememberChartState(),
    onSelectionChanged: ((AnyChartSelection?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<Any?>) -> Unit)? = {
        ChartDefaults.Tooltip(it, showSeriesNames = false)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent("No geography") },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    GeoChart(
        modifier = modifier,
        projection = projection,
        background = background,
        interaction = interaction,
        animation = animation,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        renderMode = renderMode,
        staticOptions = staticOptions,
        viewportState = viewportState,
        state = state,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    ) {
        map(
            geometry = geometry,
            fill = fill,
            stroke = stroke,
            labels = labels,
            featureLabel = featureLabel,
            simplification = simplification,
            id = "world",
        )
    }
}
