package io.devkit.chartkit.layer.custom

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.axis.ChartAxisId
import io.devkit.chartkit.charts.ExperimentalChartKitApi
import io.devkit.chartkit.coordinate.CartesianCoordinates
import io.devkit.chartkit.coordinate.PolarCoordinates
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.interaction.HitTestMode
import io.devkit.chartkit.layer.ChartLayerEntry
import io.devkit.chartkit.layer.ChartLayerRenderer
import io.devkit.chartkit.layer.ChartLayerSummary
import io.devkit.chartkit.layer.ChartRenderContext
import io.devkit.chartkit.model.AnyChartSelection
import io.devkit.chartkit.model.ChartSelection
import io.devkit.chartkit.model.ChartX
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.theme.ChartColors
import io.devkit.chartkit.theme.ChartDimensions
import io.devkit.chartkit.theme.ChartTypography
import io.devkit.chartkit.viewport.ChartViewport

/**
 * What a custom Cartesian layer is allowed to see.
 *
 * ### A read-only view, not the renderer's own state
 *
 * Everything here is a question the layer legitimately has to ask — where does
 * this value sit, how big is the plot, what is the theme, is anything selected.
 * What is deliberately absent is the renderer's mutable innards: the layer list,
 * the other layers' geometry, the gesture coordinator, the animation clock's
 * `Animatable`. Exposing those would make every internal refactor a breaking
 * change, and would let a custom layer put the chart into a state the chart
 * cannot get out of.
 *
 * ### Domain and value, never x and y
 *
 * The same rule the built-in layers follow. [pointAt] is the single place the
 * pair becomes a screen coordinate, so a custom layer written once works on a
 * horizontal chart as well as a vertical one.
 */
@ExperimentalChartKitApi
interface CartesianLayerContext {

    /** The region data is drawn inside, in pixels. */
    val plotArea: ChartRect

    /** Which axis carries the domain. */
    val orientation: ChartOrientation

    /** The visible window of the domain, as fractions of its whole. */
    val viewport: ChartViewport

    /** The `0..1` initial-draw fraction. Always `1` in a static render. */
    val reveal: Float

    /** Whether this frame is for a reader or for a picture. */
    val renderMode: ChartRenderMode

    val colors: ChartColors
    val typography: ChartTypography
    val dimensions: ChartDimensions
    val textMeasurer: TextMeasurer

    /** The current selection, or `null`. */
    val selection: ChartSelection<Any?>?

    /**
     * [dp] in pixels, at the chart's density.
     *
     * The density itself is not exposed: a [DrawScope] already carries one, and
     * two properties of the same name meaning nearly the same thing is how a
     * caller ends up converting against the wrong one.
     */
    fun px(dp: Dp): Float

    /** The pixel position of [value] on the value axis. */
    fun positionOfValue(value: Double): Float

    /** The value at a pixel position on the value axis. */
    fun valueAt(position: Float): Double

    /**
     * The pixel position of a domain value, or `null` when it is not on the
     * axis.
     *
     * Takes the same `Any?` the data's `x` lambda returns and resolves it
     * through the chart's own [io.devkit.chartkit.model.ChartXResolver], so a
     * custom layer marks `"Mar"` on a category chart and `releaseMillis` on a
     * time chart with no conversion and no separate call per axis kind.
     */
    fun positionOfDomain(value: Any?): Float?

    /** A domain-and-value pair as a screen position. */
    fun pointAt(domainPosition: Float, valuePosition: Float): ChartOffset

    /** The domain-axis component of a screen position. */
    fun domainOf(point: ChartOffset): Float

    /** The value-axis component of a screen position. */
    fun valueOf(point: ChartOffset): Float
}

/**
 * The drawing scope of a custom Cartesian layer.
 *
 * A [DrawScope] and a [CartesianLayerContext] at once, so the body reads like
 * ordinary Compose drawing with the chart's geometry in scope:
 *
 * ```kotlin
 * customLayer(id = "weekend-shading") {
 *     val from = positionOfDomain(weekendStart) ?: return@customLayer
 *     val to = positionOfDomain(weekendEnd) ?: return@customLayer
 *     drawRect(
 *         color = colors.annotation.region,
 *         topLeft = Offset(from, plotArea.top),
 *         size = Size(to - from, plotArea.height),
 *     )
 * }
 * ```
 */
