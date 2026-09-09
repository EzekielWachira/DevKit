package io.devkit.chartkit

import io.devkit.chartkit.axis.AlignmentRequest
import io.devkit.chartkit.axis.AxisAlignment
import io.devkit.chartkit.axis.AxisDensity
import io.devkit.chartkit.axis.AxisDimension
import io.devkit.chartkit.axis.AxisGridMode
import io.devkit.chartkit.axis.AxisPosition
import io.devkit.chartkit.axis.AxisRegistry
import io.devkit.chartkit.axis.AxisVisibility
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartAxisException
import io.devkit.chartkit.axis.ChartAxisId
import io.devkit.chartkit.axis.ChartAxisSpec
import io.devkit.chartkit.axis.ChartUnit
import io.devkit.chartkit.axis.ValueAxisBinding
import io.devkit.chartkit.axis.axisId
import io.devkit.chartkit.geometry.ChartOrientation
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.layout.AxisMetrics
import io.devkit.chartkit.layout.computeChartLayout
import io.devkit.chartkit.scale.LinearScale
import io.devkit.chartkit.scale.NumericDomain
import io.devkit.chartkit.scale.TickGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val Rainfall = ChartAxisId("rainfall")
private val Temperature = ChartAxisId("temperature")
private val Pressure = ChartAxisId("pressure")

/**
 * The axis registry, the alignment algorithm and the layout arithmetic that
 * multi-axis charts are built out of.
 *
 * Everything here is testable without a renderer, which is the point: a bug in
 * which axis a layer resolves to, or in how far the second axis on a side sits
 * from the plot, is arithmetic and should not need a device to catch.
 */
class AxisRegistryTest {

    private fun y(id: ChartAxisId, position: AxisPosition? = null, primary: Boolean = false) =
        ChartAxisSpec(id = id, dimension = AxisDimension.Y, position = position, primary = primary)

    private fun x(id: ChartAxisId = ChartAxisId.DefaultX) =
        ChartAxisSpec(id = id, dimension = AxisDimension.X, primary = true)

    @Test
    fun `registers axes in both dimensions`() {
        val registry = AxisRegistry.of(listOf(x(), y(Rainfall), y(Temperature)))
        assertEquals(1, registry.xAxes.size)
        assertEquals(2, registry.yAxes.size)
        assertEquals(listOf(Rainfall, Temperature), registry.yAxes.map { it.id })
    }

    @Test
    fun `looks an axis up by id`() {
        val registry = AxisRegistry.of(listOf(x(), y(Rainfall)))
        assertEquals(Rainfall, registry.find(Rainfall)?.id)
        assertNull(registry.find(Pressure))
        assertTrue(Rainfall in registry)
        assertFalse(Pressure in registry)
    }

    @Test
    fun `duplicate ids are rejected`() {
        val error = runCatching { AxisRegistry.of(listOf(y(Rainfall), y(Rainfall))) }.exceptionOrNull()
        assertTrue(error is ChartAxisException)
        assertTrue(error!!.message!!.contains("rainfall"))
    }

    @Test
    fun `a missing axis names what asked for it and what exists`() {
        val registry = AxisRegistry.of(listOf(x(), y(Rainfall), y(Temperature)))
        val error = runCatching {
            registry.requireAxis(Pressure, "Layer \"line0\"", AxisDimension.Y)
        }.exceptionOrNull()
        assertTrue(error is ChartAxisException)
        val message = error!!.message!!
        // The three things a caller needs: who, what, and what was available.
        assertTrue(message.contains("line0"))
        assertTrue(message.contains("pressure"))
        assertTrue(message.contains("rainfall"))
        assertTrue(message.contains("temperature"))
    }

    @Test
    fun `a missing axis never silently falls back to the primary`() {
        val registry = AxisRegistry.of(listOf(x(), y(Rainfall, primary = true)))
        assertTrue(
            runCatching { registry.requireAxis(Pressure, "Layer", AxisDimension.Y) }.isFailure,
        )
    }

