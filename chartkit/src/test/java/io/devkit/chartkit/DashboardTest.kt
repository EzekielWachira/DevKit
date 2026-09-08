package io.devkit.chartkit

import io.devkit.chartkit.export.ChartSvg
import io.devkit.chartkit.geometry.ChartInsets
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.scene.ChartSceneNode
import io.devkit.chartkit.scene.PaintStyle
import io.devkit.chartkit.scene.TextAnchor
import io.devkit.chartkit.scene.buildChartScene
import io.devkit.chartkit.viewport.ChartViewport
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The navigator's window arithmetic.
 *
 * Dragging a window is [ChartViewport.startingAt] and tapping to recentre is
 * [ChartViewport.centredOn]; both have to keep the window the same *width* when
 * they hit an end, because a window that narrowed as it was dragged would change
 * how much data is on screen for a gesture that only asked to move.
 */
class NavigatorViewportTest {

    @Test
    fun `a window starts where it was asked to`() {
        val window = ChartViewport.startingAt(0.25, 0.5)
        assertEquals(0.25, window.start, 1e-9)
        assertEquals(0.75, window.end, 1e-9)
    }

    @Test
    fun `dragging past the end slides rather than shrinking`() {
        val window = ChartViewport.startingAt(0.9, 0.5)
        assertEquals(0.5, window.width, 1e-9)
        assertEquals(1.0, window.end, 1e-9)
    }

    @Test
    fun `dragging past the start slides too`() {
        val window = ChartViewport.startingAt(-0.4, 0.3)
        assertEquals(0.0, window.start, 1e-9)
        assertEquals(0.3, window.width, 1e-9)
    }

    @Test
    fun `recentring puts the window's middle on the point`() {
        val window = ChartViewport.centredOn(0.5, 0.2)
        assertEquals(0.4, window.start, 1e-9)
        assertEquals(0.6, window.end, 1e-9)
    }

    @Test
    fun `recentring near an edge is clamped, keeping the width`() {
        val window = ChartViewport.centredOn(0.02, 0.4)
        assertEquals(0.0, window.start, 1e-9)
        assertEquals(0.4, window.width, 1e-9)
    }

    @Test
    fun `a window wider than the domain becomes the whole domain`() {
        val window = ChartViewport.startingAt(0.3, 2.0)
        assertEquals(0.0, window.start, 1e-9)
        assertEquals(1.0, window.end, 1e-9)
    }

    @Test
    fun `resizing an edge uses the ordinary between factory`() {
        val window = ChartViewport.between(0.7, 0.2)
        assertEquals(0.2, window.start, 1e-9)
        assertEquals(0.7, window.end, 1e-9)
    }
}

/**
 * Plot alignment.
 *
 * Two stacked charts whose value labels differ in width need the same plot
 * edges. The exchange has to settle in one extra frame and produce no feedback:
 * a chart's *natural* gutter does not depend on the padding added outside it, so
 * reporting the same value twice must change nothing.
 */
class PlotAlignmentTest {

    @Test
    fun `the group agrees on the largest gutter`() {
        val alignment = io.devkit.chartkit.state.ChartPlotAlignment()
        alignment.report("a", ChartInsets(left = 20f, right = 4f))
        alignment.report("b", ChartInsets(left = 48f, right = 4f))
        assertEquals(48f, alignment.insets.left, 0.01f)
        assertEquals(4f, alignment.insets.right, 0.01f)
    }

    @Test
    fun `each chart is told only the difference`() {
        val alignment = io.devkit.chartkit.state.ChartPlotAlignment()
        alignment.report("a", ChartInsets(left = 20f))
        alignment.report("b", ChartInsets(left = 48f))
        assertEquals(28f, alignment.extraFor(ChartInsets(left = 20f)).left, 0.01f)
        assertEquals(0f, alignment.extraFor(ChartInsets(left = 48f)).left, 0.01f)
    }

    @Test
    fun `re-reporting the same measurement changes nothing`() {
        // The property that stops the exchange oscillating: a repeated report is
        // a no-op, so the second frame is the last one.
        val alignment = io.devkit.chartkit.state.ChartPlotAlignment()
        alignment.report("a", ChartInsets(left = 20f))
        val first = alignment.insets
        alignment.report("a", ChartInsets(left = 20.2f))
        assertEquals(first.left, alignment.insets.left, 0.001f)
    }

