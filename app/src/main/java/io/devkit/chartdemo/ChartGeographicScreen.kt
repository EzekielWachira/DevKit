package io.devkit.chartdemo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartDataTableView
import io.devkit.chartkit.accessibility.GeoTableOrder
import io.devkit.chartkit.accessibility.geoDataTable
import io.devkit.chartkit.charts.ChoroplethMap
import io.devkit.chartkit.charts.GeoJoinReport
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.geo.GeoJson
import io.devkit.chartkit.geo.GeoProjection
import io.devkit.chartkit.layer.geo.GeoLabels
import io.devkit.chartkit.scale.ColorScale
import io.devkit.chartkit.state.rememberChartGeoViewportState
import io.devkit.chartkit.theme.ChartColorScales

/**
 * A thematic map of sales territories.
 *
 * ### The geometry is the sample's, not ChartKit's
 *
 * The boundaries come from a GeoJSON string in [ChartDemoData], parsed once and
 * held in `remember`. ChartKit ships no geography at all — a usable world file
 * is megabytes and would land in every consumer's APK — so this screen is also
 * a worked example of the shape a caller's own loading code takes.
 *
 * ### What the controls are for
 *
 * Each one exists because it changes an answer, not because it changes a
 * picture: the scale type changes which regions look alike, "no data" is a
 * different colour from the low end of the ramp, and the join report says why
 * a region is grey.
 */
@Composable
fun ChartGeographicScreen(modifier: Modifier = Modifier) {
    var quantile by remember { mutableStateOf(true) }
    var labels by remember { mutableStateOf(GeoLabels.Auto) }
    var showLegend by remember { mutableStateOf(true) }
    var mercator by remember { mutableStateOf(false) }
    var showTable by remember { mutableStateOf(false) }
    var readout by remember { mutableStateOf("Tap a territory") }
    var join by remember { mutableStateOf<GeoJoinReport?>(null) }

    // Parsed once. Reparsing a boundary file on recomposition is the single
    // most expensive mistake a caller can make with this API.
    val geometry = remember { GeoJson.parse(ChartDemoData.territoriesGeoJson) }
    val camera = rememberChartGeoViewportState()
    val counts = remember { ChartNumberFormatters.compact() }

    val values = remember { ChartDemoData.territoryOrders.map { it.orders } }
    val quantileScale = ChartColorScales.quantile(values, groups = 4)
    val continuousScale = ChartColorScales.continuous(
        io.devkit.chartkit.scale.NumericDomain(
            min = values.filterNotNull().min(),
            max = values.filterNotNull().max(),
        ),
    )
    val scale: ColorScale = if (quantile) quantileScale else continuousScale

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Geographic", style = MaterialTheme.typography.titleLarge)
        Text(
            "Regions shaded by a statistic, on ChartKit's own renderer. Boundaries are the " +
                "sample's own GeoJSON — there is no map SDK, no basemap and no tiles.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = quantile,
                onClick = { quantile = true },
                label = { Text("Quantile") },
                modifier = Modifier.testTag("geo-quantile"),
            )
            FilterChip(
                selected = !quantile,
                onClick = { quantile = false },
                label = { Text("Continuous") },
                modifier = Modifier.testTag("geo-continuous"),
            )
            FilterChip(
                selected = mercator,
                onClick = { mercator = !mercator },
                label = { Text("Mercator") },
                modifier = Modifier.testTag("geo-mercator"),
            )
            FilterChip(
                selected = showLegend,
                onClick = { showLegend = !showLegend },
                label = { Text("Legend") },
                modifier = Modifier.testTag("geo-legend"),
            )
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GeoLabels.entries.forEach { option ->
                FilterChip(
                    selected = labels == option,
                    onClick = { labels = option },
                    label = { Text(option.name) },
                    modifier = Modifier.testTag("geo-labels-${option.name}"),
                )
            }
        }

        ChoroplethMap(
            geometry = geometry,
            data = ChartDemoData.territoryOrders,
            featureKey = { it.properties.string("code") },
            dataKey = { it.code },
            value = { it.orders },
            featureLabel = { it.properties.string("name").orEmpty() },
            projection = if (mercator) GeoProjection.Mercator else GeoProjection.Equirectangular,
            scale = scale,
            labels = labels,
            legend = if (showLegend) LegendPosition.Bottom else LegendPosition.None,
            legendTitle = "Orders",
            valueFormatter = counts,
            viewportState = camera,
            onJoin = { join = it },
            onSelectionChanged = { selection ->
                val geo = selection?.geo
                readout = when {
                    geo == null -> "Tap a territory"
                    // "No data" and "0" are different sentences, because they
                    // are different facts.
                    !geo.hasValue -> "${geo.featureLabel}: no data"
                    else -> "${geo.featureLabel}: ${counts.format(selection.y)} orders"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .testTag("geo-map"),
        )

        Text(readout, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("geo-readout"))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { camera.reset() },
                enabled = !camera.isReset,
                modifier = Modifier.testTag("geo-reset"),
            ) {
                Text("Reset view")
            }
            TextButton(
                onClick = { camera.focusOn(geometry.features.firstOrNull()?.bounds?.expanded(0.2)) },
                modifier = Modifier.testTag("geo-focus"),
            ) {
                Text("Focus first region")
            }
        }
        Text(
            "Pinch to zoom and drag to pan once zoomed. Arrow keys, +/- and 0 do the " +
                "same from a keyboard. There is no double-tap gesture, so a tap selects " +
                "immediately rather than waiting to see whether a second one follows.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()

        // The diagnostic a caller actually needs when a map comes out blank.
        join?.let { report ->
            Text("Join", style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    append("${report.matched} of ${geometry.features.size} regions matched.")
                    if (report.unmatchedFeatureKeys.isNotEmpty()) {
                        append(" No data for ${report.unmatchedFeatureKeys.joinToString(", ")}.")
                    }
                    if (report.unmatchedDataKeys.isNotEmpty()) {
                        append(" No region for ${report.unmatchedDataKeys.joinToString(", ")}.")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("geo-join"),
            )
        }

        HorizontalDivider()
        FilterChip(
            selected = showTable,
            onClick = { showTable = !showTable },
            label = { Text("Data table") },
            modifier = Modifier.testTag("geo-table"),
        )
        if (showTable) {
            // For a map this is not a fallback. Shape, adjacency and colour are
            // not describable in a sentence, so for a reader who cannot see the
            // picture the table *is* the chart.
            ChartDataTableView(
                geoDataTable(
                    geometry = geometry,
                    data = ChartDemoData.territoryOrders,
                    featureKey = { it.properties.string("code") },
                    dataKey = { it.code },
                    value = { it.orders },
                    featureLabel = { it.properties.string("name").orEmpty() },
                    valueFormatter = counts,
                    order = GeoTableOrder.ByValueDescending,
                    caption = "Orders by territory",
                ),
            )
        }
    }
}
