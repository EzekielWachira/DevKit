package io.devkit.chartkit

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.DialGauge
import io.devkit.chartkit.charts.GaugeChart
import io.devkit.chartkit.charts.GaugeReading
import io.devkit.chartkit.charts.GaugeShape
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.gauge.GaugeDetail
import io.devkit.chartkit.gauge.GaugeInteraction
import io.devkit.chartkit.gauge.GaugeMarker
import io.devkit.chartkit.gauge.GaugeNeedleShape
import io.devkit.chartkit.gauge.GaugeNeedleStyle
import io.devkit.chartkit.gauge.GaugePane
import io.devkit.chartkit.gauge.GaugePivotStyle
import io.devkit.chartkit.gauge.GaugeTickConfig
import io.devkit.chartkit.gauge.GaugeValue
import io.devkit.chartkit.gauge.GaugeValuePosition
import io.devkit.chartkit.layer.polar.GaugeBand
import io.devkit.chartkit.theme.ChartKitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private val speedBands = listOf(
    GaugeBand(0.0, 120.0, "Normal"),
    GaugeBand(120.0, 160.0, "Caution"),
    GaugeBand(160.0, 200.0, "Over limit"),
)

/**
 * Dial gauges on a device.
 *
 * The tests that need pixels, a composition or an animation clock: what a
 * needle does when the value changes twice in quick succession, what a finger
 * on the arc reads, and what a screen reader is told. The scale, the ticks, the
 * bands and the needle outlines are arithmetic and are covered without a device
 * in `GaugeScaleTest` and `GaugeAxisTest`.
 */
class ChartGaugeDialTest {

    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun Harness(content: @Composable () -> Unit) {
        MaterialTheme { Surface { ChartKitTheme { content() } } }
    }

    // ---- rendering --------------------------------------------------------

