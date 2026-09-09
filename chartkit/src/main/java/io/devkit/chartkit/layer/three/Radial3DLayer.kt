package io.devkit.chartkit.layer.three

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarSlice
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.layer.label.LabelPlacer
import io.devkit.chartkit.layer.polar.SliceLabelContent
import io.devkit.chartkit.layer.polar.SliceLabelPosition
import io.devkit.chartkit.layer.polar.SliceSeriesEntry
import io.devkit.chartkit.layer.polar.percentage
import io.devkit.chartkit.layer.polar.placeOutsideLabel
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSelectionDetails
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.three.ArcTessellator3D
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DDepth
import io.devkit.chartkit.three.Chart3DDiagnostics
import io.devkit.chartkit.three.Chart3DHitTest
import io.devkit.chartkit.three.Chart3DKey
import io.devkit.chartkit.three.Chart3DLighting
import io.devkit.chartkit.three.Chart3DObject
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Chart3DProjector
import io.devkit.chartkit.three.Chart3DQuality
import io.devkit.chartkit.three.Chart3DReserve
import io.devkit.chartkit.three.Chart3DScene
import io.devkit.chartkit.three.FaceSide
import io.devkit.chartkit.three.Point3D
import io.devkit.chartkit.three.Projected2D
import io.devkit.chartkit.three.ProjectedFace
import io.devkit.chartkit.three.Radial3DLayout
import io.devkit.chartkit.three.Radial3DLayoutEngine
import io.devkit.chartkit.three.RadialSector3D
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Extruded pie and donut slices, projected through the shared 3D scene.
 *
 * ### What is new here, and what is not
 *
 * New: an extrusion, a tessellation, an explode displacement, and labels placed
 * from projected anchors. Not new — and not reimplemented — the slice values,
 * the total, the shares, the start angles, the sweep angles, the palette, the
 * legend, the tooltip, the selection model and the accessibility summary, all
 * of which arrive from exactly the code that produces them for a flat
 * [io.devkit.chartkit.charts.PieChart]. Also not new: the camera, the
 * projection, the culling, the depth sort, the lighting and the hit test, all
 * of which are the ones the 3D column chart uses, unchanged.
 *
 * That last sentence is the point of the whole file. A pie is a shape in a
 * scene, and if the scene needed a radial variant of any of those six things,
 * the scene would not have been a scene.
 *
 * ### Two caches, and why not one
 *
 * World geometry — the tessellated sectors — depends on the data, the radius in
 * pixels, the depth, the quality and the explode. The projection depends on all
 * of that *and* the camera. Turning the chart therefore reprojects and does not
 * re-tessellate, and a tooltip appearing does neither. One cache keyed on
 * everything would rebuild every arc in the chart on every frame of a rotation.
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class Radial3DLayer(
    override val id: String,
    private val slices: List<PolarSlice>,
    private val entries: List<SliceSeriesEntry>,
    private val seriesId: String,
    private val seriesName: String,
    private val direction: PolarDirection,
    private val chartStartAngle: Float,
    private val innerRadiusRatio: Double,
    private val depth: Chart3DDepth,
    private val quality: Chart3DQuality,
    private val cameraProvider: () -> Chart3DCamera,
    private val projection: Chart3DProjection,
    private val lighting: Chart3DLighting,
    /**
     * How far each slice is currently displaced, as a `0..1` fraction of
     * [explodeDistancePx], by source index.
     *
     * A provider and not a value: it is read at draw time, so the explode
     * animation invalidates the drawing and nothing else — the composition, the
     * scales and the slice arithmetic are all untouched by a slice sliding out.
     */
    private val explodeProvider: () -> FloatArray,
    /** How far a fully exploded slice moves, in screen pixels. */
    private val explodeDistancePx: Float,
    private val labelPosition: SliceLabelPosition,
    private val labelContent: SliceLabelContent,
    private val valueFormatter: ChartValueFormatter,
    /**
     * Room the labels written outside the ring would like on each side, in
     * pixels. Capped before it is used — see [gutterFor].
     */
    private val labelGutter: Float,
    /** The gap between the projected scene and the plot's edge, in pixels. */
    private val padding: Float,
    private val onDiagnostics: ((Chart3DDiagnostics) -> Unit)?,
    private val debug: Chart3DDebug,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = listOf(seriesId)

    /**
     * Not clipped. The scene is fitted inside the plot with the label gutter
     * already reserved, so there is nothing outside to cut off — and clipping a
     * projected shape to a rectangle that was measured for a flat one is how a
     * tilted rim loses its bottom edge.
     */
    override val clipToPlot: Boolean get() = false

    /**
     * World geometry, by what it was built from.
     *
     * A map rather than a slot because three callers want three different
     * frames at once: the draw wants the animating one, a hit test wants the
     * settled one, and a donut's centre content wants the settled one with
     * nothing exploded. Capped, so a long reveal cannot grow it without bound.
     */
    private val worlds = LinkedHashMap<WorldKey, WorldFrame>()
    private var drawn: RenderedRadial? = null
    private var settled: RenderedRadial? = null
    private var hole: RenderedRadial? = null

    // ---- drawing ----------------------------------------------------------

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val rendered = renderedFor(context, context.reveal) ?: return
        val selected = context.selection?.takeIf { it.seriesId == seriesId }?.pointIndex ?: -1
        val edgeWidth = context.px(context.dimensions.chart3DEdgeWidth)

        rendered.faces.forEachIndexed { drawIndex, face ->
            val entry = face.key?.let { entries.getOrNull(it.pointIndex) } ?: return@forEachIndexed
            val base = entry.colorOverride?.let { Color(it) }
                ?: context.colors.seriesColor(entry.paletteIndex)
            val path = face.toPath()
            val fill = shade(base, face.brightness)
            scope.drawPath(path, fill)
            // A hairline in the face's *own* colour, and not in the theme's
            // edge colour.
            //
            // A column's six faces are six surfaces, and a contrasting hairline
            // between them helps a reader see a box. A slice's faces are not:
            // twenty of them are one curved rim and twenty more are one flat
            // cap, and outlining every quad turns a pie into a wagon wheel with
            // a bright spot where all the spokes meet. What is still needed is
            // a stroke of *some* colour, because two anti-aliased fills meeting
            // along a shared edge leave a pale seam between them — so the
            // stroke is the fill, which closes the seam and draws nothing.
            //
            // The surfaces stay distinguishable because the lighting already
            // separates them: a cap and the rim below it differ by far more
            // than a hairline would add.
            if (edgeWidth > 0f) {
                scope.drawPath(path, fill, style = Stroke(width = edgeWidth))
            }
            if (face.key.pointIndex == selected) {
                // Every visible face of the slice, not one of them. Emphasising
                // the cap alone makes a selected slice read as a slice with a
                // differently-coloured top, and on a strongly tilted chart the
                // cap of a far slice may not be visible at all.
                scope.drawPath(path, context.colors.selectionHighlight)
                scope.drawPath(
                    path = path,
                    color = context.colors.threeD.selectedOutline,
                    style = Stroke(width = context.px(context.dimensions.selectionGuideWidth)),
                )
            }
            when (debug) {
                Chart3DDebug.Wireframe ->
                    scope.drawPath(
                        path,
                        context.colors.selectionGuide,
                        style = Stroke(width = edgeWidth),
                    )
                Chart3DDebug.DepthOrder -> scope.drawDebugIndex(face, drawIndex, context)
                Chart3DDebug.Normals -> scope.drawDebugNormal(rendered, face, context)
                Chart3DDebug.None -> Unit
            }
        }

        if (labelPosition != SliceLabelPosition.None && context.reveal >= 1f) {
            scope.drawLabels(rendered, context)
        }
        onDiagnostics?.invoke(rendered.diagnostics)
    }

    /**
     * Slice labels, anchored in three dimensions and drawn in two.
     *
     * ### Projected anchors, upright text
     *
     * Each label's position comes from a point on the slice's own front cap or
     * rim, pushed through the same projector the faces went through — so a
     * label cannot drift from what it names however the chart is turned. The
     * *text* is then drawn upright, because skewing it onto the cap's plane
     * would be more visually consistent and materially less readable, and a
     * label is the part of a chart a reader is least able to guess at.
     *
     * ### Why placement in screen space is the right answer under perspective
     *
     * A far slice is drawn smaller than a near one of the same share. Deciding
     * whether its label fits from its *share* would therefore be wrong; deciding
     * from its projected extent is exactly right, and it falls out of doing the
     * placement after the projection rather than before it.
     */
    private fun DrawScope.drawLabels(rendered: RenderedRadial, context: ChartRenderContext) {
        val insideStyle = context.typography.sliceLabel
        val outsideStyle = context.typography.sliceLabel.copy(color = context.colors.axisLabel)
        val gap = context.px(context.dimensions.labelPadding)
        // Labels are placed against the whole canvas, not against the plot.
        //
        // A polar chart's plot is the largest *square* inside its box, because
        // a circle centred in a square is what a pie is. On a tall, narrow
        // chart that leaves a band of unused width down each side, and it is
        // exactly where an outside label wants to be. Confining labels to the
        // square would drop the ones that would have fitted perfectly well in
        // the space beside it — and this layer is not clipped to the plot, so
        // there is nothing stopping them.
        val placer = LabelPlacer(
            ChartRect(0f, 0f, size.width, size.height),
            rendered.layout.slices.size,
        )
        val centre = rendered.projector.toScreen(Point3D.Origin)

        // Nearest first, so a label the reader is closest to survives a
        // collision with one behind it.
        rendered.layout.slices
            .sortedBy { rendered.frontDepthOf(it.key) }
            .forEach { slice ->
                val entry = entries.getOrNull(slice.sourceIndex) ?: return@forEach
                val text = labelText(entry, slice.value, slice.fraction)
                if (text.isEmpty()) return@forEach

                val capBox = rendered.capExtentOf(slice.key)
                val measuredInside = context.textMeasurer.measure(text, insideStyle)
                val fitsInside = capBox != null &&
                    capBox.width >= measuredInside.size.width &&
                    capBox.height >= measuredInside.size.height
                val resolved = when (labelPosition) {
                    SliceLabelPosition.Auto ->
                        if (fitsInside) SliceLabelPosition.Inside else SliceLabelPosition.Outside
                    else -> labelPosition
                }

                when (resolved) {
                    SliceLabelPosition.Inside -> {
                        if (!fitsInside) return@forEach
                        val anchor = rendered.projector.toScreen(slice.sector.anchor())
                            ?: return@forEach
                        val left = anchor.x.toFloat() - measuredInside.size.width / 2f
                        val top = anchor.y.toFloat() - measuredInside.size.height / 2f
                        if (!placer.place(
                                left,
                                top,
                                measuredInside.size.width.toFloat(),
                                measuredInside.size.height.toFloat(),
                            )
                        ) {
                            return@forEach
                        }
                        // Black or white against the slice's own shaded fill.
                        // The theme's label colour knows nothing about the
                        // palette, and a mid-grey over a saturated fill is
                        // unreadable however the theme is configured.
                        val fill = entry.colorOverride?.let { Color(it) }
                            ?: context.colors.seriesColor(entry.paletteIndex)
                        drawText(
                            textLayoutResult = measuredInside,
                            color = onColour(shade(fill, rendered.capBrightnessOf(slice.key))),
                            topLeft = Offset(left, top),
                        )
                    }

                    SliceLabelPosition.Outside -> {
                        val measured = context.textMeasurer.measure(text, outsideStyle)
                        val from = rendered.projector.toScreen(slice.sector.rimPoint())
                            ?: return@forEach
                        val origin = centre ?: return@forEach
                        // The leader runs straight out from the projected centre
                        // through the projected rim point, so it points away
                        // from the chart at whatever angle the camera has left
                        // that slice at rather than at its world angle.
                        val dx = from.x - origin.x
                        val dy = from.y - origin.y
                        val length = hypot(dx, dy)
                        if (length < LEADER_EPSILON) return@forEach
                        // Scaled outward *and* pushed out by a fixed gap. The
                        // fixed gap alone is not enough on a tilted chart: the
                        // rim point at twelve o'clock is only a few pixels above
                        // the top of a squashed ellipse whose widest part is
                        // further down, so a label placed a gap above it lands
                        // on the slices either side. Scaling from the projected
                        // centre clears the silhouette whatever the camera has
                        // done to it, because the silhouette is what was scaled.
                        val reach = length * LEADER_OUTWARD + gap * LEADER_REACH
                        val to = ChartOffset(
                            (origin.x + dx / length * (length + reach)).toFloat(),
                            (origin.y + dy / length * (length + reach)).toFloat(),
                        )
                        val at = placeOutsideLabel(
                            placer = placer,
                            to = to,
                            rightHalf = dx >= 0.0,
                            gap = gap,
                            width = measured.size.width.toFloat(),
                            height = measured.size.height.toFloat(),
                        ) ?: return@forEach
                        drawLine(
                            color = context.colors.axisLine,
                            start = Offset(from.x.toFloat(), from.y.toFloat()),
                            end = Offset(to.x, to.y),
                            strokeWidth = context.px(context.dimensions.axisLineWidth),
                        )
                        drawText(measured, topLeft = Offset(at.x, at.y))
                    }

                    SliceLabelPosition.None, SliceLabelPosition.Auto -> Unit
                }
            }
    }

    private fun labelText(entry: SliceSeriesEntry, value: Double, fraction: Double): String =
        when (labelContent) {
            SliceLabelContent.Label -> entry.label
            SliceLabelContent.Value -> valueFormatter.format(value)
            SliceLabelContent.Percentage -> percentage(fraction)
            SliceLabelContent.LabelAndPercentage -> "${entry.label} ${percentage(fraction)}"
        }

    private fun DrawScope.drawDebugNormal(
        rendered: RenderedRadial,
        face: ProjectedFace,
        context: ChartRenderContext,
    ) {
        val source = rendered.objects.getOrNull(face.objectIndex)?.geometry
            ?.faces?.getOrNull(face.faceIndex) ?: return
        val from = rendered.projector.toScreen(source.centroid) ?: return
        val to = rendered.projector.toScreen(source.centroid + source.normal * DEBUG_NORMAL_LENGTH)
            ?: return
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
        drawText(
            textLayoutResult = context.textMeasurer.measure(
                drawIndex.toString(),
                context.typography.valueLabel,
            ),
            color = context.colors.axisTitle,
            topLeft = Offset(centre.x.toFloat(), centre.y.toFloat()),
        )
    }

    // ---- interaction ------------------------------------------------------

    /**
     * The slice under the pointer, resolved through the projected geometry.
     *
     * Front-most wins, from the same depth order the faces were drawn in, so a
     * slice hidden behind another cannot take a tap on the visible one. Which
     * *surface* was hit is discarded: a tap on a cap, on the rim, on the wall of
     * a donut's hole or on a radial edge all name the same slice, because they
     * are all the same slice.
     */
    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        // Settled geometry, always. The gesture callbacks capture their render
        // context once per layout, so the reveal they carry is whatever it was
        // when the chart was measured — usually zero. Hit testing against that
        // would test a tap against a pie of no sweep at all: the chart looks
        // right and simply never selects anything.
        val rendered = renderedFor(context, reveal = 1f) ?: return null
        val face = Chart3DHitTest.faceAt(
            rendered.faces,
            point.x.toDouble(),
            point.y.toDouble(),
        ) ?: return null
        return selectionFor(rendered, face.key ?: return null)
    }

    /** A selection for [key], for a tap or for a programmatic select. */
    internal fun selectionFor(rendered: RenderedRadial, key: Chart3DKey): AnyChartSelection? {
        val slice = rendered.layout.slices.firstOrNull { it.key == key } ?: return null
        val entry = entries.getOrNull(slice.sourceIndex) ?: return null
        val anchor = rendered.projector.toScreen(slice.sector.anchor())
        return ChartSelection(
            seriesId = seriesId,
            seriesName = seriesName,
            seriesIndex = 0,
            pointIndex = slice.sourceIndex,
            // The slice's label is its domain value, exactly as on a flat pie,
            // so the shared tooltip and the accessibility layer read it without
            // a 3D special case.
            x = ChartX.Category(entry.label),
            y = slice.value,
            item = entry.item,
            position = anchor
                ?.let { ChartOffset(it.x.toFloat(), it.y.toFloat()) }
                ?: ChartOffset.Zero,
            // The share comes from the slice engine's own arithmetic and never
            // from a projected area. Under perspective a near slice covers more
            // pixels than a far one of the same value, so measuring the picture
            // to recover the number would report the distortion as data.
            details = ChartSelectionDetails.Polar(
                fraction = slice.fraction,
                label = entry.label,
                startAngle = slice.sector.startAngle.toFloat(),
                sweepAngle = slice.sector.sweepAngle.toFloat(),
            ),
        )
    }

    override fun tooltipEntriesAt(
        selection: AnyChartSelection,
        context: ChartRenderContext,
    ): List<ChartTooltipEntry<Any?>> {
        val slice = slices.firstOrNull { it.sourceIndex == selection.pointIndex }
            ?: return emptyList()
        val entry = entries.getOrNull(slice.sourceIndex) ?: return emptyList()
        return listOf(
            ChartTooltipEntry(
                seriesId = seriesId,
                seriesName = entry.label,
                value = slice.value,
                item = entry.item,
                paletteIndex = entry.paletteIndex,
            ),
        )
    }

    // ---- accessibility ----------------------------------------------------

    /**
     * What a screen reader hears: categories, values and shares.
     *
     * Identical to what the flat pie announces, and identical at every camera
     * angle, because none of it is computed from the picture. A 3D chart that
     * described its own geometry would be reading out a rendering choice.
     */
    override fun describe(): List<ChartLayerSummary> = listOf(
        ChartLayerSummary(
            seriesId = seriesId,
            seriesName = seriesName,
            pointCount = slices.size,
            entries = slices.mapNotNull { slice ->
                val entry = entries.getOrNull(slice.sourceIndex) ?: return@mapNotNull null
                ChartLayerEntry(
                    label = "${entry.label} (${percentage(slice.fraction)})",
                    value = slice.value,
                )
            },
        ),
    )

    // ---- export -----------------------------------------------------------

    override fun renderScene(
        builder: io.devkit.chartkit.scene.ChartSceneBuilder,
        context: ChartRenderContext,
    ): Boolean {
        val rendered = renderedFor(context, reveal = 1f) ?: return true
        builder.group(id) {
            rendered.faces.forEach { face ->
                val entry = face.key?.let { entries.getOrNull(it.pointIndex) } ?: return@forEach
                val base = entry.colorOverride?.let { Color(it) }
                    ?: context.colors.seriesColor(entry.paletteIndex)
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
     * Where a donut's centre content goes: the projected hole, as a rectangle.
     *
     * Measured from the **top** surface's inner rim, which is the edge the
     * reader actually sees and the conservative one — the underside's rim
     * projects lower and wider, and fitting to it would put a total half over
     * the ring. The inscribed factor is deliberately under `√2`, so a long
     * total leaves a margin rather than touching the arc.
     *
     * Computed from an un-exploded reference ring on purpose. A slice moving
     * outward opens a gap at the hole, and content that grew into it and shrank
     * back on the next tap would be worse than content that stays put.
     */
    fun centerBounds(plot: ChartRect, radiusPx: Double): ChartRect? {
        if (innerRadiusRatio <= 0.0) return null
        // Un-exploded, and settled. Also the reason this does not go through
        // the drawing caches: reading the explode animation here would tie the
        // *composition* of the centre content to it, and a total that
        // recomposed on every frame of a slice moving outward would be a
        // needless cost for a box that is not meant to move.
        val rendered = renderedForPlot(
            plot = plot,
            radiusPx = radiusPx,
            reveal = 1f,
            explode = NO_EXPLODE,
            slot = Slot.Hole,
        ) ?: return null
        val inner = rendered.layout.innerRadius
        if (inner <= 0.0) return null
        val samples = ArcTessellator3D.anglesFor(0.0, FULL_CIRCLE, HOLE_SAMPLES)
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        samples.forEach { angle ->
            val at = rendered.projector.toScreen(
                ArcTessellator3D.pointAt(inner, angle, rendered.layout.topY),
            ) ?: return@forEach
            minX = min(minX, at.x); maxX = max(maxX, at.x)
            minY = min(minY, at.y); maxY = max(maxY, at.y)
        }
        if (!minX.isFinite() || maxX <= minX || maxY <= minY) return null
        val halfWidth = (maxX - minX) / 2.0 * INSCRIBED_FACTOR / 2.0
        val halfHeight = (maxY - minY) / 2.0 * INSCRIBED_FACTOR / 2.0
        val centreX = (minX + maxX) / 2.0
        val centreY = (minY + maxY) / 2.0
        return ChartRect(
            left = (centreX - halfWidth).toFloat(),
            top = (centreY - halfHeight).toFloat(),
            right = (centreX + halfWidth).toFloat(),
            bottom = (centreY + halfHeight).toFloat(),
        )
    }

    private fun renderedFor(context: ChartRenderContext, reveal: Float): RenderedRadial? {
        val polar = context.polar
        if (!polar.plotArea.isEmpty && polar.outerRadius <= 0f) return null
        return renderedForPlot(
            plot = polar.plotArea,
            radiusPx = polar.outerRadius.toDouble(),
            reveal = reveal,
            explode = explodeProvider(),
            slot = if (reveal >= 1f) Slot.Settled else Slot.Drawn,
        )
    }

    /**
     * The projected frame, reusing whatever is still valid.
     *
     * Two levels. The world frame survives a camera change, because tessellated
     * arcs do not depend on where the reader is standing; the projected frame
     * survives nothing but an idle redraw, which is exactly what it costs to
     * rebuild. A selection or a tooltip changes neither.
     */
    @Suppress("LongParameterList")
    private fun renderedForPlot(
        plot: ChartRect,
        radiusPx: Double,
        reveal: Float,
        explode: FloatArray,
        slot: Slot,
    ): RenderedRadial? {
        if (plot.isEmpty || slices.isEmpty()) return null
        val worldKey = WorldKey(
            radiusPx = quantisePixels(radiusPx),
            reveal = quantise(reveal),
            explode = explode.joinToString(",") { quantise(it).toString() },
        )
        val camera = cameraProvider()
        val viewKey = ViewKey(worldKey, plot, camera)
        cached(slot)?.let { if (it.key == viewKey) return it }

        val frame = worlds[worldKey] ?: buildWorld(worldKey, radiusPx, explode).also {
            if (worlds.size >= MAX_WORLDS) worlds.remove(worlds.keys.first())
            worlds[worldKey] = it
        }

        val gutter = gutterFor(plot)
        val reserve = Chart3DReserve(
            left = gutter + padding,
            top = padding,
            right = gutter + padding,
            bottom = padding,
        )
        val projector = Chart3DProjector.of(
            scene = frame.scene,
            camera = camera,
            projection = projection,
            bounds = plot,
            reserve = reserve,
            // The pie's own rim, not the corners of the box around it. A disc
            // does not reach the corners of its bounding box, and fitting to
            // them leaves a visible margin that grows with the tilt.
            fitTo = frame.layout.fitPoints(),
        ) ?: return null
        val result = projector.project(frame.scene)

        val rendered = RenderedRadial(
            key = viewKey,
            layout = frame.layout,
            objects = frame.scene.objects,
            projector = projector,
            faces = result.faces,
            diagnostics = result.diagnostics.copy(
                tessellationSegments = frame.layout.tessellationSegments,
            ),
        )
        when (slot) {
            Slot.Drawn -> drawn = rendered
            Slot.Settled -> settled = rendered
            Slot.Hole -> hole = rendered
        }
        return rendered
    }

    private fun cached(slot: Slot): RenderedRadial? = when (slot) {
        Slot.Drawn -> drawn
        Slot.Settled -> settled
        Slot.Hole -> hole
    }

    /** Which of the three frames a request is for. See [worlds]. */
    private enum class Slot { Drawn, Settled, Hole }

    /**
     * How much of the plot the labels may actually take, per side.
     *
     * The labels ask for their own measured width; they do not get all of it.
     * Two full label widths out of a square plot can cost a pie two thirds of
     * its diameter, which trades the chart for its annotations — so the reserve
     * is capped, the pie stays legible, and a label that then has nowhere to go
     * is dropped by [LabelPlacer] rather than drawn over the data. The same
     * trade [io.devkit.chartkit.charts.PolarChartCore] makes with its own
     * minimum radius, at the same sort of fraction.
     */
    private fun gutterFor(plot: ChartRect): Float =
        labelGutter.coerceIn(0f, plot.width * MAX_LABEL_GUTTER)

    private fun buildWorld(key: WorldKey, radiusPx: Double, explode: FloatArray): WorldFrame {
        val layout = Radial3DLayoutEngine.layout(
            slices = slices,
            labels = entries.map { it.label },
            seriesId = seriesId,
            direction = direction,
            chartStartAngle = chartStartAngle,
            innerRadiusRatio = innerRadiusRatio,
            depth = depth,
            quality = quality,
            radiusPx = radiusPx,
            // Stated in pixels by the caller and converted here, because the
            // world is measured in radii: a 14dp displacement means one thing
            // on a chart 400px across and another on one 120px across, and the
            // radius is the only place both are known.
            explodeOf = { index ->
                val progress = explode.getOrElse(index) { 0f }.toDouble()
                if (radiusPx > 0.0) progress * explodeDistancePx / radiusPx else 0.0
            },
            reveal = key.reveal / REVEAL_STEPS,
        )
        val objects = layout.slices.map { slice ->
            val entry = entries.getOrNull(slice.sourceIndex)
            Chart3DObject(
                geometry = slice.sector,
                paletteIndex = entry?.paletteIndex ?: slice.sourceIndex,
                colorOverride = entry?.colorOverride,
            )
        }
        return WorldFrame(key, layout, Chart3DScene(objects, lighting))
    }

    /** Positions of everything drawn, for a caller inspecting the layout in a test. */
    internal fun lastRendered(): RenderedRadial? = settled ?: drawn

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

    /** [base] at [brightness], with its alpha untouched. */
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

    /** Black or white, whichever reads on [background]. sRGB relative luminance. */
    private fun onColour(background: Color): Color {
        val luminance = LUMA_R * background.red + LUMA_G * background.green +
            LUMA_B * background.blue
        return if (luminance > LIGHT_ON_DARK_THRESHOLD) Color.Black else Color.White
    }

    private fun quantise(value: Float): Int =
        (value.coerceIn(0f, 1f) * REVEAL_STEPS).roundToInt()

    private fun quantisePixels(value: Double): Int =
        if (value.isFinite()) value.roundToInt() else 0

    private companion object {
        /** Fine enough that no frame of a reveal is skipped; coarse enough to settle. */
        const val REVEAL_STEPS = 240f

        /** The drawn frame, the settled one and the un-exploded one. */
        const val MAX_WORLDS = 3

        /** Nothing displaced: what the centre content is measured against. */
        val NO_EXPLODE = FloatArray(0)

        /** How far past the rim a leader line reaches, in label paddings. */
        const val LEADER_REACH = 2f

        /** And as a fraction of the slice's own distance from the projected centre. */
        const val LEADER_OUTWARD = 0.08

        /** Below this the projected rim and the projected centre coincide. */
        const val LEADER_EPSILON = 1e-3

        /** How many points the projected hole is measured from. */
        const val HOLE_SAMPLES = 48

        /** However long the labels are, at most this much of the plot goes to them per side. */
        const val MAX_LABEL_GUTTER = 0.2f

        const val FULL_CIRCLE = 360.0

        /**
         * Side of the box fitted inside the projected hole, as a fraction of the
         * hole's own extent. Under `√2` on purpose — see [centerBounds].
         */
        const val INSCRIBED_FACTOR = 1.34

        /** How far a debug normal spur reaches, in world units. */
        const val DEBUG_NORMAL_LENGTH = 0.25

        const val LUMA_R = 0.2126
        const val LUMA_G = 0.7152
        const val LUMA_B = 0.0722

        /** Above this relative luminance, black text reads better than white. */
        const val LIGHT_ON_DARK_THRESHOLD = 0.55
    }
}

/** Everything the world geometry depends on, and nothing else. */
internal data class WorldKey(
    val radiusPx: Int,
    val reveal: Int,
    val explode: String,
)

/** Everything the projection depends on: the world, plus where the reader is. */
internal data class ViewKey(
    val world: WorldKey,
    val plot: ChartRect,
    val camera: Chart3DCamera,
)

/** Tessellated sectors in world space, before any camera. */
internal class WorldFrame(
    val key: WorldKey,
    val layout: Radial3DLayout,
    val scene: Chart3DScene,
)

/** What the layer projected last, kept so a hit test and a draw cannot disagree. */
internal class RenderedRadial(
    val key: ViewKey,
    val layout: Radial3DLayout,
    val objects: List<Chart3DObject>,
    val projector: Chart3DProjector,
    val faces: List<ProjectedFace>,
    val diagnostics: Chart3DDiagnostics,
) {
    /** The depth of the nearest face belonging to [key], for label ordering. */
    fun frontDepthOf(key: Chart3DKey): Double =
        faces.filter { it.key == key }.minOfOrNull { it.depth } ?: Double.MAX_VALUE

    /**
     * The projected bounding box of a slice's visible front cap, or `null` when
     * none of it survived culling.
     *
     * What decides whether an inside label fits. Deliberately the *top surface*
     * and not the whole slice: a label centred on the slice would otherwise be
     * permitted by the height of a rim that carries no room for text at all.
     */
    fun capExtentOf(key: Chart3DKey): ChartRect? {
        val cap = faces.filter { it.key == key && it.side == FaceSide.Top }
        if (cap.isEmpty()) return null
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        cap.forEach { face ->
            face.points.forEach { point ->
                minX = min(minX, point.x); maxX = max(maxX, point.x)
                minY = min(minY, point.y); maxY = max(maxY, point.y)
            }
        }
        if (!minX.isFinite()) return null
        // Two thirds of the cap's box: a sector is not a rectangle, and a label
        // as wide as the cap's bounding box would hang over the arc at both
        // ends. The fraction is conservative rather than exact — the exact
        // answer is the largest rectangle inside a projected annular sector,
        // which is not worth solving for a label that can simply move outside.
        val insetX = (maxX - minX) * (1.0 - CAP_USABLE) / 2.0
        val insetY = (maxY - minY) * (1.0 - CAP_USABLE) / 2.0
        return ChartRect(
            left = (minX + insetX).toFloat(),
            top = (minY + insetY).toFloat(),
            right = (maxX - insetX).toFloat(),
            bottom = (maxY - insetY).toFloat(),
        )
    }

    /** The brightness a slice's cap was drawn at, for label contrast. */
    fun capBrightnessOf(key: Chart3DKey): Double =
        faces.firstOrNull { it.key == key && it.side == FaceSide.Top }?.brightness ?: 1.0

    /** The world sector behind [key], for tests and diagnostics. */
    fun sectorOf(key: Chart3DKey): RadialSector3D? =
        layout.slices.firstOrNull { it.key == key }?.sector

    private companion object {
        /** How much of a cap's bounding box a label may use. */
        const val CAP_USABLE = 0.66
    }
}
