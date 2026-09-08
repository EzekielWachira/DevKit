package io.devkit.chartkit.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Colours for financial charts.
 *
 * ### Not green and red
 *
 * Nothing in ChartKit's rendering knows that "up" is green. The convention is
 * not universal — several East Asian markets colour rising prices red — and it
 * is invisible to the eight percent of men with red-green colour vision
 * deficiency, for whom the two most important colours on a candlestick chart
 * are the same colour. So the semantic roles live here, the renderers ask for
 * [increase] and [decrease] by name, and an application that needs the
 * opposite convention swaps two values instead of forking a layer.
 *
 * The defaults are taken from the enclosing Material scheme rather than from
 * constants, for the same reason the series palette is derived rather than
 * hardcoded.
 *
 * @param increase a period that closed above its open.
 * @param decrease a period that closed below its open.
 * @param neutral a period that closed where it opened, and the fallback where
 *   direction is unknown.
 * @param wick the high–low line. Quieter than the body: the body is the
 *   quantity being compared, and the wick is its extent.
 */
@Immutable
data class ChartFinancialColors(
    val increase: Color,
    val decrease: Color,
    val neutral: Color,
    val wick: Color,
)

/**
 * Colours for heatmaps and calendar heatmaps.
 *
 * @param low the domain minimum's colour.
 * @param high the domain maximum's colour.
 * @param missing a cell with no measurement. Visually distinct from [low] on
 *   purpose — "nobody measured this" and "this measured zero" are different
 *   facts, and a heatmap that paints them the same is asserting one of them.
 * @param cellBorder an optional hairline between cells; transparent leaves them
 *   flush.
 */
@Immutable
data class ChartHeatmapColors(
    val low: Color,
    val high: Color,
    val missing: Color,
    val cellBorder: Color,
)

/**
 * Colours for the statistical charts.
 *
 * @param box the fill of a box plot's interquartile box.
 * @param boxBorder its outline, and the whiskers.
 * @param median the median line. Drawn in a contrasting colour rather than a
 *   darker shade of the box, because the median is the number most readers take
 *   from a box plot and it has to survive being printed.
 * @param outlier individual points beyond the whiskers.
 * @param densityFill the body of a violin.
 * @param densityOutline its edge.
 */
@Immutable
data class ChartStatisticalColors(
    val box: Color,
    val boxBorder: Color,
    val median: Color,
    val outlier: Color,
    val densityFill: Color,
    val densityOutline: Color,
)

/**
 * Colours for annotations.
 *
 * @param line rules and marker outlines.
 * @param region the wash of a range or region annotation.
 * @param labelContainer the chip behind an annotation's label.
 * @param labelContent text on that chip.
 */
@Immutable
data class ChartAnnotationColors(
    val line: Color,
    val region: Color,
    val labelContainer: Color,
    val labelContent: Color,
)

/**
 * The colours every ChartKit chart draws with.
 *
 * @param palette one colour per series, taken by index. Series keep their slot
 *   when another is hidden, so toggling a legend entry never recolours the rest.
 * @param axisLine the axis rule.
 * @param gridLine grid lines behind the plot. Quieter than [axisLine] on
 *   purpose: a grid competing with the data is a grid drawn wrong.
 * @param axisLabel tick label text.
 * @param axisTitle axis title text.
 * @param selectionGuide the vertical (or horizontal) line drawn through the
 *   selected point while scrubbing.
 * @param selectionHighlight the wash over a selected bar.
 * @param tooltipContainer the tooltip's surface.
 * @param tooltipContent text on the tooltip.
 * @param valueLabel text for value labels drawn on bars and points.
 * @param emptyContent the muted colour for the built-in empty and error states.
 * @param radialTrack the unfilled part of a radial bar's ring — the "100%" a
 *   72% bar is measured against. Quiet by default: a track competing with its
 *   bar is a track drawn wrong.
 * @param crosshairGuide the crosshair's rules. Defaults to [selectionGuide],
 *   because a crosshair *is* a selection made visible.
 * @param crosshairLabelContainer the surface behind a crosshair's axis readout.
 * @param crosshairLabelContent text on that readout.
 * @param rangeFill the wash over a selected domain range.
 * @param rangeBorder the range's edges. Drawn as well as the fill, not instead
 *   of it: a region distinguished only by a tint is invisible to a reader with
 *   low contrast sensitivity.
 *
 * @param financial semantic colours for candlestick, OHLC and volume charts.
 * @param heatmap the ramp ends and the "no data" colour for heatmaps.
 * @param statistical box, whisker, median, outlier and density colours.
 * @param annotation rules, regions and annotation labels.
 *
 * Every parameter after [emptyContent] defaults to a value derived from the
 * ones above it, so a `ChartColors(...)` written against an earlier surface
 * still compiles and still looks right — and an application that customised
 * four colours does not have to learn about twenty.
 */
