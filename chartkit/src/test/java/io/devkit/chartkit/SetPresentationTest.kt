package io.devkit.chartkit

import androidx.compose.ui.graphics.Color
import io.devkit.chartkit.accessibility.setDataTable
import io.devkit.chartkit.accessibility.setRelationshipTable
import io.devkit.chartkit.charts.SetRegionNaming
import io.devkit.chartkit.layer.set.blendSetColors
import io.devkit.chartkit.layer.set.defaultRegionName
import io.devkit.chartkit.set.SetAnalyzer
import io.devkit.chartkit.set.SetContainment
import io.devkit.chartkit.set.SetDefinition
import io.devkit.chartkit.set.SetIntersection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Colour blending, region naming and the accessibility tables.
 *
 * All three are things a reader either reads or hears, so all three are asserted
 * on their *output* rather than on a screenshot.
 */
class SetPresentationTest {

    private val data = SetAnalyzer.analyze(
        listOf(
            SetDefinition("android", "Android", 200.0),
            SetDefinition("ios", "iOS", 160.0),
        ),
        listOf(SetIntersection(setOf("android", "ios"), 70.0)),
    )

    // ---- blending --------------------------------------------------------

    @Test
    fun blendingIsOrderIndependent() {
        val red = Color(1f, 0f, 0f)
        val blue = Color(0f, 0f, 1f)

        // The property that makes the mode usable: the same region cannot have
        // two colours depending on which set was declared first. Successive
        // alpha compositing — the usual implementation — fails this.
        assertEquals(blendSetColors(listOf(red, blue)), blendSetColors(listOf(blue, red)))
    }

    @Test
    fun blendingTwoColoursGivesTheirMean() {
        val result = blendSetColors(listOf(Color(1f, 0f, 0f), Color(0f, 1f, 0f)))

        // Tolerance, not exactness: Compose stores an sRGB channel in eight
        // bits, so a mean of 0.5 comes back as 128/255.
        assertEquals(0.5f, result.red, 0.005f)
        assertEquals(0.5f, result.green, 0.005f)
        assertEquals(0f, result.blue, 0.005f)
    }

    @Test
    fun blendingIsDeterministic() {
        val colors = listOf(Color(0.2f, 0.4f, 0.6f), Color(0.8f, 0.1f, 0.3f), Color(0f, 1f, 0.5f))

        assertEquals(blendSetColors(colors), blendSetColors(colors))
    }

    @Test
    fun blendingThreeColoursWeightsThemEqually() {
        val result = blendSetColors(
            listOf(Color(0.9f, 0f, 0f), Color(0f, 0.9f, 0f), Color(0f, 0f, 0.9f)),
        )

        assertEquals(0.3f, result.red, 0.005f)
        assertEquals(0.3f, result.green, 0.005f)
        assertEquals(0.3f, result.blue, 0.005f)
    }

    @Test
    fun oneColourBlendsToItself() {
        val only = Color(0.1f, 0.2f, 0.3f)

        assertEquals(only, blendSetColors(listOf(only)))
        assertEquals(only, blendSetColors(listOf(only, Color.Transparent)))
    }

    @Test
    fun blendingNothingIsTransparent() {
        assertEquals(Color.Transparent, blendSetColors(emptyList()))
    }

    @Test
    fun theBlendKeepsItsAlpha() {
        val result = blendSetColors(
            listOf(Color(1f, 0f, 0f, alpha = 0.4f), Color(0f, 0f, 1f, alpha = 0.8f)),
        )

        assertEquals(0.6f, result.alpha, 0.005f)
    }

    // ---- naming ----------------------------------------------------------

    @Test
    fun anExclusiveRegionIsNamedOnly() {
        val region = data.region(setOf("android"))!!

        // "Android" and "Android only" are different regions with different
        // values, and a tooltip that used the first name for the second would
        // report 130 beside the word for 200.
        assertEquals("Android only", defaultRegionName(region, data, " only", " & "))
    }

    @Test
    fun anIntersectionIsNamedWithTheSeparator() {
        val region = data.region(setOf("android", "ios"))!!

        assertEquals("Android & iOS", defaultRegionName(region, data, " only", " & "))
    }

