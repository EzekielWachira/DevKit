package io.devkit.chartkit

import io.devkit.chartkit.flow.ChordLayout
import io.devkit.chartkit.flow.ChordValidation
import io.devkit.chartkit.flow.buildChordMatrix
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

private class Region(val code: String, val name: String = code)
private class Move(val from: String, val to: String, val people: Double)

private val regions = listOf(
    Region("eu", "Europe"),
    Region("as", "Asia"),
    Region("af", "Africa"),
    Region("am", "Americas"),
)

private fun matrix(
    moves: List<Move>,
    groups: List<Region> = regions,
    validation: ChordValidation = ChordValidation.Drop,
) = buildChordMatrix(
    groups = groups,
    flows = moves,
    groupId = { it.code },
    groupLabel = { it.name },
    source = { it.from },
    target = { it.to },
    value = { it.people },
    validation = validation,
)

private val exchange = listOf(
    Move("eu", "as", 100.0),
    Move("as", "eu", 60.0),
    Move("af", "eu", 40.0),
)

/** Building a chord matrix from the caller's own objects. */
class ChordMatrixTest {

    @Test
    fun `a group's total counts both directions`() {
        val result = matrix(exchange)
        val europe = result.groups.first { it.id == "eu" }

        assertEquals(100.0, europe.outgoing, 1e-9)
        assertEquals(100.0, europe.incoming, 1e-9)
        assertEquals(200.0, europe.total, 1e-9)
    }

    @Test
    fun `a group that only receives is still sized by what it receives`() {
        // The distinguishing choice against the Circos convention, where a group
        // is sized by its row sum and a receive-only group collapses to nothing.
        val result = matrix(listOf(Move("eu", "af", 50.0)))
        val africa = result.groups.first { it.id == "af" }

        assertEquals(0.0, africa.outgoing, 1e-9)
        assertEquals(50.0, africa.total, 1e-9)
    }

    @Test
    fun `the caller's own objects come back on the group and the flow`() {
        val moves = listOf(Move("eu", "as", 10.0))
        val result = matrix(moves)

        assertTrue(result.groups.first().item is Region)
        assertTrue(result.flows.first().item is Move)
        assertEquals(moves.first(), result.flows.first().item)
    }

    @Test
    fun `a flow naming an unknown group is dropped and counted`() {
        val result = matrix(listOf(Move("eu", "atlantis", 10.0), Move("eu", "as", 5.0)))

        assertEquals(1, result.flows.size)
        assertEquals(1, result.unknownReferences)
    }

    @Test
    fun `a flow naming an unknown group throws under Reject`() {
        val thrown = runCatching {
            matrix(listOf(Move("eu", "atlantis", 10.0)), validation = ChordValidation.Reject)
        }.exceptionOrNull()

        assertNotNull(thrown)
        assertTrue(thrown!!.message!!.contains("atlantis"))
    }

    @Test
    fun `weights that cannot be drawn are dropped and counted`() {
        val result = matrix(
            listOf(
                Move("eu", "as", 0.0),
                Move("eu", "af", -5.0),
                Move("as", "af", Double.NaN),
                Move("af", "am", 7.0),
            ),
        )

        assertEquals(1, result.flows.size)
        assertEquals(3, result.invalidValues)
    }

    @Test
    fun `a self-flow is kept rather than cut as a cycle`() {
        // The difference from a Sankey graph, which has to order its nodes into
        // columns and so cannot admit one.
        val result = matrix(listOf(Move("eu", "eu", 30.0)))

        assertEquals(1, result.flows.size)
        assertTrue(result.flows.first().isSelfFlow)
        assertEquals(60.0, result.groups.first { it.id == "eu" }.total, 1e-9)
    }

    @Test
    fun `a duplicate group id resolves to the first declaration`() {
        val duplicated = regions + Region("eu", "Europe again")
        val result = matrix(listOf(Move("eu", "as", 10.0)), groups = duplicated)

        assertEquals(0, result.flows.first().sourceIndex)
    }

    @Test
    fun `neighbours reach both ways and include the group itself`() {
        val result = matrix(exchange)
        val europe = result.groups.first { it.id == "eu" }.index

        assertEquals(setOf(0, 1, 2), result.neighbours(europe))
    }

    @Test
    fun `an empty matrix reports itself empty`() {
        assertTrue(matrix(emptyList()).isEmpty)
        assertTrue(matrix(exchange, groups = emptyList()).isEmpty)
    }
}

/** Angular layout: what each group and each ribbon actually occupies. */
class ChordLayoutTest {

    private fun layout(
        moves: List<Move> = exchange,
        startAngle: Float = 0f,
        sweepAngle: Float = PolarGeometry.FULL_CIRCLE,
        direction: PolarDirection = PolarDirection.Clockwise,
        padAngle: Float = 0f,
    ) = ChordLayout.layout(matrix(moves), startAngle, sweepAngle, direction, padAngle)

