package io.devkit.chartkit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartWithDataTable
import io.devkit.chartkit.accessibility.setDataTable
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.EulerDiagram
import io.devkit.chartkit.charts.SetDiagram
import io.devkit.chartkit.charts.SetIconGroup
import io.devkit.chartkit.charts.VennDiagram
import io.devkit.chartkit.layer.set.SetColorMode
import io.devkit.chartkit.layer.set.SetFocusMode
import io.devkit.chartkit.model.ChartSelectionDetails
import io.devkit.chartkit.render.ChartRenderMode
import io.devkit.chartkit.set.SetAnalyzer
import io.devkit.chartkit.set.SetContainment
import io.devkit.chartkit.set.SetDefinition
import io.devkit.chartkit.set.SetDiagramLayout
import io.devkit.chartkit.set.SetIntersection
import io.devkit.chartkit.set.SetItems
import io.devkit.chartkit.set.SetShape
import io.devkit.chartkit.set.SetSizing
import io.devkit.chartkit.set.SetStyle
import org.junit.Rule
import org.junit.Test

private data class Account(val id: String)

/**
 * Venn and Euler diagrams on a device: they draw, they select the right region,
 * they carry arbitrary content, and they read out.
 *
 * Positions are chosen from the *canonical arrangements*, which are fixed and
 * documented, so a tap at a stated fraction of the plot lands in a known region
 * without pinning anything to a pixel.
 */
class ChartSetDiagramTest {

    @get:Rule
    val rule = createComposeRule()

    private val two = listOf(
        SetDefinition("android", "Android", 200.0),
        SetDefinition("ios", "iOS", 160.0),
    )
    private val twoOverlap = listOf(SetIntersection(setOf("android", "ios"), 70.0))