    @Test
    fun `a chart that leaves the composition stops widening the others`() {
        val alignment = io.devkit.chartkit.state.ChartPlotAlignment()
        alignment.report("a", ChartInsets(left = 20f))
        alignment.report("b", ChartInsets(left = 48f))
        alignment.forget("b")
        assertEquals(20f, alignment.insets.left, 0.01f)
        assertEquals(1, alignment.memberCount)
    }

    @Test
    fun `an empty group asks for nothing`() {
        assertEquals(ChartInsets.Zero, io.devkit.chartkit.state.ChartPlotAlignment().insets)
    }
}

/**
 * Cross-filtering.
 *
 * ChartKit coordinates which selections are active and does no filtering of its
 * own. The behaviours that matter are that a dimension replaces within itself,
 * that dimensions combine with **and**, and that toggling the same key twice
 * returns to where it started — the property that makes a feedback loop
 * impossible.
 */
class ChartFilterStateTest {

    private class Order(val region: String, val month: String)

    private val orders = listOf(
        Order("EMEA", "Jan"),
        Order("EMEA", "Feb"),
        Order("AMER", "Jan"),
        Order("APAC", "Mar"),
    )

    private fun state(multi: Boolean = false) =
        io.devkit.chartkit.state.ChartFilterState(multi)

    @Test
    fun `a fresh state filters nothing`() {
        val filters = state()
        assertTrue(filters.isEmpty)
        assertEquals(orders, filters.apply(orders, "region") { it.region })
    }

    @Test
    fun `toggling once selects and twice clears`() {
        val filters = state()
        val filter = io.devkit.chartkit.state.ChartFilter("region", "EMEA")
        filters.toggle(filter)
        assertTrue(filters.isActive("region", "EMEA"))
        filters.toggle(filter)
        assertTrue(filters.isEmpty)
    }

    @Test
    fun `single select replaces within a dimension`() {
        val filters = state()
        filters.toggle(io.devkit.chartkit.state.ChartFilter("region", "EMEA"))
        filters.toggle(io.devkit.chartkit.state.ChartFilter("region", "AMER"))
        assertEquals(listOf<Any?>("AMER"), filters.keysFor("region"))
    }

    @Test
    fun `multi select accumulates within a dimension`() {
        val filters = state(multi = true)
        filters.toggle(io.devkit.chartkit.state.ChartFilter("region", "EMEA"))
        filters.toggle(io.devkit.chartkit.state.ChartFilter("region", "AMER"))
        assertEquals(2, filters.keysFor("region").size)
    }

    @Test
    fun `selecting one dimension leaves the others alone`() {
        val filters = state()
        filters.toggle(io.devkit.chartkit.state.ChartFilter("region", "EMEA"))
        filters.toggle(io.devkit.chartkit.state.ChartFilter("month", "Jan"))
        assertTrue(filters.hasFilter("region"))
        assertTrue(filters.hasFilter("month"))
    }

    @Test
    fun `dimensions combine with and, keys within one with or`() {
        val filters = state(multi = true)
        filters.toggle(io.devkit.chartkit.state.ChartFilter("region", "EMEA"))
        filters.toggle(io.devkit.chartkit.state.ChartFilter("region", "AMER"))
        filters.toggle(io.devkit.chartkit.state.ChartFilter("month", "Jan"))
        val result = filters.apply(
            orders,
            "region" to { order: Order -> order.region },
            "month" to { order: Order -> order.month },
        )
        assertEquals(2, result.size)
        assertTrue(result.all { it.month == "Jan" })
    }

    @Test
    fun `a null key clears the dimension, which is what a tap on empty space means`() {
        val filters = state()
        filters.toggle(io.devkit.chartkit.state.ChartFilter("region", "EMEA"))
        filters.toggle(io.devkit.chartkit.state.ChartFilter("region", null))
        assertTrue(filters.isEmpty)
    }

    @Test
    fun `an unfiltered dimension does not narrow the data`() {
        val filters = state()
        filters.toggle(io.devkit.chartkit.state.ChartFilter("region", "EMEA"))
        assertEquals(orders, filters.apply(orders, "month") { it.month })
    }

    @Test
    fun `the source is carried so a chart can recognise its own publication`() {
        val filters = state()
        val chartId = Any()
        filters.toggle(io.devkit.chartkit.state.ChartFilter("region", "EMEA", source = chartId))
        assertEquals(chartId, filters.filters.single().source)
    }
}

/** The scene model and its SVG serialisation. */
class ChartSceneTest {

