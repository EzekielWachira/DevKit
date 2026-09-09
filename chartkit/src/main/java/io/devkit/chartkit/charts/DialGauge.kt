package io.devkit.chartkit.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.gauge.GaugeBandOverflow
import io.devkit.chartkit.gauge.GaugeBandOverlap
import io.devkit.chartkit.gauge.GaugeBandResolution
import io.devkit.chartkit.gauge.GaugeBandStyle
import io.devkit.chartkit.gauge.GaugeDetail
import io.devkit.chartkit.gauge.GaugeInteraction
import io.devkit.chartkit.gauge.GaugeMarker
import io.devkit.chartkit.gauge.GaugeNeedleStyle
import io.devkit.chartkit.gauge.GaugeOverflow
import io.devkit.chartkit.gauge.GaugePane
import io.devkit.chartkit.gauge.GaugePivotStyle
import io.devkit.chartkit.gauge.GaugeScale
import io.devkit.chartkit.gauge.GaugeTickConfig
import io.devkit.chartkit.gauge.GaugeTickPlacement
import io.devkit.chartkit.gauge.GaugeTickPlan
import io.devkit.chartkit.gauge.GaugeValue
import io.devkit.chartkit.gauge.GaugeValuePosition
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.layer.custom.CustomPolarLayer
import io.devkit.chartkit.layer.custom.CustomPolarLayerRenderer
import io.devkit.chartkit.layer.polar.GaugeBand
import io.devkit.chartkit.layer.polar.GaugeDialLayer
import io.devkit.chartkit.layer.polar.GaugeDialPlan
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * What a dial currently reads, for a caller who wants to act on it.
 *
 * Published so an application does not have to repeat the threshold lookup the
 * gauge already did — a status chip beside a dial that computed its own bands
 * is one refactor away from disagreeing with the arc it sits next to.
 *
 * @param bandLabel the caller's own words for the band the value falls in, or
 *   `null` when it falls in none. ChartKit never invents one: a band from 160
 *   to 200 is a redline on one gauge and a target on the next, and only the
 *   application knows which.
 */
class GaugeReading internal constructor(
    val value: Double,
    val bandLabel: String?,
    val bandIndex: Int?,
    /** True when [value] lies outside the gauge's own range. */
    val isOutOfRange: Boolean,
)

