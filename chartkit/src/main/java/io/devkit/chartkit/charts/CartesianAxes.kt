package io.devkit.chartkit.charts

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import io.devkit.chartkit.axis.AxisAlignment
import io.devkit.chartkit.axis.AxisDiagnostic
import io.devkit.chartkit.axis.AxisDimension
import io.devkit.chartkit.axis.AxisGridMode
import io.devkit.chartkit.axis.AxisPosition
import io.devkit.chartkit.axis.AxisRegistry
import io.devkit.chartkit.axis.AxisStyleMode
import io.devkit.chartkit.axis.AxisTickAlignment
import io.devkit.chartkit.axis.AxisVisibility
import io.devkit.chartkit.axis.AlignmentRequest
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartAxisId
import io.devkit.chartkit.axis.ChartAxisSpec
import io.devkit.chartkit.axis.ChartUnit
import io.devkit.chartkit.axis.MeasuredAxis
import io.devkit.chartkit.axis.MeasuredAxisLabel
import io.devkit.chartkit.axis.selectLabelIndices
import io.devkit.chartkit.coordinate.CartesianCoordinates
import io.devkit.chartkit.coordinate.DomainAxis
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.layout.AxisMetrics
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.scale.ScaleTransform
import io.devkit.chartkit.scale.apply
import io.devkit.chartkit.theme.ChartDimensions
import io.devkit.chartkit.theme.ChartTypography
import java.util.Locale

/**
 * One value axis, resolved as far as it can be before the plot area is known.
 *
 * ### Why the two phases
 *
 * An axis' gutter depends on how wide its widest tick label is, and the plot
 * area depends on every axis' gutter. So domains, ticks and label *text* are
 * resolved first, then measured, then the layout engine is told what each axis
 * needs, and only then — once there is a plot — do the scales and tick
 * *positions* exist. Doing it in one pass would mean guessing a gutter and
 * being wrong for any axis whose labels are not four characters wide.
 *
 * @param formatter the axis' own number formatter, used for its ticks, its
 *   crosshair readout and its rows of the tooltip. Per axis and not per chart:
 *   `1,018 hPa` and `14.2 °C` are two formats, and one chart-wide formatter
 *   would write one of them wrong.
 */
internal class PreparedValueAxis(
    val spec: ChartAxisSpec,
    val position: AxisPosition,
    val config: ChartAxis,
    val visible: Boolean,
    val domain: NumericDomain,
    val transform: ScaleTransform,
    val tickValues: List<Double>,
    val formatter: ChartValueFormatter,
    val measuredLabels: List<TextLayoutResult>,
    val title: TextLayoutResult?,
    val ownsGrid: Boolean,
    /** The palette slot of the first series on this axis, for [AxisStyleMode.MatchSeries]. */
    val accentPaletteIndex: Int?,
) {
    val id: ChartAxisId get() = spec.id

    /**
     * The unit this axis writes after its numbers, which is not always the one
     * it measures in.
     *
     * A caller who supplied their own `valueFormatter` has already said how the
     * number reads — a currency formatter writes `£86,400` — and appending the
     * declared unit on top of that produces `£86,400 GBP`, which is both wrong
     * and, spoken aloud, worse. So an explicit formatter is the whole
     * presentation and the declared unit stays what it is for: the mismatch
     * check, the summary and the data table's unit column.
     */
    val labelUnit: ChartUnit = if (spec.axis.valueFormatter != null) ChartUnit.None else spec.unit

    /** Formats a value the way this axis writes it, unit included. */
    fun label(value: Double): String = labelUnit.label(formatter.format(value))

    /** Announces a value with the unit spelled out. */
    fun spoken(value: Double): String = labelUnit.spoken(formatter.format(value))
}

/** Everything the multi-axis resolution produced, before the plot exists. */
internal class PreparedAxes(
    val axes: List<PreparedValueAxis>,
    val diagnostics: List<AxisDiagnostic>,
) {
    fun find(id: ChartAxisId): PreparedValueAxis? = axes.firstOrNull { it.id == id }

    /** The axis a layer that named nothing is measured against. */
    val primary: PreparedValueAxis? get() = axes.firstOrNull { it.spec.primary } ?: axes.firstOrNull()
}

