package io.devkit.chartkit.charts

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.animation.rememberAnimatedSeriesValues
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartGrid
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.DEFAULT_GROUP_PADDING
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.three.Chart3DDebug
import io.devkit.chartkit.layer.three.Column3DLabelPlacement
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.model.ChartXAxisKind
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.model.MissingValuePolicy
import io.devkit.chartkit.model.normalizeSeries
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.scale.CategoryScale
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.scene.ChartSceneState
import io.devkit.chartkit.state.Chart3DCameraState
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChart3DCameraState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberChartViewportState
import io.devkit.chartkit.three.Chart3DDiagnostics
import io.devkit.chartkit.three.Chart3DFrame
import io.devkit.chartkit.three.Chart3DLighting
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Column3DArrangement
import io.devkit.chartkit.three.Chart3DDepth

/**
 * What a pointer does to a 3D chart.
 *
 * Rotation is **off by default**, and that is a deliberate analytical position
 * rather than caution about the implementation. A chart the reader can turn is
 * a chart whose columns are at an angle nobody chose, which makes two readers
 * looking at the same dashboard see two different pictures — and under
 * perspective, two different apparent heights. Turn it on where exploring the
 * arrangement is the point, and leave it off where the chart is making a claim.
 */
enum class Chart3DInteraction {

    /** Nothing. A picture. */
    None,

    /** Tap selects a column. The default. */
    Select,

    /** Drag turns the camera and pinch moves it closer. Nothing is selectable. */
    Rotate,

    /** Both: a tap selects, a drag turns. */
    RotateAndSelect,
    ;

    val selects: Boolean get() = this == Select || this == RotateAndSelect
    val rotates: Boolean get() = this == Rotate || this == RotateAndSelect
}

/**
 * A 3D column chart over the caller's own data.
 *
 * ```kotlin
 * data class Sale(val month: String, val total: Double)
 *
 * ColumnChart3D(
 *     data = sales,
 *     category = { it.month },
 *     value = { it.total },
 * )
 * ```
 *
 * ### What the third dimension is for
 *
 * Depth here carries *grouping*, not a quantity. There is no z axis to read a
 * number off, and there is deliberately no way to ask for one: a numeric depth
 * axis on a perspective projection would encode values in the one direction the
 * reader cannot measure. Height is the measurement; depth says which pile a
 * segment belongs to.
 *
 * ### And what it costs
 *
 * Perspective makes a far column smaller than a near one of the same value.
 * That is what perspective *is*, and the price of the depth cue. Where the
 * comparison matters more than the arrangement, either use the flat [BarChart]
 * or set `projection = Chart3DProjection.Orthographic`, under which two equal
 * values are drawn at equal heights wherever they stand.
 *
 * @param category reads the band from an item. See [ChartXResolver].
 * @param value reads the column's value. Negative values draw below the floor.
 */
@Suppress("LongParameterList")
@Composable
fun <T> ColumnChart3D(
    data: List<T>,
    category: (T) -> Any?,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    seriesName: String = "",
    depth: Chart3DDepth = Chart3DDepth.Auto,
    categoryPadding: Double = CategoryScale.DEFAULT_CATEGORY_PADDING,
    categoryAxis: ChartAxis = ChartAxis.Default,
    valueAxis: ChartAxis = ChartAxis.Default,
    cameraState: Chart3DCameraState = rememberChart3DCameraState(),
    projection: Chart3DProjection = Chart3DProjection.Default,
    lighting: Chart3DLighting = Chart3DLighting.Default,
    frame: Chart3DFrame = Chart3DFrame.Auto,
    valueLabels: Column3DLabelPlacement = Column3DLabelPlacement.None,
    valueDomain: DomainPolicy = DomainPolicy.Baseline,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: Chart3DInteraction = Chart3DInteraction.Select,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
) {
    ColumnChart3D(
        series = remember(data, seriesName) {
            singleSeries(data, ChartDefaults.SINGLE_SERIES_ID, seriesName)
        },
        category = category,
        value = value,
        modifier = modifier,
        depth = depth,
        categoryPadding = categoryPadding,
        categoryAxis = categoryAxis,
        valueAxis = valueAxis,
        legend = LegendPosition.None,
        cameraState = cameraState,
        projection = projection,
        lighting = lighting,
        frame = frame,
        valueLabels = valueLabels,
        valueDomain = valueDomain,
        valueFormatter = valueFormatter,
        animation = animation,
        interaction = interaction,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        state = state,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        isLoading = isLoading,
        error = error,
    )
}

