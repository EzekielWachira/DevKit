package io.devkit.chartdemo

import androidx.compose.foundation.background
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartDataTableView
import io.devkit.chartkit.accessibility.columns3DDataTable
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.charts.CartesianChart3D
import io.devkit.chartkit.charts.ExperimentalChartKitApi
import io.devkit.chartkit.charts.Chart3DInteraction
import io.devkit.chartkit.charts.ColumnChart3D
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.geometry.BarGrouping
import io.devkit.chartkit.layer.three.Column3DLabelPlacement
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.state.rememberChart3DCameraState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.theme.materialDerivedChartColors
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DFrame
import io.devkit.chartkit.three.Chart3DFrameGrid
import io.devkit.chartkit.three.Chart3DLighting
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Chart3DSideWall
import io.devkit.chartkit.three.Column3DArrangement
import io.devkit.chartkit.three.COLUMN_3D_AUTO_DEPTH
import io.devkit.chartkit.three.Chart3DDepth
import io.devkit.chartkit.three.Vector3D
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Every 3D column capability, one screen.
 *
 * The controls change how the chart is *drawn* and never what it says. Turning
 * the camera, switching the projection or deepening the columns moves pixels;
 * the values, the stacks, the tooltip, the announcement and the data table
 * below are identical in every one of these demos.
 */
