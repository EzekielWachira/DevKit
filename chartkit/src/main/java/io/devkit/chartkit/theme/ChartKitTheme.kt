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
