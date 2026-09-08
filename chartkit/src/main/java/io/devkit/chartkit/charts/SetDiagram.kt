package io.devkit.chartkit.charts

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.coordinate.PlanarCoordinates
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartInsets
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.layer.set.SetColorMode
import io.devkit.chartkit.layer.set.SetDiagramLayer
import io.devkit.chartkit.layer.set.SetFocusMode
import io.devkit.chartkit.layer.set.defaultRegionName
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipData
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.render.ChartStaticOptions
import io.devkit.chartkit.set.RegionGeometry
import io.devkit.chartkit.set.RegionGeometryIndex
import io.devkit.chartkit.set.SetAnalyzer
import io.devkit.chartkit.set.SetContainment
import io.devkit.chartkit.set.SetDefinition
import io.devkit.chartkit.set.SetDiagramData
import io.devkit.chartkit.set.SetDiagramLayout
import io.devkit.chartkit.set.SetIntersection
import io.devkit.chartkit.set.SetItems
import io.devkit.chartkit.set.SetLayout
import io.devkit.chartkit.set.SetLayoutConfig
import io.devkit.chartkit.set.membershipAt
import io.devkit.chartkit.set.SetRegion
import io.devkit.chartkit.set.SetSizing
import io.devkit.chartkit.set.SetStyle
import io.devkit.chartkit.set.SetValidationMode
import io.devkit.chartkit.set.engine
import io.devkit.chartkit.state.ChartState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * How region and set names are written.
 *
 * ```text
 * "Android"              a set
 * "Android only"         its exclusive region
 * "Android & iOS"        a pairwise region
 * ```
 *
 * ### Why this is a parameter and not three string constants
 *
 * "only" and "&" are English. A diagram shipped to a Turkish or Japanese reader
 * needs different words and, in some languages, a different word order. Burying
 * them in the rendering code would make every set diagram in every locale say
 * "Android only" — so they are here, defaulted for English, and replaceable.
 *
 * @param separator between the names of two sets in an intersection.
 * @param exclusiveSuffix appended to a set's name for its exclusive region.
 */
class SetRegionNaming(
    val separator: String = " & ",
    val exclusiveSuffix: String = " only",
)

/**
 * A Venn diagram.
 *
 * ```kotlin
 * VennDiagram(
 *     sets = listOf(
 *         SetDefinition(id = "android", label = "Android", value = 200),
 *         SetDefinition(id = "ios", label = "iOS", value = 160),
 *     ),
 *     intersections = listOf(
 *         SetIntersection(sets = setOf("android", "ios"), value = 70),
 *     ),
 *     modifier = Modifier.fillMaxWidth().height(280.dp),
 * )
 * ```
 *
 * ### What a Venn diagram is
 *
 * A template for **all** the combinations the sets could produce, drawn whether
 * or not anything is in them. Three sets get seven regions even if two of them
 * share nothing — the empty region is still there, and a reader tapping it is
 * told it is empty. That is the right picture when the question is *what could
 * overlap*; when the question is *what does*, reach for [EulerDiagram].
 *
 * ### The values
 *
 * `value` on a set is its **total**, and an intersection's value is how many
 * items are in *at least* those sets. The exclusive regions — "Android only" —
 * are derived, so the numbers in the picture always add up to the union. Stating
 * exclusive counts directly is the commonest way a hand-built Venn diagram ends
 * up self-contradictory.
 *
 * @param sizing [SetSizing.Conceptual] draws equal, readable shapes;
 *   [SetSizing.Proportional] sizes them by cardinality and solves the overlaps.
 */