    @Test
    fun `with no padding the groups fill the circle exactly`() {
        val geometry = layout()
        val covered = geometry.arcs.sumOf { abs(it.sweepAngle).toDouble() }

        assertEquals(360.0, covered, 1e-3)
    }

    @Test
    fun `a group's share of the circle is its share of the flow`() {
        val geometry = layout()
        val result = matrix(exchange)
        val grandTotal = result.groups.sumOf { it.total }
        val europe = result.groups.first { it.id == "eu" }
        val arc = geometry.arcFor(europe.index)!!

        assertEquals(europe.total / grandTotal * 360.0, abs(arc.sweepAngle).toDouble(), 1e-3)
    }

    @Test
    fun `a ribbon's two ends are the same width, because they are one value`() {
        // The property the Circos convention gives up. Both ends span the
        // flow's own value, so a ribbon carries exactly one number.
        layout().ribbons.forEach { ribbon ->
            assertEquals(abs(ribbon.sourceSweep), abs(ribbon.targetSweep), 1e-3f)
        }
    }

    @Test
    fun `the ends attached to a group exactly fill its arc`() {
        // What makes the angular widths readable as quantities: no slack, no
        // overlap, and no end spilling into the neighbouring group.
        val geometry = layout()
        geometry.arcs.forEach { arc ->
            val attached = geometry.ribbons.sumOf { ribbon ->
                var total = 0.0
                if (ribbon.sourceGroup == arc.groupIndex) total += abs(ribbon.sourceSweep).toDouble()
                if (ribbon.targetGroup == arc.groupIndex) total += abs(ribbon.targetSweep).toDouble()
                total
            }
            assertEquals(abs(arc.sweepAngle).toDouble(), attached, 1e-3)
        }
    }

    @Test
    fun `padding is taken out of the data, not added to the circle`() {
        val padded = layout(padAngle = 4f)
        val covered = padded.arcs.sumOf { abs(it.sweepAngle).toDouble() }

        // Four groups, a closed circle, so four gaps.
        assertEquals(360.0 - 4.0 * 4.0, covered, 1e-3)
    }

    @Test
    fun `a partial sweep needs one fewer gap than a full circle`() {
        val half = ChordLayout.layout(
            matrix(exchange),
            startAngle = 0f,
            sweepAngle = 180f,
            direction = PolarDirection.Clockwise,
            padAngle = 3f,
        )
        val covered = half.arcs.sumOf { abs(it.sweepAngle).toDouble() }

        // Four groups with two free ends: three gaps, not four.
        assertEquals(180.0 - 3.0 * 3.0, covered, 1e-3)
    }

    @Test
    fun `padding can never eat the whole circle`() {
        // Forty groups at ten degrees of padding would want four hundred
        // degrees of gap, and a diagram of nothing but gaps is worse than a
        // cramped one.
        val many = (0 until 40).map { Region("g$it") }
        val flows = (0 until 39).map { Move("g$it", "g${it + 1}", 1.0) }
        val geometry = ChordLayout.layout(
            buildChordMatrix(
                groups = many,
                flows = flows,
                groupId = { it.code },
                groupLabel = { it.name },
                source = { it.from },
                target = { it.to },
                value = { it.people },
            ),
            startAngle = 0f,
            sweepAngle = 360f,
            direction = PolarDirection.Clockwise,
            padAngle = 10f,
        )
        val covered = geometry.arcs.sumOf { abs(it.sweepAngle).toDouble() }

        assertTrue("groups were squeezed out entirely, covering $covered", covered > 200.0)
    }

    @Test
    fun `counter-clockwise mirrors every sweep and keeps the first group's start`() {
        val clockwise = layout()
        val counter = layout(direction = PolarDirection.CounterClockwise)

        assertEquals(clockwise.arcs.size, counter.arcs.size)
        // Only the first group shares a leading edge; after that the two
        // cursors walk away from each other, which is the whole point.
        assertEquals(clockwise.arcs.first().startAngle, counter.arcs.first().startAngle, 1e-3f)
        clockwise.arcs.zip(counter.arcs).forEach { (forward, backward) ->
            assertEquals(forward.sweepAngle, -backward.sweepAngle, 1e-3f)
        }
    }

    @Test
    fun `a group no flow touches gets a zero-width arc rather than dropping out`() {
        // The Americas take part in none of these moves. The arc stays so the
        // list keeps index parity with the matrix's groups; `isDrawable` is
        // what stops it being painted.
        val geometry = layout()
        val americas = matrix(exchange).groups.first { it.id == "am" }
        val arc = geometry.arcFor(americas.index)

        assertNotNull(arc)
        assertEquals(0f, arc!!.sweepAngle, 1e-6f)
        assertFalse(arc.isDrawable)
    }

