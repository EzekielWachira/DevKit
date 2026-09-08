package io.devkit.chartkit.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The chart's picture, as data, kept up to date by the chart.
 *
 * ```kotlin
 * val sceneState = rememberChartSceneState()
 *
 * LineChart(data = revenue, x = { it.month }, y = { it.amount }, sceneState = sceneState)
 * Button(onClick = { share(ChartSvg.render(sceneState.scene ?: return@Button)) }) {
 *     Text("Export SVG")
 * }
 * ```
 *
 * ### A parameter, not a modifier
 *
 * [io.devkit.chartkit.capture.chartCapture] is a modifier because a raster
 * capture records *whatever it is applied to* — a chart, a chart with a title
 * around it, a whole dashboard. A scene is different: it can only come from a
 * chart that knows its own geometry, so it is that chart's parameter.
 *
 * ### Completeness is reported
 *
 * [ChartScene.unexportedLayers] names any layer that had no scene
 * representation. Check [ChartScene.isComplete] before writing a file: a vector
 * export missing a series looks like a chart and is not one.
 */
@Stable
class ChartSceneState internal constructor() {

    /** The latest scene, or `null` before the chart's first layout. */
    var scene: ChartScene? by mutableStateOf(null)
        internal set

    /** True once a scene exists and every layer is in it. */
    val isComplete: Boolean get() = scene?.isComplete == true
}

/** Remembers a [ChartSceneState]. */
@Composable
fun rememberChartSceneState(): ChartSceneState = remember { ChartSceneState() }
