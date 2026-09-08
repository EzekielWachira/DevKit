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
 * Colours for treemaps and sunbursts.
 *
 * The tiles and arcs themselves take the series palette, so a branch and its
 * children read as one family; only the furniture is here.
 *
 * @param tileBorder the hairline between adjacent tiles or arcs. Drawn in the
 *   surface colour rather than an outline colour, so it reads as a gap.
 * @param tileLabel text written inside a tile. Chosen to survive both ends of
 *   the depth shading, which is why it is not simply `onSurface`.
 * @param breadcrumbCurrent the breadcrumb entry the chart is showing.
 * @param breadcrumbAncestor the entries above it, which are tappable.
 */
@Immutable
data class ChartHierarchyColors(
    val tileBorder: Color,
    val tileLabel: Color,
    val breadcrumbCurrent: Color,
    val breadcrumbAncestor: Color,
)

/**
 * Colours for Sankey diagrams and funnels.
 *
 * @param node a flow node's box, and a funnel stage's body.
 * @param nodeBorder its outline.
 * @param link a flow band at rest.
 * @param linkHighlight a band connected to the selection.
 * @param linkMuted a band that is not. Muted with a *colour*, not only with
 *   opacity: connection state conveyed by alpha alone disappears on a dense
 *   diagram and for a reader with low contrast sensitivity.
 * @param label text drawn beside a node or inside a stage.
 */
@Immutable
data class ChartFlowColors(
    val node: Color,
    val nodeBorder: Color,
    val link: Color,
    val linkHighlight: Color,
    val linkMuted: Color,
    val label: Color,
)

/**
 * Colours for network graphs.
 *
 * @param node a node at rest.
 * @param nodeBorder its outline, which is also what marks a pinned node.
 * @param edge an edge at rest.
 * @param edgeHighlight an edge touching the selection.
 * @param neighbour a node one hop from the selection.
 * @param label a node's name.
 */
@Immutable
data class ChartGraphColors(
    val node: Color,
    val nodeBorder: Color,
    val edge: Color,
    val edgeHighlight: Color,
    val neighbour: Color,
    val label: Color,
)

/**
 * Semantic colours for the comparison charts.
 *
 * ### Not green and red
 *
 * The same reasoning as [ChartFinancialColors]: the convention is not
 * universal, and it is invisible to the readers who most need the distinction.
 * A waterfall asks for [increase] and [decrease] by name, and an application
 * that needs the opposite convention swaps two values.
 *
 * @param increase a positive contribution.
 * @param decrease a negative one.
 * @param subtotal a checkpoint measured from zero.
 * @param total the final figure.
 * @param connector the line joining one waterfall bar to the next.
 * @param target a bullet graph's target mark, and a dumbbell's end marker.
 * @param stem a lollipop's stem, and a dumbbell's connector.
 * @param qualitativeBands a bullet graph's background ranges, quiet to loud.
 *   Ordered rather than named "good/fair/poor": the meaning of a band is the
 *   application's, and a library that named them would be asserting it.
 */
@Immutable
data class ChartComparisonColors(
    val increase: Color,
    val decrease: Color,
    val subtotal: Color,
    val total: Color,
    val connector: Color,
    val target: Color,
    val stem: Color,
    val qualitativeBands: List<Color>,
) {
    init {
        require(qualitativeBands.isNotEmpty()) {
            "A bullet graph needs at least one qualitative band colour"
        }
    }

    /** The colour for band [index], wrapping when there are more bands than colours. */
    fun band(index: Int): Color =
        qualitativeBands[((index % qualitativeBands.size) + qualitativeBands.size) % qualitativeBands.size]
}

/**
 * Colours for gauges.
 *
 * @param track the unfilled arc — the maximum a value is measured against.
 * @param progress the filled arc, when no threshold band applies.
 * @param needle the indicator, where one is drawn.
 */
@Immutable
data class ChartGaugeColors(
    val track: Color,
    val progress: Color,
    val needle: Color,
)

/**
 * Colours for timelines, range charts and Gantt-style views.
 *
 * @param interval a duration bar.
 * @param progress the completed part drawn inside one.
 * @param milestone a moment marker.
 * @param laneSeparator the rule between two lanes.
 * @param laneLabel a lane's name.
 */