/**
 * A dial: an angular scale, threshold bands, tick marks, numbers and a needle.
 *
 * ```kotlin
 * DialGauge(
 *     value = speed,
 *     min = 0.0,
 *     max = 200.0,
 *     shape = GaugeShape.SemiCircle,
 *     unit = "km/h",
 *     bands = listOf(
 *         GaugeBand(0.0, 120.0, "Normal"),
 *         GaugeBand(120.0, 160.0, "Caution"),
 *         GaugeBand(160.0, 200.0, "Over limit"),
 *     ),
 *     modifier = Modifier.size(320.dp),
 * )
 * ```
 *
 * ```text
 *        60    80   100  120
 *     40 ╲  ╲   │   ╱  ╱ 140
 *   20 ─  ╲  ╲  │  ╱  ╱  ─ 160
 *  0 ──     ╲   │   ╱     ── 180
 *              ╲│╱
 *               ●        80 km/h
 * ```
 *
 * ### Not a second polar engine
 *
 * Built on [PolarChartCore] — the same one the pie, donut, radial bar and
 * sunburst use. The coordinate system, the animation clock, the theme, the
 * legend, the tooltip, the selection state and the accessibility layer are all
 * shared. What a dial adds is [GaugeScale], which maps a number onto an angle,
 * and the marks that read against it.
 *
 * ### Distinct from [GaugeChart]
 *
 * [GaugeChart] draws a value as a filled arc: a KPI ring, readable in a small
 * dashboard tile, with no scale to read against. This draws an instrument. They
 * share the scale, the bands and the polar geometry; their rendering stays
 * separate, because an arc gauge with tick marks is neither one thing nor the
 * other.
 *
 * ### The needle is clamped; the reading is not
 *
 * A value past `max` puts the needle at the end of the arc — a dial has a
 * physical end — but the announcement, the tooltip and [onReadingChanged]
 * report the **real** number. A gauge that renamed 250 km/h as 200 would hide
 * exactly the reading its owner most needs.
 *
 * @param unit what the numbers are in. Written after the value and spelled out
 *   for a screen reader; never appended to the tick labels, which would repeat
 *   it a dozen times around the arc.
 * @param label what the dial measures. Announced first, so a reader hears
 *   "Speed" before they hear a number.
 * @param interaction whether the dial is a display or a control. A display by
 *   default — a speedometer is not a knob.
 * @param onValueChange called when an interactive dial is tapped or dragged.
 *   Required for [GaugeInteraction.Tap] and [GaugeInteraction.Drag] to do
 *   anything: the gauge does not own the value.
 * @param step the increment an adjustable dial moves by, for the screen
 *   reader's increase and decrease actions. `null` uses a hundredth of the
 *   range.
 * @param valueContent the readout inside the dial. Receives the **animated**
 *   value, so the number and the needle arrive together — a label reading 80
 *   beside a needle already at 140 is worse than no label.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun DialGauge(
    value: Double,
    modifier: Modifier = Modifier,
    min: Double = 0.0,
    max: Double = 100.0,
    shape: GaugeShape = GaugeShape.ThreeQuarter,
    label: String = "",
    unit: String = "",
    bands: List<GaugeBand> = emptyList(),
    bandStyles: Map<Int, GaugeBandStyle> = emptyMap(),
    bandOverflow: GaugeBandOverflow = GaugeBandOverflow.Clamp,
    bandOverlap: GaugeBandOverlap = GaugeBandOverlap.LastDrawnWins,
    markers: List<GaugeMarker> = emptyList(),
    ticks: GaugeTickConfig = GaugeTickConfig.Default,
    needle: GaugeNeedleStyle = GaugeNeedleStyle.Default,
    pivot: GaugePivotStyle = GaugePivotStyle.Default,
    pane: GaugePane = GaugePane.None,
    showTrack: Boolean = true,
    trackThickness: Dp? = null,
    detail: GaugeDetail = GaugeDetail.Auto,
    overflow: GaugeOverflow = GaugeOverflow.Clamp,
    direction: PolarDirection = PolarDirection.Clockwise,
    valuePosition: GaugeValuePosition = GaugeValuePosition.Center,
    labelFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    valueFormatter: ChartValueFormatter = labelFormatter,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: GaugeInteraction = GaugeInteraction.None,
    step: Double? = null,
    onValueChange: ((Double) -> Unit)? = null,
    onReadingChanged: ((GaugeReading) -> Unit)? = null,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    customLayers: List<CustomPolarLayer> = emptyList(),
    state: ChartState<Any?> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<Any?>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<Any?>) -> Unit)? = null,
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
    valueContent: (@Composable (Double) -> Unit)? = null,
) {
    DialGauge(
        series = remember(value, label) {
            listOf(GaugeValue(id = PRIMARY_NEEDLE, value = value, label = label))
        },
        modifier = modifier,
        min = min,
        max = max,
        shape = shape,
        label = label,
        unit = unit,
        bands = bands,
        bandStyles = bandStyles,
        bandOverflow = bandOverflow,
        bandOverlap = bandOverlap,
        markers = markers,
        ticks = ticks,
        needle = needle,
        pivot = pivot,
        pane = pane,
        showTrack = showTrack,
        trackThickness = trackThickness,
        detail = detail,
        overflow = overflow,
        direction = direction,
        valuePosition = valuePosition,
        labelFormatter = labelFormatter,
        valueFormatter = valueFormatter,
        animation = animation,
        interaction = interaction,
        step = step,
        onValueChange = onValueChange,
        onReadingChanged = onReadingChanged,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        renderMode = renderMode,
        staticOptions = staticOptions,
        customLayers = customLayers,
        legend = LegendPosition.None,
        state = state,
        onSelectionChanged = onSelectionChanged,
        tooltip = tooltip,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        valueContent = valueContent,
    )
}

/**
 * A dial carrying several needles.
 *
 * ```kotlin
 * DialGauge(
 *     series = listOf(
 *         GaugeValue("current", speed, "Current speed"),
 *         GaugeValue("target", 100.0, "Target", style = GaugeNeedleStyle.Target),
 *     ),
 *     min = 0.0,
 *     max = 200.0,
 *     unit = "km/h",
 *     legend = LegendPosition.Bottom,
 * )
 * ```
 *
 * Needles are identified by [GaugeValue.id] and never by list position: adding
 * a target needle above the current one would otherwise animate the current
 * needle to the target's value and back, which looks precisely like a data
 * error. The **first** needle is the dial's primary reading — the one an
 * interactive gauge adjusts and the one a bare selection reports.
 *
 * @param legend a key for the needles. Off by default: one needle needs no
 *   legend, and a legend for it is a row of furniture saying what the dial
 *   already says.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun DialGauge(
    series: List<GaugeValue>,
    modifier: Modifier = Modifier,
    min: Double = 0.0,
    max: Double = 100.0,
    shape: GaugeShape = GaugeShape.ThreeQuarter,
    label: String = "",
    unit: String = "",
    bands: List<GaugeBand> = emptyList(),
    bandStyles: Map<Int, GaugeBandStyle> = emptyMap(),
    bandOverflow: GaugeBandOverflow = GaugeBandOverflow.Clamp,
    bandOverlap: GaugeBandOverlap = GaugeBandOverlap.LastDrawnWins,
    markers: List<GaugeMarker> = emptyList(),
    ticks: GaugeTickConfig = GaugeTickConfig.Default,
    needle: GaugeNeedleStyle = GaugeNeedleStyle.Default,
    pivot: GaugePivotStyle = GaugePivotStyle.Default,
    pane: GaugePane = GaugePane.None,
    showTrack: Boolean = true,
    trackThickness: Dp? = null,
    detail: GaugeDetail = GaugeDetail.Auto,
    overflow: GaugeOverflow = GaugeOverflow.Clamp,
    direction: PolarDirection = PolarDirection.Clockwise,
    valuePosition: GaugeValuePosition = GaugeValuePosition.Center,
    labelFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    valueFormatter: ChartValueFormatter = labelFormatter,
    animation: ChartAnimation = ChartAnimation.Default,
    interaction: GaugeInteraction = GaugeInteraction.None,
    step: Double? = null,
    onValueChange: ((Double) -> Unit)? = null,
    onReadingChanged: ((GaugeReading) -> Unit)? = null,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    customLayers: List<CustomPolarLayer> = emptyList(),
    legend: LegendPosition = LegendPosition.None,
    state: ChartState<Any?> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<Any?>?) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<Any?>) -> Unit)? = null,
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent() },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
    valueContent: (@Composable (Double) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val theme = ChartKitTheme.current

    // The scale: the one place a value becomes an angle. Everything the dial
    // draws — ticks, bands, needles, markers, hit tests — asks it, so none of
    // them can disagree at the ends of the arc.
    val scale = remember(min, max, shape, direction, overflow) {
        GaugeScale(
            min = min,
            max = max,
            startAngle = shape.startAngle,
            sweepAngle = shape.sweepAngle,
            direction = direction,
            overflow = overflow,
        )
    }

    val labelGap = with(density) { theme.dimensions.gaugeLabelGap.toPx() }
    val labelStyle = theme.typography.gaugeLabel
    // Room reserved outside the arc for the numbers, before the radius is
    // chosen — the same rule the Cartesian axes follow. Measured from the
    // widest label the dial will actually draw rather than guessed at, so
    // `1,000` gets the room it needs and `0..5` does not waste it.
    val labelReserve = remember(scale, ticks, labelFormatter, labelStyle, density, theme) {
        val widest = GaugeTickPlan.of(scale, ticks).labelled
            .maxOfOrNull { labelFormatter.format(it).length } ?: 0
        // Plus whatever the ticks themselves reach outward, since the labels
        // are pushed clear of those too.
        val tickReach = with(density) {
            when (ticks.placement) {
                GaugeTickPlacement.Outside -> theme.dimensions.gaugeMajorTickLength.toPx()
                GaugeTickPlacement.Cross -> theme.dimensions.gaugeMajorTickLength.toPx() / 2f
                GaugeTickPlacement.Inside -> 0f
            }
        }
        with(density) { labelStyle.fontSize.toPx() * widest * LABEL_WIDTH_FACTOR } + labelGap + tickReach
    }

    val trackPx = trackThickness?.let { with(density) { it.toPx() } }
        ?: with(density) { theme.dimensions.gaugeThickness.toPx() }
    val compactBelow = with(density) { theme.dimensions.gaugeCompactRadius.toPx() }

    // Each needle animated independently, from wherever it currently is.
    // `animateFloatAsState` retargets rather than restarting, which is what
    // makes 80 → 120 → 160 in quick succession one continuous sweep instead of
    // a jump back to 80. Keyed by id, so inserting a needle does not make its
    // neighbours travel.
    val animatedValues = series.associate { entry ->
        // Animated as a *fraction*, not as a value: on a gauge whose range does
        // not start at zero the two differ, and interpolating the fraction is
        // what keeps the needle's angular speed even.
        val target = scale.fractionOf(entry.value).toFloat()
        val animated by animateFloatAsState(
            targetValue = target,
            animationSpec = animation.dataChangeSpec(),
            label = "ChartKit gauge ${entry.id}",
        )
        val fraction = if (animation.enabled) animated else target
        entry.id to (scale.min + scale.span * fraction.toDouble())
    }

    val primary = series.firstOrNull()
    val animatedPrimary = primary?.let { animatedValues[it.id] } ?: min

    val resolution = remember(bands, scale, bandOverflow, bandOverlap, bandStyles) {
        GaugeBandResolution.of(bands, scale, bandOverflow, bandOverlap, bandStyles)
    }

    // Published so a status chip beside the dial does not repeat the lookup.
    val reading = remember(primary?.value, resolution) {
        val current = primary?.value ?: min
        val band = resolution.bandAt(current)
        GaugeReading(
            value = current,
            bandLabel = band?.label,
            bandIndex = band?.sourceIndex,
            isOutOfRange = current !in scale,
        )
    }
    // Keyed on what the reading *is*, not on the object: GaugeReading has no
    // equals, so keying on it would fire the callback on every recomposition.
    androidx.compose.runtime.LaunchedEffect(
        reading.value, reading.bandIndex, reading.isOutOfRange, onReadingChanged,
    ) {
        onReadingChanged?.invoke(reading)
    }

    val effectiveStep = step ?: (scale.span / DEFAULT_STEPS)

    // The dial's own geometry, computed here as well as in the engine so the
    // value readout can be placed against the *pivot* rather than against the
    // composable's box. `GaugeGeometry.fit` is a pure function of the same
    // inputs, so the two cannot disagree — and on a full-circle dial the
    // difference is the number sitting under the needle instead of below it.
    var plotSize by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero)
    }

    Box(modifier.onSizeChanged { plotSize = it }) {
        PolarChartCore(
            layers = { polar ->
                // The static half of the dial, rebuilt only when the things
                // that shape it change — not when the value does. A gauge on a
                // live dashboard therefore pays for a needle per frame, not for
                // a tick generation and a band resolution.
                val plan = GaugeDialPlan(
                    scale = scale,
                    bands = resolution,
                    ticks = GaugeTickPlan.of(
                        scale = scale,
                        config = if (detail.isCompact(polar.outerRadius, compactBelow)) {
                            ticks.copy(minorCount = 0)
                        } else {
                            ticks
                        },
                        radius = polar.outerRadius,
                    ),
                    labelText = GaugeTickPlan.of(scale, ticks, polar.outerRadius).labelled
                        .associateWith { labelFormatter.format(it) },
                    markers = markers,
                    compact = detail.isCompact(polar.outerRadius, compactBelow),
                )
                listOf(
                    GaugeDialLayer(
                        id = "dial",
                        plan = plan,
                        values = series,
                        animatedValues = animatedValues,
                        pane = pane,
                        pivot = pivot,
                        defaultNeedle = needle,
                        tickPlacement = ticks.placement,
                        showTrack = showTrack,
                        trackThickness = trackPx,
                        seriesName = label,
                        valueFormatter = valueFormatter,
                        unit = unit,
                    ),
                ) + customLayers.map(::CustomPolarLayerRenderer)
            },
            modifier = Modifier.fillMaxSize(),
            innerRadiusRatio = 0f,
            startAngle = shape.startAngle,
            sweepAngle = shape.sweepAngle,
            direction = direction,
            legend = legend,
            legendItems = remember(series) {
                if (series.size <= 1) {
                    emptyList()
                } else {
                    series.mapIndexed { index, entry ->
                        ChartKeyItem(
                            id = entry.id,
                            label = entry.label,
                            paletteIndex = index,
                            colorOverride = entry.style?.color,
                        )
                    }
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
            accessibilitySummary = accessibilitySummary ?: {
                dialSummary(label, series, scale, resolution, valueFormatter, unit)
            },
            isEmpty = series.isEmpty() || series.none { it.value.isFinite() },
            isLoading = isLoading,
            error = error,
            loadingContent = loadingContent,
            emptyContent = emptyContent,
            errorContent = errorContent,
            centerContent = null,
            renderMode = renderMode,
            staticOptions = staticOptions,
            ringThickness = trackPx,
            radiusInset = labelReserve,
            // A dial's arc is rarely the whole circle, and the space its
            // missing part would have taken belongs to the dial.
            fitToSweep = true,
            onAngleAt = if (interaction.isInteractive && onValueChange != null) {
                { angle ->
                    // `valueAtOrNull` rather than `valueAt`: a tap below a
                    // semicircular dial is not a reading, and wrapping it into
                    // the nearest end is how every naive `atan2` gauge sets
                    // itself to maximum when a finger strays.
                    scale.valueAtOrNull(angle, tolerance = ANGLE_TOLERANCE)
                        ?.let { onValueChange(if (step != null) scale.snap(it, step) else it) }
                }
            } else {
                null
            },
            dragAngles = interaction.allowsDrag,
            semantics = if (interaction.isInteractive && onValueChange != null && primary != null) {
                {
                    // Adjustable, not merely described: a dial that can only be
                    // dragged is unusable with a screen reader, and range info
                    // plus a set action is what gives it increase and decrease.
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = primary.value.toFloat().coerceIn(min.toFloat(), max.toFloat()),
                        range = min.toFloat()..max.toFloat(),
                        steps = (scale.span / effectiveStep).toInt().coerceIn(0, MAX_A11Y_STEPS),
                    )
                    setProgress { target ->
                        onValueChange(scale.snap(target.toDouble(), effectiveStep))
                        true
                    }
                }
            } else {
                null
            },
            plotModifier = if (interaction.isInteractive && onValueChange != null && primary != null) {
                Modifier.gaugeKeyboard(
                    scale = scale,
                    current = primary.value,
                    step = effectiveStep,
                    onValueChange = onValueChange,
                )
            } else {
                Modifier
            },
        )

        if (valueContent != null && plotSize != androidx.compose.ui.unit.IntSize.Zero) {
            val padding = with(density) { theme.dimensions.polarPadding.toPx() }
            val fit = io.devkit.chartkit.gauge.GaugeGeometry.fit(
                bounds = io.devkit.chartkit.geometry.ChartRect(
                    padding, padding,
                    plotSize.width - padding, plotSize.height - padding,
                ),
                startAngle = shape.startAngle,
                sweepAngle = shape.sweepAngle,
                reserve = labelReserve,
            )
            // Below the pivot by a fraction of the radius for BelowCenter, on it
            // for Center. A needle dial almost always wants the former: the
            // centre of the face is exactly where the pivot and every needle's
            // base already are.
            val dy = when (valuePosition) {
                GaugeValuePosition.BelowCenter -> fit.radius * VALUE_BELOW_PIVOT
                GaugeValuePosition.Center -> 0f
            }
            // Centred in the box, then displaced from the box's centre to the
            // pivot — so the content stays centred on the point whatever size
            // it turns out to be.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.offset {
                        androidx.compose.ui.unit.IntOffset(
                            (fit.center.x - plotSize.width / 2f).toInt(),
                            (fit.center.y + dy - plotSize.height / 2f).toInt(),
                        )
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    valueContent(animatedPrimary)
                }
            }
        }
    }
}

/**
 * The dial in words.
 *
 * ### Never more meaning than the caller supplied
 *
 * The band is named only when the application named it. A gauge that announced
 * "Danger" because a band was red would be inventing a reading out of a
 * colour — which is exactly the inference a screen reader user cannot check.
 */
