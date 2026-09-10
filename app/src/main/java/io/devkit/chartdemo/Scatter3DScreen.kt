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
import io.devkit.chartkit.accessibility.scatter3DDataTable
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.axis.ChartAxis
import io.devkit.chartkit.axis.ChartUnit
import io.devkit.chartkit.charts.CartesianChart3D
import io.devkit.chartkit.charts.Chart3DInteraction
import io.devkit.chartkit.charts.ExperimentalChartKitApi
import io.devkit.chartkit.charts.ScatterChart3D
import io.devkit.chartkit.components.legend.LegendPosition
import io.devkit.chartkit.formatter.ChartNumberFormatters
import io.devkit.chartkit.layer.three.Chart3DSceneFit
import io.devkit.chartkit.layer.three.Scatter3DGuides
import io.devkit.chartkit.layer.three.Scatter3DRenderMode
import io.devkit.chartkit.model.ChartSeries
import io.devkit.chartkit.state.rememberChart3DCameraState
import io.devkit.chartkit.state.rememberChartState
import io.devkit.chartkit.theme.ChartKitTheme
import io.devkit.chartkit.theme.materialDerivedChartColors
import io.devkit.chartkit.three.Chart3DCamera
import io.devkit.chartkit.three.Chart3DCameraLimits
import io.devkit.chartkit.three.Chart3DFrame
import io.devkit.chartkit.three.Chart3DGridPlanes
import io.devkit.chartkit.three.Chart3DProjection
import io.devkit.chartkit.three.Chart3DSceneDepth
import io.devkit.chartkit.three.Chart3DSideWall
import io.devkit.chartkit.three.Marker3D
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Every 3D scatter capability, one screen.
 *
 * ### What the controls do and what they do not
 *
 * Rotation, distance, projection, markers, the frame and the grid all change
 * how the cloud is *drawn*. None of them changes an observation's age, income
 * or satisfaction, the tooltip that reports them, the announcement a screen
 * reader hears or the table at the bottom. That separation is the point of the
 * architecture, and this screen is where it is easiest to check by hand: turn
 * the chart as far as it goes, then open the data table.
 */