@Immutable
data class ChartTimelineColors(
    val interval: Color,
    val progress: Color,
    val milestone: Color,
    val laneSeparator: Color,
    val laneLabel: Color,
)

/**
 * Colours for the overview navigator.
 *
 * @param window the selected window's fill.
 * @param windowBorder its edges.
 * @param mask the wash over what is *not* selected. Dimming the outside rather
 *   than tinting the inside keeps the data inside the window at its true
 *   colours, which is the part the reader is about to look at.
 * @param handle the draggable edges.
 */
@Immutable
data class ChartNavigatorColors(
    val window: Color,
    val windowBorder: Color,
    val mask: Color,
    val handle: Color,
)

/**
 * Colours for thematic maps.
 *
 * The shaded regions themselves come from a
 * [io.devkit.chartkit.scale.ColorScale], exactly as a heatmap's cells do — so
 * only the furniture is here.
 *
 * @param border the line between two regions. Visible by default: a choropleth
 *   without boundaries is a blur of colour in which no reader can tell where
 *   one region ends, and two adjacent regions of similar value become one.
 * @param missing a region the data had no record for. Deliberately **not** a
 *   shade of the ramp: "nobody measured this" and "this measured lowest" are
 *   different facts, and a map that paints them alike is asserting one of them.
 * @param missingBorder the outline of such a region, so it still reads as a
 *   region rather than as a hole in the map.
 * @param label text drawn on a region.
 * @param labelHalo drawn behind a label so it survives a dark fill underneath.
 */
