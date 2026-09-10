package io.devkit.chartkit.charts

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.animation.rememberAnimatedSeriesValues
import io.devkit.chartkit.axis.AxisDimension
import io.devkit.chartkit.axis.AxisRegistry
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartAxisId
import io.devkit.chartkit.axis.ChartAxisSpec
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.axis.ChartUnit
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.three.Chart3DDebug
import io.devkit.chartkit.layer.three.Chart3DSceneFit
import io.devkit.chartkit.layer.three.Scatter3DGuides
import io.devkit.chartkit.layer.three.Scatter3DRenderMode
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.model.ChartXAxisKind
import io.devkit.chartkit.model.MissingValuePolicy
import io.devkit.chartkit.model.PlotData
import io.devkit.chartkit.model.normalizeSeries
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.SizeScale
import io.devkit.chartkit.scale.SizeScaleMode
import io.devkit.chartkit.scale.apply
import io.devkit.chartkit.scene.ChartSceneState
import io.devkit.chartkit.state.Chart3DCameraState
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChart3DCameraState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberChartViewportState
import io.devkit.chartkit.theme.ChartColorScales
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DCameraLimits
import io.devkit.chartkit.three.Chart3DDiagnostics
import io.devkit.chartkit.three.Chart3DFrame
import io.devkit.chartkit.three.Chart3DGridPlanes
import io.devkit.chartkit.three.Chart3DLighting
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Chart3DSceneDepth
import io.devkit.chartkit.three.Marker3D

/**
 * A true X/Y/Z scatter over the caller's own data.
 *
 * ```kotlin
 * data class Observation(val age: Double, val income: Double, val score: Double)
 *
 * ScatterChart3D(
 *     data = observations,
 *     x = { it.age },
 *     y = { it.income },
 *     z = { it.score },
 *     xAxis = ChartAxis(title = "Age"),
 *     yAxis = ChartAxis(title = "Income"),
 *     zAxis = ChartAxis(title = "Satisfaction"),
 *     modifier = Modifier.fillMaxWidth().height(360.dp),
 * )
 * ```
 *
 * No conversion step and no point type: `data` is a `List<Observation>` and
 * stays one, exactly as it does for a line, a bar or a flat scatter.
 *
 * ### Three variables, three scales, and why that is the whole point
 *
 * Each of the three accessors feeds an axis with its own domain, its own ticks,
 * its own formatter and its own log or linear transform. A dataset spanning
 * `18..80`, `20,000..200,000` and `0..100` produces three separate
 * normalisations mapped into one box, and the reader can read a value off any
 * of the three.
 *
 * That is a different thing from the depth of a [ColumnChart3D]. There, depth
 * separates stacks and carries no quantity; here it is a measurement. The two
 * ideas are kept apart all the way down — see
 * [io.devkit.chartkit.three.Cartesian3DCoordinates].
 *
 * ### And what perspective costs
 *
 * A far point is drawn smaller than a near one of the same size, and two points
 * an equal distance apart are drawn at unequal separations depending on where
 * in the volume they sit. That is what perspective *is*, and it is the depth
 * cue that makes the cloud read as a volume at all. Where a precise comparison
 * matters more than the sense of depth, pass
 * `projection = Chart3DProjection.Orthographic`: under a parallel projection
 * equal distances are drawn equal and marker size no longer varies with depth.
 *
 * @param x, [y] and [z] read the three variables from an item. A point missing
 *   any one of them is not drawn: two thirds of a position is not a position.
 * @param size an optional fourth channel, mapped through a [SizeScale] by
 *   **area** so a value twice as large draws a marker occupying twice the area.
 * @param color an optional fifth channel, mapped through [colorScale].
 * @param marker what a point looks like. See [Marker3D].
 * @param renderMode how much detail each marker is worth. See
 *   [Scatter3DRenderMode].
 * @param guides optional lines from a selected point to the reference planes.
 * @param cameraState the hoisted camera, so an application can rotate, animate
 *   or reset it. Shared freely with a [ColumnChart3D], a `PieChart3D` or a
 *   `DonutChart3D` — it is the same type and the same limits.
 * @param interaction what a pointer does. Defaults to
 *   [Chart3DInteraction.RotateAndSelect]: a scatter is explored, and rotating
 *   it is how a reader resolves which of two points is in front.
 */