/**
 * A multi-series 3D column chart: grouped, stacked, grouped-and-stacked or
 * 100% stacked.
 *
 * ```kotlin
 * ColumnChart3D(
 *     series = listOf(
 *         ChartSeries("john", "John", john),
 *         ChartSeries("jane", "Jane", jane),
 *         ChartSeries("joe", "Joe", joe),
 *         ChartSeries("janet", "Janet", janet),
 *     ),
 *     category = { it.fruit },
 *     value = { it.count },
 *     grouping = BarGrouping.Stacked,
 *     // Two piles per category rather than one, side by side.
 *     stack = { series -> if (series.id in setOf("john", "jane")) "male" else "female" },
 * )
 * ```
 *
 * ### Stacks and groups are different things
 *
 * [grouping] says whether the members of one stack are *piled* — nothing else.
 * [stack] says which pile a series belongs to, and two piles are two footprints
 * whether or not either of them is stacked. That separation is what makes
 * "grouped and stacked" one configuration rather than a special case: with
 * `grouping = Stacked` and two stack ids you get the reference arrangement, and
 * with `grouping = Grouped` and no [stack] at all every series is its own pile
 * and the chart is an ordinary grouped one.
 *
 * @param stack which stack a series belongs to, or `null` for one of its own.
 * @param arrangement whether stacks stand side by side or one behind the other.
 * @param legendTogglesSeries whether tapping a legend row hides the series.
 *   Hiding one recomputes its stack's totals and the value domain, exactly as
 *   it does in 2D.
 */