@Suppress("LongParameterList")
@Composable
fun VennDiagram(
    sets: List<SetDefinition>,
    modifier: Modifier = Modifier,
    intersections: List<SetIntersection> = emptyList(),
    containments: List<SetContainment> = emptyList(),
    sizing: SetSizing = SetSizing.Conceptual,
    validation: SetValidationMode = SetValidationMode.Strict,
    colorMode: SetColorMode = SetColorMode.Blend,
    fillAlpha: Float = DEFAULT_FILL_ALPHA,
    focusMode: SetFocusMode = SetFocusMode.None,
    naming: SetRegionNaming = SetRegionNaming(),
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    showValues: Boolean = true,
    legend: LegendPosition = LegendPosition.None,
    animation: ChartAnimation = ChartAnimation.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    layoutConfig: SetLayoutConfig = SetLayoutConfig.Default,
    state: ChartState<SetRegion> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<SetRegion>?) -> Unit)? = null,
    setStyle: (SetDefinition) -> SetStyle? = { it.style },
    intersectionStyle: (SetRegion) -> SetStyle? = { null },
    setLabel: (@Composable (SetLabelScope) -> Unit)? = null,
    regionLabel: (@Composable (SetRegionScope) -> Unit)? = null,
    regionContent: (@Composable (SetRegionScope) -> Unit)? = null,
    overflow: SetContentOverflow = SetContentOverflow.Hide,
    tooltip: (@Composable (ChartTooltipData<SetRegion>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter, showSeriesNames = false)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent("No sets") },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val data = remember(sets, intersections, containments, validation) {
        SetAnalyzer.analyze(sets, intersections, containments, validation)
    }
    SetDiagram(
        data = data,
        modifier = modifier,
        layout = SetDiagramLayout.Venn(sizing),
        colorMode = colorMode,
        fillAlpha = fillAlpha,
        focusMode = focusMode,
        naming = naming,
        valueFormatter = valueFormatter,
        showValues = showValues,
        legend = legend,
        animation = animation,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        renderMode = renderMode,
        staticOptions = staticOptions,
        layoutConfig = layoutConfig,
        state = state,
        onSelectionChanged = onSelectionChanged,
        setStyle = setStyle,
        intersectionStyle = intersectionStyle,
        setLabel = setLabel,
        regionLabel = regionLabel,
        regionContent = regionContent,
        overflow = overflow,
        tooltip = tooltip,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}

/**
 * A Venn diagram over real collections.
 *
 * ```kotlin
 * VennDiagram(
 *     sets = listOf(
 *         SetItems("android", "Android", androidUsers),
 *         SetItems("ios", "iOS", iosUsers),
 *     ),
 *     itemKey = User::id,
 * )
 * ```
 *
 * Every intersection is counted rather than stated, so the numbers cannot
 * disagree with each other. The collections are read once and reduced to counts;
 * the diagram never holds them — see [SetAnalyzer.fromItems], including what
 * happens to an item that appears twice in one collection.
 */
@Suppress("LongParameterList")
@Composable
fun <T> VennDiagram(
    sets: List<SetItems<T>>,
    itemKey: (T) -> Any?,
    modifier: Modifier = Modifier,
    sizing: SetSizing = SetSizing.Conceptual,
    validation: SetValidationMode = SetValidationMode.Strict,
    colorMode: SetColorMode = SetColorMode.Blend,
    fillAlpha: Float = DEFAULT_FILL_ALPHA,
    naming: SetRegionNaming = SetRegionNaming(),
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    showValues: Boolean = true,
    animation: ChartAnimation = ChartAnimation.Default,
    state: ChartState<SetRegion> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<SetRegion>?) -> Unit)? = null,
    setLabel: (@Composable (SetLabelScope) -> Unit)? = null,
    regionLabel: (@Composable (SetRegionScope) -> Unit)? = null,
    regionContent: (@Composable (SetRegionScope) -> Unit)? = null,
    tooltip: (@Composable (ChartTooltipData<SetRegion>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter, showSeriesNames = false)
    },
) {
    val data = remember(sets, itemKey, validation) {
        SetAnalyzer.fromItems(sets, itemKey, validation)
    }
    SetDiagram(
        data = data,
        modifier = modifier,
        layout = SetDiagramLayout.Venn(sizing),
        colorMode = colorMode,
        fillAlpha = fillAlpha,
        naming = naming,
        valueFormatter = valueFormatter,
        showValues = showValues,
        animation = animation,
        state = state,
        onSelectionChanged = onSelectionChanged,
        setLabel = setLabel,
        regionLabel = regionLabel,
        regionContent = regionContent,
        tooltip = tooltip,
    )
}

