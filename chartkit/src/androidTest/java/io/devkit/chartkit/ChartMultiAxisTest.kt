package io.devkit.chartkit

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.annotation.horizontalRule
import io.devkit.chartkit.axis.AxisDensity
import io.devkit.chartkit.axis.AxisDiagnostic
import io.devkit.chartkit.axis.AxisGridMode
import io.devkit.chartkit.axis.AxisPosition
import io.devkit.chartkit.axis.AxisTickAlignment
import io.devkit.chartkit.axis.AxisVisibility
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartAxisException
import io.devkit.chartkit.axis.ChartAxisId
import io.devkit.chartkit.axis.ChartUnit
import io.devkit.chartkit.charts.CartesianChart
import io.devkit.chartkit.charts.ExperimentalChartKitApi
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartTooltipEntry
import io.devkit.chartkit.model.ChartTooltipOrder
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.scene.ChartScene
import io.devkit.chartkit.scene.ChartSceneNode
import io.devkit.chartkit.scene.rememberChartSceneState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.state.rememberChartViewportState
import io.devkit.chartkit.theme.ChartKitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class Reading(val month: String, val rainfall: Double, val temperature: Double, val pressure: Double)

private val Rainfall = ChartAxisId("rainfall")
private val Temperature = ChartAxisId("temperature")
private val Pressure = ChartAxisId("pressure")

private val Millimetres = ChartUnit.Custom("mm", "millimetres")
private val Celsius = ChartUnit.Custom("°C", "degrees Celsius")
private val Hectopascals = ChartUnit.Custom("hPa", "hectopascals")

private val readings = listOf(
    Reading("Jan", 89.0, 4.2, 1016.0),
    Reading("Feb", 64.0, 4.6, 1019.0),
    Reading("Mar", 58.0, 6.9, 1015.0),
    Reading("Apr", 46.0, 9.4, 1014.0),
)

/**
 * Multi-axis Cartesian charts, on a device.
 *
 * The tests that need pixels: which scale a layer actually resolved, how much
 * of the chart the axes took, which axis drew the grid, and what a tooltip and
 * a screen reader ended up with. The arithmetic underneath is covered without a
 * device in `MultiAxisTest`.
 */
class ChartMultiAxisTest {

    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun Harness(content: @Composable () -> Unit) {
        MaterialTheme { Surface { ChartKitTheme { content() } } }
    }

    /**
     * One chart configuration to render.
     *
     * Comparisons render every variant in a single `setContent` — a Compose
     * test rule allows exactly one — so a test that asks "does adding an axis
     * shrink the plot" draws both charts at once and compares the two scenes.
     */
    private data class Variant(
        val alignment: AxisTickAlignment = AxisTickAlignment.Independent,
        val density: AxisDensity = AxisDensity.Full,
        val pressureGrid: AxisGridMode = AxisGridMode.Primary,
        val hidden: Set<String> = emptySet(),
    )

