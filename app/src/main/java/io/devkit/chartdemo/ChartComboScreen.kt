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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartDataTableView
import io.devkit.chartkit.accessibility.ComboMeasure
import io.devkit.chartkit.accessibility.comboDataTable
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.annotation.horizontalRule
import io.devkit.chartkit.axis.AxisDimension
import io.devkit.chartkit.axis.AxisGridMode
import io.devkit.chartkit.axis.AxisPosition
import io.devkit.chartkit.axis.AxisStyleMode
import io.devkit.chartkit.axis.AxisTickAlignment
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartAxisId
import io.devkit.chartkit.axis.ChartUnit
import io.devkit.chartkit.charts.CartesianChart
import io.devkit.chartkit.charts.ExperimentalChartKitApi
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.interaction.CrosshairConfig
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.model.ChartTooltipOrder
import io.devkit.chartkit.scale.DomainPolicy
import io.devkit.chartkit.state.rememberChartState
import java.util.Locale

// The axes, named once. Ids are constants rather than string literals at each
// call site for the reason [ChartAxisId] gives: a typo in a literal is a
// configuration error at runtime, and a typo in a constant is a compile error.
private val RainfallAxis = ChartAxisId("rainfall")
private val TemperatureAxis = ChartAxisId("temperature")
private val PressureAxis = ChartAxisId("pressure")

private val BookingsAxis = ChartAxisId("bookings")
private val RevenueAxis = ChartAxisId("revenue")
private val ConversionAxis = ChartAxisId("conversion")

private val PriceAxis = ChartAxisId("price")
private val VolumeAxis = ChartAxisId("volume")

private val ProfitAxis = ChartAxisId("profit")
private val MarginAxis = ChartAxisId("margin")

private val Millimetres = ChartUnit.Custom("mm", "millimetres")
private val Celsius = ChartUnit.Custom("°C", "degrees Celsius")
private val Hectopascals = ChartUnit.Custom("hPa", "hectopascals")

/**
 * Multi-axis combo charts, one screen.
 *
 * Each demo exists because it shows something the others do not: three units on
 * one X, a business case that is not weather, a financial case where the second
 * axis is volume, legend toggling that removes an axis, tick alignment, zero
 * alignment, and a shared crosshair reading three quantities at once.
 *
 * Every one of them is the same `CartesianChart` — there is no combo chart type
 * in ChartKit, and adding one would have been the wrong answer to all six.
 */
