package io.devkit.chartdemo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartDataTableView
import io.devkit.chartkit.accessibility.setDataTable
import io.devkit.chartkit.accessibility.setRelationshipTable
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.EulerDiagram
import io.devkit.chartkit.charts.SetContentOverflow
import io.devkit.chartkit.charts.SetDiagram
import io.devkit.chartkit.charts.SetIconGroup
import io.devkit.chartkit.charts.VennDiagram
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.layer.set.SetColorMode
import io.devkit.chartkit.layer.set.SetFocusMode
import io.devkit.chartkit.set.SetAnalyzer
import io.devkit.chartkit.set.SetDiagramLayout
import io.devkit.chartkit.set.SetSizing
import io.devkit.chartkit.set.SetStyle
import io.devkit.chartkit.theme.ChartKitTheme

/**
 * Every set-diagram capability, one screen.
 *
 * Each demo exists because it shows something the others do not: a different
 * semantic (Venn against Euler), a different sizing rule, a different kind of
 * content inside a region, or a different colour strategy. The controls change
 * what is *shown*, never what the data means — switching from blended colour to
 * colour-by-set does not alter a single number.
 */
@Composable
fun ChartSetScreen(modifier: Modifier = Modifier) {
    var demo by remember { mutableStateOf(SetDemo.TwoSets) }
    var animate by remember { mutableStateOf(true) }
    var showValues by remember { mutableStateOf(true) }
    var blend by remember { mutableStateOf(true) }
    var dimUnrelated by remember { mutableStateOf(false) }
    var readout by remember { mutableStateOf("Tap a region") }

    val animation = if (animate) ChartAnimation.Default else ChartAnimation.None
    val colorMode = if (blend) SetColorMode.Blend else SetColorMode.BySet
    val focus = if (dimUnrelated) SetFocusMode.DimUnrelated else SetFocusMode.None
    val counts = remember { ChartNumberFormatters.compact() }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Set relationships", style = MaterialTheme.typography.titleLarge)
        Text(
            "Venn and Euler diagrams on ChartKit's own renderer. Sets, intersections and " +
                "containment go in; logical regions, geometry, labels, selection and " +
                "accessibility come out.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .testTag("set-demos"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SetDemo.entries.forEach { entry ->
                FilterChip(
                    selected = demo == entry,
                    onClick = {
                        demo = entry
                        readout = "Tap a region"
                    },
                    label = { Text(entry.label) },
                    modifier = Modifier.testTag("set-demo-${entry.name}"),
                )
            }
        }

        Text(demo.description, style = MaterialTheme.typography.bodySmall)

        val chartModifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .testTag("set-chart")

        when (demo) {
            SetDemo.TwoSets -> VennDiagram(
                sets = SetDemoData.platforms.take(2),
                intersections = SetDemoData.platformPairs.take(1),
                animation = animation,
                showValues = showValues,
                colorMode = colorMode,
                focusMode = focus,
                legend = LegendPosition.Bottom,
                valueFormatter = counts,
                onSelectionChanged = { readout = describe(it?.set) },
                modifier = chartModifier,
            )

            SetDemo.ThreeSets -> VennDiagram(
                sets = SetDemoData.platforms,
                intersections = SetDemoData.platformIntersections,
                animation = animation,
                showValues = showValues,
                colorMode = colorMode,
                focusMode = focus,
                legend = LegendPosition.Bottom,
                valueFormatter = counts,
                onSelectionChanged = { readout = describe(it?.set) },
                modifier = chartModifier,
            )

            SetDemo.FourSets -> VennDiagram(
                sets = SetDemoData.channels,
                intersections = SetDemoData.channelIntersections,
                animation = animation,
                showValues = false,
                colorMode = colorMode,
                legend = LegendPosition.Bottom,
                onSelectionChanged = { readout = describe(it?.set) },
                modifier = chartModifier,
            )

            SetDemo.Proportional -> VennDiagram(
                sets = SetDemoData.platforms,
                intersections = SetDemoData.platformIntersections,
                sizing = SetSizing.Proportional,
                animation = animation,
                showValues = showValues,
                colorMode = colorMode,
                legend = LegendPosition.Bottom,
                valueFormatter = counts,
                onSelectionChanged = { readout = describe(it?.set) },
                modifier = chartModifier,
            )

            SetDemo.VennVersusEuler -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The same data twice. A Venn diagram draws a region for "a
                // mammal that is a plant"; an Euler diagram does not, because
                // there is no such thing.
                Text("Venn — every combination", style = MaterialTheme.typography.labelLarge)
                VennDiagram(
                    sets = SetDemoData.kingdoms,
                    containments = SetDemoData.kingdomContainment,
                    animation = animation,
                    showValues = false,
                    colorMode = colorMode,
                    onSelectionChanged = { readout = describe(it?.set) },
                    modifier = Modifier.fillMaxWidth().height(200.dp).testTag("set-chart"),
                )
                Text("Euler — only what occurs", style = MaterialTheme.typography.labelLarge)
                EulerDiagram(
                    sets = SetDemoData.kingdoms,
                    containments = SetDemoData.kingdomContainment,
                    animation = animation,
                    colorMode = colorMode,
                    onSelectionChanged = { readout = describe(it?.set) },
                    modifier = Modifier.fillMaxWidth().height(200.dp).testTag("set-chart-euler"),
                )
            }

            SetDemo.IconGroups -> SetDiagram(
                data = remember { SetAnalyzer.analyze(SetDemoData.marketing) },
                // The arrangement *is* the message here: five circles placed to
                // create exactly the overlaps being talked about. No solver
                // should be second-guessing a diagram like this.
                layout = SetDiagramLayout.Custom(SetDemoData.marketingLayout),
                colorMode = colorMode,
                showValues = false,
                animation = animation,
                overflow = SetContentOverflow.Allow,
                // The set's name and its marks are one stack rather than two
                // overlays: both belong at the same anchor, and laying them out
                // together is how Compose keeps them from landing on top of one
                // another.
                regionContent = { scope ->
                    val glyphs = SetDemoData.marketingGlyphs[scope.region.id].orEmpty()
                    val name = SetDemoData.marketingRegionNames[scope.region.id]
                        ?: scope.region.memberships.singleOrNull()
                            ?.let { id -> SetDemoData.marketing.first { it.id == id }.label }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (name != null) {
                            Text(
                                text = name,
                                style = ChartKitTheme.typography.setRegionLabel,
                                color = ChartKitTheme.colors.set.regionLabel,
                                textAlign = TextAlign.Center,
                            )
                        }
                        if (glyphs.isNotEmpty()) {
                            SetIconGroup(items = glyphs, available = scope.clearance) { glyph ->
                                DemoGlyphIcon(glyph, ChartKitTheme.colors.set.regionLabel)
                            }
                        }
                    }
                },
                onSelectionChanged = { readout = describe(it?.set) },
                modifier = chartModifier,
            )

            SetDemo.IconsInLabels -> VennDiagram(
                sets = SetDemoData.sustainability,
                intersections = SetDemoData.sustainabilityIntersections,
                animation = animation,
                showValues = false,
                colorMode = colorMode,
                // The reference's "icons in point labels", in Compose: a Column
                // holding a drawn mark and the set's name. No canvas glyph
                // hacks, no icon font.
                setLabel = { scope ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        DemoGlyphIcon(
                            glyph = SetDemoData.sustainabilityGlyphs.getValue(scope.set.id),
                            color = ChartKitTheme.colors.set.label,
                            size = 22.dp,
                        )
                        Text(
                            text = scope.set.label,
                            style = ChartKitTheme.typography.setLabel,
                            color = ChartKitTheme.colors.set.label,
                        )
                    }
                },
                // The reference names its intersections rather than counting
                // them — "Viable", "Bearable", "Sustainable" — which is what a
                // region label slot is for.
                regionLabel = { scope ->
                    val name = SetDemoData.sustainabilityRegions[scope.region.id]
                    if (name != null) {
                        Text(
                            text = name,
                            style = ChartKitTheme.typography.setRegionLabel,
                            color = ChartKitTheme.colors.set.regionLabel,
                            textAlign = TextAlign.Center,
                        )
                    }
                },
                onSelectionChanged = { readout = describe(it?.set) },
                modifier = chartModifier,
            )

            SetDemo.Logos -> EulerDiagram(
                sets = SetDemoData.socialCategories,
                animation = animation,
                colorMode = SetColorMode.BySet,
                setLabel = { scope ->
                    Text(
                        text = scope.set.label,
                        style = ChartKitTheme.typography.setLabel,
                        color = ChartKitTheme.colors.set.label,
                        textAlign = TextAlign.Center,
                    )
                },
                // Arbitrary Compose content packed inside each region. Real
                // logos would be `Image(painterResource(...))` here; ChartKit
                // supplies the anchor and the room, and never loads an image.
                regionContent = { scope ->
                    val logos = SetDemoData.socialLogos[scope.region.id].orEmpty()
                    if (logos.isNotEmpty()) {
                        SetIconGroup(
                            items = logos,
                            available = scope.clearance,
                            maxItems = 3,
                        ) { logo ->
                            DemoLogo(logo, ChartKitTheme.colors.set.label)
                        }
                    }
                },
                onSelectionChanged = { readout = describe(it?.set) },
                modifier = chartModifier,
            )

            SetDemo.NestedEuler -> EulerDiagram(
                sets = SetDemoData.isles,
                intersections = SetDemoData.islesIntersections,
                containments = SetDemoData.islesContainment,
                animation = animation,
                colorMode = SetColorMode.BySet,
                fillAlpha = 0.9f,
                setLabel = { scope ->
                    Text(
                        text = scope.set.label,
                        style = ChartKitTheme.typography.setRegionLabel,
                        color = ChartKitTheme.colors.set.label,
                        textAlign = TextAlign.Center,
                    )
                },
                // A nested set's exclusive region is a thin ring: the default
                // policy would drop every inner label, because none of them fits
                // inside a circle drawn in the ring's width. They do fit along
                // it, and the caller is the one who knows that.
                overflow = SetContentOverflow.Allow,
                onSelectionChanged = { readout = describe(it?.set) },
                modifier = Modifier.fillMaxWidth().height(380.dp).testTag("set-chart"),
            )

            SetDemo.Disjoint -> EulerDiagram(
                sets = SetDemoData.disjointTeams,
                animation = animation,
                colorMode = colorMode,
                showValues = showValues,
                valueFormatter = counts,
                onSelectionChanged = { readout = describe(it?.set) },
                modifier = chartModifier,
            )

            SetDemo.ExplicitColors -> VennDiagram(
                sets = SetDemoData.platforms,
                intersections = SetDemoData.platformIntersections,
                animation = animation,
                showValues = showValues,
                colorMode = colorMode,
                valueFormatter = counts,
                // A design system that needs one overlap in a specific semantic
                // colour says so here, and nothing else in the diagram changes.
                intersectionStyle = { region ->
                    if (region.memberships == setOf("android", "ios")) {
                        SetStyle(fill = Color(0xFF6750A4))
                    } else {
                        null
                    }
                },
                legend = LegendPosition.Bottom,
                onSelectionChanged = { readout = describe(it?.set) },
                modifier = chartModifier,
            )

            SetDemo.FromCollections -> VennDiagram(
                sets = SetDemoData.developerSets,
                itemKey = SetDemoData.Developer::id,
                animation = animation,
                showValues = showValues,
                colorMode = colorMode,
                onSelectionChanged = { readout = describe(it?.set) },
                modifier = chartModifier,
            )

            SetDemo.Accessibility -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VennDiagram(
                    sets = SetDemoData.platforms,
                    intersections = SetDemoData.platformIntersections,
                    animation = animation,
                    showValues = showValues,
                    colorMode = colorMode,
                    valueFormatter = counts,
                    onSelectionChanged = { readout = describe(it?.set) },
                    modifier = Modifier.fillMaxWidth().height(240.dp).testTag("set-chart"),
                )
                val data = remember {
                    SetAnalyzer.analyze(
                        SetDemoData.platforms,
                        SetDemoData.platformIntersections,
                    )
                }
                // For a reader who cannot see the overlaps, the tables are the
                // diagram: the values one partitions the union, and the
                // relationships one states the structure the picture carries.
                ChartDataTableView(setDataTable(data, caption = "Regions"))
                ChartDataTableView(setRelationshipTable(data, caption = "Relationships"))
            }
        }

        Text(readout, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("set-readout"))

        HorizontalDivider()
        Text("Controls", style = MaterialTheme.typography.titleSmall)
        DemoToggle("Blend overlap colours", blend) { blend = it }
        DemoToggle("Region values", showValues) { showValues = it }
        DemoToggle("Dim unrelated on selection", dimUnrelated) { dimUnrelated = it }
        DemoToggle("Animate", animate) { animate = it }
    }
}