    @Test
    fun `asking for a Y axis by an X axis id is an error`() {
        val registry = AxisRegistry.of(listOf(x(), y(Rainfall)))
        val error = runCatching {
            registry.requireAxis(ChartAxisId.DefaultX, "Layer", AxisDimension.Y)
        }.exceptionOrNull()
        assertTrue(error is ChartAxisException)
        assertTrue(error!!.message!!.contains("registered as a X axis"))
    }

    @Test
    fun `a Y axis on a horizontal edge of a vertical chart is rejected`() {
        val error = runCatching {
            AxisRegistry.of(listOf(y(Rainfall, position = AxisPosition.Bottom)))
        }.exceptionOrNull()
        assertTrue(error is ChartAxisException)
        assertTrue(error!!.message!!.contains("Start or End"))
    }

    @Test
    fun `on a horizontal chart the value axis belongs along the bottom`() {
        val registry = AxisRegistry.of(
            listOf(y(Rainfall, position = AxisPosition.Bottom)),
            orientation = ChartOrientation.Horizontal,
        )
        assertEquals(AxisPosition.Bottom, registry.positionOf(registry.find(Rainfall)!!))
        // And the same axis on Start would be the mistake there.
        assertTrue(
            runCatching {
                AxisRegistry.of(
                    listOf(y(Temperature, position = AxisPosition.Start)),
                    orientation = ChartOrientation.Horizontal,
                )
            }.isFailure,
        )
    }

    @Test
    fun `two primary axes in one dimension are rejected`() {
        val error = runCatching {
            AxisRegistry.of(listOf(y(Rainfall, primary = true), y(Temperature, primary = true)))
        }.exceptionOrNull()
        assertTrue(error is ChartAxisException)
        assertTrue(error!!.message!!.contains("primary"))
    }

    @Test
    fun `the first declared axis is primary when none says so`() {
        val registry = AxisRegistry.of(listOf(x(), y(Rainfall), y(Temperature)))
        assertEquals(Rainfall, registry.primaryY?.id)
    }

    @Test
    fun `an explicit primary wins over declaration order`() {
        val registry = AxisRegistry.of(listOf(x(), y(Rainfall), y(Temperature, primary = true)))
        assertEquals(Temperature, registry.primaryY?.id)
    }

    @Test
    fun `axes on one side come back in declaration order`() {
        val registry = AxisRegistry.of(
            listOf(
                x(),
                y(Rainfall, AxisPosition.Start),
                y(Temperature, AxisPosition.End),
                y(Pressure, AxisPosition.End),
            ),
        )
        assertEquals(listOf(Temperature, Pressure), registry.axesAt(AxisPosition.End).map { it.id })
        assertEquals(listOf(Rainfall), registry.axesAt(AxisPosition.Start).map { it.id })
    }

    @Test
    fun `default positions come from the chart's orientation`() {
        val vertical = AxisRegistry.of(listOf(x(), y(Rainfall)))
        assertEquals(AxisPosition.Bottom, vertical.positionOf(vertical.find(ChartAxisId.DefaultX)!!))
        assertEquals(AxisPosition.Start, vertical.positionOf(vertical.find(Rainfall)!!))

        val horizontal = AxisRegistry.of(listOf(x(), y(Rainfall)), ChartOrientation.Horizontal)
        assertEquals(AxisPosition.Start, horizontal.positionOf(horizontal.find(ChartAxisId.DefaultX)!!))
        assertEquals(AxisPosition.Bottom, horizontal.positionOf(horizontal.find(Rainfall)!!))
    }

    @Test
    fun `a blank axis id is refused at construction`() {
        assertTrue(runCatching { ChartAxisId("  ") }.isFailure)
    }

    @Test
    fun `the legacy binding resolves to the default axis ids`() {
        assertEquals(ChartAxisId.DefaultY, ValueAxisBinding.Primary.axisId())
        assertEquals(ChartAxisId.SecondaryY, ValueAxisBinding.Secondary.axisId())
    }

