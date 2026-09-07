package io.devkit.chartkit.model

import io.devkit.chartkit.scale.NumericDomain

/**
 * What a chart does with a point whose value is missing.
 *
 * "Missing" means the caller's `y` lambda returned `null`, or returned a
 * `Double` that is `NaN` or infinite. There is no safe universal answer, so the
 * behaviour is a stated policy rather than a hidden one — and the default is
 * the conservative reading. Substituting zero is never the default: a missing
 * reading and a reading of zero are different facts, and a chart that conflates
 * them reports something that did not happen.
 */
enum class MissingValuePolicy {

    /**
     * Break the line and leave a gap. The default.
     *
     * Says "no data here" without claiming anything about what the value was.
     */
    Break,

    /**
     * Join the points on either side, drawing straight through the gap.
     *
     * Honest only when the gap is known to be a sampling artefact rather than
     * an absence, which is why it is opt-in.
     *
     * Affects lines and areas only. A bar has no neighbour to be joined to, so
     * a bar chart draws a hole under either this policy or [Break] — the two
     * differ only where there is a path to close.
     */
    Connect,

    /**
     * Treat the point as zero.
     *
     * Correct for a genuinely additive quantity — no sales recorded means zero
     * sales — and wrong for anything measured, where it invents a reading.
     */
    Zero,
}

/**
 * One normalised point: a resolved x, a value that may be absent, and the index
 * it came from in the caller's list.
 */
internal data class PlotPoint(
    val x: ChartX,
    val y: Double?,
    val sourceIndex: Int,
) {
    val isPresent: Boolean get() = y != null && y.isFinite()
}

/**
 * A series after normalisation: the caller's items still attached, alongside
 * points the engine can measure.
 *
 * [items] is parallel to [points] by `sourceIndex`, which is what lets a
 * selection hand back the original object without the layers ever knowing what
 * type it was.
 */
internal class PlotSeries(
    val id: String,
    val name: String,
    val points: List<PlotPoint>,
    val items: List<Any?>,
    val visible: Boolean,
    val color: Int?,
    /** Palette slot: the index among *all* series, so hiding one recolours none. */
    val paletteIndex: Int,
) {
    val presentPoints: List<PlotPoint> get() = points.filter { it.isPresent }

    fun itemAt(index: Int): Any? = items.getOrNull(index)
}

/**
 * How the horizontal axis should be built.
 *
 * Inferred from the resolved x values unless the caller states it. The
 * inference is deliberately pessimistic: one [ChartX.Category] among a hundred
 * numbers makes the whole axis categorical, because a numeric axis has nowhere
 * to put a value that is not a number.
 */
enum class ChartXAxisKind {
    Numeric,
    Category,
    Time,
    ;

    internal companion object {
        fun infer(values: Iterable<ChartX>): ChartXAxisKind {
            var sawNumeric = false
            var sawTime = false
            for (value in values) {
                when (value) {
                    is ChartX.Category -> return Category
                    is ChartX.Numeric -> sawNumeric = true
                    is ChartX.Time -> sawTime = true
                }
            }
            return when {
                sawTime && !sawNumeric -> Time
                sawTime -> Time
                sawNumeric -> Numeric
                // No data at all. Category draws an empty band axis rather than
                // a numeric one labelled 0..1, which reads less like real data.
                else -> Category
            }
        }
    }
}

/**
 * Everything the engine needs about the data, computed once per data change.
 *
 * Assembled by [normalizeSeries] and then cached in composition, so scales,
 * ticks and geometry are not recomputed when an unrelated recomposition
 * happens — a tooltip appearing must not re-derive the domain of ten thousand
 * points.
 */
