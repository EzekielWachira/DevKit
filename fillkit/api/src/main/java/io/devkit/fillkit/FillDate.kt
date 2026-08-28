package io.devkit.fillkit

/** A small, Android 7 compatible civil date used by FillKit's public API. */
data class FillDate(
    val year: Int,
    val month: Int,
    val day: Int,
) : Comparable<FillDate> {
    init {
        require(month in 1..12) { "month must be in 1..12" }
        require(day in 1..daysInMonth(year, month)) {
            "day $day is invalid for $year-$month"
        }
    }

    override fun compareTo(other: FillDate): Int =
        compareValuesBy(this, other, FillDate::year, FillDate::month, FillDate::day)

    override fun toString(): String = "%04d-%02d-%02d".format(year, month, day)

    companion object {
        /** Returns the number of days in [month], including leap-year handling. */
        fun daysInMonth(year: Int, month: Int): Int = when (month) {
            2 -> if (year % 400 == 0 || year % 4 == 0 && year % 100 != 0) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}
