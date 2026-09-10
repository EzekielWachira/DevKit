package io.devkit.chartkit.layer.three

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import io.devkit.chartkit.coordinate.DomainAxis
import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartSelectionDetails
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.SizeScale
import io.devkit.chartkit.three.Bounds3D
import io.devkit.chartkit.three.Cartesian3DCoordinates
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DDiagnostics
import io.devkit.chartkit.three.Chart3DFrame
import io.devkit.chartkit.three.Chart3DGridLine
import io.devkit.chartkit.three.Chart3DGridPlanes
import io.devkit.chartkit.three.Chart3DHitTest
import io.devkit.chartkit.three.Chart3DKey
import io.devkit.chartkit.three.Chart3DLighting
import io.devkit.chartkit.three.Chart3DMark
import io.devkit.chartkit.three.Chart3DObject
import io.devkit.chartkit.three.Chart3DPlane
import io.devkit.chartkit.three.Chart3DPlotBox
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Chart3DProjector
import io.devkit.chartkit.three.Chart3DReserve
import io.devkit.chartkit.three.Chart3DRole
import io.devkit.chartkit.three.Chart3DScene
import io.devkit.chartkit.three.Chart3DSceneDepth
import io.devkit.chartkit.three.Cuboid3D
import io.devkit.chartkit.three.Marker3D
import io.devkit.chartkit.three.Point3D
import io.devkit.chartkit.three.Projected2D
import io.devkit.chartkit.three.Projected3D
import io.devkit.chartkit.three.ProjectedFace
import io.devkit.chartkit.three.ProjectedMark
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How much detail a 3D scatter spends on each marker.
 *
 * A performance *policy*, not a marker choice: the marker says what a point
 * looks like and this says how much of that ChartKit will actually pay for.
 * The split matters because the right marker for a hundred observations and the
 * right marker for fifty thousand differ, and a caller should not have to
 * rewrite their chart between the two.
 */
enum class Scatter3DRenderMode {

    /**
     * [Rich] up to [Scatter3DLayer.RICH_POINT_LIMIT] visible points, [Optimized]
     * beyond it. The default.
     *
     * The threshold is a documented constant and not a hidden heuristic, so a
     * caller can predict which mode a dataset will get and pin it if the answer
     * is wrong for their case. It is a count of *visible* points, so hiding a
     * series through the legend can move a chart from one mode to the other —
     * which is correct: the cost is what changed.
     */
    Auto,

    /** Every marker as configured: sphere shading, outlines, cube faces. */
    Rich,

    /**
     * Flat billboard discs, no outline, no gradient.
     *
     * One filled circle per point and nothing else. The depth cue that
     * survives is the strongest one anyway — the arrangement of the cloud and
     * the perspective size falloff — and what is lost is the roundness of
     * markers that are six pixels across.
     */
    Optimized,
}

/**
 * What is drawn to help a reader place a selected point in the volume.
 *
 * ### Why a 3D chart cannot have a crosshair
 *
 * A 2D crosshair works because a screen position determines a domain value.
 * Under a projection it does not: every pixel is a whole ray through the
 * volume, so a vertical line down the plot names no x, and drawing one anyway
 * would be a confident lie. Guides answer the question a crosshair was for —
 * *where is this point on each axis* — by starting from the point's own
 * analytical coordinates and drawing to the planes those coordinates are read
 * against.
 */
enum class Scatter3DGuides {

    /** Nothing. The default: a chart is not always being interrogated. */
    None,

    /**
     * Three lines from the selected point to the floor, the back wall and the
     * side wall.
     *
     * The classic drop-line reading. Each line is parallel to one axis, so
     * following it tells the reader which coordinate it is resolving.
     */
    Axes,

    /**
     * [Axes], plus a mark where the point lands on each of the three planes.
     *
     * The shadow of the point on the XZ, XY and YZ planes. Worth the extra ink
     * where a reader is comparing two points' positions on one plane rather
     * than reading absolute values off the axes.
     */
    Planes,
    ;

    val drawsLines: Boolean get() = this != None
    val drawsMarks: Boolean get() = this == Planes
}

/**
 * What the camera is fitted to.
 *
 * ### The case for not fitting the content
 *
 * The default fits everything, which is right when the data is the picture: a
 * cloud that reaches the corners of its volume should fill the plot. It is
 * wrong when a single distant observation is present, because fitting to it
 * shrinks every other point to make room for one — and wrong again on a
 * streaming chart, where each arriving outlier rescales the whole scene and the
 * cloud appears to breathe.
 *
 * [Volume] fits the declared plot box instead. Points outside their domains are
 * still drawn, still selectable, and visibly outside the frame, which is the
 * honest presentation of a value that left the axis.
 */
enum class Chart3DSceneFit {

    /** Fit everything the scene holds, points included. The default. */
    Content,

    /** Fit the plot volume; let points outside their domains fall outside it. */
    Volume,
}

/** One observation, already resolved to three numbers and its encodings. */
internal class Scatter3DPoint(
    val sourceIndex: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val size: Double? = null,
    val colorValue: Double? = null,
    val item: Any? = null,
)

/** One scatter series' identity and its points. */
internal class Scatter3DSeriesInfo(
    val seriesId: String,
    val seriesName: String,
    val paletteIndex: Int,
    val colorOverride: Int?,
    val points: List<Scatter3DPoint>,
)

/**
 * One analytical axis of a 3D scatter: a scale, its ticks and how they read.
 *
 * The three are built identically, from three [io.devkit.chartkit.axis.ChartAxis]
 * configurations, by [io.devkit.chartkit.charts.buildScatter3DLayer]. That
 * symmetry is the point of the type: the Z axis is not a depth setting with a
 * label bolted on, it is the same thing X and Y are.
 */