/**
 * An Euler diagram: only the relationships that actually occur.
 *
 * ```kotlin
 * EulerDiagram(
 *     sets = listOf(
 *         SetDefinition("animals", "Animals", 100),
 *         SetDefinition("mammals", "Mammals", 40),
 *         SetDefinition("plants", "Plants", 60),
 *     ),
 *     intersections = listOf(
 *         SetIntersection(setOf("animals", "mammals"), 40),  // every mammal is an animal
 *     ),
 * )
 * ```
 *
 * Mammals is drawn **inside** Animals, because the data says every mammal is an
 * animal; Plants is drawn beside them, touching nothing, because the data says
 * they share nothing. A Venn diagram of the same numbers would draw all seven
 * regions, including "a mammal that is a plant", and report zero for it.
 *
 * ### Choose Euler when the structure is the message
 *
 * Containment, disjointness and nesting are what an Euler diagram shows and what
 * a Venn diagram hides behind empty regions. Choose Venn when every combination
 * is worth discussing even if it happens to be empty — a template for a
 * conversation rather than a report.
 *
 * ### It is an approximation, and it says so
 *
 * Not every set system can be drawn exactly with circles. The layout solves for
 * the stated overlaps within a bounded number of steps and reports how close it
 * got; see [SetDiagramLayout] and the README's limitations.
 */
@Suppress("LongParameterList")
@Composable
fun EulerDiagram(
    sets: List<SetDefinition>,
    modifier: Modifier = Modifier,
    intersections: List<SetIntersection> = emptyList(),
    containments: List<SetContainment> = emptyList(),
    sizing: SetSizing = SetSizing.Proportional,
    validation: SetValidationMode = SetValidationMode.Strict,
    colorMode: SetColorMode = SetColorMode.BySet,
    fillAlpha: Float = DEFAULT_FILL_ALPHA,
    focusMode: SetFocusMode = SetFocusMode.None,
    naming: SetRegionNaming = SetRegionNaming(),
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    showValues: Boolean = false,
    legend: LegendPosition = LegendPosition.None,
    animation: ChartAnimation = ChartAnimation.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    layoutConfig: SetLayoutConfig = SetLayoutConfig.Default,
    state: ChartState<SetRegion> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<SetRegion>?) -> Unit)? = null,
    setStyle: (SetDefinition) -> SetStyle? = { it.style },
    intersectionStyle: (SetRegion) -> SetStyle? = { null },
    setLabel: (@Composable (SetLabelScope) -> Unit)? = null,
    regionLabel: (@Composable (SetRegionScope) -> Unit)? = null,
    regionContent: (@Composable (SetRegionScope) -> Unit)? = null,
    overflow: SetContentOverflow = SetContentOverflow.Hide,
    tooltip: (@Composable (ChartTooltipData<SetRegion>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter, showSeriesNames = false)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent("No sets") },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val data = remember(sets, intersections, containments, validation) {
        SetAnalyzer.analyze(sets, intersections, containments, validation)
    }
    SetDiagram(
        data = data,
        modifier = modifier,
        layout = SetDiagramLayout.Euler(sizing),
        colorMode = colorMode,
        fillAlpha = fillAlpha,
        focusMode = focusMode,
        naming = naming,
        valueFormatter = valueFormatter,
        showValues = showValues,
        legend = legend,
        animation = animation,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        renderMode = renderMode,
        staticOptions = staticOptions,
        layoutConfig = layoutConfig,
        state = state,
        onSelectionChanged = onSelectionChanged,
        setStyle = setStyle,
        intersectionStyle = intersectionStyle,
        setLabel = setLabel,
        regionLabel = regionLabel,
        regionContent = regionContent,
        overflow = overflow,
        tooltip = tooltip,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
    )
}

