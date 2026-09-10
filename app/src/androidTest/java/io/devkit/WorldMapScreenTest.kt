package io.devkit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.devkit.chartdemo.MapDemo
import io.devkit.chartdemo.WorldMapScreen
import io.devkit.chartkit.layer.geo.GeoLabels
import org.junit.Rule
import org.junit.Test

/**
 * Every world-map demo composes and stays composable as its controls change.
 *
 * The sample is where the geographic behaviour is demonstrated, so this is where
 * it is checked. It walks each demo rather than spot-checking one, because the
 * twelve differ in which layers exist, which formats were parsed and which
 * projection is in force — and a chart that draws nothing is indistinguishable
 * from a chart that draws correctly unless something asserts otherwise.
 */
class WorldMapScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyDemoComposes() {
        rule.setContent { MaterialTheme { Surface { WorldMapScreen() } } }

        MapDemo.entries.forEach { demo ->
            rule.onNodeWithTag("map-demo-${demo.tag}").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("map-chart").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("map-explanation").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun everyProjectionComposes() {
        rule.setContent { MaterialTheme { Surface { WorldMapScreen() } } }

        rule.onNodeWithTag("map-demo-projections").performScrollTo().performClick()
        rule.waitForIdle()

        listOf("Equal-Earth", "Mercator", "Equirectangular").forEach { name ->
            rule.onNodeWithTag("map-projection-$name").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("map-chart").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun everyLabelPolicyComposes() {
        rule.setContent { MaterialTheme { Surface { WorldMapScreen() } } }

        GeoLabels.entries.forEach { policy ->
            rule.onNodeWithTag("map-labels-${policy.name}").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("map-chart").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun theCameraControlsSurviveEveryDemo() {
        rule.setContent { MaterialTheme { Surface { WorldMapScreen() } } }

        MapDemo.entries.forEach { demo ->
            rule.onNodeWithTag("map-demo-${demo.tag}").performScrollTo().performClick()
            rule.waitForIdle()
            // Zoom in, then focus a region that straddles the antimeridian —
            // the case whose bounding box cannot express the wrap, and which
            // must therefore still leave a drawable chart behind.
            rule.onNodeWithTag("map-zoom-in").performScrollTo().performClick()
            rule.onNodeWithTag("map-focus-fiji").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("map-chart").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("map-fit").performScrollTo().performClick()
            rule.waitForIdle()
        }
    }

    @Test
    fun theThematicDemoReportsItsJoin() {
        rule.setContent { MaterialTheme { Surface { WorldMapScreen() } } }

        rule.onNodeWithTag("map-demo-thematic").performScrollTo().performClick()
        rule.waitForIdle()

        // The diagnostic a caller actually needs when a map comes out blank.
        rule.onNodeWithTag("map-join").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theAccessibleDemoOffersItsTable() {
        rule.setContent { MaterialTheme { Surface { WorldMapScreen() } } }

        rule.onNodeWithTag("map-demo-accessible").performScrollTo().performClick()
        rule.waitForIdle()

        // For a map the table is not a fallback — it is the chart.
        rule.onNodeWithTag("map-table").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theBubbleDemoOffersItsTable() {
        rule.setContent { MaterialTheme { Surface { WorldMapScreen() } } }

        rule.onNodeWithTag("map-demo-bubbles").performScrollTo().performClick()
        rule.waitForIdle()

        // A bubble encodes its number as an area, which a reader who cannot see
        // the map has no access to at all.
        rule.onNodeWithTag("map-point-table").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun everyDemoComposesInDarkMode() {
        rule.setContent { MaterialTheme { Surface { WorldMapScreen() } } }

        rule.onNodeWithTag("map-dark").performScrollTo().performClick()
        rule.waitForIdle()

        // Boundaries, the "no data" fill, labels, the selection outline, the
        // legend and the tooltip all have to survive the swap — and they have
        // to survive it on every demo, not just the one the toggle was flipped
        // on.
        MapDemo.entries.forEach { demo ->
            rule.onNodeWithTag("map-demo-${demo.tag}").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("map-chart").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun theAttributionIsAlwaysShown() {
        rule.setContent { MaterialTheme { Surface { WorldMapScreen() } } }

        // The geometry is somebody else's work and says so, on screen, not only
        // in a comment.
        rule.onNodeWithTag("map-attribution").performScrollTo().assertIsDisplayed()
    }
}
