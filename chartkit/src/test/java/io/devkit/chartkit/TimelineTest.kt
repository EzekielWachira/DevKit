package io.devkit.chartkit

import io.devkit.chartkit.timeline.TimelineDependency
import io.devkit.chartkit.timeline.buildTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOUR = 3_600_000L

private class Booking(
    val guest: String,
    val room: String,
    val from: Long,
    val to: Long?,
    val progress: Double? = null,
    val gate: Boolean = false,
)

/**
 * Timeline normalisation: lanes, rows, overlaps and the malformed intervals a
 * real dataset contains.
 */
class TimelineModelTest {

    private fun model(vararg bookings: Booking, stack: Boolean = true) = buildTimeline(
        data = bookings.toList(),
        start = { it.from },
        label = { it.guest },
        end = { it.to },
        lane = { it.room },
        progress = { it.progress },
        milestone = { it.gate },
        stackOverlaps = stack,
    )

    @Test
    fun `lanes keep first-appearance order rather than being sorted`() {
        val result = model(
            Booking("A", "Room B", 0, HOUR),
            Booking("B", "Room A", 0, HOUR),
        )
        assertEquals(listOf("Room B", "Room A"), result.lanes)
    }

    @Test
    fun `a point event has no end and no duration`() {
        val result = model(Booking("A", "Room A", HOUR, null))
        val entry = result.entries.single()
        assertNull(entry.end)
        assertTrue(!entry.isInterval)
        assertEquals(0L, entry.durationMillis)
    }

    @Test
    fun `an interval keeps its span`() {
        val entry = model(Booking("A", "Room A", HOUR, 4 * HOUR)).entries.single()
        assertTrue(entry.isInterval)
        assertEquals(3 * HOUR, entry.durationMillis)
    }

    @Test
    fun `an end before the start is read as a point event`() {
        // A data error, not a negative bar: only the start is unambiguous.
        val entry = model(Booking("A", "Room A", 4 * HOUR, HOUR)).entries.single()
        assertNull(entry.end)
    }

    @Test
    fun `overlapping entries in one lane get separate rows`() {
        val result = model(
            Booking("A", "Room A", 0, 3 * HOUR),
            Booking("B", "Room A", HOUR, 4 * HOUR),
        )
        assertEquals(2, result.rowsPerLane[0])
        assertTrue(result.rows[0] != result.rows[1])
    }

    @Test
    fun `non-overlapping entries in one lane share a row`() {
        val result = model(
            Booking("A", "Room A", 0, HOUR),
            Booking("B", "Room A", 2 * HOUR, 3 * HOUR),
        )
        assertEquals(1, result.rowsPerLane[0])
        assertEquals(result.rows[0], result.rows[1])
    }

    @Test
    fun `stacking can be turned off`() {
        val result = model(
            Booking("A", "Room A", 0, 3 * HOUR),
            Booking("B", "Room A", HOUR, 4 * HOUR),
            stack = false,
        )
        assertEquals(1, result.rowsPerLane[0])
    }

    @Test
    fun `rows are numbered across every lane`() {
        val result = model(
            Booking("A", "Room A", 0, 3 * HOUR),
            Booking("B", "Room A", HOUR, 4 * HOUR),
            Booking("C", "Room B", 0, HOUR),
        )
        assertEquals(3, result.rowCount)
        assertEquals(2, result.rowOffset(1))
        assertEquals(2, result.absoluteRow(2))
    }

    @Test
    fun `the extent covers every entry`() {
        val extent = model(
            Booking("A", "Room A", HOUR, 3 * HOUR),
            Booking("B", "Room B", 5 * HOUR, null),
        ).extent()!!
        assertEquals(HOUR, extent.first)
        assertEquals(5 * HOUR, extent.last)
    }

    @Test
    fun `progress is clamped into zero to one`() {
        val result = buildTimeline(
            data = listOf(Booking("A", "Room A", 0, HOUR, progress = 1.8)),
            start = { it.from },
            label = { it.guest },
            end = { it.to },
            lane = { it.room },
            progress = { it.progress },
        )
        assertEquals(1.0, result.entries.single().progress!!, 1e-9)
    }

    @Test
    fun `a milestone is marked whether or not it has an end`() {
        val result = model(Booking("Launch", "Room A", HOUR, null, gate = true))
        assertTrue(result.entries.single().isMilestone)
    }

    @Test
    fun `entries with no lane accessor share one lane`() {
        val result = buildTimeline(
            data = listOf(Booking("A", "x", 0, HOUR), Booking("B", "y", 2 * HOUR, 3 * HOUR)),
            start = { it.from },
            label = { it.guest },
            end = { it.to },
        )
        assertEquals(1, result.lanes.size)
    }

    @Test
    fun `dependencies are carried through`() {
        val result = buildTimeline(
            data = listOf(Booking("A", "x", 0, HOUR), Booking("B", "x", 2 * HOUR, 3 * HOUR)),
            start = { it.from },
            label = { it.guest },
            end = { it.to },
            dependencies = listOf(TimelineDependency(0, 1)),
        )
        assertEquals(1, result.dependencies.size)
    }

    @Test
    fun `an empty timeline has no extent`() {
        val result = buildTimeline(
            data = emptyList<Booking>(),
            start = { it.from },
            label = { it.guest },
        )
        assertTrue(result.isEmpty)
        assertNull(result.extent())
    }
}