@Suppress("LongParameterList")
@Composable
fun <T> ScatterChart3D(
    data: List<T>,
    x: (T) -> Number?,
    y: (T) -> Number?,
    z: (T) -> Number?,
    modifier: Modifier = Modifier,
    seriesName: String = "",
    size: ((T) -> Number?)? = null,
    color: ((T) -> Number?)? = null,
    colorScale: ColorScale? = null,
    sizeDomain: DomainPolicy = DomainPolicy.Default,
    sizeMode: SizeScaleMode = SizeScaleMode.Area,
    minMarkerSize: Dp? = null,
    maxMarkerSize: Dp? = null,
    marker: Marker3D = Marker3D.Sphere,
    markerSize: Dp? = null,
    renderMode: Scatter3DRenderMode = Scatter3DRenderMode.Auto,
    guides: Scatter3DGuides = Scatter3DGuides.None,
    colorLegend: Boolean = true,
    colorLegendTitle: String? = null,
    xAxis: ChartAxis = ChartAxis.Default,
    yAxis: ChartAxis = ChartAxis.Default,
    zAxis: ChartAxis = ChartAxis.Default,
    xUnit: ChartUnit = ChartUnit.None,
    yUnit: ChartUnit = ChartUnit.None,
    zUnit: ChartUnit = ChartUnit.None,
    xDomain: DomainPolicy = DomainPolicy.Default,
    yDomain: DomainPolicy = DomainPolicy.Default,
    zDomain: DomainPolicy = DomainPolicy.Default,
    cameraState: Chart3DCameraState = rememberChart3DCameraState(
        camera = Chart3DCamera.Isometric,
        limits = Chart3DCameraLimits.Cartesian3D,
    ),
    projection: Chart3DProjection = Chart3DProjection.Default,
    lighting: Chart3DLighting = Chart3DLighting.Default,
    frame: Chart3DFrame = Chart3DFrame.Auto,
    gridPlanes: Chart3DGridPlanes = Chart3DGridPlanes.Primary,
    sceneDepth: Chart3DSceneDepth = Chart3DSceneDepth.Auto,
    fit: Chart3DSceneFit = Chart3DSceneFit.Content,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: Chart3DInteraction = Chart3DInteraction.RotateAndSelect,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    debug: Chart3DDebug = Chart3DDebug.None,
    onDiagnostics: ((Chart3DDiagnostics) -> Unit)? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Scatter3DTooltip(it)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
) {
    ScatterChart3D(
        series = remember(data, seriesName) {
            singleSeries(data, ChartDefaults.SINGLE_SERIES_ID, seriesName)
        },
        x = x,
        y = y,
        z = z,
        modifier = modifier,
        size = size,
        color = color,
        colorScale = colorScale,
        sizeDomain = sizeDomain,
        sizeMode = sizeMode,
        minMarkerSize = minMarkerSize,
        maxMarkerSize = maxMarkerSize,
        marker = marker,
        markerSize = markerSize,
        renderMode = renderMode,
        guides = guides,
        colorLegend = colorLegend,
        colorLegendTitle = colorLegendTitle,
        xAxis = xAxis,
        yAxis = yAxis,
        zAxis = zAxis,
        xUnit = xUnit,
        yUnit = yUnit,
        zUnit = zUnit,
        xDomain = xDomain,
        yDomain = yDomain,
        zDomain = zDomain,
        legend = LegendPosition.None,
        cameraState = cameraState,
        projection = projection,
        lighting = lighting,
        frame = frame,
        gridPlanes = gridPlanes,
        sceneDepth = sceneDepth,
        fit = fit,
        animation = animation,
        interaction = interaction,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        debug = debug,
        onDiagnostics = onDiagnostics,
        state = state,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        isLoading = isLoading,
        error = error,
    )
}

/**
 * A multi-series 3D scatter.
 *
 * ```kotlin
 * ScatterChart3D(
 *     series = listOf(
 *         ChartSeries("control", "Control", control),
 *         ChartSeries("treated", "Treated", treated),
 *     ),
 *     x = { it.age },
 *     y = { it.income },
 *     z = { it.score },
 *     legend = LegendPosition.Bottom,
 *     legendTogglesSeries = true,
 * )
 * ```
 *
 * Series behave exactly as they do everywhere else in ChartKit: one palette
 * slot each, one legend row each, and hiding one through the legend removes its
 * markers and narrows all three Auto domains — the depth axis included, which
 * is what keeps the cloud filling its box after a toggle.
 *
 * @param legendTogglesSeries whether tapping a legend row hides the series.
 */
