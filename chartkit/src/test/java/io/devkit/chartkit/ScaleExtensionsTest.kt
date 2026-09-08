package io.devkit.chartkit

import androidx.compose.ui.graphics.Color
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.SizeScale
import io.devkit.chartkit.scale.SizeScaleMode
import io.devkit.chartkit.scale.bandIndex
import io.devkit.chartkit.scale.lerpColor
import io.devkit.chartkit.scale.sampleRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** The two scales added alongside position: size and colour. */
class SizeScaleTest {

    @Test
    fun `the domain ends map to the size ends`() {
        val scale = SizeScale(NumericDomain(0.0, 100.0), minSize = 4f, maxSize = 32f)
        assertEquals(4f, scale.size(0.0), 1e-4f)
        assertEquals(32f, scale.size(100.0), 1e-4f)
    }

    @Test
    fun `area mode maps the value to area, not to radius`() {
        val scale = SizeScale(NumericDomain(0.0, 100.0), minSize = 0f, maxSize = 10f)
        // Half the value should give half the *area*: r = 10/sqrt(2).
        val half = scale.size(50.0)
        assertEquals(10f / kotlin.math.sqrt(2f), half, 1e-3f)
        // And the areas really are in a 1:2 ratio.
        val fullArea = PI * 10.0 * 10.0
        val halfArea = PI * half * half
        assertEquals(0.5, halfArea / fullArea, 1e-3)
    }

    @Test
    fun `radius mode maps the value straight to the radius`() {
        val scale = SizeScale(
            NumericDomain(0.0, 100.0),
            minSize = 0f,
            maxSize = 10f,
            mode = SizeScaleMode.Radius,
        )
        assertEquals(5f, scale.size(50.0), 1e-4f)
    }

    @Test
    fun `values outside the domain are clamped rather than extrapolated`() {
        val scale = SizeScale(NumericDomain(10.0, 20.0), minSize = 4f, maxSize = 20f)
        assertEquals(4f, scale.size(-100.0), 1e-4f)
        assertEquals(20f, scale.size(1e9), 1e-4f)
    }

    @Test
    fun `a missing or non-finite value takes the smallest size`() {
        val scale = SizeScale(NumericDomain(0.0, 10.0), minSize = 5f, maxSize = 20f)
        assertEquals(5f, scale.size(null), 1e-4f)
        assertEquals(5f, scale.size(Double.NaN), 1e-4f)
    }

    @Test
    fun `a degenerate domain does not divide by zero`() {
        val scale = SizeScale(NumericDomain(7.0, 7.0), minSize = 4f, maxSize = 20f)
        assertTrue(scale.size(7.0).isFinite())
    }

    @Test
    fun `legend stops are round numbers with their sizes`() {
        val scale = SizeScale(NumericDomain(0.0, 97.0), minSize = 4f, maxSize = 32f)
        val stops = scale.legendStops(3)
        assertTrue(stops.isNotEmpty())
        assertTrue(stops.all { it.second in 4f..32f })
    }
}

/** Colour scales, and the pure banding and ramp maths behind them. */
class ColorScaleTest {

    private val low = Color(0xFF000000)
    private val high = Color(0xFFFFFFFF)

    @Test
    fun `a continuous scale interpolates between its stops`() {
        val scale = ColorScale.Continuous(NumericDomain(0.0, 100.0), listOf(low, high))
        assertEquals(low, scale.colorAt(0.0))
        assertEquals(high, scale.colorAt(100.0))
        val middle = scale.colorAt(50.0)!!
        assertEquals(0.5f, middle.red, 0.01f)
    }

    @Test
    fun `a continuous scale clamps outside its domain`() {
        val scale = ColorScale.Continuous(NumericDomain(0.0, 100.0), listOf(low, high))
        assertEquals(low, scale.colorAt(-500.0))
        assertEquals(high, scale.colorAt(5000.0))
    }

    @Test
    fun `a missing value has no colour, which is not the low colour`() {
        val scale = ColorScale.Continuous(NumericDomain(0.0, 100.0), listOf(low, high))
        assertNull(scale.colorAt(null))
        assertNull(scale.colorAt(Double.NaN))
        assertNotNull(scale.colorAt(0.0))
    }

    @Test
    fun `threshold bands are half-open upward`() {
        val thresholds = listOf(20.0, 50.0, 80.0)
        assertEquals(0, bandIndex(0.0, thresholds))
        assertEquals(0, bandIndex(19.999, thresholds))
        // "80 and above is critical" puts 80 in the critical band.
        assertEquals(1, bandIndex(20.0, thresholds))
        assertEquals(2, bandIndex(50.0, thresholds))
        assertEquals(3, bandIndex(80.0, thresholds))
        assertEquals(3, bandIndex(1000.0, thresholds))
    }

    @Test
    fun `a threshold scale gives one colour per band`() {
        val scale = ColorScale.Threshold(
            thresholds = listOf(20.0, 50.0),
            colors = listOf(Color.Green, Color.Yellow, Color.Red),
            labels = listOf("ok", "warn", "bad"),
        )
        assertEquals(Color.Green, scale.colorAt(5.0))
        assertEquals(Color.Yellow, scale.colorAt(30.0))
        assertEquals(Color.Red, scale.colorAt(90.0))
        assertEquals(listOf("ok", "warn", "bad"), scale.legendStops().map { it.label })
    }

    @Test
    fun `a threshold scale rejects a mismatched colour count`() {
        val failure = runCatching {
            ColorScale.Threshold(listOf(1.0, 2.0), listOf(Color.Red, Color.Blue))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a quantized scale buckets the domain into equal steps`() {
        val scale = ColorScale.Quantized(NumericDomain(0.0, 100.0), listOf(low, high), steps = 4)
        assertEquals(0, scale.stepOf(0.0))
        assertEquals(0, scale.stepOf(24.9))
        assertEquals(1, scale.stepOf(25.0))
        assertEquals(3, scale.stepOf(100.0))
        assertEquals(4, scale.legendStops().size)
    }

    @Test
    fun `a quantized scale gives every step a distinct colour`() {
        val scale = ColorScale.Quantized(NumericDomain(0.0, 100.0), listOf(low, high), steps = 4)
        val colors = (0..3).map { scale.colorAt(it * 25.0 + 1.0) }
        assertEquals(4, colors.toSet().size)
    }

    @Test
    fun `a categorical scale maps by key and wraps its palette`() {
        val scale = ColorScale.Categorical(
            keys = listOf("a", "b", "c"),
            colors = listOf(Color.Red, Color.Blue),
        )
        assertEquals(Color.Red, scale.colorFor("a"))
        assertEquals(Color.Blue, scale.colorFor("b"))
        assertEquals(Color.Red, scale.colorFor("c"))
        assertNull(scale.colorFor("missing"))
    }

    @Test
    fun `ramp sampling is component-wise and bounded`() {
        val ramp = listOf(Color.Black, Color.White)
        assertEquals(Color.Black, sampleRamp(ramp, -1f))
        assertEquals(Color.White, sampleRamp(ramp, 2f))
        assertEquals(0.25f, sampleRamp(ramp, 0.25f).red, 0.01f)
    }

    @Test
    fun `a three-stop ramp passes through its middle colour`() {
        val ramp = listOf(Color.Black, Color.Red, Color.White)
        assertEquals(Color.Red, sampleRamp(ramp, 0.5f))
    }

    @Test
    fun `colour interpolation carries alpha`() {
        val transparent = Color.Red.copy(alpha = 0f)
        assertEquals(0.5f, lerpColor(transparent, Color.Red, 0.5f).alpha, 0.01f)
    }
}
