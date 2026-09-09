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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.accessibility.ChartDataTableView
import io.devkit.chartkit.accessibility.pieDataTable
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.Chart3DInteraction
import io.devkit.chartkit.charts.DonutChart3D
import io.devkit.chartkit.charts.PieChart3D
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.layer.polar.SliceLabelContent
import io.devkit.chartkit.layer.polar.SliceLabelPosition
import io.devkit.chartkit.state.rememberChart3DCameraState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.theme.materialDerivedChartColors
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DCameraLimits
import io.devkit.chartkit.three.Chart3DDepth
import io.devkit.chartkit.three.Chart3DLighting
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Chart3DQuality
import io.devkit.chartkit.three.Vector3D
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Every 3D pie and donut capability, one screen.
 *
 * The controls change how the chart is *drawn* and never what it says. Turning
 * the camera, switching the projection, deepening the extrusion or exploding a
 * slice moves pixels; the values, the shares, the tooltip, the announcement and
 * the data table below are identical in every one of these demos and at every
 * camera angle.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun RadialChart3DScreen(modifier: Modifier = Modifier) {
    var demo by remember { mutableStateOf(RadialDemo.BasicPie) }
    var orthographic by remember { mutableStateOf(false) }
    var animate by remember { mutableStateOf(true) }
    var showTable by remember { mutableStateOf(false) }
    var quarterTwo by remember { mutableStateOf(false) }
    var rotationX by remember { mutableFloatStateOf(Chart3DCamera.Radial.rotationX.toFloat()) }
    var rotationY by remember { mutableFloatStateOf(Chart3DCamera.Radial.rotationY.toFloat()) }
    var distance by remember { mutableFloatStateOf(Chart3DCamera.Radial.distance.toFloat()) }
    var depth by remember { mutableFloatStateOf(0.25f) }
    var selectionText by remember { mutableStateOf("") }

    val animation = if (animate) ChartAnimation.Default else ChartAnimation.None
    val projection = if (orthographic) {
        Chart3DProjection.Orthographic
    } else {
        Chart3DProjection.Perspective()
    }
    val whole = remember { ChartNumberFormatters.integer(Locale.UK) }
    val money = remember { ChartNumberFormatters.compact(locale = Locale.UK) }

    val camera = rememberChart3DCameraState(
        camera = Chart3DCamera(
            rotationX = rotationX.toDouble(),
            rotationY = rotationY.toDouble(),
            distance = distance.toDouble(),
        ),
        limits = Chart3DCameraLimits.Radial,
    )
    // A second, independent camera for the demos that are *about* moving it —
    // so dragging the interactive chart does not fight the sliders above.
    val interactiveCamera = rememberChart3DCameraState(
        camera = Chart3DCamera.Radial,
        limits = Chart3DCameraLimits.Radial,
    )
    val scope = rememberCoroutineScope()
    val pieState = rememberChartState<RadialDemoData.Share>()

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("3D pie and donut", style = MaterialTheme.typography.titleLarge)
        Text(
            "Extruded radial slices on the same scene, camera, projection, lighting, depth " +
                "sorting and hit testing as the 3D columns — and on the same slice engine, " +
                "legend, tooltip and accessibility model as the flat pie.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .testTag(RadialDemoTestTags.Demos),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadialDemo.entries.forEach { entry ->
                FilterChip(
                    selected = demo == entry,
                    onClick = {
                        demo = entry
                        selectionText = ""
                        pieState.clearSelection()
                    },
                    label = { Text(entry.label) },
                    modifier = Modifier.testTag(RadialDemoTestTags.demo(entry.name)),
                )
            }
        }

        Text(demo.description, style = MaterialTheme.typography.bodySmall)

        val chartModifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .testTag(RadialDemoTestTags.Chart)

        when (demo) {
            RadialDemo.BasicPie -> PieChart3D(
                data = RadialDemoData.browsers,
                value = { it.users },
                label = { it.name },
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                state = pieState,
                onSelectionChanged = { selectionText = describeSlice(it?.xLabel, it?.y, whole) },
                modifier = chartModifier,
            )

            RadialDemo.BasicDonut -> DonutChart3D(
                data = RadialDemoData.browsers,
                value = { it.users },
                label = { it.name },
                innerRadiusRatio = 0.55f,
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                state = pieState,
                onSelectionChanged = { selectionText = describeSlice(it?.xLabel, it?.y, whole) },
                modifier = chartModifier,
            )

            // The reference-parity pie: extrusion, a tilted camera, shaded
            // surfaces, labels with leader lines, a tooltip, a legend and a
            // slice that pops out when it is selected.
            RadialDemo.ReferencePie -> PieChart3D(
                data = RadialDemoData.browsers,
                value = { it.users },
                label = { it.name },
                labelPosition = SliceLabelPosition.Outside,
                labelContent = SliceLabelContent.LabelAndPercentage,
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                legend = LegendPosition.Bottom,
                state = pieState,
                onSelectionChanged = { selectionText = describeSlice(it?.xLabel, it?.y, whole) },
                modifier = chartModifier,
            )

            // The reference-parity donut: a hole, an inner wall, an outer wall
            // and everything the pie above has.
            RadialDemo.ReferenceDonut -> DonutChart3D(
                data = RadialDemoData.budget,
                value = { it.amount },
                label = { it.category },
                innerRadiusRatio = 0.5f,
                labelPosition = SliceLabelPosition.Outside,
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = money,
                onSelectionChanged = { selectionText = describeSlice(it?.xLabel, it?.y, money) },
                modifier = chartModifier,
            )

            RadialDemo.ExplodedPie -> PieChart3D(
                data = RadialDemoData.browsers,
                value = { it.users },
                label = { it.name },
                // Permanently out, whatever is selected — the way a chart calls
                // attention to one category in a deck.
                explode = { it.name == "Safari" },
                labelPosition = SliceLabelPosition.Outside,
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                state = pieState,
                onSelectionChanged = { selectionText = describeSlice(it?.xLabel, it?.y, whole) },
                modifier = chartModifier,
            )

            RadialDemo.ExplodedDonut -> DonutChart3D(
                data = RadialDemoData.browsers,
                value = { it.users },
                label = { it.name },
                innerRadiusRatio = 0.5f,
                explode = { it.name == "Chrome" || it.name == "Edge" },
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                state = pieState,
                onSelectionChanged = { selectionText = describeSlice(it?.xLabel, it?.y, whole) },
                modifier = chartModifier,
            )

            // Tap a slice: it slides out and stays out until another is chosen.
            // Displacement rather than a colour change, so the emphasis
            // survives a screenshot and a reader who cannot separate two hues.
            RadialDemo.Selection -> PieChart3D(
                data = RadialDemoData.platforms,
                value = { it.users },
                label = { it.name },
                labelPosition = SliceLabelPosition.Auto,
                explodeSelected = true,
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                state = pieState,
                onSelectionChanged = { selectionText = describeSlice(it?.xLabel, it?.y, whole) },
                modifier = chartModifier,
            )

            RadialDemo.Projection -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Perspective", style = MaterialTheme.typography.labelMedium)
                PieChart3D(
                    data = RadialDemoData.platforms,
                    value = { it.users },
                    label = { it.name },
                    projection = Chart3DProjection.Perspective(),
                    cameraState = camera,
                    depth = Chart3DDepth.Relative(depth.toDouble()),
                    animation = animation,
                    legend = LegendPosition.None,
                    valueFormatter = whole,
                    modifier = Modifier.fillMaxWidth().height(230.dp).testTag(RadialDemoTestTags.Chart),
                )
                Text(
                    "Orthographic — the same data, the same camera, no size distortion",
                    style = MaterialTheme.typography.labelMedium,
                )
                PieChart3D(
                    data = RadialDemoData.platforms,
                    value = { it.users },
                    label = { it.name },
                    projection = Chart3DProjection.Orthographic,
                    cameraState = camera,
                    depth = Chart3DDepth.Relative(depth.toDouble()),
                    animation = animation,
                    legend = LegendPosition.None,
                    valueFormatter = whole,
                    modifier = Modifier.fillMaxWidth().height(230.dp),
                )
            }

            RadialDemo.Depth -> PieChart3D(
                data = RadialDemoData.platforms,
                value = { it.users },
                label = { it.name },
                // Stated in pixels rather than as a fraction, for a chart that
                // has to match another exactly.
                depth = Chart3DDepth.Absolute(46f),
                cameraState = camera,
                projection = projection,
                animation = animation,
                valueFormatter = whole,
                modifier = chartModifier,
            )

            RadialDemo.Lighting -> PieChart3D(
                data = RadialDemoData.browsers,
                value = { it.users },
                label = { it.name },
                // A harder light from the right: the rim's gradient reverses
                // and the caps darken, without a single colour being restated.
                lighting = Chart3DLighting(
                    ambient = 0.5,
                    diffuse = 0.5,
                    direction = Vector3D(-0.6, -0.5, 0.6),
                ),
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                modifier = chartModifier,
            )

            RadialDemo.StartAngle -> PieChart3D(
                data = RadialDemoData.browsers,
                value = { it.users },
                label = { it.name },
                // Ninety degrees is three o'clock, and the gap separates the
                // slices enough to expose their radial walls.
                startAngle = 90f,
                sliceGap = 2f,
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                modifier = chartModifier,
            )

            RadialDemo.PartialPie -> PieChart3D(
                data = RadialDemoData.platforms,
                value = { it.users },
                label = { it.name },
                sweepAngle = 180f,
                startAngle = 270f,
                labelPosition = SliceLabelPosition.Auto,
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                modifier = chartModifier,
            )

            RadialDemo.PartialDonut -> DonutChart3D(
                data = RadialDemoData.platforms,
                value = { it.users },
                label = { it.name },
                innerRadiusRatio = 0.55f,
                sweepAngle = 270f,
                startAngle = 225f,
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                modifier = chartModifier,
            )

            RadialDemo.CenterContent -> DonutChart3D(
                data = RadialDemoData.budget,
                value = { it.amount },
                label = { it.category },
                innerRadiusRatio = 0.58f,
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = money,
                // Ordinary Compose content, laid out inside the projected hole
                // and never tilted with it. Turning the chart moves the box;
                // the text stays upright and stays readable.
                centerContent = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.testTag(RadialDemoTestTags.Center),
                    ) {
                        Text(
                            "Total",
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            money.format(RadialDemoData.budgetTotal),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                },
                modifier = chartModifier,
            )

            RadialDemo.DataUpdates -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    onClick = { quarterTwo = !quarterTwo },
                    modifier = Modifier.testTag(RadialDemoTestTags.Swap),
                ) {
                    Text(if (quarterTwo) "Show Q1" else "Show Q2")
                }
                DonutChart3D(
                    data = if (quarterTwo) RadialDemoData.quarterTwo else RadialDemoData.quarterOne,
                    value = { it.users },
                    label = { it.name },
                    innerRadiusRatio = 0.5f,
                    labelPosition = SliceLabelPosition.Auto,
                    cameraState = camera,
                    projection = projection,
                    depth = Chart3DDepth.Relative(depth.toDouble()),
                    animation = animation,
                    valueFormatter = whole,
                    modifier = Modifier.fillMaxWidth().height(280.dp).testTag(RadialDemoTestTags.Chart),
                )
            }

            RadialDemo.CameraRotation -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Drag to turn, pinch to move closer. The camera is hoisted, so the " +
                        "buttons below and the gesture drive the same state.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            scope.launch { interactiveCamera.animateTo(Chart3DCamera.Radial) }
                        },
                        modifier = Modifier.testTag(RadialDemoTestTags.Animate),
                    ) { Text("Animate to default") }
                    TextButton(
                        onClick = { interactiveCamera.reset() },
                        modifier = Modifier.testTag(RadialDemoTestTags.Reset),
                    ) { Text("Reset") }
                }
                DonutChart3D(
                    data = RadialDemoData.browsers,
                    value = { it.users },
                    label = { it.name },
                    innerRadiusRatio = 0.45f,
                    cameraState = interactiveCamera,
                    interaction = Chart3DInteraction.RotateAndSelect,
                    projection = projection,
                    animation = animation,
                    valueFormatter = whole,
                    state = pieState,
                    onSelectionChanged = { selectionText = describeSlice(it?.xLabel, it?.y, whole) },
                    modifier = Modifier.fillMaxWidth().height(280.dp).testTag(RadialDemoTestTags.Chart),
                )
            }

            RadialDemo.DarkTheme -> ChartKitTheme(
                colors = materialDerivedChartColors(isDark = true),
            ) {
                DonutChart3D(
                    data = RadialDemoData.browsers,
                    value = { it.users },
                    label = { it.name },
                    innerRadiusRatio = 0.5f,
                    labelPosition = SliceLabelPosition.Outside,
                    cameraState = camera,
                    projection = projection,
                    depth = Chart3DDepth.Relative(depth.toDouble()),
                    animation = animation,
                    valueFormatter = whole,
                    modifier = chartModifier,
                )
            }

            RadialDemo.AwkwardValues -> PieChart3D(
                data = RadialDemoData.awkward,
                value = { it.users },
                label = { it.name },
                labelPosition = SliceLabelPosition.Auto,
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                modifier = chartModifier,
            )

            RadialDemo.Dense -> PieChart3D(
                data = RadialDemoData.dense,
                value = { it.users },
                label = { it.name },
                // Twenty slices, and a coarser tessellation because at this
                // density nobody is looking at the smoothness of a rim.
                quality = Chart3DQuality.Low,
                legend = LegendPosition.None,
                cameraState = camera,
                projection = projection,
                depth = Chart3DDepth.Relative(depth.toDouble()),
                animation = animation,
                valueFormatter = whole,
                modifier = chartModifier,
            )
        }

        if (selectionText.isNotEmpty()) {
            Text(
                selectionText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(RadialDemoTestTags.Selection),
            )
        }

        HorizontalDivider()

        Text("Camera", style = MaterialTheme.typography.titleSmall)
        LabelledSlider("Pitch", rotationX, 12f..85f) { rotationX = it }
        LabelledSlider("Yaw", rotationY, -30f..30f) { rotationY = it }
        LabelledSlider("Distance", distance, 1.6f..8f) { distance = it }
        LabelledSlider("Depth", depth, 0.05f..0.6f) { depth = it }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = orthographic,
                onClick = { orthographic = !orthographic },
                label = { Text("Orthographic") },
                modifier = Modifier.testTag(RadialDemoTestTags.Orthographic),
            )
            FilterChip(
                selected = animate,
                onClick = { animate = !animate },
                label = { Text("Animate") },
            )
            FilterChip(
                selected = showTable,
                onClick = { showTable = !showTable },
                label = { Text("Data table") },
                modifier = Modifier.testTag(RadialDemoTestTags.Table),
            )
        }

        if (showTable) {
            // The same numbers at every camera angle, because none of them is
            // measured from the picture.
            ChartDataTableView(
                table = pieDataTable(
                    data = RadialDemoData.browsers,
                    value = { it.users },
                    label = { it.name },
                    valueFormatter = whole,
                    categoryColumn = "Browser",
                    caption = "Browser share",
                ),
                modifier = Modifier.testTag(RadialDemoTestTags.TableView),
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
            "$label ${String.format(Locale.UK, "%.2f", value)}",
            style = MaterialTheme.typography.labelSmall,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.testTag(RadialDemoTestTags.slider(label)),
        )
    }
}