@JvmName("ScatterChart3DSeries")
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun <T> ScatterChart3D(
    series: List<ChartSeries<T>>,
    x: (T) -> Number?,
    y: (T) -> Number?,
    z: (T) -> Number?,
    modifier: Modifier = Modifier,
    size: ((T) -> Number?)? = null,
    color: ((T) -> Number?)? = null,
    colorScale: ColorScale? = null,
    sizeDomain: DomainPolicy = DomainPolicy.Default,
    sizeMode: SizeScaleMode = SizeScaleMode.Area,
    minMarkerSize: Dp? = null,
    maxMarkerSize: Dp? = null,
    marker: Marker3D = Marker3D.Sphere,
    markerSize: Dp? = null,
    renderMode: Scatter3DRenderMode = Scatter3DRenderMode.Auto,
    guides: Scatter3DGuides = Scatter3DGuides.None,
    xAxis: ChartAxis = ChartAxis.Default,
    yAxis: ChartAxis = ChartAxis.Default,
    zAxis: ChartAxis = ChartAxis.Default,
    xUnit: ChartUnit = ChartUnit.None,
    yUnit: ChartUnit = ChartUnit.None,
    zUnit: ChartUnit = ChartUnit.None,
    xDomain: DomainPolicy = DomainPolicy.Default,
    yDomain: DomainPolicy = DomainPolicy.Default,
    zDomain: DomainPolicy = DomainPolicy.Default,
    legend: LegendPosition = if (series.size > 1) LegendPosition.Bottom else LegendPosition.None,
    legendTogglesSeries: Boolean = false,
    colorLegend: Boolean = true,
    colorLegendTitle: String? = null,
    cameraState: Chart3DCameraState = rememberChart3DCameraState(
        camera = Chart3DCamera.Isometric,
        limits = Chart3DCameraLimits.Cartesian3D,
    ),
    projection: Chart3DProjection = Chart3DProjection.Default,
    lighting: Chart3DLighting = Chart3DLighting.Default,
    frame: Chart3DFrame = Chart3DFrame.Auto,
    gridPlanes: Chart3DGridPlanes = Chart3DGridPlanes.Primary,
    sceneDepth: Chart3DSceneDepth = Chart3DSceneDepth.Auto,
    fit: Chart3DSceneFit = Chart3DSceneFit.Content,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: Chart3DInteraction = Chart3DInteraction.RotateAndSelect,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderChartMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    sceneState: ChartSceneState? = null,
    debug: Chart3DDebug = Chart3DDebug.None,
    onDiagnostics: ((Chart3DDiagnostics) -> Unit)? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Scatter3DTooltip(it)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val density = LocalDensity.current
    val theme = ChartKitTheme.current

    val visibleSeries = remember(series, state.hiddenSeriesIds) {
        series.map { it.copy(visible = it.visible && state.isSeriesVisible(it.id)) }
    }

    // The shared normalisation, over x and y. Never `Zero` for a missing
    // value: an observation whose income was not recorded has no position, and
    // putting it on the axis would invent a measurement.
    val plotData = remember(visibleSeries, x, y) {
        normalizeSeries(
            series = visibleSeries,
            x = { item -> x(item) },
            y = y,
            xResolver = io.devkit.chartkit.model.ChartXResolver.Default,
            missingValuePolicy = MissingValuePolicy.Break,
            xAxisKind = ChartXAxisKind.Numeric,
        )
    }

    val rawX = remember(visibleSeries, x) {
        visibleSeries.map { s -> s.data.map { item -> x(item)?.toDouble() } }
    }
    val rawY = remember(visibleSeries, y) {
        visibleSeries.map { s -> s.data.map { item -> y(item)?.toDouble() } }
    }
    val rawZ = remember(visibleSeries, z) {
        visibleSeries.map { s -> s.data.map { item -> z(item)?.toDouble() } }
    }
    val seriesIds = remember(visibleSeries) { visibleSeries.map { it.id } }

    // ### Interpolated in data space, never on screen
    //
    // Three animations on the same clock, one per coordinate, each running
    // between the caller's own numbers. Everything downstream — the three
    // scales, the world position, the camera, the projection — re-runs from the
    // interpolated values on every frame, so a point that moves while the
    // reader is dragging the camera follows a straight line *in the data* and
    // not a straight line across the glass. Interpolating projected screen
    // positions would be cheaper and would be wrong the moment the two
    // animations overlap, which on an interactive chart is most of the time.
    val animatedX = rememberAnimatedSeriesValues(rawX, seriesIds, animation)
    val animatedY = rememberAnimatedSeriesValues(rawY, seriesIds, animation)
    val animatedZ = rememberAnimatedSeriesValues(rawZ, seriesIds, animation)

    val animatedData = remember(plotData, animatedX, animatedY) {
        plotData.withCoordinates(animatedX, animatedY)
    }
    val zValues = remember(seriesIds, animatedZ) {
        seriesIds.mapIndexed { index, id -> id to animatedZ.getOrElse(index) { emptyList() } }
            .toMap()
    }

    val sizes = remember(visibleSeries, size) {
        size?.let { accessor ->
            visibleSeries.associate { s -> s.id to s.data.map { accessor(it)?.toDouble() } }
        }
    }
    val colorValues = remember(visibleSeries, color) {
        color?.let { accessor ->
            visibleSeries.associate { s -> s.id to s.data.map { accessor(it)?.toDouble() } }
        }
    }

    // One size scale across every series, so two groups in one chart are
    // measured against one domain — the same rule the 2D bubble chart follows,
    // and for the same reason.
    val sizeScale = remember(sizes, sizeDomain, minMarkerSize, maxMarkerSize, sizeMode, theme) {
        sizes?.let { values ->
            SizeScale(
                domain = sizeDomain.apply(NumericDomain.of(values.values.flatten().filterNotNull())),
                minSize = with(density) {
                    (minMarkerSize ?: theme.dimensions.scatter3DMinMarkerRadius).toPx()
                },
                maxSize = with(density) {
                    (maxMarkerSize ?: theme.dimensions.scatter3DMaxMarkerRadius).toPx()
                },
                mode = sizeMode,
            )
        }
    }

    val defaultColorScale = ChartColorScales.continuous(
        remember(colorValues) {
            NumericDomain.of(colorValues?.values?.flatten()?.filterNotNull().orEmpty())
                ?: NumericDomain.Default
        },
    )
    val resolvedColorScale = if (colorValues == null) null else colorScale ?: defaultColorScale

    val camera = cameraState.camera

    val layers = remember(
        animatedData, zValues, sizes, colorValues, sizeScale, resolvedColorScale,
        xAxis, yAxis, zAxis, xUnit, yUnit, zUnit, zDomain, sceneDepth, camera, projection,
        lighting, frame, gridPlanes, fit, marker, markerSize, renderMode, guides, debug,
        onDiagnostics,
    ) {
        listOf(
            ResolvedLayer.Scatter3D(
                key = "scatter3d",
                data = animatedData,
                zValues = zValues,
                sizes = sizes,
                colorValues = colorValues,
                sizeScale = sizeScale,
                colorScale = resolvedColorScale,
                xAxis = xAxis,
                yAxis = yAxis,
                zAxis = zAxis,
                xUnit = xUnit,
                yUnit = yUnit,
                zUnit = zUnit,
                zDomain = zDomain,
                sceneDepth = sceneDepth,
                camera = { camera },
                projection = projection,
                lighting = lighting,
                frame = frame,
                gridPlanes = gridPlanes,
                fit = fit,
                marker = marker,
                markerRadius = markerSize,
                renderMode = renderMode,
                guides = guides,
                debug = debug,
                onDiagnostics = onDiagnostics,
            ),
        )
    }

    val registry = remember(xAxis, yAxis, zAxis, xUnit, yUnit, zUnit, xDomain, yDomain, zDomain) {
        cartesian3DAxisRegistry(
            xAxis = xAxis, yAxis = yAxis, zAxis = zAxis,
            xUnit = xUnit, yUnit = yUnit, zUnit = zUnit,
            xDomain = xDomain, yDomain = yDomain, zDomain = zDomain,
        )
    }

    // ### The legend a colour encoding needs
    //
    // A series legend names series; it has nothing to say about a fourth
    // channel. When colour carries a *quantity*, the key a reader needs is the
    // ramp and the numbers at its ends — the same key a heatmap and a
    // choropleth show, from the same component and the same scale object, so a
    // marker's colour and the legend beside it cannot disagree.
    val colorKey = resolvedColorScale.takeIf { colorLegend && colorValues != null }
    val body: @Composable (Modifier) -> Unit = { bodyModifier ->
    CartesianChartCore(
        layers = layers,
        modifier = bodyModifier,
        orientation = ChartOrientation.Vertical,
        // Invisible rather than replaced, exactly as [ColumnChart3D] does it:
        // the layer draws its own labels at projected positions, and an
        // invisible axis takes no gutter, so the plot is the whole canvas and
        // the scene reserves what its own labels measured. The configuration —
        // formatter, tick count, title, domain — is still the caller's, and
        // still reaches the layer.
        domainAxis = xAxis.copy(visible = false, domain = xAxis.domain ?: xDomain),
        valueAxis = yAxis.copy(visible = false),
        grid = ChartGrid.None,
        valueDomainPolicy = yAxis.domain ?: yDomain,
        legend = legend,
        legendTogglesSeries = legendTogglesSeries,
        animation = animation,
        interaction = if (interaction.selects) ChartInteraction.TapOnly else ChartInteraction.None,
        crosshair = CrosshairConfig.None,
        // A 3D marker is a disc with a real radius, and the layer's own test
        // asks whether the pointer is inside it. `NearestDomain` would ask the
        // engine to answer along a domain axis that is not on screen.
        hitTestMode = HitTestMode.Contains,
        sharedTooltip = false,
        state = state.asErased(),
        viewportState = rememberChartViewportState(),
        sharedCrosshair = null,
        annotations = emptyList(),
        axisRegistry = registry,
        renderMode = renderChartMode,
        staticOptions = staticOptions,
        sceneState = sceneState,
        // Arrow keys step through the observations, and never move the camera.
        // A reader exploring a chart with a keyboard is reading values; losing
        // that to a rotation control would trade the accessible interaction for
        // a decorative one.
        keyboardNavigation = true,
        plotModifier = rotateModifier(interaction, renderChartMode, cameraState),
        onSelectionChanged = onSelectionChanged?.let { callback ->
            { erased -> callback(erased?.asTyped()) }
        },
        onRangeSelectionChanged = null,
        tooltip = tooltip?.let { slot -> { erased -> slot(erased.asTypedTooltip()) } },
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary ?: {
            scatter3DSummary(
                seriesCount = visibleSeries.count { it.visible },
                pointCount = plotData.visibleSeries.sumOf { it.points.count { p -> p.isPresent } },
                xTitle = xAxis.title,
                yTitle = yAxis.title,
                zTitle = zAxis.title,
            )
        },
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
    }

    if (colorKey == null) {
        body(modifier)
    } else {
        androidx.compose.foundation.layout.Column(modifier) {
            body(Modifier.weight(1f))
            io.devkit.chartkit.components.legend.ChartColorLegend(
                scale = colorKey,
                modifier = Modifier.padding(top = ChartKitTheme.dimensions.labelPadding),
                title = colorLegendTitle,
            )
        }
    }
}

/**
 * The three axes of a true 3D Cartesian chart, as a registry.
 *
 * ### The Z axis is registered, not invented
 *
 * It gets a stable [ChartAxisId], the duplicate check every other axis gets,
 * the same title/unit/domain/formatter fields, and a name a layer could bind
 * to. Nothing about X or Y changes, and no second registry type exists — which
 * is what §44 asks for and what makes "does this chart have a third dimension"
 * a question with one answer: whether [AxisRegistry.zAxes] is empty.
 *
 * X and Y are marked invisible because a 3D chart draws its own labels at
 * projected positions; the *configuration* is kept, so the titles, the tick
 * counts, the formatters and the domain policies all still reach the layer and
 * the accessibility summary.
 */
@Suppress("LongParameterList")
internal fun cartesian3DAxisRegistry(
    xAxis: ChartAxis,
    yAxis: ChartAxis,
    zAxis: ChartAxis,
    xUnit: ChartUnit = ChartUnit.None,
    yUnit: ChartUnit = ChartUnit.None,
    zUnit: ChartUnit = ChartUnit.None,
    xDomain: DomainPolicy = DomainPolicy.Default,
    yDomain: DomainPolicy = DomainPolicy.Default,
    zDomain: DomainPolicy = DomainPolicy.Default,
): AxisRegistry = AxisRegistry.of(
    listOf(
        ChartAxisSpec(
            id = ChartAxisId.DefaultX,
            dimension = AxisDimension.X,
            axis = xAxis.copy(visible = false),
            unit = xUnit,
            domain = xAxis.domain ?: xDomain,
        ),
        ChartAxisSpec(
            id = ChartAxisId.DefaultY,
            dimension = AxisDimension.Y,
            axis = yAxis.copy(visible = false),
            unit = yUnit,
            domain = yAxis.domain ?: yDomain,
            primary = true,
        ),
        ChartAxisSpec(
            id = ChartAxisId.DefaultZ,
            dimension = AxisDimension.Z,
            axis = zAxis,
            unit = zUnit,
            domain = zAxis.domain ?: zDomain,
        ),
    ),
    orientation = ChartOrientation.Vertical,
)

/**
 * Drag to rotate, pinch to zoom — the same gesture the 3D column chart uses.
 *
 * ### Tap and drag do not fight
 *
 * [detectTransformGestures] reports nothing until the pointer has moved past
 * the platform's own touch slop, so a tap never reaches this at all and never
 * moves the camera by the pixel or two a finger travels while pressing.
 * Selection is handled by the engine's ordinary tap gesture, applied outside
 * this one; a drag that has begun consumes its events, so a rotation does not
 * leave a trail of selections behind it.
 */
@Composable
private fun rotateModifier(
    interaction: Chart3DInteraction,
    renderMode: ChartRenderMode,
    cameraState: Chart3DCameraState,
): Modifier = if (interaction.rotates && !renderMode.isStatic) {
    Modifier.pointerInput(cameraState) {
        detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
            // The scene follows the finger, as though the reader had hold of
            // the front of it. Both signs are the opposite of what steering a
            // camera would give, and turning an object is what a reader expects
            // from a drag on the object itself.
            if (pan.x != 0f || pan.y != 0f) {
                cameraState.rotateBy(
                    deltaX = pan.y * SCATTER_ROTATION_SENSITIVITY,
                    deltaY = -pan.x * SCATTER_ROTATION_SENSITIVITY,
                )
            }
            if (zoom != 1f && zoom > 0f) cameraState.zoomBy(zoom)
        }
    }
} else {
    Modifier
}

