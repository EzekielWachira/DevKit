package io.example.consumer

import io.devkit.chartkit.ChartKitVersion
import io.devkit.chartkit.accessibility.chartDataTable
import io.devkit.chartkit.accessibility.ohlcDataTable
import androidx.compose.ui.graphics.Color
import io.devkit.chartkit.data.LttbDownsampler
import io.devkit.chartkit.export.ChartSvg
import io.devkit.chartkit.flow.buildSankeyGraph
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.graph.GraphLayout
import io.devkit.chartkit.graph.GraphLayoutStrategy
import io.devkit.chartkit.graph.buildChartGraph
import io.devkit.chartkit.hierarchy.SunburstLayout
import io.devkit.chartkit.hierarchy.TreemapLayout
import io.devkit.chartkit.hierarchy.buildHierarchy
import io.devkit.chartkit.scale.LogScale
import io.devkit.chartkit.scale.SymlogScale
import io.devkit.chartkit.scene.ChartSceneNode
import io.devkit.chartkit.scene.buildChartScene
import io.devkit.chartkit.timeline.buildTimeline
import io.devkit.chartkit.transform.FunnelTransform
import io.devkit.chartkit.transform.WaterfallTransform
import io.devkit.chartkit.data.VisibleRange
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.geometry.CalendarGeometry
import io.devkit.chartkit.geometry.OhlcPolicy
import io.devkit.chartkit.geometry.PriceDirection
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.SizeScale
import io.devkit.chartkit.scale.TickGenerator
import io.devkit.chartkit.stats.BoxStatistics
import io.devkit.chartkit.stats.ChartStatistics
import io.devkit.chartkit.stats.DensityEstimator
import io.devkit.chartkit.stats.HistogramBinner
import io.devkit.chartkit.stats.HistogramBins
import io.devkit.chartkit.stats.MovingAverage
import io.devkit.chartkit.stats.Quartiles
import io.devkit.chartkit.stream.ChartWindow
import io.devkit.core.DevKitDistribution
import java.util.Locale
import java.util.TimeZone

/**
 * Release-safe usage of ChartKit, compiled against the **published** artifact.
 *
 * In `src/main`, so it is compiled into the release variant too. If ChartKit
 * were classified or published as debug-only, this file would not build — which
 * is the claim being verified, not merely asserted.
 *
 * It reaches into the engine — scales, ticks, series, formatters, statistics,
 * downsampling, financial normalisation and the streaming window — rather than
 * only reading a version constant, so the AAR is proven to contain its classes
 * and not merely to resolve. All of it is plain Kotlin: none of these entry
 * points needs Compose, which is itself worth verifying.
 *
 * Compose *composables* are deliberately not touched here: this module has no
 * Compose plugin, and driving the whole Compose toolchain would test AGP rather
 * than ChartKit's publication.
 */
object ChartKitUsage {

    fun describe(): String = ChartKitVersion.chartKit.qualifiedLabel

    /** ChartKit is a runtime library, and the artifact metadata says so. */
    fun isReleaseSafe(): Boolean =
        ChartKitVersion.chartKit.distribution == DevKitDistribution.RUNTIME

    /** A real series over a consumer's own type, with no conversion step. */
    data class Revenue(val month: String, val amount: Double)

    fun sampleSeries(): ChartSeries<Revenue> = ChartSeries(
        id = "revenue",
        name = "Revenue",
        data = listOf(Revenue("Jan", 24_000.0), Revenue("Feb", 31_500.0)),
    )

    /** Exercises the scale and tick engine end to end. */
    fun axisLabels(): List<String> {
        val domain = NumericDomain(0.0, 100.0)
        val scale = LinearScale(domain, rangeStart = 0f, rangeEnd = 800f)
        val formatter = ChartNumberFormatters.compact(locale = Locale.UK)
        return scale.ticks(5).map(formatter::format)
    }

    /** The tick generator is public engine surface too. */
    fun tickCount(): Int = TickGenerator.ticks(NumericDomain(0.0, 97.0), 5).size