@OptIn(ExperimentalChartKitApi::class)
@Composable
fun ChartComboScreen(modifier: Modifier = Modifier) {
    var demo by remember { mutableStateOf(ComboDemo.Weather) }
    var aligned by remember { mutableStateOf(false) }
    var animate by remember { mutableStateOf(true) }
    var readout by remember { mutableStateOf("Tap or drag across the plot") }

    val animation = if (animate) ChartAnimation.Default else ChartAnimation.None
    val alignment = if (aligned) AxisTickAlignment.Aligned else AxisTickAlignment.Independent
    val locale = Locale.UK
    val money = remember { ChartNumberFormatters.currency("GBP", decimals = 0, locale = locale) }
    val counts = remember { ChartNumberFormatters.integer(locale) }
    val oneDecimal = remember { ChartNumberFormatters.decimal(decimals = 1, locale = locale) }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Multi-axis combo charts", style = MaterialTheme.typography.titleLarge)
        Text(
            "One shared X domain, several value axes with their own units, scales and " +
                "formatters, and any mix of layers bound to them by name.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .testTag("combo-demos"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ComboDemo.entries.forEach { entry ->
                FilterChip(
                    selected = demo == entry,
                    onClick = {
                        demo = entry
                        readout = "Tap or drag across the plot"
                    },
                    label = { Text(entry.label) },
                    modifier = Modifier.testTag("combo-demo-${entry.name}"),
                )
            }
        }

        Text(demo.description, style = MaterialTheme.typography.bodySmall)

        val chartModifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .testTag("combo-chart")

        when (demo) {
            ComboDemo.Weather -> WeatherCombo(
                alignment = alignment,
                animation = animation,
                onReadout = { readout = it },
                modifier = chartModifier,
            )

            ComboDemo.Business -> BusinessCombo(
                alignment = alignment,
                animation = animation,
                money = money,
                counts = counts,
                onReadout = { readout = it },
                modifier = chartModifier,
            )

            ComboDemo.Financial -> FinancialCombo(
                animation = animation,
                onReadout = { readout = it },
                modifier = chartModifier,
            )

            ComboDemo.Toggle -> ToggleCombo(
                alignment = alignment,
                animation = animation,
                onReadout = { readout = it },
                modifier = chartModifier,
            )

            ComboDemo.ZeroAlignment -> ZeroAlignedCombo(
                animation = animation,
                oneDecimal = oneDecimal,
                onReadout = { readout = it },
                modifier = chartModifier,
            )

            ComboDemo.Crosshair -> CrosshairCombo(
                alignment = alignment,
                animation = animation,
                onReadout = { readout = it },
                modifier = chartModifier,
            )
        }

        Text(readout, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("combo-readout"))

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = aligned,
                onClick = { aligned = !aligned },
                label = { Text("Aligned ticks") },
                modifier = Modifier.testTag("combo-aligned"),
            )
            FilterChip(
                selected = animate,
                onClick = { animate = !animate },
                label = { Text("Animate") },
                modifier = Modifier.testTag("combo-animate"),
            )
        }

        Text(
            if (aligned) {
                "Aligned: each axis is widened to the same number of round intervals, so the " +
                    "same gridline is the same tick on all three."
            } else {
                "Independent: each axis picks its own round numbers. The gridlines belong to " +
                    "the primary axis and to no other."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        if (demo == ComboDemo.Weather) {
            HorizontalDivider()
            Text("Data table", style = MaterialTheme.typography.titleMedium)
            // Every value with its unit beside it. A combo chart's table without
            // one is a column of numbers that cannot be read.
            ChartDataTableView(
                table = comboDataTable(
                    ComboMeasure(
                        axisTitle = "Rainfall",
                        series = listOf(ChartSeries("rainfall", "Rainfall", ComboDemoData.weather)),
                        category = { it.month },
                        value = { it.rainfall },
                        unit = Millimetres,
                    ),
                    ComboMeasure(
                        axisTitle = "Temperature",
                        series = listOf(ChartSeries("temperature", "Temperature", ComboDemoData.weather)),
                        category = { it.month },
                        value = { it.temperature },
                        unit = Celsius,
                        valueFormatter = oneDecimal,
                    ),
                    ComboMeasure(
                        axisTitle = "Pressure",
                        series = listOf(ChartSeries("pressure", "Pressure", ComboDemoData.weather)),
                        category = { it.month },
                        value = { it.pressure },
                        unit = Hectopascals,
                    ),
                    caption = "Monthly weather in three units",
                    xColumn = "Month",
                ),
                modifier = Modifier.testTag("combo-table"),
            )
        }
    }
}

/**
 * Rainfall as bars, temperature and pressure as lines, three Y axes.
 *
 * The reference case: one X domain, three quantities that have nothing in
 * common but the month they were measured in.
 */
@OptIn(ExperimentalChartKitApi::class)
@Composable
private fun WeatherCombo(
    alignment: AxisTickAlignment,
    animation: ChartAnimation,
    onReadout: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CartesianChart(
        modifier = modifier,
        animation = animation,
        tickAlignment = alignment,
        legend = LegendPosition.Bottom,
        crosshair = CrosshairConfig.Vertical,
        interaction = ChartInteraction.Default,
        tooltipOrder = ChartTooltipOrder.ByAxis(listOf(RainfallAxis, TemperatureAxis, PressureAxis)),
        onSelectionChanged = { selection ->
            onReadout(selection?.let { "${it.seriesName} at ${it.xLabel}" } ?: "Nothing selected")
        },
    ) {
        yAxis(
            id = RainfallAxis,
            position = AxisPosition.Start,
            title = "Rainfall",
            unit = Millimetres,
            // Bars are lengths from a baseline, so this one keeps zero.
            domain = DomainPolicy.IncludeZero(),
            grid = AxisGridMode.Primary,
            primary = true,
        )
        yAxis(
            id = TemperatureAxis,
            position = AxisPosition.End,
            title = "Temperature",
            unit = Celsius,
            // A line encodes position, not length: forcing zero onto 4..18°C
            // would flatten the only variation worth seeing.
            domain = DomainPolicy.Auto(),
            style = AxisStyleMode.MatchSeries,
        )
        yAxis(
            id = PressureAxis,
            position = AxisPosition.End,
            title = "Pressure",
            unit = Hectopascals,
            domain = DomainPolicy.Auto(),
            style = AxisStyleMode.MatchSeries,
        )

        bars(
            series = listOf(ChartSeries("rainfall", "Rainfall", ComboDemoData.weather, unit = Millimetres)),
            category = { it.month },
            value = { it.rainfall },
            yAxis = RainfallAxis,
        )
        line(
            series = listOf(ChartSeries("temperature", "Temperature", ComboDemoData.weather, unit = Celsius)),
            x = { it.month },
            y = { it.temperature },
            yAxis = TemperatureAxis,
        )
        line(
            series = listOf(ChartSeries("pressure", "Pressure", ComboDemoData.weather, unit = Hectopascals)),
            x = { it.month },
            y = { it.pressure },
            yAxis = PressureAxis,
        )
    }
}