@ExperimentalChartKitApi
class CartesianLayerScope internal constructor(
    context: CartesianLayerContext,
    drawScope: DrawScope,
) : CartesianLayerContext by context, DrawScope by drawScope

/** One item a custom layer contributes to the accessibility summary. */
@ExperimentalChartKitApi
data class CustomLayerItem(
    val label: String,
    val value: Double? = null,
    /** A complete phrase replacing the default `"label: value"`. */
    val detail: String? = null,
)

/** What a custom layer's hit test found. */
@ExperimentalChartKitApi
data class CustomLayerHit(
    val label: String,
    val value: Double,
    /** Where a tooltip should be anchored. */
    val position: ChartOffset,
    /** The caller's own object, handed back on the selection. */
    val item: Any? = null,
    val pointIndex: Int = 0,
)

/** One legend row a custom layer contributes. */
@ExperimentalChartKitApi
data class CustomLayerLegendEntry(
    val id: String,
    val label: String,
    val paletteIndex: Int = 0,
    val colorOverride: Int? = null,
)

/**
 * A custom Cartesian layer, as declared through the layer DSL.
 *
 * ### Only [draw] is required
 *
 * A layer that shades a region needs nothing else. Hit testing, accessibility
 * and the legend are separate optional lambdas rather than members of an
 * interface, so a two-line custom layer stays two lines instead of three empty
 * overrides — the same reasoning that keeps
 * [io.devkit.chartkit.layer.ChartLayerRenderer] small internally.
 *
 * ### Geometry preparation
 *
 * Do it in the caller's own `remember`, outside the DSL, and capture the result
 * in [draw]. The DSL block runs during composition and the draw lambda runs per
 * frame, so anything expensive computed inside either would be recomputed for
 * every frame or every recomposition. ChartKit does not offer a "prepare" hook
 * because a `remember` in the caller's own scope is both simpler and correctly
 * keyed on the caller's own inputs.
 */
@ExperimentalChartKitApi
class CustomCartesianLayer internal constructor(
    val id: String,
    internal val clipToPlot: Boolean,
    internal val valueAxisId: ChartAxisId,
    internal val draw: CartesianLayerScope.() -> Unit,
    internal val hitTest: (CartesianLayerContext.(ChartOffset) -> CustomLayerHit?)?,
    internal val describe: (() -> List<CustomLayerItem>)?,
    internal val legendEntries: List<CustomLayerLegendEntry>,
    internal val seriesName: String,
)

/**
 * Adapts a [CustomCartesianLayer] to the internal renderer contract.
 *
 * The bridge is one class rather than the DSL producing a `ChartLayerRenderer`
 * directly, which keeps the internal interface free to change without breaking
 * anybody's custom layer.
 */
@OptIn(ExperimentalChartKitApi::class)
internal class CustomLayerRenderer(
    private val spec: CustomCartesianLayer,
    private val resolveDomain: (Any?) -> Float?,
) : ChartLayerRenderer {

    override val id: String get() = spec.id
    override val seriesIds: List<String> get() = listOf(spec.id)
    override val clipToPlot: Boolean get() = spec.clipToPlot
    override val valueAxisId: ChartAxisId get() = spec.valueAxisId

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val cartesian = context.coordinates as? CartesianCoordinates ?: return
        if (cartesian.plotArea.isEmpty) return
        CartesianLayerScope(ContextAdapter(context, cartesian, resolveDomain), scope).apply(spec.draw)
    }

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val test = spec.hitTest ?: return null
        val cartesian = context.coordinates as? CartesianCoordinates ?: return null
        val adapter = ContextAdapter(context, cartesian, resolveDomain)
        val hit = adapter.test(point) ?: return null
        return ChartSelection(
            seriesId = spec.id,
            seriesName = spec.seriesName,
            seriesIndex = 0,
            pointIndex = hit.pointIndex,
            x = ChartX.Category(hit.label),
            y = hit.value,
            item = hit.item,
            position = hit.position,
        )
    }

    /**
     * The layer's own items, when it supplied any.
     *
     * A custom layer that says nothing contributes nothing rather than an empty
     * series, so a decorative shading layer does not make a screen reader
     * announce "0 data points".
     *
     * The lambda takes no geometry on purpose. What a reader needs to hear —
     * "Target: 100,000", "Weekend" — is a fact about the data, not about where
     * it landed in pixels, and a description that depended on the plot size
     * would change when the device rotated.
     */
    override fun describe(): List<ChartLayerSummary> {
        val items = spec.describe?.invoke().orEmpty()
        if (items.isEmpty()) return emptyList()
        return listOf(
            ChartLayerSummary(
                seriesId = spec.id,
                seriesName = spec.seriesName,
                pointCount = items.size,
                entries = items.map { ChartLayerEntry(it.label, it.value, it.detail) },
            ),
        )
    }
}

