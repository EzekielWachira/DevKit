package io.devkit.chartkit.layer.set

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSelectionDetails
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.set.RegionGeometryIndex
import io.devkit.chartkit.set.SetDiagramData
import io.devkit.chartkit.set.SetHitTester
import io.devkit.chartkit.set.SetLayout
import io.devkit.chartkit.set.SetRegion
import io.devkit.chartkit.set.SetRegionKind
import io.devkit.chartkit.set.SetShape
import io.devkit.chartkit.set.SetStyle
import io.devkit.chartkit.set.membershipAt

/**
 * How overlapping sets are coloured.
 *
 * The reference behaviour every Venn implementation is measured against, and
 * genuinely two different pictures of the same data — so it is a mode, not a
 * styling detail.
 */
enum class SetColorMode {

    /**
     * Every region takes the colour of the **smallest** set covering it.
     *
     * Smallest, not first-declared: a region belongs most specifically to the
     * tightest set that contains it. On a nested Euler diagram that is what
     * makes the picture readable at all — every region of the British Isles
     * contains the British Isles, so colouring by the outermost set would paint
     * the whole diagram one colour. Ties are broken by declaration order, so it
     * stays deterministic.
     */
    BySet,

    /**
     * Every region takes the mix of the colours of the sets covering it.
     *
     * ```text
     * A ∩ B  →  mix(A, B)
     * A ∩ B ∩ C  →  mix(A, B, C)
     * ```
     *
     * The mix is the **mean of the member colours** in sRGB, computed from the
     * membership set. Two consequences follow, and both are the point:
     *
     * - It is **order independent**. Painting translucent circles on top of one
     *   another — which is how most implementations do this — makes the overlap
     *   depend on which circle was drawn last, so the same region has two
     *   different colours depending on declaration order. Here it cannot.
     * - It is **opaque**. Nothing beneath a set diagram shows through a region,
     *   so the colour a reader sees is the colour the legend promised rather
     *   than that colour composited over whatever the surface happened to be.
     */
    Blend,
}

/** What happens to sets that are not part of the current selection. */
enum class SetFocusMode {

    /** Nothing. The selection is shown by its own outline and wash. */
    None,

    /**
     * Regions outside the selection are dimmed.
     *
     * Useful on a dense diagram where the selected region is a sliver: dimming
     * the other nine is easier to read than outlining the one. It changes only
     * appearance — the data, the regions and the hit testing are untouched.
     */
    DimUnrelated,
}

/**
 * Draws a set diagram.
 *
 * ```text
 * shapes ──▶ region paths (∩ members, − non-members) ──▶ fill per region
 *        └─▶ set outlines
 *        └─▶ selection emphasis
 * ```
 *
 * ### Regions are drawn as regions
 *
 * The easy implementation fills each set's whole circle with a translucent
 * colour and lets the overlaps come out darker. That produces a picture, but not
 * a *diagram*: nothing in it is the region `A ∩ B`, so nothing can be given the
 * intersection's own colour, nothing can be emphasised on selection, and the
 * result depends on the order the circles were painted.
 *
 * So each logical region is built as a real path — the intersection of the
 * shapes it belongs to, minus every shape it does not — and filled once, with
 * its own colour. That is what makes [SetColorMode.Blend] order independent,
 * what lets `intersectionStyle` give one overlap a semantic colour, and what
 * lets a selection highlight exactly the region that was tapped.
 *
 * ### One canvas, not one composable per set
 *
 * Shapes, fills, boolean paths, outlines and the selection all happen here in
 * one draw pass. Labels, icons, logos and arbitrary region content are Compose
 * overlays instead, because they need measurement, theming, their own semantics
 * and sometimes their own click targets — none of which a `DrawScope` has.
 */