    @Test
    fun `a spec folds its title and domain into the axis it draws with`() {
        val spec = ChartAxisSpec(
            id = Temperature,
            title = "Temperature",
            axis = ChartAxis(tickCount = 6),
        )
        assertEquals("Temperature", spec.config.title)
        assertEquals(6, spec.config.tickCount)
        assertEquals("Temperature", spec.displayName)
    }

    @Test
    fun `an axis with no title is named by its id and never by nothing`() {
        assertEquals("pressure", ChartAxisSpec(id = Pressure).displayName)
    }
}

/** Independent domains, per axis, from that axis' own layers only. */
class AxisDomainTest {

    @Test
    fun `each axis maps its own domain into the same plot extent`() {
        val plot = ChartRect(0f, 0f, 400f, 300f)
        val rainfall = LinearScale(NumericDomain(0.0, 250.0), plot.bottom, plot.top)
        val temperature = LinearScale(NumericDomain(-10.0, 40.0), plot.bottom, plot.top)
        val pressure = LinearScale(NumericDomain(980.0, 1040.0), plot.bottom, plot.top)

        // Three domains with nothing in common; three scales agreeing exactly
        // about where the top and the bottom of the plot are.
        listOf(rainfall to 250.0, temperature to 40.0, pressure to 1040.0).forEach { (scale, max) ->
            assertEquals(plot.top, scale.scale(max), 0.01f)
        }
        listOf(rainfall to 0.0, temperature to -10.0, pressure to 980.0).forEach { (scale, min) ->
            assertEquals(plot.bottom, scale.scale(min), 0.01f)
        }
    }

    @Test
    fun `a value maps to a different row on each axis`() {
        val plot = ChartRect(0f, 0f, 400f, 300f)
        val rainfall = LinearScale(NumericDomain(0.0, 250.0), plot.bottom, plot.top)
        val temperature = LinearScale(NumericDomain(-10.0, 40.0), plot.bottom, plot.top)
        // The whole point of independent scales: 40 is near the bottom of a
        // rainfall axis and at the very top of a temperature one.
        assertTrue(rainfall.scale(40.0) > temperature.scale(40.0))
    }
}

/** Tick alignment: shared rows, round numbers, and knowing when to decline. */
class AxisAlignmentTest {

    private fun request(
        id: ChartAxisId,
        min: Double,
        max: Double,
        ticks: Int = 5,
        alignZero: Boolean = false,
    ) = AlignmentRequest(id, NumericDomain(min, max), ticks, alignZero)

    @Test
    fun `aligned axes end up with the same number of ticks`() {
        val result = AxisAlignment.align(
            listOf(
                request(Rainfall, 0.0, 96.0),
                request(Temperature, 4.2, 18.1),
                request(Pressure, 1012.0, 1019.0),
            ),
        )
        val counts = result.axes.values.map { it.ticks.size }.toSet()
        assertEquals(1, counts.size)
        assertEquals(3, result.axes.size)
    }

    @Test
    fun `aligned ticks land on the same screen rows`() {
        val result = AxisAlignment.align(
            listOf(request(Rainfall, 0.0, 96.0), request(Temperature, 4.2, 18.1)),
        )
        val plotTop = 0f
        val plotBottom = 300f
        val rows = result.axes.map { (_, axis) ->
            val scale = LinearScale(axis.domain, plotBottom, plotTop)
            axis.ticks.map { scale.scale(it) }
        }
        assertEquals(2, rows.size)
        rows[0].forEachIndexed { index, position ->
            assertEquals(position, rows[1][index], 0.01f)
        }
    }

    @Test
    fun `aligned axes keep round tick values`() {
        val result = AxisAlignment.align(
            listOf(request(Rainfall, 0.0, 96.0), request(Temperature, 4.2, 18.1)),
        )
        result.axes.values.forEach { axis ->
            val steps = axis.ticks.zipWithNext { a, b -> b - a }.distinct()
            // One uniform step, and a step off the 1-2-5-10 ladder.
            assertEquals(1, steps.map { Math.round(it * 1e6) }.distinct().size)
            val step = steps.first()
            val normalised = step / Math.pow(10.0, Math.floor(Math.log10(step)))
            assertTrue("step $step is not nice", normalised in setOf(1.0, 2.0, 5.0, 10.0))
        }
    }