/** Presents a render context as the read-only view a custom layer sees. */
@OptIn(ExperimentalChartKitApi::class)
private class ContextAdapter(
    private val context: ChartRenderContext,
    private val coordinates: CartesianCoordinates,
    private val resolveDomain: (Any?) -> Float?,
) : CartesianLayerContext {

    override val plotArea: ChartRect get() = coordinates.plotArea
    override val orientation: ChartOrientation get() = coordinates.orientation
    override val viewport: ChartViewport get() = context.viewport
    override val reveal: Float get() = context.reveal
    override val renderMode: ChartRenderMode get() = context.renderMode
    override val colors: ChartColors get() = context.colors
    override val typography: ChartTypography get() = context.typography
    override val dimensions: ChartDimensions get() = context.dimensions
    override val textMeasurer: TextMeasurer get() = context.textMeasurer
    override val selection: ChartSelection<Any?>? get() = context.selection

    override fun px(dp: Dp): Float = context.px(dp)
    override fun positionOfValue(value: Double): Float = coordinates.positionOfValue(value)
    override fun valueAt(position: Float): Double = coordinates.valueAt(position)
    override fun positionOfDomain(value: Any?): Float? = resolveDomain(value)
    override fun pointAt(domainPosition: Float, valuePosition: Float): ChartOffset =
        coordinates.pointAt(domainPosition, valuePosition)

    override fun domainOf(point: ChartOffset): Float = coordinates.domainOf(point)
    override fun valueOf(point: ChartOffset): Float = coordinates.valueOf(point)
}

// ---- polar ------------------------------------------------------------------

/**
 * What a custom polar layer is allowed to see.
 *
 * The polar analogue of [CartesianLayerContext], and deliberately **not** the
 * same interface with nullable halves. A polar layer has no value axis and no
 * domain axis; forcing it to answer `positionOfValue` would mean either lying
 * or returning null from half the methods, and a caller could not tell which
 * were meaningful.
 */
@ExperimentalChartKitApi
interface PolarLayerContext {

    val plotArea: ChartRect
    /**
     * The circle's centre, in pixels.
     *
     * Named for the chart rather than as `center`, which a [DrawScope] already
     * defines as the middle of the *canvas* — a different point whenever the
     * chart reserved a gutter, and a silent one to confuse.
     */
    val polarCenter: ChartOffset
    val innerRadius: Float
    val outerRadius: Float
    val startAngle: Float
    val sweepAngle: Float

    val reveal: Float
    val renderMode: ChartRenderMode
    val colors: ChartColors
    val typography: ChartTypography
    val dimensions: ChartDimensions
    val textMeasurer: TextMeasurer
    val selection: ChartSelection<Any?>?

    fun px(dp: Dp): Float

    /** The screen position at an angle and a radius. */
    fun pointAt(angleDegrees: Float, radius: Float): ChartOffset

    /** The angle from the centre to a point, in ChartKit's convention. */
    fun angleOf(point: ChartOffset): Float

    /** The distance from the centre to a point. */
    fun radiusOf(point: ChartOffset): Float
}

/** The drawing scope of a custom polar layer. */
@ExperimentalChartKitApi
class PolarLayerScope internal constructor(
    context: PolarLayerContext,
    drawScope: DrawScope,
) : PolarLayerContext by context, DrawScope by drawScope