/**
 * What a screen reader hears about the chart as a whole.
 *
 * Three axis names and a count, and nothing about the camera. A reader who
 * cannot see the picture gains nothing from being told the scene is turned 30°,
 * and a chart that said so would be describing its own rendering.
 */
private fun scatter3DSummary(
    seriesCount: Int,
    pointCount: Int,
    xTitle: String?,
    yTitle: String?,
    zTitle: String?,
): String = buildString {
    append("3D scatter chart. ")
    append("X axis: ${xTitle ?: "X"}. ")
    append("Y axis: ${yTitle ?: "Y"}. ")
    append("Z axis: ${zTitle ?: "Z"}. ")
    append("$pointCount observation${if (pointCount == 1) "" else "s"}")
    if (seriesCount > 1) append(" in $seriesCount series")
    append(".")
}

/** Degrees of camera rotation per pixel of drag, for a chart meant to be turned. */
private const val SCATTER_ROTATION_SENSITIVITY = 0.28

/**
 * The same points at new x and y values, for an animation frame.
 *
 * Both coordinates and not only the value, which is where this differs from
 * [PlotData.withValues]: on a scatter the horizontal position is a measurement
 * too, and animating only y would slide a point vertically to a place it never
 * occupied. The domains are the target's, so the axis labels do not flicker
 * through intermediate values while the cloud moves — the same choice
 * `withValues` makes and for the same reason.
 */
internal fun PlotData.withCoordinates(
    xs: List<List<Double?>>,
    ys: List<List<Double?>>,
): PlotData {
    if (xs.size != series.size || ys.size != series.size) return this
    return PlotData(
        series = series.mapIndexed { seriesIndex, source ->
            val nextX = xs[seriesIndex]
            val nextY = ys[seriesIndex]
            io.devkit.chartkit.model.PlotSeries(
                id = source.id,
                name = source.name,
                points = source.points.map { point ->
                    point.copy(
                        // Indexed by source index rather than by position:
                        // the animated lists are in the caller's own data
                        // order, and that is what a point's source index is.
                        x = nextX.getOrNull(point.sourceIndex)
                            ?.let { ChartX.Numeric(it) } ?: point.x,
                        y = nextY.getOrNull(point.sourceIndex) ?: point.y,
                    )
                },
                items = source.items,
                visible = source.visible,
                color = source.color,
                paletteIndex = source.paletteIndex,
            )
        },
        xAxisKind = xAxisKind,
        categories = categories,
        xDomain = xDomain,
        yDomain = yDomain,
    )
}

