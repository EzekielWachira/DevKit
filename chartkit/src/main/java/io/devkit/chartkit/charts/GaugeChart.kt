package io.devkit.chartkit.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.layer.polar.GaugeBand
import io.devkit.chartkit.layer.polar.GaugeIndicator
import io.devkit.chartkit.layer.polar.GaugeLayer
import io.devkit.chartkit.layer.custom.CustomPolarLayer
import io.devkit.chartkit.layer.custom.CustomPolarLayerRenderer
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * How much of a circle a gauge sweeps, and where it starts.
 *
 * Named shapes rather than two loose angles, because the combinations that read
 * as a gauge are few and the ones that do not are many: an arc starting at four
 * o'clock and sweeping two hundred degrees is not a dial anyone has seen.
 * [Custom] is there for the cases that genuinely need it.
 */
sealed interface GaugeShape {

    /** Where the arc begins, in ChartKit's convention: zero at twelve o'clock. */
    val startAngle: Float

    /** How far it sweeps, clockwise. */
    val sweepAngle: Float

    /** A half circle opening downward: nine o'clock through twelve to three. */
    data object SemiCircle : GaugeShape {
        override val startAngle: Float get() = 270f
        override val sweepAngle: Float get() = 180f
    }

    /** Three quarters, with the gap at the bottom. The classic dial. */
    data object ThreeQuarter : GaugeShape {
        override val startAngle: Float get() = 225f
        override val sweepAngle: Float get() = 270f
    }

    /** A complete ring, starting at twelve o'clock. */
    data object FullCircle : GaugeShape {
        override val startAngle: Float get() = 0f
        override val sweepAngle: Float get() = PolarGeometry.FULL_CIRCLE
    }

    /** Any other arc. */
    data class Custom(
        override val startAngle: Float,
        override val sweepAngle: Float,
    ) : GaugeShape {
        init {
            require(sweepAngle > 0f && sweepAngle <= PolarGeometry.FULL_CIRCLE) {
                "A gauge sweep must be in (0, 360], was $sweepAngle"
            }
        }
    }
}

/**
 * One value against a range.
 *
 * ```kotlin
 * GaugeChart(
 *     value = 72.0,
 *     min = 0.0,
 *     max = 100.0,
 *     bands = listOf(
 *         GaugeBand(0.0, 50.0, "Below target"),
 *         GaugeBand(50.0, 75.0, "On target"),
 *         GaugeBand(75.0, 100.0, "Ahead"),
 *     ),
 *     centerContent = { Text("72", style = MaterialTheme.typography.headlineMedium) },
 * )
 * ```
 *
 * ```text
 *      ╭───────────╮
 *    ╭─╯ ▓▓▓▓▓▓░░░ ╰─╮
 *   │        72       │
 * ```
 *
 * ### Not a second polar engine
 *
 * Built on [PolarChartCore], the same one the pie, donut, radial bar and
 * sunburst use. A gauge is an arc with a track behind it; the coordinate
 * system, the centre-content slot, the tooltip, the selection state and the
 * accessibility layer are shared.
 *
 * ### Bands are intervals, not severities
 *
 * ChartKit does not know whether high is good. See [GaugeBand].
 *
 * @param value the reading. A value outside `[min, max]` draws at the end of
 *   the arc but is **announced and shown as itself** — a gauge that renamed
 *   130% as 100% would hide the reading most worth seeing.
 * @param centerContent Compose content laid out inside the arc. Where the
 *   number itself belongs; drawing it onto the canvas would fix its size and
 *   lose it to a screen reader.
 */
@Suppress("LongParameterList")
@Composable
fun GaugeChart(
    value: Double,
    modifier: Modifier = Modifier,
    min: Double = 0.0,
    max: Double = 100.0,
    bands: List<GaugeBand> = emptyList(),
    shape: GaugeShape = GaugeShape.ThreeQuarter,
    indicator: GaugeIndicator = GaugeIndicator.Arc,
    label: String = "",
    thickness: androidx.compose.ui.unit.Dp? = null,
    direction: PolarDirection = PolarDirection.Clockwise,
    legend: LegendPosition = LegendPosition.None,
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    animation: ChartAnimation = ChartAnimation.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    customLayers: List<CustomPolarLayer> = emptyList(),
    state: ChartState<Any?> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<Any?>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<Any?>) -> Unit)? = null,
    seriesId: String = ChartDefaults.SINGLE_SERIES_ID,
    seriesName: String = "",
    item: Any? = null,
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
    centerContent: (@Composable () -> Unit)? = null,
) {
    require(max > min) { "A gauge needs max > min, was [$min, $max]" }
    val density = LocalDensity.current
    val theme = ChartKitTheme.current

    // The ring's thickness is a dimension, but `PolarCoordinates` takes an
    // inner *ratio* — so it is converted once here, against the radius the
    // engine will actually use, rather than guessed at as a fraction.
    val ringThickness = with(density) { (thickness ?: theme.dimensions.gaugeThickness).toPx() }

    PolarChartCore(
        layers = { polar ->
            listOf(
                GaugeLayer(
                    id = "gauge",
                    value = value,
                    minimum = min,
                    maximum = max,
                    bands = bands,
                    indicator = indicator,
                    seriesId = seriesId,
                    seriesName = seriesName.ifBlank { label },
                    label = label,
                    item = item,
                    valueFormatter = valueFormatter,
                ),
            ) + customLayers.map(::CustomPolarLayerRenderer)
        },
        modifier = modifier,
        innerRadiusRatio = 0f,
        startAngle = shape.startAngle,
        sweepAngle = shape.sweepAngle,
        direction = direction,
        legend = legend,
        legendItems = remember(bands) {
            bands.mapIndexedNotNull { index, band ->
                val name = band.label ?: return@mapIndexedNotNull null
                ChartKeyItem(
                    id = "$seriesId-band-$index",
                    label = name,
                    paletteIndex = index,
                    colorOverride = band.color,
                )
            }
        },
        animation = animation,
        tapSelects = tooltip != null || onSelectionChanged != null,
        clearOnTapOutside = true,
        state = state,
        valueFormatter = valueFormatter,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        isEmpty = !value.isFinite(),
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        centerContent = centerContent,
        renderMode = renderMode,
        staticOptions = staticOptions,
        // The arc is drawn as a stroke of this width centred on the ring, so
        // the ring itself is `thickness` wide and the hole is everything
        // inside it — which is what leaves room for the centre content.
        ringThickness = ringThickness,
    )
}
