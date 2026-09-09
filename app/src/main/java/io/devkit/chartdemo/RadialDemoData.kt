package io.devkit.chartdemo

/**
 * Illustrative data for the 3D pie and donut demos.
 *
 * Invented, deterministic and local. The reference demos this gallery is
 * measured against plot browser share and a brand-versus-version breakdown; the
 * *shape* of those datasets — one dominant category, a long tail, and a
 * secondary split of the leader — is what makes labels, leader lines and
 * exploded slices worth demonstrating at all, so the shape is kept and the
 * numbers are ChartKit's own.
 */
object RadialDemoData {

    data class Share(val name: String, val users: Double?)

    /** One dominant slice, three middling ones and a tail: the ordinary pie. */
    val browsers: List<Share> = listOf(
        Share("Chrome", 4823.0),
        Share("Safari", 3112.0),
        Share("Edge", 1841.0),
        Share("Firefox", 980.0),
        Share("Opera", 412.0),
        Share("Other", 288.0),
    )

    /** What the browsers add up to, for a donut's centre. */
    val browserTotal: Double = browsers.sumOf { it.users ?: 0.0 }

    /** Fewer, larger slices — where inside labels actually fit. */
    val platforms: List<Share> = listOf(
        Share("Mobile", 6420.0),
        Share("Desktop", 4180.0),
        Share("Tablet", 1180.0),
    )

    /** A budget, for a donut whose centre carries the total. */
    data class Spend(val category: String, val amount: Double)

    val budget: List<Spend> = listOf(
        Spend("Engineering", 482_000.0),
        Spend("Sales", 264_000.0),
        Spend("Marketing", 173_000.0),
        Spend("Support", 96_000.0),
        Spend("Operations", 74_000.0),
    )

    val budgetTotal: Double = budget.sumOf { it.amount }

    /**
     * The same categories at two moments, for the data-update demo.
     *
     * Deliberately a *reordering* as well as a change of magnitude: the second
     * quarter's leader is not the first's, so a chart that animated by list
     * position rather than by identity would visibly morph one category into
     * another.
     */
    val quarterOne: List<Share> = listOf(
        Share("North", 1240.0),
        Share("South", 860.0),
        Share("East", 1580.0),
        Share("West", 640.0),
    )

    val quarterTwo: List<Share> = listOf(
        Share("North", 1810.0),
        Share("South", 1120.0),
        Share("East", 900.0),
        Share("West", 1340.0),
    )

    /**
     * A set with a zero, a null and a negative in it.
     *
     * All three are values a part-to-whole chart cannot draw, and all three are
     * handled by the *slice engine* rather than by anything 3D — which is the
     * point of including them here.
     */
    val awkward: List<Share> = listOf(
        Share("Measured", 420.0),
        Share("Zero", 0.0),
        Share("Missing", null),
        Share("Negative", -120.0),
        Share("Recorded", 310.0),
    )

    /** Twenty slices: enough to show what a 3D pie stops being good at. */
    val dense: List<Share> = List(20) { index ->
        Share("Item ${index + 1}", (60 + (index * 37) % 91).toDouble())
    }
}