/** An Euler diagram over real collections. */
@Suppress("LongParameterList")
@Composable
fun <T> EulerDiagram(
    sets: List<SetItems<T>>,
    itemKey: (T) -> Any?,
    modifier: Modifier = Modifier,
    sizing: SetSizing = SetSizing.Proportional,
    colorMode: SetColorMode = SetColorMode.BySet,
    naming: SetRegionNaming = SetRegionNaming(),
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    showValues: Boolean = false,
    animation: ChartAnimation = ChartAnimation.Default,
    state: ChartState<SetRegion> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<SetRegion>?) -> Unit)? = null,
    setLabel: (@Composable (SetLabelScope) -> Unit)? = null,
    regionLabel: (@Composable (SetRegionScope) -> Unit)? = null,
    regionContent: (@Composable (SetRegionScope) -> Unit)? = null,
) {
    val data = remember(sets, itemKey) { SetAnalyzer.fromItems(sets, itemKey) }
    SetDiagram(
        data = data,
        modifier = modifier,
        layout = SetDiagramLayout.Euler(sizing),
        colorMode = colorMode,
        naming = naming,
        valueFormatter = valueFormatter,
        showValues = showValues,
        animation = animation,
        state = state,
        onSelectionChanged = onSelectionChanged,
        setLabel = setLabel,
        regionLabel = regionLabel,
        regionContent = regionContent,
    )
}

