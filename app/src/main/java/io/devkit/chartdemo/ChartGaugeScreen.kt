package io.devkit.chartdemo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.DialGauge
import io.devkit.chartkit.charts.GaugeChart
import io.devkit.chartkit.charts.GaugeShape
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.gauge.GaugeBandStyle
import io.devkit.chartkit.gauge.GaugeDetail
import io.devkit.chartkit.gauge.GaugeInteraction
import io.devkit.chartkit.gauge.GaugeMarker
import io.devkit.chartkit.gauge.GaugeMarkerShape
import io.devkit.chartkit.gauge.GaugeNeedleShape
import io.devkit.chartkit.gauge.GaugeNeedleStyle
import io.devkit.chartkit.gauge.GaugePane
import io.devkit.chartkit.gauge.GaugePivotStyle
import io.devkit.chartkit.gauge.GaugeTickConfig
import io.devkit.chartkit.gauge.GaugeTickPlacement
import io.devkit.chartkit.gauge.GaugeValue
import io.devkit.chartkit.gauge.GaugeValuePosition
import io.devkit.chartkit.layer.polar.GaugeIndicator
import io.devkit.chartkit.theme.ChartKitTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Every gauge capability, one screen.
 *
 * Each demo exists because it shows something the others do not: a different
 * sweep, a different scale, a different set of marks, or a different mode of
 * interaction. The controls change what is *shown* and never what the reading
 * means — switching a dial to compact detail does not alter a single number.
 */