    @Test
    fun theWordsAreReplaceable() {
        val exclusive = data.region(setOf("android"))!!
        val shared = data.region(setOf("android", "ios"))!!

        // "only" and "&" are English. A diagram shipped anywhere else needs
        // different words, and they must not be buried in the renderer.
        assertEquals("Android seulement", defaultRegionName(exclusive, data, " seulement", " et "))
        assertEquals("Android et iOS", defaultRegionName(shared, data, " seulement", " et "))
    }

    @Test
    fun aSingleSetDiagramDoesNotSayOnly() {
        val single = SetAnalyzer.analyze(listOf(SetDefinition("a", "Alpha", 10.0)))

        // With one set there is nothing to be exclusive of, so "Alpha only"
        // would be an odd way to say "Alpha".
        assertEquals(
            "Alpha",
            defaultRegionName(single.region(setOf("a"))!!, single, " only", " & "),
        )
    }

    // ---- tables ----------------------------------------------------------

    @Test
    fun theDataTableListsRegionsThatAddUp() {
        val table = setDataTable(data, naming = SetRegionNaming())

        assertEquals(listOf("Region", "Value", "Share of union"), table.columns)
        assertEquals(3, table.rows.size)
        assertTrue(table.rows.any { it[0] == "Android only" && it[1] == "130" })
        assertTrue(table.rows.any { it[0] == "iOS only" && it[1] == "90" })
        assertTrue(table.rows.any { it[0] == "Android & iOS" && it[1] == "70" })
    }

    @Test
    fun theTableSharesAreOfTheUnion() {
        val table = setDataTable(data)

        // 130 of 290. A share of the *set* would mean something different in
        // every row, because each row belongs to a different combination.
        assertEquals("45%", table.rows.first { it[0] == "Android only" }[2])
    }

    @Test
    fun emptyRegionsAreLeftOutUnlessAskedFor() {
        val sparse = SetAnalyzer.analyze(
            listOf(
                SetDefinition("a", "A", 10.0),
                SetDefinition("b", "B", 10.0),
                SetDefinition("c", "C", 10.0),
            ),
        )

        assertEquals(3, setDataTable(sparse).rows.size)
        assertTrue(setDataTable(sparse, includeEmpty = true).rows.size >= 3)
    }

    @Test
    fun theRelationshipTableStatesNestingAndDisjointness() {
        val nested = SetAnalyzer.analyze(
            listOf(
                SetDefinition("animals", "Animals", 100.0),
                SetDefinition("mammals", "Mammals", 40.0),
                SetDefinition("plants", "Plants", 60.0),
            ),
            containments = listOf(SetContainment("animals", "mammals")),
        )

        val table = setRelationshipTable(nested)
        val rows = table.rows.map { it.joinToString(" ") }

        assertTrue(rows.any { it == "Animals contains Mammals" })
        assertTrue(rows.any { it == "Animals shares nothing with Plants" })
        assertTrue(rows.any { it == "Mammals shares nothing with Plants" })
    }

    @Test
    fun theRelationshipTableSaysNothingLabelsDidNotEarn() {
        val nested = SetAnalyzer.analyze(
            listOf(
                SetDefinition("gb", "Great Britain", 100.0),
                SetDefinition("scotland", "Scotland", 20.0),
            ),
        )

        // No containment was modelled, so none is announced — however
        // geographic the labels look. Inferring the relationship from the words
        // is how an accessibility layer ends up asserting something false.
        val rows = setRelationshipTable(nested).rows.map { it.joinToString(" ") }
        assertTrue(rows.any { it.contains("shares nothing with") })
        assertFalse(rows.any { it.contains("contains") })
    }

    @Test
    fun theRelationshipWordsAreReplaceable() {
        val nested = SetAnalyzer.analyze(
            listOf(
                SetDefinition("a", "A", 10.0),
                SetDefinition("b", "B", 5.0),
            ),
            containments = listOf(SetContainment("a", "b")),
        )

        val table = setRelationshipTable(nested, containsText = "umfasst")
        assertTrue(table.rows.any { it[1] == "umfasst" })
    }

    @Test
    fun theTableTextIsReadableEndToEnd() {
        val text = setDataTable(data, caption = "Platform usage").asText()

        assertTrue(text.startsWith("Platform usage."))
        assertTrue(text.contains("Region: Android only"))
        assertTrue(text.contains("Value: 130"))
    }
}