internal class PlotData(
    val series: List<PlotSeries>,
    val xAxisKind: ChartXAxisKind,
    /** Band labels, in order, when [xAxisKind] is [ChartXAxisKind.Category]. */
    val categories: List<String>,
    /** The interval x occupies, for continuous axes. `null` when categorical. */
    val xDomain: NumericDomain?,
    /** The interval y occupies across every visible series. */
    val yDomain: NumericDomain?,
) {
    val visibleSeries: List<PlotSeries> get() = series.filter { it.visible }

    val isEmpty: Boolean
        get() = series.none { it.visible && it.points.any { point -> point.isPresent } }

    /** The continuous x position of a point, for numeric and time axes. */
    fun continuousX(point: PlotPoint): Double = when (val x = point.x) {
        is ChartX.Numeric -> x.value
        is ChartX.Time -> x.epochMillis.toDouble()
        is ChartX.Category -> categories.indexOf(x.label).toDouble()
    }

    /**
     * The same data with each series' points in ascending x order.
     *
     * Only meaningful on a continuous axis: sorting a category axis would mean
     * choosing an order for the bands, and the caller's list order is already
     * the answer to that. Returns `this` unchanged for a category axis.
     */
    fun sortedByX(): PlotData {
        if (xAxisKind == ChartXAxisKind.Category) return this
        return PlotData(
            series = series.map { s ->
                PlotSeries(
                    id = s.id,
                    name = s.name,
                    // `items` is indexed by `sourceIndex`, which the points
                    // carry, so reordering points alone keeps every selection
                    // pointing at the right object.
                    points = s.points.sortedBy { continuousX(it) },
                    items = s.items,
                    visible = s.visible,
                    color = s.color,
                    paletteIndex = s.paletteIndex,
                )
            },
            xAxisKind = xAxisKind,
            categories = categories,
            xDomain = xDomain,
            yDomain = yDomain,
        )
    }

    /**
     * The same data with every palette slot shifted by [offset].
     *
     * A combined chart normalises each layer separately, so each layer's series
     * would otherwise start again at slot 0 — and the bars and the line would
     * both be the theme's first colour. Offsetting by the number of series
     * already declared gives every series in the chart its own colour, in
     * declaration order.
     */
    fun withPaletteOffset(offset: Int): PlotData {
        if (offset == 0) return this
        return PlotData(
            series = series.map { s ->
                PlotSeries(
                    id = s.id,
                    name = s.name,
                    points = s.points,
                    items = s.items,
                    visible = s.visible,
                    color = s.color,
                    paletteIndex = s.paletteIndex + offset,
                )
            },
            xAxisKind = xAxisKind,
            categories = categories,
            xDomain = xDomain,
            yDomain = yDomain,
        )
    }

    /**
     * The same data with each series' y values replaced.
     *
     * How a data-change animation reaches the geometry: the interpolated frame
     * is a [PlotData] identical to the target in every way except its values,
     * so scales, categories and layers need no notion of animation at all.
     */
    fun withValues(values: List<List<Double?>>): PlotData {
        if (values.size != series.size) return this
        val updated = series.mapIndexed { seriesIndex, s ->
            val replacement = values[seriesIndex]
            PlotSeries(
                id = s.id,
                name = s.name,
                points = s.points.mapIndexed { pointIndex, point ->
                    point.copy(y = replacement.getOrNull(pointIndex))
                },
                items = s.items,
                visible = s.visible,
                color = s.color,
                paletteIndex = s.paletteIndex,
            )
        }
        return PlotData(
            series = updated,
            xAxisKind = xAxisKind,
            categories = categories,
            // The domain stays the *target's*: recomputing it per frame would
            // make the axis labels flicker through intermediate values while
            // the bars grow.
            xDomain = xDomain,
            yDomain = yDomain,
        )
    }

    companion object {
        val Empty: PlotData = PlotData(
            series = emptyList(),
            xAxisKind = ChartXAxisKind.Category,
            categories = emptyList(),
            xDomain = null,
            yDomain = null,
        )
    }
}

/**
 * Converts the caller's series and accessor lambdas into [PlotData].
 *
 * The single crossing point between "the developer's model" and "the chart
 * engine". It runs once per data change, never inside a draw pass.
 *
 * Input order is preserved. ChartKit does not sort the caller's data: a line
 * chart of unsorted points draws in the order given, which is visible and
 * fixable, whereas a silent reorder produces a chart that disagrees with the
 * list the developer is looking at. [io.devkit.chartkit.charts.ChartDataOrder]
 * exposes the choice.
 */
internal fun <T> normalizeSeries(
    series: List<ChartSeries<T>>,
    x: (T) -> Any?,
    y: (T) -> Number?,
    xResolver: ChartXResolver,
    missingValuePolicy: MissingValuePolicy,
    xAxisKind: ChartXAxisKind?,
): PlotData {
    series.requireDistinctIds()
    if (series.isEmpty()) return PlotData.Empty

    val plotSeries = series.mapIndexed { seriesIndex, source ->
        val points = ArrayList<PlotPoint>(source.data.size)
        source.data.forEachIndexed { index, item ->
            val resolvedX = xResolver.resolveOrDefault(x(item))
            val rawY = y(item)?.toDouble()
            val value = when {
                rawY != null && rawY.isFinite() -> rawY
                missingValuePolicy == MissingValuePolicy.Zero -> 0.0
                else -> null
            }
            points += PlotPoint(resolvedX, value, index)
        }
        PlotSeries(
            id = source.id,
            name = source.name,
            points = points,
            items = source.data,
            visible = source.visible,
            color = source.color,
            paletteIndex = seriesIndex,
        )
    }

    val allX = plotSeries.filter { it.visible }.flatMap { it.points }.map { it.x }
    val kind = xAxisKind ?: ChartXAxisKind.infer(allX)

    // Categories are collected across every series so a grouped chart whose
    // second series omits a category still leaves its band in place.
    val categories = if (kind == ChartXAxisKind.Category) {
        LinkedHashSet<String>().apply {
            plotSeries.forEach { s ->
                s.points.forEach { point ->
                    add(
                        when (val value = point.x) {
                            is ChartX.Category -> value.label
                            is ChartX.Numeric -> value.value.toString()
                            is ChartX.Time -> value.epochMillis.toString()
                        },
                    )
                }
            }
        }.toList()
    } else {
        emptyList()
    }

    val visible = plotSeries.filter { it.visible }

    val xDomain = if (kind == ChartXAxisKind.Category) {
        null
    } else {
        NumericDomain.of(
            visible.flatMap { s ->
                s.points.map { point ->
                    when (val value = point.x) {
                        is ChartX.Numeric -> value.value
                        is ChartX.Time -> value.epochMillis.toDouble()
                        is ChartX.Category -> Double.NaN
                    }
                }
            },
        )
    }

    val yDomain = NumericDomain.of(
        visible.flatMap { s -> s.points.mapNotNull { it.y } },
    )

    return PlotData(
        series = plotSeries,
        xAxisKind = kind,
        categories = categories,
        xDomain = xDomain,
        yDomain = yDomain,
    )
}
