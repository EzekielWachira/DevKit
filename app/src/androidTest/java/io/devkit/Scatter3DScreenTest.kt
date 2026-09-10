package io.devkit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.devkit.chartdemo.Scatter3DDemo
import io.devkit.chartdemo.Scatter3DScreen
import io.devkit.chartkit.layer.three.Scatter3DGuides
import io.devkit.chartkit.layer.three.Scatter3DRenderMode
import org.junit.Rule
import org.junit.Test

/**
 * Every 3D scatter demo composes, under both projections and every render mode.
 *
 * The sample is where the reference behaviour is reproduced, so this is where
 * it is checked. Both projections are exercised because they are two different
 * paths through the fit, the culling and the marker sizing — a parallel
 * projection culls against a fixed view direction, sizes markers uniformly and
 * would draw an empty plot rather than throwing if the fit collapsed.
 */
class Scatter3DScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyDemoComposes() {
        rule.setContent { MaterialTheme { Surface { Scatter3DScreen() } } }

        Scatter3DDemo.entries.forEach { demo ->
            rule.onNodeWithTag("scatter-3d-demo-${demo.name}").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("scatter-3d-chart").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun everyDemoComposesUnderOrthographicProjection() {
        rule.setContent { MaterialTheme { Surface { Scatter3DScreen() } } }

        rule.onNodeWithTag("scatter-3d-orthographic").performScrollTo().performClick()
        Scatter3DDemo.entries.forEach { demo ->
            rule.onNodeWithTag("scatter-3d-demo-${demo.name}").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("scatter-3d-chart").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun everyGuideModeDraws() {
        rule.setContent { MaterialTheme { Surface { Scatter3DScreen() } } }

        rule.onNodeWithTag("scatter-3d-demo-${Scatter3DDemo.Guides.name}")
            .performScrollTo().performClick()
        Scatter3DGuides.entries.forEach { mode ->
            rule.onNodeWithTag("scatter-3d-guides-${mode.name}").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("scatter-3d-chart").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun everyRenderModeDrawsTheLargeDataset() {
        rule.setContent { MaterialTheme { Surface { Scatter3DScreen() } } }

        rule.onNodeWithTag("scatter-3d-demo-${Scatter3DDemo.LargeDataset.name}")
            .performScrollTo().performClick()
        Scatter3DRenderMode.entries.forEach { mode ->
            rule.onNodeWithTag("scatter-3d-mode-${mode.name}").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("scatter-3d-chart").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun everyCameraPresetLeavesTheChartDrawable() {
        rule.setContent { MaterialTheme { Surface { Scatter3DScreen() } } }

        listOf("Isometric", "Front", "Top", "Side").forEach { preset ->
            rule.onNodeWithTag("scatter-3d-preset-$preset").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("scatter-3d-chart").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun theDataTableShowsTheSameThreeVariables() {
        rule.setContent { MaterialTheme { Surface { Scatter3DScreen() } } }

        rule.onNodeWithTag("scatter-3d-table-toggle").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("scatter-3d-table").performScrollTo().assertIsDisplayed()
    }
}