@Suppress("LongParameterList")
internal class SetDiagramLayer(
    override val id: String,
    private val data: SetDiagramData,
    private val layout: SetLayout,
    private val regions: RegionGeometryIndex,
    private val setColors: Map<String, Color>,
    private val colorMode: SetColorMode,
    private val fillAlpha: Float,
    private val focusMode: SetFocusMode,
    private val regionStyle: (SetRegion) -> SetStyle?,
    private val setStyle: (String) -> SetStyle?,
    private val seriesId: String,
    private val seriesName: String,
    private val valueFormatter: ChartValueFormatter,
    private val regionNamer: (SetRegion) -> String,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    /**
     * The path per set, and the boolean region paths, built once per layout.
     *
     * Keyed on the layout itself: a selection, a hover, a colour-mode switch and
     * a tooltip all reuse them. Only a new arrangement — new data, a resize, a
     * different layout strategy — rebuilds. Boolean path arithmetic is the most
     * expensive thing this layer does, and doing it per frame would be the one
     * mistake that makes a five-set diagram stutter.
     */
    private var cachedShapes: Map<String, Path>? = null
    private var cachedRegions: List<Pair<Set<String>, Path>>? = null
    private var cacheKey: SetLayout? = null

    private fun shapePaths(): Map<String, Path> {
        buildCache()
        return cachedShapes.orEmpty()
    }

    private fun regionPaths(): List<Pair<Set<String>, Path>> {
        buildCache()
        return cachedRegions.orEmpty()
    }

    private fun buildCache() {
        if (cacheKey === layout && cachedShapes != null) return
        val shapes = layout.order.mapNotNull { id ->
            layout.shape(id)?.let { id to pathOf(it) }
        }.toMap()

        // Only regions the sampler actually found are built. A Venn diagram of
        // five sets has thirty-one theoretical regions and rather fewer real
        // ones; asking for a path per theoretical region would spend most of the
        // work producing empty paths.
        val built = ArrayList<Pair<Set<String>, Path>>(regions.regions.size)
        regions.regions.keys
            .sortedWith(compareBy({ it.size }, { it.sorted().joinToString(" ") }))
            .forEach { membership ->
                val path = regionPath(membership, shapes) ?: return@forEach
                built += membership to path
            }

        cachedShapes = shapes
        cachedRegions = built
        cacheKey = layout
    }

    /**
     * The path for exactly this membership.
     *
     * `null` when the boolean arithmetic produced nothing — two tangent circles,
     * a region whose geometry vanished at this size, or an operation the path
     * engine declined. All three are ordinary in a diagram being animated or
     * resized, so they end the region rather than the frame.
     */
    private fun regionPath(membership: Set<String>, shapes: Map<String, Path>): Path? {
        val members = membership.mapNotNull { shapes[it] }
        if (members.isEmpty() || members.size != membership.size) return null

        var result = Path().apply { addPath(members.first()) }
        members.drop(1).forEach { other ->
            val next = Path()
            if (!next.op(result, other, PathOperation.Intersect)) return null
            result = next
        }
        layout.order.forEach { id ->
            if (id in membership) return@forEach
            val other = shapes[id] ?: return@forEach
            val next = Path()
            if (!next.op(result, other, PathOperation.Difference)) return null
            result = next
        }
        return if (result.isEmpty) null else result
    }

    private fun pathOf(shape: SetShape): Path = when (shape) {
        is SetShape.Circle -> Path().apply {
            addOval(
                Rect(
                    left = (shape.centerX - shape.radius).toFloat(),
                    top = (shape.centerY - shape.radius).toFloat(),
                    right = (shape.centerX + shape.radius).toFloat(),
                    bottom = (shape.centerY + shape.radius).toFloat(),
                ),
            )
        }

        is SetShape.Ellipse -> Path().apply {
            addOval(
                Rect(
                    left = (shape.centerX - shape.radiusX).toFloat(),
                    top = (shape.centerY - shape.radiusY).toFloat(),
                    right = (shape.centerX + shape.radiusX).toFloat(),
                    bottom = (shape.centerY + shape.radiusY).toFloat(),
                ),
            )
            if (shape.rotation != 0.0) {
                // Rotated about its own centre rather than about the origin,
                // which is why the translation brackets the rotation.
                val matrix = Matrix()
                matrix.translate(shape.centerX.toFloat(), shape.centerY.toFloat())
                matrix.rotateZ((shape.rotation * 180.0 / Math.PI).toFloat())
                matrix.translate(-shape.centerX.toFloat(), -shape.centerY.toFloat())
                transform(matrix)
            }
        }
    }

    /** The colour of one region, before any caller override. */
    private fun fillFor(membership: Set<String>): Color {
        val override = regionStyle(data.regionOrEmpty(membership))?.fill
        if (override != null) return override
        val members = layout.order.filter { it in membership }
        if (members.isEmpty()) return Color.Transparent
        return when (colorMode) {
            SetColorMode.BySet -> {
                val owner = members.minByOrNull { data.set(it)?.value ?: Double.MAX_VALUE }
                    ?: members.first()
                setStyle(owner)?.fill ?: setColors[owner] ?: Color.Transparent
            }

            SetColorMode.Blend -> blendSetColors(members.map { setStyle(it)?.fill ?: setColors[it] ?: Color.Transparent })
        }
    }

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        if (layout.isEmpty) return
        val reveal = context.reveal.coerceIn(0f, 1f)
        if (reveal <= 0f) return
        val colors = context.colors.set
        val selected = selectedMembership(context)

        regionPaths().forEach { (membership, path) ->
            val region = data.regionOrEmpty(membership)
            val style = regionStyle(region)
            val alpha = style?.fillAlpha ?: fillAlpha
            val color = fillFor(membership)
            // The reveal is a fade, not a scale. Growing the shapes would make
            // the geometry disagree with the hit testing for the length of the
            // animation, and a tap during the intro would select the wrong
            // region — or nothing.
            scope.drawPath(path, color.copy(alpha = color.alpha * alpha * reveal))

            if (focusMode == SetFocusMode.DimUnrelated && selected != null &&
                membership.none { it in selected }
            ) {
                scope.drawPath(path, colors.dim.copy(alpha = colors.dim.alpha * reveal))
            }
        }

        val outlineWidth = context.px(context.dimensions.setOutlineWidth)
        if (outlineWidth > 0f) {
            shapePaths().forEach { (id, path) ->
                val style = setStyle(id)
                val color = style?.outline ?: colors.outline
                val width = style?.outlineWidthDp?.let { context.px(androidx.compose.ui.unit.Dp(it)) }
                    ?: outlineWidth
                scope.drawPath(
                    path = path,
                    color = color.copy(alpha = color.alpha * reveal),
                    style = Stroke(width = width),
                )
            }
        }

        // Drawn last, over every neighbour, so a selected region's outline is
        // not half-covered by whichever region happens to come after it.
        if (selected != null && reveal >= 1f) {
            regionPaths().firstOrNull { it.first == selected }?.let { (_, path) ->
                scope.drawPath(path, colors.selectionFill)
                scope.drawPath(
                    path = path,
                    color = colors.selectedOutline,
                    style = Stroke(width = context.px(context.dimensions.setSelectedOutlineWidth)),
                )
            }
        }
    }

    private fun selectedMembership(context: ChartRenderContext): Set<String>? =
        context.selection
            ?.takeIf { it.seriesId == seriesId }
            ?.let { (it.details as? ChartSelectionDetails.Set)?.memberships }

    /**
     * The region under a pointer.
     *
     * Delegates to [SetHitTester], which decides membership from the geometry
     * rather than from draw order — see its documentation for why that
     * distinction is the whole of correct set-diagram interaction.
     */
    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val region = SetHitTester.regionAt(point, layout, data) ?: return null
        return selectionFor(region, point)
    }

    /** The selection for a region, shared by hit testing and programmatic use. */
    fun selectionFor(region: SetRegion, pointer: ChartOffset): AnyChartSelection {
        val anchor = SetHitTester.anchorFor(region, regions, pointer)
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = layout.order.indexOfFirst { it in region.memberships }.coerceAtLeast(0),
            x = ChartX.Category(regionNamer(region)),
            y = region.value,
            item = region,
            position = anchor,
            details = ChartSelectionDetails.Set(
                memberships = region.memberships,
                kind = region.kind,
                value = region.value,
                totalValue = region.totalValue,
                label = regionNamer(region),
                sets = region.memberships.mapNotNull { data.set(it) },
                isTheoretical = region.isTheoretical,
            ),
        )
    }

    /**
     * Every region, with its exclusive value.
     *
     * The **regions**, not the sets. A reader who cannot see the picture is
     * served by "Android only: 130, Android and iOS: 70" — numbers that
     * partition the data and add up — rather than by the set totals, which
     * overlap and therefore sum to more than there are people.
     */
    override fun describe(): List<ChartLayerSummary> {
        if (data.isEmpty) return emptyList()
        val entries = data.regions
            .filter { it.value > 0.0 }
            .map { region ->
                ChartLayerEntry(
                    label = regionNamer(region),
                    value = region.value,
                    detail = "${regionNamer(region)}: ${valueFormatter.format(region.value)}",
                )
            }
        return listOf(
            ChartLayerSummary(
                seriesId = seriesId,
                seriesName = seriesName,
                pointCount = entries.size,
                entries = entries,
            ),
        )
    }

    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? {
        val details = selection.details as? ChartSelectionDetails.Set ?: return null
        return "${details.label}: ${formatter.format(details.value)}."
    }

}

