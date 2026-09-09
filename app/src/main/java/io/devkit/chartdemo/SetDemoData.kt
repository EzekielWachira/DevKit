package io.devkit.chartdemo

import io.devkit.chartkit.set.SetContainment
import io.devkit.chartkit.set.SetDefinition
import io.devkit.chartkit.set.SetIntersection
import io.devkit.chartkit.set.SetItems
import io.devkit.chartkit.set.SetShape

/**
 * The sample's set data, in the sample's own types.
 *
 * Numbers are invented and chosen to be *consistent*: every intersection fits
 * inside its sets and every derived region comes out non-negative, because the
 * strict validator refuses anything else — which is itself part of what the
 * demo shows.
 */
object SetDemoData {

    data class Developer(val id: String, val name: String)

    // ---- platform usage: the plain Venn cases ----------------------------

    val platforms: List<SetDefinition> = listOf(
        SetDefinition("android", "Android", 200),
        SetDefinition("ios", "iOS", 160),
        SetDefinition("web", "Web", 240),
    )

    val platformPairs: List<SetIntersection> = listOf(
        SetIntersection(setOf("android", "ios"), 70),
        SetIntersection(setOf("android", "web"), 100),
        SetIntersection(setOf("ios", "web"), 80),
    )

    val platformIntersections: List<SetIntersection> =
        platformPairs + SetIntersection(setOf("android", "ios", "web"), 45)

    // ---- four sets -------------------------------------------------------

    val channels: List<SetDefinition> = listOf(
        SetDefinition("email", "Email", 900),
        SetDefinition("push", "Push", 700),
        SetDefinition("sms", "SMS", 400),
        SetDefinition("inapp", "In-app", 600),
    )

    val channelIntersections: List<SetIntersection> = listOf(
        SetIntersection(setOf("email", "push"), 300),
        SetIntersection(setOf("email", "sms"), 180),
        SetIntersection(setOf("email", "inapp"), 260),
        SetIntersection(setOf("push", "sms"), 150),
        SetIntersection(setOf("push", "inapp"), 240),
        SetIntersection(setOf("sms", "inapp"), 120),
        SetIntersection(setOf("email", "push", "sms"), 90),
        SetIntersection(setOf("email", "push", "inapp"), 140),
        SetIntersection(setOf("email", "sms", "inapp"), 70),
        SetIntersection(setOf("push", "sms", "inapp"), 60),
        SetIntersection(setOf("email", "push", "sms", "inapp"), 40),
    )

    // ---- Venn against Euler ---------------------------------------------

    val kingdoms: List<SetDefinition> = listOf(
        SetDefinition("animals", "Animals", 100),
        SetDefinition("mammals", "Mammals", 40),
        SetDefinition("plants", "Plants", 60),
    )

    /** Every mammal is an animal. Nothing is both a plant and an animal. */
    val kingdomContainment: List<SetContainment> = listOf(
        SetContainment("animals", "mammals"),
    )

    // ---- the conceptual icon-group diagram -------------------------------

    val marketing: List<SetDefinition> = listOf(
        SetDefinition("ppc", "PPC", 1),
        SetDefinition("seo", "SEO", 1),
        SetDefinition("social", "Social", 1),
        SetDefinition("email", "Email", 1),
        SetDefinition("website", "Website", 1),
    )

    /**
     * Five circles, placed by hand.
     *
     * A conceptual diagram's arrangement is its content: these overlaps exist
     * because the author is talking about them, not because a solver found them
     * in the cardinalities — which are all one, deliberately.
     */
    val marketingLayout: Map<String, SetShape> = mapOf(
        "ppc" to SetShape.Circle(-0.26, -0.62, 0.70),
        "seo" to SetShape.Circle(-0.76, 0.10, 0.70),
        "social" to SetShape.Circle(-0.36, 0.74, 0.70),
        "email" to SetShape.Circle(0.50, 0.66, 0.70),
        "website" to SetShape.Circle(0.60, -0.30, 0.86),
    )

    /** What each overlap is called, keyed by the region's own id. */
    val marketingRegionNames: Map<String, String> = mapOf(
        "ppc seo" to "Keyword research",
        "ppc website" to "Landing pages",
        "seo social" to "Authorship",
        "social email" to "Customer service",
        "email website" to "Upselling",
        "seo website" to "Clean content",
        "social website" to "Social links",
    )

