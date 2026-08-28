package io.devkit.fillkit.engine

import io.devkit.fillkit.FillDate

internal object DateMath {
    fun ordinal(date: FillDate): Int = daysBeforeYear(date.year) + dayOfYear(date) - 1

    fun fromOrdinal(value: Int): FillDate {
        var low = 1
        var high = 9999
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (daysBeforeYear(middle) <= value) low = middle else high = middle - 1
        }
        var remaining = value - daysBeforeYear(low)
        var month = 1
        while (remaining >= FillDate.daysInMonth(low, month)) {
            remaining -= FillDate.daysInMonth(low, month)
            month++
        }
        return FillDate(low, month, remaining + 1)
    }

    private fun daysBeforeYear(year: Int): Int {
        val previous = year - 1
        return previous * 365 + previous / 4 - previous / 100 + previous / 400
    }

    private fun dayOfYear(date: FillDate): Int =
        (1 until date.month).sumOf { FillDate.daysInMonth(date.year, it) } + date.day
}
