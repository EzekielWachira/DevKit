package io.devkit.chartkit

import io.devkit.chartkit.gauge.GaugeBandOverflow
import io.devkit.chartkit.gauge.GaugeBandOverlap
import io.devkit.chartkit.gauge.GaugeBandResolution
import io.devkit.chartkit.gauge.GaugeBandStyle
import io.devkit.chartkit.gauge.GaugeException
import io.devkit.chartkit.gauge.GaugeScale
import io.devkit.chartkit.gauge.GaugeTickConfig
import io.devkit.chartkit.gauge.GaugeTickPlan
import io.devkit.chartkit.layer.polar.GaugeBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Major and minor tick generation, in the value domain. */
class GaugeTickTest {

    private val speedo = GaugeScale.between(0.0, 200.0, startAngle = -90f, endAngle = 90f)

    @Test
    fun `an explicit interval puts a tick at every multiple`() {
        val plan = GaugeTickPlan.of(speedo, GaugeTickConfig(interval = 20.0, minorCount = 0))
        assertEquals(
            listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0, 120.0, 140.0, 160.0, 180.0, 200.0),
            plan.major,
        )
    }

    @Test
    fun `automatic ticks are round numbers, not equal divisions`() {
        val plan = GaugeTickPlan.of(speedo, GaugeTickConfig(count = 5, minorCount = 0))
        // Every step off the 1-2-5-10 ladder, and every value a round one.
        val steps = plan.major.zipWithNext { a, b -> b - a }.distinct()
        assertEquals(1, steps.size)
        plan.major.forEach { assertEquals(it, Math.round(it).toDouble(), 1e-9) }
    }

    @Test
    fun `the ends of the range always get a tick`() {
        // 0..97 has no round tick at 97; a dial whose last mark is 80 reads as
        // broken, so the end is added back.
        val scale = GaugeScale.between(0.0, 97.0, startAngle = -90f, endAngle = 90f)
        val plan = GaugeTickPlan.of(scale, GaugeTickConfig(count = 5, minorCount = 0))
        assertEquals(0.0, plan.major.first(), 1e-9)
        assertEquals(97.0, plan.major.last(), 1e-9)
    }

    @Test
    fun `includeEnd off leaves the maximum unmarked`() {
        val scale = GaugeScale.between(0.0, 97.0, startAngle = -90f, endAngle = 90f)
        val plan = GaugeTickPlan.of(
            scale,
            GaugeTickConfig(count = 5, minorCount = 0, includeEnd = false),
        )
        assertTrue(plan.major.last() < 97.0)
    }

    @Test
    fun `minor ticks subdivide each major interval and never duplicate one`() {
        val plan = GaugeTickPlan.of(speedo, GaugeTickConfig(interval = 20.0, minorCount = 4))
        // Four subdivisions means three minors between each pair of majors.
        assertEquals(5.0, plan.minor[0], 1e-9)
        assertEquals(10.0, plan.minor[1], 1e-9)
        assertEquals(15.0, plan.minor[2], 1e-9)
        assertEquals(25.0, plan.minor[3], 1e-9)
        plan.minor.forEach { minor ->
            assertTrue(
                "minor $minor coincides with a major",
                plan.major.none { kotlin.math.abs(it - minor) < 1e-6 },
            )
        }
    }

    @Test
    fun `an explicit minor interval overrides the subdivision count`() {
        val plan = GaugeTickPlan.of(
            speedo,
            GaugeTickConfig(interval = 20.0, minorCount = 4, minorInterval = 10.0),
        )
        assertEquals(listOf(10.0, 30.0, 50.0), plan.minor.take(3))
    }

    @Test
    fun `no minor ticks when none were asked for`() {
        assertTrue(GaugeTickPlan.of(speedo, GaugeTickConfig.MajorOnly).minor.isEmpty())
    }

    @Test
    fun `a tiny range still gets ticks`() {
        val scale = GaugeScale.between(0.0, 1.0, startAngle = -90f, endAngle = 90f)
        val plan = GaugeTickPlan.of(scale, GaugeTickConfig(count = 5, minorCount = 0))
        assertTrue(plan.major.size >= 2)
        assertEquals(0.0, plan.major.first(), 1e-9)
        assertEquals(1.0, plan.major.last(), 1e-9)
    }

    @Test
    fun `a huge range still gets a readable number of ticks`() {
        val scale = GaugeScale.between(0.0, 1_000_000.0, startAngle = -90f, endAngle = 90f)
        val plan = GaugeTickPlan.of(scale, GaugeTickConfig(count = 5, minorCount = 0))
        assertTrue(plan.major.size in 2..12)
    }

    @Test
    fun `a negative range gets ticks through zero`() {
        val scale = GaugeScale.between(-50.0, 50.0, startAngle = -90f, endAngle = 90f)
        val plan = GaugeTickPlan.of(scale, GaugeTickConfig(interval = 25.0, minorCount = 0))
        assertEquals(listOf(-50.0, -25.0, 0.0, 25.0, 50.0), plan.major)
    }

    @Test
    fun `a range that does not include zero gets ticks in its own interval`() {
        val scale = GaugeScale.between(900.0, 1100.0, startAngle = -90f, endAngle = 90f)
        val plan = GaugeTickPlan.of(scale, GaugeTickConfig(interval = 50.0, minorCount = 0))
        assertEquals(listOf(900.0, 950.0, 1000.0, 1050.0, 1100.0), plan.major)
    }

    @Test
    fun `a full circle does not draw two ticks on one angle`() {
        val scale = GaugeScale(0.0, 360.0, startAngle = 0f, sweepAngle = 360f)
        val plan = GaugeTickPlan.of(scale, GaugeTickConfig(interval = 90.0, minorCount = 0))
        // 0 and 360 are the same place; only one mark is drawn there.
        assertEquals(listOf(0.0, 90.0, 180.0, 270.0), plan.major)
    }

    @Test
    fun `a small dial thins its labels but keeps its ticks`() {
        val big = GaugeTickPlan.of(speedo, GaugeTickConfig(interval = 20.0), radius = 400f)
        val small = GaugeTickPlan.of(speedo, GaugeTickConfig(interval = 20.0), radius = 60f)
        assertEquals(big.major, small.major)
        assertTrue(
            "a small dial should label fewer ticks (${small.labelled.size} of ${small.major.size})",
            small.labelled.size < big.labelled.size,
        )
        assertTrue(small.labelled.isNotEmpty())
    }

    @Test
    fun `thinned labels keep a uniform stride from the first`() {
        val plan = GaugeTickPlan.of(speedo, GaugeTickConfig(interval = 10.0), radius = 70f)
        assertEquals(plan.major.first(), plan.labelled.first(), 1e-9)
        val strides = plan.labelled.zipWithNext { a, b -> b - a }.distinct()
        assertTrue("labels were thinned unevenly: $strides", strides.size <= 1)
    }

    @Test
    fun `a hard label cap is honoured`() {
        val plan = GaugeTickPlan.of(
            speedo, GaugeTickConfig(interval = 10.0), radius = 400f, maxLabels = 3,
        )
        assertTrue(plan.labelled.size <= 3)
    }

    @Test
    fun `an automatic tick count grows with the dial`() {
        val small = GaugeTickPlan.of(speedo, GaugeTickConfig(minorCount = 0), radius = 60f)
        val large = GaugeTickPlan.of(speedo, GaugeTickConfig(minorCount = 0), radius = 400f)
        assertTrue(large.major.size >= small.major.size)
    }

    @Test
    fun `a subdivision finer than the dial can show is dropped rather than drawn solid`() {
        val plan = GaugeTickPlan.of(
            speedo,
            GaugeTickConfig(interval = 20.0, minorInterval = 0.01),
        )
        assertTrue(plan.minor.isEmpty())
    }
}

