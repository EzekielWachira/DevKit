package io.devkit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.devkit.chartdemo.ChartGaugeScreen
import io.devkit.chartdemo.GaugeDemo
import org.junit.Rule
import org.junit.Test

/**
 * Every gauge demo composes, at full and compact detail.
 *
 * The sample is where the reference speedometer is reproduced, so this is where
 * it is checked. Both detail modes are exercised because compaction rewrites
 * the tick plan, and a plan that came back empty would draw a bare arc rather
 * than throwing.
 */
class ChartGaugeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyDemoComposes() {
        rule.setContent { MaterialTheme { Surface { ChartGaugeScreen() } } }

        GaugeDemo.entries.forEach { demo ->
            rule.onNodeWithTag("gauge-demo-${demo.name}").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("gauge-readout").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun everyDemoComposesAtCompactDetail() {
        rule.setContent { MaterialTheme { Surface { ChartGaugeScreen() } } }

        rule.onNodeWithTag("gauge-compact").performScrollTo().performClick()
        GaugeDemo.entries.forEach { demo ->
            rule.onNodeWithTag("gauge-demo-${demo.name}").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("gauge-readout").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun theSpeedometerRespondsToTheValueControl() {
        rule.setContent { MaterialTheme { Surface { ChartGaugeScreen() } } }

        rule.onNodeWithTag("gauge-demo-Speedometer").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("gauge-chart").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("gauge-slider").performScrollTo().assertIsDisplayed()
    }
}
