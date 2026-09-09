package io.devkit.chartkit.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.geometry.PolarValuePolicy
import io.devkit.chartkit.geometry.computePolarSlices
import io.devkit.chartkit.layer.polar.SliceLabelContent
import io.devkit.chartkit.layer.polar.SliceLabelPosition
import io.devkit.chartkit.layer.polar.SliceSeriesEntry
import io.devkit.chartkit.layer.three.Chart3DDebug
import io.devkit.chartkit.layer.three.Radial3DLayer
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.Chart3DCameraState
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChart3DCameraState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DCameraLimits
import io.devkit.chartkit.three.Chart3DDepth
import io.devkit.chartkit.three.Chart3DDiagnostics
import io.devkit.chartkit.three.Chart3DLighting
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Chart3DQuality
import kotlinx.coroutines.launch

/**
 * A 3D pie chart over the caller's own data.
 *
 * ```kotlin
 * data class Share(val browser: String, val users: Double)
 *
 * PieChart3D(
 *     data = shares,
 *     value = { it.users },
 *     label = { it.browser },
 *     modifier = Modifier.fillMaxWidth().height(320.dp),
 * )
 * ```
 *
 * No conversion step and no slice type, exactly as with [PieChart]: `data` is a
 * `List<Share>` and stays one. Values are normalised, the first slice starts at
 * twelve o'clock and the chart runs clockwise — all three because this *is*
 * [PieChart]'s arithmetic, called with the same arguments and then extruded.
 *
 * ### What the third dimension costs
 *
 * Under perspective a slice at the front is drawn larger than a slice of the
 * same share at the back. That is what perspective is, and it is the price of
 * the depth cue. Where comparing shares precisely is the point of the chart,
 * either use the flat [PieChart] or set
 * `projection = Chart3DProjection.Orthographic`, which keeps the extrusion and
 * removes the size distortion.
 *
 * The values, the percentages, the tooltip, the legend and everything a screen
 * reader hears are unaffected by any of this: none of them is measured from the
 * picture. See [io.devkit.chartkit.layer.three.Radial3DLayer].
 *
 * @param value the slice's magnitude. Negative and `NaN` values are dropped by
 *   default — a share of a whole is neither — under the same
 *   [PolarValuePolicy] a flat pie uses.
 * @param label its name, used by the legend, the tooltip, slice labels and the
 *   accessibility summary.
 * @param innerRadiusRatio the hole, as a fraction of the outer radius. Zero
 *   here; [DonutChart3D] is this function with a hole and a centre slot.
 * @param depth how far the slices are extruded, as a fraction of the outer
 *   radius. Relative rather than absolute so one setting works at every chart
 *   size; see [Chart3DDepth].
 * @param explodeSelected whether tapping a slice slides it outward. On by
 *   default: displacement survives being printed, screenshotted or read by
 *   somebody who cannot separate two colours, and a colour change does not.
 * @param explode an additional, permanent explode for slices the caller wants
 *   emphasised whatever is selected.
 * @param explodeDistance how far an exploded slice moves. Unspecified takes the
 *   theme's own.
 * @param quality how finely the curved rim is approximated. [Chart3DQuality.Auto]
 *   derives it from the radius the chart is actually drawn at.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun <T> PieChart3D(
    data: List<T>,
    value: (T) -> Number?,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    innerRadiusRatio: Float = 0f,
    startAngle: Float = 0f,
    sweepAngle: Float = PolarGeometry.FULL_CIRCLE,
    direction: PolarDirection = PolarDirection.Clockwise,
    sliceGap: Float = 0f,
    valuePolicy: PolarValuePolicy = PolarValuePolicy.Ignore,
    color: ((T) -> Int?)? = null,
    depth: Chart3DDepth = Chart3DDepth.Auto,
    quality: Chart3DQuality = Chart3DQuality.Auto,
    cameraState: Chart3DCameraState = rememberChart3DCameraState(
        camera = Chart3DCamera.Radial,
        limits = Chart3DCameraLimits.Radial,
    ),
    projection: Chart3DProjection = Chart3DProjection.Default,
    lighting: Chart3DLighting = Chart3DLighting.Default,
    explodeSelected: Boolean = true,
    explode: ((T) -> Boolean)? = null,
    explodeDistance: Dp = Dp.Unspecified,
    labelPosition: SliceLabelPosition = SliceLabelPosition.None,
    labelContent: SliceLabelContent = SliceLabelContent.LabelAndPercentage,
    legend: LegendPosition = LegendPosition.Bottom,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: Chart3DInteraction = Chart3DInteraction.Select,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    accessibilitySummary: (() -> String)? = null,
    debug: Chart3DDebug = Chart3DDebug.None,
    onDiagnostics: ((Chart3DDiagnostics) -> Unit)? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter, showSeriesNames = false)
    },
    seriesId: String = ChartDefaults.SINGLE_SERIES_ID,
    seriesName: String = "",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
    centerContent: (@Composable () -> Unit)? = null,
) {
    val theme = ChartKitTheme.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val entries = remember(data, label, color) {
        data.mapIndexed { index, item ->
            SliceSeriesEntry(
                label = label(item),
                item = item,
                paletteIndex = index,
                colorOverride = color?.invoke(item),
            )
        }
    }

    // The 2D slice engine, called exactly as PieChart calls it. Everything that
    // can be got wrong about a part-to-whole chart — normalisation, the total,
    // negative and NaN values, the gap, the direction — is decided here, once,
    // for both charts.
    val slices = remember(data, value, startAngle, sweepAngle, sliceGap, direction, valuePolicy) {
        computePolarSlices(
            values = data.map { value(it)?.toDouble() },
            startAngle = startAngle,
            totalSweep = sweepAngle,
            gapDegrees = sliceGap,
            direction = direction,
            policy = valuePolicy,
        )
    }
    val isEmpty = slices.none { it.sweepAngle > 0f }

    // ---- explode ---------------------------------------------------------

    val selectedIndex = state.selection?.takeIf { it.seriesId == seriesId }?.pointIndex ?: -1
    val exploded = remember(data, explode, explodeSelected, selectedIndex) {
        data.mapIndexed { index, item ->
            (explodeSelected && index == selectedIndex) || explode?.invoke(item) == true
        }
    }
    // One animation per slice, so two slices can be moving in opposite
    // directions at once — which is what a selection change looks like, and
    // what a single shared progress could not express.
    val explodeAnimations = remember { mutableStateMapOf<Int, Animatable<Float, *>>() }
    val animated = animation != ChartAnimation.None && !renderMode.isStatic
    LaunchedEffect(exploded, animated) {
        exploded.forEachIndexed { index, wanted ->
            val target = if (wanted) 1f else 0f
            val slice = explodeAnimations.getOrPut(index) { Animatable(target) }
            @Suppress("UNCHECKED_CAST")
            val progress = slice as Animatable<Float, *>
            if (progress.targetValue == target) return@forEachIndexed
            launch {
                if (animated) {
                    progress.animateTo(target, tween(durationMillis = EXPLODE_DURATION_MS))
                } else {
                    progress.snapTo(target)
                }
            }
        }
    }
    val explodePx = with(density) {
        (if (explodeDistance.isSpecified()) explodeDistance else theme.dimensions.chart3DExplodeOffset)
            .toPx()
    }
    val sliceCount = data.size

    // ---- the layer -------------------------------------------------------

    // Read in composition and captured as a lambda the layer calls at draw
    // time. Reading it in the layer's constructor would freeze the camera into
    // the geometry, and a rotation would re-tessellate every arc to move the
    // eye.
    val camera = cameraState.camera
    val labelPadding = with(density) { theme.dimensions.labelPadding.toPx() }
    val labelGutter = remember(
        labelPosition, labelContent, entries, slices, valueFormatter, textMeasurer, theme,
        labelPadding,
    ) {
        if (labelPosition == SliceLabelPosition.Inside ||
            labelPosition == SliceLabelPosition.None
        ) {
            0f
        } else {
            // Measured from the text that will actually be *drawn*, not from
            // the bare category name: "Chrome" and "Chrome 42.1%" need very
            // different gutters, and reserving the first drops the label for
            // the largest slice on the chart — silently, and only for the
            // labels that matter most.
            val widest = entries.withIndex().maxOfOrNull { (index, entry) ->
                val slice = slices.firstOrNull { it.sourceIndex == index }
                val text = when (labelContent) {
                    SliceLabelContent.Label -> entry.label
                    SliceLabelContent.Value -> valueFormatter.format(slice?.value ?: 0.0)
                    SliceLabelContent.Percentage ->
                        io.devkit.chartkit.layer.polar.percentage(slice?.fraction ?: 0.0)
                    SliceLabelContent.LabelAndPercentage ->
                        entry.label + " " +
                            io.devkit.chartkit.layer.polar.percentage(slice?.fraction ?: 0.0)
                }
                textMeasurer.measure(text, theme.typography.sliceLabel).size.width.toFloat()
            } ?: 0f
            // Plus the leader line and the gap either side of it.
            widest + labelPadding * LEADER_GUTTER_PADDINGS
        }
    }
    val padding = with(density) { theme.dimensions.chart3DPadding.toPx() }

    val layer = remember(
        slices, entries, seriesId, seriesName, direction, startAngle, innerRadiusRatio,
        depth, quality, camera, projection, lighting, labelPosition, labelContent,
        valueFormatter, labelGutter, padding, explodePx, sliceCount, debug, onDiagnostics,
    ) {
        Radial3DLayer(
            id = "radial3d",
            slices = slices,
            entries = entries,
            seriesId = seriesId,
            seriesName = seriesName,
            direction = direction,
            chartStartAngle = startAngle,
            innerRadiusRatio = innerRadiusRatio.toDouble(),
            depth = depth,
            quality = quality,
            cameraProvider = { camera },
            projection = projection,
            lighting = lighting,
            explodeProvider = {
                FloatArray(sliceCount) { index -> explodeAnimations[index]?.value ?: 0f }
            },
            explodeDistancePx = explodePx,
            labelPosition = labelPosition,
            labelContent = labelContent,
            valueFormatter = valueFormatter,
            labelGutter = labelGutter,
            padding = padding,
            onDiagnostics = onDiagnostics,
            debug = debug,
        )
    }

    val rotateModifier = if (interaction.rotates && !renderMode.isStatic) {
        Modifier.pointerInput(cameraState) {
            detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
                // The disc follows the finger, as though the reader had hold of
                // the front of it: dragging down tips the far side up and opens
                // the ellipse, dragging up closes it toward edge-on.
                if (pan.x != 0f || pan.y != 0f) {
                    cameraState.rotateBy(
                        deltaX = pan.y * RADIAL_ROTATION_SENSITIVITY,
                        deltaY = -pan.x * RADIAL_ROTATION_SENSITIVITY,
                    )
                }
                if (zoom != 1f && zoom > 0f) cameraState.zoomBy(zoom)
            }
        }
    } else {
        Modifier
    }

    PolarChartCore(
        layers = { listOf(layer) },
        modifier = modifier,
        innerRadiusRatio = innerRadiusRatio,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        direction = direction,
        legend = legend,
        legendItems = remember(entries, seriesId) {
            entries.mapIndexed { index, entry ->
                ChartKeyItem(
                    id = "$seriesId-$index",
                    label = entry.label,
                    paletteIndex = entry.paletteIndex,
                    colorOverride = entry.colorOverride,
                )
            }
        },
        animation = animation,
        tapSelects = interaction.selects,
        clearOnTapOutside = true,
        state = state.asErased(),
        valueFormatter = valueFormatter,
        onSelectionChanged = onSelectionChanged?.let { callback ->
            { erased -> callback(erased?.asTyped()) }
        },
        tooltip = tooltip?.let { slot -> { erased -> slot(erased.asTypedTooltip()) } },
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        isEmpty = isEmpty,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        renderMode = renderMode,
        staticOptions = staticOptions,
        centerContent = centerContent,
        plotModifier = rotateModifier,
        // The hole is neither centred nor circular once a camera has tilted it,
        // so the layer says where it ended up rather than the core assuming.
        centerContentBounds = { coordinates ->
            layer.centerBounds(coordinates.plotArea, coordinates.outerRadius.toDouble())
        },
    )
}

/**
 * A 3D donut chart: a [PieChart3D] with a hole, an inner wall, and optionally
 * something in the middle.
 *
 * ```kotlin
 * DonutChart3D(
 *     data = shares,
 *     value = { it.users },
 *     label = { it.browser },
 *     innerRadiusRatio = 0.55f,
 *     centerContent = {
 *         Column(horizontalAlignment = Alignment.CenterHorizontally) {
 *             Text("Total", style = MaterialTheme.typography.labelMedium)
 *             Text("11,456", style = MaterialTheme.typography.headlineSmall)
 *         }
 *     },
 * )
 * ```
 *
 * Not a separate renderer, and not even a separate shape: it calls [PieChart3D]
 * with a non-zero [innerRadiusRatio], and the extra inner wall falls out of
 * [io.devkit.chartkit.three.RadialSector3D] having one when its inner radius is
 * not zero. The same relationship [DonutChart] has to [PieChart].
 *
 * ### The centre content stays upright
 *
 * [centerContent] is ordinary Compose content, laid out inside the projected
 * hole and **never** tilted, skewed or projected onto the ring's plane. A total
 * drawn in perspective is a total that is harder to read, and the reason to put
 * one in a donut is that it is easy to read. What the camera changes is where
 * the box goes and how big it is; see
 * [io.devkit.chartkit.layer.three.Radial3DLayer.centerBounds].
 *
 * It takes no pointer input, so the hole stays inert and no slice loses a tap.
 *
 * @param innerRadiusRatio the hole, as a fraction of the outer radius — the
 *   same units [DonutChart] uses, so a 2D and a 3D donut configured alike have
 *   the same hole.
 */