/** Bookings as bars, revenue and conversion as lines: the same engine, no weather. */
@OptIn(ExperimentalChartKitApi::class)
@Composable
private fun BusinessCombo(
    alignment: AxisTickAlignment,
    animation: ChartAnimation,
    money: io.devkit.chartkit.formatter.ChartValueFormatter,
    counts: io.devkit.chartkit.formatter.ChartValueFormatter,
    onReadout: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CartesianChart(
        modifier = modifier,
        animation = animation,
        tickAlignment = alignment,
        legend = LegendPosition.Bottom,
        crosshair = CrosshairConfig.Vertical,
        onSelectionChanged = { selection ->
            onReadout(selection?.let { "${it.seriesName} in ${it.xLabel}" } ?: "Nothing selected")
        },
    ) {
        yAxis(
            id = BookingsAxis,
            position = AxisPosition.Start,
            title = "Bookings",
            unit = ChartUnit.Count,
            axis = ChartAxis(valueFormatter = counts),
            domain = DomainPolicy.IncludeZero(),
            primary = true,
        )
        yAxis(
            id = RevenueAxis,
            position = AxisPosition.End,
            title = "Revenue",
            unit = ChartUnit.Currency("GBP"),
            // The axis' own formatter writes the currency, so the unit symbol
            // is not repeated after it.
            axis = ChartAxis(valueFormatter = money),
            domain = DomainPolicy.IncludeZero(),
            style = AxisStyleMode.MatchSeries,
        )
        yAxis(
            id = ConversionAxis,
            position = AxisPosition.End,
            title = "Conversion",
            unit = ChartUnit.Percent,
            domain = DomainPolicy.Auto(),
            style = AxisStyleMode.MatchSeries,
        )

        bars(
            series = listOf(ChartSeries("bookings", "Bookings", ComboDemoData.business)),
            category = { it.month },
            value = { it.bookings },
            yAxis = BookingsAxis,
        )
        line(
            series = listOf(ChartSeries("revenue", "Revenue", ComboDemoData.business)),
            x = { it.month },
            y = { it.revenue },
            yAxis = RevenueAxis,
        )
        line(
            series = listOf(ChartSeries("conversion", "Conversion", ComboDemoData.business)),
            x = { it.month },
            y = { it.conversion },
            yAxis = ConversionAxis,
        )
    }
}

/**
 * Candles and a moving average on the price axis; volume on its own.
 *
 * The financial case is the one where a second axis is least controversial:
 * price and volume are different quantities by orders of magnitude, and nobody
 * reads them against each other. The moving average shares the price axis
 * because it *is* a price.
 */
