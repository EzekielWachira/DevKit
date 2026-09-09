package io.devkit.chartkit

import io.devkit.chartkit.charts.GeoDuplicatePolicy
import io.devkit.chartkit.charts.joinGeoData
import io.devkit.chartkit.geo.GeoFeature
import io.devkit.chartkit.geo.GeoJson
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.scale.quantileBreaks
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A consumer's record, deliberately not a ChartKit type. */
private data class GeoReading(val code: String, val rate: Double?)

/**
 * The thematic join and the colour scales a map is shaded with.
 *
 * The join is where a choropleth actually fails in practice — a key mismatch
 * produces a blank map with no error — so every failure mode has a test that
 * says what the caller is told.
 */
class GeoJoinTest {

    private fun regions(vararg codes: String): io.devkit.chartkit.geo.GeoFeatureCollection {
        val features = codes.mapIndexed { index, code ->
            val x = index.toDouble()
            """
            { "type": "Feature", "id": "$code",
              "properties": { "code": "$code", "name": "Region $code" },
              "geometry": { "type": "Polygon", "coordinates":
                [[[$x,0],[${x + 1},0],[${x + 1},1],[$x,1],[$x,0]]] } }
            """.trimIndent()
        }
        return GeoJson.parse(
            """{ "type": "FeatureCollection", "features": [${features.joinToString(",")}] }""",
        )
    }

    private val key: (GeoFeature) -> String? = { it.properties.string("code") }

    private fun join(
        codes: List<String>,
        data: List<GeoReading>,
        policy: GeoDuplicatePolicy = GeoDuplicatePolicy.First,
    ) = joinGeoData(
        geometry = regions(*codes.toTypedArray()),
        data = data,
        featureKey = key,
        dataKey = { it.code },
        value = { it.rate },
        duplicatePolicy = policy,
    )

    @Test
    fun everyRecordFindsItsRegion() {
        val result = join(
            listOf("A", "B"),
            listOf(GeoReading("A", 1.0), GeoReading("B", 2.0)),
        )

        assertEquals(1.0, result.rows[0]!!.value!!, 1e-9)
        assertEquals(2.0, result.rows[1]!!.value!!, 1e-9)
        assertEquals(2, result.report.matched)
        assertTrue(result.report.isComplete)
    }

    @Test
    fun rowsFollowFeatureOrderNotDataOrder() {
        val result = join(
            listOf("A", "B", "C"),
            listOf(GeoReading("C", 3.0), GeoReading("A", 1.0), GeoReading("B", 2.0)),
        )

        // The layer addresses features by index, so a join that returned rows
        // in data order would shade the right values onto the wrong regions —
        // a map that is confidently, invisibly wrong.
        assertEquals(listOf(1.0, 2.0, 3.0), result.rows.map { it!!.value })
    }

    @Test
    fun aRegionWithNoRecordIsMissingRatherThanZero() {
        val result = join(listOf("A", "B"), listOf(GeoReading("A", 5.0)))

        assertNull(result.rows[1])
        assertEquals(listOf("B"), result.report.unmatchedFeatureKeys)
        assertFalse(result.report.isComplete)
    }

    @Test
    fun aRecordWithNoRegionIsReported() {
        val result = join(listOf("A"), listOf(GeoReading("A", 1.0), GeoReading("ZZ", 9.0)))

        // The commonest real failure: "CA" against "California", "06" against
        // "6". Without this the caller sees a blank region and no reason.
        assertEquals(listOf("ZZ"), result.report.unmatchedDataKeys)
    }

    @Test
    fun aNullValueIsMissingRatherThanZero() {
        val result = join(listOf("A"), listOf(GeoReading("A", null)))

        assertNotNull(result.rows[0])
        assertNull(result.rows[0]!!.value)
    }

    @Test
    fun aNonFiniteValueIsMissing() {
        val result = join(listOf("A"), listOf(GeoReading("A", Double.NaN)))

        assertNull(result.rows[0]!!.value)
    }

    @Test
    fun duplicatesKeepTheFirstByDefaultAndAreReported() {
        val result = join(
            listOf("A"),
            listOf(GeoReading("A", 1.0), GeoReading("A", 9.0)),
        )

        assertEquals(1.0, result.rows[0]!!.value!!, 1e-9)
        assertEquals(listOf("A"), result.report.duplicateKeys)
    }

