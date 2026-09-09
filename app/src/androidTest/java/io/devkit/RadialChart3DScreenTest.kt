package io.devkit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.devkit.chartdemo.RadialChart3DScreen
import io.devkit.chartdemo.RadialDemo
import io.devkit.chartdemo.RadialDemoTestTags
import org.junit.Rule
import org.junit.Test

/**
 * Every 3D pie and donut demo composes, under both projections.
 *
 * The sample is where the reference charts are reproduced, so this is where
 * they are checked. Both projections are exercised because they are two
 * different paths through the fit and the culling — a parallel projection culls
 * against a fixed view direction and a perspective one against the eye — and a
 * chart that fitted to nothing would draw an empty plot rather than throwing.
 */
class RadialChart3DScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyDemoComposes() {
        rule.setContent { MaterialTheme { Surface { RadialChart3DScreen() } } }

        RadialDemo.entries.forEach { demo ->
            rule.onNodeWithTag(RadialDemoTestTags.demo(demo.name)).performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag(RadialDemoTestTags.Chart).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun everyDemoComposesUnderOrthographicProjection() {
        rule.setContent { MaterialTheme { Surface { RadialChart3DScreen() } } }

        rule.onNodeWithTag(RadialDemoTestTags.Orthographic).performScrollTo().performClick()
        RadialDemo.entries.forEach { demo ->
            rule.onNodeWithTag(RadialDemoTestTags.demo(demo.name)).performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag(RadialDemoTestTags.Chart).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun theDonutCentreContentIsShown() {
        rule.setContent { MaterialTheme { Surface { RadialChart3DScreen() } } }

        rule.onNodeWithTag(RadialDemoTestTags.demo(RadialDemo.CenterContent.name))
            .performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(RadialDemoTestTags.Center, useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theCameraControlsAndTheDataTableAreReachable() {
        rule.setContent { MaterialTheme { Surface { RadialChart3DScreen() } } }

        rule.onNodeWithTag(RadialDemoTestTags.slider("Pitch")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(RadialDemoTestTags.slider("Yaw")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(RadialDemoTestTags.slider("Depth")).performScrollTo().assertIsDisplayed()

        rule.onNodeWithTag(RadialDemoTestTags.Table).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(RadialDemoTestTags.TableView).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theCameraCanBeResetAndAnimated() {
        rule.setContent { MaterialTheme { Surface { RadialChart3DScreen() } } }

        rule.onNodeWithTag(RadialDemoTestTags.demo(RadialDemo.CameraRotation.name))
            .performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(RadialDemoTestTags.Animate).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(RadialDemoTestTags.Reset).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(RadialDemoTestTags.Chart).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theDataUpdateDemoSwapsItsDataset() {
        rule.setContent { MaterialTheme { Surface { RadialChart3DScreen() } } }

        rule.onNodeWithTag(RadialDemoTestTags.demo(RadialDemo.DataUpdates.name))
            .performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(RadialDemoTestTags.Swap).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(RadialDemoTestTags.Chart).performScrollTo().assertIsDisplayed()
    }
}
