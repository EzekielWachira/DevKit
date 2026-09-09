package io.devkit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.devkit.chartdemo.Chart3DScreen
import io.devkit.chartdemo.ThreeDDemo
import org.junit.Rule
import org.junit.Test

/**
 * Every 3D demo composes, under both projections.
 *
 * The sample is where the reference arrangement is reproduced, so this is where
 * it is checked. Both projections are exercised because they are two different
 * paths through the fit and the culling — a parallel projection culls against a
 * fixed view direction and a perspective one against the eye — and a chart that
 * fitted to nothing would draw an empty frame rather than throwing.
 */
class Chart3DScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyDemoComposes() {
        rule.setContent { MaterialTheme { Surface { Chart3DScreen() } } }

        ThreeDDemo.entries.forEach { demo ->
            rule.onNodeWithTag("three-d-demo-${demo.name}").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("three-d-chart").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun everyDemoComposesUnderOrthographicProjection() {
        rule.setContent { MaterialTheme { Surface { Chart3DScreen() } } }

        rule.onNodeWithTag("three-d-orthographic").performScrollTo().performClick()
        ThreeDDemo.entries.forEach { demo ->
            rule.onNodeWithTag("three-d-demo-${demo.name}").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("three-d-chart").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun theCameraControlsAndTheDataTableAreReachable() {
        rule.setContent { MaterialTheme { Surface { Chart3DScreen() } } }

        rule.onNodeWithTag("three-d-slider-Pitch").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("three-d-slider-Yaw").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("three-d-reset").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("three-d-table-toggle").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("three-d-table").performScrollTo().assertIsDisplayed()
    }
}