@OptIn(ExperimentalChartKitApi::class)
@Composable
fun Chart3DScreen(modifier: Modifier = Modifier) {
    var demo by remember { mutableStateOf(ThreeDDemo.GroupedAndStacked) }
    var orthographic by remember { mutableStateOf(false) }
    var animate by remember { mutableStateOf(true) }
    var showTable by remember { mutableStateOf(false) }
    var rotationX by remember { mutableFloatStateOf(Chart3DCamera.DEFAULT_ROTATION_X.toFloat()) }
    var rotationY by remember { mutableFloatStateOf(Chart3DCamera.DEFAULT_ROTATION_Y.toFloat()) }
    var distance by remember { mutableFloatStateOf(Chart3DCamera.DEFAULT_DISTANCE.toFloat()) }
    var depth by remember { mutableFloatStateOf(COLUMN_3D_AUTO_DEPTH.toFloat()) }
    var selectionText by remember { mutableStateOf("") }

    val animation = if (animate) ChartAnimation.Default else ChartAnimation.None
    val projection = if (orthographic) {
        Chart3DProjection.Orthographic
    } else {
        Chart3DProjection.Perspective()
    }
    val whole = remember { ChartNumberFormatters.integer(Locale.UK) }

    val camera = rememberChart3DCameraState(
        rotationX = rotationX.toDouble(),
        rotationY = rotationY.toDouble(),
        distance = distance.toDouble(),
    )
    val interactiveCamera = rememberChart3DCameraState(Chart3DCamera.Presentation)
    val scope = rememberCoroutineScope()

    val harvest = remember {
        listOf(
            ChartSeries("john", "John", ThreeDDemoData.john),
            ChartSeries("jane", "Jane", ThreeDDemoData.jane),
            ChartSeries("joe", "Joe", ThreeDDemoData.joe),
            ChartSeries("janet", "Janet", ThreeDDemoData.janet),
        )
    }
    val households: (ChartSeries<ThreeDDemoData.Harvest>) -> String =
        { if (it.id == "john" || it.id == "joe") "First house" else "Second house" }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("3D columns", style = MaterialTheme.typography.titleLarge)
        Text(
            "Grouped and stacked columns projected through a camera — on the same stack " +
                "engine, axes, legend, tooltip and accessibility model as the flat bar chart.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .testTag("three-d-demos"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThreeDDemo.entries.forEach { entry ->
                FilterChip(
                    selected = demo == entry,
                    onClick = {
                        demo = entry
                        selectionText = ""
                    },
                    label = { Text(entry.label) },
                    modifier = Modifier.testTag("three-d-demo-${entry.name}"),
                )
            }
        }

        Text(demo.description, style = MaterialTheme.typography.bodySmall)

        val chartModifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .testTag("three-d-chart")

        when (demo) {
            ThreeDDemo.Basic -> ColumnChart3D(
                data = ThreeDDemoData.sales,
                category = { it.month },
                value = { it.total },
                seriesName = "Sales",
                valueAxis = ChartAxis(title = "Units"),
                categoryAxis = ChartAxis(title = "Month"),
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                onSelectionChanged = { selectionText = describe(it?.xLabel, it?.y, whole) },
                modifier = chartModifier,
            )

            ThreeDDemo.Grouped -> ColumnChart3D(
                series = harvest,
                category = { it.fruit },
                value = { it.count },
                grouping = BarGrouping.Grouped,
                valueAxis = ChartAxis(title = "Picked"),
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                legendTogglesSeries = true,
                onSelectionChanged = { selectionText = describe(it?.xLabel, it?.y, whole) },
                modifier = chartModifier,
            )

            ThreeDDemo.Stacked -> ColumnChart3D(
                series = harvest,
                category = { it.fruit },
                value = { it.count },
                grouping = BarGrouping.Stacked,
                // One stack id for every series: one pile per category.
                stack = { "everyone" },
                valueAxis = ChartAxis(title = "Picked"),
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                legendTogglesSeries = true,
                onSelectionChanged = { selectionText = describe(it?.xLabel, it?.y, whole) },
                modifier = chartModifier,
            )

            ThreeDDemo.GroupedAndStacked -> ColumnChart3D(
                series = harvest,
                category = { it.fruit },
                value = { it.count },
                grouping = BarGrouping.Stacked,
                stack = households,
                valueAxis = ChartAxis(title = "Picked"),
                categoryAxis = ChartAxis(title = "Fruit"),
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                legendTogglesSeries = true,
                onSelectionChanged = { selectionText = describe(it?.xLabel, it?.y, whole) },
                modifier = chartModifier,
            )

            ThreeDDemo.DepthRows -> ColumnChart3D(
                series = harvest,
                category = { it.fruit },
                value = { it.count },
                grouping = BarGrouping.Stacked,
                stack = households,
                // The same data as the demo above, arranged one pile behind the
                // other rather than side by side.
                arrangement = Column3DArrangement.Depth,
                valueAxis = ChartAxis(title = "Picked"),
                cameraState = camera,
                projection = projection,
                animation = animation,
                valueFormatter = whole,
                onSelectionChanged = { selectionText = describe(it?.xLabel, it?.y, whole) },
                modifier = chartModifier,
            )

            ThreeDDemo.Percent -> ColumnChart3D(
                series = harvest,
                category = { it.fruit },
                value = { it.count },
                grouping = BarGrouping.StackedPercent,
                stack = households,
                valueAxis = ChartAxis(title = "Share"),
                cameraState = camera,
                projection = projection,
                animation = animation,
                onSelectionChanged = { selectionText = describe(it?.xLabel, it?.y, whole) },
                modifier = chartModifier,
            )

            ThreeDDemo.Negative -> ColumnChart3D(
                series = remember {
                    listOf(
                        ChartSeries("trading", "Trading", ThreeDDemoData.trading),
                        ChartSeries("fx", "FX", ThreeDDemoData.currency),
                    )
                },
                category = { it.label },
                value = { it.value },
                grouping = BarGrouping.Stacked,
                stack = { "result" },
                valueAxis = ChartAxis(title = "£m"),
                cameraState = camera,
                projection = projection,
                animation = animation,
                valueFormatter = whole,
                onSelectionChanged = { selectionText = describe(it?.xLabel, it?.y, whole) },
                modifier = chartModifier,
            )

            ThreeDDemo.NullAndZero -> ColumnChart3D(
                series = remember {
                    listOf(
                        ChartSeries("measured", "Measured", ThreeDDemoData.measured),
                        ChartSeries("recorded", "Recorded", ThreeDDemoData.recorded),
                    )
                },
                category = { it.label },
                value = { it.value },
                grouping = BarGrouping.Grouped,
                valueAxis = ChartAxis(title = "Reading"),
                cameraState = camera,
                projection = projection,
                animation = animation,
                valueLabels = Column3DLabelPlacement.Top,
                valueFormatter = whole,
                onSelectionChanged = { selectionText = describe(it?.xLabel, it?.y, whole) },
                modifier = chartModifier,
            )

            ThreeDDemo.InteractiveCamera -> ColumnChart3D(
                series = harvest,
                category = { it.fruit },
                value = { it.count },
                grouping = BarGrouping.Stacked,
                stack = households,
                cameraState = interactiveCamera,
                projection = projection,
                interaction = Chart3DInteraction.RotateAndSelect,
                animation = animation,
                valueFormatter = whole,
                onSelectionChanged = { selectionText = describe(it?.xLabel, it?.y, whole) },
                modifier = chartModifier,
            )

            ThreeDDemo.Lighting -> ColumnChart3D(
                series = harvest,
                category = { it.fruit },
                value = { it.count },
                grouping = BarGrouping.Grouped,
                cameraState = camera,
                projection = projection,
                // A harder light from further round to the side: the same
                // geometry, more separation between the faces.
                lighting = Chart3DLighting(
                    ambient = 0.45,
                    diffuse = 0.55,
                    direction = Vector3D(0.7, -0.6, 0.4),
                ),
                animation = animation,
                valueFormatter = whole,
                modifier = chartModifier,
            )

            ThreeDDemo.Frame -> ColumnChart3D(
                series = harvest,
                category = { it.fruit },
                value = { it.count },
                grouping = BarGrouping.Stacked,
                stack = households,
                cameraState = camera,
                projection = projection,
                frame = Chart3DFrame(
                    floor = true,
                    back = true,
                    side = Chart3DSideWall.Auto,
                    grid = Chart3DFrameGrid.Both,
                    opacity = 0.8f,
                ),
                animation = animation,
                valueFormatter = whole,
                modifier = chartModifier,
            )

            ThreeDDemo.NoFrame -> ColumnChart3D(
                series = harvest,
                category = { it.fruit },
                value = { it.count },
                grouping = BarGrouping.Grouped,
                cameraState = camera,
                projection = projection,
                frame = Chart3DFrame.None,
                animation = animation,
                valueFormatter = whole,
                modifier = chartModifier,
            )

            ThreeDDemo.DarkTheme -> ChartKitTheme(
                colors = materialDerivedChartColors(isDark = true),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF101418))
                        .padding(8.dp),
                ) {
                    ColumnChart3D(
                        series = harvest,
                        category = { it.fruit },
                        value = { it.count },
                        grouping = BarGrouping.Stacked,
                        stack = households,
                        cameraState = camera,
                        projection = projection,
                        animation = animation,
                        valueFormatter = whole,
                        modifier = chartModifier,
                    )
                }
            }

            ThreeDDemo.Density -> ColumnChart3D(
                series = remember {
                    ThreeDDemoData.dense.mapIndexed { index, values ->
                        ChartSeries("s$index", "Team ${index + 1}", values)
                    }
                },
                category = { it.fruit },
                value = { it.count },
                grouping = BarGrouping.Stacked,
                stack = { "all" },
                cameraState = camera,
                projection = projection,
                legend = LegendPosition.None,
                animation = animation,
                valueFormatter = whole,
                modifier = chartModifier,
            )

            ThreeDDemo.LowLevel -> CartesianChart3D(
                cameraState = camera,
                projection = projection,
                valueAxis = ChartAxis(title = "Picked"),
                categoryAxis = ChartAxis(title = "Fruit"),
                state = rememberChartState(),
                modifier = chartModifier,
            ) {
                columns(
                    series = harvest,
                    category = { it.fruit },
                    value = { it.count },
                    grouping = BarGrouping.Stacked,
                    stack = households,
                    depth = Chart3DDepth.Relative(depth.toDouble()),
                    valueLabels = Column3DLabelPlacement.Auto,
                )
            }
        }

        if (selectionText.isNotEmpty()) {
            Text(
                selectionText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("three-d-selection"),
            )
        }

        HorizontalDivider()
        Text("Camera", style = MaterialTheme.typography.titleSmall)
        Text(
            "Rotation, distance and projection change the view and nothing else. Under " +
                "orthographic projection two equal values are drawn at equal heights wherever " +
                "they stand, which is the reading to prefer when depth is grouping rather " +
                "than decoration.",
            style = MaterialTheme.typography.bodySmall,
        )

        LabelledSlider("Pitch", rotationX, -5f..70f) { rotationX = it }
        LabelledSlider("Yaw", rotationY, -55f..55f) { rotationY = it }
        LabelledSlider("Distance", distance, 1.6f..8f) { distance = it }
        LabelledSlider("Depth", depth, 0.2f..1.6f) { depth = it }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = orthographic,
                onClick = { orthographic = !orthographic },
                label = { Text("Orthographic") },
                modifier = Modifier.testTag("three-d-orthographic"),
            )
            FilterChip(
                selected = animate,
                onClick = { animate = !animate },
                label = { Text("Animate") },
            )
            TextButton(
                onClick = {
                    rotationX = Chart3DCamera.DEFAULT_ROTATION_X.toFloat()
                    rotationY = Chart3DCamera.DEFAULT_ROTATION_Y.toFloat()
                    distance = Chart3DCamera.DEFAULT_DISTANCE.toFloat()
                    depth = COLUMN_3D_AUTO_DEPTH.toFloat()
                    scope.launch { interactiveCamera.animateTo(Chart3DCamera.Presentation) }
                },
                modifier = Modifier.testTag("three-d-reset"),
            ) { Text("Reset view") }
        }

        HorizontalDivider()
        Text("Accessibility", style = MaterialTheme.typography.titleSmall)
        Text(
            "The chart announces categories, series, values and stack totals — never faces, " +
                "depths or camera angles. Perspective makes precise magnitude comparison " +
                "harder than a flat bar chart does, so the same numbers are also available " +
                "as a table.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = { showTable = !showTable },
            modifier = Modifier.testTag("three-d-table-toggle"),
        ) {
            Text(if (showTable) "Hide data table" else "View data table")
        }
        if (showTable) {
            ChartDataTableView(
                table = columns3DDataTable(
                    series = harvest,
                    category = { it.fruit },
                    value = { it.count },
                    stack = households,
                    valueFormatter = whole,
                    caption = "Fruit picked, by person and household",
                ),
                modifier = Modifier.testTag("three-d-table"),
            )
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(
            "$label: ${"%.1f".format(Locale.UK, value)}",
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.testTag("three-d-slider-$label"),
        )
    }
}