@JvmName("ColumnChart3DSeries")
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <T> ColumnChart3D(
    series: List<ChartSeries<T>>,
    category: (T) -> Any?,
    value: (T) -> Number?,
    modifier: Modifier = Modifier,
    grouping: BarGrouping = BarGrouping.Grouped,
    stack: ((ChartSeries<T>) -> String?)? = null,
    arrangement: Column3DArrangement = Column3DArrangement.Side,
    depth: Chart3DDepth = Chart3DDepth.Auto,
    categoryPadding: Double = CategoryScale.DEFAULT_CATEGORY_PADDING,
    groupPadding: Double = DEFAULT_GROUP_PADDING,
    depthGap: Double = DEFAULT_DEPTH_GAP,
    categoryAxis: ChartAxis = ChartAxis.Default,
    valueAxis: ChartAxis = ChartAxis.Default,
    legend: LegendPosition = if (series.size > 1) LegendPosition.Bottom else LegendPosition.None,
    legendTogglesSeries: Boolean = false,
    cameraState: Chart3DCameraState = rememberChart3DCameraState(),
    projection: Chart3DProjection = Chart3DProjection.Default,
    lighting: Chart3DLighting = Chart3DLighting.Default,
    frame: Chart3DFrame = Chart3DFrame.Auto,
    valueLabels: Column3DLabelPlacement = Column3DLabelPlacement.None,
    valueDomain: DomainPolicy = DomainPolicy.Baseline,
    valueFormatter: ChartValueFormatter? = null,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: Chart3DInteraction = Chart3DInteraction.Select,
    missingValuePolicy: MissingValuePolicy = MissingValuePolicy.Break,
    xResolver: ChartXResolver = ChartXResolver.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    sceneState: ChartSceneState? = null,
    debug: Chart3DDebug = Chart3DDebug.None,
    onDiagnostics: ((Chart3DDiagnostics) -> Unit)? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val visibleSeries = remember(series, state.hiddenSeriesIds) {
        series.map { it.copy(visible = it.visible && state.isSeriesVisible(it.id)) }
    }

    val plotData = remember(visibleSeries, category, value, xResolver, missingValuePolicy) {
        normalizeSeries(
            series = visibleSeries,
            x = category,
            y = value,
            xResolver = xResolver,
            missingValuePolicy = missingValuePolicy,
            xAxisKind = ChartXAxisKind.Category,
        )
    }

    val targetValues = remember(plotData) { plotData.series.map { s -> s.points.map { it.y } } }
    val animatedValues = rememberAnimatedSeriesValues(
        target = targetValues,
        seriesIds = remember(plotData) { plotData.series.map { it.id } },
        animation = animation,
    )
    val animatedData = remember(plotData, animatedValues) { plotData.withValues(animatedValues) }

    val stacks = remember(series, stack) {
        series.mapNotNull { s -> stack?.invoke(s)?.let { s.id to it } }.toMap()
    }

    // Read here, in composition, and captured as a lambda the layer calls at
    // draw time. Reading it in the layer's constructor instead would freeze the
    // camera into the geometry, and a rotation would rebuild the whole stack
    // layout to move the eye.
    val camera = cameraState.camera

    val resolvedValueFormatter = valueFormatter ?: if (grouping == BarGrouping.StackedPercent) {
        io.devkit.chartkit.formatter.ChartNumberFormatters.fraction()
    } else {
        null
    }
    val effectiveValueAxis = if (
        resolvedValueFormatter != null && valueAxis.valueFormatter == null
    ) {
        valueAxis.copy(valueFormatter = resolvedValueFormatter)
    } else {
        valueAxis
    }

    val layers = remember(
        animatedData, grouping, stacks, arrangement, depth, categoryPadding, groupPadding,
        depthGap, camera, projection, lighting, frame, valueLabels, categoryAxis,
        effectiveValueAxis, debug, onDiagnostics,
    ) {
        listOf(
            ResolvedLayer.Columns3D(
                key = "columns3d",
                data = animatedData,
                stacks = stacks,
                grouping = grouping,
                arrangement = arrangement,
                depth = depth,
                categoryPadding = categoryPadding,
                groupPadding = groupPadding,
                depthGap = depthGap,
                camera = { camera },
                projection = projection,
                lighting = lighting,
                frame = frame,
                labels = valueLabels,
                categoryAxis = categoryAxis,
                valueAxis = effectiveValueAxis,
                debug = debug,
                onDiagnostics = onDiagnostics,
            ),
        )
    }

    val rotateModifier = if (interaction.rotates && !renderMode.isStatic) {
        Modifier.pointerInput(cameraState) {
            detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
                // The scene follows the finger, as though the reader had hold
                // of the front of it: dragging down pulls the front face down
                // and rolls the tops of the columns into view, and dragging
                // right swings the front to the right so the left-hand side
                // comes round. Both signs are the opposite of what steering a
                // camera would give, and turning an object is what a reader
                // expects from a drag on the object itself.
                if (pan.x != 0f || pan.y != 0f) {
                    cameraState.rotateBy(
                        deltaX = pan.y * ROTATION_SENSITIVITY,
                        deltaY = -pan.x * ROTATION_SENSITIVITY,
                    )
                }
                if (zoom != 1f && zoom > 0f) cameraState.zoomBy(zoom)
            }
        }
    } else {
        Modifier
    }

    CartesianChartCore(
        layers = layers,
        modifier = modifier,
        orientation = ChartOrientation.Vertical,
        // The caller's own axes, made invisible rather than replaced. The flat
        // axis renderer is switched off — the layer draws its own labels at
        // projected positions on the frame, and an invisible axis takes no
        // gutter, so the plot is the whole canvas and the scene reserves what
        // its labels actually measure. Keeping the *configuration* matters:
        // the domain policy, the formatter and the titles all still come from
        // it, and the titles still reach a screen reader through the chart's
        // summary. Replacing them with `ChartAxis.Hidden` would silently drop
        // all four. See `measureColumns3DAxes`.
        domainAxis = categoryAxis.copy(visible = false),
        valueAxis = effectiveValueAxis.copy(visible = false),
        grid = ChartGrid.None,
        valueDomainPolicy = valueAxis.domain ?: valueDomain,
        legend = legend,
        legendTogglesSeries = legendTogglesSeries,
        animation = animation,
        interaction = if (interaction.selects) ChartInteraction.TapOnly else ChartInteraction.None,
        crosshair = CrosshairConfig.None,
        hitTestMode = HitTestMode.Contains,
        sharedTooltip = true,
        state = state.asErased(),
        viewportState = rememberChartViewportState(),
        sharedCrosshair = null,
        annotations = emptyList(),
        renderMode = renderMode,
        staticOptions = staticOptions,
        sceneState = sceneState,
        // Arrow keys step through the categories, exactly as they do on a 2D
        // bar chart. The camera does not take them: a reader navigating a chart
        // with a keyboard is reading values, and losing that to a rotation
        // control would trade the accessible interaction for a decorative one.
        keyboardNavigation = true,
        plotModifier = rotateModifier,
        onSelectionChanged = onSelectionChanged?.let { callback ->
            { erased -> callback(erased?.asTyped()) }
        },
        onRangeSelectionChanged = null,
        tooltip = tooltip?.let { slot -> { erased -> slot(erased.asTypedTooltip()) } },
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}