@Immutable
data class ChartColors(
    val palette: List<Color>,
    val axisLine: Color,
    val gridLine: Color,
    val axisLabel: Color,
    val axisTitle: Color,
    val selectionGuide: Color,
    val selectionHighlight: Color,
    val tooltipContainer: Color,
    val tooltipContent: Color,
    val valueLabel: Color,
    val emptyContent: Color,
    val radialTrack: Color = gridLine,
    val crosshairGuide: Color = selectionGuide,
    val crosshairLabelContainer: Color = tooltipContainer,
    val crosshairLabelContent: Color = tooltipContent,
    val rangeFill: Color = selectionHighlight,
    val rangeBorder: Color = selectionGuide,
    val financial: ChartFinancialColors = ChartFinancialColors(
        // Two hues from the chart's own palette, which the generator has
        // already spread as far apart as it can. Distinguishable without
        // asserting a colour convention ChartKit has no business assuming.
        increase = palette[0],
        decrease = palette[palette.size / 2],
        neutral = axisLabel,
        wick = axisLine,
    ),
    val heatmap: ChartHeatmapColors = ChartHeatmapColors(
        low = palette[0].copy(alpha = 0.12f),
        high = palette[0],
        missing = gridLine.copy(alpha = 0.25f),
        cellBorder = Color.Transparent,
    ),
    val statistical: ChartStatisticalColors = ChartStatisticalColors(
        box = palette[0].copy(alpha = 0.35f),
        boxBorder = palette[0],
        median = axisLabel,
        outlier = palette[0],
        densityFill = palette[0].copy(alpha = 0.30f),
        densityOutline = palette[0],
    ),
    val annotation: ChartAnnotationColors = ChartAnnotationColors(
        line = selectionGuide,
        region = selectionHighlight,
        labelContainer = tooltipContainer,
        labelContent = tooltipContent,
    ),
) {
    init {
        require(palette.isNotEmpty()) {
            "A chart palette needs at least one colour — series are coloured by index into it"
        }
    }

    /**
     * The colour for series [index], wrapping when there are more series than
     * colours.
     *
     * Wrapping rather than throwing: a chart with thirteen series is legible
     * enough to draw and no reason to crash. The palette generator supplies
     * twelve distinguishable hues before that happens.
     */
    fun seriesColor(index: Int): Color = palette[((index % palette.size) + palette.size) % palette.size]
}

/** Text styles for the chart's own furniture. */
@Immutable
data class ChartTypography(
    val axisLabel: TextStyle,
    val axisTitle: TextStyle,
    val legendLabel: TextStyle,
    val valueLabel: TextStyle,
    val tooltipTitle: TextStyle,
    val tooltipValue: TextStyle,
    /** Labels drawn on pie and donut slices. */
    val sliceLabel: TextStyle = valueLabel,
    /** The value readout a crosshair puts on an axis. */
    val crosshairLabel: TextStyle = axisLabel,
    /** Text drawn inside a heatmap or calendar cell. */
    val cellLabel: TextStyle = valueLabel,
    /** An annotation's own label. */
    val annotationLabel: TextStyle = axisLabel,
)

