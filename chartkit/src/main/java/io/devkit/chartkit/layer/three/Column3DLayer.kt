package io.devkit.chartkit.layer.three

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DDiagnostics
import io.devkit.chartkit.three.Chart3DFrame
import io.devkit.chartkit.three.Chart3DHitTest
import io.devkit.chartkit.three.Chart3DLighting
import io.devkit.chartkit.three.Chart3DObject
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Chart3DProjector
import io.devkit.chartkit.three.Chart3DReserve
import io.devkit.chartkit.three.Chart3DRole
import io.devkit.chartkit.three.Chart3DScene
import io.devkit.chartkit.three.Column3DArrangement
import io.devkit.chartkit.three.Chart3DDepth
import io.devkit.chartkit.three.Column3DLayout
import io.devkit.chartkit.three.Column3DLayoutEngine
import io.devkit.chartkit.three.Column3DSeries
import io.devkit.chartkit.three.FaceSide
import io.devkit.chartkit.three.Point3D
import io.devkit.chartkit.three.ProjectedFace
import io.devkit.chartkit.three.Projected2D
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Where a value label sits on a column. */
enum class Column3DLabelPlacement {

    /** No labels. The default: a 3D chart is already dense. */
    None,

    /** Above the column's top face, in the picture plane. */
    Top,

    /** Centred on the front face of the segment, for stacked charts. */
    Inside,

    /**
     * [Inside] where the segment is tall enough to hold the text, [Top]
     * otherwise, and nothing at all when neither fits.
     *
     * Nothing at all is a real outcome, not a failure. A label wider than its
     * own segment overlaps its neighbours, and two overlapping numbers are less
     * readable than none.
     */
    Auto,
}

/** Development-only overlays. Never drawn by any of ChartKit's own samples. */
enum class Chart3DDebug {
    None,

    /** A short spur out of each visible face, along its normal. */
    Normals,

    /** The draw index of each face, so an out-of-order sort is visible. */
    DepthOrder,

    /**
     * Every face outlined, so a tessellated surface shows its segments.
     *
     * The one to reach for on a curved shape: a rim that looks faceted and a
     * rim that is drawn from far too many slivers look identical until the
     * segments are visible.
     */
    Wireframe,
}

/** One series' identity, for the legend, tooltips and the accessibility summary. */
internal class Column3DSeriesInfo(
    val seriesId: String,
    val seriesName: String,
    val paletteIndex: Int,
    val colorOverride: Int?,
    val stackId: String,
    val values: List<Double?>,
    val items: List<Any?>,
    val sourceIndices: List<Int>,
)

/**
 * Grouped and stacked 3D columns.
 *
 * ### One layer, one depth-sorted pass
 *
 * The frame, the grid lines and the columns are drawn from a single projection
 * of a single scene. They have to be: a back wall drawn by one layer and
 * columns drawn by another cannot be interleaved, and the moment a column is
 * further away than a wall — which happens the instant a reader rotates past
 * the wall's plane — the picture is wrong with no way for either layer to know.
 *
 * ### What this layer does not compute
 *
 * Stacking, percent normalisation, sign handling, the value domain, the tick
 * values, the category order and the palette. All of those arrive already
 * decided, from exactly the code that decides them for a 2D bar chart. What is
 * added here is the third dimension and nothing else.
 */