@OptIn(ExperimentalChartKitApi::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun Scatter3DScreen(modifier: Modifier = Modifier) {
    var demo by remember { mutableStateOf(Scatter3DDemo.Basic) }
    var orthographic by remember { mutableStateOf(false) }
    var animate by remember { mutableStateOf(true) }
    var showTable by remember { mutableStateOf(false) }
    var guides by remember { mutableStateOf(Scatter3DGuides.None) }
    var renderMode by remember { mutableStateOf(Scatter3DRenderMode.Auto) }
    var rotationX by remember { mutableFloatStateOf(24f) }
    var rotationY by remember { mutableFloatStateOf(32f) }
    var distance by remember { mutableFloatStateOf(3.4f) }
    var sceneDepth by remember { mutableFloatStateOf(1f) }
    var selectionText by remember { mutableStateOf("") }

    val animation = if (animate) ChartAnimation.Default else ChartAnimation.None
    val projection = if (orthographic) {
        Chart3DProjection.Orthographic
    } else {
        Chart3DProjection.Perspective()
    }
    val money = remember { ChartNumberFormatters.compact(locale = Locale.UK) }
    val whole = remember { ChartNumberFormatters.integer(Locale.UK) }

    // Driven by the sliders. Every one of these is a *camera* change, so the
    // three scales, the world points and the plot volume survive all of them.
    val camera = rememberChart3DCameraState(
        camera = Chart3DCamera(
            rotationX = rotationX.toDouble(),
            rotationY = rotationY.toDouble(),
            distance = distance.toDouble(),
        ),
        limits = Chart3DCameraLimits.Cartesian3D,
    )
    // A second, independent camera for the drag demo — so turning that chart by
    // hand does not fight the sliders driving the others. The type is the same
    // one a 3D column, pie or donut takes.
    val draggable = rememberChart3DCameraState(
        camera = Chart3DCamera(rotationX = 22.0, rotationY = 38.0, distance = 3.0),
        limits = Chart3DCameraLimits.Cartesian3D,
    )
    val scope = rememberCoroutineScope()

    val sceneDepthPolicy = if (sceneDepth == 1f) {
        Chart3DSceneDepth.Auto
    } else {
        Chart3DSceneDepth.Relative(sceneDepth.toDouble())
    }

    val ageAxis = ChartAxis(title = "Age")
    val incomeAxis = ChartAxis(title = "Income", valueFormatter = money)
    val scoreAxis = ChartAxis(title = "Satisfaction")

    val groups = remember {
        listOf(
            ChartSeries("control", "Control", Scatter3DDemoData.control),
            ChartSeries("treated", "Treated", Scatter3DDemoData.treated),
        )
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("3D scatter", style = MaterialTheme.typography.titleLarge)
        Text(
            "Three analytical variables, three independent scales, one volume. Unlike a 3D " +
                "column chart — where depth separates stacks and carries no quantity — the " +
                "depth here is a measurement with its own axis, ticks and formatter.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .testTag("scatter-3d-demos"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Scatter3DDemo.entries.forEach { entry ->
                FilterChip(
                    selected = demo == entry,
                    onClick = {
                        demo = entry
                        selectionText = ""
                    },
                    label = { Text(entry.label) },
                    modifier = Modifier.testTag("scatter-3d-demo-${entry.name}"),
                )
            }
        }

        Text(demo.description, style = MaterialTheme.typography.bodySmall)

        val chartModifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .testTag("scatter-3d-chart")

        val report: (io.devkit.chartkit.model.ChartSelection<*>?) -> Unit = { selection ->
            selectionText = selection?.cartesian3D?.let { detail ->
                "${detail.xTitle ?: "X"} ${detail.formattedX}, " +
                    "${detail.yTitle ?: "Y"} ${detail.formattedY}, " +
                    "${detail.zTitle ?: "Z"} ${detail.formattedZ}"
            } ?: ""
        }

        when (demo) {
            Scatter3DDemo.Basic -> ScatterChart3D(
                data = Scatter3DDemoData.survey,
                x = { it.age },
                y = { it.income },
                z = { it.satisfaction },
                seriesName = "Respondents",
                xAxis = ageAxis,
                yAxis = incomeAxis,
                zAxis = scoreAxis,
                yUnit = ChartUnit.Currency("£"),
                cameraState = camera,
                projection = projection,
                sceneDepth = sceneDepthPolicy,
                guides = guides,
                renderMode = renderMode,
                animation = animation,
                onSelectionChanged = report,
                modifier = chartModifier,
            )

            Scatter3DDemo.Draggable -> ScatterChart3D(
                data = Scatter3DDemoData.survey,
                x = { it.age },
                y = { it.income },
                z = { it.satisfaction },
                seriesName = "Respondents",
                xAxis = ageAxis,
                yAxis = incomeAxis,
                zAxis = scoreAxis,
                // Its own camera, and a starting view that is deliberately not
                // front-on: a 3D plot opened square to the reader looks like a
                // 2D one until they touch it.
                cameraState = draggable,
                projection = projection,
                interaction = Chart3DInteraction.RotateAndSelect,
                guides = guides,
                animation = animation,
                onSelectionChanged = report,
                modifier = chartModifier,
            )

            Scatter3DDemo.MultiSeries -> ScatterChart3D(
                series = groups,
                x = { it.age },
                y = { it.income },
                z = { it.satisfaction },
                xAxis = ageAxis,
                yAxis = incomeAxis,
                zAxis = scoreAxis,
                legend = LegendPosition.Bottom,
                legendTogglesSeries = true,
                cameraState = camera,
                projection = projection,
                guides = guides,
                animation = animation,
                onSelectionChanged = report,
                modifier = chartModifier,
            )

            Scatter3DDemo.SphereMarkers -> ScatterChart3D(
                data = Scatter3DDemoData.survey,
                x = { it.age },
                y = { it.income },
                z = { it.satisfaction },
                marker = Marker3D.Sphere,
                markerSize = 8.dp,
                renderMode = Scatter3DRenderMode.Rich,
                xAxis = ageAxis,
                yAxis = incomeAxis,
                zAxis = scoreAxis,
                cameraState = camera,
                projection = projection,
                animation = animation,
                onSelectionChanged = report,
                modifier = chartModifier,
            )

            Scatter3DDemo.BillboardMarkers -> ScatterChart3D(
                data = Scatter3DDemoData.survey,
                x = { it.age },
                y = { it.income },
                z = { it.satisfaction },
                marker = Marker3D.BillboardCircle,
                renderMode = Scatter3DRenderMode.Optimized,
                xAxis = ageAxis,
                yAxis = incomeAxis,
                zAxis = scoreAxis,
                cameraState = camera,
                projection = projection,
                animation = animation,
                onSelectionChanged = report,
                modifier = chartModifier,
            )

            Scatter3DDemo.CubeMarkers -> ScatterChart3D(
                data = Scatter3DDemoData.readings,
                x = { it.temperature },
                y = { it.pressure },
                z = { it.humidity },
                marker = Marker3D.Cube,
                markerSize = 7.dp,
                renderMode = Scatter3DRenderMode.Rich,
                xAxis = ChartAxis(title = "Temperature"),
                yAxis = ChartAxis(title = "Pressure"),
                zAxis = ChartAxis(title = "Humidity"),
                xUnit = ChartUnit.Custom("°C", "degrees Celsius"),
                yUnit = ChartUnit.Custom("hPa", "hectopascals"),
                zUnit = ChartUnit.Percent,
                cameraState = camera,
                projection = projection,
                animation = animation,
                onSelectionChanged = report,
                modifier = chartModifier,
            )

            Scatter3DDemo.ColorEncoded -> ScatterChart3D(
                data = Scatter3DDemoData.survey,
                x = { it.age },
                y = { it.income },
                z = { it.satisfaction },
                // A fourth channel. The scale receives the caller's own value,
                // so the legend and the colour agree with the number.
                color = { it.tenure },
                colorLegendTitle = "Years with us",
                xAxis = ageAxis,
                yAxis = incomeAxis,
                zAxis = scoreAxis,
                cameraState = camera,
                projection = projection,
                animation = animation,
                onSelectionChanged = report,
                modifier = chartModifier,
            )

            Scatter3DDemo.SizeEncoded -> ScatterChart3D(
                data = Scatter3DDemoData.survey,
                x = { it.age },
                y = { it.income },
                z = { it.satisfaction },
                // A fifth. Mapped by area, so a household twice the size draws
                // a marker occupying twice the area rather than four times it.
                size = { it.household },
                minMarkerSize = 3.dp,
                maxMarkerSize = 13.dp,
                xAxis = ageAxis,
                yAxis = incomeAxis,
                zAxis = scoreAxis,
                cameraState = camera,
                projection = projection,
                animation = animation,
                onSelectionChanged = report,
                modifier = chartModifier,
            )

            Scatter3DDemo.SizeAndColor -> ScatterChart3D(
                data = Scatter3DDemoData.survey,
                x = { it.age },
                y = { it.income },
                z = { it.satisfaction },
                size = { it.household },
                color = { it.tenure },
                minMarkerSize = 3.dp,
                maxMarkerSize = 13.dp,
                xAxis = ageAxis,
                yAxis = incomeAxis,
                zAxis = scoreAxis,
                cameraState = camera,
                projection = projection,
                animation = animation,
                onSelectionChanged = report,
                modifier = chartModifier,
            )

            Scatter3DDemo.Guides -> ScatterChart3D(
                data = Scatter3DDemoData.survey,
                x = { it.age },
                y = { it.income },
                z = { it.satisfaction },
                // Tap a point: three lines run from it to the floor, the side
                // wall and the back wall — each parallel to one axis.
                guides = if (guides == Scatter3DGuides.None) Scatter3DGuides.Axes else guides,
                xAxis = ageAxis,
                yAxis = incomeAxis,
                zAxis = scoreAxis,
                gridPlanes = Chart3DGridPlanes.All,
                cameraState = camera,
                projection = projection,
                animation = animation,
                onSelectionChanged = report,
                modifier = chartModifier,
            )

            Scatter3DDemo.Frame -> ScatterChart3D(
                data = Scatter3DDemoData.survey,
                x = { it.age },
                y = { it.income },
                z = { it.satisfaction },
                frame = Chart3DFrame(
                    floor = true,
                    back = true,
                    side = Chart3DSideWall.Auto,
                    opacity = 0.8f,
                ),
                gridPlanes = Chart3DGridPlanes.All,
                xAxis = ageAxis,
                yAxis = incomeAxis,
                zAxis = scoreAxis,
                cameraState = camera,
                projection = projection,
                animation = animation,
                onSelectionChanged = report,
                modifier = chartModifier,
            )

            Scatter3DDemo.NoFrame -> ScatterChart3D(
                data = Scatter3DDemoData.survey,
                x = { it.age },
                y = { it.income },
                z = { it.satisfaction },
                frame = Chart3DFrame.None,
                gridPlanes = Chart3DGridPlanes.None,
                xAxis = ageAxis,
                yAxis = incomeAxis,
                zAxis = scoreAxis,
                cameraState = camera,
                projection = projection,
                animation = animation,
                onSelectionChanged = report,
                modifier = chartModifier,
            )

            Scatter3DDemo.LargeDataset -> ScatterChart3D(
                data = Scatter3DDemoData.large,
                x = { it.age },
                y = { it.income },
                z = { it.satisfaction },
                // Five thousand points. Auto draws them as billboards, because
                // that is past the documented Rich limit; the chips below force
                // either mode so the difference is visible.
                renderMode = renderMode,
                markerSize = 3.dp,
                xAxis = ageAxis,
                yAxis = incomeAxis,
                zAxis = scoreAxis,
                cameraState = camera,
                projection = projection,
                // Fitting the declared volume rather than the cloud, so an
                // outlying respondent does not shrink everybody else.
                fit = Chart3DSceneFit.Volume,
                animation = ChartAnimation.None,
                onSelectionChanged = report,
                modifier = chartModifier,
            )

            Scatter3DDemo.DarkTheme -> ChartKitTheme(
                colors = materialDerivedChartColors(isDark = true),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF101418))
                        .padding(8.dp),
                ) {
                    ScatterChart3D(
                        series = groups,
                        x = { it.age },
                        y = { it.income },
                        z = { it.satisfaction },
                        xAxis = ageAxis,
                        yAxis = incomeAxis,
                        zAxis = scoreAxis,
                        legend = LegendPosition.Bottom,
                        cameraState = camera,
                        projection = projection,
                        guides = guides,
                        animation = animation,
                        onSelectionChanged = report,
                        modifier = chartModifier,
                    )
                }
            }

            Scatter3DDemo.LowLevel -> CartesianChart3D(
                cameraState = camera,
                projection = projection,
                categoryAxis = ageAxis,
                valueAxis = incomeAxis,
                legend = LegendPosition.Bottom,
                state = rememberChartState(),
                modifier = chartModifier,
            ) {
                scatter(
                    data = Scatter3DDemoData.control,
                    x = { it.age },
                    y = { it.income },
                    z = { it.satisfaction },
                    seriesId = "control",
                    seriesName = "Control",
                    zAxis = scoreAxis,
                )
                scatter(
                    data = Scatter3DDemoData.treated,
                    x = { it.age },
                    y = { it.income },
                    z = { it.satisfaction },
                    seriesId = "treated",
                    seriesName = "Treated",
                    zAxis = scoreAxis,
                )
            }
        }

        if (selectionText.isNotEmpty()) {
            Text(
                selectionText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("scatter-3d-selection"),
            )
        }

        HorizontalDivider()
        Text("Camera", style = MaterialTheme.typography.titleSmall)
        Text(
            "Every control below reprojects the scene and recomputes nothing else — the three " +
                "scales, the domains and the world positions all survive a drag. Under " +
                "orthographic projection depth stops changing marker size, which is the " +
                "reading to prefer when two observations are being compared rather than " +
                "explored.",
            style = MaterialTheme.typography.bodySmall,
        )

        LabelledScatterSlider("Pitch", rotationX, 0f..82f) { rotationX = it }
        LabelledScatterSlider("Yaw", rotationY, -85f..85f) { rotationY = it }
        LabelledScatterSlider("Distance", distance, 1.6f..8f) { distance = it }
        LabelledScatterSlider("Scene depth", sceneDepth, 0.4f..2.5f) { sceneDepth = it }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = orthographic,
                onClick = { orthographic = !orthographic },
                label = { Text("Orthographic") },
                modifier = Modifier.testTag("scatter-3d-orthographic"),
            )
            FilterChip(
                selected = animate,
                onClick = { animate = !animate },
                label = { Text("Animate") },
            )
            TextButton(
                onClick = {
                    rotationX = 24f
                    rotationY = 32f
                    distance = 3.4f
                    sceneDepth = 1f
                    scope.launch {
                        draggable.animateTo(
                            Chart3DCamera(rotationX = 22.0, rotationY = 38.0, distance = 3.0),
                        )
                    }
                },
                modifier = Modifier.testTag("scatter-3d-reset"),
            ) { Text("Reset view") }
        }

        Text("Presets", style = MaterialTheme.typography.labelMedium)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "Isometric" to Chart3DCamera.Isometric,
                "Front" to Chart3DCamera.Front,
                "Top" to Chart3DCamera.Top,
                "Side" to Chart3DCamera.Side,
            ).forEach { (label, preset) ->
                TextButton(
                    onClick = {
                        rotationX = preset.rotationX.toFloat()
                        rotationY = preset.rotationY.toFloat()
                        distance = preset.distance.toFloat()
                        scope.launch { draggable.animateTo(preset) }
                    },
                    modifier = Modifier.testTag("scatter-3d-preset-$label"),
                ) { Text(label) }
            }
        }

        HorizontalDivider()
        Text("Selection guides", style = MaterialTheme.typography.titleSmall)
        Text(
            "A 2D crosshair has no meaning here: under a projection every pixel is a ray " +
                "through the volume, so a vertical line names no value. Guides start from " +
                "the selected point's own three coordinates instead.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Scatter3DGuides.entries.forEach { mode ->
                FilterChip(
                    selected = guides == mode,
                    onClick = { guides = mode },
                    label = { Text(mode.name) },
                    modifier = Modifier.testTag("scatter-3d-guides-${mode.name}"),
                )
            }
        }

        HorizontalDivider()
        Text("Render modes", style = MaterialTheme.typography.titleSmall)
        Text(
            "Rich shades every marker as a sphere; Optimized draws flat billboards. Auto " +
                "picks Rich up to a documented point count and Optimized beyond it, so a " +
                "caller can predict which they will get.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Scatter3DRenderMode.entries.forEach { mode ->
                FilterChip(
                    selected = renderMode == mode,
                    onClick = { renderMode = mode },
                    label = { Text(mode.name) },
                    modifier = Modifier.testTag("scatter-3d-mode-${mode.name}"),
                )
            }
        }

        HorizontalDivider()
        Text("Accessibility", style = MaterialTheme.typography.titleSmall)
        Text(
            "The chart announces three axis names and three values, and never a depth, a " +
                "marker or a camera angle. Perspective makes precise comparison harder than " +
                "a flat chart does, so the same numbers are available as a table.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = { showTable = !showTable },
            modifier = Modifier.testTag("scatter-3d-table-toggle"),
        ) {
            Text(if (showTable) "Hide data table" else "View data table")
        }
        if (showTable) {
            ChartDataTableView(
                table = scatter3DDataTable(
                    series = groups,
                    x = { it.age },
                    y = { it.income },
                    z = { it.satisfaction },
                    xFormatter = whole,
                    yFormatter = money,
                    zFormatter = whole,
                    xColumn = "Age",
                    yColumn = "Income",
                    zColumn = "Satisfaction",
                    caption = "Survey respondents, by group",
                ),
                modifier = Modifier.testTag("scatter-3d-table"),
            )
        }
    }
}