/**
 * Resolves every value axis' domain, ticks, formatter and measured labels.
 *
 * @param dataDomains what the layers bound to each axis actually occupy, or
 *   `null` for an axis nothing is bound to. Computed by the caller because the
 *   answer depends on stacking, which depends on the merged category order.
 * @param boundLayerCounts how many *visible* layers each axis measures, for
 *   [AxisVisibility.Auto].
 */
@Suppress("LongParameterList")
internal fun prepareValueAxes(
    registry: AxisRegistry,
    orientation: ChartOrientation,
    dataDomains: Map<ChartAxisId, NumericDomain?>,
    boundLayerCounts: Map<ChartAxisId, Int>,
    accentPaletteIndices: Map<ChartAxisId, Int>,
    defaultPolicy: DomainPolicy,
    tickAlignment: AxisTickAlignment,
    compact: Boolean,
    textMeasurer: TextMeasurer,
    typography: ChartTypography,
    locale: Locale,
): PreparedAxes {
    val diagnostics = mutableListOf<AxisDiagnostic>()
    val specs = registry.yAxes
    if (specs.isEmpty()) return PreparedAxes(emptyList(), diagnostics)

    val visibility = specs.associate { spec ->
        spec.id to when (spec.visibility) {
            AxisVisibility.Visible -> spec.axis.visible
            AxisVisibility.Hidden -> false
            // An axis measuring nothing is not describing the chart. Hiding it
            // also gives its gutter back to the plot, which is what makes a
            // legend toggle feel like it did something rather than leaving a
            // labelled edge beside an empty scale.
            AxisVisibility.Auto -> spec.axis.visible && (boundLayerCounts[spec.id] ?: 0) > 0
        }
    }

    // Domains first, from each axis' own layers only. An axis in Auto that
    // borrowed another axis' extent would be measuring a quantity it does not
    // carry — the mistake dual axes exist to avoid.
    val domains = specs.associate { spec ->
        val policy = spec.domainPolicy ?: defaultPolicy
        spec.id to policy.apply(dataDomains[spec.id])
    }

    val tickCounts = specs.associate { spec ->
        spec.id to if (compact) {
            spec.axis.tickCount.coerceAtMost(COMPACT_TICK_COUNT)
        } else {
            spec.axis.tickCount
        }
    }

    // Row alignment, when asked for and when more than one axis can take part.
    // A log axis opts out: its ticks are powers, and forcing them onto shared
    // rows would relabel it in numbers that are not powers of anything.
    val aligned = if (tickAlignment == AxisTickAlignment.Aligned) {
        val participants = specs.filter {
            it.alignTicks && it.axis.ticks == null && it.axis.scale.transform() === ScaleTransform.Identity
        }
        AxisAlignment.align(
            participants.map { spec ->
                AlignmentRequest(
                    id = spec.id,
                    domain = domains.getValue(spec.id),
                    tickCount = tickCounts.getValue(spec.id),
                    alignZero = spec.alignZero,
                )
            },
        ).also { diagnostics += it.diagnostics }.axes
    } else {
        emptyMap()
    }

    val labelStyle = typography.axisLabel
    val titleStyle = typography.axisTitle

    val prepared = specs.map { spec ->
        val transform = spec.axis.scale.transform()
        val alignment = aligned[spec.id]
        val domain = alignment?.domain ?: domains.getValue(spec.id)
        val ticks = spec.axis.ticks
            ?: alignment?.ticks
            ?: transform.ticks(domain, tickCounts.getValue(spec.id))
        val formatter = spec.axis.valueFormatter
            ?: defaultFormatter(spec.unit, ticks, compact, locale)
        val isVisible = visibility.getValue(spec.id)
        val labelUnit = if (spec.axis.valueFormatter != null) ChartUnit.None else spec.unit
        val texts = if (spec.axis.showLabels && isVisible) {
            ticks.map { labelUnit.label(formatter.format(it)) }
        } else {
            emptyList()
        }
        PreparedValueAxis(
            spec = spec,
            position = registry.positionOf(spec),
            config = spec.config.copy(visible = isVisible),
            visible = isVisible,
            domain = domain,
            transform = transform,
            tickValues = ticks,
            formatter = formatter,
            measuredLabels = texts.map { textMeasurer.measure(it, labelStyle) },
            title = spec.config.title
                ?.takeIf { it.isNotBlank() && isVisible }
                ?.let { textMeasurer.measure(it, titleStyle) },
            ownsGrid = when (spec.grid) {
                AxisGridMode.Hidden -> false
                AxisGridMode.Visible -> true
                AxisGridMode.Primary -> spec.id == registry.primaryY?.id
            },
            accentPaletteIndex = accentPaletteIndices[spec.id]
                ?.takeIf { spec.style == AxisStyleMode.MatchSeries },
        )
    }
    return PreparedAxes(prepared, diagnostics)
}

