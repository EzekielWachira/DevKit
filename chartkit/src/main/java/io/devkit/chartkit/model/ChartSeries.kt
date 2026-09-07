package io.devkit.chartkit.model

/**
 * One named run of the caller's own data.
 *
 * `data` stays `List<T>` — the caller's type, not a converted one — and the
 * chart is told how to read x and y from it. That is the difference between
 * charting a `List<Revenue>` and first mapping it into a list of entries whose
 * only purpose is to be charted.
 *
 * @param id a stable identifier. Load-bearing rather than decorative: it is what
 *   lets an animation match "revenue" across a data change instead of matching
 *   by list position, and what a legend toggles. Two series with the same id in
 *   one chart is a programming error and is rejected.
 * @param name the human label, for legends, tooltips and accessibility.
 * @param data the caller's items, in the order they should be read.
 * @param visible whether the series is drawn. Hidden series still occupy their
 *   colour and legend slot, so hiding one does not recolour the others.
 * @param color an explicit colour as an ARGB value, or `null` to take the next
 *   entry from the theme palette. An `Int` and not a Compose `Color` so the
 *   model stays free of Compose types and testable on the JVM.
 */
data class ChartSeries<out T>(
    val id: String,
    val name: String,
    val data: List<T>,
    val visible: Boolean = true,
    val color: Int? = null,
) {
    init {
        require(id.isNotBlank()) {
            "A chart series needs a stable, non-blank id — it is what animation and legend " +
                "toggling match on across data changes"
        }
    }
}

/** Convenience for the common case where the id doubles as the label. */
fun <T> chartSeries(
    id: String,
    data: List<T>,
    name: String = id,
    visible: Boolean = true,
    color: Int? = null,
): ChartSeries<T> = ChartSeries(id, name, data, visible, color)

/** Fails fast on duplicate ids, which would silently break animation matching. */
internal fun <T> List<ChartSeries<T>>.requireDistinctIds() {
    val seen = HashSet<String>(size)
    for (series in this) {
        require(seen.add(series.id)) {
            "Duplicate chart series id '${series.id}'. Ids identify a series across data " +
                "changes, so they have to be unique within one chart."
        }
    }
}
