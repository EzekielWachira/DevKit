package io.devkit.chartkit.axis

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.scale.DomainPolicy

/**
 * Which of a chart's two directions an axis measures.
 *
 * Stated rather than inferred from the edge it is drawn against, because the
 * edge is a layout decision and the dimension is not: a horizontal bar chart
 * puts its **value** axis along the bottom, and a chart that read "bottom axis"
 * as "domain axis" would build the wrong scale for it.
 */
enum class AxisDimension {

    /** The shared axis — categories, dates or numbers every layer is indexed by. */
    X,

    /** A value axis — the quantity a layer is measured in. */
    Y,
}

/**
 * Whether an axis is drawn.
 *
 * [Auto] is the interesting one: an axis with no visible series on it is
 * measuring nothing, and leaving it on the edge with ticks and a title tells a
 * reader that a quantity is being shown when it is not. Hiding it also gives
 * the width back to the plot, which is what makes legend toggling feel like it
 * did something.
 */
enum class AxisVisibility {
    Visible,
    Hidden,

    /** Visible while at least one visible layer is bound to it. */
    Auto,
}

/**
 * Which axes own the grid lines behind the plot.
 *
 * Default [Primary], and the default matters more here than most. One grid per
 * value axis produces three interleaved sets of horizontal lines at unrelated
 * intervals — a moiré that is not only ugly but actively misleading, because
 * every line looks like it means something and only a third of them do for any
 * given series.
 */
enum class AxisGridMode {

    /** Draw grid lines only if this is the chart's primary axis. The default. */
    Primary,

    /** Always draw them. For a chart that deliberately wants a second set. */
    Visible,

    /** Never draw them. */
    Hidden,
}

/**
 * How closely an axis' styling is tied to the series on it.
 *
 * [Neutral] by default: an axis is chart furniture, and three axes each painted
 * in their series' colour turn the edges of the plot into a second legend
 * competing with the first. [MatchSeries] is there for the case where a reader
 * genuinely has to trace three lines back to three scales, and it only colours
 * the tick labels and title — the axis line stays neutral, because a coloured
 * rule reads as data.
 */
enum class AxisStyleMode {
    Neutral,
    MatchSeries,
    Custom,
}

/**
 * One axis of a Cartesian chart: its identity, its role and how it is drawn.
 *
 * Splits cleanly from [ChartAxis], which is *appearance and scale* and knows
 * nothing about identity. That split is what lets `LineChart(yAxis = ChartAxis(...))`
 * keep working unchanged while a combo chart says the same things about three
 * axes at once.
 *
 * ```kotlin
 * ChartAxisSpec(
 *     id = ChartAxisId("temperature"),
 *     dimension = AxisDimension.Y,
 *     position = AxisPosition.End,
 *     title = "Temperature",
 *     unit = ChartUnit.Custom("°C", "degrees Celsius"),
 * )
 * ```
 *
 * @param axis the appearance and scale: ticks, formatter, line, log or linear.
 * @param title the axis' own label. Overrides [ChartAxis.title] when set, so
 *   the common case is one parameter rather than a nested `ChartAxis(title=…)`.
 * @param unit what the axis measures in. Used for labels, for announcements and
 *   for the series/axis mismatch check — see [ChartUnit].
 * @param domain how the axis picks its interval. Falls back to [ChartAxis.domain]
 *   and then to the chart's default.
 * @param visibility whether it is drawn; see [AxisVisibility].
 * @param grid whether it owns grid lines; see [AxisGridMode].
 * @param primary whether this is the chart's main axis for its dimension —
 *   grid ownership, unqualified annotations and value-axis zoom. Exactly one Y
 *   axis is primary; when none says so, the first declared one is.
 * @param offset an explicit distance from the plot edge, overriding the
 *   measured one. An escape hatch for a layout a measurement cannot know about;
 *   almost every chart should leave it `null` and let the engine measure. See
 *   [io.devkit.chartkit.layout.AxisMetrics].
 * @param style how closely the axis is tied to its series' colour.
 * @param alignTicks whether this axis takes part in
 *   [AxisTickAlignment.Aligned]. A log axis and a linear one cannot share tick
 *   rows without one of them lying, so a log axis opts out.
 * @param alignZero whether this axis' zero should land on the same screen row
 *   as every other axis asking for it. See [AxisTickAlignment].
 */