/**
 * A formatter that writes numbers the way this axis' unit implies.
 *
 * A percent axis writes `45%` and a currency axis writes its code, without the
 * caller repeating either in a formatter — and a compact chart abbreviates
 * rather than dropping the axis.
 */
private fun defaultFormatter(
    unit: ChartUnit,
    ticks: List<Double>,
    compact: Boolean,
    locale: Locale,
): ChartValueFormatter = when {
    // Compaction is about width, and only large numbers are wide. Below a
    // thousand `1.0K` is longer than the number it replaces.
    compact && ticks.any { kotlin.math.abs(it) >= COMPACT_THRESHOLD } ->
        ChartNumberFormatters.compact(locale = locale)

    unit is ChartUnit.Percent -> ChartNumberFormatters.forTicks(ticks, locale)
    else -> ChartNumberFormatters.forTicks(ticks, locale)
}

/** What one prepared axis needs from the layout engine. */
internal fun PreparedValueAxis.metrics(density: Density, dimensions: ChartDimensions): AxisMetrics {
    val tickLength = with(density) { dimensions.tickLength.toPx() }
    val labelPadding = with(density) { dimensions.labelPadding.toPx() }
    val labelExtent = when {
        measuredLabels.isEmpty() -> 0f
        position.isHorizontal -> measuredLabels.maxOf { it.size.height }.toFloat()
        else -> measuredLabels.maxOf { it.size.width }.toFloat()
    }
    return AxisMetrics(
        position = position,
        visible = visible,
        labelExtent = labelExtent,
        tickLength = if (config.showTicks) tickLength else 0f,
        labelPadding = if (config.showLabels) labelPadding else 0f,
        // A rotated title is as thick as its line height, not as wide as its
        // words — measuring the unrotated width here would reserve a gutter
        // wide enough to write "Atmospheric pressure" across.
        titleExtent = title?.let { it.size.height + labelPadding }?.toFloat() ?: 0f,
        id = spec.id,
        offsetOverride = spec.offset?.let { with(density) { it.toPx() } },
    )
}

/** The scale this axis maps values through, once the plot area is known. */
internal fun PreparedValueAxis.scaleFor(plot: ChartRect, orientation: ChartOrientation): LinearScale =
    // Inverted on a vertical chart: larger values sit at smaller y. Encoded
    // once, here, exactly as the single-axis engine always did — which is why
    // no layer had to change to gain a second scale.
    if (orientation.isVertical) {
        LinearScale(domain, plot.bottom, plot.top, transform = transform)
    } else {
        LinearScale(domain, plot.left, plot.right, transform = transform)
    }

/** This axis, measured against a known plot area and ready to draw. */
@Suppress("LongParameterList")
internal fun PreparedValueAxis.measured(
    plot: ChartRect,
    orientation: ChartOrientation,
    scale: LinearScale,
    offset: Float,
    labelPadding: Float,
): MeasuredAxis {
    val positions = tickValues.map(scale::scale)
    val available = if (orientation.isVertical) plot.height else plot.width
    val extent = when {
        measuredLabels.isEmpty() -> 0f
        orientation.isVertical -> measuredLabels.maxOf { it.size.height }.toFloat() + labelPadding
        else -> measuredLabels.maxOf { it.size.width }.toFloat() + labelPadding * 2f
    }
    val kept = selectLabelIndices(
        count = measuredLabels.size,
        available = available,
        labelExtent = extent,
        maxLabels = config.maxLabels,
    )
    return MeasuredAxis(
        position = position,
        config = config,
        ticks = positions,
        labels = kept.mapNotNull { index ->
            val layout = measuredLabels.getOrNull(index) ?: return@mapNotNull null
            val at = positions.getOrNull(index) ?: return@mapNotNull null
            MeasuredAxisLabel(layout, at)
        },
        title = title,
        rotated = false,
        id = spec.id,
        offset = offset,
        accentPaletteIndex = accentPaletteIndex,
    )
}