private fun describe(
    label: String?,
    value: Double?,
    formatter: io.devkit.chartkit.formatter.ChartValueFormatter,
): String =
    if (label == null || value == null) "" else "$label: ${formatter.format(value)}"

/** One demo each, because each shows something none of the others does. */
internal enum class ThreeDDemo(val label: String, val description: String) {
    GroupedAndStacked(
        "Grouped + stacked",
        "Two households per fruit, each a stack of two pickers. The reference " +
            "arrangement: stacking within a pile, grouping between piles.",
    ),
    Basic(
        "Basic",
        "One series, one column per month. The plainest 3D chart there is.",
    ),
    Grouped(
        "Grouped",
        "Four series side by side in each band, each standing on the floor.",
    ),
    Stacked(
        "Stacked",
        "One pile per fruit. Every series shares a footprint and accumulates upward.",
    ),
    DepthRows(
        "Depth rows",
        "The same two stacks, one behind the other instead of side by side.",
    ),
    Percent(
        "100% stacked",
        "Each household normalised to its own total, so the piles compare shares " +
            "rather than counts.",
    ),
    Negative(
        "Negative values",
        "Quarters that went both ways. Positive and negative segments accumulate " +
            "on their own side of the baseline.",
    ),
    NullAndZero(
        "Null vs zero",
        "East has no reading and draws no column; South measured zero and draws a " +
            "flat one that is still in the tooltip and the table.",
    ),
    InteractiveCamera(
        "Interactive camera",
        "Drag to turn the chart, pinch to move closer, and tap to select. Off by " +
            "default elsewhere: a chart at an angle nobody chose is a chart two " +
            "readers see differently.",
    ),
    Lighting(
        "Custom lighting",
        "A harder light further round to the side. Shading separates the faces; it " +
            "carries no data.",
    ),
    Frame(
        "3D frame",
        "Floor, back wall and the far side wall, with value grid lines projected " +
            "onto them.",
    ),
    NoFrame(
        "No frame",
        "The same chart with the frame off, for a dense dashboard tile.",
    ),
    DarkTheme(
        "Dark theme",
        "The same geometry over a dark surface. Shading is a multiplier on the " +
            "series colour, so it darkens rather than washing out.",
    ),
    Density(
        "Twenty categories",
        "Six series over twenty bands. 3D is not the right chart for this, and the " +
            "demo is here so the cost is visible rather than described.",
    ),
    LowLevel(
        "Low-level API",
        "The same chart declared through CartesianChart3D { columns(...) }, which is " +
            "what the convenience overloads delegate to.",
    ),
}
