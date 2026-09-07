package io.devkit.chartkit.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.devkit.chartkit.geometry.ChartMath

/**
 * How a chart animates.
 *
 * One configuration object shared by every chart type. Animation is the easiest
 * thing in a charting library to end up implementing once per chart, with four
 * slightly different durations; here the fractions are produced centrally and
 * layers only read them.
 *
 * @param enabled off switch. Compose previews, screenshot tests and readers who
 *   have asked the system to reduce motion want the final frame immediately.
 * @param durationMillis how long the initial reveal takes.
 * @param dataChangeDurationMillis how long a value change takes. Shorter than
 *   the reveal: the reader already has context and is watching a number move,
 *   not meeting the chart.
 * @param easing the curve. Material's standard easing by default.
 */
@Immutable
data class ChartAnimation(
    val enabled: Boolean = true,
    val durationMillis: Int = DEFAULT_DURATION_MILLIS,
    val dataChangeDurationMillis: Int = DEFAULT_DATA_CHANGE_MILLIS,
    val easing: Easing = FastOutSlowInEasing,
) {
    init {
        require(durationMillis >= 0) {
            "Animation duration cannot be negative, was $durationMillis"
        }
        require(dataChangeDurationMillis >= 0) {
            "Animation duration cannot be negative, was $dataChangeDurationMillis"
        }
    }

    internal fun revealSpec(): AnimationSpec<Float> =
        tween(durationMillis = durationMillis, easing = easing)

    internal fun dataChangeSpec(): AnimationSpec<Float> =
        tween(durationMillis = dataChangeDurationMillis, easing = easing)

    companion object {

        /** Long enough to read as motion, short enough not to delay the data. */
        const val DEFAULT_DURATION_MILLIS: Int = 450

        const val DEFAULT_DATA_CHANGE_MILLIS: Int = 300

        val Default: ChartAnimation = ChartAnimation()

        /** No motion. What previews and deterministic tests use. */
        val None: ChartAnimation = ChartAnimation(enabled = false)
    }
}

/**
 * The `0..1` reveal fraction: `0` at first composition, `1` once drawn in.
 *
 * Layers scale their geometry by it, so bars grow from the baseline and lines
 * draw in. It runs once and is not reset by a data change — see
 * [rememberAnimatedSeriesValues] for why that is a different animation.
 */
@Composable
internal fun rememberChartReveal(animation: ChartAnimation): Float {
    if (!animation.enabled) return 1f
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val reveal by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = animation.revealSpec(),
        label = "ChartKit reveal",
    )
    return reveal
}

/**
 * Interpolates series values towards [target] when the data changes.
 *
 * ### Why the values animate and the reveal does not restart
 *
 * A bar whose value went from 40 to 60 should travel to 60. Restarting the
 * reveal instead would collapse it to the axis and regrow it, which reads as
 * "the chart reloaded" rather than "the number changed" — and on a live
 * dashboard updating every few seconds it never settles.
 *
 * ### Compatible changes only
 *
 * Interpolation needs a correspondence between the old values and the new ones.
 * ChartKit establishes it from the **series ids** and the point counts: same
 * ids, same counts, and each value moves to its new position. Anything else —
 * a series added, a series removed, a different number of points — is a change
 * of shape, and there is no honest correspondence to animate along. Those snap.
 *
 * Matching on ids rather than list position is what makes this survive a
 * reordered legend: `[revenue, expenses]` becoming `[expenses, revenue]`
 * animates nothing, because nothing moved.
 *
 * @param target the values to move towards, indexed `[series][point]`.
 * @param seriesIds parallel to [target]; identifies each series across changes.
 */
@Composable
internal fun rememberAnimatedSeriesValues(
    target: List<List<Double?>>,
    seriesIds: List<String>,
    animation: ChartAnimation,
): List<List<Double?>> {
    if (!animation.enabled) return target

    val shape = remember(seriesIds, target) { ValueShape(seriesIds, target.map { it.size }) }
    val progress = remember { Animatable(1f) }
    val from = remember { mutableStateOf(target) }
    val to = remember { mutableStateOf(target) }
    val lastShape = remember { mutableStateOf(shape) }
    var initialised by remember { mutableStateOf(false) }

    LaunchedEffect(target) {
        if (!initialised) {
            initialised = true
            from.value = target
            to.value = target
            lastShape.value = shape
            return@LaunchedEffect
        }
        if (lastShape.value != shape) {
            // A change of shape: no correspondence to interpolate along.
            from.value = target
            to.value = target
            lastShape.value = shape
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        // Interrupting an in-flight transition: restart from what is currently
        // on screen, not from where the previous one began, so a stream of
        // updates moves continuously instead of jumping back each time.
        from.value = lerpValues(from.value, to.value, progress.value)
        to.value = target
        progress.snapTo(0f)
        progress.animateTo(1f, animation.dataChangeSpec())
    }

    val fraction = progress.value
    return if (fraction >= 1f) to.value else lerpValues(from.value, to.value, fraction)
}

/** Identifies a dataset's *shape*, which is what decides if a change can animate. */
private data class ValueShape(val seriesIds: List<String>, val pointCounts: List<Int>)

/**
 * Element-wise interpolation.
 *
 * A value that is missing at either end does not interpolate — there is no
 * position between "absent" and 40 — so it takes the target directly. The point
 * appears or disappears rather than sliding in from nowhere.
 */
private fun lerpValues(
    from: List<List<Double?>>,
    to: List<List<Double?>>,
    fraction: Float,
): List<List<Double?>> = to.mapIndexed { seriesIndex, series ->
    val previousSeries = from.getOrNull(seriesIndex)
    series.mapIndexed { pointIndex, value ->
        val previous = previousSeries?.getOrNull(pointIndex)
        when {
            value == null || previous == null -> value
            else -> ChartMath.lerp(previous, value, fraction.toDouble())
        }
    }
}

/**
 * A `0..1` fraction that eases towards `1` while [selected] is true.
 *
 * Used for the emphasis on a selected point or bar, so selection has a
 * transition of its own rather than snapping.
 */
@Composable
internal fun rememberSelectionProgress(selected: Boolean, animation: ChartAnimation): Float {
    if (!animation.enabled) return if (selected) 1f else 0f
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(
            durationMillis = SELECTION_DURATION_MILLIS,
            easing = animation.easing,
        ),
        label = "ChartKit selection",
    )
    return progress
}

/** Selection has to feel immediate; anything slower reads as lag, not motion. */
private const val SELECTION_DURATION_MILLIS: Int = 140
