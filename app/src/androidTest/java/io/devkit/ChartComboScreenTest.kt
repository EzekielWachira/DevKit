package io.devkit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.devkit.chartdemo.ChartComboScreen
import io.devkit.chartdemo.ComboDemo
import org.junit.Rule
import org.junit.Test

/**
 * Every multi-axis demo composes, with ticks independent and aligned.
 *
 * The sample is where the reference case is reproduced, so this is where it is
 * checked. Both alignment modes are exercised because aligned mode rewrites
 * every axis' domain, and an alignment that produced an empty or inverted
 * interval would draw nothing rather than throwing.
 */
class ChartComboScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyDemoComposes() {
        rule.setContent { MaterialTheme { Surface { ChartComboScreen() } } }

        ComboDemo.entries.forEach { demo ->
            rule.onNodeWithTag("combo-demo-${demo.name}").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("combo-readout").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun everyDemoComposesWithAlignedTicks() {
        rule.setContent { MaterialTheme { Surface { ChartComboScreen() } } }

        rule.onNodeWithTag("combo-aligned").performScrollTo().performClick()
        ComboDemo.entries.forEach { demo ->
            rule.onNodeWithTag("combo-demo-${demo.name}").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("combo-readout").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun theWeatherDemoPublishesADataTableWithUnits() {
        rule.setContent { MaterialTheme { Surface { ChartComboScreen() } } }

        rule.onNodeWithTag("combo-demo-Weather").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("combo-table").performScrollTo().assertIsDisplayed()
    }
}