/** Band resolution: clamping, overlap and the status lookup. */
class GaugeBandTest {

    private val speedo = GaugeScale.between(0.0, 200.0, startAngle = -90f, endAngle = 90f)

    private fun bands(vararg pairs: Pair<Double, Double>) =
        pairs.map { GaugeBand(it.first, it.second) }

    @Test
    fun `a band becomes an arc through the scale`() {
        val resolution = GaugeBandResolution.of(bands(0.0 to 100.0), speedo)
        val band = resolution.bands.single()
        assertEquals(-90f, band.startAngle, 0.01f)
        assertEquals(90f, band.sweepAngle, 0.01f)
    }

    @Test
    fun `three bands tile the arc without gaps`() {
        val resolution = GaugeBandResolution.of(
            bands(0.0 to 120.0, 120.0 to 160.0, 160.0 to 200.0), speedo,
        )
        assertEquals(3, resolution.bands.size)
        val total = resolution.bands.sumOf { it.sweepAngle.toDouble() }
        assertEquals(180.0, total, 0.01)
    }

    @Test
    fun `a band reaching past the maximum is trimmed and says so`() {
        val resolution = GaugeBandResolution.of(bands(160.0 to 250.0), speedo)
        val band = resolution.bands.single()
        assertEquals(200.0, band.to, 1e-9)
        assertTrue(band.clamped)
    }

    @Test
    fun `a band entirely outside the range is dropped and reported`() {
        val resolution = GaugeBandResolution.of(bands(300.0 to 400.0), speedo)
        assertTrue(resolution.isEmpty)
        assertEquals(1, resolution.skipped.size)
        assertTrue(resolution.skipped.single().contains("outside"))
    }