internal class Scatter3DAxis(
    val scale: LinearScale,
    val ticks: List<Double>,
    val labels: List<TextLayoutResult>,
    val title: TextLayoutResult?,
    val displayName: String?,
    val formatter: ChartValueFormatter,
    val unitSymbol: String?,
) {
    /** The `0..1` position of each tick. Parallel to [labels]. */
    val fractions: List<Double> = ticks.map { scale.fraction(it) }

    /** [value] through this axis' own formatter and unit. */
    fun format(value: Double): String {
        val text = formatter.format(value)
        return unitSymbol?.takeIf { it.isNotBlank() }?.let { "$text $it" } ?: text
    }

    /** The same axis with its title dropped, when there was no room for it. */
    fun withoutTitle(): Scatter3DAxis = Scatter3DAxis(
        scale = scale,
        ticks = ticks,
        labels = labels,
        title = null,
        // The *name* survives, because it is not a layout decision: a tooltip
        // and a screen reader still say "Income" for an axis whose title was
        // too wide to draw beside a narrow chart.
        displayName = displayName,
        formatter = formatter,
        unitSymbol = unitSymbol,
    )

    companion object {
        val None: Scatter3DAxis = Scatter3DAxis(
            scale = LinearScale(
                io.devkit.chartkit.scale.NumericDomain.Default,
                rangeStart = 0f,
                rangeEnd = 1f,
            ),
            ticks = emptyList(),
            labels = emptyList(),
            title = null,
            displayName = null,
            formatter = ChartValueFormatter.Raw,
            unitSymbol = null,
        )
    }
}

/** The three axes' measured text, and the gutters it needs. */
internal class Scatter3DAxisFurniture(
    val x: Scatter3DAxis = Scatter3DAxis.None,
    val y: Scatter3DAxis = Scatter3DAxis.None,
    val z: Scatter3DAxis = Scatter3DAxis.None,
    val leftGutter: Float = 0f,
    val bottomGutter: Float = 0f,
    val rightGutter: Float = 0f,
    val leftLabelExtent: Float = 0f,
    val bottomLabelExtent: Float = 0f,
    val rightLabelExtent: Float = 0f,
) {
    val isEmpty: Boolean
        get() = x.labels.isEmpty() && y.labels.isEmpty() && z.labels.isEmpty() &&
            x.title == null && y.title == null && z.title == null
}