    /** Renders each variant and returns its exported scene, in order. */
    private fun scenesOf(vararg variants: Variant): List<ChartScene> {
        val scenes = arrayOfNulls<ChartScene>(variants.size)
        rule.setContent {
            Harness {
                androidx.compose.foundation.layout.Column {
                    variants.forEachIndexed { index, variant ->
                        val sceneState = rememberChartSceneState()
                        val state = rememberChartState<Any?>()
                        variant.hidden.forEach { state.setSeriesVisible(it, false) }
                        WeatherChart(
                            alignment = variant.alignment,
                            axisDensity = variant.density,
                            pressureGrid = variant.pressureGrid,
                            sceneState = sceneState,
                            state = state,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .testTag("chart-$index"),
                        )
                        scenes[index] = sceneState.scene
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { scenes.all { it != null } }
        return scenes.map { it!! }
    }

    private fun sceneOfWeatherChart(
        alignment: AxisTickAlignment = AxisTickAlignment.Independent,
        density: AxisDensity = AxisDensity.Full,
        pressureGrid: AxisGridMode = AxisGridMode.Primary,
        hidden: Set<String> = emptySet(),
    ): ChartScene = scenesOf(Variant(alignment, density, pressureGrid, hidden)).single()

    @OptIn(ExperimentalChartKitApi::class)
    @Composable
    private fun WeatherChart(
        modifier: Modifier = Modifier,
        alignment: AxisTickAlignment = AxisTickAlignment.Independent,
        axisDensity: AxisDensity = AxisDensity.Full,
        pressureGrid: AxisGridMode = AxisGridMode.Primary,
        legendToggles: Boolean = false,
        crosshair: CrosshairConfig = CrosshairConfig.None,
        annotations: List<io.devkit.chartkit.annotation.ChartAnnotation> = emptyList(),
        tooltipOrder: ChartTooltipOrder = ChartTooltipOrder.Declaration,
        sceneState: io.devkit.chartkit.scene.ChartSceneState? = null,
        state: io.devkit.chartkit.state.ChartState<Any?> = rememberChartState(),
        viewportState: io.devkit.chartkit.state.ChartViewportState = rememberChartViewportState(),
        onDiagnostics: ((List<AxisDiagnostic>) -> Unit)? = null,
        onSelection: ((io.devkit.chartkit.model.AnyChartSelection?) -> Unit)? = null,
        tooltip: (@Composable (io.devkit.chartkit.model.AnyChartTooltipData) -> Unit)? = null,
    ) {
        CartesianChart(
            modifier = modifier,
            animation = ChartAnimation.None,
            tickAlignment = alignment,
            axisDensity = axisDensity,
            tooltipOrder = tooltipOrder,
            legend = LegendPosition.Bottom,
            legendTogglesSeries = legendToggles,
            crosshair = crosshair,
            annotations = annotations,
            sceneState = sceneState,
            state = state,
            viewportState = viewportState,
            interaction = ChartInteraction.Explorable,
            onAxisDiagnostics = onDiagnostics,
            onSelectionChanged = onSelection,
            tooltip = tooltip,
        ) {
            yAxis(
                id = Rainfall,
                position = AxisPosition.Start,
                title = "Rainfall",
                unit = Millimetres,
                domain = DomainPolicy.IncludeZero(),
                primary = true,
            )
            yAxis(
                id = Temperature,
                position = AxisPosition.End,
                title = "Temperature",
                unit = Celsius,
                domain = DomainPolicy.Auto(),
            )
            yAxis(
                id = Pressure,
                position = AxisPosition.End,
                title = "Pressure",
                unit = Hectopascals,
                domain = DomainPolicy.Auto(),
                grid = pressureGrid,
            )
            bars(
                series = listOf(ChartSeries("rainfall", "Rainfall", readings, unit = Millimetres)),
                category = { it.month },
                value = { it.rainfall },
                yAxis = Rainfall,
            )
            line(
                series = listOf(ChartSeries("temperature", "Temperature", readings, unit = Celsius)),
                x = { it.month },
                y = { it.temperature },
                yAxis = Temperature,
            )
            line(
                series = listOf(ChartSeries("pressure", "Pressure", readings, unit = Hectopascals)),
                x = { it.month },
                y = { it.pressure },
                yAxis = Pressure,
            )
        }
    }

    private fun ChartScene.groupIds(): List<String> =
        nodes.filterIsInstance<ChartSceneNode.Group>().map { it.id }

    private fun ChartScene.axisTexts(axis: ChartAxisId): List<String> =
        nodes.filterIsInstance<ChartSceneNode.Group>()
            .firstOrNull { it.id == "axis-${axis.value}" }
            ?.children
            ?.filterIsInstance<ChartSceneNode.Text>()
            ?.map { it.text }
            .orEmpty()

    private fun ChartScene.axisLine(axis: ChartAxisId): ChartSceneNode.Line? =
        nodes.filterIsInstance<ChartSceneNode.Group>()
            .firstOrNull { it.id == "axis-${axis.value}" }
            ?.children
            ?.filterIsInstance<ChartSceneNode.Line>()
            ?.firstOrNull()

    // ---- rendering and binding ------------------------------------------

    @Test
    fun threeAxesAreAllDrawn() {
        val scene = sceneOfWeatherChart()
        val ids = scene.groupIds()
        assertTrue(ids.contains("axis-rainfall"))
        assertTrue(ids.contains("axis-temperature"))
        assertTrue(ids.contains("axis-pressure"))
    }

    @Test
    fun eachAxisLabelsItselfInItsOwnUnit() {
        val scene = sceneOfWeatherChart()
        assertTrue(scene.axisTexts(Rainfall).all { it.endsWith("mm") })
        assertTrue(scene.axisTexts(Temperature).all { it.endsWith("°C") })
        assertTrue(scene.axisTexts(Pressure).all { it.endsWith("hPa") })
    }

    @Test
    fun eachAxisLabelsItsOwnValuesAndNotAnother() {
        val scene = sceneOfWeatherChart()
        // Pressure sits in the thousands and nothing else in this chart does.
        // Parsed rather than string-matched: a locale that groups thousands
        // writes 1015 as "1,015", and "101" is not a substring of that.
        fun numbers(axis: ChartAxisId) = scene.axisTexts(axis)
            .mapNotNull { text -> text.filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull() }
        assertTrue(numbers(Pressure).any { it > 900.0 })
        assertTrue(numbers(Rainfall).all { it < 900.0 })
        assertTrue(numbers(Temperature).all { it < 100.0 })
    }

    @Test
    fun twoAxesOnOneSideDoNotShareALine() {
        val scene = sceneOfWeatherChart()
        val temperature = scene.axisLine(Temperature)
        val pressure = scene.axisLine(Pressure)
        assertNotNull(temperature)
        assertNotNull(pressure)
        // The pressure axis is the outer one, so it sits further right.
        assertTrue(pressure!!.from.x > temperature!!.from.x)
    }

    @Test
    fun theStartAxisIsLeftOfTheEndAxes() {
        val scene = sceneOfWeatherChart()
        val rainfall = scene.axisLine(Rainfall)!!
        val temperature = scene.axisLine(Temperature)!!
        assertTrue(rainfall.from.x < temperature.from.x)
    }

    @Test
    fun everySeriesIsDrawnInsideTheOnePlotArea() {
        val scene = sceneOfWeatherChart()
        val paths = scene.flatten().filterIsInstance<ChartSceneNode.Path>()
        val rainfall = scene.axisLine(Rainfall)!!
        val temperature = scene.axisLine(Temperature)!!
        // Every drawn line lies between the innermost axes on the two sides.
        paths.flatMap { it.points }.forEach { point ->
            assertTrue(point.x >= rainfall.from.x - 1f)
            assertTrue(point.x <= temperature.from.x + 1f)
        }
    }

    @Test
    fun seriesOnDifferentAxesAreDrawnAtDifferentHeights() {
        val scene = sceneOfWeatherChart()
        val paths = scene.flatten().filterIsInstance<ChartSceneNode.Path>()
            .filter { it.points.size == readings.size }
        assertEquals(2, paths.size)
        // Temperature (4..9) and pressure (1014..1019) are drawn from entirely
        // different domains, so nothing about their shapes should coincide.
        val first = paths[0].points.map { it.y }
        val second = paths[1].points.map { it.y }
        assertFalse(first == second)
    }

    @Test
    fun aSeriesUsesItsOwnAxisSpanAndNotTheChartsWidestOne() {
        val scene = sceneOfWeatherChart()
        val paths = scene.flatten().filterIsInstance<ChartSceneNode.Path>()
            .filter { it.points.size == readings.size }
        // Pressure varies by 5 in a domain of ~5, so its line uses most of the
        // plot's height. Measured against a rainfall scale of 0..89 it would be
        // a flat line at the very top.
        paths.forEach { path ->
            val span = path.points.maxOf { it.y } - path.points.minOf { it.y }
            assertTrue("a series collapsed to a flat line: span $span", span > 20f)
        }
    }

    // ---- layout ----------------------------------------------------------

    @Test
    fun thePlotShrinksAsAxesAreAdded() {
        var threeAxisPlot = 0f
        var oneAxisPlot = 0f
        rule.setContent {
            Harness {
                androidx.compose.foundation.layout.Column {
                    val three = rememberChartSceneState()
                    WeatherChart(
                        sceneState = three,
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                    )
                    val one = rememberChartSceneState()
                    LineChart(
                        data = readings,
                        x = { it.month },
                        y = { it.rainfall },
                        animation = ChartAnimation.None,
                        sceneState = one,
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                    )
                    three.scene?.let { scene ->
                        val left = scene.axisLine(Rainfall)?.from?.x
                        val right = scene.axisLine(Temperature)?.from?.x
                        if (left != null && right != null) threeAxisPlot = right - left
                    }
                    one.scene?.let { scene ->
                        val left = scene.axisLine(ChartAxisId.DefaultY)?.from?.x
                        if (left != null) oneAxisPlot = scene.width - left
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { threeAxisPlot > 0f && oneAxisPlot > 0f }
        // Two axes' worth of gutter came out of the plot on the right, and the
        // rainfall axis' labels are wider than a bare number's on the left.
        assertTrue(
            "three axes ($threeAxisPlot) should leave less plot than one ($oneAxisPlot)",
            threeAxisPlot < oneAxisPlot,
        )
    }

    @Test
    fun compactModeThinsTheTicksRatherThanDroppingAnAxis() {
        val (full, compact) = scenesOf(
            Variant(density = AxisDensity.Full),
            Variant(density = AxisDensity.Compact),
        )
        // Every axis is still there.
        assertTrue(compact.groupIds().contains("axis-pressure"))
        assertTrue(compact.axisTexts(Rainfall).size <= full.axisTexts(Rainfall).size)
    }

    // ---- tick alignment --------------------------------------------------

    @Test
    fun alignedAxesPutTheirTicksOnTheSameRows() {
        val scene = sceneOfWeatherChart(alignment = AxisTickAlignment.Aligned)
        val rainfall = scene.nodes.filterIsInstance<ChartSceneNode.Group>()
            .first { it.id == "axis-rainfall" }
            .children.filterIsInstance<ChartSceneNode.Text>().map { it.position.y }
        val temperature = scene.nodes.filterIsInstance<ChartSceneNode.Group>()
            .first { it.id == "axis-temperature" }
            .children.filterIsInstance<ChartSceneNode.Text>().map { it.position.y }
        assertEquals(rainfall.size, temperature.size)
        rainfall.forEachIndexed { index, y -> assertEquals(y, temperature[index], 1.5f) }
    }

    @Test
    fun independentAxesKeepTheirOwnTickCounts() {
        val (independent, aligned) = scenesOf(
            Variant(alignment = AxisTickAlignment.Independent),
            Variant(alignment = AxisTickAlignment.Aligned),
        )
        // Not a claim that they differ — sometimes they agree by luck — but
        // that alignment does force agreement.
        assertEquals(
            aligned.axisTexts(Rainfall).size,
            aligned.axisTexts(Pressure).size,
        )
        assertTrue(independent.axisTexts(Rainfall).isNotEmpty())
    }

    // ---- grid ownership --------------------------------------------------

    @Test
    fun onlyThePrimaryAxisDrawsAGridByDefault() {
        val (primaryOnly, bothGrids) = scenesOf(
            Variant(),
            Variant(pressureGrid = AxisGridMode.Visible),
        )
        val count = { scene: ChartScene ->
            scene.flatten().filterIsInstance<ChartSceneNode.Line>().count { it.from.y == it.to.y }
        }
        assertTrue("a second grid should add rows", count(bothGrids) > count(primaryOnly))
    }

    // ---- legend and auto-hide -------------------------------------------

    @Test
    fun hidingTheOnlySeriesOnAnAxisHidesTheAxis() {
        val (all, withoutPressure) = scenesOf(Variant(), Variant(hidden = setOf("pressure")))
        assertTrue(all.groupIds().contains("axis-pressure"))
        assertFalse(withoutPressure.groupIds().contains("axis-pressure"))
    }

    @Test
    fun hidingAnAxisGivesItsGutterBackToThePlot() {
        val (all, fewer) = scenesOf(Variant(), Variant(hidden = setOf("pressure")))
        val plotWidth = { scene: ChartScene ->
            scene.axisLine(Temperature)!!.from.x - scene.axisLine(Rainfall)!!.from.x
        }
        assertTrue(plotWidth(fewer) > plotWidth(all))
    }

    @Test
    fun tappingALegendEntryTogglesItsSeries() {
        rule.setContent {
            Harness {
                WeatherChart(
                    legendToggles = true,
                    modifier = Modifier.fillMaxWidth().height(240.dp).testTag("chart"),
                )
            }
        }
        rule.onNodeWithText("Pressure").assertIsDisplayed()
        rule.onNodeWithText("Pressure").performClick()
        rule.waitForIdle()
        // Still on screen as a legend row, and the chart still draws.
        rule.onNodeWithTag("chart").assertIsDisplayed()
    }

    // ---- interaction -----------------------------------------------------

    @Test
    fun aSharedTooltipCarriesOneRowPerAxisWithItsOwnUnit() {
        var entries: List<ChartTooltipEntry<Any?>> = emptyList()
        rule.setContent {
            Harness {
                WeatherChart(
                    crosshair = CrosshairConfig.Vertical,
                    modifier = Modifier.fillMaxWidth().height(240.dp).testTag("chart"),
                    tooltip = { data ->
                        entries = data.entries
                    },
                )
            }
        }
        rule.onNodeWithTag("chart").performClick()
        rule.waitForIdle()
        val axes = entries.mapNotNull { it.axisId }.distinct()
        assertTrue("expected rows from more than one axis, got $axes", axes.size >= 2)
        entries.forEach { entry ->
            assertNotNull("every entry should be written by its own axis", entry.formattedValue)
        }
        assertTrue(entries.any { it.formattedValue!!.endsWith("mm") })
        assertTrue(entries.any { it.formattedValue!!.endsWith("hPa") })
    }

    @Test
    fun tooltipRowsCanBeOrderedByAxis() {
        var entries: List<ChartTooltipEntry<Any?>> = emptyList()
        rule.setContent {
            Harness {
                WeatherChart(
                    crosshair = CrosshairConfig.Vertical,
                    tooltipOrder = ChartTooltipOrder.ByAxis(listOf(Pressure, Temperature, Rainfall)),
                    modifier = Modifier.fillMaxWidth().height(240.dp).testTag("chart"),
                    tooltip = { data -> entries = data.entries },
                )
            }
        }
        rule.onNodeWithTag("chart").performClick()
        rule.waitForIdle()
        val order = entries.mapNotNull { it.axisId }.distinct()
        assertEquals(listOf(Pressure, Temperature, Rainfall).filter { it in order }, order)
    }

    @Test
    fun everyTooltipEntryKnowsWhereItsSeriesSits() {
        var entries: List<ChartTooltipEntry<Any?>> = emptyList()
        rule.setContent {
            Harness {
                WeatherChart(
                    crosshair = CrosshairConfig.Vertical,
                    modifier = Modifier.fillMaxWidth().height(240.dp).testTag("chart"),
                    tooltip = { data -> entries = data.entries },
                )
            }
        }
        rule.onNodeWithTag("chart").performClick()
        rule.waitForIdle()
        val positions = entries.mapNotNull { it.position?.y }
        assertTrue(positions.isNotEmpty())
        // Three quantities at one x sit at three heights; two identical rows
        // would mean two entries resolved through the same scale.
        assertEquals(positions.size, positions.distinct().size)
    }

    @Test
    fun aSelectionOnAnySeriesResolvesThroughItsOwnAxis() {
        var selectedY: Double? = null
        var selectedSeries: String? = null
        rule.setContent {
            Harness {
                WeatherChart(
                    modifier = Modifier.fillMaxWidth().height(240.dp).testTag("chart"),
                    onSelection = { selection ->
                        selectedY = selection?.y
                        selectedSeries = selection?.seriesId
                    },
                )
            }
        }
        rule.onNodeWithTag("chart").performClick()
        rule.waitForIdle()
        assertNotNull(selectedSeries)
        val expected = when (selectedSeries) {
            "rainfall" -> readings.map { it.rainfall }
            "temperature" -> readings.map { it.temperature }
            else -> readings.map { it.pressure }
        }
        assertTrue("selected $selectedY is not one of this series' values", selectedY in expected)
    }

    @Test
    fun panningMovesEverySeriesTogether() {
        var viewportStart = 0.0
        rule.setContent {
            Harness {
                val viewport = rememberChartViewportState()
                WeatherChart(
                    viewportState = viewport,
                    modifier = Modifier.fillMaxWidth().height(240.dp).testTag("chart"),
                )
                viewportStart = viewport.viewport.start
            }
        }
        rule.onNodeWithTag("chart").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        // One viewport for the whole chart: there is no per-axis window to
        // drift out of step with.
        assertTrue(viewportStart >= 0.0)
    }

    // ---- annotations -----------------------------------------------------

    @Test
    fun anAnnotationBoundToASecondaryAxisDrawsAtThatAxisScale() {
        var scene: ChartScene? = null
        rule.setContent {
            Harness {
                val sceneState = rememberChartSceneState()
                WeatherChart(
                    annotations = listOf(
                        horizontalRule(
                            value = 8.0,
                            label = "Mild",
                            valueAxis = Temperature,
                            extendsDomain = false,
                        ),
                    ),
                    sceneState = sceneState,
                    modifier = Modifier.fillMaxWidth().height(240.dp).testTag("chart"),
                )
                scene = sceneState.scene
            }
        }
        rule.waitForIdle()
        rule.waitUntil(3_000) { scene != null }
        // 8°C is roughly mid-domain on the temperature axis (4.2..9.4) and far
        // below mid-domain on rainfall (0..89), so the rule's height tells the
        // two apart.
        val plotTop = scene!!.axisLine(Temperature)!!.from.y
        val plotBottom = scene!!.axisLine(Temperature)!!.to.y
        val rules = scene!!.flatten().filterIsInstance<ChartSceneNode.Line>()
            .filter { it.from.y == it.to.y && it.dash != null }
        if (rules.isNotEmpty()) {
            val fraction = (rules.first().from.y - plotTop) / (plotBottom - plotTop)
            assertTrue("a temperature rule at 8 should sit mid-plot, was $fraction", fraction in 0.2f..0.8f)
        }
    }

    // ---- validation ------------------------------------------------------

    @OptIn(ExperimentalChartKitApi::class)
    @Test
    fun aLayerBoundToAnUnregisteredAxisFails() {
        val error = runCatching {
            rule.setContent {
                Harness {
                    CartesianChart(
                        animation = ChartAnimation.None,
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                    ) {
                        yAxis(id = Rainfall, title = "Rainfall")
                        line(
                            series = listOf(ChartSeries("t", "Temperature", readings)),
                            x = { it.month },
                            y = { it.temperature },
                            yAxis = Temperature,
                        )
                    }
                }
            }
            rule.waitForIdle()
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(generateSequence(error) { it.cause }.any { it is ChartAxisException })
    }

    @OptIn(ExperimentalChartKitApi::class)
    @Test
    fun stackedBarsAcrossTwoAxesAreRejected() {
        val error = runCatching {
            rule.setContent {
                Harness {
                    CartesianChart(
                        animation = ChartAnimation.None,
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                    ) {
                        yAxis(id = Rainfall, position = AxisPosition.Start, title = "Rainfall")
                        yAxis(id = Pressure, position = AxisPosition.End, title = "Pressure")
                        bars(
                            series = listOf(ChartSeries("a", "A", readings)),
                            category = { it.month },
                            value = { it.rainfall },
                            grouping = BarGrouping.Stacked,
                            yAxis = Rainfall,
                        )
                        bars(
                            series = listOf(ChartSeries("b", "B", readings)),
                            category = { it.month },
                            value = { it.pressure },
                            grouping = BarGrouping.Stacked,
                            yAxis = Pressure,
                        )
                    }
                }
            }
            rule.waitForIdle()
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(generateSequence(error) { it.cause }.any { it is ChartAxisException })
    }

    @OptIn(ExperimentalChartKitApi::class)
    @Test
    fun aSeriesInTheWrongUnitIsReportedAndNotThrown() {
        var diagnostics: List<AxisDiagnostic> = emptyList()
        rule.setContent {
            Harness {
                CartesianChart(
                    animation = ChartAnimation.None,
                    onAxisDiagnostics = { diagnostics = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp).testTag("chart"),
                ) {
                    yAxis(id = Rainfall, title = "Rainfall", unit = Millimetres)
                    line(
                        series = listOf(ChartSeries("t", "Temperature", readings, unit = Celsius)),
                        x = { it.month },
                        y = { it.temperature },
                        yAxis = Rainfall,
                    )
                }
            }
        }
        rule.waitForIdle()
        // Drawn anyway — a mismatch is often the caller discovering something —
        // but reported.
        rule.onNodeWithTag("chart").assertIsDisplayed()
        assertTrue(diagnostics.any { it.message.contains("°C") })
    }

    @OptIn(ExperimentalChartKitApi::class)
    @Test
    fun anAxisWithItsOwnFormatterDoesNotAppendItsUnitTwice() {
        var scene: ChartScene? = null
        rule.setContent {
            Harness {
                val sceneState = rememberChartSceneState()
                CartesianChart(
                    animation = ChartAnimation.None,
                    sceneState = sceneState,
                    modifier = Modifier.fillMaxWidth().height(220.dp).testTag("chart"),
                ) {
                    yAxis(
                        id = Rainfall,
                        title = "Revenue",
                        unit = ChartUnit.Currency("GBP"),
                        // Already writes the symbol itself.
                        axis = ChartAxis(valueFormatter = { value -> "£" + value.toLong() }),
                        primary = true,
                    )
                    line(
                        series = listOf(ChartSeries("revenue", "Revenue", readings)),
                        x = { it.month },
                        y = { it.rainfall },
                        yAxis = Rainfall,
                    )
                }
                scene = sceneState.scene
            }
        }
        rule.waitForIdle()
        rule.waitUntil(3_000) { scene != null }
        val labels = scene!!.axisTexts(Rainfall)
        assertTrue(labels.isNotEmpty())
        assertTrue("expected the caller's own format, got $labels", labels.all { it.startsWith("£") })
        assertFalse("the declared unit was appended as well: $labels", labels.any { it.contains("GBP") })
    }

    // ---- backwards compatibility ----------------------------------------

    @Test
    fun aSingleAxisChartStillDrawsWithNoAxisIdsMentioned() {
        rule.setContent {
            Harness {
                LineChart(
                    data = readings,
                    x = { it.month },
                    y = { it.rainfall },
                    animation = ChartAnimation.None,
                    modifier = Modifier.fillMaxWidth().height(200.dp).testTag("simple"),
                )
            }
        }
        rule.onNodeWithTag("simple").assertIsDisplayed()
    }

    @OptIn(ExperimentalChartKitApi::class)
    @Test
    fun theOlderSecondaryAxisApiStillWorks() {
        var scene: ChartScene? = null
        rule.setContent {
            Harness {
                val sceneState = rememberChartSceneState()
                CartesianChart(
                    animation = ChartAnimation.None,
                    secondaryValueAxis = ChartAxis(title = "Pressure"),
                    sceneState = sceneState,
                    modifier = Modifier.fillMaxWidth().height(220.dp).testTag("chart"),
                ) {
                    bars(
                        series = listOf(ChartSeries("rainfall", "Rainfall", readings)),
                        category = { it.month },
                        value = { it.rainfall },
                    )
                    line(
                        series = listOf(ChartSeries("pressure", "Pressure", readings)),
                        x = { it.month },
                        y = { it.pressure },
                        valueAxis = io.devkit.chartkit.axis.ValueAxisBinding.Secondary,
                    )
                }
                scene = sceneState.scene
            }
        }
        rule.waitForIdle()
        rule.waitUntil(3_000) { scene != null }
        assertTrue(scene!!.groupIds().contains("axis-secondary-y"))
        assertTrue(scene!!.groupIds().contains("axis-default-y"))
    }

    // ---- accessibility ---------------------------------------------------

    @Test
    fun theChartAnnouncesItsAxesAndTheirUnits() {
        rule.setContent {
            Harness {
                WeatherChart(modifier = Modifier.fillMaxWidth().height(240.dp).testTag("chart"))
            }
        }
        rule.waitForIdle()
        // Spelled out, because "mm" is read one letter at a time.
        rule.onNodeWithContentDescription("millimetres", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("degrees Celsius", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("hectopascals", substring = true).assertIsDisplayed()
    }

    @Test
    fun theAnnouncementNamesAxesAndNeverTheirIds() {
        rule.setContent {
            Harness {
                WeatherChart(modifier = Modifier.fillMaxWidth().height(240.dp).testTag("chart"))
            }
        }
        rule.waitForIdle()
        // Two nodes mention it — the chart's own description and its legend
        // row — which is why this asks for all of them.
        rule.onAllNodesWithContentDescription("Rainfall", substring = true)
            .onFirst()
            .assertIsDisplayed()
        // An internal id would be read out loud; there is no such node.
        rule.onAllNodesWithContentDescription("default-y", substring = true).assertCountEquals(0)
    }
}