internal fun dialSummary(
    label: String,
    series: List<GaugeValue>,
    scale: GaugeScale,
    bands: GaugeBandResolution,
    formatter: ChartValueFormatter,
    unit: String,
): String = buildString {
    val unitSuffix = if (unit.isBlank()) "" else " $unit"
    if (label.isNotBlank()) {
        append(label)
        append(". ")
    }
    series.forEach { entry ->
        if (entry.label.isNotBlank()) {
            append(entry.label)
            append(": ")
        }
        append(formatter.format(entry.value))
        append(unitSuffix)
        append(". ")
        if (entry.value !in scale) append("Outside the gauge's range. ")
    }
    append("Range: ")
    append(formatter.format(scale.min))
    append(" to ")
    append(formatter.format(scale.max))
    append(unitSuffix)
    append(".")
    series.firstOrNull()?.let { primary ->
        bands.bandAt(primary.value)?.label?.takeIf { it.isNotBlank() }?.let {
            append(" Current range: ")
            append(it)
            append(".")
        }
    }
}

/** The id the single-value overload gives its one needle. */
private const val PRIMARY_NEEDLE = "value"

/** How many increments a screen reader's adjust action moves by, by default. */
private const val DEFAULT_STEPS = 100.0

/** Beyond this, a stepped range info is noise rather than navigation. */
private const val MAX_A11Y_STEPS = 100

/** How far below the pivot a [GaugeValuePosition.BelowCenter] readout sits. */
private const val VALUE_BELOW_PIVOT = 0.34f

/** Degrees past each end of the arc that still read as that end. */
private const val ANGLE_TOLERANCE = 12f

/**
 * The share of a font's size one digit occupies.
 *
 * Used to reserve room for the widest tick label before the radius is chosen.
 * An approximation on purpose: measuring every label needs a `TextMeasurer`,
 * which needs a composition, which is where the radius is being decided. The
 * factor is generous, and the reserve is a floor rather than a promise.
 */
private const val LABEL_WIDTH_FACTOR = 0.62f