@Immutable
data class ChartGeoColors(
    val border: Color,
    val missing: Color,
    val missingBorder: Color,
    val label: Color,
    val labelHalo: Color,
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
 * @param hierarchy treemap and sunburst furniture, and breadcrumb text.
 * @param flow Sankey nodes and links, and funnel stages.
 * @param graph network-graph nodes, edges and highlighting.
 * @param comparison waterfall, dumbbell, lollipop and bullet semantics.
 * @param gauge a gauge's track, progress arc and needle.
 * @param timeline interval bars, progress overlays and milestones.
 * @param navigator the overview chart's window and mask.
 * @param geo region borders, the "no data" fill and map labels.
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
    val hierarchy: ChartHierarchyColors = ChartHierarchyColors(
        tileBorder = Color.Transparent,
        tileLabel = tooltipContent,
        breadcrumbCurrent = axisTitle,
        breadcrumbAncestor = axisLabel,
    ),
    val flow: ChartFlowColors = ChartFlowColors(
        node = palette[0],
        nodeBorder = axisLine,
        link = palette[0].copy(alpha = 0.35f),
        linkHighlight = palette[0],
        linkMuted = gridLine.copy(alpha = 0.35f),
        label = axisLabel,
    ),
    val graph: ChartGraphColors = ChartGraphColors(
        node = palette[0],
        nodeBorder = axisLine,
        edge = gridLine,
        edgeHighlight = selectionGuide,
        neighbour = palette[palette.size / 2],
        label = axisLabel,
    ),
    val comparison: ChartComparisonColors = ChartComparisonColors(
        increase = financial.increase,
        decrease = financial.decrease,
        subtotal = axisLabel,
        total = palette[0],
        connector = gridLine,
        target = axisTitle,
        stem = gridLine,
        // Three steps of one hue rather than three hues: a bullet graph's
        // bands are an ordered scale, and three unrelated colours would read
        // as three categories.
        qualitativeBands = listOf(
            gridLine.copy(alpha = 0.35f),
            gridLine.copy(alpha = 0.22f),
            gridLine.copy(alpha = 0.12f),
        ),
    ),
    val gauge: ChartGaugeColors = ChartGaugeColors(
        track = radialTrack,
        progress = palette[0],
        needle = axisTitle,
    ),
    val timeline: ChartTimelineColors = ChartTimelineColors(
        interval = palette[0],
        progress = palette[0].copy(alpha = 0.55f),
        milestone = axisTitle,
        laneSeparator = gridLine,
        laneLabel = axisLabel,
    ),
    val navigator: ChartNavigatorColors = ChartNavigatorColors(
        window = rangeFill,
        windowBorder = rangeBorder,
        mask = emptyContent.copy(alpha = 0.18f),
        handle = rangeBorder,
    ),
    val geo: ChartGeoColors = ChartGeoColors(
        border = gridLine,
        missing = heatmap.missing,
        missingBorder = gridLine,
        label = axisLabel,
        labelHalo = tooltipContent,
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
    /** One entry of a hierarchy breadcrumb trail. */
    val breadcrumbLabel: TextStyle = legendLabel,
    /** A flow node's, graph node's or funnel stage's name. */
    val nodeLabel: TextStyle = valueLabel,
    /** A region's name, drawn on a thematic map. */
    val geoLabel: TextStyle = valueLabel,
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

    /** The length of a callout's connector before it turns toward its label. */
    val calloutConnectorLength: Dp = 24.dp,

    /** The size of an arrow annotation's head. */
    val annotationArrowHead: Dp = 7.dp,

    // ---- hierarchy ------------------------------------------------------

    /** The corner rounding of a treemap tile. */
    val treemapCornerRadius: Dp = 3.dp,

    /** The hairline drawn around a treemap tile. */
    val treemapTileBorderWidth: Dp = 1.dp,

    /** Space inset inside a treemap branch before its children are packed. */
    val treemapNestingPadding: Dp = 2.dp,

    /** The gap left between sibling treemap tiles. */
    val treemapTileGap: Dp = 2.dp,

    /** The gap left between two sunburst rings. */
    val sunburstRingSpacing: Dp = 1.dp,

    /** Angular space between sibling sunburst arcs, in degrees. */
    val sunburstSliceGap: Float = 0.6f,

    // ---- flow -----------------------------------------------------------

    /** The width of a Sankey node's box. */
    val sankeyNodeWidth: Dp = 12.dp,

    /** Vertical space between two Sankey nodes in one column. */
    val sankeyNodePadding: Dp = 10.dp,

    /** Vertical space between two funnel stages. */
    val funnelStageSpacing: Dp = 3.dp,

    // ---- comparison -----------------------------------------------------

    /** The line joining one waterfall bar to the next. */
    val waterfallConnectorWidth: Dp = 1.dp,

    /** The bar joining a dumbbell's two markers. */
    val dumbbellConnectorWidth: Dp = 3.dp,

    /** The radius of a dumbbell's end markers. */
    val dumbbellMarkerRadius: Dp = 5.dp,

    /** The stem of a lollipop. */
    val lollipopStemWidth: Dp = 2.dp,

    /** The head of a lollipop. */
    val lollipopMarkerRadius: Dp = 5.dp,

    /** The thickness of a bullet graph's measure bar. */
    val bulletBarThickness: Dp = 10.dp,

    /** The thickness of its target mark. */
    val bulletTargetWidth: Dp = 3.dp,

    /** The height of one bullet graph row, bands included. */
    val bulletRowHeight: Dp = 28.dp,

    // ---- gauge ----------------------------------------------------------

    /** The thickness of a gauge's arc. */
    val gaugeThickness: Dp = 18.dp,

    /** The width of a gauge's needle at its base. */
    val gaugeNeedleWidth: Dp = 4.dp,

    // ---- timeline -------------------------------------------------------

    /** The height of one timeline row. */
    val timelineRowHeight: Dp = 22.dp,

    /** The vertical gap between two timeline rows. */
    val timelineRowSpacing: Dp = 6.dp,

    /** The corner rounding of an interval bar. */
    val timelineBarCornerRadius: Dp = 4.dp,

    /** The half-diagonal of a milestone diamond. */
    val milestoneRadius: Dp = 6.dp,

    // ---- graph ----------------------------------------------------------

    /** The radius of a graph node with no weight encoding. */
    val graphNodeRadius: Dp = 7.dp,

    /** The smallest a weighted graph node is drawn. */
    val graphNodeMinRadius: Dp = 4.dp,

    /** The largest a weighted graph node is drawn. */
    val graphNodeMaxRadius: Dp = 18.dp,

    /** The width of a graph edge. */
    val graphEdgeWidth: Dp = 1.dp,

    // ---- navigator ------------------------------------------------------

    /** The height an overview navigator falls back to. */
    val navigatorHeight: Dp = 56.dp,

    /** The width of the navigator window's draggable edges. */
    val navigatorHandleWidth: Dp = 8.dp,

    // ---- geographic -----------------------------------------------------

    /** The line between two regions on a thematic map. */
    val geoBorderWidth: Dp = 0.5.dp,

    /** The outline of a selected region. Heavier, so it reads over any fill. */
    val geoSelectedBorderWidth: Dp = 2.dp,

    /** Space kept between the geography and the plot's edge. */
    val geoMapPadding: Dp = 12.dp,

    /** The height of a continuous colour-scale legend's ramp bar. */
    val colorLegendBarHeight: Dp = 10.dp,

    /** The width a continuous colour-scale legend's ramp bar aims for. */
    val colorLegendBarWidth: Dp = 160.dp,
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
        hierarchy = ChartHierarchyColors(
            // The gap between tiles is the surface showing through, not a
            // drawn outline: an outline in a third colour turns a dense
            // treemap into a grid of borders.
            tileBorder = scheme.surface,
            // `onSurface` rather than the tooltip's content colour: a treemap
            // shades its tiles toward the background with depth, so the label
            // has to stay readable at the pale end as well as the saturated
            // one.
            tileLabel = scheme.onSurface,
            breadcrumbCurrent = scheme.onSurface,
            breadcrumbAncestor = scheme.primary,
        ),
        flow = ChartFlowColors(
            node = scheme.primary,
            nodeBorder = scheme.outlineVariant,
            // A band is translucent so overlapping flows remain legible, and
            // so a node's box reads as solid against them.
            link = scheme.primary.copy(alpha = 0.30f),
            linkHighlight = scheme.primary,
            // A different colour, not merely a fainter one: connection state
            // carried by opacity alone vanishes on a busy diagram.
            linkMuted = scheme.outlineVariant.copy(alpha = 0.30f),
            label = scheme.onSurfaceVariant,
        ),
        graph = ChartGraphColors(
            node = scheme.primary,
            nodeBorder = scheme.surface,
            edge = scheme.outlineVariant,
            edgeHighlight = scheme.onSurface,
            neighbour = scheme.tertiary,
            label = scheme.onSurfaceVariant,
        ),
        comparison = ChartComparisonColors(
            increase = scheme.tertiary,
            decrease = scheme.error,
            subtotal = scheme.onSurfaceVariant,
            total = scheme.primary,
            connector = scheme.outlineVariant,
            target = scheme.onSurface,
            stem = scheme.outlineVariant,
            qualitativeBands = listOf(
                scheme.surfaceVariant,
                scheme.surfaceVariant.copy(alpha = 0.6f),
                scheme.surfaceVariant.copy(alpha = 0.3f),
            ),
        ),
        gauge = ChartGaugeColors(
            track = scheme.surfaceVariant,
            progress = scheme.primary,
            needle = scheme.onSurface,
        ),
        timeline = ChartTimelineColors(
            interval = scheme.primary,
            // Drawn over the bar, so it needs to differ from it without
            // becoming a second category colour.
            progress = scheme.onPrimary.copy(alpha = 0.45f),
            milestone = scheme.onSurface,
            laneSeparator = scheme.outlineVariant.copy(alpha = 0.5f),
            laneLabel = scheme.onSurfaceVariant,
        ),
        geo = ChartGeoColors(
            // The boundary is the surface showing between two regions rather
            // than a third colour drawn on top, which keeps a dense county map
            // from reading as a grid of outlines.
            border = scheme.surface,
            // The scheme's own container role, not a tint of the ramp: a
            // reader must be able to tell "no record" from "lowest value" at a
            // glance, and a paler shade of the same hue reads as the latter.
            missing = scheme.surfaceVariant,
            missingBorder = scheme.outlineVariant,
            label = scheme.onSurface,
            // The surface itself behind the glyphs, so a label stays legible
            // over the darkest end of the ramp as well as the lightest.
            labelHalo = scheme.surface,
        ),
        navigator = ChartNavigatorColors(
            window = scheme.primary.copy(alpha = 0.12f),
            windowBorder = scheme.primary,
            // The *outside* is dimmed, so the data inside the window keeps its
            // true colours — that is the part the reader is about to look at.
            mask = scheme.surface.copy(alpha = if (isDark) 0.55f else 0.65f),
            handle = scheme.primary,
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