@Composable
private fun LabelledScatterSlider(
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
            modifier = Modifier.testTag("scatter-3d-slider-$label"),
        )
    }
}

/** The demos, in the order a reader meets the ideas. */
internal enum class Scatter3DDemo(val label: String, val description: String) {
    Basic(
        "Basic",
        "A hundred and twenty respondents. Age runs across, income upward and " +
            "satisfaction away from the reader — three variables, three scales, " +
            "three sets of ticks.",
    ),
    Draggable(
        "Draggable",
        "Drag to turn the cloud, pinch to move closer, tap to select. Rotation is " +
            "how a reader resolves which of two overlapping points is in front.",
    ),
    MultiSeries(
        "Multi-series",
        "Two groups over the same three variables. Hiding one through the legend " +
            "removes its markers and narrows all three Auto domains, the depth " +
            "axis included.",
    ),
    SphereMarkers(
        "Sphere markers",
        "Shaded from the scene's own light: the highlight sits where a sphere's " +
            "surface normal would point at it, and every marker shares one " +
            "direction because the light is fixed to the camera.",
    ),
    BillboardMarkers(
        "Billboard markers",
        "One filled circle per point. The depth cue that survives is the strongest " +
            "one anyway — the arrangement of the cloud and the perspective size " +
            "falloff.",
    ),
    CubeMarkers(
        "Cube markers",
        "Real boxes in the scene, with six faces each projected, culled, lit and " +
            "sorted. The only marker whose orientation tells the reader anything, " +
            "and the only one sized in scene units.",
    ),
    ColorEncoded(
        "Colour encoded",
        "A fourth channel: tenure, through a continuous colour scale. The scale " +
            "receives the caller's own value, so the colour and the number agree.",
    ),
    SizeEncoded(
        "Size encoded",
        "A fifth: household size, mapped by area rather than by radius — a value " +
            "twice as large draws a marker occupying twice the area.",
    ),
    SizeAndColor(
        "Size + colour",
        "X, Y, Z, size and colour at once, through one coordinate system and one " +
            "renderer.",
    ),
    Guides(
        "Selection guides",
        "Tap a point: three lines run to the floor, the side wall and the back " +
            "wall, each parallel to one axis and each starting from the point's " +
            "own analytical coordinates.",
    ),
    Frame(
        "3D frame",
        "Floor, back wall and the far side wall, with all three grids drawn from " +
            "the axes' own ticks.",
    ),
    NoFrame(
        "No frame",
        "The same cloud with the frame and the grid off, for a dense dashboard tile.",
    ),
    LargeDataset(
        "Large dataset",
        "Five thousand deterministic observations, generated locally. Switch the " +
            "render mode below to see what Rich costs at this size.",
    ),
    DarkTheme(
        "Dark theme",
        "The same geometry over a dark surface. Shading is a multiplier on the " +
            "series colour, so markers darken rather than washing out.",
    ),
    LowLevel(
        "Layer DSL",
        "Two scatter layers declared through CartesianChart3D. The same engine, " +
            "with the layers stated one at a time.",
    ),
}