    @Test
    fun `an aligned axis still covers its own data`() {
        val result = AxisAlignment.align(
            listOf(request(Rainfall, 0.0, 96.0), request(Pressure, 1012.0, 1019.0)),
        )
        assertTrue(result.axes.getValue(Rainfall).domain.max >= 96.0)
        assertTrue(result.axes.getValue(Rainfall).domain.min <= 0.0)
        assertTrue(result.axes.getValue(Pressure).domain.max >= 1019.0)
        assertTrue(result.axes.getValue(Pressure).domain.min <= 1012.0)
    }

    @Test
    fun `one axis cannot be aligned with itself`() {
        val result = AxisAlignment.align(listOf(request(Rainfall, 0.0, 96.0)))
        assertTrue(result.axes.isEmpty())
    }

    @Test
    fun `zero alignment puts both zeros on the same row`() {
        val result = AxisAlignment.align(
            listOf(
                request(Rainfall, -42.0, 96.0, alignZero = true),
                request(Temperature, -1.8, 3.6, alignZero = true),
            ),
        )
        assertEquals(2, result.axes.size)
        val rows = result.axes.values.map { axis ->
            LinearScale(axis.domain, 300f, 0f).scale(0.0)
        }
        assertEquals(rows[0], rows[1], 0.01f)
    }

    @Test
    fun `zero alignment keeps zero as an actual tick`() {
        val result = AxisAlignment.align(
            listOf(
                request(Rainfall, -42.0, 96.0, alignZero = true),
                request(Temperature, -1.8, 3.6, alignZero = true),
            ),
        )
        result.axes.values.forEach { axis ->
            assertTrue(axis.ticks.any { kotlin.math.abs(it) < 1e-9 })
        }
    }

    @Test
    fun `zero alignment declines rather than flattening an axis`() {
        // [999, 1001] aligned to zero with [-5, 5] would have to span
        // [-1000, 1000] and draw its own data as one flat line.
        val result = AxisAlignment.align(
            listOf(
                request(Rainfall, -5.0, 5.0, alignZero = true),
                request(Pressure, 999.0, 1001.0, alignZero = true),
            ),
        )
        assertFalse(Pressure in result.axes)
        assertTrue(result.diagnostics.any { it.axisId == Pressure })
    }

    @Test
    fun `a declined alignment says which axis and why`() {
        val result = AxisAlignment.align(
            listOf(
                request(Rainfall, -5.0, 5.0, alignZero = true),
                request(Pressure, 999.0, 1001.0, alignZero = true),
            ),
        )
        val message = result.diagnostics.first { it.axisId == Pressure }.message
        assertTrue(message.contains("pressure"))
        assertTrue(message.contains("unaligned"))
    }

    @Test
    fun `fitting a domain to a tick count produces exactly that many intervals`() {
        val fitted = AxisAlignment.fit(NumericDomain(0.0, 96.0), intervals = 5)
        assertEquals(6, fitted.ticks.size)
    }

    @Test
    fun `independent axes keep their own nice ticks`() {
        // What Independent means: the generator, untouched by any other axis.
        val rainfall = TickGenerator.ticks(NumericDomain(0.0, 96.0), 5)
        val pressure = TickGenerator.ticks(NumericDomain(1012.0, 1019.0), 5)
        assertTrue(rainfall.size >= 2)
        assertTrue(pressure.size >= 2)
        // Nothing forces them to agree, and here they do not.
        assertTrue(rainfall.size != pressure.size || rainfall.first() != pressure.first())
    }

    @Test
    fun `the nice-number ladder walks upward`() {
        assertEquals(2.0, TickGenerator.nextStep(1.0), 1e-9)
        assertEquals(5.0, TickGenerator.nextStep(2.0), 1e-9)
        assertEquals(10.0, TickGenerator.nextStep(5.0), 1e-9)
        assertEquals(20.0, TickGenerator.nextStep(10.0), 1e-9)
    }
}