    @Test
    fun theLastPolicyKeepsTheLast() {
        val result = join(
            listOf("A"),
            listOf(GeoReading("A", 1.0), GeoReading("A", 9.0)),
            GeoDuplicatePolicy.Last,
        )

        assertEquals(9.0, result.rows[0]!!.value!!, 1e-9)
    }

    @Test
    fun theSumPolicyAdds() {
        val result = join(
            listOf("A"),
            listOf(GeoReading("A", 1.0), GeoReading("A", 2.0), GeoReading("A", 3.0)),
            GeoDuplicatePolicy.Sum,
        )

        assertEquals(6.0, result.rows[0]!!.value!!, 1e-9)
    }

    @Test
    fun summingTwoAbsentValuesStaysAbsent() {
        val result = join(
            listOf("A"),
            listOf(GeoReading("A", null), GeoReading("A", null)),
            GeoDuplicatePolicy.Sum,
        )

        // Not zero. Two counties that reported nothing did not jointly report
        // nothing-as-a-number.
        assertNull(result.rows[0]!!.value)
    }

    @Test
    fun theRejectPolicyNamesTheOffendingKey() {
        val failure = runCatching {
            join(listOf("A"), listOf(GeoReading("A", 1.0), GeoReading("A", 2.0)), GeoDuplicatePolicy.Reject)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("\"A\""))
    }

    @Test
    fun anEmptyDataSetLeavesEveryRegionUnmeasured() {
        val result = join(listOf("A", "B", "C"), emptyList())

        assertTrue(result.rows.all { it == null })
        assertEquals(0, result.report.matched)
        assertEquals(3, result.report.unmatchedFeatureKeys.size)
    }

    // Colour scales -------------------------------------------------------

    @Test
    fun quantileBreaksSplitTheSampleEvenly() {
        val values = (1..100).map { it.toDouble() as Double? }

        val breaks = quantileBreaks(values, groups = 4)

        assertEquals(3, breaks.size)
        assertEquals(26.0, breaks[0], 1e-9)
        assertEquals(51.0, breaks[1], 1e-9)
        assertEquals(76.0, breaks[2], 1e-9)
    }

    @Test
    fun quantileBreaksAreValuesThatExistInTheData() {
        val values = listOf<Double?>(10.0, 10.0, 10.0, 40.0, 90.0)

        val breaks = quantileBreaks(values, groups = 5)

        // An interpolated break of 41.7 between two counties at 40 and 90 is a
        // number no county has, and a legend that prints it invites the reader
        // to look for it.
        assertTrue(breaks.all { it in values })
    }

    @Test
    fun tiedValuesCollapseIntoFewerBands() {
        val values = List(9) { 5.0 as Double? } + listOf(100.0)

        val scale = ColorScale.Quantile(values, listOf(Color.White, Color.Black), groups = 5)

        // Five bands over one repeated value would produce four bands nothing
        // can ever fall into.
        assertTrue(scale.bandCount < 5)
        assertTrue(scale.bandCount >= 2)
    }

    @Test
    fun aQuantileScaleGivesMissingValuesNoColourAtAll() {
        val scale = ColorScale.Quantile(
            listOf(1.0, 2.0, 3.0, 4.0),
            listOf(Color.White, Color.Black),
        )

        assertNull(scale.colorAt(null))
        assertNull(scale.colorAt(Double.NaN))
        assertNotNull(scale.colorAt(2.0))
    }

    @Test
    fun aQuantileScaleOverNothingPlacesNothing() {
        val scale = ColorScale.Quantile(listOf(null, null), listOf(Color.White, Color.Black))

        assertEquals(0, scale.bandCount)
        assertNull(scale.colorAt(1.0))
        assertTrue(scale.legendStops().isEmpty())
    }

    @Test
    fun quantileBandsAscendAndAreDistinguishable() {
        val scale = ColorScale.Quantile(
            (1..50).map { it.toDouble() as Double? },
            listOf(Color.White, Color.Black),
            groups = 5,
        )

        val low = scale.colorAt(1.0)!!
        val high = scale.colorAt(50.0)!!

        assertEquals(5, scale.bandCount)
        assertTrue(low.red > high.red)
    }

    @Test
    fun quantileLegendStopsReadAsRanges() {
        val scale = ColorScale.Quantile(
            (1..20).map { it.toDouble() as Double? },
            listOf(Color.White, Color.Black),
            groups = 4,
        )

        val labels = scale.legendStops().map { it.label }

        assertEquals(4, labels.size)
        assertTrue(labels.first()!!.startsWith("<"))
        assertTrue(labels.last()!!.endsWith("and above"))
    }
}
