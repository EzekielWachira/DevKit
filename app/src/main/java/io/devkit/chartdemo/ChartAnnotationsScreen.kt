package io.devkit.chartdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.annotation.AnnotationStyle
import io.devkit.chartkit.annotation.ChartAnnotation
import io.devkit.chartkit.annotation.domainRange
import io.devkit.chartkit.annotation.eventMarker
import io.devkit.chartkit.annotation.horizontalRule
import io.devkit.chartkit.annotation.region
import io.devkit.chartkit.annotation.valueRange
import io.devkit.chartkit.annotation.verticalRule
import io.devkit.chartkit.charts.BarChart
import io.devkit.chartkit.charts.LineChart
import io.devkit.chartkit.formatter.ChartNumberFormatters

/**
 * Annotations, each toggleable so the effect of one is separable from the rest.
 *
 * The same six annotations are applied to a line chart and to a bar chart on
 * the same screen. That is the point being demonstrated: annotations belong to
 * the coordinate system, so they work on every Cartesian chart rather than on
 * the one they were written for.
 */
@Composable
fun ChartAnnotationsScreen(modifier: Modifier = Modifier) {
    var target by rememberSaveable { mutableStateOf(true) }
    var release by rememberSaveable { mutableStateOf(true) }
    var band by rememberSaveable { mutableStateOf(true) }
    var campaign by rememberSaveable { mutableStateOf(false) }
    var goal by rememberSaveable { mutableStateOf(false) }
    var marker by rememberSaveable { mutableStateOf(true) }

    val annotations: List<ChartAnnotation> = buildList {
        if (target) {
            add(horizontalRule(value = 40_000.0, label = "Target"))
        }
        if (release) {
            add(verticalRule(at = "Mar", label = "v2.0", style = AnnotationStyle.Solid))
        }
        if (band) {
            add(valueRange(from = 30_000.0, to = 40_000.0, label = "On track"))
        }
        if (campaign) {
            add(domainRange(from = "Feb", to = "Apr", label = "Campaign"))
        }
        if (goal) {
            add(
                region(
                    domainFrom = "Apr",
                    domainTo = "Jun",
                    valueFrom = 38_000.0,
                    valueTo = 46_000.0,
                    label = "Q2 goal",
                ),
            )
        }
        if (marker) {
            add(eventMarker(at = "May", value = 44_100.0, label = "Record"))
        }
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Annotations", style = MaterialTheme.typography.titleLarge)
        Text(
            "Reference marks in the chart's own coordinate space. Positions along the domain " +
                "go through the same resolver the data does, so `verticalRule(at = \"Mar\")` " +
                "lands on the March band and `verticalRule(at = releaseMillis)` lands on the " +
                "release date, with no separate annotation type per axis.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Toggle("Horizontal rule — target", target, "ann-target") { target = it }
        Toggle("Vertical rule — release", release, "ann-release") { release = it }
        Toggle("Value range — on-track band", band, "ann-band") { band = it }
        Toggle("Domain range — campaign", campaign, "ann-campaign") { campaign = it }
        Toggle("Region — quarter goal", goal, "ann-goal") { goal = it }
        Toggle("Event marker — record month", marker, "ann-marker") { marker = it }

        HorizontalDivider()

        Text("On a line chart", style = MaterialTheme.typography.titleSmall)
        LineChart(
            data = ChartDemoData.revenue,
            x = { it.month },
            y = { it.amount },
            annotations = annotations,
            valueFormatter = ChartNumberFormatters.compact(),
            modifier = Modifier.fillMaxWidth().height(260.dp).testTag(LINE),
        )

        Text("The same annotations on a bar chart", style = MaterialTheme.typography.titleSmall)
        BarChart(
            data = ChartDemoData.revenue,
            category = { it.month },
            value = { it.amount },
            annotations = annotations,
            valueFormatter = ChartNumberFormatters.compact(),
            modifier = Modifier.fillMaxWidth().height(260.dp).testTag(BAR),
        )

        Text(
            "Regions and bands draw behind the data and rules and markers in front, so a " +
                "shaded target zone never hides the values it exists to be compared against, " +
                "and a threshold line stays visible where it crosses the series.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
        Text("  $label", style = MaterialTheme.typography.bodyMedium)
    }
}

private const val LINE = "annotations-line"
private const val BAR = "annotations-bar"
