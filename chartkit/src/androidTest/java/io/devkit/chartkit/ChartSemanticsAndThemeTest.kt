package io.devkit.chartkit

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartAccessibility
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.BarChart
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.preview.ChartKitPreviewData
import io.devkit.chartkit.theme.ChartDimensions
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.theme.materialDerivedChartColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChartSemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        MaterialTheme { Surface { content() } }
    }

    @Test
    fun aChartAnnouncesItsTitleSeriesAndValues() {
        rule.setContent {
            Host {
                LineChart(
                    data = ChartKitPreviewData.revenue,
                    x = { it.month },
                    y = { it.amount },
                    animation = ChartAnimation.None,
                    accessibility = ChartAccessibility(
                        title = "Monthly revenue",
                        description = "Six months",
                    ),
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
            }
        }
        rule.onNodeWithContentDescription("Monthly revenue", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Jan", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("6 data points", substring = true).assertIsDisplayed()
    }

    @Test
    fun aCustomSummaryReplacesTheGeneratedOne() {
        rule.setContent {
            Host {
                BarChart(
                    data = ChartKitPreviewData.revenue,
                    category = { it.month },
                    value = { it.amount },
                    animation = ChartAnimation.None,
                    accessibilitySummary = { "Revenue is reported quarterly elsewhere" },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
            }
        }
        rule.onNodeWithContentDescription(
            "Revenue is reported quarterly elsewhere",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun anEmptyChartStillAnnouncesItself() {
        rule.setContent {
            Host {
                LineChart(
                    data = emptyList<ChartKitPreviewData.MonthlyValue>(),
                    x = { it.month },
                    y = { it.amount },
                    animation = ChartAnimation.None,
                    accessibility = ChartAccessibility(title = "Revenue"),
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
            }
        }
        rule.onNodeWithContentDescription("Revenue", substring = true).assertIsDisplayed()
    }
}

class ChartThemeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun anExplicitThemeOverridesTheMaterialDefault() {
        var seen: Color? = null
        rule.setContent {
            MaterialTheme {
                val custom = materialDerivedChartColors().copy(
                    palette = listOf(Color.Magenta, Color.Cyan),
                )
                ChartKitTheme(colors = custom) {
                    seen = ChartKitTheme.colors.seriesColor(0)
                }
            }
        }
        assertEquals(Color.Magenta, seen)
    }

    @Test
    fun anInnerThemeInheritsWhatItDoesNotOverride() {
        var palette: List<Color>? = null
        var lineWidth: androidx.compose.ui.unit.Dp? = null
        rule.setContent {
            MaterialTheme {
                val custom = materialDerivedChartColors().copy(palette = listOf(Color.Magenta))
                ChartKitTheme(colors = custom) {
                    ChartKitTheme(dimensions = ChartDimensions(lineWidth = 9.dp)) {
                        palette = ChartKitTheme.colors.palette
                        lineWidth = ChartKitTheme.dimensions.lineWidth
                    }
                }
            }
        }
        assertEquals(listOf(Color.Magenta), palette)
        assertEquals(9.dp, lineWidth)
    }

    @Test
    fun lightAndDarkSchemesProduceDifferentPalettes() {
        var light: List<Color>? = null
        var dark: List<Color>? = null
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                light = materialDerivedChartColors(isDark = false).palette
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                dark = materialDerivedChartColors(isDark = true).palette
            }
        }
        rule.waitForIdle()
        assertNotEquals(light, dark)
        assertTrue(light!!.isNotEmpty() && dark!!.isNotEmpty())
    }

    @Test
    fun noChartColourIsTransparentOrUnspecified() {
        var colors: io.devkit.chartkit.theme.ChartColors? = null
        rule.setContent { MaterialTheme { colors = materialDerivedChartColors() } }
        val resolved = colors!!
        (resolved.palette + resolved.axisLine + resolved.axisLabel + resolved.gridLine).forEach {
            assertNotEquals(Color.Unspecified, it)
            assertTrue("an invisible default is a bug, not a theme", it.alpha > 0f)
        }
    }

    @Test
    fun aThemedChartDrawsInBothSchemes() {
        val dark = mutableStateOf(false)
        rule.setContent {
            MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                Surface {
                    BarChart(
                        data = ChartKitPreviewData.revenue,
                        category = { it.month },
                        value = { it.amount },
                        animation = ChartAnimation.None,
                        modifier = Modifier.testTag("chart").fillMaxWidth().height(200.dp),
                    )
                }
            }
        }
        rule.onNodeWithTag("chart").assertIsDisplayed()
        rule.runOnUiThread { dark.value = true }
        rule.waitForIdle()
        rule.onNodeWithTag("chart").assertIsDisplayed()
    }
}