    private val scene = buildChartScene(width = 200f, height = 100f, background = Color.White) {
        group("grid") {
            add(
                ChartSceneNode.Line(
                    from = ChartOffset(0f, 50f),
                    to = ChartOffset(200f, 50f),
                    color = Color(0xFF888888),
                    strokeWidth = 1f,
                ),
            )
        }
        group("series") {
            add(
                ChartSceneNode.Path(
                    points = listOf(ChartOffset(0f, 90f), ChartOffset(100f, 40f), ChartOffset(200f, 10f)),
                    color = Color(0xFF0055FF),
                    style = PaintStyle.Stroke,
                    strokeWidth = 2f,
                ),
            )
            add(ChartSceneNode.Circle(ChartOffset(100f, 40f), 3f, Color(0xFF0055FF)))
        }
        add(
            ChartSceneNode.Rect(
                bounds = ChartRect(10f, 10f, 40f, 30f),
                color = Color(0x33FF0000),
                cornerRadius = 2f,
            ),
        )
        add(
            ChartSceneNode.Arc(
                center = ChartOffset(100f, 50f),
                innerRadius = 10f,
                outerRadius = 20f,
                startAngle = 0f,
                sweepAngle = 90f,
                color = Color(0xFF00AA00),
            ),
        )
        add(
            ChartSceneNode.Text(
                text = "Revenue & \"growth\"",
                position = ChartOffset(5f, 95f),
                color = Color.Black,
                fontSizePx = 11f,
                anchor = TextAnchor.Start,
            ),
        )
    }

    @Test
    fun `the scene keeps its size`() {
        assertEquals(200f, scene.width, 0f)
        assertEquals(100f, scene.height, 0f)
    }

    @Test
    fun `a scene with nothing unexported is complete`() {
        assertTrue(scene.isComplete)
    }

    @Test
    fun `flattening walks out of the groups`() {
        assertEquals(6, scene.flatten().size)
    }

    @Test
    fun `an unexported layer is reported rather than hidden`() {
        val partial = buildChartScene(10f, 10f) { unexported("candles-0") }
        assertTrue(!partial.isComplete)
        assertEquals(listOf("candles-0"), partial.unexportedLayers)
    }

    @Test
    fun `the SVG is well-formed XML`() {
        val svg = ChartSvg.render(scene, title = "Revenue")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(svg.byteInputStream())
        assertEquals("svg", document.documentElement.tagName)
    }

    @Test
    fun `the document carries the scene's dimensions and a view box`() {
        val svg = ChartSvg.render(scene)
        assertTrue(svg.contains("""width="200""""))
        assertTrue(svg.contains("""viewBox="0 0 200 100""""))
    }

    @Test
    fun `scaling changes the document size but not the coordinates`() {
        val svg = ChartSvg.render(scene, scale = 2f)
        assertTrue(svg.contains("""width="400""""))
        assertTrue(svg.contains("""viewBox="0 0 200 100""""))
    }

    @Test
    fun `every primitive becomes its own element`() {
        val svg = ChartSvg.render(scene)
        assertTrue("line", svg.contains("<line "))
        assertTrue("path", svg.contains("<path "))
        assertTrue("circle", svg.contains("<circle "))
        assertTrue("rect", svg.contains("<rect "))
        assertTrue("text", svg.contains("<text "))
    }

    @Test
    fun `groups become named g elements, so the output can be edited`() {
        val svg = ChartSvg.render(scene)
        assertTrue(svg.contains("""<g id="grid">"""))
        assertTrue(svg.contains("""<g id="series">"""))
    }

    @Test
    fun `text is escaped rather than breaking the document`() {
        val svg = ChartSvg.render(scene)
        assertTrue(svg.contains("Revenue &amp; &quot;growth&quot;"))
    }

    @Test
    fun `opacity is carried separately from the colour`() {
        // Eight-digit hex is CSS Color 4, which several vector editors ignore.
        val svg = ChartSvg.render(scene)
        assertTrue(svg.contains("fill-opacity="))
        assertTrue(!svg.contains("#33ff0000"))
    }

    @Test
    fun `a title becomes an accessible name`() {
        val svg = ChartSvg.render(scene, title = "Monthly revenue", description = "Six months")
        assertTrue(svg.contains("<title>Monthly revenue</title>"))
        assertTrue(svg.contains("<desc>Six months</desc>"))
    }

    @Test
    fun `an empty scene is still a valid document`() {
        val svg = ChartSvg.render(buildChartScene(10f, 10f) {})
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg.byteInputStream())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive scale is rejected`() {
        ChartSvg.render(scene, scale = 0f)
    }
}