/**
 * Sizes and stroke widths.
 *
 * All in `Dp`, so a chart scales with the reader's display settings instead of
 * being drawn at a fixed pixel size that looks right on exactly one device.
 */
@Immutable
data class ChartDimensions(
    val lineWidth: Dp = 2.dp,
    val gridLineWidth: Dp = 1.dp,
    val axisLineWidth: Dp = 1.dp,
    val tickLength: Dp = 4.dp,
    val labelPadding: Dp = 4.dp,
    val pointRadius: Dp = 3.dp,
    val selectedPointRadius: Dp = 6.dp,
    val barCornerRadius: Dp = 4.dp,
    val selectionGuideWidth: Dp = 1.dp,
    val legendItemSpacing: Dp = 12.dp,
    val legendIndicatorSize: Dp = 10.dp,
    val tooltipPadding: Dp = 8.dp,
    val tooltipCornerRadius: Dp = 8.dp,
    val contentPadding: Dp = 4.dp,
    /** The height a chart falls back to when its caller constrains neither. */
    val defaultChartHeight: Dp = 200.dp,

    // ---- polar ----------------------------------------------------------

    /**
     * How far a selected pie or donut slice lifts out of the ring.
     *
     * Displacement rather than a colour change, because it survives being
     * printed, screenshotted or read by somebody who cannot distinguish the
     * two colours involved.
     */
    val sliceSelectionOffset: Dp = 6.dp,

    /** The ring a radial bar sweeps within. */
    val radialBarThickness: Dp = 16.dp,

    /** Space between concentric radial tracks. */
    val radialBarSpacing: Dp = 6.dp,

    /** Padding between a polar chart's outer radius and its plot area. */
    val polarPadding: Dp = 8.dp,

    // ---- interaction overlays -------------------------------------------

    val crosshairWidth: Dp = 1.dp,

    /** Padding inside a crosshair's axis readout. */
    val crosshairLabelPadding: Dp = 4.dp,

    /** The width of a range selection's edge markers. */
    val rangeHandleWidth: Dp = 2.dp,

    // ---- statistical ----------------------------------------------------

    /** The radius of a scatter marker. Larger than a line's point marker,
     *  which sits on a line the reader can already see. */
    val scatterPointRadius: Dp = 4.dp,

    /** The smallest bubble a size scale will draw. Never zero: a bubble of no
     *  size is indistinguishable from a missing observation. */
    val bubbleMinRadius: Dp = 5.dp,

    /** The largest bubble a size scale will draw. */
    val bubbleMaxRadius: Dp = 28.dp,

    /** The fraction of a category band a box plot's box occupies. */
    val boxPlotWidthFraction: Float = 0.55f,

    /** The fraction of a category band a box plot's whisker caps occupy. */
    val whiskerCapFraction: Float = 0.28f,

    /** The radius of an outlier point. */
    val outlierRadius: Dp = 2.5.dp,

    /** The fraction of a category band a violin's widest point occupies. */
    val violinWidthFraction: Float = 0.85f,

    /** The stroke around a violin's body. */
    val violinOutlineWidth: Dp = 1.dp,

    // ---- density --------------------------------------------------------

    /** The gap left between heatmap cells. */
    val heatmapCellSpacing: Dp = 1.dp,

    /** The corner rounding of a heatmap or calendar cell. */
    val heatmapCellCornerRadius: Dp = 2.dp,

    /** The gap left between calendar-heatmap cells. */
    val calendarCellSpacing: Dp = 2.dp,

    // ---- financial ------------------------------------------------------

    /** The fraction of the space between two periods a candle body occupies. */
    val candleBodyFraction: Float = 0.7f,

    /** The narrowest a candle body is drawn before it becomes a bare line. */
    val candleMinBodyWidth: Dp = 1.dp,

    /** The width of a wick, and of an OHLC bar's stem and ticks. */
    val candleWickWidth: Dp = 1.dp,

    // ---- annotations ----------------------------------------------------

    val annotationLineWidth: Dp = 1.dp,

    /** The radius of an event marker. */
    val annotationMarkerRadius: Dp = 5.dp,

    /** Padding inside an annotation's label chip. */
    val annotationLabelPadding: Dp = 4.dp,
)