/**
 * A true X/Y/Z scatter, drawn through the shared 3D scene.
 *
 * ### The pipeline, and where each stage is cached
 *
 * ```
 * caller's objects
 *   → three independent scales           (Cartesian3DCoordinates)
 *   → world points inside the plot box   ← cached on the plot size alone
 *   → Chart3DScene: frame panels + marks
 *   → Chart3DProjector                   ← rebuilt on any camera change
 *   → one back-to-front order over faces and marks
 *   → draw / hit test / guides
 * ```
 *
 * The two caches are the whole performance story of a camera drag. World points
 * depend on the data and the plot box and on nothing about the camera, so a
 * drag reuses every one of them; the projection depends on the camera and
 * nothing else, so a tooltip appearing reuses that. Neither the scales nor the
 * domains are touched by either.
 *
 * ### Why the frame and the points are one scene
 *
 * Because "is this point in front of the back wall" has exactly one right
 * answer and it is a depth comparison. Drawing the frame first and the markers
 * afterwards — which is what two layers would have to do — puts every point in
 * front of every wall, so a point behind the volume's far face is drawn on top
 * of it and the reader loses the one cue that says the cloud has depth. The
 * projector sorts faces and marks together; see
 * [io.devkit.chartkit.three.Chart3DProjectionResult.items].
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class Scatter3DLayer(
    override val id: String,
    private val series: List<Scatter3DSeriesInfo>,
    private val xScale: LinearScale,
    private val yScale: LinearScale,
    private val zScale: LinearScale,
    private val sceneDepth: Chart3DSceneDepth,
    private val cameraProvider: () -> Chart3DCamera,
    private val projection: Chart3DProjection,
    private val lighting: Chart3DLighting,
    private val frame: Chart3DFrame,
    private val gridPlanes: Chart3DGridPlanes,
    private val fit: Chart3DSceneFit,
    private val marker: Marker3D,
    private val markerRadiusPx: Float,
    private val sizeScale: SizeScale?,
    private val colorScale: ColorScale?,
    private val renderMode: Scatter3DRenderMode,
    private val guides: Scatter3DGuides,
    private val axisFurniture: Scatter3DAxisFurniture,
    private val onDiagnostics: ((Chart3DDiagnostics) -> Unit)?,
    private val debug: Chart3DDebug,
    override val valueAxisId: io.devkit.chartkit.axis.ChartAxisId,
) : ChartLayerRenderer {

    override val seriesIds: List<String> get() = series.map { it.seriesId }

    /** Labels are written outside the volume, so there is nothing to clip to. */
    override val clipToPlot: Boolean get() = false

    private val visiblePointCount: Int = series.sumOf { it.points.size }

    /**
     * Which marker actually gets drawn, after the render mode has had its say.
     *
     * Resolved once, in the constructor, because it depends only on the data
     * and the configuration. A mode that changed with the camera would make a
     * drag cross the threshold and back, and the markers would flicker between
     * two appearances for a dataset that had not changed.
     */
    private val effectiveMarker: Marker3D = when (renderMode) {
        Scatter3DRenderMode.Rich -> marker
        Scatter3DRenderMode.Optimized -> Marker3D.BillboardCircle
        Scatter3DRenderMode.Auto ->
            if (visiblePointCount <= RICH_POINT_LIMIT) marker else Marker3D.BillboardCircle
    }

    private val richShading: Boolean = effectiveMarker == Marker3D.Sphere

    private var world: WorldCloud? = null
    private var drawn: RenderedScatter? = null
    private var settled: RenderedScatter? = null

    // ---- drawing ----------------------------------------------------------

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val rendered = renderedFor(context, context.reveal) ?: return
        scope.drawGrid(rendered, context)
        scope.drawItems(rendered, context)
        scope.drawGuides(rendered, context)
        scope.drawAxes(rendered, context)
        onDiagnostics?.invoke(rendered.diagnostics)
    }

    /**
     * Faces and marks in one pass, in the order the projector settled.
     *
     * The single loop is the correctness argument. Splitting it — walls, then
     * cubes, then points — would be faster to read and would reintroduce
     * exactly the ordering bug the merged list exists to prevent.
     */
    private fun DrawScope.drawItems(rendered: RenderedScatter, context: ChartRenderContext) {
        val colors = context.colors
        val threeD = colors.threeD
        val frameStroke = context.px(context.dimensions.chart3DFrameWidth)
        val outlineWidth = if (richShading) {
            context.px(context.dimensions.scatter3DMarkerOutlineWidth)
        } else {
            0f
        }
        val selected = context.selection
        val shading = rendered.projector.markerShading

        rendered.items.forEach { item ->
            when (item) {
                is ProjectedFace -> {
                    val obj = rendered.objects.getOrNull(item.objectIndex) ?: return@forEach
                    val path = item.toPath()
                    if (obj.role == Chart3DRole.Frame) {
                        drawPath(
                            path,
                            threeD.frame.copy(alpha = threeD.frame.alpha * obj.opacity),
                        )
                        drawPath(path, threeD.frameBorder, style = Stroke(width = frameStroke))
                    } else {
                        val base = obj.colorOverride?.let { Color(it) }
                            ?: colors.seriesColor(obj.paletteIndex)
                        drawPath(path, shade(base, item.brightness))
                        if (outlineWidth > 0f) {
                            drawPath(path, threeD.edge, style = Stroke(width = outlineWidth))
                        }
                        if (isSelected(selected, item.key)) {
                            drawPath(path, colors.selectionHighlight)
                            drawPath(
                                path = path,
                                color = threeD.selectedOutline,
                                style = Stroke(width = frameStroke * 2f),
                            )
                        }
                    }
                    if (debug == Chart3DDebug.Wireframe) {
                        drawPath(path, colors.selectionGuide, style = Stroke(width = frameStroke))
                    }
                }

                is ProjectedMark -> {
                    val source = rendered.marks.getOrNull(item.markIndex) ?: return@forEach
                    val base = source.colorOverride?.let { Color(it) }
                        ?: colors.seriesColor(source.paletteIndex)
                    val centre = Offset(item.center.x.toFloat(), item.center.y.toFloat())
                    val radius = item.radius.toFloat()
                    if (richShading) {
                        drawCircle(
                            brush = sphereBrush(base, item, shading),
                            radius = radius,
                            center = centre,
                            alpha = source.opacity,
                        )
                    } else {
                        drawCircle(
                            color = shade(base, item.brightness),
                            radius = radius,
                            center = centre,
                            alpha = source.opacity,
                        )
                    }
                    if (outlineWidth > 0f) {
                        drawCircle(
                            color = threeD.markerOutline,
                            radius = radius,
                            center = centre,
                            style = Stroke(width = outlineWidth),
                        )
                    }
                    if (isSelected(selected, item.key)) {
                        drawCircle(
                            color = threeD.selectedOutline,
                            radius = radius + context.px(context.dimensions.selectionGuideWidth),
                            center = centre,
                            style = Stroke(
                                width = context.px(context.dimensions.selectionGuideWidth) * 2f,
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * A sphere's shading, as a radial gradient from the scene's own light.
     *
     * The gradient's centre is offset toward [Marker3DShading.offsetX] and
     * `offsetY`, which is where the surface normal of a real sphere points at
     * the light — so every marker in the scene is lit from the same direction,
     * and rotating the camera does not change it, because the light is fixed to
     * the camera exactly as it is for a column's faces.
     */
    private fun sphereBrush(
        base: Color,
        mark: ProjectedMark,
        shading: io.devkit.chartkit.three.Marker3DShading,
    ): Brush {
        val radius = mark.radius.toFloat()
        val offset = radius * io.devkit.chartkit.three.Marker3DShading.HIGHLIGHT_OFFSET.toFloat()
        return Brush.radialGradient(
            colors = listOf(shade(base, shading.highlight), shade(base, shading.terminator)),
            center = Offset(
                mark.center.x.toFloat() + (shading.offsetX * offset).toFloat(),
                mark.center.y.toFloat() + (shading.offsetY * offset).toFloat(),
            ),
            // Reaching past the disc, so the darkest stop lands at the limb
            // rather than inside it — a gradient that finishes early paints a
            // flat ring around the marker and it reads as a washer.
            radius = radius * GRADIENT_REACH,
        )
    }

    private fun DrawScope.drawGrid(rendered: RenderedScatter, context: ChartRenderContext) {
        if (rendered.gridLines.isEmpty()) return
        val colors = context.colors.threeD
        val width = context.px(context.dimensions.chart3DFrameWidth)
        rendered.gridLines.forEach { (from, to, plane) ->
            // The floor is seen at a glancing angle, so a line drawn on it at
            // the wall's weight reads considerably darker than the same line
            // seen square on. Fading it is what makes the two planes look like
            // they carry the same grid.
            val alpha = if (plane == Chart3DPlane.Floor) FLOOR_GRID_ALPHA else 1f
            drawLine(
                color = colors.frameGrid.copy(alpha = colors.frameGrid.alpha * alpha),
                start = Offset(from.x.toFloat(), from.y.toFloat()),
                end = Offset(to.x.toFloat(), to.y.toFloat()),
                strokeWidth = width,
            )
        }
    }

    /**
     * The selected point's position on each of the three reference planes.
     *
     * Built from the point's **analytical** coordinates and not from anything
     * on screen: the foot of the drop line to the floor is the world point
     * `(x, minY, z)`, which is where a reader would find the same x and z with
     * y taken away. A guide drawn as a screen-space vertical from the marker to
     * the bottom of the plot would point at nothing.
     */
    private fun DrawScope.drawGuides(rendered: RenderedScatter, context: ChartRenderContext) {
        if (!guides.drawsLines) return
        val selection = context.selection ?: return
        val point = rendered.worldOf(selection) ?: return
        val volume = rendered.coordinates.volume
        val colors = context.colors.threeD
        val width = context.px(context.dimensions.scatter3DGuideWidth)
        val markRadius = context.px(context.dimensions.scatter3DGuideWidth) * GUIDE_MARK_RADIUS

        val feet = listOf(
            Point3D(point.x, volume.minY, point.z),
            Point3D(volume.minX, point.y, point.z),
            Point3D(point.x, point.y, volume.maxZ),
        )
        val origin = rendered.projector.toScreen(point) ?: return
        feet.forEach { foot ->
            val at = rendered.projector.toScreen(foot) ?: return@forEach
            drawLine(
                color = colors.selectionGuideLine,
                start = Offset(origin.x.toFloat(), origin.y.toFloat()),
                end = Offset(at.x.toFloat(), at.y.toFloat()),
                strokeWidth = width,
            )
            if (guides.drawsMarks) {
                drawCircle(
                    color = colors.selectionGuideMark,
                    radius = markRadius,
                    center = Offset(at.x.toFloat(), at.y.toFloat()),
                )
            }
        }
    }

    /**
     * Three axes' ticks and titles, placed in world space and drawn upright.
     *
     * Which physical edge of the volume carries each axis is chosen from the
     * camera — see [RenderedScatter.edges] — so the Y labels are always down the
     * left of the picture, the X labels always along its near bottom edge and
     * the Z labels always down its right. What never changes is *which* axis is
     * which: a rotation moves the labels, it does not renumber them.
     */
    private fun DrawScope.drawAxes(rendered: RenderedScatter, context: ChartRenderContext) {
        if (axisFurniture.isEmpty) return
        val gap = context.px(context.dimensions.chart3DLabelGap)
        val labelColor = context.colors.axisLabel
        val titleColor = context.colors.axisTitle
        val edges = rendered.edges
        val volume = rendered.coordinates.volume

        // ### Why three axes need a collision test and two do not
        //
        // On a flat chart the value labels run down one edge and the domain
        // labels along another, and the two meet only at a corner nothing is
        // written in. Under a projection all three families converge on the
        // near-bottom corner of the volume: the last X tick and the first Z
        // tick are within a few pixels of each other at almost every camera
        // angle, and drawn unconditionally they overprint into an unreadable
        // smudge that looks like a rendering fault.
        //
        // So they go through the same placer the value labels and the pie's
        // outside labels use, in the order Y, X, Z — a dropped label is
        // therefore a Z one, which is the axis with the most room to lose one,
        // and never a Y one, which is the axis a reader is most often reading a
        // magnitude off.
        val placer = io.devkit.chartkit.layer.label.LabelPlacer(
            ChartRect(0f, 0f, size.width, size.height),
            axisFurniture.x.labels.size + axisFurniture.y.labels.size +
                axisFurniture.z.labels.size + AXIS_TITLE_SLOTS,
        )
        fun draw(label: TextLayoutResult, left: Float, top: Float, color: Color) {
            if (!placer.place(left, top, label.size.width.toFloat(), label.size.height.toFloat())) {
                return
            }
            drawText(textLayoutResult = label, color = color, topLeft = Offset(left, top))
        }

        axisFurniture.y.labels.forEachIndexed { index, label ->
            val fraction = axisFurniture.y.fractions.getOrNull(index) ?: return@forEachIndexed
            val at = rendered.projector.toScreen(
                Point3D(edges.yEdgeX, volume.minY + volume.height * fraction, edges.yEdgeZ),
            ) ?: return@forEachIndexed
            draw(
                label,
                at.x.toFloat() - label.size.width - gap,
                at.y.toFloat() - label.size.height / 2f,
                labelColor,
            )
        }

        axisFurniture.x.labels.forEachIndexed { index, label ->
            val fraction = axisFurniture.x.fractions.getOrNull(index) ?: return@forEachIndexed
            val at = rendered.projector.toScreen(
                Point3D(volume.minX + volume.width * fraction, volume.minY, edges.xEdgeZ),
            ) ?: return@forEachIndexed
            draw(label, at.x.toFloat() - label.size.width / 2f, at.y.toFloat() + gap, labelColor)
        }

        axisFurniture.z.labels.forEachIndexed { index, label ->
            val fraction = axisFurniture.z.fractions.getOrNull(index) ?: return@forEachIndexed
            val at = rendered.projector.toScreen(
                Point3D(edges.zEdgeX, volume.minY, volume.minZ + volume.depth * fraction),
            ) ?: return@forEachIndexed
            draw(
                label,
                at.x.toFloat() + gap,
                at.y.toFloat() - label.size.height / 2f,
                labelColor,
            )
        }

        // The two side titles are turned, and the same way round the flat axis
        // renderer turns its Start and End titles: a reader who has learnt to
        // tilt their head one way for a 2D chart's value axis should not have
        // to learn a second convention for the 3D one.
        axisFurniture.y.title?.let { title ->
            val at = rendered.projector.toScreen(
                Point3D(edges.yEdgeX, volume.center.y, edges.yEdgeZ),
            ) ?: return@let
            val centreX = max(title.size.height / 2f, at.x.toFloat() - axisFurniture.leftGutter +
                title.size.height / 2f)
            rotate(degrees = -90f, pivot = Offset(centreX, at.y.toFloat())) {
                drawText(
                    textLayoutResult = title,
                    color = titleColor,
                    topLeft = Offset(
                        centreX - title.size.width / 2f,
                        at.y.toFloat() - title.size.height / 2f,
                    ),
                )
            }
        }
        axisFurniture.x.title?.let { title ->
            val at = rendered.projector.toScreen(
                Point3D(volume.center.x, volume.minY, edges.xEdgeZ),
            ) ?: return@let
            draw(
                title,
                at.x.toFloat() - title.size.width / 2f,
                at.y.toFloat() + axisFurniture.bottomLabelExtent + gap,
                titleColor,
            )
        }
        axisFurniture.z.title?.let { title ->
            val at = rendered.projector.toScreen(
                Point3D(edges.zEdgeX, volume.center.y, volume.center.z),
            ) ?: return@let
            val centreX = min(
                size.width - title.size.height / 2f,
                at.x.toFloat() + axisFurniture.rightGutter - title.size.height / 2f,
            )
            rotate(degrees = 90f, pivot = Offset(centreX, at.y.toFloat())) {
                drawText(
                    textLayoutResult = title,
                    color = titleColor,
                    topLeft = Offset(
                        centreX - title.size.width / 2f,
                        at.y.toFloat() - title.size.height / 2f,
                    ),
                )
            }
        }
    }

    // ---- interaction ------------------------------------------------------

    /**
     * The observation under the pointer, front-most first.
     *
     * Settled geometry, not the animating frame, for the reason the column
     * layer gives: a gesture callback captures its render context once per
     * layout, so the reveal it carries is whatever it was when the chart was
     * measured.
     *
     * The search goes through the *merged* list, so a marker hidden behind an
     * opaque frame panel loses to the panel — and because a panel carries no
     * key, the answer is then "nothing" rather than "the point behind the wall".
     */
    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val rendered = renderedFor(context, reveal = 1f) ?: return null
        val slop = context.px(context.dimensions.scatter3DSelectionSlop).toDouble()
        val mark = rendered.markAt(point.x.toDouble(), point.y.toDouble(), slop)
            ?: return nearestByX(rendered, point, context, mode)
        return selectionFor(rendered, mark)
    }

    /**
     * The observation nearest the probe along the X axis, for keyboard stepping.
     *
     * ### Only for [HitTestMode.NearestDomain], and only in the data
     *
     * A *tap* that missed every marker has genuinely missed — there is nothing
     * under the finger — and answering it with the nearest point would make the
     * empty space inside the frame selectable. `NearestDomain` is how keyboard
     * and screen-reader stepping ask, and they are probing a position on the
     * domain rather than pointing at a mark.
     *
     * The answer is resolved in the **data**: the observation whose x is
     * closest to the probed x, which orders the cloud the way a reader stepping
     * through it expects and — this is the point of §132 — needs no rotation to
     * reach. A reader exploring a chart with a keyboard is reading values, and
     * requiring them to turn the scene first would make an accessible
     * interaction depend on a decorative one.
     */
    private fun nearestByX(
        rendered: RenderedScatter,
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        if (mode != HitTestMode.NearestDomain) return null
        val scale = (context.cartesian.domainAxis as? DomainAxis.Continuous)?.scale ?: return null
        val probed = scale.invert(context.cartesian.domainOf(point))
        if (!probed.isFinite()) return null
        var best: Pair<Scatter3DSeriesInfo, Scatter3DPoint>? = null
        var bestDistance = Double.MAX_VALUE
        series.forEach { source ->
            source.points.forEach { observation ->
                val distance = abs(observation.x - probed)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = source to observation
                }
            }
        }
        val (source, observation) = best ?: return null
        // Anchored where the observation actually is on screen, so a tooltip
        // opened by the keyboard lands on the marker it names.
        val anchor = rendered.projectedMarks.firstOrNull {
            it.key?.seriesId == source.seriesId && it.key?.pointIndex == observation.sourceIndex
        }?.center
        return selectionOf(source, observation, anchor)
    }

    private fun selectionFor(
        rendered: RenderedScatter,
        mark: ProjectedMark,
    ): AnyChartSelection? {
        val key = mark.key ?: return null
        val source = series.firstOrNull { it.seriesId == key.seriesId } ?: return null
        val observation = source.points.firstOrNull { it.sourceIndex == key.pointIndex }
            ?: return null
        return selectionOf(source, observation, mark.center)
    }

    private fun selectionOf(
        source: Scatter3DSeriesInfo,
        observation: Scatter3DPoint,
        anchor: Projected2D?,
    ): AnyChartSelection {
        return ChartSelection(
            seriesId = source.seriesId,
            seriesName = source.seriesName,
            seriesIndex = series.indexOf(source),
            pointIndex = observation.sourceIndex,
            x = ChartX.Numeric(observation.x),
            y = observation.y,
            item = observation.item,
            position = anchor
                ?.let { ChartOffset(it.x.toFloat(), it.y.toFloat()) }
                ?: ChartOffset.Zero,
            details = ChartSelectionDetails.Cartesian3D(
                x = observation.x,
                y = observation.y,
                z = observation.z,
                xTitle = axisFurniture.x.displayName,
                yTitle = axisFurniture.y.displayName,
                zTitle = axisFurniture.z.displayName,
                formattedX = axisFurniture.x.format(observation.x),
                formattedY = axisFurniture.y.format(observation.y),
                formattedZ = axisFurniture.z.format(observation.z),
                size = observation.size,
                colorValue = observation.colorValue,
            ),
        )
    }

    private fun isSelected(selection: AnyChartSelection?, key: Chart3DKey?): Boolean =
        selection != null && key != null &&
            selection.seriesId == key.seriesId && selection.pointIndex == key.pointIndex

    // ---- accessibility ----------------------------------------------------

    /**
     * Each series as its observations, in the caller's own numbers.
     *
     * Every entry carries a [ChartLayerEntry.detail] holding all three
     * coordinates, because a scatter observation is not one number: announcing
     * only its y would describe a third of the mark. Past
     * [ACCESSIBILITY_ENTRY_LIMIT] points the entries are dropped in favour of
     * the value range, which is the convention the rest of the library follows
     * for long series — nobody listens to fifty thousand announcements, and
     * building the strings costs a layout each.
     */
    override fun describe(): List<ChartLayerSummary> = series.map { source ->
        val long = source.points.size > ACCESSIBILITY_ENTRY_LIMIT
        ChartLayerSummary(
            seriesId = source.seriesId,
            seriesName = source.seriesName,
            pointCount = source.points.size,
            entries = if (long) {
                emptyList()
            } else {
                source.points.mapIndexed { index, point ->
                    ChartLayerEntry(
                        label = "${index + 1}",
                        value = point.y,
                        detail = describePoint(point),
                    )
                }
            },
            valueRange = if (long) {
                val values = source.points.map { it.y }
                (values.minOrNull() ?: 0.0)..(values.maxOrNull() ?: 0.0)
            } else {
                null
            },
        )
    }

    /**
     * What a screen reader hears for a selected observation.
     *
     * Three axis names and three formatted values, and nothing about depth. A
     * reader who cannot see the picture is not helped by "this point is in
     * front"; front is a fact about where the camera happens to be, and a
     * chart that announced it would be reporting its own rendering as data.
     */
    override fun describeSelection(
        selection: AnyChartSelection,
        formatter: ChartValueFormatter,
    ): String? {
        val detail = selection.cartesian3D ?: return null
        val name = selection.seriesName.ifBlank { selection.seriesId }
        val axes = listOf(
            (detail.xTitle ?: "X") to detail.formattedX,
            (detail.yTitle ?: "Y") to detail.formattedY,
            (detail.zTitle ?: "Z") to detail.formattedZ,
        ).joinToString(". ") { (label, value) -> "$label: $value" }
        return "$name, observation ${selection.pointIndex + 1}. $axes."
    }

    private fun describePoint(point: Scatter3DPoint): String = listOf(
        (axisFurniture.x.displayName ?: "X") to axisFurniture.x.format(point.x),
        (axisFurniture.y.displayName ?: "Y") to axisFurniture.y.format(point.y),
        (axisFurniture.z.displayName ?: "Z") to axisFurniture.z.format(point.z),
    ).joinToString(", ") { (label, value) -> "$label $value" }

    // ---- export -----------------------------------------------------------

    /**
     * The finished picture as flat primitives, in draw order.
     *
     * Faithful for the frame and for billboard markers, which really are filled
     * polygons and filled circles. A sphere marker's radial gradient has no
     * primitive in the scene model, so it is exported as a flat disc at its
     * mid-brightness — a difference in shading and never in position, size,
     * colour identity or order.
     */
    override fun renderScene(
        builder: io.devkit.chartkit.scene.ChartSceneBuilder,
        context: ChartRenderContext,
    ): Boolean {
        val rendered = renderedFor(context, reveal = 1f) ?: return true
        builder.group(id) {
            rendered.items.forEach { item ->
                when (item) {
                    is ProjectedFace -> {
                        val obj = rendered.objects.getOrNull(item.objectIndex) ?: return@forEach
                        val color = if (obj.role == Chart3DRole.Frame) {
                            context.colors.threeD.frame
                        } else {
                            val base = obj.colorOverride?.let { Color(it) }
                                ?: context.colors.seriesColor(obj.paletteIndex)
                            shade(base, item.brightness)
                        }
                        add(
                            io.devkit.chartkit.scene.ChartSceneNode.Path(
                                points = item.points.map {
                                    ChartOffset(it.x.toFloat(), it.y.toFloat())
                                },
                                color = color,
                                style = io.devkit.chartkit.scene.PaintStyle.Fill,
                                closed = true,
                            ),
                        )
                    }

                    is ProjectedMark -> {
                        val source = rendered.marks.getOrNull(item.markIndex) ?: return@forEach
                        val base = source.colorOverride?.let { Color(it) }
                            ?: context.colors.seriesColor(source.paletteIndex)
                        add(
                            io.devkit.chartkit.scene.ChartSceneNode.Circle(
                                center = ChartOffset(
                                    item.center.x.toFloat(),
                                    item.center.y.toFloat(),
                                ),
                                radius = item.radius.toFloat(),
                                color = shade(base, item.brightness),
                                style = io.devkit.chartkit.scene.PaintStyle.Fill,
                            ),
                        )
                    }
                }
            }
        }
        return true
    }

    // ---- pipeline ---------------------------------------------------------

    /**
     * World points for the whole cloud, reused across every camera change.
     *
     * Keyed on the plot rectangle alone, because that is the only thing outside
     * the data that changes where a point sits in the volume. A drag, a zoom, a
     * projection switch and a tooltip all leave this untouched, which is §189
     * and §191 in one cache.
     */
    private fun worldFor(plot: ChartRect): WorldCloud {
        world?.let { if (it.plot == plot) return it }
        val depth = Chart3DSceneDepth.resolve(
            policy = sceneDepth,
            // Cubic by default, as far as the plot allows: the three variables
            // are equally important and giving one of them visibly more room
            // than the others would suggest a hierarchy the data does not have.
            reference = min(plot.width, plot.height).toDouble(),
            minimum = MIN_SCENE_DEPTH,
        )
        val coordinates = Cartesian3DCoordinates(
            xScale = xScale,
            yScale = yScale,
            zScale = zScale,
            box = Chart3DPlotBox(
                width = plot.width.toDouble(),
                height = plot.height.toDouble(),
                depth = depth,
            ),
        )
        val placed = ArrayList<PlacedPoint>(visiblePointCount)
        series.forEach { source ->
            source.points.forEach { point ->
                val position = coordinates.worldOf(point.x, point.y, point.z) ?: return@forEach
                placed += PlacedPoint(
                    seriesId = source.seriesId,
                    paletteIndex = source.paletteIndex,
                    colorOverride = colorScale?.colorAt(point.colorValue)?.toArgb()
                        ?: source.colorOverride,
                    radius = sizeScale?.size(point.size)?.toDouble()
                        ?: markerRadiusPx.toDouble(),
                    position = position,
                    key = Chart3DKey(
                        seriesId = source.seriesId,
                        categoryIndex = point.sourceIndex,
                        category = "",
                        stackId = source.seriesId,
                        pointIndex = point.sourceIndex,
                    ),
                )
            }
        }
        return WorldCloud(plot, coordinates, placed).also { world = it }
    }

    @Suppress("LongMethod")
    private fun renderedFor(context: ChartRenderContext, reveal: Float): RenderedScatter? {
        val plot = context.cartesian.plotArea
        if (plot.isEmpty || series.isEmpty()) return null
        val camera = cameraProvider()
        val isSettled = reveal >= 1f
        val key = ScatterFrameKey(plot, camera, quantise(reveal), context.density.density)
        (if (isSettled) settled else drawn)?.let { if (it.key == key) return it }

        val cloud = worldFor(plot)
        val volume = cloud.coordinates.volume
        val panels = frame.panelsFor(volume, camera)

        // The reveal grows the markers rather than moving them. Moving them
        // would have to move them *from* somewhere, and every candidate origin
        // — the floor, the centre, the axis — is a position a reader could read
        // as data mid-animation.
        val revealed = reveal.coerceIn(0f, 1f).toDouble()
        val cubes = ArrayList<Chart3DObject>()
        val marks = ArrayList<Chart3DMark>(cloud.points.size)
        cloud.points.forEach { placed ->
            val radius = placed.radius * revealed
            if (radius <= 0.0) return@forEach
            if (effectiveMarker == Marker3D.Cube) {
                cubes += Chart3DObject(
                    geometry = Cuboid3D(
                        x = placed.position.x - radius,
                        width = radius * 2.0,
                        yStart = placed.position.y - radius,
                        yEnd = placed.position.y + radius,
                        z = placed.position.z - radius,
                        depth = radius * 2.0,
                        key = placed.key,
                    ),
                    paletteIndex = placed.paletteIndex,
                    colorOverride = placed.colorOverride,
                    role = Chart3DRole.Data,
                )
            } else {
                marks += Chart3DMark(
                    position = placed.position,
                    radius = radius,
                    marker = effectiveMarker,
                    paletteIndex = placed.paletteIndex,
                    colorOverride = placed.colorOverride,
                    key = placed.key,
                )
            }
        }

        val objects = panels + cubes
        val scene = Chart3DScene(objects, lighting, marks)
        val reserve = Chart3DReserve(
            left = axisFurniture.leftGutter,
            top = context.px(context.dimensions.chart3DPadding),
            right = axisFurniture.rightGutter,
            bottom = axisFurniture.bottomGutter,
        )
        val projector = Chart3DProjector.of(
            scene = scene,
            camera = camera,
            projection = projection,
            bounds = plot,
            reserve = reserve,
            fitTo = if (fit == Chart3DSceneFit.Volume) volume.corners() else null,
        ) ?: return null

        val result = projector.project(scene)
        val gridLines = frame.gridLines(
            volume = volume,
            camera = camera,
            planes = gridPlanes,
            xFractions = axisFurniture.x.fractions,
            yFractions = axisFurniture.y.fractions,
            zFractions = axisFurniture.z.fractions,
        ).mapNotNull { line -> projector.screenLine(line) }

        val rendered = RenderedScatter(
            key = key,
            coordinates = cloud.coordinates,
            points = cloud.points,
            projector = projector,
            objects = objects,
            marks = marks,
            items = result.items,
            projectedMarks = result.marks,
            gridLines = gridLines,
            edges = Chart3DEdges.of(projector, volume),
            diagnostics = result.diagnostics,
        )
        if (isSettled) settled = rendered else drawn = rendered
        return rendered
    }

    /** The last projected frame, for a test inspecting the geometry. */
    internal fun lastRendered(): RenderedScatter? = drawn ?: settled

    /** The marker each point is actually drawn with, after the render mode. */
    internal fun resolvedMarker(): Marker3D = effectiveMarker

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

    /** [base] at [brightness], with its alpha untouched. See [Column3DLayer]. */
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

    private fun quantise(reveal: Float): Int = (reveal.coerceIn(0f, 1f) * REVEAL_STEPS).roundToInt()

    internal companion object {

        /**
         * How many visible points [Scatter3DRenderMode.Auto] will draw richly.
         *
         * Stated as a constant, and a public one, because §106 is right that an
         * undocumented threshold is worse than no automatic mode at all: a
         * caller has to be able to predict which side of it their dataset falls
         * and to pin the mode when the answer is wrong for them. The number is
         * a starting point chosen so that a chart of a few thousand shaded
         * markers stays comfortable, not a measured limit — see the README for
         * what was actually measured.
         */
        const val RICH_POINT_LIMIT: Int = 2_000

        /** Past this many points a series is announced as a range, not as entries. */
        const val ACCESSIBILITY_ENTRY_LIMIT: Int = 200

        private const val REVEAL_STEPS = 240f

        /** A volume with no depth is a flat picture. */
        private const val MIN_SCENE_DEPTH = 1.0

        /** How far past the disc a sphere's gradient runs, so the limb is dark. */
        private const val GRADIENT_REACH = 1.15f

        /** The floor's grid, relative to the wall's, at a glancing angle. */
        private const val FLOOR_GRID_ALPHA = 0.6f

        /** A plane-projection mark's radius, in multiples of the guide's width. */
        private const val GUIDE_MARK_RADIUS = 2f

        /** Room in the placer for the three axis titles. */
        private const val AXIS_TITLE_SLOTS = 3
    }
}

/** One observation, placed in the volume, with its encodings resolved. */
internal class PlacedPoint(
    val seriesId: String,
    val paletteIndex: Int,
    val colorOverride: Int?,
    val radius: Double,
    val position: Point3D,
    val key: Chart3DKey,
)

/** The cloud in world space, and the coordinate system that put it there. */
internal class WorldCloud(
    val plot: ChartRect,
    val coordinates: Cartesian3DCoordinates,
    val points: List<PlacedPoint>,
)

/**
 * Which edge of the volume each axis writes its labels along.
 *
 * ### Chosen from the camera, and always to the same side of the picture
 *
 * Four vertical edges and four bottom edges are candidates, and which of them
 * is on the left, at the front or on the right depends entirely on the yaw. The
 * choice is made by *projecting* them and reading the answer off the screen —
 * leftmost for Y, nearest for X, rightmost for Z — rather than by a table of
 * yaw ranges, because a table has to be re-derived for every projection and
 * every pitch and is wrong at the boundaries.
 *
 * What this deliberately does **not** do is move an axis to a different
 * dimension. A rotation changes which physical edge carries the Z labels; it
 * never makes the Z labels describe x. §116, made structural.
 */
internal class Chart3DEdges(
    val yEdgeX: Double,
    val yEdgeZ: Double,
    val xEdgeZ: Double,
    val zEdgeX: Double,
) {
    companion object {
        fun of(projector: Chart3DProjector, volume: Bounds3D): Chart3DEdges {
            // The four vertical edges, taken at the floor where their labels
            // and the two horizontal axes meet.
            val corners = listOf(
                volume.minX to volume.minZ,
                volume.minX to volume.maxZ,
                volume.maxX to volume.minZ,
                volume.maxX to volume.maxZ,
            )
            val projected = corners.map { (x, z) ->
                Triple(x, z, projector.toScreen(Point3D(x, volume.minY, z)))
            }
            val usable = projected.filter { it.third != null }
            if (usable.isEmpty()) {
                return Chart3DEdges(volume.minX, volume.minZ, volume.minZ, volume.maxX)
            }
            val leftmost = usable.minByOrNull { it.third!!.x }!!
            val rightmost = usable.maxByOrNull { it.third!!.x }!!
            // The near bottom edge in x is whichever of the two z faces has the
            // smaller camera depth at its centre.
            val front = projector.toCamera(
                Point3D(volume.center.x, volume.minY, volume.minZ),
            ).z
            val back = projector.toCamera(
                Point3D(volume.center.x, volume.minY, volume.maxZ),
            ).z
            return Chart3DEdges(
                yEdgeX = leftmost.first,
                yEdgeZ = leftmost.second,
                xEdgeZ = if (front <= back) volume.minZ else volume.maxZ,
                zEdgeX = rightmost.first,
            )
        }
    }
}

/** A projected grid line: two screen points and the plane it lies on. */
internal data class ScreenGridLine(
    val from: Projected2D,
    val to: Projected2D,
    val plane: Chart3DPlane,
)

private fun Chart3DProjector.screenLine(line: Chart3DGridLine): ScreenGridLine? {
    val from = toScreen(line.from) ?: return null
    val to = toScreen(line.to) ?: return null
    return ScreenGridLine(from, to, line.plane)
}

/** Everything one projection of the cloud produced, kept so a hit test agrees with a draw. */
internal class RenderedScatter(
    val key: ScatterFrameKey,
    val coordinates: Cartesian3DCoordinates,
    val points: List<PlacedPoint>,
    val projector: Chart3DProjector,
    val objects: List<Chart3DObject>,
    val marks: List<Chart3DMark>,
    val items: List<Projected3D>,
    val projectedMarks: List<ProjectedMark>,
    val gridLines: List<ScreenGridLine>,
    val edges: Chart3DEdges,
    val diagnostics: Chart3DDiagnostics,
) {
    /**
     * A screen-space grid over the projected marks, for hit testing.
     *
     * ### Why an index, and why it is rebuilt with the projection
     *
     * A linear scan is fine for a hundred points and is not fine for fifty
     * thousand on every tap. The index is a uniform bucket grid over the marks'
     * own screen positions, so a tap examines the handful of points near it
     * rather than all of them.
     *
     * It is built **here**, inside the object that holds the projection it was
     * derived from, and that is the whole answer to staleness: a camera change
     * produces a new [RenderedScatter], which produces a new index. There is no
     * code path that can consult an index built for a different camera, because
     * there is no index that outlives its projection.
     *
     * Built lazily, so a chart nobody taps never pays for it.
     */
    private val index: MarkIndex? by lazy(LazyThreadSafetyMode.NONE) {
        if (projectedMarks.size < INDEX_THRESHOLD) null else MarkIndex.of(projectedMarks)
    }

    /**
     * The front-most mark under a pointer, or `null`.
     *
     * Front-most and not nearest: of two overlapping observations the reader
     * can only see one, and answering with the other would select something
     * that is not under their finger. Because [items] is in the projector's own
     * back-to-front order and marks keep that order in [projectedMarks], the
     * front-most is simply the last one in the list that contains the point.
     */
    fun markAt(x: Double, y: Double, slop: Double): ProjectedMark? {
        val candidates = index?.candidates(x, y, slop)
        if (candidates == null) return Chart3DHitTest.markAt(projectedMarks, x, y, slop)
        var best: ProjectedMark? = null
        var bestOrder = -1
        candidates.forEach { order ->
            val mark = projectedMarks.getOrNull(order) ?: return@forEach
            if (mark.key == null) return@forEach
            if (order > bestOrder && mark.contains(x, y, slop)) {
                best = mark
                bestOrder = order
            }
        }
        return best
    }

    /** The world position of the selected observation, for the guides. */
    fun worldOf(selection: AnyChartSelection): Point3D? = points.firstOrNull {
        it.key.seriesId == selection.seriesId && it.key.pointIndex == selection.pointIndex
    }?.position

    private companion object {
        /** Below this a scan is cheaper than the buckets it would allocate. */
        const val INDEX_THRESHOLD = 256
    }
}

/**
 * A uniform bucket grid over projected mark centres.
 *
 * A grid and not a quadtree: the marks are already in a bounded rectangle at
 * roughly uniform density, which is the case a grid is optimal for and the case
 * a quadtree's extra structure buys nothing in. It holds **positions in the
 * sorted mark list**, so the caller can still resolve ties by draw order.
 */
internal class MarkIndex private constructor(
    private val originX: Double,
    private val originY: Double,
    private val cell: Double,
    private val columns: Int,
    private val rows: Int,
    private val buckets: Array<MutableList<Int>?>,
) {
    /** Every mark whose bucket is within [slop] of ([x], [y]). */
    fun candidates(x: Double, y: Double, slop: Double): List<Int> {
        val reach = slop + cell
        val minColumn = ((x - reach - originX) / cell).toInt()
        val maxColumn = ((x + reach - originX) / cell).toInt()
        val minRow = ((y - reach - originY) / cell).toInt()
        val maxRow = ((y + reach - originY) / cell).toInt()
        val found = ArrayList<Int>()
        for (row in max(0, minRow)..min(rows - 1, maxRow)) {
            for (column in max(0, minColumn)..min(columns - 1, maxColumn)) {
                buckets[row * columns + column]?.let { found += it }
            }
        }
        return found
    }

    companion object {
        fun of(marks: List<ProjectedMark>): MarkIndex? {
            if (marks.isEmpty()) return null
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var maxRadius = 0.0
            marks.forEach { mark ->
                minX = min(minX, mark.center.x); maxX = max(maxX, mark.center.x)
                minY = min(minY, mark.center.y); maxY = max(maxY, mark.center.y)
                maxRadius = max(maxRadius, mark.radius)
            }
            if (!minX.isFinite() || !maxX.isFinite()) return null
            val width = max(maxX - minX, 1.0)
            val height = max(maxY - minY, 1.0)
            // Roughly one mark per cell: the grid should have about as many
            // buckets as there are points, so a lookup touches a constant few.
            val target = sqrt(width * height / marks.size.coerceAtLeast(1))
            val cell = max(target, max(maxRadius, MIN_CELL))
            val columns = (width / cell).toInt() + 1
            val rows = (height / cell).toInt() + 1
            if (columns.toLong() * rows > MAX_BUCKETS) return null
            val buckets = arrayOfNulls<MutableList<Int>>(columns * rows)
            marks.forEachIndexed { order, mark ->
                val column = ((mark.center.x - minX) / cell).toInt().coerceIn(0, columns - 1)
                val row = ((mark.center.y - minY) / cell).toInt().coerceIn(0, rows - 1)
                val slot = row * columns + column
                (buckets[slot] ?: ArrayList<Int>(4).also { buckets[slot] = it }) += order
            }
            return MarkIndex(minX, minY, cell, columns, rows, buckets)
        }

        private const val MIN_CELL = 4.0

        /** A cap, so a degenerate projection cannot allocate an enormous grid. */
        private const val MAX_BUCKETS = 1 shl 20
    }
}

/** Everything a projection of the cloud depends on, and nothing else. */
internal data class ScatterFrameKey(
    val plot: ChartRect,
    val camera: Chart3DCamera,
    val reveal: Int,
    val density: Float,
)