private fun describeSlice(
    label: String?,
    value: Double?,
    formatter: io.devkit.chartkit.formatter.ChartValueFormatter,
): String = if (label == null || value == null) {
    ""
} else {
    "Selected $label: ${formatter.format(value)}"
}

/** The demos this screen can show. */
internal enum class RadialDemo(val label: String, val description: String) {
    BasicPie("Pie", "The plainest 3D pie: one accessor for the value, one for the label."),
    BasicDonut("Donut", "The same chart with a hole, which adds an inner wall to every slice."),
    ReferencePie(
        "Reference pie",
        "Extrusion, a tilted camera, shaded surfaces, labels with leader lines, a tooltip, " +
            "a legend and a slice that pops out when it is selected.",
    ),
    ReferenceDonut(
        "Reference donut",
        "An inner radius, an extrusion, both walls, labels, a tooltip and a legend.",
    ),
    ExplodedPie("Exploded pie", "One slice permanently displaced along its own mid-angle."),
    ExplodedDonut("Exploded donut", "Several slices out at once; the hole opens with them."),
    Selection(
        "Selection",
        "Tap a slice — anywhere on it, cap or rim or wall — and it slides out.",
    ),
    Projection(
        "Perspective vs orthographic",
        "The same data and camera twice. Orthographic keeps the extrusion and removes the " +
            "size distortion, so two equal shares are drawn equally wherever they sit.",
    ),
    Depth("Custom depth", "An extrusion stated in pixels rather than as a fraction of the radius."),
    Lighting("Custom lighting", "A harder light from the other side; no colour is restated."),
    StartAngle("Start angle", "Starting at three o'clock, with a gap that exposes the walls."),
    PartialPie("Partial pie", "A semicircle: the same primitive with a smaller total sweep."),
    PartialDonut("Partial donut", "Three quarters of a ring, hole and all."),
    CenterContent(
        "Centre content",
        "Arbitrary Compose content in the hole, placed by the projection and never tilted by it.",
    ),
    DataUpdates(
        "Data updates",
        "Two quarters with the same categories in a different order. Slices are matched by " +
            "identity, so nothing morphs into its neighbour.",
    ),
    CameraRotation("Camera", "Drag to turn, pinch to move closer, animate or reset."),
    DarkTheme("Dark theme", "Surfaces, labels, leader lines and outlines in a dark palette."),
    AwkwardValues(
        "Zero, null, negative",
        "Three values a part-to-whole chart cannot draw, handled by the slice engine rather " +
            "than by anything three-dimensional.",
    ),
    Dense("Twenty slices", "Where a 3D pie stops being a good idea, drawn honestly anyway."),
}

/** Test tags the sample's own instrumentation tests reach for. */
object RadialDemoTestTags {
    const val Demos = "radial3d:demos"
    const val Chart = "radial3d:chart"
    const val Selection = "radial3d:selection"
    const val Center = "radial3d:center"
    const val Table = "radial3d:table"
    const val TableView = "radial3d:tableview"
    const val Orthographic = "radial3d:orthographic"
    const val Reset = "radial3d:reset"
    const val Animate = "radial3d:animate"
    const val Swap = "radial3d:swap"
    fun demo(name: String) = "radial3d:demo:$name"
    fun slider(label: String) = "radial3d:slider:$label"
}