@Suppress("LongParameterList")
@Composable
fun <T> DonutChart3D(
    data: List<T>,
    value: (T) -> Number?,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    innerRadiusRatio: Float = DEFAULT_DONUT_INNER_RATIO,
    startAngle: Float = 0f,
    sweepAngle: Float = PolarGeometry.FULL_CIRCLE,
    direction: PolarDirection = PolarDirection.Clockwise,
    sliceGap: Float = 0f,
    valuePolicy: PolarValuePolicy = PolarValuePolicy.Ignore,
    color: ((T) -> Int?)? = null,
    depth: Chart3DDepth = Chart3DDepth.Auto,
    quality: Chart3DQuality = Chart3DQuality.Auto,
    cameraState: Chart3DCameraState = rememberChart3DCameraState(
        camera = Chart3DCamera.Radial,
        limits = Chart3DCameraLimits.Radial,
    ),
    projection: Chart3DProjection = Chart3DProjection.Default,
    lighting: Chart3DLighting = Chart3DLighting.Default,
    explodeSelected: Boolean = true,
    explode: ((T) -> Boolean)? = null,
    explodeDistance: Dp = Dp.Unspecified,
    labelPosition: SliceLabelPosition = SliceLabelPosition.None,
    labelContent: SliceLabelContent = SliceLabelContent.LabelAndPercentage,
    legend: LegendPosition = LegendPosition.Bottom,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: Chart3DInteraction = Chart3DInteraction.Select,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    accessibilitySummary: (() -> String)? = null,
    debug: Chart3DDebug = Chart3DDebug.None,
    onDiagnostics: ((Chart3DDiagnostics) -> Unit)? = null,
    state: ChartState<T> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<T>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<T>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter, showSeriesNames = false)
    },
    seriesId: String = ChartDefaults.SINGLE_SERIES_ID,
    seriesName: String = "",
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
    centerContent: (@Composable () -> Unit)? = null,
) {
    PieChart3D(
        data = data,
        value = value,
        label = label,
        modifier = modifier,
        innerRadiusRatio = innerRadiusRatio,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        direction = direction,
        sliceGap = sliceGap,
        valuePolicy = valuePolicy,
        color = color,
        depth = depth,
        quality = quality,
        cameraState = cameraState,
        projection = projection,
        lighting = lighting,
        explodeSelected = explodeSelected,
        explode = explode,
        explodeDistance = explodeDistance,
        labelPosition = labelPosition,
        labelContent = labelContent,
        legend = legend,
        valueFormatter = valueFormatter,
        animation = animation,
        interaction = interaction,
        accessibility = accessibility,
        renderMode = renderMode,
        staticOptions = staticOptions,
        accessibilitySummary = accessibilitySummary,
        debug = debug,
        onDiagnostics = onDiagnostics,
        state = state,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        seriesId = seriesId,
        seriesName = seriesName,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        centerContent = centerContent,
    )
}

private fun Dp.isSpecified(): Boolean = this != Dp.Unspecified

/**
 * Degrees of camera rotation per pixel of drag.
 *
 * Gentler than the column chart's. A pie is a smaller target and its pitch does
 * more to the picture per degree — the ellipse closes fast — so the same
 * sensitivity would make it feel twitchy.
 */
private const val RADIAL_ROTATION_SENSITIVITY = 0.22

/** How long a slice takes to slide out or back. */
private const val EXPLODE_DURATION_MS = 260

/** Label paddings held back beyond the widest label, for the leader line and its gaps. */
private const val LEADER_GUTTER_PADDINGS = 4f