@Immutable
data class ChartAxisSpec(
    val id: ChartAxisId,
    val dimension: AxisDimension = AxisDimension.Y,
    val position: AxisPosition? = null,
    val axis: ChartAxis = ChartAxis.Default,
    val title: String? = null,
    val unit: ChartUnit = ChartUnit.None,
    val domain: DomainPolicy? = null,
    val visibility: AxisVisibility = AxisVisibility.Auto,
    val grid: AxisGridMode = AxisGridMode.Primary,
    val primary: Boolean = false,
    val offset: Dp? = null,
    val style: AxisStyleMode = AxisStyleMode.Neutral,
    val alignTicks: Boolean = true,
    val alignZero: Boolean = false,
) {
    /** The [ChartAxis] this spec draws with, with [title] and [domain] folded in. */
    val config: ChartAxis
        get() {
            val withTitle = if (title != null) axis.copy(title = title) else axis
            return if (domain != null) withTitle.copy(domain = domain) else withTitle
        }

    /** The interval policy this axis wants, or `null` to take the chart's. */
    val domainPolicy: DomainPolicy? get() = domain ?: axis.domain

    /** The axis' human name, for announcements and diagnostics. */
    val displayName: String get() = title ?: axis.title ?: id.value

    /**
     * The edge this axis is drawn against on a chart of [orientation].
     *
     * Resolved rather than fixed, because "the value axis" is on the left of a
     * column chart and along the bottom of a horizontal bar chart. A caller who
     * stated a [position] gets theirs.
     */
    fun positionOn(orientation: ChartOrientation): AxisPosition = position ?: when (dimension) {
        AxisDimension.X -> if (orientation.isVertical) AxisPosition.Bottom else AxisPosition.Start
        AxisDimension.Y -> if (orientation.isVertical) AxisPosition.Start else AxisPosition.Bottom
    }
}

/**
 * Every axis a chart has, indexed by name and checked for consistency.
 *
 * ### The one architectural change multi-axis needs
 *
 * Before this, a Cartesian layer resolved its scale by *being* on the primary
 * or the secondary axis — a two-valued enum, checked with an `if`, in three
 * places. That is exactly as far as it goes: there is no third branch of that
 * `if` that means "pressure".
 *
 * A registry replaces the `if` with a lookup. Layers name an axis; the chart
 * resolves the name to a scale once, before drawing, and hands each layer a
 * coordinate system built over its own axis. Nothing in a layer changes, and
 * nothing in the layer model knows how many axes exist — which is why bars,
 * lines, areas, scatters, candles, boxes and custom layers all gained
 * multi-axis support without being touched.
 *
 * ### What it validates
 *
 * Construction is where axis mistakes are caught, because every one of them is
 * a configuration error with no correct runtime behaviour:
 *
 * - two axes with the same id — the later one would silently win;
 * - a Y axis placed on a horizontal edge of a vertical chart, or an X axis on a
 *   vertical one — the scale would run the wrong way;
 * - two primary axes in one dimension — grid ownership would be arbitrary.
 *
 * Layer binding is checked separately, when the layers are known: see
 * [requireAxis].
 */