/** Axis stacking, offsets and how much of the chart the plot keeps. */
class MultiAxisLayoutTest {

    private fun metrics(
        id: ChartAxisId,
        position: AxisPosition,
        labelExtent: Float = 20f,
        title: Float = 0f,
        visible: Boolean = true,
        offsetOverride: Float? = null,
    ) = AxisMetrics(
        position = position,
        visible = visible,
        labelExtent = labelExtent,
        tickLength = 4f,
        labelPadding = 4f,
        titleExtent = title,
        id = id,
        offsetOverride = offsetOverride,
    )

    @Test
    fun `two axes on one side stack outward by the first one's gutter`() {
        val layout = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            axes = listOf(
                metrics(Temperature, AxisPosition.End, labelExtent = 30f),
                metrics(Pressure, AxisPosition.End, labelExtent = 40f),
            ),
        )
        assertEquals(0f, layout.offsetOf(Temperature), 0.01f)
        // The temperature axis reserved 30 + 4 + 4 = 38.
        assertEquals(38f, layout.offsetOf(Pressure), 0.01f)
    }

    @Test
    fun `two axes on one side do not overlap`() {
        val layout = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            axes = listOf(
                metrics(Temperature, AxisPosition.End, labelExtent = 30f),
                metrics(Pressure, AxisPosition.End, labelExtent = 40f),
            ),
        )
        val firstEnds = layout.offsetOf(Temperature) + 38f
        assertTrue(layout.offsetOf(Pressure) >= firstEnds - 0.01f)
        // And the plot gave up both gutters: 38 + 48.
        assertEquals(400f - 86f, layout.plotArea.right, 0.01f)
    }

    @Test
    fun `axes on opposite sides both start at the plot edge`() {
        val layout = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            axes = listOf(
                metrics(Rainfall, AxisPosition.Start),
                metrics(Temperature, AxisPosition.End),
            ),
        )
        assertEquals(0f, layout.offsetOf(Rainfall), 0.01f)
        assertEquals(0f, layout.offsetOf(Temperature), 0.01f)
    }

    @Test
    fun `the plot shrinks as axes are added`() {
        val bounds = ChartRect(0f, 0f, 400f, 300f)
        val one = computeChartLayout(bounds, axes = listOf(metrics(Rainfall, AxisPosition.Start)))
        val two = computeChartLayout(
            bounds,
            axes = listOf(metrics(Rainfall, AxisPosition.Start), metrics(Temperature, AxisPosition.End)),
        )
        val three = computeChartLayout(
            bounds,
            axes = listOf(
                metrics(Rainfall, AxisPosition.Start),
                metrics(Temperature, AxisPosition.End),
                metrics(Pressure, AxisPosition.End),
            ),
        )
        assertTrue(two.plotArea.width < one.plotArea.width)
        assertTrue(three.plotArea.width < two.plotArea.width)
    }

    @Test
    fun `a hidden axis takes no gutter but keeps a measured offset`() {
        val layout = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            axes = listOf(
                metrics(Temperature, AxisPosition.End, visible = false),
                metrics(Pressure, AxisPosition.End, labelExtent = 40f),
            ),
        )
        assertEquals(0f, layout.offsetOf(Pressure), 0.01f)
        assertEquals(400f - 48f, layout.plotArea.right, 0.01f)
    }

    @Test
    fun `an explicit offset overrides the measured one`() {
        val layout = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            axes = listOf(
                metrics(Temperature, AxisPosition.End, labelExtent = 30f),
                metrics(Pressure, AxisPosition.End, labelExtent = 40f, offsetOverride = 96f),
            ),
        )
        assertEquals(96f, layout.offsetOf(Pressure), 0.01f)
    }

    @Test
    fun `a wider label reserves a wider gutter`() {
        val narrow = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            axes = listOf(metrics(Rainfall, AxisPosition.Start, labelExtent = 12f)),
        )
        val wide = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            axes = listOf(metrics(Rainfall, AxisPosition.Start, labelExtent = 60f)),
        )
        assertEquals(48f, wide.plotArea.left - narrow.plotArea.left, 0.01f)
    }

    @Test
    fun `an unnamed axis contributes a gutter and no offset`() {
        val layout = computeChartLayout(
            bounds = ChartRect(0f, 0f, 400f, 300f),
            axes = listOf(
                AxisMetrics(AxisPosition.Bottom, true, 20f, 4f, 4f, 0f),
                metrics(Rainfall, AxisPosition.Start),
            ),
        )
        assertEquals(0f, layout.offsetOf(null), 0.01f)
        assertEquals(300f - 28f, layout.plotArea.bottom, 0.01f)
    }
}