/**
 * The mean of several colours in sRGB.
 *
 * The mean rather than successive alpha compositing, because the mean is
 * **symmetric**: `blend(a, b)` and `blend(b, a)` are the same colour, so a
 * region's appearance cannot depend on the order its sets were declared in.
 * Compositing is not symmetric, and that asymmetry is exactly the bug this
 * avoids — the same overlap coming out differently because somebody reordered a
 * list.
 *
 * It is not perceptually uniform, as no component-wise sRGB mix is, so a blend
 * of two distant hues passes through a desaturated middle. The theme's palette
 * is built so that neighbouring slots do not.
 */
internal fun blendSetColors(colors: List<Color>): Color {
    val usable = colors.filter { it != Color.Transparent }
    if (usable.isEmpty()) return Color.Transparent
    if (usable.size == 1) return usable.first()
    var red = 0f
    var green = 0f
    var blue = 0f
    var alpha = 0f
    usable.forEach {
        red += it.red
        green += it.green
        blue += it.blue
        alpha += it.alpha
    }
    val count = usable.size.toFloat()
    return Color(
        red = red / count,
        green = green / count,
        blue = blue / count,
        alpha = alpha / count,
    )
}

/** Everything needed to name a region, without assuming a language. */
internal fun defaultRegionName(
    region: SetRegion,
    data: SetDiagramData,
    exclusiveSuffix: String,
    separator: String,
): String {
    val names = region.memberships
        .mapNotNull { data.set(it)?.label ?: it }
        .sortedBy { label -> region.memberships.indexOfFirst { data.set(it)?.label == label } }
    return when {
        names.isEmpty() -> ""
        // "Android only", not "Android": the exclusive region of a set is not
        // the set, and a tooltip that calls it one reports the wrong number
        // beside the right name.
        region.kind == SetRegionKind.Exclusive && data.sets.size > 1 ->
            names.first() + exclusiveSuffix

        else -> names.joinToString(separator)
    }
}
