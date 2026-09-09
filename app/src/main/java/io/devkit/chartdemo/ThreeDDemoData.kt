package io.devkit.chartdemo

/**
 * Illustrative data for the 3D column demos.
 *
 * Invented, deterministic and local. The reference demo this gallery is
 * measured against counts fruit by person; the shape of that dataset — a few
 * categories, four series, two natural groupings — is what makes grouping and
 * stacking legible at the same time, so the shape is kept and the numbers are
 * ChartKit's own.
 */
object ThreeDDemoData {

    data class Harvest(val fruit: String, val count: Double?)

    /** Four pickers, two households: the grouped-and-stacked case. */
    val john: List<Harvest> = listOf(
        Harvest("Apples", 5.0),
        Harvest("Oranges", 3.0),
        Harvest("Pears", 4.0),
        Harvest("Grapes", 7.0),
        Harvest("Bananas", 2.0),
    )

    val joe: List<Harvest> = listOf(
        Harvest("Apples", 3.0),
        Harvest("Oranges", 4.0),
        Harvest("Pears", 4.0),
        Harvest("Grapes", 2.0),
        Harvest("Bananas", 5.0),
    )

    val jane: List<Harvest> = listOf(
        Harvest("Apples", 2.0),
        Harvest("Oranges", 5.0),
        Harvest("Pears", 6.0),
        Harvest("Grapes", 2.0),
        Harvest("Bananas", 1.0),
    )

    val janet: List<Harvest> = listOf(
        Harvest("Apples", 3.0),
        Harvest("Oranges", 0.0),
        Harvest("Pears", 4.0),
        Harvest("Grapes", 4.0),
        Harvest("Bananas", 3.0),
    )

    /** A single series, for the plainest possible 3D column chart. */
    data class MonthlySale(val month: String, val total: Double)

    val sales: List<MonthlySale> = listOf(
        MonthlySale("Jan", 42.0),
        MonthlySale("Feb", 38.0),
        MonthlySale("Mar", 55.0),
        MonthlySale("Apr", 61.0),
        MonthlySale("May", 49.0),
        MonthlySale("Jun", 72.0),
    )

    /**
     * A point of any series: a label and a value that may be absent.
     *
     * One shape for both of the demos below, because a multi-series chart takes
     * one `value` accessor for every series — so two series over two different
     * record types cannot be plotted together, and inventing a second accessor
     * per series would be an API for a problem the caller can solve by mapping.
     */
    data class Point(val label: String, val value: Double?)

    /**
     * A quarter that went both ways, for the negative-value demo.
     *
     * Two series that each cross zero, so a category has a positive pile and a
     * negative one over the same baseline rather than one bar that happens to
     * point down.
     */
    val trading: List<Point> = listOf(
        Point("Q1", 34.0),
        Point("Q2", -18.0),
        Point("Q3", 27.0),
        Point("Q4", -8.0),
    )

    val currency: List<Point> = listOf(
        Point("Q1", -12.0),
        Point("Q2", 9.0),
        Point("Q3", 14.0),
        Point("Q4", -21.0),
    )

    /**
     * A series with a hole in it and a series with a genuine zero.
     *
     * The two are different facts and the chart has to say so: the hole gets no
     * column at all, and the zero gets a column of no height that still appears
     * in the tooltip, the table and the announcement.
     */
    val measured: List<Point> = listOf(
        Point("North", 24.0),
        Point("East", null),
        Point("South", 0.0),
        Point("West", 31.0),
        Point("Central", 19.0),
    )

    val recorded: List<Point> = listOf(
        Point("North", 18.0),
        Point("East", 22.0),
        Point("South", 0.0),
        Point("West", null),
        Point("Central", 26.0),
    )

    /** Twenty categories and six series, for the density demo. */
    val dense: List<List<Harvest>> = List(6) { seriesIndex ->
        List(20) { categoryIndex ->
            Harvest(
                fruit = "W${categoryIndex + 1}",
                // Deterministic and varied: no random source, so a screenshot
                // taken today matches one taken next year.
                count = 4.0 + ((seriesIndex * 7 + categoryIndex * 5) % 11),
            )
        }
    }
}