/**
 * A custom polar layer, added to any polar chart.
 *
 * ```kotlin
 * DonutChart(
 *     data = usage, value = { it.value }, label = { it.label },
 *     customLayers = listOf(
 *         polarLayer("target-ring") {
 *             drawCircle(
 *                 color = colors.annotation.line,
 *                 radius = innerRadius + (outerRadius - innerRadius) * 0.72f,
 *                 center = Offset(center.x, center.y),
 *                 style = Stroke(width = px(1.dp)),
 *             )
 *         },
 *     ),
 * )
 * ```
 *
 * A parameter on the existing charts rather than a separate composition DSL:
 * what a caller actually wants is to add a mark to *their* donut, not to
 * reassemble one from parts.
 */
@ExperimentalChartKitApi
class CustomPolarLayer internal constructor(
    val id: String,
    internal val draw: PolarLayerScope.() -> Unit,
    internal val hitTest: (PolarLayerContext.(ChartOffset) -> CustomLayerHit?)?,
    internal val seriesName: String,
)

/** Declares a custom polar layer. */
@ExperimentalChartKitApi
fun polarLayer(
    id: String,
    seriesName: String = id,
    hitTest: (PolarLayerContext.(ChartOffset) -> CustomLayerHit?)? = null,
    draw: PolarLayerScope.() -> Unit,
): CustomPolarLayer = CustomPolarLayer(id, draw, hitTest, seriesName)

/** Adapts a [CustomPolarLayer] to the internal renderer contract. */
@OptIn(ExperimentalChartKitApi::class)
internal class CustomPolarLayerRenderer(
    private val spec: CustomPolarLayer,
) : ChartLayerRenderer {

    override val id: String get() = spec.id
    override val seriesIds: List<String> get() = listOf(spec.id)

    override fun draw(scope: DrawScope, context: ChartRenderContext) {
        val polar = context.coordinates as? PolarCoordinates ?: return
        if (!polar.isDrawable) return
        PolarLayerScope(PolarContextAdapter(context, polar), scope).apply(spec.draw)
    }

    override fun hitTest(
        point: ChartOffset,
        context: ChartRenderContext,
        mode: HitTestMode,
    ): AnyChartSelection? {
        val test = spec.hitTest ?: return null
        val polar = context.coordinates as? PolarCoordinates ?: return null
        val hit = PolarContextAdapter(context, polar).test(point) ?: return null
        return ChartSelection(
            seriesId = spec.id,
            seriesName = spec.seriesName,
            seriesIndex = 0,
            pointIndex = hit.pointIndex,
            x = ChartX.Category(hit.label),
            y = hit.value,
            item = hit.item,
            position = hit.position,
        )
    }
}

@OptIn(ExperimentalChartKitApi::class)
private class PolarContextAdapter(
    private val context: ChartRenderContext,
    private val coordinates: PolarCoordinates,
) : PolarLayerContext {

    override val plotArea: ChartRect get() = coordinates.plotArea
    override val polarCenter: ChartOffset get() = coordinates.center
    override val innerRadius: Float get() = coordinates.innerRadius
    override val outerRadius: Float get() = coordinates.outerRadius
    override val startAngle: Float get() = coordinates.startAngle
    override val sweepAngle: Float get() = coordinates.sweepAngle
    override val reveal: Float get() = context.reveal
    override val renderMode: ChartRenderMode get() = context.renderMode
    override val colors: ChartColors get() = context.colors
    override val typography: ChartTypography get() = context.typography
    override val dimensions: ChartDimensions get() = context.dimensions
    override val textMeasurer: TextMeasurer get() = context.textMeasurer
    override val selection: ChartSelection<Any?>? get() = context.selection

    override fun px(dp: Dp): Float = context.px(dp)
    override fun pointAt(angleDegrees: Float, radius: Float): ChartOffset =
        coordinates.pointAt(angleDegrees, radius)

    override fun angleOf(point: ChartOffset): Float = coordinates.angleOf(point)
    override fun radiusOf(point: ChartOffset): Float = coordinates.radiusOf(point)
}