/**
 * The generic set diagram, over data you have already analysed.
 *
 * The lower level of the three: [VennDiagram] and [EulerDiagram] are this with a
 * layout strategy chosen and their arguments named for the diagram their callers
 * have in mind. Reach for it when the strategy is a runtime choice — a toggle
 * between Venn and Euler in the same screen — or when the arrangement is
 * supplied outright with [SetDiagramLayout.Custom].
 *
 * ```kotlin
 * SetDiagram(
 *     data = SetAnalyzer.analyze(sets, intersections),
 *     layout = if (showEuler) SetDiagramLayout.Euler else SetDiagramLayout.Venn,
 * )
 * ```
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun SetDiagram(
    data: SetDiagramData,
    modifier: Modifier = Modifier,
    layout: SetDiagramLayout = SetDiagramLayout.Venn,
    colorMode: SetColorMode = SetColorMode.Blend,
    fillAlpha: Float = DEFAULT_FILL_ALPHA,
    focusMode: SetFocusMode = SetFocusMode.None,
    naming: SetRegionNaming = SetRegionNaming(),
    valueFormatter: ChartValueFormatter = ChartValueFormatter.Raw,
    showValues: Boolean = true,
    legend: LegendPosition = LegendPosition.None,
    animation: ChartAnimation = ChartAnimation.Default,
    accessibility: ChartAccessibility = ChartAccessibility.Auto,
    accessibilitySummary: (() -> String)? = null,
    renderMode: ChartRenderMode = ChartRenderMode.Interactive,
    staticOptions: ChartStaticOptions = ChartStaticOptions.Default,
    layoutConfig: SetLayoutConfig = SetLayoutConfig.Default,
    state: ChartState<SetRegion> = rememberChartState(),
    onSelectionChanged: ((ChartSelection<SetRegion>?) -> Unit)? = null,
    setStyle: (SetDefinition) -> SetStyle? = { it.style },
    intersectionStyle: (SetRegion) -> SetStyle? = { null },
    setLabel: (@Composable (SetLabelScope) -> Unit)? = null,
    regionLabel: (@Composable (SetRegionScope) -> Unit)? = null,
    regionContent: (@Composable (SetRegionScope) -> Unit)? = null,
    overflow: SetContentOverflow = SetContentOverflow.Hide,
    tooltip: (@Composable (ChartTooltipData<SetRegion>) -> Unit)? = {
        ChartDefaults.Tooltip(it, valueFormatter = valueFormatter, showSeriesNames = false)
    },
    isLoading: Boolean = false,
    error: Throwable? = null,
    loadingContent: @Composable () -> Unit = { DefaultLoadingContent() },
    emptyContent: @Composable () -> Unit = { DefaultEmptyContent("No sets") },
    errorContent: @Composable (Throwable) -> Unit = { DefaultErrorContent(it) },
) {
    val theme = ChartKitTheme.current
    val density = LocalDensity.current

    // The solve happens once per data-and-strategy change and is then reused for
    // every resize, selection, hover, animation frame and recomposition. It is
    // the most expensive thing a set diagram does, and running it per frame is
    // the mistake that would make one unusable.
    val geometry = remember(data, layout, layoutConfig) {
        SetGeometryCache(data, layout, layoutConfig)
    }

    val colors = remember(data, theme.colors) {
        data.sets.mapIndexed { index, set -> set.id to theme.colors.seriesColor(index) }.toMap()
    }

    val namer: (SetRegion) -> String = remember(data, naming) {
        { region -> defaultRegionName(region, data, naming.exclusiveSuffix, naming.separator) }
    }

    val padding = with(density) { theme.dimensions.setDiagramPadding.toPx() }

    PlanarChartCore(
        layers = { coordinates ->
            val fitted = geometry.forPlot(coordinates.contentBounds, padding)
            listOf(
                SetDiagramLayer(
                    id = "set-diagram",
                    data = data,
                    layout = fitted.layout,
                    regions = fitted.regions,
                    setColors = colors,
                    colorMode = colorMode,
                    fillAlpha = fillAlpha,
                    focusMode = focusMode,
                    regionStyle = intersectionStyle,
                    setStyle = { id -> data.set(id)?.let(setStyle) },
                    seriesId = ChartDefaults.SINGLE_SERIES_ID,
                    seriesName = "",
                    valueFormatter = valueFormatter,
                    regionNamer = namer,
                ),
            )
        },
        modifier = modifier,
        legend = legend,
        legendItems = remember(data, colors) {
            data.sets.mapIndexed { index, set ->
                ChartKeyItem(
                    id = set.id,
                    label = set.label,
                    paletteIndex = index,
                    colorOverride = null,
                )
            }
        },
        animation = animation,
        tapSelects = true,
        clearOnTapOutside = true,
        state = state.asErased(),
        valueFormatter = valueFormatter,
        accessibility = accessibility,
        accessibilitySummary = accessibilitySummary,
        renderMode = renderMode,
        staticOptions = staticOptions,
        isEmpty = data.isEmpty,
        isLoading = isLoading,
        error = error,
        loadingContent = loadingContent,
        emptyContent = emptyContent,
        errorContent = errorContent,
        onSelectionChanged = { erased -> onSelectionChanged?.invoke(erased?.asTyped()) },
        tooltip = tooltip?.let { slot -> { erased -> slot(erased.asTypedTooltip()) } },
        // Set labels, region labels, icon groups and logos are Compose content,
        // not canvas drawing: they measure text, hold images, respond to the
        // theme and carry their own semantics, and rasterising them would lose
        // all four. The shapes stay on the canvas; everything that has to be
        // *composed* lives here.
        overlayContent = { coordinates ->
            SetDiagramOverlay(
                data = data,
                geometry = geometry.forPlot(coordinates.contentBounds, padding),
                coordinates = coordinates,
                selection = state.selection,
                namer = namer,
                valueFormatter = valueFormatter,
                showValues = showValues,
                overflow = overflow,
                setLabel = setLabel,
                regionLabel = regionLabel,
                regionContent = regionContent,
            )
        },
        contentInsets = ChartInsets(0f, 0f, 0f, 0f),
    )
}

/**
 * The Compose layer over the canvas: set names, region labels and region content.
 *
 * Laid out by Compose at anchors the geometry supplied, so an icon knows how
 * much room it has before it decides to appear. Takes no pointer input, so a
 * label never steals a tap from the region under it.
 */