    private val three = two + SetDefinition("web", "Web", 240.0)
    private val threeOverlaps = listOf(
        SetIntersection(setOf("android", "ios"), 70.0),
        SetIntersection(setOf("android", "web"), 100.0),
        SetIntersection(setOf("ios", "web"), 80.0),
        SetIntersection(setOf("android", "ios", "web"), 45.0),
    )

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        MaterialTheme { Surface { Column { content() } } }
    }

    private val square = Modifier.fillMaxWidth().height(320.dp)

    // ---- drawing ---------------------------------------------------------

    @Test
    fun aTwoSetVennDraws() {
        rule.setContent {
            Host {
                VennDiagram(
                    sets = two,
                    intersections = twoOverlap,
                    animation = ChartAnimation.None,
                    modifier = square.testTag("venn"),
                )
            }
        }

        rule.onNodeWithTag("venn").assertIsDisplayed()
    }

    @Test
    fun aThreeSetVennDraws() {
        rule.setContent {
            Host {
                VennDiagram(
                    sets = three,
                    intersections = threeOverlaps,
                    animation = ChartAnimation.None,
                    modifier = square.testTag("venn"),
                )
            }
        }

        rule.onNodeWithTag("venn").assertIsDisplayed()
    }

    @Test
    fun aFourSetVennDraws() {
        rule.setContent {
            Host {
                VennDiagram(
                    sets = three + SetDefinition("desktop", "Desktop", 120.0),
                    animation = ChartAnimation.None,
                    modifier = square.testTag("venn"),
                )
            }
        }

        rule.onNodeWithTag("venn").assertIsDisplayed()
    }

    @Test
    fun anEmptyDiagramShowsItsEmptyStateRatherThanCrashing() {
        rule.setContent {
            Host {
                VennDiagram(
                    sets = emptyList(),
                    animation = ChartAnimation.None,
                    modifier = square.testTag("venn"),
                )
            }
        }

        rule.onNodeWithTag("venn").assertIsDisplayed()
    }

    @Test
    fun coincidentAndTangentGeometryDrawsWithoutFailing() {
        rule.setContent {
            Host {
                // Two identical sets and one that touches nothing: the geometry
                // where a lens formula divides by zero and a boolean path
                // operation returns nothing.
                EulerDiagram(
                    sets = listOf(
                        SetDefinition("a", "A", 50.0),
                        SetDefinition("b", "B", 50.0),
                        SetDefinition("c", "C", 50.0),
                    ),
                    intersections = listOf(SetIntersection(setOf("a", "b"), 50.0)),
                    animation = ChartAnimation.None,
                    modifier = square.testTag("euler"),
                )
            }
        }

        rule.onNodeWithTag("euler").assertIsDisplayed()
    }

    @Test
    fun anEulerDiagramOfNestedSetsDraws() {
        rule.setContent {
            Host {
                EulerDiagram(
                    sets = listOf(
                        SetDefinition("animals", "Animals", 100.0),
                        SetDefinition("mammals", "Mammals", 40.0),
                        SetDefinition("plants", "Plants", 60.0),
                    ),
                    containments = listOf(SetContainment("animals", "mammals")),
                    animation = ChartAnimation.None,
                    modifier = square.testTag("euler"),
                )
            }
        }

        rule.onNodeWithTag("euler").assertIsDisplayed()
    }

    // ---- selection -------------------------------------------------------

    /**
     * A two-set Venn in a square plot, tapped at fractions of the plot.
     *
     * The canonical two-set arrangement puts the sets left and right of centre
     * with a lens between them, so 30% across is inside the left set alone and
     * 50% is in both.
     */
    @Composable
    private fun TwoSetSelection(onSelected: (ChartSelectionDetails.Set?) -> Unit) {
        VennDiagram(
            sets = two,
            intersections = twoOverlap,
            animation = ChartAnimation.None,
            onSelectionChanged = { onSelected(it?.set) },
            modifier = square.testTag("venn"),
        )
    }

    @Test
    fun tappingOneSetAloneSelectsItsExclusiveRegion() {
        var selected: ChartSelectionDetails.Set? = null
        rule.setContent { Host { TwoSetSelection { selected = it } } }

        rule.onNodeWithTag("venn").performTouchInput {
            click(Offset(width * 0.25f, height * 0.5f))
        }
        rule.waitForIdle()

        // "Android only" — 130, not the set's 200. Reporting the total for the
        // exclusive region is the classic off-by-everything-shared bug.
        assert(selected?.memberships == setOf("android")) { "was ${selected?.memberships}" }
        assert(selected?.value == 130.0) { "was ${selected?.value}" }
        assert(selected?.label == "Android only") { "was ${selected?.label}" }
    }

    @Test
    fun tappingTheOverlapSelectsTheIntersection() {
        var selected: ChartSelectionDetails.Set? = null
        rule.setContent { Host { TwoSetSelection { selected = it } } }

        rule.onNodeWithTag("venn").performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        rule.waitForIdle()

        assert(selected?.memberships == setOf("android", "ios")) { "was ${selected?.memberships}" }
        assert(selected?.value == 70.0) { "was ${selected?.value}" }
        assert(selected?.label == "Android & iOS") { "was ${selected?.label}" }
    }

    @Test
    fun tappingTheMiddleOfThreeSetsSelectsTheTripleIntersection() {
        var selected: ChartSelectionDetails.Set? = null
        rule.setContent {
            Host {
                VennDiagram(
                    sets = three,
                    intersections = threeOverlaps,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it?.set },
                    modifier = square.testTag("venn"),
                )
            }
        }

        rule.onNodeWithTag("venn").performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        rule.waitForIdle()

        // Not one of the pairs, and not whichever circle was painted last.
        assert(selected?.memberships?.size == 3) { "was ${selected?.memberships}" }
        assert(selected?.value == 45.0) { "was ${selected?.value}" }
    }

    @Test
    fun tappingOutsideEverySetClearsTheSelection() {
        var selected: ChartSelectionDetails.Set? = null
        rule.setContent { Host { TwoSetSelection { selected = it } } }

        rule.onNodeWithTag("venn").performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        rule.waitForIdle()
        assert(selected != null)

        rule.onNodeWithTag("venn").performTouchInput { click(Offset(2f, 2f)) }
        rule.waitForIdle()

        assert(selected == null) { "expected the selection to clear, was $selected" }
    }

    @Test
    fun aSelectionCarriesTheSetsBehindIt() {
        var selected: ChartSelectionDetails.Set? = null
        rule.setContent { Host { TwoSetSelection { selected = it } } }

        rule.onNodeWithTag("venn").performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        rule.waitForIdle()

        assert(selected?.sets?.map { it.label } == listOf("Android", "iOS")) {
            "was ${selected?.sets?.map { it.label }}"
        }
        assert(selected?.totalValue == 70.0)
    }

    @Test
    fun anEmptyTheoreticalRegionSelectsAndSaysItIsEmpty() {
        var selected: ChartSelectionDetails.Set? = null
        rule.setContent {
            Host {
                // No intersections at all, but Venn semantics still draw the
                // overlap; a reader who taps it is told it holds nothing.
                VennDiagram(
                    sets = two,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it?.set },
                    modifier = square.testTag("venn"),
                )
            }
        }

        rule.onNodeWithTag("venn").performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        rule.waitForIdle()

        assert(selected?.memberships == setOf("android", "ios")) { "was ${selected?.memberships}" }
        assert(selected?.value == 0.0)
        assert(selected?.isTheoretical == true)
    }

    @Test
    fun aStaticRenderTakesNoPointerInput() {
        var selected: ChartSelectionDetails.Set? = null
        rule.setContent {
            Host {
                VennDiagram(
                    sets = two,
                    intersections = twoOverlap,
                    animation = ChartAnimation.None,
                    renderMode = ChartRenderMode.Static,
                    onSelectionChanged = { selected = it?.set },
                    modifier = square.testTag("venn"),
                )
            }
        }

        rule.onNodeWithTag("venn").performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        rule.waitForIdle()

        assert(selected == null)
    }

    // ---- tooltip ---------------------------------------------------------

    @Test
    fun theTooltipNamesTheRegionAndItsExclusiveValue() {
        rule.setContent {
            Host {
                VennDiagram(
                    sets = two,
                    intersections = twoOverlap,
                    animation = ChartAnimation.None,
                    modifier = square.testTag("venn"),
                )
            }
        }

        rule.onNodeWithTag("venn").performTouchInput {
            click(Offset(width * 0.25f, height * 0.5f))
        }
        rule.waitForIdle()

        rule.onNodeWithText("Android only", substring = true).assertIsDisplayed()
        // Two nodes carry the number — the label drawn in the region and the
        // tooltip above it — which is the intended behaviour, not a duplicate.
        rule.onAllNodesWithText("130", substring = true).onFirst().assertIsDisplayed()
    }

    // ---- content slots ---------------------------------------------------

    @Test
    fun aCustomSetLabelIsShown() {
        rule.setContent {
            Host {
                VennDiagram(
                    sets = two,
                    intersections = twoOverlap,
                    animation = ChartAnimation.None,
                    setLabel = { scope ->
                        Row { Text("★ ${scope.set.label}") }
                    },
                    modifier = square.testTag("venn"),
                )
            }
        }

        // Compose content, laid out and measured by Compose — not a glyph
        // rasterised onto the canvas.
        rule.onNodeWithText("★ Android").assertIsDisplayed()
        rule.onNodeWithText("★ iOS").assertIsDisplayed()
    }

    @Test
    fun aCustomRegionLabelReplacesTheValue() {
        rule.setContent {
            Host {
                VennDiagram(
                    sets = two,
                    intersections = twoOverlap,
                    animation = ChartAnimation.None,
                    regionLabel = { scope ->
                        if (scope.region.memberships.size == 2) Text("Shared")
                    },
                    modifier = square.testTag("venn"),
                )
            }
        }

        rule.onNodeWithText("Shared").assertIsDisplayed()
    }

    @Test
    fun arbitraryRegionContentIsPlacedInsideItsRegion() {
        rule.setContent {
            Host {
                VennDiagram(
                    sets = two,
                    intersections = twoOverlap,
                    animation = ChartAnimation.None,
                    showValues = false,
                    regionContent = { scope ->
                        if (scope.region.memberships == setOf("android")) {
                            Text("content-here")
                        }
                    },
                    modifier = square.testTag("venn"),
                )
            }
        }

        rule.onNodeWithText("content-here").assertIsDisplayed()
    }

    @Test
    fun anIconGroupPacksIntoARegion() {
        rule.setContent {
            Host {
                VennDiagram(
                    sets = two,
                    intersections = twoOverlap,
                    animation = ChartAnimation.None,
                    showValues = false,
                    regionContent = { scope ->
                        if (scope.region.memberships == setOf("android")) {
                            SetIconGroup(
                                items = listOf(Color.Red, Color.Blue, Color.Green),
                                available = scope.clearance,
                            ) { color ->
                                androidx.compose.foundation.layout.Box(
                                    Modifier.size(10.dp).background(color).testTag("glyph"),
                                )
                            }
                        }
                    },
                    modifier = square.testTag("venn"),
                )
            }
        }

        rule.onNodeWithTag("venn").assertIsDisplayed()
    }

    @Test
    fun aRegionScopeReportsTheRoomItHas() {
        var clearance = -1f
        rule.setContent {
            Host {
                VennDiagram(
                    sets = two,
                    intersections = twoOverlap,
                    animation = ChartAnimation.None,
                    regionContent = { scope ->
                        if (scope.region.memberships == setOf("android")) {
                            clearance = scope.clearance
                        }
                    },
                    modifier = square.testTag("venn"),
                )
            }
        }
        rule.waitForIdle()

        // The number content consults before deciding whether it fits. Without
        // it, an icon group would have to guess.
        assert(clearance > 0f) { "clearance was $clearance" }
    }

    // ---- colour ----------------------------------------------------------

    @Test
    fun switchingColourModeChangesNothingAboutTheData() {
        var selected: ChartSelectionDetails.Set? = null
        rule.setContent {
            Host {
                var blend by remember { mutableStateOf(true) }
                Text(
                    text = if (blend) "blend" else "byset",
                    modifier = Modifier.testTag("mode"),
                )
                VennDiagram(
                    sets = two,
                    intersections = twoOverlap,
                    animation = ChartAnimation.None,
                    colorMode = if (blend) SetColorMode.Blend else SetColorMode.BySet,
                    onSelectionChanged = { selected = it?.set },
                    modifier = square.testTag("venn"),
                )
                androidx.compose.runtime.LaunchedEffect(Unit) { blend = false }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("venn").performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        rule.waitForIdle()

        rule.onNodeWithText("byset").assertIsDisplayed()
        assert(selected?.value == 70.0) { "colour mode must not touch the data" }
    }

    @Test
    fun anExplicitIntersectionColourDraws() {
        rule.setContent {
            Host {
                VennDiagram(
                    sets = two,
                    intersections = twoOverlap,
                    animation = ChartAnimation.None,
                    intersectionStyle = { region ->
                        if (region.memberships.size == 2) SetStyle(fill = Color.Magenta) else null
                    },
                    modifier = square.testTag("venn"),
                )
            }
        }

        rule.onNodeWithTag("venn").assertIsDisplayed()
    }

    @Test
    fun dimmingUnrelatedSetsDraws() {
        rule.setContent {
            Host {
                VennDiagram(
                    sets = three,
                    intersections = threeOverlaps,
                    animation = ChartAnimation.None,
                    focusMode = SetFocusMode.DimUnrelated,
                    modifier = square.testTag("venn"),
                )
            }
        }

        rule.onNodeWithTag("venn").performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        rule.waitForIdle()

        rule.onNodeWithTag("venn").assertIsDisplayed()
    }

    // ---- the generic API -------------------------------------------------

    @Test
    fun theGenericDiagramSwitchesStrategyWithoutChangingTheData() {
        rule.setContent {
            Host {
                var euler by remember { mutableStateOf(false) }
                val data = remember {
                    SetAnalyzer.analyze(
                        listOf(
                            SetDefinition("animals", "Animals", 100.0),
                            SetDefinition("mammals", "Mammals", 40.0),
                        ),
                        containments = listOf(SetContainment("animals", "mammals")),
                    )
                }
                Text(if (euler) "euler" else "venn", modifier = Modifier.testTag("strategy"))
                SetDiagram(
                    data = data,
                    layout = if (euler) SetDiagramLayout.Euler else SetDiagramLayout.Venn,
                    animation = ChartAnimation.None,
                    modifier = square.testTag("set"),
                )
                androidx.compose.runtime.LaunchedEffect(Unit) { euler = true }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("euler").assertIsDisplayed()
        rule.onNodeWithTag("set").assertIsDisplayed()
    }

    @Test
    fun aCustomArrangementIsHonoured() {
        rule.setContent {
            Host {
                val data = remember {
                    SetAnalyzer.analyze(
                        listOf(
                            SetDefinition("a", "A", 1.0),
                            SetDefinition("b", "B", 1.0),
                        ),
                    )
                }
                SetDiagram(
                    data = data,
                    layout = SetDiagramLayout.Custom(
                        mapOf(
                            "a" to SetShape.Circle(-0.5, 0.0, 1.0),
                            "b" to SetShape.Circle(0.5, 0.0, 1.0),
                        ),
                    ),
                    animation = ChartAnimation.None,
                    modifier = square.testTag("set"),
                )
            }
        }

        rule.onNodeWithTag("set").assertIsDisplayed()
    }

    // ---- collections -----------------------------------------------------

    @Test
    fun aCollectionDrivenDiagramCountsItsOwnIntersections() {
        var selected: ChartSelectionDetails.Set? = null
        rule.setContent {
            Host {
                VennDiagram(
                    sets = listOf(
                        SetItems("a", "A", (1..10).map { Account("id-$it") }),
                        SetItems("b", "B", (6..15).map { Account("id-$it") }),
                    ),
                    itemKey = Account::id,
                    animation = ChartAnimation.None,
                    onSelectionChanged = { selected = it?.set },
                    modifier = square.testTag("venn"),
                )
            }
        }

        rule.onNodeWithTag("venn").performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        rule.waitForIdle()

        assert(selected?.value == 5.0) { "was ${selected?.value}" }
    }

    // ---- accessibility ---------------------------------------------------

    @Test
    fun theDataTableNamesEveryRegionAndItsValue() {
        rule.setContent {
            Host {
                val data = remember { SetAnalyzer.analyze(two, twoOverlap) }
                ChartWithDataTable(
                    table = setDataTable(data, caption = "Platforms"),
                    modifier = Modifier.testTag("table"),
                ) {
                    VennDiagram(
                        sets = two,
                        intersections = twoOverlap,
                        animation = ChartAnimation.None,
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                    )
                }
            }
        }

        rule.onNodeWithTag("table").assertIsDisplayed()
        rule.onNodeWithText("Table").performTouchInput { click() }
        rule.waitForIdle()

        // The regions, not the sets: these partition the union and therefore
        // add up, which the set totals do not.
        rule.onNodeWithText("Android only", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("130", substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun aProportionalDiagramDraws() {
        rule.setContent {
            Host {
                VennDiagram(
                    sets = three,
                    intersections = threeOverlaps,
                    sizing = SetSizing.Proportional,
                    animation = ChartAnimation.None,
                    modifier = square.testTag("venn"),
                )
            }
        }

        rule.onNodeWithTag("venn").assertIsDisplayed()
    }

    @Test
    fun anAnimatedDiagramSettlesAtItsFinalGeometry() {
        var selected: ChartSelectionDetails.Set? = null
        rule.setContent {
            Host {
                VennDiagram(
                    sets = two,
                    intersections = twoOverlap,
                    // The real animation, on the test clock — not a sleep.
                    animation = ChartAnimation.Default,
                    onSelectionChanged = { selected = it?.set },
                    modifier = square.testTag("venn"),
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("venn").performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }
        rule.waitForIdle()

        // The reveal is a fade, so hit testing is correct throughout it rather
        // than only once it finishes.
        assert(selected?.memberships == setOf("android", "ios")) { "was ${selected?.memberships}" }
    }
}