/**
 * Everything a chart needs to style itself, resolved once.
 *
 * Read through [ChartKitTheme] rather than constructed directly, except when
 * overriding.
 */
@Immutable
data class ChartTheme(
    val colors: ChartColors,
    val typography: ChartTypography,
    val dimensions: ChartDimensions,
)

private val LocalChartTheme: ProvidableCompositionLocal<ChartTheme?> =
    staticCompositionLocalOf { null }

/**
 * Styling for every ChartKit chart beneath it.
 *
 * ```kotlin
 * ChartKitTheme(colors = brandChartColors) {
 *     App()
 * }
 * ```
 *
 * ### Precedence
 *
 * ```text
 * explicit chart parameter  →  ChartKitTheme  →  MaterialTheme-derived default
 * ```
 *
 * All three levels exist because each solves a different problem. The
 * Material-derived default means a chart dropped into an app with no
 * configuration already matches it. The theme means an organisation states its
 * chart styling once. The per-chart parameter means changing one line's width
 * does not require declaring a theme — the trap a library falls into when
 * styling is only available wholesale.
 *
 * Every parameter is optional and falls back to the enclosing theme, so a
 * nested `ChartKitTheme(dimensions = …)` overrides dimensions and inherits the
 * colours.
 */
@Composable
fun ChartKitTheme(
    colors: ChartColors? = null,
    typography: ChartTypography? = null,
    dimensions: ChartDimensions? = null,
    content: @Composable () -> Unit,
) {
    val inherited = ChartKitTheme.current
    val theme = remember(colors, typography, dimensions, inherited) {
        ChartTheme(
            colors = colors ?: inherited.colors,
            typography = typography ?: inherited.typography,
            dimensions = dimensions ?: inherited.dimensions,
        )
    }
    CompositionLocalProvider(LocalChartTheme provides theme, content = content)
}

/** Access to the resolved chart theme. */
object ChartKitTheme {

    /**
     * The theme in scope, or one derived from [MaterialTheme].
     *
     * The fallback is what makes an unconfigured chart look right: colours come
     * from the app's own Material scheme, so light and dark, and dynamic colour
     * where the app uses it, are handled without ChartKit knowing about any of
     * it.
     */
    val current: ChartTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalChartTheme.current ?: materialDerivedChartTheme()

    val colors: ChartColors
        @Composable
        @ReadOnlyComposable
        get() = current.colors

    val typography: ChartTypography
        @Composable
        @ReadOnlyComposable
        get() = current.typography

    val dimensions: ChartDimensions
        @Composable
        @ReadOnlyComposable
        get() = current.dimensions
}

/**
 * Chart colours derived from the enclosing Material 3 scheme.
 *
 * The series palette is generated from `primary` — see [ChartPalette] for why
 * it is generated rather than picked from the scheme's own roles — while the
 * chart's furniture uses the roles Material already defines for it: outlines
 * for rules and grid, `onSurfaceVariant` for secondary text, the inverse
 * surface pair for the tooltip, which is the same treatment Material's own
 * tooltips and snackbars use.
 */