@OptIn(ExperimentalChartKitApi::class)
@Composable
private fun FinancialCombo(
    animation: ChartAnimation,
    onReadout: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CartesianChart(
        modifier = modifier,
        animation = animation,
        legend = LegendPosition.Bottom,
        crosshair = CrosshairConfig.Vertical,
        onSelectionChanged = { selection ->
            onReadout(selection?.let { "${it.seriesName}: ${it.y}" } ?: "Nothing selected")
        },
    ) {
        yAxis(
            id = PriceAxis,
            position = AxisPosition.End,
            title = "Price",
            unit = ChartUnit.Currency("USD"),
            domain = DomainPolicy.Auto(),
            primary = true,
        )
        yAxis(
            id = VolumeAxis,
            position = AxisPosition.Start,
            title = "Volume",
            unit = ChartUnit.Count,
            // Deliberately three times the tallest bar, so volume occupies the
            // bottom third and the candles above it stay readable. A volume
            // axis fitted to its own data fills the plot and buries the price
            // series it exists to annotate — which is a charting convention,
            // not something the engine should decide.
            domain = DomainPolicy.Fixed(0.0, ComboDemoData.trading.maxOf { it.volume } * 3.0),
            axis = ChartAxis(valueFormatter = ChartNumberFormatters.compact()),
            grid = AxisGridMode.Hidden,
        )

        volume(
            data = ComboDemoData.trading,
            x = { it.at },
            volume = { it.volume },
            open = { it.open },
            close = { it.close },
            yAxis = VolumeAxis,
        )
        candles(
            data = ComboDemoData.trading,
            x = { it.at },
            open = { it.open },
            high = { it.high },
            low = { it.low },
            close = { it.close },
            yAxis = PriceAxis,
        )
        line(
            series = listOf(ChartSeries("ma5", "5-day MA", ComboDemoData.movingAverage())),
            x = { it.at },
            y = { it.close },
            yAxis = PriceAxis,
        )
    }
}

/**
 * Legend toggling, so the axis auto-hide is visible.
 *
 * Hide "Pressure" and the pressure axis goes with it — it was measuring
 * nothing — and the plot gets its gutter back.
 */
@OptIn(ExperimentalChartKitApi::class)
@Composable
private fun ToggleCombo(
    alignment: AxisTickAlignment,
    animation: ChartAnimation,
    onReadout: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberChartState<Any?>()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CartesianChart(
            modifier = modifier,
            animation = animation,
            tickAlignment = alignment,
            legend = LegendPosition.Bottom,
            legendTogglesSeries = true,
            state = state,
            crosshair = CrosshairConfig.Vertical,
            onSelectionChanged = { selection ->
                onReadout(selection?.let { "${it.seriesName} at ${it.xLabel}" } ?: "Nothing selected")
            },
        ) {
            yAxis(
                id = RainfallAxis,
                position = AxisPosition.Start,
                title = "Rainfall",
                unit = Millimetres,
                domain = DomainPolicy.IncludeZero(),
                primary = true,
            )
            yAxis(
                id = TemperatureAxis,
                position = AxisPosition.End,
                title = "Temperature",
                unit = Celsius,
                domain = DomainPolicy.Auto(),
            )
            yAxis(
                id = PressureAxis,
                position = AxisPosition.End,
                title = "Pressure",
                unit = Hectopascals,
                domain = DomainPolicy.Auto(),
            )

            bars(
                series = listOf(ChartSeries("rainfall", "Rainfall", ComboDemoData.weather)),
                category = { it.month },
                value = { it.rainfall },
                yAxis = RainfallAxis,
            )
            line(
                series = listOf(ChartSeries("temperature", "Temperature", ComboDemoData.weather)),
                x = { it.month },
                y = { it.temperature },
                yAxis = TemperatureAxis,
            )
            line(
                series = listOf(ChartSeries("pressure", "Pressure", ComboDemoData.weather)),
                x = { it.month },
                y = { it.pressure },
                yAxis = PressureAxis,
            )
        }
        Text(
            "Hidden: " + state.hiddenSeriesIds.sorted().joinToString().ifEmpty { "none" },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("combo-hidden"),
        )
    }
}

/**
 * Two quantities that both cross zero, with their zeros on one row.
 *
 * Without alignment the baselines sit at different heights and the chart claims
 * a month was profitable on one measure and not the other when both agree.
 */
@OptIn(ExperimentalChartKitApi::class)
@Composable
private fun ZeroAlignedCombo(
    animation: ChartAnimation,
    oneDecimal: io.devkit.chartkit.formatter.ChartValueFormatter,
    onReadout: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CartesianChart(
        modifier = modifier,
        animation = animation,
        tickAlignment = AxisTickAlignment.Aligned,
        legend = LegendPosition.Bottom,
        crosshair = CrosshairConfig.Vertical,
        onSelectionChanged = { selection ->
            onReadout(selection?.let { "${it.seriesName} in ${it.xLabel}" } ?: "Nothing selected")
        },
    ) {
        yAxis(
            id = ProfitAxis,
            position = AxisPosition.Start,
            title = "Profit change",
            unit = ChartUnit.Custom("£k", "thousand pounds"),
            domain = DomainPolicy.Auto(),
            alignZero = true,
            primary = true,
        )
        yAxis(
            id = MarginAxis,
            position = AxisPosition.End,
            title = "Margin change",
            unit = ChartUnit.Custom("pp", "percentage points"),
            axis = ChartAxis(valueFormatter = oneDecimal),
            domain = DomainPolicy.Auto(),
            alignZero = true,
        )

        bars(
            series = listOf(ChartSeries("profit", "Profit change", ComboDemoData.changes)),
            category = { it.month },
            value = { it.profitChange },
            yAxis = ProfitAxis,
        )
        line(
            series = listOf(ChartSeries("margin", "Margin change", ComboDemoData.changes)),
            x = { it.month },
            y = { it.marginChange },
            yAxis = MarginAxis,
        )
    }
}