    // ---- the statistical, financial and streaming surface -------------------
    //
    // Reached from the published artifact for the same reason as the scales: an
    // AAR that resolves but does not contain these classes fails here rather
    // than in a consumer's app.

    /** Quartiles, by ChartKit's documented method. */
    fun latencyQuartiles(): Quartiles =
        ChartStatistics.quartiles(ChartStatistics.finiteSorted(SAMPLES))

    /** A five-number summary, computed rather than supplied. */
    fun latencySummary(): BoxStatistics? = BoxStatistics.from(SAMPLES)

    /** Histogram binning, including the boundary rules. */
    fun histogramBinCount(): Int =
        HistogramBinner.bin(SAMPLES.map { it }, HistogramBins.Count(5)).size

    /** Kernel density estimation. */
    fun densityPeak(): Double = DensityEstimator.estimate(SAMPLES).peak

    /** The size scale, mapping a value to a bubble radius by area. */
    fun bubbleRadius(): Float =
        SizeScale(NumericDomain(0.0, 100.0), minSize = 4f, maxSize = 32f).size(50.0)

    /** Downsampling: a hundred thousand points reduced to a drawable budget. */
    fun sampledCount(): Int {
        val x = DoubleArray(100_000) { it.toDouble() }
        val y = DoubleArray(100_000) { kotlin.math.sin(it / 50.0) }
        return LttbDownsampler.sample(x, y, 1_000).size
    }

    /** Visible-range lookup over an ordered series. */
    fun visibleCount(): Int {
        val values = DoubleArray(1_000) { it.toDouble() }
        return VisibleRange.of(values, 100.0, 200.0).size
    }

    /**
     * The public financial surface.
     *
     * OHLC *normalisation* is internal on purpose — it is engine machinery a
     * consumer reaches through `CandlestickChart`, not directly — so what is
     * verified here is what a consumer can actually name: the validation policy,
     * the direction the theme colours by, and the typed table adapter.
     */
    fun pricePolicies(): List<String> = listOf(
        OhlcPolicy.Repair.name,
        PriceDirection.Increase.name,
        PriceDirection.Decrease.name,
        PriceDirection.Neutral.name,
    )

    /** A typed OHLC table, with no reflection anywhere in it. */
    fun priceTableColumns(): List<String> = ohlcDataTable(
        data = listOf(Bar("Mon", 10.0, 12.0, 9.5, 11.0)),
        date = { it.label },
        open = { it.open },
        high = { it.high },
        low = { it.low },
        close = { it.close },
    ).columns

    private data class Bar(
        val label: String,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
    )

    /** A moving average, as a plain list aligned to its input. */
    fun movingAverage(): List<Double?> =
        MovingAverage.simple(listOf(1.0, 2.0, 3.0, 4.0, 5.0), period = 3)

    /** The calendar grid a calendar heatmap is laid out on. */
    fun calendarWeeks(): Int =
        CalendarGeometry.grid(0L, 364L, Locale.UK, TimeZone.getTimeZone("UTC")).weekCount

    /** The accessible table representation. */
    fun dataTableRows(): Int = chartDataTable(
        data = sampleSeries().data,
        category = { it.month },
        value = { it.amount },
    ).rows.size

    /** The rolling window behind a streaming chart. */
    fun windowSize(): Int = (ChartWindow.Count(500) as ChartWindow.Count).size

    // ---- hierarchy, flow, relationships, time ------------------------------
    //
    // All plain Kotlin, and all reached through the *public* surface — which is
    // the point of this file: a class that is `internal` by design would not
    // compile here, and one missing from the AAR would not link.

    private data class Team(val id: String, val name: String, val spend: Double?, val teams: List<Team> = emptyList())

    private val company = Team(
        "root",
        "Company",
        null,
        listOf(
            Team("eng", "Engineering", null, listOf(Team("a", "Android", 40.0), Team("i", "iOS", 30.0))),
            Team("sales", "Sales", 60.0),
        ),
    )

