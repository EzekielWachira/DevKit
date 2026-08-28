package io.devkit.fillkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FillKitApiTest {
    @Test
    fun scenarioDslPreservesTypedValuesAndOrder() {
        val scenario = fillScenario("happy", "Happy") {
            text("name", "Amina")
            integer("age", 29)
            boolean("active", true)
        }
        assertEquals(listOf("name", "age", "active"), scenario.values.keys.toList())
        assertEquals(FillValue.Integer(29), scenario.values["age"])
    }

    @Test
    fun invalidConstraintsFailEarly() {
        assertThrows(IllegalArgumentException::class.java) { FillType.Integer(65..18) }
        assertThrows(IllegalArgumentException::class.java) { FillType.Text(10, 2) }
        assertThrows(IllegalArgumentException::class.java) { FillType.Selection(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { FillType.Password(minLength = 2) }
        assertThrows(IllegalArgumentException::class.java) { FillType.Date(FillDate(2025, 1, 1), FillDate(2024, 1, 1)) }
    }

    @Test
    fun fillDateValidatesCalendarDates() {
        assertEquals(FillDate(2024, 2, 29), FillDate(2024, 2, 29))
        assertThrows(IllegalArgumentException::class.java) { FillDate(2025, 2, 29) }
    }
}
