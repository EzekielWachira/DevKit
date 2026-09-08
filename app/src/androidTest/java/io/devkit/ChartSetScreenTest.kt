package io.devkit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.devkit.chartdemo.ChartSetScreen
import io.devkit.chartdemo.SetDemo
import org.junit.Rule
import org.junit.Test

/**
 * Every set-diagram demo composes.
 *
 * The sample is where the reference cases are reproduced, so this is where they
 * are checked. The datasets are all strictly validated, which means an
 * inconsistent number in [io.devkit.chartdemo.SetDemoData] fails here with the
 * offending combination named rather than shipping a diagram nobody looked at.
 */
class ChartSetScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyDemoComposes() {
        rule.setContent { MaterialTheme { Surface { ChartSetScreen() } } }

        SetDemo.entries.forEach { demo ->
            rule.onNodeWithTag("set-demo-${demo.name}").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("set-readout").assertIsDisplayed()
        }
    }
}