    @Test
    fun `a zero-width band is dropped rather than drawn as a hairline`() {
        val resolution = GaugeBandResolution.of(bands(100.0 to 100.0), speedo)
        assertTrue(resolution.isEmpty)
        assertTrue(resolution.skipped.single().contains("zero width"))
    }

    @Test
    fun `a backwards band is read as the interval it names`() {
        val resolution = GaugeBandResolution.of(listOf(GaugeBand(160.0, 120.0)), speedo)
        val band = resolution.bands.single()
        assertEquals(120.0, band.from, 1e-9)
        assertEquals(160.0, band.to, 1e-9)
    }

    @Test
    fun `Skip leaves a partly-outside band undrawn`() {
        val resolution = GaugeBandResolution.of(
            bands(160.0 to 250.0), speedo, overflow = GaugeBandOverflow.Skip,
        )
        assertTrue(resolution.isEmpty)
        assertEquals(1, resolution.skipped.size)
    }

    @Test
    fun `Reject refuses a band that does not fit`() {
        val error = runCatching {
            GaugeBandResolution.of(bands(160.0 to 250.0), speedo, overflow = GaugeBandOverflow.Reject)
        }.exceptionOrNull()
        assertTrue(error is GaugeException)
        assertTrue(error!!.message!!.contains("reaches outside"))
    }

    @Test
    fun `overlapping bands are allowed by default and resolve to the first`() {
        val resolution = GaugeBandResolution.of(
            listOf(GaugeBand(0.0, 200.0, "Acceptable"), GaugeBand(90.0, 110.0, "Target")),
            speedo,
        )
        assertEquals(2, resolution.bands.size)
        // Drawn second, so the target sits on top; but the status is the first
        // match, so it does not depend on draw order.
        assertEquals("Acceptable", resolution.bandAt(100.0)?.label)
    }

    @Test
    fun `Reject refuses overlapping bands and says why`() {
        val error = runCatching {
            GaugeBandResolution.of(
                bands(0.0 to 120.0, 100.0 to 200.0),
                speedo,
                overlap = GaugeBandOverlap.Reject,
            )
        }.exceptionOrNull()
        assertTrue(error is GaugeException)
        assertTrue(error!!.message!!.contains("overlap"))
    }

    @Test
    fun `bands that merely touch do not count as overlapping`() {
        val resolution = GaugeBandResolution.of(
            bands(0.0 to 120.0, 120.0 to 200.0),
            speedo,
            overlap = GaugeBandOverlap.Reject,
        )
        assertEquals(2, resolution.bands.size)
    }

    @Test
    fun `the band lookup answers what the arc was coloured with`() {
        val resolution = GaugeBandResolution.of(
            listOf(
                GaugeBand(0.0, 120.0, "Normal"),
                GaugeBand(120.0, 160.0, "Warning"),
                GaugeBand(160.0, 200.0, "Critical"),
            ),
            speedo,
        )
        assertEquals("Normal", resolution.bandAt(80.0)?.label)
        assertEquals("Warning", resolution.bandAt(140.0)?.label)
        assertEquals("Critical", resolution.bandAt(180.0)?.label)
    }

    @Test
    fun `a value outside every band has no band`() {
        val resolution = GaugeBandResolution.of(bands(0.0 to 50.0), speedo)
        assertNull(resolution.bandAt(180.0))
        assertNull(resolution.bandAt(Double.NaN))
        assertNotNull(resolution.bandAt(25.0))
    }

    @Test
    fun `a band keeps its own style and its place in the caller's list`() {
        val style = GaugeBandStyle(thickness = 0.5f, position = 1f, rounded = true)
        val resolution = GaugeBandResolution.of(
            bands(0.0 to 50.0, 50.0 to 100.0), speedo, styles = mapOf(1 to style),
        )
        assertEquals(0, resolution.bands[0].sourceIndex)
        assertEquals(1, resolution.bands[1].sourceIndex)
        assertEquals(style, resolution.bands[1].style)
        assertEquals(1f, resolution.bands[0].style.thickness, 1e-6f)
    }

    @Test
    fun `a dropped band does not shift the palette slot of the ones after it`() {
        // The second band is outside the range; the third must keep index 2, or
        // it takes the missing band's colour.
        val resolution = GaugeBandResolution.of(
            bands(0.0 to 50.0, 300.0 to 400.0, 50.0 to 100.0), speedo,
        )
        assertEquals(listOf(0, 2), resolution.bands.map { it.sourceIndex })
    }

    @Test
    fun `an invalid band style is refused at construction`() {
        assertFalse(runCatching { GaugeBandStyle(alpha = 2f) }.isSuccess)
        assertFalse(runCatching { GaugeBandStyle(thickness = 0f) }.isSuccess)
    }
}