@Composable
@ReadOnlyComposable
fun materialDerivedChartColors(
    isDark: Boolean = false,
    paletteSize: Int = DEFAULT_PALETTE_SIZE,
): ChartColors {
    val scheme = MaterialTheme.colorScheme
    val palette = ChartPalette
        .derive(scheme.primary.toArgb(), isDark, paletteSize)
        .map { Color(it) }
    return ChartColors(
        palette = palette,
        axisLine = scheme.outlineVariant,
        // Grid lines sit behind the data and must not compete with it.
        gridLine = scheme.outlineVariant.copy(alpha = 0.5f),
        axisLabel = scheme.onSurfaceVariant,
        axisTitle = scheme.onSurfaceVariant,
        selectionGuide = scheme.onSurface.copy(alpha = 0.4f),
        selectionHighlight = scheme.onSurface.copy(alpha = 0.12f),
        tooltipContainer = scheme.inverseSurface,
        tooltipContent = scheme.inverseOnSurface,
        valueLabel = scheme.onSurfaceVariant,
        emptyContent = scheme.onSurfaceVariant,
        // A radial track reads as the empty half of a measurement, so it takes
        // the scheme's own container role rather than a tint of the bar.
        radialTrack = scheme.surfaceVariant,
        crosshairGuide = scheme.onSurface.copy(alpha = 0.55f),
        crosshairLabelContainer = scheme.inverseSurface,
        crosshairLabelContent = scheme.inverseOnSurface,
        rangeFill = scheme.primary.copy(alpha = 0.16f),
        rangeBorder = scheme.primary,
        financial = ChartFinancialColors(
            // Material's own roles, not green and red. `tertiary` and `error`
            // are the scheme's two most distinguishable accents, they adapt
            // with the app's colour, and neither asserts a market convention.
            // An application whose market colours rising prices red overrides
            // these two values.
            increase = scheme.tertiary,
            decrease = scheme.error,
            neutral = scheme.onSurfaceVariant,
            wick = scheme.onSurfaceVariant,
        ),
        heatmap = ChartHeatmapColors(
            // A ramp within one hue: legible in both themes, and free of the
            // desaturated middle a two-hue ramp passes through. In dark mode
            // it runs from a dim surface tint up to the full accent, so the
            // low end stays visible against a near-black background instead of
            // disappearing into it.
            low = if (isDark) {
                scheme.primary.copy(alpha = 0.18f)
            } else {
                scheme.primary.copy(alpha = 0.10f)
            },
            high = scheme.primary,
            missing = scheme.surfaceVariant.copy(alpha = if (isDark) 0.35f else 0.6f),
            cellBorder = scheme.surface,
        ),
        statistical = ChartStatisticalColors(
            box = palette.first().copy(alpha = 0.35f),
            boxBorder = palette.first(),
            // The median contrasts with the box rather than shading it: it is
            // the number most readers take from a box plot.
            median = scheme.onSurface,
            outlier = palette.first(),
            densityFill = palette.first().copy(alpha = 0.30f),
            densityOutline = palette.first(),
        ),
        annotation = ChartAnnotationColors(
            line = scheme.onSurface.copy(alpha = 0.55f),
            region = scheme.secondary.copy(alpha = 0.14f),
            labelContainer = scheme.inverseSurface,
            labelContent = scheme.inverseOnSurface,
        ),
    )
}

/** Chart text styles derived from the enclosing Material 3 type scale. */
@Composable
@ReadOnlyComposable
fun materialDerivedChartTypography(): ChartTypography {
    val type = MaterialTheme.typography
    return ChartTypography(
        axisLabel = type.labelSmall,
        axisTitle = type.labelMedium,
        legendLabel = type.labelMedium,
        valueLabel = type.labelSmall,
        tooltipTitle = type.labelMedium.copy(fontWeight = FontWeight.Medium),
        tooltipValue = type.bodySmall,
    )
}

@Composable
@ReadOnlyComposable
private fun materialDerivedChartTheme(): ChartTheme = ChartTheme(
    colors = materialDerivedChartColors(isDark = isSystemInDarkThemeSafe()),
    typography = materialDerivedChartTypography(),
    dimensions = ChartDimensions(),
)

/**
 * Whether to build a dark-mode palette.
 *
 * Reads the system setting rather than inspecting the Material scheme, because
 * an app can — and often does — apply a dark scheme in light mode or the
 * reverse. The consequence is that an app forcing one theme against the system
 * setting gets a palette tuned for the other, which is why
 * [materialDerivedChartColors] takes `isDark` as a parameter and a caller in
 * that position provides it explicitly.
 */
@Composable
@ReadOnlyComposable
private fun isSystemInDarkThemeSafe(): Boolean = isSystemInDarkTheme()

/** Twelve distinguishable hues; more series than that and a chart needs rethinking. */
const val DEFAULT_PALETTE_SIZE: Int = 12
