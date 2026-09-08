package io.devkit.chartkit.accessibility

import io.devkit.chartkit.formatter.ChartValueFormatter
import io.devkit.chartkit.layer.ChartLayerSummary

/**
 * What a chart tells assistive technology.
 *
 * A chart drawn entirely on a `Canvas` is, to a screen reader, one unlabelled
 * rectangle. Nothing about Compose fixes that automatically, so the semantics
 * are constructed deliberately here.
 *
 * @param title what the chart shows, e.g. "Monthly revenue". Worth supplying
 *   even when it is rendered as a heading elsewhere: the semantics tree has no
 *   way to know the heading above the chart refers to it.
 * @param description longer context, read after the title.
 * @param includeDataPoints whether individual values are read out. Useful and
 *   noisy in equal measure — see [MAX_ANNOUNCED_POINTS].
 */
data class ChartAccessibility(
    val title: String? = null,
    val description: String? = null,
    val includeDataPoints: Boolean = true,
) {
    companion object {

        /** Title-less but still describing its series and values. */
        val Auto: ChartAccessibility = ChartAccessibility()

        /**
         * The title, the series names, the counts and the range — but not the
         * individual values.
         *
         * Named for what it does. It is *not* "no semantics": a chart with no
         * description at all is one unlabelled rectangle to a screen reader,
         * which is never what a caller wants. Supplying
         * `accessibilitySummary` replaces the generated text entirely, and
         * that is the way to say something else.
         */
        val Concise: ChartAccessibility = ChartAccessibility(includeDataPoints = false)

        @Deprecated(
            message = "The name claimed more than it did: this still announces the title, " +
                "the series and the counts. Renamed to Concise.",
            replaceWith = ReplaceWith("ChartAccessibility.Concise"),
        )
        val None: ChartAccessibility = Concise

        /**
         * Beyond this many points, values are summarised rather than listed.
         *
         * A screen reader reading two hundred numbers in sequence is not
         * accessible; it is a way of making the chart unusable politely. Past
         * the threshold the announcement gives the series, the count and the
         * range, which is what a reader can actually hold.
         */
        const val MAX_ANNOUNCED_POINTS: Int = 24
    }
}

/**
 * Builds the sentence a screen reader announces for a chart.
 *
 * Strictly factual. Values, counts, minima and maxima — never "trending
 * upward", "a strong correlation" or any other interpretation. Those are
 * statistical claims ChartKit has not computed, and a confidently wrong one is
 * worse than saying nothing: a reader who cannot see the chart has no way to
 * check it.
 */
internal fun buildChartSummary(
    accessibility: ChartAccessibility,
    summaries: List<ChartLayerSummary>,
    formatter: ChartValueFormatter,
): String {
    val parts = ArrayList<String>()
    accessibility.title?.takeIf { it.isNotBlank() }?.let { parts += it }
    accessibility.description?.takeIf { it.isNotBlank() }?.let { parts += it }

    if (summaries.isEmpty()) {
        parts += "No data."
        return parts.joinToString(" ")
    }

    if (summaries.size > 1) {
        parts += "${summaries.size} series: ${summaries.joinToString(", ") { it.seriesName }}."
    }

    summaries.forEach { summary ->
        val present = summary.entries.mapNotNull { it.value }
        val missing = if (summary.entries.isEmpty()) {
            summary.missingCount
        } else {
            summary.pointCount - present.size
        }
        val header = buildString {
            if (summaries.size > 1) append("${summary.seriesName}: ")
            append("${summary.pointCount} data points")
            if (missing > 0) append(", $missing missing")
            append(".")
        }
        parts += header

        // A layer that skipped materialising its entries — because there were
        // far more than could ever be announced — supplies the range instead.
        val range = summary.valueRange
            ?: present.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() }

        when {
            range == null -> Unit

            accessibility.includeDataPoints &&
                summary.entries.isNotEmpty() &&
                summary.entries.size <= ChartAccessibility.MAX_ANNOUNCED_POINTS ->
                parts += summary.entries.joinToString(", ") { entry ->
                    val detail = entry.detail
                    val value = entry.value
                    when {
                        // A layer that knows its point needs more than one
                        // number to describe says so itself.
                        detail != null -> detail
                        value == null -> "${entry.label}: no value"
                        else -> "${entry.label}: ${formatter.format(value)}"
                    }
                } + "."

            else -> parts +=
                "Values from ${formatter.format(range.start)} to " +
                    "${formatter.format(range.endInclusive)}."
        }
    }
    return parts.joinToString(" ")
}

/** The announcement for the currently selected point. */
internal fun describeSelection(
    seriesName: String,
    xLabel: String,
    value: Double,
    formatter: ChartValueFormatter,
    multiSeries: Boolean,
): String = buildString {
    if (multiSeries) {
        append(seriesName)
        append(", ")
    }
    append(xLabel)
    append(": ")
    append(formatter.format(value))
}
