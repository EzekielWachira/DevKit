package io.devkit.chartkit.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Generates a multi-series colour palette from one seed colour.
 *
 * ### Why generate rather than hardcode
 *
 * A fixed blue/red/green palette baked into a charting library ignores the
 * application's design system, and looks wrong the moment the app is not blue.
 * Picking `primary, secondary, tertiary, error` out of the Material scheme
 * instead is theme-aware but only yields four colours — and one of them is the
 * error colour, so the fourth series is red for no reason a reader can
 * interpret.
 *
 * So ChartKit rotates the seed's hue. The offsets are chosen to spread the
 * first few series as far apart as possible — 0°, 180°, then the quarters, then
 * the eighths — so a two-series chart gets complements and a five-series chart
 * still has no two adjacent hues. Saturation and lightness are pinned to values
 * that hold their contrast against the surface, and differ between light and
 * dark themes because a colour readable on white is not readable on near-black.
 *
 * Colour is never the only channel: series also differ by legend order, and
 * selection is shown by size and a guide line rather than by opacity alone.
 * A palette cannot make a chart accessible on its own.
 *
 * Pure Kotlin — no Compose, no `android.graphics` — so the arithmetic is
 * testable on the JVM.
 */
internal object ChartPalette {

    /**
     * Hue offsets in degrees, ordered so each new series lands as far from the
     * existing ones as it can.
     */
    private val HUE_OFFSETS = floatArrayOf(
        0f, 180f, 90f, 270f, 45f, 225f, 135f, 315f, 22f, 202f, 112f, 292f,
    )

    /** ARGB colours derived from [seedArgb], [count] of them. */
    fun derive(seedArgb: Int, isDark: Boolean, count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val hsl = rgbToHsl(seedArgb)
        val baseHue = hsl[0]

        // Pinned rather than inherited from the seed: a pastel or near-grey
        // brand colour makes a fine surface tint and an unreadable series line.
        val saturation = if (isDark) 0.62f else 0.68f
        val lightness = if (isDark) 0.66f else 0.46f

        return List(count) { index ->
            val offset = HUE_OFFSETS[index % HUE_OFFSETS.size]
            // Past one full cycle of offsets, nudge lightness so a thirteenth
            // series is not identical to the first.
            val cycle = index / HUE_OFFSETS.size
            val adjustedLightness = (lightness + cycle * (if (isDark) -0.12f else 0.12f))
                .coerceIn(0.2f, 0.85f)
            hslToRgb(
                hue = (baseHue + offset) % 360f,
                saturation = saturation,
                lightness = adjustedLightness,
            )
        }
    }

    /** `[hue 0..360, saturation 0..1, lightness 0..1]`. */
    fun rgbToHsl(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val maximum = max(r, max(g, b))
        val minimum = min(r, min(g, b))
        val delta = maximum - minimum
        val lightness = (maximum + minimum) / 2f

        if (delta < 1e-6f) return floatArrayOf(0f, 0f, lightness)

        val saturation = delta / (1f - abs(2f * lightness - 1f)).coerceAtLeast(1e-6f)
        val hue = when (maximum) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return floatArrayOf(if (hue < 0f) hue + 360f else hue, saturation.coerceIn(0f, 1f), lightness)
    }

    /** An opaque ARGB colour. */
    fun hslToRgb(hue: Float, saturation: Float, lightness: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(value: Float): Int = ((value + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