@Suppress("LongParameterList")
internal class Column3DLayer(
    override val id: String,
    private val categories: List<String>,
    private val series: List<Column3DSeriesInfo>,
    private val grouping: BarGrouping,
    private val arrangement: Column3DArrangement,
    private val depth: Chart3DDepth,
    private val groupPadding: Double,
    private val depthGap: Double,
    private val valueFraction: (Double) -> Double,
    private val categoryCentres: List<Float>,
    private val bandWidth: Float,
    private val cameraProvider: () -> Chart3DCamera,
    private val projection: Chart3DProjection,
    private val lighting: Chart3DLighting,
    private val frame: Chart3DFrame,
    private val axisFurniture: Column3DAxisFurniture,
    private val labelPlacement: Column3DLabelPlacement,
    private val formatter: ChartValueFormatter,
    private val onDiagnostics: ((Chart3DDiagnostics) -> Unit)?,
    private val debug: Chart3DDebug,
    override val valueAxisId: io.devkit.chartkit.axis.ChartAxisId,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = series.map { it.seriesId }

    /**
     * The axis labels are written outside the frame, so the layer is not
     * clipped to the plot the way a bar layer is.
     *
     * There is nothing to clip *to*: with the 2D axes hidden the plot is the
     * whole canvas, and the scene has already been fitted inside it with the
     * label gutters reserved.
     */
    override val clipToPlot: Boolean get() = false

    private val layoutSeries: List<Column3DSeries> = series.map {
        Column3DSeries(
            seriesId = it.seriesId,
            seriesName = it.seriesName,
            paletteIndex = it.paletteIndex,
            colorOverride = it.colorOverride,
            stackId = it.stackId,
            values = it.values,
            sourceIndices = it.sourceIndices,
        )
    }
    private val itemsBySeries: Map<String, List<Any?>> =
        series.associate { it.seriesId to it.items }

    /**
     * The last drawn projection, and the last settled one.
     *
     * Two slots rather than one because the two callers want different frames:
     * a draw wants the geometry at the animation's current fraction, and a hit
     * test wants it settled. One slot would thrash between them on every frame
     * of a reveal and hold the wrong one afterwards.
     */
    private var drawn: RenderedFrame? = null
    private var settled: RenderedFrame? = null

    // ---- drawing ----------------------------------------------------------

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val rendered = renderedFor(context, context.reveal) ?: return
        val colors = context.colors

        scope.drawFrame(rendered, context)

        val selected = context.selection
        val edgeWidth = context.px(context.dimensions.chart3DEdgeWidth)
        rendered.dataFaces.forEachIndexed { drawIndex, face ->
            val obj = rendered.dataObjects.getOrNull(face.objectIndex) ?: return@forEachIndexed
            val base = obj.colorOverride?.let { Color(it) }
                ?: colors.seriesColor(obj.paletteIndex)
            val path = face.toPath()
            scope.drawPath(path, shade(base, face.brightness))
            if (edgeWidth > 0f) {
                scope.drawPath(path, colors.threeD.edge, style = Stroke(width = edgeWidth))
            }
            val isSelected = selected != null &&
                face.key != null &&
                selected.seriesId == face.key.seriesId &&
                selected.pointIndex == face.key.pointIndex
            if (isSelected) {
                // Every visible face, not one of them. Emphasising the front
                // face alone makes a selected column look like a column with a
                // differently-coloured front, which is a rendering fault rather
                // than a highlight — and the front face is not even visible on
                // a column at the far edge of a rotated chart.
                scope.drawPath(path, colors.selectionHighlight)
                scope.drawPath(
                    path = path,
                    color = colors.threeD.selectedOutline,
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth) * 2f),
                )
            }
            when (debug) {
                Chart3DDebug.DepthOrder -> scope.drawDebugIndex(face, drawIndex, context)
                Chart3DDebug.Normals -> scope.drawDebugNormal(rendered, face, context)
                Chart3DDebug.Wireframe ->
                    scope.drawPath(path, colors.selectionGuide, style = Stroke(width = edgeWidth))
                Chart3DDebug.None -> Unit
            }
        }

        scope.drawValueLabels(rendered, context)
        scope.drawAxisFurniture(rendered, context)
        onDiagnostics?.invoke(rendered.diagnostics)
    }

    private fun DrawScope.drawFrame(rendered: RenderedFrame, context: ChartRenderContext) {
        if (rendered.frameFaces.isEmpty() && rendered.gridLines.isEmpty()) return
        val colors = context.colors.threeD
        rendered.frameFaces.forEach { face ->
            val panel = rendered.frameObjects.getOrNull(face.objectIndex) ?: return@forEach
            val path = face.toPath()
            drawPath(path, colors.frame.copy(alpha = colors.frame.alpha * panel.opacity))
            drawPath(
                path = path,
                color = colors.frameBorder,
                style = Stroke(width = context.px(context.dimensions.chart3DFrameWidth)),
            )
        }
        val gridWidth = context.px(context.dimensions.chart3DFrameWidth)
        rendered.gridLines.forEach { (from, to) ->
            drawLine(
                color = colors.frameGrid,
                start = Offset(from.x.toFloat(), from.y.toFloat()),
                end = Offset(to.x.toFloat(), to.y.toFloat()),
                strokeWidth = gridWidth,
            )
        }
    }

    /**
     * Value labels, placed from the projected geometry of each column.
     *
     * Drawn after every face, and only for columns whose top face survived
     * culling — a label over a column that is entirely hidden behind another is
     * a number floating in front of the wrong data.
     */
    private fun DrawScope.drawValueLabels(rendered: RenderedFrame, context: ChartRenderContext) {
        if (labelPlacement == Column3DLabelPlacement.None) return
        val measurer = context.textMeasurer
        val style = context.typography.valueLabel
        val visible = rendered.dataFaces.mapNotNull { it.key }.toSet()
        // The same collision rule the flat value labels use, and the same
        // conservative outcome: a label that would leave the plot or land on one
        // already placed is dropped rather than shrunk. Placed nearest-first, so
        // what survives on a crowded chart is the front row rather than whatever
        // the segment order happened to be.
        val placer = io.devkit.chartkit.layer.label.LabelPlacer(
            context.cartesian.plotArea,
            rendered.layout.segments.size,
        )

        val piles = rendered.layout.segments.groupBy { it.key.stackId to it.key.categoryIndex }
        // A zero-length segment inside a pile that has height gets no label: it
        // would be pinned against its neighbour's edge, saying nothing the stack
        // does not already say. A zero standing alone keeps its label, because a
        // flat column and a missing one look alike and the number is what tells
        // them apart.
        val pileHasHeight = piles.mapValues { (_, group) ->
            group.any { abs(it.plottedValue) > VALUE_EPSILON }
        }
        // Which segment is the outermost of its pile, in each direction. Only
        // those may put a label *above* the column: a label placed over an
        // interior segment lands inside whatever is stacked on top of it, which
        // reads as the wrong number attached to the wrong colour.
        val outermost = HashSet<io.devkit.chartkit.three.Chart3DKey>()
        piles.values.forEach { group ->
            group.filter { !it.cuboid.isNegative }.maxByOrNull { it.cuboid.bounds.maxY }
                ?.let { outermost += it.key }
            group.filter { it.cuboid.isNegative }.minByOrNull { it.cuboid.bounds.minY }
                ?.let { outermost += it.key }
        }

        // Nearest first: a label the reader is closest to is the one worth
        // keeping when two collide.
        val depths = rendered.dataFaces.filter { it.key != null }
            .groupBy { it.key!! }
            .mapValues { (_, faces) -> faces.minOf { it.depth } }
        rendered.layout.segments.sortedBy { depths[it.key] ?: Double.MAX_VALUE }.forEach { segment ->
            if (segment.key !in visible) return@forEach
            if (abs(segment.plottedValue) < VALUE_EPSILON &&
                pileHasHeight[segment.key.stackId to segment.key.categoryIndex] == true
            ) {
                return@forEach
            }
            val text = measurer.measure(formatter.format(segment.value), style)
            val fits = rendered.segmentHeightOf(segment.key) > text.size.height * INSIDE_HEADROOM
            val inside = when (labelPlacement) {
                Column3DLabelPlacement.Inside -> true
                Column3DLabelPlacement.Auto -> fits
                else -> false
            }
            // Inside and it does not fit, or above and this is not the top of
            // its pile: neither placement is available, and no label is better
            // than one over the wrong segment.
            if (labelPlacement == Column3DLabelPlacement.Inside && !fits) return@forEach
            if (!inside && segment.key !in outermost) return@forEach

            val point = if (inside) {
                labelAnchor(rendered, segment.cuboid.faceCenter(FaceSide.Front))
            } else {
                labelAnchor(rendered, segment.cuboid.faceCenter(FaceSide.Top))
            } ?: return@forEach
            val top = if (inside) {
                point.y.toFloat() - text.size.height / 2f
            } else {
                point.y.toFloat() - text.size.height - context.px(context.dimensions.labelPadding)
            }
            // A label inside a column is written over the series' own colour,
            // which the theme's label colour knows nothing about — a mid-grey
            // over a saturated blue is unreadable, and the chart cannot fix it
            // by choosing a different grey. The two extremes are the only
            // colours guaranteed to contrast with an arbitrary fill, so the
            // brighter of them is chosen against the fill's own luminance.
            val colour = if (inside) {
                val base = segment.colorOverride?.let { Color(it) }
                    ?: context.colors.seriesColor(segment.paletteIndex)
                onColour(shade(base, faceBrightness(rendered, segment.key)))
            } else {
                context.colors.valueLabel
            }
            val left = point.x.toFloat() - text.size.width / 2f
            if (!placer.place(left, top, text.size.width.toFloat(), text.size.height.toFloat())) {
                return@forEach
            }
            drawText(textLayoutResult = text, color = colour, topLeft = Offset(left, top))
        }
    }

    /** The brightness the front face of [key] was drawn at, or a lit default. */
    private fun faceBrightness(
        rendered: RenderedFrame,
        key: io.devkit.chartkit.three.Chart3DKey,
    ): Double = rendered.dataFaces
        .firstOrNull { it.key == key && it.side == FaceSide.Front }
        ?.brightness ?: 1.0

    /**
     * Black or white, whichever reads on [background].
     *
     * The luminance coefficients are the ones the sRGB relative-luminance
     * definition uses, and the threshold is the point at which the contrast
     * ratio against black overtakes the one against white.
     */
    private fun onColour(background: Color): Color {
        val luminance = 0.2126 * background.red + 0.7152 * background.green +
            0.0722 * background.blue
        return if (luminance > LIGHT_ON_DARK_THRESHOLD) Color.Black else Color.White
    }

    private fun labelAnchor(rendered: RenderedFrame, point: Point3D): Projected2D? =
        rendered.projector.toScreen(point)

    /**
     * The value ticks and category names, written flat.
     *
     * ### Flat, and not projected
     *
     * The labels are *placed* in three dimensions — each sits at the projected
     * position of the value or the band it names, so it cannot drift from what
     * it labels — and then drawn upright. Skewing them onto the frame's planes
     * would be more visually consistent and less readable, and the numbers on
     * an axis are the part of a chart a reader is least able to guess at.
     */
    private fun DrawScope.drawAxisFurniture(rendered: RenderedFrame, context: ChartRenderContext) {
        val furniture = axisFurniture
        if (furniture.isEmpty) return
        val gap = context.px(context.dimensions.chart3DLabelGap)
        val volume = rendered.layout.volume

        furniture.valueLabels.forEachIndexed { index, label ->
            val fraction = furniture.valueFractions.getOrNull(index) ?: return@forEachIndexed
            val y = volume.minY + volume.height * fraction
            val at = rendered.projector.toScreen(Point3D(volume.minX, y, volume.minZ))
                ?: return@forEachIndexed
            drawText(
                textLayoutResult = label,
                color = context.colors.axisLabel,
                topLeft = Offset(
                    at.x.toFloat() - label.size.width - gap,
                    at.y.toFloat() - label.size.height / 2f,
                ),
            )
        }

        furniture.categoryLabels.forEachIndexed { index, label ->
            if (label == null) return@forEachIndexed
            val centre = categoryCentres.getOrNull(index) ?: return@forEachIndexed
            val at = rendered.projector.toScreen(
                Point3D(centre.toDouble(), volume.minY, volume.minZ),
            ) ?: return@forEachIndexed
            drawText(
                textLayoutResult = label,
                color = context.colors.axisLabel,
                topLeft = Offset(at.x.toFloat() - label.size.width / 2f, at.y.toFloat() + gap),
            )
        }

        furniture.valueTitle?.let { title ->
            val at = rendered.projector.toScreen(
                Point3D(volume.minX, volume.center.y, volume.minZ),
            )
            if (at != null) {
                drawText(
                    textLayoutResult = title,
                    color = context.colors.axisTitle,
                    // The title is written upright, so what it needs is its
                    // *width*; the gutter reserved exactly that, beyond the tick
                    // labels, and this is where that reserve begins.
                    topLeft = Offset(
                        max(0f, at.x.toFloat() - furniture.valueGutter),
                        at.y.toFloat() - title.size.height / 2f,
                    ),
                )
            }
        }
        furniture.categoryTitle?.let { title ->
            val at = rendered.projector.toScreen(
                Point3D(volume.center.x, volume.minY, volume.minZ),
            )
            if (at != null) {
                drawText(
                    textLayoutResult = title,
                    color = context.colors.axisTitle,
                    topLeft = Offset(
                        at.x.toFloat() - title.size.width / 2f,
                        at.y.toFloat() + furniture.categoryLabelExtent + gap * 2f,
                    ),
                )
            }
        }
    }

    /**
     * A spur out of each visible face, along its outward normal.
     *
     * The single most useful thing to look at when a chart is drawing its own
     * interior: a face whose spur points *into* the box is wound backwards, and
     * nothing else about the picture says so.
     */
    private fun DrawScope.drawDebugNormal(
        rendered: RenderedFrame,
        face: ProjectedFace,
        context: ChartRenderContext,
    ) {
        val source = rendered.dataObjects.getOrNull(face.objectIndex)?.geometry
            ?.faces?.getOrNull(face.faceIndex) ?: return
        val from = rendered.projector.toScreen(source.centroid) ?: return
        val tip = source.centroid + (source.normal * DEBUG_NORMAL_LENGTH)
        val to = rendered.projector.toScreen(tip) ?: return
        drawLine(
            color = context.colors.selectionGuide,
            start = Offset(from.x.toFloat(), from.y.toFloat()),
            end = Offset(to.x.toFloat(), to.y.toFloat()),
            strokeWidth = context.px(context.dimensions.chart3DFrameWidth),
        )
    }

    private fun DrawScope.drawDebugIndex(
        face: ProjectedFace,
        drawIndex: Int,
        context: ChartRenderContext,
    ) {
        val centre = face.points.fold(Projected2D(0.0, 0.0)) { acc, point ->
            Projected2D(acc.x + point.x / face.points.size, acc.y + point.y / face.points.size)
        }
        val text = context.textMeasurer.measure(
            drawIndex.toString(),
            context.typography.valueLabel,
        )
        drawText(
            textLayoutResult = text,
            color = context.colors.axisTitle,
            topLeft = Offset(centre.x.toFloat(), centre.y.toFloat()),
        )
    }

    // ---- interaction ------------------------------------------------------

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        // Settled geometry, not the animating frame. The gesture callbacks
        // capture their render context once per layout, so the reveal they
        // carry is whatever it was when the chart was measured — usually zero.
        // Hit testing against that would test a tap against columns lying flat
        // on the floor, which is both wrong and invisible: the chart looks
        // right and simply never selects anything. Settled geometry is also
        // what the 2D bar layer hit tests against, for the same reason.
        val rendered = renderedFor(context, reveal = 1f) ?: return null
        val face = Chart3DHitTest.faceAt(
            rendered.dataFaces,
            point.x.toDouble(),
            point.y.toDouble(),
        ) ?: return nearestColumn(rendered, point, mode)
        return selectionFor(rendered, face.key ?: return null)
    }

    /**
     * The column nearest the pointer along the domain axis.
     *
     * Only for [HitTestMode.NearestDomain], which is how keyboard and
     * screen-reader stepping ask: they probe the middle of a band and want
     * whatever is in it. A *tap* that missed every face has genuinely missed —
     * there is nothing under the finger — and answering it with the nearest
     * column would make the empty space above a chart selectable.
     */
    private fun nearestColumn(
        rendered: RenderedFrame,
        point: ChartOffset,
        mode: HitTestMode,
    ): AnyChartSelection? {
        if (mode != HitTestMode.NearestDomain) return null
        var best: ProjectedFace? = null
        var bestDistance = Double.MAX_VALUE
        rendered.dataFaces.forEach { face ->
            if (face.key == null || face.side != FaceSide.Front) return@forEach
            val centre = face.points.fold(0.0) { acc, p -> acc + p.x } / face.points.size
            val distance = abs(centre - point.x)
            if (distance < bestDistance) {
                bestDistance = distance
                best = face
            }
        }
        val key = best?.key ?: return null
        return selectionFor(rendered, key)
    }

    private fun selectionFor(
        rendered: RenderedFrame,
        key: io.devkit.chartkit.three.Chart3DKey,
    ): AnyChartSelection? {
        val segment = rendered.layout.segments.firstOrNull { it.key == key } ?: return null
        val source = series.firstOrNull { it.seriesId == key.seriesId } ?: return null
        // Anchored to the top face, which is where a reader's eye is: the top
        // of a column is what its height is read against.
        val anchor = rendered.projector.toScreen(segment.cuboid.faceCenter(FaceSide.Top))
            ?: rendered.projector.toScreen(segment.cuboid.faceCenter(FaceSide.Front))
        return ChartSelection(
            seriesId = source.seriesId,
            seriesName = source.seriesName,
            seriesIndex = series.indexOf(source),
            pointIndex = key.pointIndex,
            x = ChartX.Category(key.category),
            y = segment.value,
            item = segment.item,
            position = anchor
                ?.let { ChartOffset(it.x.toFloat(), it.y.toFloat()) }
                ?: ChartOffset.Zero,
        )
    }

    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        val label = (selection.x as? ChartX.Category)?.label ?: return emptyList()
        val index = categories.indexOf(label)
        if (index < 0) return emptyList()
        return series.mapNotNull { source ->
            val value = source.values.getOrNull(index) ?: return@mapNotNull null
            ChartTooltipEntry(
                seriesId = source.seriesId,
                seriesName = source.seriesName,
                value = value,
                item = source.sourceIndices.getOrNull(index)?.let { source.items.getOrNull(it) },
                paletteIndex = source.paletteIndex,
            )
        }
    }

    // ---- accessibility ----------------------------------------------------

    override fun describe(): List<ChartLayerSummary> = series.map { source ->
        ChartLayerSummary(
            seriesId = source.seriesId,
            seriesName = source.seriesName,
            pointCount = source.values.size,
            entries = source.values.mapIndexed { index, value ->
                ChartLayerEntry(
                    label = categories.getOrElse(index) { index.toString() },
                    value = value,
                )
            },
            missingCount = source.values.count { it == null },
        )
    }

    /**
     * What a screen reader hears, in the data's own terms.
     *
     * No camera, no depth, no faces. A reader who cannot see the picture is not
     * helped by being told which side of a box is facing them, and a chart that
     * announced its geometry would be describing a rendering choice rather than
     * a measurement. The stack total is included because on a stacked chart it
     * is the number the picture makes most prominent and the one a single
     * segment cannot supply.
     */
    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? {
        val label = (selection.x as? ChartX.Category)?.label ?: return null
        val index = categories.indexOf(label)
        if (index < 0) return null
        val stackId = series.firstOrNull { it.seriesId == selection.seriesId }?.stackId
        val members = series.filter { stackId == null || it.stackId == stackId }
        val parts = members.mapNotNull { source ->
            val value = source.values.getOrNull(index) ?: return@mapNotNull null
            "${source.seriesName.ifBlank { source.seriesId }}: ${formatter.format(value)}"
        }
        if (parts.isEmpty()) return null
        val total = members.sumOf { it.values.getOrNull(index) ?: 0.0 }
        val suffix = if (grouping.isStacked && parts.size > 1) {
            " Stack total: ${formatter.format(total)}."
        } else {
            ""
        }
        return "$label. ${parts.joinToString(". ")}.$suffix"
    }

    // ---- export -----------------------------------------------------------

    /**
     * The finished picture as flat polygons, in draw order.
     *
     * Faithful rather than approximate: what a 3D chart draws *is* a list of
     * filled convex polygons, and a scene holding exactly those polygons in
     * exactly that order renders identically. The camera is gone from the
     * export, which is correct — an SVG has no camera.
     */
    override fun renderScene(
        builder: io.devkit.chartkit.scene.ChartSceneBuilder,
        context: ChartRenderContext,
    ): Boolean {
        // Settled, like every other layer's scene: an exported picture must
        // not depend on where an animation happened to be.
        val rendered = renderedFor(context, reveal = 1f) ?: return true
        builder.group(id) {
            rendered.frameFaces.forEach { face ->
                add(
                    io.devkit.chartkit.scene.ChartSceneNode.Path(
                        points = face.points.map { ChartOffset(it.x.toFloat(), it.y.toFloat()) },
                        color = context.colors.threeD.frame,
                        style = io.devkit.chartkit.scene.PaintStyle.Fill,
                        closed = true,
                    ),
                )
            }
            rendered.dataFaces.forEach { face ->
                val obj = rendered.dataObjects.getOrNull(face.objectIndex) ?: return@forEach
                val base = obj.colorOverride?.let { Color(it) }
                    ?: context.colors.seriesColor(obj.paletteIndex)
                add(
                    io.devkit.chartkit.scene.ChartSceneNode.Path(
                        points = face.points.map { ChartOffset(it.x.toFloat(), it.y.toFloat()) },
                        color = shade(base, face.brightness),
                        style = io.devkit.chartkit.scene.PaintStyle.Fill,
                        closed = true,
                    ),
                )
            }
        }
        return true
    }

    // ---- pipeline ---------------------------------------------------------

    /**
     * The projected frame for this context, reusing the last one when nothing
     * that affects it has changed.
     *
     * The cache key is deliberately narrow. A tooltip appearing, a selection
     * moving or a legend row being hovered changes none of these, so none of
     * them reprojects a single vertex — and a camera drag changes only the
     * camera, so the stack layout and the world geometry survive it untouched.
     */
    private fun renderedFor(context: ChartRenderContext, reveal: Float): RenderedFrame? {
        val plot = context.cartesian.plotArea
        if (plot.isEmpty || categories.isEmpty()) return null
        val camera = cameraProvider()
        val isSettled = reveal >= 1f
        val key = FrameKey(
            plot = plot,
            camera = camera,
            reveal = quantise(reveal),
            density = context.density.density,
        )
        (if (isSettled) settled else drawn)?.let { if (it.key == key) return it }

        val layout = Column3DLayoutEngine.layout(
            categories = categories,
            series = layoutSeries,
            items = itemsBySeries,
            grouping = grouping,
            arrangement = arrangement,
            depth = depth,
            categoryCentres = categoryCentres.map { it - plot.left },
            bandWidth = bandWidth,
            valueFraction = valueFraction,
            plotWidth = plot.width,
            plotHeight = plot.height,
            groupPadding = groupPadding,
            depthGap = depthGap,
            reveal = reveal,
        )

        val framePanels = frame.panelsFor(layout.volume, camera)
        val dataObjects = layout.segments.map { segment ->
            Chart3DObject(
                geometry = segment.cuboid,
                paletteIndex = segment.paletteIndex,
                colorOverride = segment.colorOverride,
                role = Chart3DRole.Data,
            )
        }

        val reserve = Chart3DReserve(
            left = axisFurniture.valueGutter,
            top = context.px(context.dimensions.chart3DPadding),
            right = context.px(context.dimensions.chart3DPadding),
            bottom = axisFurniture.categoryGutter,
        )
        // The frame is fitted with the data so the scene keeps one scale. Two
        // fits would let the columns and the floor they stand on disagree by a
        // pixel, which reads as the columns hovering.
        val whole = Chart3DScene(framePanels + dataObjects, lighting)
        val projector = Chart3DProjector.of(whole, camera, projection, plot, reserve) ?: return null

        // Projected as two scenes through the *one* projector. The frame is
        // always behind and below the data — the back wall sits at the volume's
        // far face and the floor at its base — so drawing it first is correct
        // without a global sort, and it is what lets the grid lines land on the
        // walls rather than compete for a place in the face order.
        val frameResult = projector.project(Chart3DScene(framePanels, lighting))
        val dataResult = projector.project(Chart3DScene(dataObjects, lighting))

        val gridLines = frame.gridSegments(layout.volume, axisFurniture.valueFractions)
            .mapNotNull { (from, to) ->
                val a = projector.toScreen(from) ?: return@mapNotNull null
                val b = projector.toScreen(to) ?: return@mapNotNull null
                a to b
            }

        val rendered = RenderedFrame(
            key = key,
            layout = layout,
            projector = projector,
            frameObjects = framePanels,
            dataObjects = dataObjects,
            frameFaces = frameResult.faces,
            dataFaces = dataResult.faces,
            gridLines = gridLines,
            diagnostics = dataResult.diagnostics,
        )
        if (isSettled) settled = rendered else drawn = rendered
        return rendered
    }

    /** Positions of every drawn column, for a caller inspecting the layout in a test. */
    internal fun lastRendered(): RenderedFrame? = drawn ?: settled

    private fun ProjectedFace.toPath(): Path {
        val path = Path()
        points.forEachIndexed { index, point ->
            if (index == 0) {
                path.moveTo(point.x.toFloat(), point.y.toFloat())
            } else {
                path.lineTo(point.x.toFloat(), point.y.toFloat())
            }
        }
        path.close()
        return path
    }

    /**
     * [base] at [brightness], with its alpha untouched.
     *
     * The alpha matters: a series drawn at 60% opacity multiplied component-wise
     * *including* its alpha would become both darker and more transparent on
     * every face turned away from the light, so the background would show
     * through the shading and the column would stop reading as one object.
     */
    private fun shade(base: Color, brightness: Double): Color {
        val factor = brightness.coerceIn(0.0, 1.0).toFloat()
        return Color(
            red = base.red * factor,
            green = base.green * factor,
            blue = base.blue * factor,
            alpha = base.alpha,
            colorSpace = base.colorSpace,
        )
    }

    /**
     * The reveal, rounded to a step.
     *
     * The cache would otherwise miss on every animation frame *and* every idle
     * frame, because a settled `Animatable` reports a float that is one ulp off
     * its target. Rounding makes the settled state hit.
     */
    private fun quantise(reveal: Float): Int = (reveal.coerceIn(0f, 1f) * REVEAL_STEPS).roundToInt()

    private companion object {
        /** Fine enough that no frame of a reveal is skipped; coarse enough to settle. */
        const val REVEAL_STEPS = 240f

        /** A segment shorter than its own label plus this much gets the label on top. */
        const val INSIDE_HEADROOM = 1.6

        /** Below this a segment has no length and no label worth drawing. */
        const val VALUE_EPSILON = 1e-9

        /** How far a debug normal spur reaches, in world units. */
        const val DEBUG_NORMAL_LENGTH = 18.0

        /** Above this relative luminance, black text reads better than white. */
        const val LIGHT_ON_DARK_THRESHOLD = 0.55
    }
}