/** Degrees of camera rotation per pixel of drag. */
private const val ROTATION_SENSITIVITY = 0.32

/** A gap of this fraction of a column's depth between two depth rows. */
const val DEFAULT_DEPTH_GAP: Double = 0.25

/**
 * The low-level 3D chart: a camera, a projection, and a block of layers.
 *
 * ```kotlin
 * val camera = rememberChart3DCameraState(rotationX = 15.0, rotationY = 20.0)
 *
 * CartesianChart3D(cameraState = camera, projection = Chart3DProjection.Orthographic) {
 *     columns(
 *         series = listOf(ChartSeries("john", "John", john), ChartSeries("jane", "Jane", jane)),
 *         category = { it.fruit },
 *         value = { it.count },
 *         grouping = BarGrouping.Stacked,
 *         stack = { if (it.id == "john") "male" else "female" },
 *     )
 * }
 * ```
 *
 * [ColumnChart3D] is this with one layer and a shorter parameter list. Both
 * reach the same [CartesianChartCore], so the legend, the tooltip, the
 * selection model, the animation clock and the accessibility summary are not
 * merely similar between them — they are the same code.
 *
 * Marked [ExperimentalChartKitApi] for the same reason [CartesianChart] is: the
 * layer grammar is where a real multi-layer 3D scene will want room to move.
 * [ColumnChart3D] is not, and is the API to reach for unless you need this one.
 */