    /** Marks packed into a few of the exclusive regions. */
    val marketingGlyphs: Map<String, List<DemoGlyph>> = mapOf(
        "ppc" to listOf(DemoGlyph.Circle, DemoGlyph.Diamond, DemoGlyph.Ring),
        "seo" to listOf(DemoGlyph.Square, DemoGlyph.Triangle, DemoGlyph.Cross),
        "social" to listOf(DemoGlyph.Ring, DemoGlyph.Circle),
        "email" to listOf(DemoGlyph.Cross, DemoGlyph.Square),
        "website" to listOf(DemoGlyph.Triangle, DemoGlyph.Diamond, DemoGlyph.Circle, DemoGlyph.Ring),
    )

    // ---- icons in labels -------------------------------------------------

    val sustainability: List<SetDefinition> = listOf(
        SetDefinition("economic", "Economic", 100),
        SetDefinition("environment", "Environment", 100),
        SetDefinition("social", "Social", 100),
    )

    val sustainabilityIntersections: List<SetIntersection> = listOf(
        SetIntersection(setOf("economic", "environment"), 45),
        SetIntersection(setOf("economic", "social"), 45),
        SetIntersection(setOf("environment", "social"), 45),
        SetIntersection(setOf("economic", "environment", "social"), 25),
    )

    val sustainabilityGlyphs: Map<String, DemoGlyph> = mapOf(
        "economic" to DemoGlyph.Diamond,
        "environment" to DemoGlyph.Triangle,
        "social" to DemoGlyph.Circle,
    )

    /** The classical names for the three pairs and the middle. */
    val sustainabilityRegions: Map<String, String> = mapOf(
        "economic environment" to "Viable",
        "economic social" to "Equitable",
        "environment social" to "Bearable",
        "economic environment social" to "Sustainable",
    )

    // ---- logos -----------------------------------------------------------

    val socialCategories: List<SetDefinition> = listOf(
        SetDefinition("networks", "Social networks", 100),
        SetDefinition("sharing", "Media sharing", 100),
        SetDefinition("forums", "Forums", 100),
        SetDefinition("blogging", "Blogging", 100),
    )

    /** Lettered badges standing in for product marks. */
    val socialLogos: Map<String, List<String>> = mapOf(
        "networks" to listOf("N", "K", "T"),
        "sharing" to listOf("V", "P", "S"),
        "forums" to listOf("Q", "D", "R"),
        "blogging" to listOf("B", "W", "M"),
    )

    // ---- the nested Euler case -------------------------------------------

    /**
     * The British Isles as a **set system**, not as geography.
     *
     * The relationships are modelled explicitly and the accessibility layer
     * reads them from the model; nothing is inferred from the names. The
     * cardinalities are invented — they exist to give the nesting something to
     * be proportional to.
     */
    val isles: List<SetDefinition> = listOf(
        SetDefinition("isles", "British Isles", 100),
        SetDefinition("islands", "British Islands", 74),
        SetDefinition("uk", "United Kingdom", 68),
        SetDefinition("britain", "Great Britain", 60),
        SetDefinition("ireland", "Ireland (island)", 26),
        SetDefinition("man", "Isle of Man", 3),
        SetDefinition("channel", "Channel Islands", 3),
    )

    val islesContainment: List<SetContainment> = listOf(
        SetContainment("isles", "islands"),
        SetContainment("isles", "ireland"),
        SetContainment("islands", "uk"),
        SetContainment("islands", "man"),
        SetContainment("islands", "channel"),
        SetContainment("uk", "britain"),
    )

    /**
     * Northern Ireland: in the United Kingdom and on the island of Ireland.
     *
     * The one relationship the containment tree cannot express, and the reason
     * the layout has to reconcile a tree with a cross-branch overlap.
     */
    val islesIntersections: List<SetIntersection> = listOf(
        SetIntersection(setOf("uk", "ireland"), 8),
    )

    // ---- disjoint --------------------------------------------------------

    val disjointTeams: List<SetDefinition> = listOf(
        SetDefinition("design", "Design", 12),
        SetDefinition("legal", "Legal", 5),
        SetDefinition("support", "Support", 20),
    )

    // ---- from real collections -------------------------------------------

    private fun developers(range: IntRange): List<Developer> =
        range.map { Developer("dev-$it", "Developer $it") }

    /**
     * Three overlapping teams, built from deterministic ids.
     *
     * The intersections are never stated: `SetAnalyzer` counts them from the
     * lists, so the diagram cannot disagree with the data it came from.
     */
    val developerSets: List<SetItems<Developer>> = listOf(
        SetItems("android", "Android", developers(1..24)),
        SetItems("compose", "Compose", developers(13..32)),
        SetItems("backend", "Backend", developers(28..44) + developers(20..22)),
    )
}
