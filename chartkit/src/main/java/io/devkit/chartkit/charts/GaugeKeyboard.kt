package io.devkit.chartkit.charts

import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import io.devkit.chartkit.gauge.GaugeScale

/**
 * Arrow-key control of an adjustable dial.
 *
 * ### Why a keyboard at all
 *
 * A gauge that can only be dragged is a control that a keyboard user, a switch
 * user and anyone on a desktop with no touchscreen cannot operate. The screen
 * reader's own increase and decrease actions come from the
 * [androidx.compose.ui.semantics.ProgressBarRangeInfo] the dial publishes; this
 * is the other half — the same steps, driven from the keys a slider responds to
 * everywhere else.
 *
 * ```text
 * ← ↓   decrease by one step
 * → ↑   increase by one step
 * Home  minimum
 * End   maximum
 * ```
 *
 * Both directions are offered for each action because a dial has no single
 * orientation: on a semicircular gauge "up" is toward the middle of the range
 * and on a full circle it is the start, so binding only the horizontal pair
 * would be right on one dial and wrong on the next.
 *
 * Applied only to an interactive gauge — [focusable] on a display would put a
 * stop in the tab order for something with nothing to operate.
 */
internal fun Modifier.gaugeKeyboard(
    scale: GaugeScale,
    current: Double,
    step: Double,
    onValueChange: (Double) -> Unit,
): Modifier = this
    .focusable()
    .onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val next = when (event.key) {
            Key.DirectionRight, Key.DirectionUp -> current + step
            Key.DirectionLeft, Key.DirectionDown -> current - step
            Key.MoveHome -> scale.min
            Key.MoveEnd -> scale.max
            else -> return@onKeyEvent false
        }
        onValueChange(next.coerceIn(scale.min, scale.max))
        true
    }