@ExperimentalChartKitApi
@Suppress("LongParameterList")
@Composable
fun CartesianChart3D(
    modifier: Modifier = Modifier,
    categoryAxis: ChartAxis = ChartAxis.Default,
    valueAxis: ChartAxis = ChartAxis.Default,
    valueDomain: DomainPolicy = DomainPolicy.Baseline,
    cameraState: Chart3DCameraState = rememberChart3DCameraState(),
    projection: Chart3DProjection = Chart3DProjection.Default,
    lighting: Chart3DLighting = Chart3DLighting.Default,
    frame: Chart3DFrame = Chart3DFrame.Auto,
    legend: LegendPosition = LegendPosition.Bottom,
    legendTogglesSeries: Boolean = false,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: Chart3DInteraction = Chart3DInteraction.Select,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    sceneState: ChartSceneState? = null,
    debug: Chart3DDebug = Chart3DDebug.None,
    onDiagnostics: ((Chart3DDiagnostics) -> Unit)? = null,
    state: ChartState<Any?> = rememberChartState(),
    onSelectionChanged: ((io.devkit.chartkit.model.AnyChartSelection?) -> Unit)? = null,
    tooltip: (@Composable (io.devkit.chartkit.model.AnyChartTooltipData) -> Unit)? = {
        ChartDefaults.Tooltip(it)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
    content: CartesianChart3DScope.() -> Unit,
) {
    val camera = cameraState.camera
    val layers = remember(
        content, state.hiddenSeriesIds, camera, projection, lighting, frame,
        categoryAxis, valueAxis, debug, onDiagnostics,
    ) {
        CartesianChart3DScope(
            hiddenSeriesIds = state.hiddenSeriesIds,
            camera = { camera },
            projection = projection,
            lighting = lighting,
            frame = frame,
            categoryAxis = categoryAxis,
            valueAxis = valueAxis,
            debug = debug,
            onDiagnostics = onDiagnostics,
        ).apply(content).layers.toList()
    }

    val rotateModifier = if (interaction.rotates && !renderMode.isStatic) {
        Modifier.pointerInput(cameraState) {
            detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
                if (pan.x != 0f || pan.y != 0f) {
                    cameraState.rotateBy(
                        deltaX = pan.y * ROTATION_SENSITIVITY,
                        deltaY = -pan.x * ROTATION_SENSITIVITY,
                    )
                }
                if (zoom != 1f && zoom > 0f) cameraState.zoomBy(zoom)
            }
        }
    } else {
        Modifier
    }

    CartesianChartCore(
        layers = layers,
        modifier = modifier,
        orientation = ChartOrientation.Vertical,
        // Invisible, not replaced: see the note in ColumnChart3D.
        domainAxis = categoryAxis.copy(visible = false),
        valueAxis = valueAxis.copy(visible = false),
        grid = ChartGrid.None,
        valueDomainPolicy = valueAxis.domain ?: valueDomain,
        legend = legend,
        legendTogglesSeries = legendTogglesSeries,
        animation = animation,
        interaction = if (interaction.selects) ChartInteraction.TapOnly else ChartInteraction.None,
        crosshair = CrosshairConfig.None,
        hitTestMode = HitTestMode.Contains,
        sharedTooltip = true,
        state = state,
        viewportState = rememberChartViewportState(),
        sharedCrosshair = null,
        annotations = emptyList(),
        renderMode = renderMode,
        staticOptions = staticOptions,
        sceneState = sceneState,
        keyboardNavigation = true,
        plotModifier = rotateModifier,
        onSelectionChanged = onSelectionChanged,
        onRangeSelectionChanged = null,
        tooltip = tooltip,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}

/**
 * Declares the layers of a [CartesianChart3D].
 *
 * One layer type today. The scope exists anyway, for the same reason
 * [Chart3DObject][io.devkit.chartkit.three.Chart3DObject] is not a cuboid: a 3D
 * scatter or a 3D area is a new `fun` here and a new scene object, not a new
 * chart engine.
 */
@ExperimentalChartKitApi
class CartesianChart3DScope internal constructor(
    private val hiddenSeriesIds: Set<String>,
    private val camera: () -> io.devkit.chartkit.three.Chart3DCamera,
    private val projection: Chart3DProjection,
    private val lighting: Chart3DLighting,
    private val frame: Chart3DFrame,
    private val categoryAxis: ChartAxis,
    private val valueAxis: ChartAxis,
    private val debug: Chart3DDebug,
    private val onDiagnostics: ((Chart3DDiagnostics) -> Unit)?,
) {
    internal val layers = mutableListOf<ResolvedLayer>()
    private var declaredSeries = 0

    /**
     * A 3D column layer.
     *
     * @param stack which stack each series belongs to. A series named nowhere
     *   stacks with itself, so leaving it out gives an ordinary grouped chart.
     */
    @Suppress("LongParameterList")
    fun <T> columns(
        series: List<ChartSeries<T>>,
        category: (T) -> Any?,
        value: (T) -> Number?,
        grouping: BarGrouping = BarGrouping.Grouped,
        stack: ((ChartSeries<T>) -> String?)? = null,
        arrangement: Column3DArrangement = Column3DArrangement.Side,
        depth: Chart3DDepth = Chart3DDepth.Auto,
        categoryPadding: Double = CategoryScale.DEFAULT_CATEGORY_PADDING,
        groupPadding: Double = DEFAULT_GROUP_PADDING,
        depthGap: Double = DEFAULT_DEPTH_GAP,
        valueLabels: Column3DLabelPlacement = Column3DLabelPlacement.None,
        missingValuePolicy: MissingValuePolicy = MissingValuePolicy.Break,
        xResolver: ChartXResolver = ChartXResolver.Default,
    ) {
        val visible = series.map { it.copy(visible = it.visible && it.id !in hiddenSeriesIds) }
        val data = normalizeSeries(
            series = visible,
            x = category,
            y = value,
            xResolver = xResolver,
            missingValuePolicy = missingValuePolicy,
            xAxisKind = ChartXAxisKind.Category,
        ).withPaletteOffset(declaredSeries)
        declaredSeries += series.size

        layers += ResolvedLayer.Columns3D(
            key = "columns3d${layers.size}",
            data = data,
            stacks = series.mapNotNull { s -> stack?.invoke(s)?.let { s.id to it } }.toMap(),
            grouping = grouping,
            arrangement = arrangement,
            depth = depth,
            categoryPadding = categoryPadding,
            groupPadding = groupPadding,
            depthGap = depthGap,
            camera = camera,
            projection = projection,
            lighting = lighting,
            frame = frame,
            labels = valueLabels,
            categoryAxis = categoryAxis,
            valueAxis = valueAxis,
            debug = debug,
            onDiagnostics = onDiagnostics,
        )
    }
}