    /** A hierarchy normalised from a consumer's own recursive model. */
    fun hierarchyTotal(): Double = buildHierarchy(
        root = company,
        children = { it.teams },
        value = { it.spend },
        label = { it.name },
        key = { it.id },
    ).root.value

    /** The squarified packing, over a plain rectangle. */
    fun treemapTiles(): Int = TreemapLayout.layout(
        root = buildHierarchy(
            root = company,
            children = { it.teams },
            value = { it.spend },
            label = { it.name },
            key = { it.id },
        ).root,
        bounds = ChartRect(0f, 0f, 200f, 100f),
    ).size

    /** Sunburst arcs, in the chart's own angle convention. */
    fun sunburstArcs(): Int = SunburstLayout.layout(
        root = buildHierarchy(
            root = company,
            children = { it.teams },
            value = { it.spend },
            label = { it.name },
            key = { it.id },
        ).root,
        innerRadius = 10f,
        outerRadius = 50f,
    ).size

    private data class Stage(val id: String)
    private data class Move(val from: String, val to: String, val count: Double)

    /** A flow graph, with its columns assigned. */
    fun sankeyColumns(): Int = buildSankeyGraph(
        nodes = listOf(Stage("a"), Stage("b"), Stage("c")),
        links = listOf(Move("a", "b", 10.0), Move("b", "c", 4.0)),
        nodeId = { it.id },
        nodeLabel = { it.id },
        source = { it.from },
        target = { it.to },
        value = { it.count },
    ).columnCount

    /** Funnel conversion metrics. */
    fun funnelConversion(): Double? = FunnelTransform.overallConversion(
        FunnelTransform.resolve(
            data = listOf("Visited" to 100.0, "Bought" to 25.0),
            label = { it.first },
            value = { it.second },
        ),
    )

    /** A waterfall's running total. */
    fun waterfallTotal(): Double = WaterfallTransform.resolve(
        data = listOf("Open" to 100.0, "Costs" to -30.0),
        label = { it.first },
        value = { it.second },
        kind = { WaterfallTransform.signedKind(it.second) },
    ).last().runningTotal

    private data class Svc(val name: String)
    private data class Call(val from: String, val to: String)

    /** A relationship graph, laid out deterministically. */
    fun graphPositions(): Int = GraphLayout.compute(
        graph = buildChartGraph(
            nodes = listOf(Svc("a"), Svc("b"), Svc("c")),
            edges = listOf(Call("a", "b"), Call("b", "c")),
            nodeId = { it.name },
            source = { it.from },
            target = { it.to },
        ),
        strategy = GraphLayoutStrategy.Circular(),
    ).size

    /** Timeline lanes and overlap-stacked rows. */
    fun timelineRows(): Int = buildTimeline(
        data = listOf(Triple("A", 0L, 10L), Triple("B", 5L, 15L)),
        start = { it.second },
        label = { it.first },
        end = { it.third },
        lane = { "lane" },
    ).rowCount

    /** A logarithmic position scale. */
    fun logDecadeSpacing(): Float {
        val scale = LogScale(NumericDomain(1.0, 1000.0), rangeStart = 0f, rangeEnd = 300f)
        return scale.scale(100.0) - scale.scale(10.0)
    }

    /** A symmetric-log scale, which represents zero and negatives. */
    fun symlogAtZero(): Float =
        SymlogScale(NumericDomain(-100.0, 100.0), rangeStart = 0f, rangeEnd = 200f).scale(0.0)

    /** The renderer-neutral scene model, written as SVG. */
    fun svgLength(): Int = ChartSvg.render(
        buildChartScene(width = 100f, height = 50f) {
            add(
                ChartSceneNode.Line(
                    from = ChartOffset(0f, 0f),
                    to = ChartOffset(100f, 50f),
                    color = Color.Black,
                    strokeWidth = 1f,
                ),
            )
        },
        title = "Consumer check",
    ).length

    private val SAMPLES: List<Double> =
        listOf(120.0, 135.0, 140.0, 155.0, 160.0, 178.0, 190.0, 210.0, 260.0, 480.0)
}