/** A factual sentence about the selected region. */
private fun describe(selection: io.devkit.chartkit.model.ChartSelectionDetails.Set?): String =
    when {
        selection == null -> "Tap a region"
        selection.isTheoretical ->
            "${selection.label}: nothing is in this combination"

        else -> "${selection.label}: ${selection.value.toLong()}"
    }

/** The demos, each showing something none of the others does. */
internal enum class SetDemo(val label: String, val description: String) {
    TwoSets(
        "Two sets",
        "Three regions. \"Android only\" is 130, not 200 — the exclusive value is derived " +
            "from the total and the overlap.",
    ),
    ThreeSets(
        "Three sets",
        "Seven regions from three totals and four intersections. Every number in the " +
            "picture is derived, so they add up to the union.",
    ),
    FourSets(
        "Four sets",
        "Fifteen regions. No four circles can produce them, so the layout uses four " +
            "congruent ellipses.",
    ),
    Proportional(
        "Proportional",
        "Area proportional to cardinality, with the overlaps solved for the stated " +
            "intersections. Three circles cannot satisfy seven quantities exactly; the " +
            "residual error is measured rather than hidden.",
    ),
    VennVersusEuler(
        "Venn vs Euler",
        "The same data, both ways. Venn draws a region for \"a mammal that is a plant\"; " +
            "Euler nests Mammals inside Animals and puts Plants beside them.",
    ),
    IconGroups(
        "Icon groups",
        "A conceptual diagram whose arrangement is the message: five hand-placed circles, " +
            "with named regions and packed marks inside them.",
    ),
    IconsInLabels(
        "Icons in labels",
        "Set labels are arbitrary Compose content — a drawn mark above a name — and the " +
            "intersections are named rather than counted.",
    ),
    Logos(
        "Logos",
        "Disjoint sets, each holding a packed group of image-like content. A real app " +
            "would put an Image here; ChartKit supplies the anchor and the room.",
    ),
    NestedEuler(
        "Nested Euler",
        "Four levels of containment, siblings, and one set that overlaps across branches " +
            "— the British Isles, modelled as sets rather than as geography.",
    ),
    Disjoint(
        "Disjoint",
        "Sets that share nothing are drawn apart. The layout never invents an overlap the " +
            "data does not have.",
    ),
    ExplicitColors(
        "Explicit colours",
        "One intersection given a specific semantic colour, with everything else left to " +
            "the theme.",
    ),
    FromCollections(
        "From collections",
        "The intersections are counted from real lists rather than stated, so the numbers " +
            "cannot disagree with each other.",
    ),
    Accessibility(
        "Accessibility",
        "The regions table partitions the union; the relationships table states the " +
            "structure. Both come from the model, never from the picture.",
    ),
}