@Composable
fun ChartGaugeScreen(modifier: Modifier = Modifier) {
    var demo by remember { mutableStateOf(GaugeDemo.Speedometer) }
    var animate by remember { mutableStateOf(true) }
    var compact by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(82f) }
    var readout by remember { mutableStateOf("") }

    val animation = if (animate) ChartAnimation.Default else ChartAnimation.None
    val detail = if (compact) GaugeDetail.Compact else GaugeDetail.Auto
    val whole = remember { ChartNumberFormatters.integer(Locale.UK) }
    val oneDecimal = remember { ChartNumberFormatters.decimal(decimals = 1, locale = Locale.UK) }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Gauges", style = MaterialTheme.typography.titleLarge)
        Text(
            "A numeric range, an angular scale and the marks that read against it — on the " +
                "same polar engine as the pie, donut and radial bar charts.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .testTag("gauge-demos"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GaugeDemo.entries.forEach { entry ->
                FilterChip(
                    selected = demo == entry,
                    onClick = {
                        demo = entry
                        readout = ""
                    },
                    label = { Text(entry.label) },
                    modifier = Modifier.testTag("gauge-demo-${entry.name}"),
                )
            }
        }

        Text(demo.description, style = MaterialTheme.typography.bodySmall)

        val chartModifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .testTag("gauge-chart")

        when (demo) {
            GaugeDemo.Speedometer -> Speedometer(
                speed = speed.toDouble(),
                animation = animation,
                detail = detail,
                whole = whole,
                onReading = { readout = it },
                modifier = chartModifier,
            )

            GaugeDemo.Semicircle -> DialGauge(
                value = speed.toDouble(),
                min = 0.0,
                max = 200.0,
                // The reference form: two angles rather than a start and a sweep.
                shape = GaugeShape.between(startAngle = -90f, endAngle = 90f),
                label = "Speed",
                unit = "km/h",
                ticks = GaugeTickConfig(interval = 25.0, minorCount = 5),
                labelFormatter = whole,
                animation = animation,
                detail = detail,
                valuePosition = GaugeValuePosition.BelowCenter,
                valueContent = { animated -> Readout(whole.format(animated), "km/h") },
                modifier = chartModifier,
            )

            GaugeDemo.FullCircle -> DialGauge(
                value = (speed * 1.8f).toDouble().coerceIn(0.0, 360.0),
                min = 0.0,
                max = 360.0,
                shape = GaugeShape.FullCircle,
                label = "Heading",
                unit = "degrees",
                ticks = GaugeTickConfig(interval = 45.0, minorCount = 3),
                labelFormatter = whole,
                animation = animation,
                detail = detail,
                pane = GaugePane.Themed,
                valuePosition = GaugeValuePosition.BelowCenter,
                valueContent = { animated -> Readout(whole.format(animated) + "°", "heading") },
                modifier = chartModifier,
            )

            GaugeDemo.ThreeQuarter -> DialGauge(
                value = (speed / 2f).toDouble(),
                min = 0.0,
                max = 100.0,
                shape = GaugeShape.ThreeQuarter,
                label = "Load",
                unit = "%",
                bands = GaugeDemoData.loadBands,
                ticks = GaugeTickConfig(interval = 10.0, minorCount = 2),
                labelFormatter = whole,
                animation = animation,
                detail = detail,
                onReadingChanged = { readout = it.bandLabel ?: "No band" },
                valuePosition = GaugeValuePosition.BelowCenter,
                valueContent = { animated -> Readout(whole.format(animated) + "%", "load") },
                modifier = chartModifier,
            )

            GaugeDemo.Bands -> DialGauge(
                value = (speed / 5f - 10f).toDouble(),
                min = -20.0,
                max = 40.0,
                shape = GaugeShape.between(-120f, 120f),
                label = "Cabin temperature",
                unit = "°C",
                bands = GaugeDemoData.temperatureBands,
                // Bands drawn as a thin rail just inside the track, so the
                // ticks stay legible over them.
                bandStyles = remember {
                    GaugeDemoData.temperatureBands.indices.associateWith {
                        GaugeBandStyle(thickness = 0.45f, position = 0.78f, rounded = false)
                    }
                },
                ticks = GaugeTickConfig(interval = 10.0, minorCount = 2),
                labelFormatter = whole,
                animation = animation,
                detail = detail,
                onReadingChanged = { readout = it.bandLabel ?: "No band" },
                valuePosition = GaugeValuePosition.BelowCenter,
                valueContent = { animated -> Readout(oneDecimal.format(animated) + "°C", "cabin") },
                modifier = chartModifier,
            )

            GaugeDemo.MultiNeedle -> DialGauge(
                series = listOf(
                    GaugeValue("current", speed.toDouble(), "Current"),
                    GaugeValue("target", 130.0, "Target", style = GaugeNeedleStyle.Target),
                    GaugeValue("average", 96.0, "Average", style = GaugeNeedleStyle.Thin),
                ),
                min = 0.0,
                max = 200.0,
                shape = GaugeShape.between(-120f, 120f),
                label = "Speed",
                unit = "km/h",
                bands = GaugeDemoData.speedBands,
                ticks = GaugeTickConfig(interval = 40.0, minorCount = 4),
                labelFormatter = whole,
                animation = animation,
                detail = detail,
                legend = LegendPosition.Bottom,
                modifier = chartModifier,
            )

            GaugeDemo.Interactive -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DialGauge(
                    value = speed.toDouble(),
                    min = 0.0,
                    max = 200.0,
                    shape = GaugeShape.between(-135f, 135f),
                    label = "Speed limiter",
                    unit = "km/h",
                    bands = GaugeDemoData.speedBands,
                    ticks = GaugeTickConfig(interval = 25.0, minorCount = 5),
                    labelFormatter = whole,
                    animation = animation,
                    detail = detail,
                    // A knob, not a display: drag round the dial to set it.
                    interaction = GaugeInteraction.Drag,
                    step = 5.0,
                    onValueChange = { speed = it.toFloat() },
                    valuePosition = GaugeValuePosition.BelowCenter,
                    valueContent = { animated -> Readout(whole.format(animated), "km/h") },
                    modifier = Modifier.fillMaxWidth().height(260.dp).testTag("gauge-chart"),
                )
                Text(
                    "Drag around the arc, or focus it and use the arrow keys. A screen reader " +
                        "gets increase and decrease actions in 5 km/h steps.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            GaugeDemo.CompactKpi -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("gauge-chart"),
            ) {
                // The arc gauge, not the dial: at 96dp a scale is unreadable
                // and a filled ring is not.
                GaugeChart(
                    value = 72.0,
                    min = 0.0,
                    max = 140.0,
                    label = "Attainment",
                    bands = GaugeDemoData.attainmentBands,
                    shape = GaugeShape.SemiCircle,
                    indicator = GaugeIndicator.Arc,
                    animation = animation,
                    centerContent = { Readout("72%", "of target") },
                    modifier = Modifier.size(150.dp),
                )
                DialGauge(
                    value = 46.0,
                    min = 0.0,
                    max = 100.0,
                    shape = GaugeShape.between(-120f, 120f),
                    label = "Disk",
                    unit = "%",
                    ticks = GaugeTickConfig(interval = 25.0, minorCount = 0),
                    labelFormatter = whole,
                    detail = GaugeDetail.Compact,
                    animation = animation,
                    valuePosition = GaugeValuePosition.BelowCenter,
                    valueContent = { animated -> Readout(whole.format(animated) + "%", "disk") },
                    modifier = Modifier.size(150.dp),
                )
            }

            GaugeDemo.Realtime -> RealtimeGauge(
                animation = animation,
                detail = detail,
                whole = whole,
                onReading = { readout = it },
                modifier = chartModifier,
            )

            GaugeDemo.CustomNeedle -> DialGauge(
                value = speed.toDouble(),
                min = 0.0,
                max = 200.0,
                shape = GaugeShape.between(-135f, 135f),
                label = "Tachometer",
                unit = "rpm",
                ticks = GaugeTickConfig(
                    interval = 25.0,
                    minorCount = 5,
                    placement = GaugeTickPlacement.Outside,
                ),
                // A long thin blade with a counterweight, over a dial face.
                needle = GaugeNeedleStyle(
                    shape = GaugeNeedleShape.Triangle,
                    length = 0.92f,
                    tail = 0.22f,
                    baseWidth = 10.dp,
                ),
                pivot = GaugePivotStyle(radius = 0.09f),
                pane = GaugePane.Themed,
                markers = listOf(
                    GaugeMarker(160.0, "Redline", GaugeMarkerShape.Line, position = 0.98f),
                ),
                showTrack = false,
                labelFormatter = whole,
                animation = animation,
                detail = detail,
                modifier = chartModifier,
            )
        }

        if (readout.isNotBlank()) {
            Text(readout, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "Value: ${speed.roundToInt()}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("gauge-readout"),
        )

        Slider(
            value = speed,
            onValueChange = { speed = it },
            valueRange = 0f..200f,
            modifier = Modifier.testTag("gauge-slider"),
        )

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = animate,
                onClick = { animate = !animate },
                label = { Text("Animate") },
                modifier = Modifier.testTag("gauge-animate"),
            )
            FilterChip(
                selected = compact,
                onClick = { compact = !compact },
                label = { Text("Compact") },
                modifier = Modifier.testTag("gauge-compact"),
            )
        }
        Text(
            if (compact) {
                "Compact: no minor ticks, and numbers only where they fit. Every band, needle " +
                    "and marker is still drawn — detail is reduced, never data."
            } else {
                "Full detail. Drag the slider: the needle retargets from wherever it is rather " +
                    "than restarting, so rapid changes read as one continuous sweep."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The reference speedometer.
 *
 * A semicircular pane, a 0–200 domain, major and minor ticks, numbers, three
 * threshold bands, a needle, a pivot and an animated reading — every element
 * the reference demo shows, in native ChartKit APIs.
 */
@Composable
private fun Speedometer(
    speed: Double,
    animation: ChartAnimation,
    detail: GaugeDetail,
    whole: io.devkit.chartkit.formatter.ChartValueFormatter,
    onReading: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    DialGauge(
        value = speed,
        min = 0.0,
        max = 200.0,
        shape = GaugeShape.between(startAngle = -90f, endAngle = 90f),
        label = "Speed",
        unit = "km/h",
        bands = GaugeDemoData.speedBands,
        markers = GaugeDemoData.speedLimit,
        ticks = GaugeTickConfig(interval = 20.0, minorCount = 4),
        labelFormatter = whole,
        animation = animation,
        detail = detail,
        pane = GaugePane.Themed,
        valuePosition = GaugeValuePosition.BelowCenter,
        onReadingChanged = { onReading(it.bandLabel?.let { band -> "Range: $band" } ?: "") },
        valueContent = { animated -> Readout(whole.format(animated), "km/h") },
        modifier = modifier,
    )
}

/**
 * A dial fed from a deterministic trace, with pause and resume.
 *
 * The point is the animation's behaviour under rapid change: each new sample
 * retargets the needle from wherever it currently is rather than restarting it,
 * so the dial sweeps continuously instead of stuttering back to each previous
 * reading.
 */
@Composable
private fun RealtimeGauge(
    animation: ChartAnimation,
    detail: GaugeDetail,
    whole: io.devkit.chartkit.formatter.ChartValueFormatter,
    onReading: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var running by remember { mutableStateOf(true) }
    var index by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(running) {
        while (running) {
            kotlinx.coroutines.delay(SAMPLE_INTERVAL_MILLIS)
            index = (index + 1) % GaugeDemoData.loadTrace.size
        }
    }

    val load = GaugeDemoData.loadTrace[index]

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DialGauge(
            value = load,
            min = 0.0,
            max = 100.0,
            shape = GaugeShape.between(-120f, 120f),
            label = "CPU",
            unit = "%",
            bands = GaugeDemoData.loadBands,
            ticks = GaugeTickConfig(interval = 20.0, minorCount = 4),
            labelFormatter = whole,
            animation = animation,
            detail = detail,
            onReadingChanged = { onReading(it.bandLabel ?: "") },
            valuePosition = GaugeValuePosition.BelowCenter,
            valueContent = { animated -> Readout(whole.format(animated) + "%", "CPU") },
            modifier = modifier,
        )
        FilterChip(
            selected = running,
            onClick = { running = !running },
            label = { Text(if (running) "Pause" else "Resume") },
            modifier = Modifier.testTag("gauge-realtime-toggle"),
        )
    }
}

/** The number and its caption, inside the dial. */
@Composable
private fun Readout(value: String, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = ChartKitTheme.colors.gauge.label,
        )
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = ChartKitTheme.colors.axisLabel,
        )
    }
}