/** What the layer projected last, kept so a hit test and a draw cannot disagree. */
internal class RenderedFrame(
    val key: FrameKey,
    val layout: Column3DLayout,
    val projector: Chart3DProjector,
    val frameObjects: List<Chart3DObject>,
    val dataObjects: List<Chart3DObject>,
    val frameFaces: List<ProjectedFace>,
    val dataFaces: List<ProjectedFace>,
    val gridLines: List<Pair<Projected2D, Projected2D>>,
    val diagnostics: Chart3DDiagnostics,
) {
    /** The projected height of a segment's front face, for label placement. */
    fun segmentHeightOf(key: io.devkit.chartkit.three.Chart3DKey): Double {
        val face = dataFaces.firstOrNull { it.key == key && it.side == FaceSide.Front }
            ?: return 0.0
        val top = face.points.minOf { it.y }
        val bottom = face.points.maxOf { it.y }
        return bottom - top
    }
}

/** Everything a projection depends on, and nothing else. */
internal data class FrameKey(
    val plot: ChartRect,
    val camera: Chart3DCamera,
    val reveal: Int,
    val density: Float,
)

/**
 * The axis labels and titles, measured once per layout.
 *
 * Laying text out is the expensive part of drawing an axis and it does not
 * depend on the camera, so it happens where the rest of the chart's measurement
 * happens — in the geometry builder — and a rotation reuses all of it.
 *
 * @param valueFractions the `0..1` height of each value tick, from the chart's
 *   own value scale. Parallel to [valueLabels].
 * @param categoryLabels one per category, `null` where thinning dropped it.
 * @param valueGutter how much room the value labels and title need on the left.
 * @param categoryGutter how much room the category labels and title need below.
 */
internal class Column3DAxisFurniture(
    val valueFractions: List<Double> = emptyList(),
    val valueLabels: List<TextLayoutResult> = emptyList(),
    val categoryLabels: List<TextLayoutResult?> = emptyList(),
    val valueTitle: TextLayoutResult? = null,
    val categoryTitle: TextLayoutResult? = null,
    val valueGutter: Float = 0f,
    val categoryGutter: Float = 0f,
    /**
     * How much of [valueGutter] the tick labels themselves take, and how much of
     * [categoryGutter] the category names take.
     *
     * Stored so a title lands *exactly* where the gutter reserved room for it
     * rather than being pushed left until it clamps at the plot's edge — which
     * looks right for a short title and overlaps the numbers for a long one.
     */
    val valueLabelExtent: Float = 0f,
    val categoryLabelExtent: Float = 0f,
) {
    val isEmpty: Boolean
        get() = valueLabels.isEmpty() && categoryLabels.none { it != null } &&
            valueTitle == null && categoryTitle == null

    companion object {
        val None: Column3DAxisFurniture = Column3DAxisFurniture()
    }
}