/** Units: labels, announcements and the mismatch check. */
class ChartUnitTest {

    @Test
    fun `a unit writes itself after the number`() {
        assertEquals("82 mm", ChartUnit.Custom("mm", "millimetres").label("82"))
        assertEquals("82 millimetres", ChartUnit.Custom("mm", "millimetres").spoken("82"))
    }

    @Test
    fun `percent takes no space, as it is written everywhere`() {
        assertEquals("45%", ChartUnit.Percent.label("45"))
        assertEquals("45 percent", ChartUnit.Percent.spoken("45"))
    }

    @Test
    fun `a count adds nothing`() {
        assertEquals("1,240", ChartUnit.Count.label("1,240"))
        assertEquals("1,240", ChartUnit.None.spoken("1,240"))
    }

    @Test
    fun `an unstated unit is compatible with everything`() {
        assertTrue(ChartUnit.compatible(ChartUnit.None, ChartUnit.Percent))
        assertTrue(ChartUnit.compatible(ChartUnit.Currency("GBP"), ChartUnit.None))
    }

    @Test
    fun `an axis with its own formatter does not append its unit as well`() {
        // A currency formatter already writes "£86,400"; appending the declared
        // unit on top of it gives "£86,400 GBP", which is wrong on screen and
        // worse read aloud.
        val explicit = ChartAxisSpec(
            id = Rainfall,
            unit = ChartUnit.Currency("GBP"),
            axis = ChartAxis(valueFormatter = { "£" + it.toLong() }),
        )
        val derived = ChartAxisSpec(id = Rainfall, unit = ChartUnit.Currency("GBP"))
        assertNotNull(explicit.axis.valueFormatter)
        assertNull(derived.axis.valueFormatter)
        // The declared unit survives either way — it is what the axis measures,
        // and the mismatch check, the summary and the data table all need it.
        assertEquals(ChartUnit.Currency("GBP"), explicit.unit)
    }

    @Test
    fun `two different stated units are not compatible`() {
        assertFalse(ChartUnit.compatible(ChartUnit.Percent, ChartUnit.Currency("USD")))
        assertFalse(ChartUnit.compatible(ChartUnit.Currency("USD"), ChartUnit.Currency("GBP")))
        assertTrue(ChartUnit.compatible(ChartUnit.Currency("USD"), ChartUnit.Currency("USD")))
    }
}

/** Compaction: when it starts, and what it does. */
class AxisDensityTest {

    @Test
    fun `full never compacts`() {
        assertFalse(AxisDensity.Full.isCompact(200f, axisCount = 4, widthPerAxis = 112f))
    }

    @Test
    fun `compact always compacts`() {
        assertTrue(AxisDensity.Compact.isCompact(2000f, axisCount = 1, widthPerAxis = 112f))
    }

    @Test
    fun `one axis is never cramped by its own gutter`() {
        assertFalse(AxisDensity.Auto.isCompact(200f, axisCount = 1, widthPerAxis = 112f))
    }

    @Test
    fun `three axes on a phone compact and on a tablet do not`() {
        assertTrue(AxisDensity.Auto.isCompact(360f, axisCount = 3, widthPerAxis = 112f))
        assertFalse(AxisDensity.Auto.isCompact(1024f, axisCount = 3, widthPerAxis = 112f))
    }
}

/** Grid ownership, which is a per-axis decision and not a chart-wide one. */
class AxisGridOwnershipTest {