/**
 * A shared crosshair reading three quantities, with a threshold on one of them.
 *
 * The annotation names its axis: a rule at 15 means 15°C, and on a chart whose
 * primary axis is rainfall an unqualified one would have been drawn at 15mm.
 */
@OptIn(ExperimentalChartKitApi::class)
@Composable
private fun CrosshairCombo(
    alignment: AxisTickAlignment,
    animation: ChartAnimation,
    onReadout: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CartesianChart(
        modifier = modifier,
        animation = animation,
        tickAlignment = alignment,
        legend = LegendPosition.Bottom,
        crosshair = CrosshairConfig(enabled = true, vertical = true, axisValueLabels = true),
        sharedTooltip = true,
        annotations = listOf(
            horizontalRule(
                value = 15.0,
                label = "Warm",
                valueAxis = TemperatureAxis,
                extendsDomain = false,
            ),
        ),
        onSelectionChanged = { selection ->
            onReadout(selection?.let { "${it.xLabel}: crosshair across all three axes" } ?: "Nothing selected")
        },
    ) {
        yAxis(
            id = RainfallAxis,
            position = AxisPosition.Start,
            title = "Rainfall",
            unit = Millimetres,
            domain = DomainPolicy.IncludeZero(),
            primary = true,
        )
        yAxis(
            id = TemperatureAxis,
            position = AxisPosition.End,
            title = "Temperature",
            unit = Celsius,
            domain = DomainPolicy.Auto(),
        )
        yAxis(
            id = PressureAxis,
            position = AxisPosition.End,
            title = "Pressure",
            unit = Hectopascals,
            domain = DomainPolicy.Auto(),
        )

        bars(
            series = listOf(ChartSeries("rainfall", "Rainfall", ComboDemoData.weather)),
            category = { it.month },
            value = { it.rainfall },
            yAxis = RainfallAxis,
        )
        line(
            series = listOf(ChartSeries("temperature", "Temperature", ComboDemoData.weather)),
            x = { it.month },
            y = { it.temperature },
            yAxis = TemperatureAxis,
        )
        line(
            series = listOf(ChartSeries("pressure", "Pressure", ComboDemoData.weather)),
            x = { it.month },
            y = { it.pressure },
            yAxis = PressureAxis,
        )
    }
}

/** The demos, and what each one is for. */
enum class ComboDemo(val label: String, val description: String) {
    Weather(
        "Weather",
        "Rainfall in millimetres as bars, temperature in °C and pressure in hPa as lines. " +
            "One X domain, three Y axes, three formatters — and one grid, owned by rainfall.",
    ),
    Business(
        "Business",
        "Bookings as a count, revenue in pounds, conversion in percent. The same engine as " +
            "the weather chart with nothing weather-specific in it.",
    ),
    Financial(
        "Financial",
        "Candles and a 5-day moving average on the price axis; volume on its own, with no " +
            "grid of its own. Two axes because the quantities differ by four orders of magnitude.",
    ),
    Toggle(
        "Legend toggle",
        "Tap a legend entry. Hiding the only series on an axis hides the axis too — it was " +
            "measuring nothing — and the plot takes back the gutter.",
    ),
    ZeroAlignment(
        "Zero alignment",
        "Profit change and margin change, both crossing zero, with their zeros on the same " +
            "row. Without it the two baselines sit at different heights.",
    ),
    Crosshair(
        "Crosshair",
        "One vertical guide, a chip on each axis in its own unit, and a threshold annotation " +
            "bound to the temperature axis rather than to the primary one.",
    ),
}