/**
 * Checks that no layer is measured against an axis in the wrong unit.
 *
 * Only fires where the caller stated both — a series with no declared unit has
 * made no claim to contradict, and inventing one from the numbers is exactly
 * the guess [io.devkit.chartkit.axis.ValueAxisBinding] exists to refuse. Reported
 * rather than thrown: the chart is drawable, and a mismatch is often a caller
 * discovering that two of their series really are in different units.
 */
internal fun validateSeriesUnits(
    registry: AxisRegistry,
    layers: List<ResolvedLayer>,
): List<AxisDiagnostic> = buildList {
    layers.forEach { layer ->
        val axisId = layer.valueAxisId
        val spec = registry.find(axisId) ?: return@forEach
        if (spec.unit == ChartUnit.None) return@forEach
        layer.declaredUnits.forEach { unit ->
            if (!ChartUnit.compatible(unit, spec.unit)) {
                add(
                    AxisDiagnostic(
                        axisId = axisId,
                        message = "Layer \"${layer.key}\" declares ${describe(unit)} but is bound " +
                            "to axis \"${axisId.value}\", which measures ${describe(spec.unit)}.",
                    ),
                )
            }
        }
    }
}

private fun describe(unit: ChartUnit): String = unit.symbol?.let { "\"$it\"" } ?: unit.toString()

/**
 * A registry for a chart that never mentioned axis ids.
 *
 * Every `LineChart`, `BarChart` and single-axis `CartesianChart` goes through
 * exactly the same multi-axis engine as a three-axis combo chart; this is where
 * their two implicit axes come from. That is the point of the refactor: one
 * code path, not a simple one and a general one that drift.
 */
internal fun defaultRegistry(
    orientation: ChartOrientation,
    domainAxis: ChartAxis,
    valueAxis: ChartAxis,
    secondaryValueAxis: ChartAxis?,
    valueDomainPolicy: DomainPolicy,
): AxisRegistry = AxisRegistry.of(
    buildList {
        add(
            ChartAxisSpec(
                id = ChartAxisId.DefaultX,
                dimension = AxisDimension.X,
                position = domainAxis.position,
                axis = domainAxis,
                primary = true,
            ),
        )
        add(
            ChartAxisSpec(
                id = ChartAxisId.DefaultY,
                dimension = AxisDimension.Y,
                position = valueAxis.position,
                axis = valueAxis,
                domain = valueAxis.domain ?: valueDomainPolicy,
                visibility = AxisVisibility.Visible,
                grid = AxisGridMode.Primary,
                primary = true,
            ),
        )
        secondaryValueAxis?.let { config ->
            add(
                ChartAxisSpec(
                    id = ChartAxisId.SecondaryY,
                    dimension = AxisDimension.Y,
                    // The opposite edge, always. Two value axes on the same side
                    // would overlap, and a caller who put them there through the
                    // old two-axis API had no way to say otherwise.
                    position = config.position ?: oppositeOf(valueAxis.positionOr(defaultValuePosition(orientation))),
                    axis = config,
                    domain = config.domain ?: valueDomainPolicy,
                    visibility = AxisVisibility.Visible,
                    grid = AxisGridMode.Hidden,
                ),
            )
        }
    },
    orientation = orientation,
)

private fun defaultValuePosition(orientation: ChartOrientation): AxisPosition =
    if (orientation.isVertical) AxisPosition.Start else AxisPosition.Bottom

private fun oppositeOf(position: AxisPosition): AxisPosition = when (position) {
    AxisPosition.Start -> AxisPosition.End
    AxisPosition.End -> AxisPosition.Start
    AxisPosition.Bottom -> AxisPosition.Top
    AxisPosition.Top -> AxisPosition.Bottom
}

/** A coordinate system over one value axis, sharing the chart's domain axis. */
internal fun coordinatesFor(
    plot: ChartRect,
    domainAxis: DomainAxis,
    scale: LinearScale,
    orientation: ChartOrientation,
): CartesianCoordinates = CartesianCoordinates(plot, domainAxis, scale, orientation)

/** Fewer ticks, so three axes' labels fit a phone without overlapping. */
private const val COMPACT_TICK_COUNT: Int = 4

/** Below this, `1.0K` is longer than the number it abbreviates. */
private const val COMPACT_THRESHOLD: Double = 10_000.0