/** How often the realtime demo takes a sample. */
private const val SAMPLE_INTERVAL_MILLIS = 900L

/** The demos, and what each one is for. */
enum class GaugeDemo(val label: String, val description: String) {
    Speedometer(
        "Speedometer",
        "The reference dial: a semicircular face, 0–200 km/h, major and minor ticks, numbers, " +
            "three threshold bands, a limit marker, a needle and a pivot.",
    ),
    Semicircle(
        "Semicircle",
        "The same half dial stated as two angles — -90° to 90° — with the reading below the " +
            "pivot, where a number printed at the centre would sit under the needle.",
    ),
    FullCircle(
        "Full circle",
        "A compass over 0–360°. The first and last ticks share an angle, so only one is drawn.",
    ),
    ThreeQuarter(
        "Three-quarter",
        "The classic dashboard dial, with the gap at the bottom and bands behind the scale.",
    ),
    Bands(
        "Bands",
        "A range that crosses zero, with four bands drawn as a thin rail inside the track so " +
            "the ticks stay legible over them.",
    ),
    MultiNeedle(
        "Multi-needle",
        "Current, target and average on one dial. Needles are identified by id, so adding one " +
            "does not make its neighbours travel.",
    ),
    Interactive(
        "Interactive",
        "An adjustable dial: drag around the arc, use the arrow keys, or adjust it with a " +
            "screen reader. A tap below the arc is not a reading and does nothing.",
    ),
    CompactKpi(
        "Compact KPI",
        "At 150dp a scale is unreadable. The arc gauge carries the left tile; the dial on the " +
            "right drops its minor ticks and keeps its numbers.",
    ),
    Realtime(
        "Realtime",
        "A deterministic trace, one sample per second. Each retargets the needle from where it " +
            "is rather than restarting it.",
    ),
    CustomNeedle(
        "Custom needle",
        "A long counterweighted blade over a dial face, ticks outside the arc, a redline mark " +
            "and no track.",
    ),
}