@Suppress("LongParameterList")
@Composable
private fun SetDiagramOverlay(
    data: SetDiagramData,
    geometry: FittedSetGeometry,
    coordinates: PlanarCoordinates,
    selection: ChartSelection<SetRegion>?,
    namer: (SetRegion) -> String,
    valueFormatter: ChartValueFormatter,
    showValues: Boolean,
    overflow: SetContentOverflow,
    setLabel: (@Composable (SetLabelScope) -> Unit)?,
    regionLabel: (@Composable (SetRegionScope) -> Unit)?,
    regionContent: (@Composable (SetRegionScope) -> Unit)?,
) {
    if (geometry.layout.isEmpty || coordinates.plotArea.isEmpty) return
    val density = LocalDensity.current
    val theme = ChartKitTheme.current
    val selectedMemberships = selection?.set?.memberships

    Box(Modifier) {
        data.sets.forEachIndexed { index, set ->
            val anchor = geometry.setAnchor(
                id = set.id,
                containsOthers = data.relationships.contained(set.id).isNotEmpty(),
                valueOf = { other -> data.set(other)?.value ?: Double.MAX_VALUE },
            ) ?: return@forEachIndexed
            val scope = SetLabelScope(
                set = set,
                index = index,
                anchor = anchor.anchor,
                clearance = anchor.clearance,
                shapeBounds = anchor.bounds,
                isSelected = selectedMemberships?.contains(set.id) == true,
            )
            SetOverlayItem(
                anchor = anchor.anchor,
                available = with(density) { (anchor.clearance * 2f).toDp() },
                overflow = overflow,
            ) {
                if (setLabel != null) {
                    setLabel(scope)
                } else {
                    Text(
                        text = set.label,
                        style = theme.typography.setLabel.copy(
                            color = theme.colors.set.label,
                            // A halo rather than a chosen contrast colour: the
                            // fill under a label in blend mode is a mix nobody
                            // computed in advance.
                            shadow = Shadow(color = theme.colors.set.labelHalo, blurRadius = 6f),
                        ),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        geometry.regions.drawable().forEach { region ->
            val model = data.regionOrEmpty(region.memberships)
            val scope = SetRegionScope(
                region = model,
                geometry = region,
                label = namer(model),
                isSelected = selectedMemberships == region.memberships,
            )
            val available = with(density) { (region.clearance * 2f).toDp() }

            if (regionContent != null) {
                SetOverlayItem(region.anchor, available, SetContentOverflow.Allow) {
                    regionContent(scope)
                }
            }

            val labelSlot = regionLabel
            when {
                labelSlot != null -> SetOverlayItem(region.anchor, available, overflow) {
                    labelSlot(scope)
                }

                // The default region label is the *value*, not the name: the
                // name is already carried by the set labels around it, and a
                // three-set Venn with "A & B" written in every overlap is
                // unreadable. Set names go outside, counts go inside.
                showValues && model.value > 0.0 && region.clearance >= with(density) {
                    theme.dimensions.setMinLabelClearance.toPx()
                } -> SetOverlayItem(region.anchor, available, overflow) {
                    Text(
                        text = valueFormatter.format(model.value),
                        style = theme.typography.setRegionLabel.copy(
                            color = theme.colors.set.regionLabel,
                            shadow = Shadow(color = theme.colors.set.labelHalo, blurRadius = 6f),
                        ),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * The solved arrangement, fitted to one plot size.
 *
 * The unit-space solve is done once by [SetGeometryCache]; this is what a
 * particular plot rectangle turned it into, together with the sampled regions
 * that labels and content are placed against.
 */
internal class FittedSetGeometry(
    val layout: SetLayout,
    val regions: RegionGeometryIndex,
) {
    /**
     * Where a set's own name goes.
     *
     * ### The region where this set is the innermost thing
     *
     * Not `{id}`. A set nested inside another has **no** region belonging to it
     * alone — every point of the British Islands is also in the British Isles,
     * so its ring is the region `{isles, islands}`. Looking up `{id}` finds
     * nothing for any nested set, and falling back to the shape's centre puts
     * four labels from four nesting levels in one heap in the middle.
     *
     * What a set's name should mark is the part of the diagram where *this* set
     * is the tightest one covering the point — the region whose smallest member
     * is this set. For a top-level set that is its exclusive region; for a
     * nested one it is its ring.
     *
     * ### And then pushed out of the way
     *
     * A set that contains others is labelled near the **bottom** of its own
     * shape, which is the conventional place and the one reliably clear of the
     * children sitting inside it. A set that contains nothing is pushed away
     * from the diagram's centre, which is where a Venn diagram's set names go
     * and, less decoratively, is what stops the name landing on the region's own
     * value at the same anchor.
     */
    fun setAnchor(
        id: String,
        containsOthers: Boolean = false,
        valueOf: (String) -> Double = { Double.MAX_VALUE },
    ): RegionGeometry? {
        val own = ownRegion(id, valueOf)
        if (own != null) {
            val bottom = if (containsOthers) bottomOfRing(id, own, valueOf) else null
            return own.copy(anchor = bottom ?: pushedOutward(own))
        }

        // Nothing at all — a set with no visible area of its own, which happens
        // when another set covers it exactly. Its centre is the only place left.
        val shape = layout.shape(id) ?: return null
        val box = shape.bounds()
        return RegionGeometry(
            memberships = setOf(id),
            anchor = ChartOffset(shape.centerX.toFloat(), shape.centerY.toFloat()),
            clearance = ((box[2] - box[0]) / 4).toFloat(),
            area = shape.area.toFloat(),
            bounds = ChartRect(
                box[0].toFloat(),
                box[1].toFloat(),
                box[2].toFloat(),
                box[3].toFloat(),
            ),
        )
    }

    /** The roomiest region in which [id] is the smallest set present. */
    private fun ownRegion(id: String, valueOf: (String) -> Double): RegionGeometry? =
        regions.regions.values
            .filter { it.memberships.contains(id) && innermost(it.memberships, valueOf) == id }
            .maxByOrNull { it.clearance }

    /** The smallest set covering a region, which is the one it most belongs to. */
    private fun innermost(memberships: Set<String>, valueOf: (String) -> Double): String? =
        memberships.minByOrNull { valueOf(it) }

    /**
     * The lowest point of a set's own ring where it is still the innermost set.
     *
     * Verified rather than assumed: each candidate is tested against the
     * layout's own membership, so a ring too thin at the bottom for a label
     * falls back to the deepest point instead of putting the name inside one of
     * its children.
     */
    private fun bottomOfRing(
        id: String,
        region: RegionGeometry,
        valueOf: (String) -> Double,
    ): ChartOffset? {
        val shape = layout.shape(id) ?: return null
        val box = shape.bounds()
        val height = (box[3] - box[1]).toFloat()
        val width = (box[2] - box[0]).toFloat()
        val centreX = shape.centerX.toFloat()

        // Upward from the bottom edge, and a little either side of centre: a
        // ring's bottom is often occupied by a child, and the next place along
        // is usually free. A fixed, ordered, bounded sequence, so the answer
        // does not depend on anything but the geometry.
        for (step in 0 until BOTTOM_STEPS) {
            val y = box[3].toFloat() - region.clearance - step * height * BOTTOM_STEP_FRACTION
            if (y <= box[1]) break
            for (offset in listOf(0f, -0.3f, 0.3f)) {
                val x = centreX + offset * width
                val membership = layout.membershipAt(x, y)
                if (membership.contains(id) && innermost(membership, valueOf) == id) {
                    return ChartOffset(x, y)
                }
            }
        }
        return null
    }

    /** The whole arrangement's centre, for pushing labels away from it. */
    private val centre: ChartOffset by lazy(LazyThreadSafetyMode.NONE) {
        val box = io.devkit.chartkit.set.SetGeometryUtils.boundsOf(layout.shapes.values)
        if (box == null) {
            ChartOffset.Zero
        } else {
            ChartOffset(((box[0] + box[2]) / 2).toFloat(), ((box[1] + box[3]) / 2).toFloat())
        }
    }

    private fun pushedOutward(region: RegionGeometry): ChartOffset {
        val dx = region.anchor.x - centre.x
        val dy = region.anchor.y - centre.y
        val length = kotlin.math.hypot(dx, dy)
        if (length < 1f || region.clearance <= 0f) return region.anchor
        val distance = region.clearance * LABEL_OUTWARD
        return ChartOffset(
            x = region.anchor.x + dx / length * distance,
            y = region.anchor.y + dy / length * distance,
        )
    }
}

/**
 * Solves once, fits many times.
 *
 * ```text
 * data + strategy ──▶ unit-space solve   (once, expensive)
 *          plot size ──▶ uniform fit     (per size, trivial)
 *                    ──▶ region sampling (per size, moderate — memoised)
 * ```
 *
 * A rotation, a window resize or a split-screen change re-fits and re-samples; a
 * tooltip, a selection, a hover, a colour-mode switch and an animation frame do
 * neither. That separation is what keeps the diagram responsive, and it is why
 * the solve lives in a `remember` outside the chart rather than inside the
 * layer-building lambda, which Compose calls far more often.
 */
internal class SetGeometryCache(
    private val data: SetDiagramData,
    layout: SetDiagramLayout,
    config: SetLayoutConfig,
) {
    /** The unit-space arrangement. Solved on first use, then never again. */
    val solved: SetLayout by lazy(LazyThreadSafetyMode.NONE) {
        layout.engine().layout(data, config)
    }

    private var lastPlot: ChartRect? = null
    private var lastPadding: Float = -1f
    private var lastResult: FittedSetGeometry? = null

    fun forPlot(plot: ChartRect, padding: Float): FittedSetGeometry {
        val cached = lastResult
        if (cached != null && lastPlot == plot && lastPadding == padding) return cached
        val fitted = solved.fitInto(plot, padding)
        val result = FittedSetGeometry(fitted, RegionGeometryIndex.of(fitted))
        lastPlot = plot
        lastPadding = padding
        lastResult = result
        return result
    }

    /** How well the solve reproduced the data. For diagnostics and tests. */
    fun quality() = solved.quality
}

/**
 * How opaque a set's fill is by default.
 *
 * Opaque enough that a region's colour is the colour the legend promised, and
 * light enough that an outline drawn over it still reads. Regions are filled
 * individually rather than stacked, so this is not doing the work of showing
 * overlaps — [SetColorMode] is.
 */
private const val DEFAULT_FILL_ALPHA: Float = 0.85f

/**
 * How far a set's name is pushed out of its exclusive region's centre, as a
 * fraction of the room that region has.
 *
 * Enough to clear the region's value, which is anchored at the same point;
 * little enough that the name stays inside the region it names.
 */
private const val LABEL_OUTWARD: Float = 0.55f

/** How many rows up from a ring's bottom edge a label placement is tried. */
private const val BOTTOM_STEPS: Int = 8

/** How far apart those rows are, as a fraction of the shape's height. */
private const val BOTTOM_STEP_FRACTION: Float = 0.06f
