package io.devkit.fillkit.debug.overlay

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.devkit.fillkit.FieldOverlayPlacement
import kotlin.math.roundToInt

/** Where the overlay ended up, after the IME and the container were taken into account. */
enum class ResolvedOverlayPlacement { Below, Above, AboveIme }

data class FieldOverlayLayout(
    val placement: ResolvedOverlayPlacement,
    val x: Int,
    val y: Int,
    /** Space the bar may occupy. It sizes to content within this, except when [fillWidth]. */
    val maxWidth: Int,
    /** Keyboard-accessory bars span the container; anchored popovers wrap their content. */
    val fillWidth: Boolean,
)

/**
 * Pure placement maths for the field overlay.
 *
 * Kept free of Compose UI so the rules — anchored below, flipped above, or
 * promoted to a keyboard-accessory bar — can be unit tested directly. All
 * coordinates are relative to the FillKit host, and the IME is expressed as a
 * live inset rather than an assumed keyboard height.
 */
object FieldOverlayPositioner {

    const val GAP = 8
    const val MARGIN = 12

    /**
     * A lazy list keeps the focused item composed even after it scrolls away, so
     * registration alone is not enough to know the field is on screen. An
     * off-screen or collapsed field gets no overlay rather than a stale one.
     */
    fun isFieldVisible(field: Rect, container: IntSize, imeInset: Int = 0): Boolean {
        if (field.width <= 0f || field.height <= 0f) return false
        if (container.width <= 0 || container.height <= 0) return false
        val visibleBottom = (container.height - imeInset).toFloat()
        return field.bottom > 0f && field.top < visibleBottom &&
            field.right > 0f && field.left < container.width.toFloat()
    }

    fun place(
        field: Rect,
        container: IntSize,
        overlay: IntSize,
        imeInset: Int = 0,
        requested: FieldOverlayPlacement = FieldOverlayPlacement.Auto,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        gap: Int = GAP,
        margin: Int = MARGIN,
    ): FieldOverlayLayout {
        val visibleBottom = (container.height - imeInset).coerceAtLeast(0)
        val fitsBelow = field.bottom + gap + overlay.height <= visibleBottom - margin
        val fitsAbove = field.top - gap - overlay.height >= margin

        val placement = when (requested) {
            FieldOverlayPlacement.AboveIme -> ResolvedOverlayPlacement.AboveIme
            // Field mode stays anchored as long as either side fits.
            FieldOverlayPlacement.Field -> when {
                fitsBelow -> ResolvedOverlayPlacement.Below
                fitsAbove -> ResolvedOverlayPlacement.Above
                else -> ResolvedOverlayPlacement.AboveIme
            }
            // Auto: anchored while the field is safely clear of the keyboard,
            // otherwise an accessory bar rather than a popover crowding the IME.
            FieldOverlayPlacement.Auto -> when {
                fitsBelow -> ResolvedOverlayPlacement.Below
                imeInset > 0 -> ResolvedOverlayPlacement.AboveIme
                fitsAbove -> ResolvedOverlayPlacement.Above
                else -> ResolvedOverlayPlacement.AboveIme
            }
        }

        val maxWidth = (container.width - margin * 2).coerceAtLeast(0)
        if (placement == ResolvedOverlayPlacement.AboveIme) {
            val y = (visibleBottom - overlay.height - margin).coerceAtLeast(margin)
            return FieldOverlayLayout(placement, margin, y, maxWidth, fillWidth = true)
        }

        val y = when (placement) {
            ResolvedOverlayPlacement.Below -> (field.bottom + gap).roundToInt()
            else -> (field.top - gap).roundToInt() - overlay.height
        }
        // The bar has not been measured on the first frame; anchor from the
        // field and let the clamp tighten once a real width arrives.
        val measured = overlay.width.coerceAtMost(maxWidth)
        val preferredX = when (layoutDirection) {
            LayoutDirection.Ltr -> field.left.roundToInt()
            LayoutDirection.Rtl -> field.right.roundToInt() - measured
        }
        val maxX = (container.width - measured - margin).coerceAtLeast(margin)
        return FieldOverlayLayout(
            placement = placement,
            x = preferredX.coerceIn(margin, maxX),
            y = y.coerceAtLeast(margin),
            maxWidth = maxWidth,
            fillWidth = false,
        )
    }
}
