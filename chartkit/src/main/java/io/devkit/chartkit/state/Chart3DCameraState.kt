package io.devkit.chartkit.state

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DCameraLimits

/**
 * Where the reader is standing, hoisted.
 *
 * ```kotlin
 * val camera = rememberChart3DCameraState(rotationX = 15.0, rotationY = 20.0)
 *
 * ColumnChart3D(data = sales, category = { it.month }, value = { it.total }, cameraState = camera)
 * TextButton(onClick = { camera.reset() }) { Text("Reset view") }
 * ```
 *
 * Hoisted for the same reason the viewport is: the view is a property of the
 * *reader's* session, not of the data, and an application that wants to reset
 * it, animate it, restore it across a rotation or drive it from a slider needs
 * a handle on it. A chart given none creates its own, so a fixed 3D chart stays
 * a one-line call.
 *
 * ### Camera changes never touch the data
 *
 * Moving the camera rebuilds the projection and nothing else. The stack layout,
 * the domains, the scales and the world geometry are all keyed on the data and
 * the plot size, so a drag-to-rotate does not re-run the stack engine even
 * once. See [io.devkit.chartkit.three.Chart3DProjector].
 */
@Stable
class Chart3DCameraState internal constructor(
    initial: Chart3DCamera,
    limits: Chart3DCameraLimits,
) {
    /** What the camera started at, and what [reset] returns to, exactly. */
    val initialCamera: Chart3DCamera = initial.coerceIn(limits)

    /** How far the camera may be moved, by a gesture or by a caller. */
    var limits: Chart3DCameraLimits by mutableStateOf(limits)

    private var currentCamera: Chart3DCamera by mutableStateOf(initialCamera)

    /**
     * The current view.
     *
     * Settable, and clamped on the way in. A caller assigning an unclamped
     * camera would be able to put the reader under the floor — legal geometry,
     * unreadable chart, and no gesture that gets back out of it. Reading it
     * inside a composition subscribes to it, so a chart redraws when a slider
     * moves the camera and does not when anything else changes.
     */
    var camera: Chart3DCamera
        get() = currentCamera
        set(value) {
            currentCamera = value.coerceIn(limits)
        }

    /**
     * Turns the camera to the given angles, clamped.
     *
     * The absolute counterpart to [rotateBy], and what a slider drives: a
     * control that reports a position has to be able to *state* it, and
     * expressing that as a delta from whatever the camera currently is means
     * the two drift apart the first time anything else moves the camera.
     *
     * A `null` leaves that angle alone, so turning the chart from a single
     * horizontal slider does not silently level its pitch.
     */
    fun rotateTo(rotationX: Double? = null, rotationY: Double? = null) {
        val now = camera
        camera = now.copy(
            rotationX = rotationX ?: now.rotationX,
            rotationY = rotationY ?: now.rotationY,
        )
    }

    /** Turns the camera by the given deltas, clamped. */
    fun rotateBy(deltaX: Double, deltaY: Double) {
        val now = camera
        camera = now.copy(
            rotationX = now.rotationX + deltaX,
            rotationY = now.rotationY + deltaY,
        )
    }

    /**
     * Moves the camera closer or further by a multiplicative [factor].
     *
     * Multiplicative because perspective is: halving the distance doubles the
     * apparent size whether the camera started near or far, whereas subtracting
     * a fixed amount does nothing at one end of the range and turns the chart
     * inside out at the other.
     */
    fun zoomBy(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        camera = camera.copy(distance = camera.distance / factor)
    }

    /**
     * Moves the camera to an absolute [distance] in scene widths, clamped.
     *
     * Absolute where [zoomBy] is multiplicative, for the same reason [rotateTo]
     * exists beside [rotateBy]: a pinch is a ratio and a slider is a position.
     */
    fun zoomTo(distance: Double) {
        if (!distance.isFinite() || distance <= 0.0) return
        camera = camera.copy(distance = distance)
    }

    /** Back to exactly the camera this state was created with. */
    fun reset() {
        camera = initialCamera
    }

    /**
     * Moves smoothly to [target].
     *
     * All three quantities are animated together on one clock, so the path is a
     * single continuous move rather than a rotation that finishes before the
     * dolly does. Suspending, so a caller composes it with their own
     * cancellation rather than ChartKit inventing a scope.
     */
    suspend fun animateTo(
        target: Chart3DCamera,
        animationSpec: AnimationSpec<Float> = tween(durationMillis = DEFAULT_DURATION_MS),
    ) {
        val from = camera
        val to = target.coerceIn(limits)
        val progress = Animatable(0f)
        progress.animateTo(1f, animationSpec) {
            val t = value.toDouble()
            camera = from.copy(
                rotationX = from.rotationX + (to.rotationX - from.rotationX) * t,
                rotationY = from.rotationY + (to.rotationY - from.rotationY) * t,
                rotationZ = from.rotationZ + (to.rotationZ - from.rotationZ) * t,
                distance = from.distance + (to.distance - from.distance) * t,
                target = to.target ?: from.target,
            )
        }
        camera = to
    }

    internal companion object {
        const val DEFAULT_DURATION_MS = 420
    }
}

/**
 * A camera state for a 3D chart.
 *
 * @param rotationX degrees of pitch: positive tips the tops of the columns
 *   toward the reader's view.
 * @param rotationY degrees of yaw: positive brings the right-hand side forward.
 * @param distance camera distance in scene widths. Smaller is a stronger
 *   perspective.
 */
@Composable
fun rememberChart3DCameraState(
    rotationX: Double = Chart3DCamera.DEFAULT_ROTATION_X,
    rotationY: Double = Chart3DCamera.DEFAULT_ROTATION_Y,
    distance: Double = Chart3DCamera.DEFAULT_DISTANCE,
    limits: Chart3DCameraLimits = Chart3DCameraLimits.Default,
): Chart3DCameraState = remember(rotationX, rotationY, distance, limits) {
    Chart3DCameraState(
        initial = Chart3DCamera(
            rotationX = rotationX,
            rotationY = rotationY,
            distance = distance,
        ),
        limits = limits,
    )
}

/** A camera state built from a whole [Chart3DCamera], including a preset. */
@Composable
fun rememberChart3DCameraState(
    camera: Chart3DCamera,
    limits: Chart3DCameraLimits = Chart3DCameraLimits.Default,
): Chart3DCameraState = remember(camera, limits) {
    Chart3DCameraState(initial = camera, limits = limits)
}

/**
 * A camera state that survives configuration changes.
 *
 * Worth reaching for once a chart is interactively rotatable: a reader who has
 * turned a chart to see the back row and then rotates their device has not
 * asked to go back to the default view.
 */
@Composable
fun rememberSaveableChart3DCameraState(
    camera: Chart3DCamera = Chart3DCamera.Default,
    limits: Chart3DCameraLimits = Chart3DCameraLimits.Default,
): Chart3DCameraState = rememberSaveable(
    camera,
    limits,
    saver = listSaver(
        save = { listOf(it.camera.rotationX, it.camera.rotationY, it.camera.distance) },
        restore = { values ->
            Chart3DCameraState(
                initial = camera.copy(
                    rotationX = values[0],
                    rotationY = values[1],
                    distance = values[2],
                ),
                limits = limits,
            )
        },
    ),
) {
    Chart3DCameraState(initial = camera, limits = limits)
}