    @Test
    fun aDialDrawsFromOneValue() {
        rule.setContent {
            Harness {
                DialGauge(
                    value = 82.0,
                    min = 0.0,
                    max = 200.0,
                    animation = ChartAnimation.None,
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        rule.onNodeWithTag("dial").assertIsDisplayed()
    }

    @Test
    fun everyShapeDraws() {
        rule.setContent {
            Harness {
                androidx.compose.foundation.layout.Column {
                    listOf(
                        "semi" to GaugeShape.SemiCircle,
                        "three-quarter" to GaugeShape.ThreeQuarter,
                        "full" to GaugeShape.FullCircle,
                        "custom" to GaugeShape.between(-120f, 120f),
                    ).forEach { (tag, shape) ->
                        DialGauge(
                            value = 50.0,
                            shape = shape,
                            animation = ChartAnimation.None,
                            modifier = Modifier.size(160.dp).testTag(tag),
                        )
                    }
                }
            }
        }
        listOf("semi", "three-quarter", "full", "custom").forEach {
            rule.onNodeWithTag(it).assertIsDisplayed()
        }
    }

    @Test
    fun aDialWithEveryFeatureDraws() {
        rule.setContent {
            Harness {
                DialGauge(
                    value = 140.0,
                    min = 0.0,
                    max = 200.0,
                    shape = GaugeShape.between(-90f, 90f),
                    label = "Speed",
                    unit = "km/h",
                    bands = speedBands,
                    markers = listOf(GaugeMarker(112.0, "Limit")),
                    ticks = GaugeTickConfig(interval = 20.0, minorCount = 4),
                    needle = GaugeNeedleStyle(shape = GaugeNeedleShape.Triangle, tail = 0.2f),
                    pivot = GaugePivotStyle(radius = 0.08f),
                    pane = GaugePane.Themed,
                    animation = ChartAnimation.None,
                    valueContent = { Text("140") },
                    modifier = Modifier.size(320.dp).testTag("dial"),
                )
            }
        }
        rule.onNodeWithTag("dial").assertIsDisplayed()
        rule.onNodeWithText("140").assertIsDisplayed()
    }

    @Test
    fun anEmptyReadingFallsBackRatherThanDrawingNaN() {
        rule.setContent {
            Harness {
                DialGauge(
                    value = Double.NaN,
                    animation = ChartAnimation.None,
                    modifier = Modifier.size(240.dp).testTag("dial"),
                )
            }
        }
        rule.onNodeWithTag("dial").assertIsDisplayed()
    }

    @Test
    fun aDialSurvivesEverySizeItIsGiven() {
        // 80dp is below the compact threshold; 600 is well above it. Neither
        // may fail to compose, and neither may draw at a negative radius. All
        // three at once: a Compose test rule allows one setContent.
        val sides = listOf(80, 200, 600)
        rule.setContent {
            Harness {
                androidx.compose.foundation.layout.Column(
                    Modifier.verticalScroll(rememberScrollState()),
                ) {
                    sides.forEach { side ->
                        DialGauge(
                            value = 60.0,
                            bands = speedBands,
                            max = 200.0,
                            ticks = GaugeTickConfig(interval = 10.0),
                            animation = ChartAnimation.None,
                            detail = GaugeDetail.Auto,
                            modifier = Modifier.size(side.dp).testTag("dial-$side"),
                        )
                    }
                }
            }
        }
        // Scrolled to first: on a small screen the 600dp dial is below the
        // fold, and "not currently on screen" is not the failure this test is
        // looking for.
        sides.forEach { rule.onNodeWithTag("dial-$it").performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun aVeryWideBoxStillProducesADial() {
        rule.setContent {
            Harness {
                DialGauge(
                    value = 60.0,
                    shape = GaugeShape.between(-90f, 90f),
                    animation = ChartAnimation.None,
                    modifier = Modifier.fillMaxWidth().height(120.dp).testTag("dial"),
                )
            }
        }
        rule.onNodeWithTag("dial").assertIsDisplayed()
    }

    // ---- animation --------------------------------------------------------

    @Test
    fun theNeedleTravelsRatherThanJumping() {
        rule.mainClock.autoAdvance = false
        var speed by mutableDoubleStateOf(0.0)
        val seen = mutableListOf<Double>()
        rule.setContent {
            Harness {
                DialGauge(
                    value = speed,
                    min = 0.0,
                    max = 200.0,
                    animation = ChartAnimation.Default,
                    valueContent = { animated -> seen += animated },
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        seen.clear()
        speed = 200.0
        // Part-way through: the readout is between the two, not at either end.
        rule.mainClock.advanceTimeBy(150)
        val midway = seen.lastOrNull()
        assertNotNull("the readout never reported a value", midway)
        assertTrue("expected an intermediate value, got $midway", midway!! > 0.0 && midway < 200.0)

        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(200.0, seen.last(), 0.5)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun aRapidChangeContinuesFromWhereTheNeedleIsRatherThanRestarting() {
        rule.mainClock.autoAdvance = false
        var speed by mutableDoubleStateOf(0.0)
        val seen = mutableListOf<Double>()
        rule.setContent {
            Harness {
                DialGauge(
                    value = speed,
                    min = 0.0,
                    max = 200.0,
                    animation = ChartAnimation.Default,
                    valueContent = { animated -> seen += animated },
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        rule.mainClock.advanceTimeBy(1_000)

        speed = 100.0
        rule.mainClock.advanceTimeBy(150)
        val partway = seen.last()
        assertTrue("expected to be under way, was $partway", partway > 0.0)

        seen.clear()
        // Retargeted mid-flight. The needle must carry on from where it is, not
        // snap back to zero and start again — the failure that makes a live
        // dial stutter.
        speed = 200.0
        rule.mainClock.advanceTimeBy(16)
        val afterRetarget = seen.last()
        assertTrue(
            "the needle jumped backwards: $partway then $afterRetarget",
            afterRetarget >= partway - 1.0,
        )

        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(200.0, seen.last(), 0.5)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun animationOffSettlesImmediately() {
        val seen = mutableListOf<Double>()
        rule.setContent {
            Harness {
                DialGauge(
                    value = 137.0,
                    min = 0.0,
                    max = 200.0,
                    animation = ChartAnimation.None,
                    valueContent = { animated -> seen += animated },
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        rule.waitForIdle()
        assertEquals(137.0, seen.last(), 0.001)
    }

    // ---- interaction ------------------------------------------------------

    @Test
    fun aDisplayGaugeIgnoresTaps() {
        var changes = 0
        rule.setContent {
            Harness {
                DialGauge(
                    value = 50.0,
                    animation = ChartAnimation.None,
                    interaction = GaugeInteraction.None,
                    onValueChange = { changes++ },
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        rule.onNodeWithTag("dial").performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(0, changes)
    }

    @Test
    fun aTapOnTheArcReadsAValueOutOfIt() {
        var reported: Double? = null
        rule.setContent {
            Harness {
                DialGauge(
                    value = 0.0,
                    min = 0.0,
                    max = 200.0,
                    // A half dial opening upward: the top of the arc is 100.
                    shape = GaugeShape.between(-90f, 90f),
                    animation = ChartAnimation.None,
                    interaction = GaugeInteraction.Tap,
                    onValueChange = { reported = it },
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        // Straight up from the pivot — which on a half dial sits low in the
        // box — and inside the outer radius rather than beyond the labels.
        rule.onNodeWithTag("dial").performTouchInput {
            click(Offset(width / 2f, height * 0.45f))
        }
        rule.waitForIdle()
        assertNotNull("a tap on the arc reported nothing", reported)
        assertEquals(100.0, reported!!, 25.0)
    }

    @Test
    fun aTapOutsideTheSweepIsNotAReading() {
        var reported: Double? = null
        rule.setContent {
            Harness {
                DialGauge(
                    value = 0.0,
                    min = 0.0,
                    max = 200.0,
                    shape = GaugeShape.between(-90f, 90f),
                    animation = ChartAnimation.None,
                    interaction = GaugeInteraction.Tap,
                    onValueChange = { reported = it },
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        // Below the pivot of a dial that opens upward. Wrapping this to the
        // nearest end is how a naive atan2 gauge sets itself to maximum when a
        // finger strays.
        rule.onNodeWithTag("dial").performTouchInput {
            click(Offset(width / 2f, height * 0.95f))
        }
        rule.waitForIdle()
        assertEquals(null, reported)
    }

    @Test
    fun aDragAroundTheArcMovesTheValue() {
        var reported: Double? = null
        rule.setContent {
            Harness {
                DialGauge(
                    value = 0.0,
                    min = 0.0,
                    max = 200.0,
                    shape = GaugeShape.between(-90f, 90f),
                    animation = ChartAnimation.None,
                    interaction = GaugeInteraction.Drag,
                    onValueChange = { reported = it },
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        rule.onNodeWithTag("dial").performTouchInput {
            swipe(
                start = Offset(width * 0.2f, height * 0.5f),
                end = Offset(width * 0.8f, height * 0.5f),
                durationMillis = 200,
            )
        }
        rule.waitForIdle()
        assertNotNull("a drag reported nothing", reported)
        // Ending on the right of a dial that opens upward is near the maximum.
        assertTrue("expected the upper half of the range, got $reported", reported!! > 100.0)
    }

    @Test
    fun anInteractiveDialSnapsToItsStep() {
        var reported: Double? = null
        rule.setContent {
            Harness {
                DialGauge(
                    value = 0.0,
                    min = 0.0,
                    max = 200.0,
                    shape = GaugeShape.between(-90f, 90f),
                    animation = ChartAnimation.None,
                    interaction = GaugeInteraction.Tap,
                    step = 25.0,
                    onValueChange = { reported = it },
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        rule.onNodeWithTag("dial").performTouchInput {
            click(Offset(width / 2f, height * 0.45f))
        }
        rule.waitForIdle()
        assertNotNull(reported)
        assertEquals(0.0, reported!! % 25.0, 0.001)
    }

    // ---- threshold state --------------------------------------------------

    @Test
    fun theDialReportsWhichBandItsValueIsIn() {
        val readings = mutableListOf<GaugeReading>()
        rule.setContent {
            Harness {
                DialGauge(
                    value = 140.0,
                    min = 0.0,
                    max = 200.0,
                    bands = speedBands,
                    animation = ChartAnimation.None,
                    onReadingChanged = { readings += it },
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        rule.waitForIdle()
        assertEquals("Caution", readings.last().bandLabel)
        assertEquals(1, readings.last().bandIndex)
        assertEquals(false, readings.last().isOutOfRange)
    }

    @Test
    fun anOutOfRangeReadingIsReportedAsItselfAndFlagged() {
        val readings = mutableListOf<GaugeReading>()
        rule.setContent {
            Harness {
                DialGauge(
                    value = 250.0,
                    min = 0.0,
                    max = 200.0,
                    bands = speedBands,
                    animation = ChartAnimation.None,
                    onReadingChanged = { readings += it },
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        rule.waitForIdle()
        // Clamped where it is drawn; never where it is reported.
        assertEquals(250.0, readings.last().value, 0.001)
        assertTrue(readings.last().isOutOfRange)
    }

    // ---- multiple needles -------------------------------------------------

    @Test
    fun severalNeedlesDrawAndAreLegended() {
        rule.setContent {
            Harness {
                DialGauge(
                    series = listOf(
                        GaugeValue("current", 82.0, "Current"),
                        GaugeValue("target", 130.0, "Target", style = GaugeNeedleStyle.Target),
                        GaugeValue("average", 96.0, "Average", style = GaugeNeedleStyle.Thin),
                    ),
                    min = 0.0,
                    max = 200.0,
                    animation = ChartAnimation.None,
                    legend = LegendPosition.Bottom,
                    modifier = Modifier.size(320.dp).testTag("dial"),
                )
            }
        }
        rule.onNodeWithTag("dial").assertIsDisplayed()
        listOf("Current", "Target", "Average").forEach {
            rule.onAllNodesWithContentDescription(it, substring = true).onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun oneNeedleGetsNoLegend() {
        rule.setContent {
            Harness {
                DialGauge(
                    series = listOf(GaugeValue("only", 82.0, "Speed")),
                    animation = ChartAnimation.None,
                    legend = LegendPosition.Bottom,
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        // A legend for one needle is furniture repeating what the dial says.
        rule.onAllNodesWithContentDescription("Speed", substring = true).assertCountEquals(1)
    }

    @Test
    fun eachNeedleAnimatesToItsOwnValue() {
        rule.mainClock.autoAdvance = false
        var current by mutableDoubleStateOf(0.0)
        rule.setContent {
            Harness {
                DialGauge(
                    series = listOf(
                        GaugeValue("current", current, "Current"),
                        GaugeValue("target", 130.0, "Target"),
                    ),
                    min = 0.0,
                    max = 200.0,
                    animation = ChartAnimation.Default,
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        // Moving one needle must not disturb the other; identity is by id, not
        // by list position.
        current = 180.0
        rule.mainClock.advanceTimeBy(1_000)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithContentDescription("Target: 130", substring = true).assertIsDisplayed()
    }

    // ---- accessibility ----------------------------------------------------

    @Test
    fun theDialAnnouncesItsLabelValueUnitAndRange() {
        rule.setContent {
            Harness {
                DialGauge(
                    value = 82.0,
                    min = 0.0,
                    max = 200.0,
                    label = "Speed",
                    unit = "kilometres per hour",
                    animation = ChartAnimation.None,
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        rule.onNodeWithContentDescription("Speed", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("82", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("kilometres per hour", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Range: 0 to 200", substring = true).assertIsDisplayed()
    }

    @Test
    fun theDialNamesTheBandOnlyBecauseTheCallerDidSo() {
        rule.setContent {
            Harness {
                DialGauge(
                    value = 180.0,
                    min = 0.0,
                    max = 200.0,
                    label = "Speed",
                    bands = speedBands,
                    animation = ChartAnimation.None,
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        // The caller's own words, not a severity ChartKit inferred from a colour.
        rule.onNodeWithContentDescription("Current range: Over limit", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun anUnlabelledBandContributesNoMeaning() {
        rule.setContent {
            Harness {
                DialGauge(
                    value = 180.0,
                    min = 0.0,
                    max = 200.0,
                    label = "Speed",
                    bands = listOf(GaugeBand(160.0, 200.0)),
                    animation = ChartAnimation.None,
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        rule.onAllNodesWithContentDescription("Current range", substring = true).assertCountEquals(0)
    }

    @Test
    fun anOutOfRangeReadingIsAnnouncedAsSuch() {
        rule.setContent {
            Harness {
                DialGauge(
                    value = 250.0,
                    min = 0.0,
                    max = 200.0,
                    label = "Speed",
                    animation = ChartAnimation.None,
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        rule.onNodeWithContentDescription("250", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Outside the gauge's range", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun everyNeedleIsAnnouncedWithItsOwnLabel() {
        rule.setContent {
            Harness {
                DialGauge(
                    series = listOf(
                        GaugeValue("current", 82.0, "Current speed"),
                        GaugeValue("target", 100.0, "Target speed"),
                    ),
                    min = 0.0,
                    max = 200.0,
                    unit = "km/h",
                    animation = ChartAnimation.None,
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        rule.onNodeWithContentDescription("Current speed: 82", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Target speed: 100", substring = true).assertIsDisplayed()
    }

    @Test
    fun anAdjustableDialExposesItsRangeToAScreenReader() {
        rule.setContent {
            Harness {
                DialGauge(
                    value = 80.0,
                    min = 0.0,
                    max = 200.0,
                    label = "Speed limiter",
                    animation = ChartAnimation.None,
                    interaction = GaugeInteraction.Drag,
                    step = 5.0,
                    onValueChange = {},
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        // The tag is on the layout wrapper; the chart's own semantics — which
        // are `clearAndSetSemantics`, so a leaf — are on the canvas inside it.
        val node = rule.onNodeWithContentDescription("Speed limiter", substring = true)
            .fetchSemanticsNode()
        val range = node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertNotNull("an adjustable dial should publish range info", range)
        assertEquals(80f, range!!.current, 0.01f)
        assertEquals(0f, range.range.start, 0.01f)
        assertEquals(200f, range.range.endInclusive, 0.01f)
        // And the action a screen reader drives it with.
        assertNotNull(
            node.config.getOrNull(SemanticsActions.SetProgress),
        )
    }

    @Test
    fun aDisplayDialPublishesNoAdjustableSemantics() {
        rule.setContent {
            Harness {
                DialGauge(
                    value = 80.0,
                    animation = ChartAnimation.None,
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        val node = rule.onNodeWithContentDescription("Range: 0 to 100", substring = true)
            .fetchSemanticsNode()
        assertEquals(null, node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
    }

    @Test
    fun aScreenReaderCanAdjustAnInteractiveDial() {
        var speed by mutableDoubleStateOf(80.0)
        rule.setContent {
            Harness {
                DialGauge(
                    value = speed,
                    min = 0.0,
                    max = 200.0,
                    animation = ChartAnimation.None,
                    interaction = GaugeInteraction.Drag,
                    step = 5.0,
                    onValueChange = { speed = it },
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        val action = rule.onNodeWithContentDescription("Range: 0 to 200", substring = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.SetProgress]
        rule.runOnUiThread { action.action?.invoke(120f) }
        rule.waitForIdle()
        assertEquals(120.0, speed, 0.001)
    }

    // ---- the arc gauge still works ---------------------------------------

    @Test
    fun theArcGaugeIsUnchanged() {
        rule.setContent {
            Harness {
                GaugeChart(
                    value = 72.0,
                    min = 0.0,
                    max = 100.0,
                    label = "Attainment",
                    bands = listOf(GaugeBand(0.0, 50.0, "Behind"), GaugeBand(50.0, 100.0, "Ahead")),
                    animation = ChartAnimation.None,
                    centerContent = { Text("72%") },
                    modifier = Modifier.size(240.dp).testTag("arc"),
                )
            }
        }
        rule.onNodeWithTag("arc").assertIsDisplayed()
        rule.onNodeWithText("72%").assertIsDisplayed()
        rule.onNodeWithContentDescription("Attainment", substring = true).assertIsDisplayed()
    }

    @Test
    fun bothGaugesShareTheirBandModel() {
        // The same GaugeBand list drives an arc gauge and a dial; if the two
        // had separate models this would not compile, let alone draw.
        rule.setContent {
            Harness {
                androidx.compose.foundation.layout.Column {
                    GaugeChart(
                        value = 72.0,
                        bands = speedBands,
                        max = 200.0,
                        animation = ChartAnimation.None,
                        modifier = Modifier.size(160.dp).testTag("arc"),
                    )
                    DialGauge(
                        value = 72.0,
                        bands = speedBands,
                        max = 200.0,
                        animation = ChartAnimation.None,
                        modifier = Modifier.size(160.dp).testTag("dial"),
                    )
                }
            }
        }
        rule.onNodeWithTag("arc").assertIsDisplayed()
        rule.onNodeWithTag("dial").assertIsDisplayed()
    }

    // ---- value readout ----------------------------------------------------

    @Test
    fun theReadoutSitsBelowThePivotWhenAsked() {
        rule.setContent {
            Harness {
                DialGauge(
                    value = 82.0,
                    shape = GaugeShape.between(-90f, 90f),
                    valuePosition = GaugeValuePosition.BelowCenter,
                    animation = ChartAnimation.None,
                    valueContent = { Text("82", modifier = Modifier.testTag("readout")) },
                    modifier = Modifier.size(300.dp).testTag("dial"),
                )
            }
        }
        val dial = rule.onNodeWithTag("dial").fetchSemanticsNode().boundsInRoot
        val readout = rule.onNodeWithTag("readout").fetchSemanticsNode().boundsInRoot
        // Below the middle of the box, which for a half dial is where the pivot
        // is — a number printed on the pivot sits under the needle.
        assertTrue(
            "the readout should sit below the pivot",
            readout.center.y > dial.center.y,
        )
    }
}