    @Test
    fun `the first group begins where the caller said`() {
        val geometry = layout(startAngle = 90f)

        assertEquals(90f, geometry.arcs.first().startAngle, 1e-3f)
    }

    @Test
    fun `the layout is deterministic`() {
        // A diagram that reshuffled itself between two runs of the same data
        // would be unusable and untestable.
        val first = layout(padAngle = 2f)
        val second = layout(padAngle = 2f)

        first.ribbons.zip(second.ribbons).forEach { (a, b) ->
            assertEquals(a.sourceStart, b.sourceStart, 1e-6f)
            assertEquals(a.targetStart, b.targetStart, 1e-6f)
        }
    }

    @Test
    fun `a self-flow takes two segments of its own group`() {
        val geometry = layout(listOf(Move("eu", "eu", 30.0)))
        val ribbon = geometry.ribbons.single()

        assertEquals(ribbon.sourceGroup, ribbon.targetGroup)
        // Two distinct segments, not one drawn twice.
        assertTrue(abs(ribbon.sourceStart - ribbon.targetStart) > 1e-3f)
    }

    @Test
    fun `a matrix with no flow lays out nothing`() {
        val geometry = layout(emptyList())

        assertTrue(geometry.arcs.isEmpty())
        assertTrue(geometry.ribbons.isEmpty())
    }
}

/** Where a tap lands: the shared outline behind both drawing and hit testing. */
class ChordHitTestTest {

    private val center = ChartOffset(100f, 100f)
    private val radius = 80f
    private val geometry = ChordLayout.layout(
        matrix(exchange),
        startAngle = 0f,
        sweepAngle = 360f,
        direction = PolarDirection.Clockwise,
        padAngle = 2f,
    )

    @Test
    fun `the group under an angle is the one whose arc spans it`() {
        geometry.arcs.filter { it.isDrawable }.forEach { arc ->
            val found = ChordLayout.groupAt(geometry.arcs, arc.midAngle, PolarDirection.Clockwise)
            assertEquals(arc.groupIndex, found)
        }
    }

    @Test
    fun `a group with no flow is never the answer, even at its own angle`() {
        // Its arc has no width, so there is no angle that belongs to it.
        val americas = matrix(exchange).groups.first { it.id == "am" }.index
        val arc = geometry.arcFor(americas)!!
        val found = ChordLayout.groupAt(geometry.arcs, arc.midAngle, PolarDirection.Clockwise)

        assertTrue(found != americas)
    }

    @Test
    fun `an angle inside a padding gap belongs to no group`() {
        val first = geometry.arcs[0]
        // Just past the first arc's trailing edge, inside the gap that follows.
        val inGap = PolarGeometry.normalizeAngle(first.endAngle + 1f)
        val found = ChordLayout.groupAt(geometry.arcs, inGap, PolarDirection.Clockwise)

        assertEquals(-1, found)
    }

    @Test
    fun `a ribbon's outline is closed and non-degenerate`() {
        geometry.ribbons.forEach { ribbon ->
            val outline = ChordLayout.ribbonOutline(ribbon, center, radius)
            assertTrue("outline has ${outline.size} points", outline.size >= 3)
            assertTrue(outline.all { it.x.isFinite() && it.y.isFinite() })
        }
    }

    @Test
    fun `a point on the ribbon's own end is inside it`() {
        val ribbon = geometry.ribbons.first()
        val outline = ChordLayout.ribbonOutline(ribbon, center, radius)
        // A shade inside the rim, halfway along the source end.
        val probe = PolarGeometry.pointOnCircle(
            center,
            radius - 2f,
            ribbon.sourceStart + ribbon.sourceSweep / 2f,
        )

        assertTrue(ChordLayout.contains(outline, probe))
    }

    @Test
    fun `a point well outside the circle is in no ribbon`() {
        geometry.ribbons.forEach { ribbon ->
            val outline = ChordLayout.ribbonOutline(ribbon, center, radius)
            assertFalse(ChordLayout.contains(outline, ChartOffset(1000f, 1000f)))
        }
    }

    @Test
    fun `an empty or degenerate outline contains nothing`() {
        assertFalse(ChordLayout.contains(emptyList(), center))
        assertFalse(ChordLayout.contains(listOf(center, center), center))
    }

    @Test
    fun `a zero radius produces no outline rather than a pile of points at the centre`() {
        val outline = ChordLayout.ribbonOutline(geometry.ribbons.first(), center, 0f)

        assertTrue(outline.isEmpty())
    }

    @Test
    fun `arcFor answers for a real group and refuses an unknown one`() {
        assertNotNull(geometry.arcFor(0))
        assertNull(geometry.arcFor(99))
    }
}