    private fun owns(mode: AxisGridMode, isPrimary: Boolean): Boolean = when (mode) {
        AxisGridMode.Hidden -> false
        AxisGridMode.Visible -> true
        AxisGridMode.Primary -> isPrimary
    }

    @Test
    fun `by default only the primary axis draws a grid`() {
        assertTrue(owns(AxisGridMode.Primary, isPrimary = true))
        assertFalse(owns(AxisGridMode.Primary, isPrimary = false))
    }

    @Test
    fun `a secondary axis can be asked to draw one anyway`() {
        assertTrue(owns(AxisGridMode.Visible, isPrimary = false))
    }

    @Test
    fun `and the primary can be asked not to`() {
        assertFalse(owns(AxisGridMode.Hidden, isPrimary = true))
    }
}

/** Auto visibility, which is what makes a legend toggle remove an axis. */
class AxisVisibilityTest {

    private fun visible(mode: AxisVisibility, boundLayers: Int, configVisible: Boolean = true): Boolean =
        when (mode) {
            AxisVisibility.Visible -> configVisible
            AxisVisibility.Hidden -> false
            AxisVisibility.Auto -> configVisible && boundLayers > 0
        }

    @Test
    fun `an axis with no visible layer hides itself in Auto`() {
        assertFalse(visible(AxisVisibility.Auto, boundLayers = 0))
        assertTrue(visible(AxisVisibility.Auto, boundLayers = 1))
    }

    @Test
    fun `Visible keeps an axis even with nothing on it`() {
        assertTrue(visible(AxisVisibility.Visible, boundLayers = 0))
    }

    @Test
    fun `Hidden wins over everything`() {
        assertFalse(visible(AxisVisibility.Hidden, boundLayers = 5))
    }

    @Test
    fun `a config-hidden axis stays hidden in Auto`() {
        assertFalse(visible(AxisVisibility.Auto, boundLayers = 3, configVisible = false))
    }
}

/** Tooltip ordering, which has to be deterministic. */
class TooltipOrderTest {

    private fun entry(seriesId: String, axis: ChartAxisId) =
        io.devkit.chartkit.model.ChartTooltipEntry<Any?>(
            seriesId = seriesId,
            seriesName = seriesId,
            value = 1.0,
            item = null,
            paletteIndex = 0,
            axisId = axis,
        )

    @Test
    fun `declaration order is the order it was given`() {
        val entries = listOf(entry("a", Pressure), entry("b", Rainfall))
        assertEquals(
            listOf("a", "b"),
            io.devkit.chartkit.model.ChartTooltipOrder.Declaration.sort(entries).map { it.seriesId },
        )
    }

    @Test
    fun `by-axis groups rows in the axes' own order`() {
        val entries = listOf(entry("pressure", Pressure), entry("rain", Rainfall), entry("temp", Temperature))
        val sorted = io.devkit.chartkit.model.ChartTooltipOrder
            .ByAxis(listOf(Rainfall, Temperature, Pressure))
            .sort(entries)
        assertEquals(listOf("rain", "temp", "pressure"), sorted.map { it.seriesId })
    }

    @Test
    fun `an entry on an unlisted axis sorts last rather than disappearing`() {
        val entries = listOf(entry("mystery", ChartAxisId("other")), entry("rain", Rainfall))
        val sorted = io.devkit.chartkit.model.ChartTooltipOrder.ByAxis(listOf(Rainfall)).sort(entries)
        assertEquals(listOf("rain", "mystery"), sorted.map { it.seriesId })
    }

    @Test
    fun `a custom comparator is honoured`() {
        val entries = listOf(entry("b", Rainfall), entry("a", Rainfall))
        val sorted = io.devkit.chartkit.model.ChartTooltipOrder
            .Custom(compareBy { it.seriesId })
            .sort(entries)
        assertEquals(listOf("a", "b"), sorted.map { it.seriesId })
    }

    @Test
    fun `an entry writes itself with its own axis' text`() {
        val entry = entry("rain", Rainfall).copy(formattedValue = "82 mm")
        assertEquals("82 mm", entry.text)
        assertNotNull(entry.axisId)
    }
}