@Immutable
class AxisRegistry private constructor(
    /** Every axis, in declaration order. */
    val axes: List<ChartAxisSpec>,
    private val byId: Map<ChartAxisId, ChartAxisSpec>,
    private val orientation: ChartOrientation,
) {

    /** The domain axes. Normally exactly one. */
    val xAxes: List<ChartAxisSpec> = axes.filter { it.dimension == AxisDimension.X }

    /** The value axes, in declaration order. */
    val yAxes: List<ChartAxisSpec> = axes.filter { it.dimension == AxisDimension.Y }

    /**
     * The chart's main value axis.
     *
     * The one marked [ChartAxisSpec.primary], or the first declared. Owns the
     * grid, unqualified annotations and — where the chart zooms vertically at
     * all — the Y viewport.
     */
    val primaryY: ChartAxisSpec? = yAxes.firstOrNull { it.primary } ?: yAxes.firstOrNull()

    /** The chart's domain axis. */
    val primaryX: ChartAxisSpec? = xAxes.firstOrNull { it.primary } ?: xAxes.firstOrNull()

    /** The axis called [id], or `null`. */
    fun find(id: ChartAxisId): ChartAxisSpec? = byId[id]

    operator fun contains(id: ChartAxisId): Boolean = id in byId

    /**
     * The axis called [id], or a configuration error naming what asked for it.
     *
     * Never falls back to the primary axis. A line bound to an axis that does
     * not exist and drawn against the revenue scale is not a degraded chart, it
     * is a wrong one — and the mistake is invisible, because the line still
     * looks like a line.
     */
    fun requireAxis(id: ChartAxisId, requestedBy: String, dimension: AxisDimension): ChartAxisSpec {
        val spec = byId[id] ?: throw ChartAxisException(
            "$requestedBy references ${dimension.name} axis \"${id.value}\", but no " +
                "${dimension.name} axis with that id is registered. Registered " +
                "${dimension.name} axes: " +
                axes.filter { it.dimension == dimension }.joinToString { "\"${it.id.value}\"" }
                    .ifEmpty { "(none)" } + ".",
        )
        if (spec.dimension != dimension) {
            throw ChartAxisException(
                "$requestedBy references \"${id.value}\" as a ${dimension.name} axis, " +
                    "but it is registered as a ${spec.dimension.name} axis.",
            )
        }
        return spec
    }

    /** The edge [spec] is drawn against, for this registry's orientation. */
    fun positionOf(spec: ChartAxisSpec): AxisPosition = spec.positionOn(orientation)

    /**
     * The axes on one edge, in the order they stack outward from the plot.
     *
     * Declaration order, so a caller reading their own `yAxis(...)` calls top to
     * bottom sees the axes left to right — and moving one in the source moves it
     * on screen, which is the only mapping that does not need documenting.
     */
    fun axesAt(position: AxisPosition): List<ChartAxisSpec> =
        axes.filter { positionOf(it) == position }

    companion object {

        /**
         * Builds a registry, or throws [ChartAxisException] describing what is
         * wrong with the declaration.
         */
        fun of(
            axes: List<ChartAxisSpec>,
            orientation: ChartOrientation = ChartOrientation.Vertical,
        ): AxisRegistry {
            val byId = LinkedHashMap<ChartAxisId, ChartAxisSpec>(axes.size)
            axes.forEach { spec ->
                val existing = byId.put(spec.id, spec)
                if (existing != null) {
                    throw ChartAxisException(
                        "Duplicate axis id \"${spec.id.value}\". Every axis in a chart needs its " +
                            "own id: a layer naming this one could not say which it meant.",
                    )
                }
                validatePosition(spec, orientation)
            }
            listOf(AxisDimension.X, AxisDimension.Y).forEach { dimension ->
                val primaries = axes.filter { it.dimension == dimension && it.primary }
                if (primaries.size > 1) {
                    throw ChartAxisException(
                        "${primaries.size} ${dimension.name} axes are marked primary " +
                            "(${primaries.joinToString { "\"${it.id.value}\"" }}). A chart has one " +
                            "primary axis per dimension: it decides grid ownership and where an " +
                            "unqualified annotation goes.",
                    )
                }
            }
            return AxisRegistry(axes, byId, orientation)
        }

        private fun validatePosition(spec: ChartAxisSpec, orientation: ChartOrientation) {
            val stated = spec.position ?: return
            // The edge this dimension *would* have taken, which is what says
            // which pair of edges are legal. Asking `positionOn` would hand the
            // caller's own answer back and check nothing.
            val expected = when (spec.dimension) {
                AxisDimension.X ->
                    if (orientation.isVertical) AxisPosition.Bottom else AxisPosition.Start
                AxisDimension.Y ->
                    if (orientation.isVertical) AxisPosition.Start else AxisPosition.Bottom
            }
            val compatible = stated.isHorizontal == expected.isHorizontal
            if (!compatible) {
                val edges = if (expected.isHorizontal) "Bottom or Top" else "Start or End"
                throw ChartAxisException(
                    "Axis \"${spec.id.value}\" is a ${spec.dimension.name} axis on a " +
                        "${if (orientation.isVertical) "vertical" else "horizontal"} chart, so it " +
                        "belongs at $edges — it was placed at $stated, where its scale would run " +
                        "across the plot instead of along it.",
                )
            }
        }
    }
}
