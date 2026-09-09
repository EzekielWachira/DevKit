# ChartKit

Compose-native data visualisation for Android, on **one engine**.

Line, area, bar, scatter, bubble, histogram, box plot, violin, heatmap, calendar
heatmap, candlestick, OHLC, volume, waterfall, dumbbell, lollipop, bullet,
timeline, range and Gantt on Cartesian coordinates; pie, donut, radial bar,
radar, sunburst and gauge on polar ones; treemap, Sankey, funnel and network
graphs on planar ones; choropleth maps on geographic ones. Venn and Euler
diagrams sit on the planar engine too, and the 3D charts — grouped and stacked
columns, extruded pies and donuts — are a projected scene over the Cartesian and
polar engines rather than a fifth engine of their own. All four
coordinate systems share the same scales, layout, layers, viewport, interaction,
animation, theming, overlays and accessibility.

It also handles the parts that decide whether a chart survives real data:
annotations, hierarchical drill-down, linked charts and cross-filtering, viewport
culling and downsampling for datasets in the tens of thousands, a Flow adapter
for live streams, custom layers, log and symmetric-log scales, second value axes,
deterministic report rendering and image export.

ChartKit charts **your** data classes. There is no entry type to convert into:

```kotlin
data class Revenue(val month: String, val amount: Double)

LineChart(
    data = revenue,
    x = { it.month },
    y = { it.amount },
    modifier = Modifier.fillMaxWidth().height(240.dp),
)
```

ChartKit owns its rendering. It does not wrap MPAndroidChart, Vico or
KoalaPlot, and nothing in it is a `WebView` around a JavaScript library — a
wrapper inherits somebody else's data model, view interop and theming, which is
the opposite of the point.

## Contents

- [Install](#install) · [Requirements](#requirements) · [Run the sample](#run-the-sample)
- Cartesian charts: [Line](#line-chart) · [Area](#area-chart) · [Bar](#bar-chart) · [Horizontal](#horizontal-bars) · [Grouped](#grouped-bars) · [Stacked](#stacked-bars) · [100% stacked](#100-stacked-bars) · [Multi-series](#multiple-series) · [Combined](#combined-charts) · [Multi-axis combos](#multi-axis-combo-charts)
- 3D: [3D columns](#3d-columns) · [3D pie and donut](#3d-pie-and-donut)
- Polar charts: [Pie](#pie-chart) · [Donut](#donut-chart) · [Radial bar](#radial-bar-chart) · [Radar](#radar-chart) · [Polar coordinates](#polar-coordinates)
- Statistical: [Scatter](#scatter-chart) · [Bubble](#bubble-chart) · [Histogram](#histogram) · [Box plot](#box-plot) · [Violin](#violin-plot) · [Statistics API](#statistics-api)
- Density: [Heatmap](#heatmap) · [Calendar heatmap](#calendar-heatmap) · [Colour scales](#colour-scales)
- Financial: [Candlestick](#candlestick-chart) · [OHLC](#ohlc-chart) · [Volume](#volume-chart) · [Linked charts](#linked-charts)
- Hierarchy: [Treemap](#treemap) · [Sunburst](#sunburst) · [State and breadcrumbs](#hierarchy-state-and-breadcrumbs)
- Flow: [Sankey](#sankey-diagram) · [Funnel](#funnel-chart)
- Comparison: [Waterfall](#waterfall-chart) · [Dumbbell and lollipop](#dumbbell-and-lollipop) · [Bullet](#bullet-graph) · [Gauge](#gauge-chart) · [Dial gauges](#dial-gauges)
- Time: [Timeline, range and Gantt](#timeline-range-and-gantt-charts)
- Relationships: [Network graph](#network-graph)
- Sets: [Venn and Euler diagrams](#set-relationship-diagrams)
- Geographic: [Choropleth map](#choropleth-map)
- Dashboards: [Coordination](#dashboard-coordination) · [Navigator](#overview-navigator)
- [Annotations](#annotations)
- Configuration: [Axes](#axes) · [Scales](#scales) · [Secondary axes](#secondary-value-axes) · [Multi-axis combos](#multi-axis-combo-charts) · [Grid](#grid-lines) · [Formatting](#formatting) · [Legends](#legends) · [Value labels](#value-labels)
- Interaction: [Interaction modes](#interaction-modes) · [Selection](#selection) · [Scrubbing](#scrubbing) · [Crosshair](#crosshair) · [Zoom and pan](#zoom-and-pan) · [Range selection](#range-selection) · [Tooltips](#tooltips) · [State](#hoisted-state)
- Extending: [Custom layers](#custom-layers)
- Scale: [Large datasets](#large-datasets) · [Downsampling](#downsampling) · [Streaming](#streaming)
- Presentation: [Theming](#theming) · [Animation](#animation) · [Accessibility](#accessibility) · [Data tables](#data-tables) · [Capture](#capture) · [Static rendering](#static-rendering) · [Export](#export) · [Loading, empty and error](#loading-empty-and-error) · [Sizing](#sizing)
- Data: [X values](#x-values) · [Missing values](#missing-values) · [Ordering](#ordering) · [Edge cases](#edge-cases)
- [Architecture](#architecture) · [Performance](#performance) · [Limitations](#current-limitations) · [Roadmap](#roadmap)

## Install

ChartKit is a **runtime** library. It draws your users' data on a screen they
see, so it belongs in `implementation` and reaches your release build.

```kotlin
dependencies {
    implementation("io.github.ezekielwachira.devkit:chartkit:0.1.0")
}
```

Through the BOM, naming no version:

```kotlin
dependencies {
    implementation(platform("io.github.ezekielwachira.devkit:devkit-bom:0.2.0"))
    implementation("io.github.ezekielwachira.devkit:chartkit")
}
```

Or as part of the release-safe umbrella:

```kotlin
dependencies {
    implementation("io.github.ezekielwachira.devkit:devkit:0.2.0")
}
```

Installing ChartKit alone pulls ChartKit and `core`. It does **not** pull
FillKit or NetKit — asserted, not assumed, by `consumer-test`.

> **Not yet on Maven Central.** Until a release is published, build locally with
> `./gradlew publishToMavenLocal` and add `mavenLocal()` to your repositories.

### What arrives with it

| Dependency | Scope | Why |
| --- | --- | --- |
| `core` | `api` | The `DevKitTool` / `DevKitDistribution` metadata every kit shares |
| `compose-ui`, `ui-graphics`, `foundation`, `foundation-layout` | `api` | `Modifier`, `Color`, `TextStyle` and `DrawScope` appear in ChartKit's own signatures |
| `material3` | `implementation` | Default colours and type are derived from `MaterialTheme`; no Material type is exposed |
| `ui-tooling-preview` | `implementation` | ChartKit ships `@Preview`s of its own chart types |

No test, sample or debug dependency reaches the POM.

## Requirements

- `compileSdk` 37, `minSdk` 24
- Kotlin 2.2.10, Compose BOM 2026.02.01, AGP 9.3.2
- Java 11 source/target compatibility; JDK 17 to run Gradle

No `java.time` requirement and no core-library desugaring: ChartKit's time axis
takes epoch milliseconds, so it works on API 24 as it stands.

## Run the sample

```bash
./gradlew :app:installDebug
```

Open the drawer and pick a **ChartKit** destination:

| Screen | Shows |
| --- | --- |
| Chart gallery | Every Cartesian chart type, with live toggles for grid, points, legend, labels, smoothing and animation |
| Chart interaction | Tap selection, scrubbing, a custom tooltip, legend toggling |
| Chart theming | `ChartKitTheme` overrides, dark mode, compact/currency/percent/date formatting |
| Chart states | Loading, empty and error slots; single-point, constant and gapped datasets; 5,000 points; accessibility semantics |
| Polar charts | Pie, donut with live centre content, radial bars, a 270° gauge, and values a pie cannot represent |
| Zoom, pan and range | 730 daily readings: pinch, pan, reset, animate to a window, crosshair, and range selection reported in dates |
| Statistical | Scatter, bubble, histogram with every binning rule, box plot and violin |
| Density | Heatmap, calendar heatmap and the colour scales behind them |
| Radar | Per-axis and shared normalisation, filled and outlined |
| Financial | Candlesticks, OHLC, volume and a moving average on one chart |
| Annotations | Rules, bands, regions, markers and callouts on live data |
| Large datasets | 50,000 points, with culling and every downsampling strategy side by side |
| Streaming | A live `Flow` with rolling windows, throttling and backpressure |
| Accessibility | Generated summaries, custom summaries and the data table |
| Hierarchy | Treemap and sunburst over **one** tree and one navigation state, with breadcrumbs and drill-down |
| Flow | A Sankey diagram with node and link selection, and a funnel with conversion and drop-off |
| Comparison | Waterfall, dumbbell, lollipop, bullet and gauge |
| Time and intervals | A point timeline, durations in lanes, and a Gantt chart with progress and milestones |
| Relationships | A service graph, circular and force directed, draggable and zoomable, with its data table |
| Geographic | A choropleth over the sample's own GeoJSON: quantile against continuous shading, both projections, labels, a legend, missing data, the join report and the data table |
| Gauges | Ten demos: the reference speedometer, a semicircle stated as two angles, a full-circle compass, a three-quarter dial, four bands over a range crossing zero, three needles with a legend, an adjustable dial, a compact KPI pair, a deterministic realtime feed, and a custom counterweighted needle |
| 3D pie and donut | Nineteen demos: the reference 3D pie and 3D donut, a plain pie and donut, exploded pies and donuts, tap-to-explode selection, perspective against orthographic, custom depth, custom lighting, a custom start angle with slice gaps, partial pies and donuts, Compose centre content, animated data updates, an interactive camera, dark mode, zero/null/negative values and twenty slices — with live pitch, yaw, distance, depth and projection controls and the accessible data table |
| 3D columns | Fifteen demos: the reference grouped-and-stacked arrangement, a single series, grouped, stacked, depth rows, 100% stacked, negative values, null against zero, an interactive camera, custom lighting, the frame on and off, dark mode, twenty categories, and the low-level `CartesianChart3D` API — with live pitch, yaw, distance, depth and projection controls and the accessible data table |
| Multi-axis combos | Six demos: the reference weather chart with three units, a business combo, a financial combo with volume on its own axis, legend toggling with axis auto-hide, zero alignment, and a shared crosshair with per-axis chips |
| Set relationships | Thirteen Venn and Euler demos: two, three and four sets, proportional sizing, Venn against Euler, hand-placed icon groups, icons in labels, packed image content, four-level nesting, disjoint sets, explicit intersection colours, collection-driven sets and the accessibility tables |
| Dashboard | Linked candlestick and volume charts with aligned plots, an overview navigator, and cross-filtering |
| Advanced | A custom layer, log and linear axes side by side, a second value axis, static report mode, and PNG and SVG export |

---

## Line chart

```kotlin
LineChart(
    data = revenue,
    x = { it.month },
    y = { it.amount },
    modifier = Modifier.fillMaxWidth().height(240.dp),
)
```

| Parameter | Default | Notes |
| --- | --- | --- |
| `interpolation` | `Linear` | `Linear`, `Smooth`, `Step` |
| `lineStyle` | `Solid` | `Solid`, `Dashed` |
| `pointMode` | `Auto` | `None`, `Always`, `SelectedOnly`, `Auto` |
| `lineWidth` | theme | Overrides `ChartDimensions.lineWidth` for this chart |
| `valueDomain` | `Auto()` | See [Edge cases](#edge-cases) |

### Interpolation

`Smooth` is a **monotone cubic** (Fritsch–Carlson), not a Catmull–Rom or natural
spline. Those overshoot: three points at `10, 90, 10` produce a curve that rises
above 90 and dips below 10, inventing values your data never held — and on a
percentage, leaves the possible range entirely. The monotone construction cannot
overshoot between two points.

```kotlin
LineChart(
    data = revenue,
    x = { it.month },
    y = { it.amount },
    interpolation = LineInterpolation.Smooth,
)
```

`Step` holds each value until the next x, which is the right reading for sampled
state — a setting that was on, then off.

### Point markers

`PointMode.Auto` draws markers while a series is small enough for them to mean
something and stops past `ChartPerformance.pointMarkerThreshold` (40 by
default). A thousand markers on a 500px-wide line are a solid band, not
information, and they cost a thousand draw calls a frame to produce it.

## Area chart

```kotlin
AreaChart(
    data = revenue,
    x = { it.month },
    y = { it.amount },
    fill = AreaFill(alpha = 0.22f, gradient = true),
)
```

An area chart is a line chart with a fill — the same layer, the same
interpolation, the same hit testing. The region closes to the **zero line**, not
to the bottom of the composable, so a chart whose axis starts above zero still
shades a region whose height means something.

Areas default to `DomainPolicy.Baseline` (zero included), because a filled
region reads as a magnitude. Lines default to `Auto`.

## Bar chart

```kotlin
BarChart(
    data = sales,
    category = { it.product },
    value = { it.total },
)
```

The value axis includes zero by default. A bar encodes its value as a *length
from a baseline*, so a bar chart whose axis starts at 98 draws `[98, 100]` as
one bar twice the height of the other — a factual chart making a false claim.
Overridable, but not casually:

```kotlin
BarChart(..., valueDomain = DomainPolicy.Fixed(min = 90.0, max = 100.0))
```

Negative values draw below the baseline. Corners are rounded only at the end
away from the baseline: a bar with rounded bottom corners appears to float above
the axis it is measured from.

## Horizontal bars

```kotlin
HorizontalBarChart(
    data = productLines,
    category = { it.name },
    value = { it.revenue },
)
```

Not a second renderer. Bar geometry is expressed in *domain* and *value* terms
rather than x and y, so orientation is a parameter:

```kotlin
BarChart(..., orientation = ChartOrientation.Horizontal)
```

Reach for it whenever category names are long — a horizontal chart gives each
label a full line instead of a bar's width.

## Grouped bars

```kotlin
BarChart(
    series = listOf(
        ChartSeries(id = "new", name = "New", data = newCustomers),
        ChartSeries(id = "returning", name = "Returning", data = returning),
    ),
    category = { it.quarter },
    value = { it.count },
    grouping = BarGrouping.Grouped,
    groupPadding = 0.1,
)
```

## Stacked bars

```kotlin
BarChart(..., grouping = BarGrouping.Stacked)
```

Segments accumulate **per sign**: positives pile upward from zero and negatives
downward from zero. A category holding `+3` and `-1` therefore draws a segment
above the baseline and one below, rather than a single bar of net height 2 —
the only reading under which segment lengths still match their values.

Only the outermost segment in each direction is rounded, so a stack reads as one
continuous length.

## 100% stacked bars

```kotlin
BarChart(..., grouping = BarGrouping.StackedPercent)
```

Each category is normalised to its own total, and the value axis is labelled in
percent unless you pass a `valueFormatter`. Shares are taken over the sum of
**absolute** values, so a category mixing signs still normalises rather than
dividing by a total that cancels to near zero. **A category totalling zero
yields empty segments, not a division by zero.**

## Multiple series

```kotlin
LineChart(
    series = listOf(
        ChartSeries(id = "revenue", name = "Revenue", data = revenue),
        ChartSeries(id = "expenses", name = "Expenses", data = expenses),
        ChartSeries(id = "forecast", name = "Forecast", data = forecast),
    ),
    x = { it.month },
    y = { it.amount },
    legend = LegendPosition.Bottom,
    legendTogglesSeries = true,
)
```

`id` is load-bearing, not decorative. It is what animation matches on across a
data change and what the legend toggles. Duplicate ids within one chart are
rejected.

Series colours come from the palette **by declaration order**, so hiding a
series never recolours the others.

## Combined charts

```kotlin
@OptIn(ExperimentalChartKitApi::class)
CartesianChart(
    modifier = Modifier.fillMaxWidth().height(240.dp),
) {
    bars(
        series = listOf(ChartSeries("actual", "Actual", actuals)),
        category = { it.month },
        value = { it.amount },
    )
    line(
        series = listOf(ChartSeries("target", "Target", targets)),
        x = { it.month },
        y = { it.amount },
        pointMode = PointMode.Always,
    )
}
```

`bars`, `line` and `area` layers share **one** plot area, **one** pair of
scales, **one** hit test and **one** animation clock. Layers cannot disagree
about where a value sits, and a tap resolves across all of them.

Layers measured in different units bind to their own value axes; see
[multi-axis combo charts](#multi-axis-combo-charts).

`CartesianChart` is marked `@ExperimentalChartKitApi` — the layer grammar is
where a fuller visualisation DSL (annotations, secondary axes, custom marks)
will want room to move before 1.0. `LineChart`, `AreaChart` and `BarChart` are
not experimental and are not expected to change.

---

## Pie chart

```kotlin
data class Expense(val category: String, val amount: Double)

PieChart(
    data = expenses,
    value = { it.amount },
    label = { it.category },
    modifier = Modifier.fillMaxWidth().height(280.dp),
)
```

No conversion step and no slice type: `data` is a `List<Expense>` and stays one,
exactly as it does for a line or bar chart.

Values are **normalised**, so they need not sum to anything in particular —
`40, 30, 20, 10` and `0.4, 0.3, 0.2, 0.1` draw the same chart.

| Parameter | Default | Notes |
| --- | --- | --- |
| `startAngle` | `0` | Zero is twelve o'clock |
| `sweepAngle` | `360` | Less than a full circle for a gauge or half-donut |
| `direction` | `Clockwise` | |
| `sliceGap` | `0` | Degrees between slices, taken out of each slice's own sweep so the circle still closes |
| `labelPosition` | `None` | `Inside`, `Outside`, or leave the names to the legend |
| `labelContent` | `LabelAndPercentage` | `Label`, `Value`, `Percentage` |
| `color` | `null` | `(T) -> Int?` for categories whose colour carries meaning |
| `valuePolicy` | `Ignore` | See below |

### Angles

**Zero degrees is at twelve o'clock and angles increase clockwise.** That is not
what a canvas does — `drawArc` measures from three o'clock — and translating it
once, inside the engine, is what stops `startAngle = -90f` appearing in every
call site. No ChartKit API exposes the canvas convention.

### Values a pie cannot represent

A share of a whole is never negative, and `NaN` is not a share at all. Such
values are **dropped**: they contribute nothing to the total and draw no slice,
while keeping their legend row and their palette slot so a chart whose data is
briefly wrong does not recolour itself. Taking the absolute value instead would
draw a positive share of a total that value reduced.

```kotlin
PieChart(..., valuePolicy = PolarValuePolicy.Reject)   // throw instead
```

Every degenerate input renders: an empty list and an all-zero dataset show the
empty content, a single value fills the circle, and a value a millionth of the
total still gets a real fraction rather than a division by zero.

### In three dimensions

[`PieChart3D` and `DonutChart3D`](#3d-pie-and-donut) plot the same data through
the same slice engine, extruded and projected through a camera. Prefer the flat
chart when comparing shares precisely is the point — see the note there on what
perspective costs.

### Slice labels

`SliceLabelPosition.Inside` draws a label only where the slice is genuinely big
enough — the arc at the label's radius wider than the text, and the ring taller
than it — so a pie with one dominant slice labels that one and leaves the
slivers to the legend. `Outside` puts them beyond the ring with a leader line,
skipping any whose measured box would overlap one already placed or leave the
plot. Nothing is shrunk or ellipsised.

## Donut chart

```kotlin
DonutChart(
    data = usage,
    value = { it.value },
    label = { it.label },
    innerRadiusRatio = 0.6f,
    centerContent = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("3,600", style = MaterialTheme.typography.headlineSmall)
            Text("total", style = MaterialTheme.typography.labelSmall)
        }
    },
)
```

Not a separate renderer: it calls `PieChart` with a non-zero inner radius,
because a donut *is* a pie with a hole — and the hole lives in the coordinate
system, not in the drawing.

**`centerContent` is a Compose slot**, laid out inside the hole rather than
rasterised onto the canvas, so it can hold anything: a total, a percentage, an
icon, a small KPI. It scales with the app's text settings and is readable by a
screen reader.

It takes **no pointer input**. The hole belongs to no slice, so nothing is
stolen from the chart, and the middle of a donut never becomes a dead zone.
A tap in the hole selects nothing — which is the truth, and is asserted by a
test.

## Radial bar chart

```kotlin
data class Metric(val name: String, val value: Double)

RadialBarChart(
    data = metrics,
    value = { it.value },
    label = { it.name },
    maxValue = 100.0,
)
```

Concentric rings, outermost first. Each is drawn against a full **track** — the
"out of 100" a 72% ring is read against; without it a short arc says nothing.

**The range is yours.** `minValue` and `maxValue` name the domain a value's
sweep is measured against, and neither is fixed at a percentage: storage in
gigabytes, a score out of five, a temperature between −10 and 40 all work
without normalising the data first. `maxValue = null` derives the maximum from
the data, which is right for a comparison and wrong for a target — so it is not
the default.

Out-of-range values clamp; `RadialRangePolicy.Reject` throws instead. A bar
cannot sweep past its own track, so the alternative to clamping is no bar at all.

A partial sweep turns the same chart into a gauge:

```kotlin
RadialBarChart(..., startAngle = 225f, sweepAngle = 270f)
```

Hit testing picks the ring by radius: a point is in exactly one band, and the
visible gap between two rings belongs to neither.

## Polar coordinates

Pie, donut and radial bar are three high-level charts on **one**
`PolarCoordinates` — a centre, a ring between an inner and an outer radius, a
start angle and a sweep. `PolarGeometry` holds the arithmetic: angle
normalisation, points on the circumference, slice boundaries, centroids, arc
containment and hit testing, all plain Kotlin and all unit-tested on the JVM.

That is the sibling of `CartesianCoordinates`, and everything above the
`CoordinateSystem` interface is shared between them: the layer model, the
selection model, the tooltip overlay, the animation clock, the legend, the theme
and the accessibility layer. A polar chart costs two layers, not a second engine.

---

## Radar chart

```kotlin
data class Rating(val aspect: String, val score: Double)

RadarChart(
    data = ratings,
    metric = { it.aspect },
    value = { it.score },
)
```

Comparing two or three profiles is what a radar chart is genuinely good at:

```kotlin
RadarChart(
    series = listOf(
        ChartSeries(id = "q2", name = "This quarter", data = thisQuarter),
        ChartSeries(id = "q1", name = "Last quarter", data = lastQuarter),
    ),
    metric = { it.aspect },
    value = { it.score },
    valueRange = 0.0..100.0,
    legend = LegendPosition.Bottom,
)
```

Built on the same `PolarCoordinates` as pie, donut and radial bar — radius
carries the value, angle carries the category — so it needed two layers rather
than a third coordinate system.

**Normalisation is the decision that matters**, so it is a parameter:

| | |
| --- | --- |
| `RadarNormalization.PerAxis` | Each spoke scaled to its own metric's range. The default, and right when the metrics are not comparable — a latency in milliseconds beside a score out of ten |
| `RadarNormalization.Shared` | One domain for every spoke. Right when they *are* comparable, and it makes the polygon's shape mean something |
| `valueRange = 0.0..100.0` | Fixes one explicit range for every spoke |

Under either, the **area** of the polygon means nothing in particular and should
not be read as a total. That is true of every radar chart.

A series with no value on a spoke leaves the polygon open there rather than
pulling it to the centre — drawing a zero would assert a measurement, and on a
radar chart that reads as being worst at that metric.

Spoke labels are **measured** and the ring shrinks to leave room for them, the
same way the Cartesian axes reserve their gutters. A label that still does not
fit is dropped rather than clipped or overlapped.

Below three metrics the chart draws its empty state: two spokes are a line, not
a chart.

## Scatter chart

```kotlin
data class Observation(val height: Double, val weight: Double)

ScatterChart(
    data = observations,
    x = { it.height },
    y = { it.weight },
    modifier = Modifier.fillMaxWidth().height(280.dp),
)
```

Both axes are continuous and neither is forced to zero: a scatter of heights
between 150 and 195 cm is about that interval, not about the interval from
nothing.

Multiple groups:

```kotlin
ScatterChart(
    series = listOf(
        ChartSeries(id = "control", name = "Control", data = control),
        ChartSeries(id = "treated", name = "Treated", data = treated),
    ),
    x = { it.dose },
    y = { it.response },
)
```

Markers are `Circle`, `Square` or `Diamond`, matched **by area** so a chart
mixing shapes does not appear to be encoding a magnitude that is not there.

ChartKit draws no trend line and reports no correlation, here or in the
accessibility summary. Both are statistical claims with assumptions attached,
and a chart library that produced them silently would be putting an unchecked
assertion on screen.

Hit testing goes through a uniform spatial grid rather than a scan — scatter
data has no order to binary-search, and a scan is `O(n)` on every pointer frame.

## Bubble chart

```kotlin
BubbleChart(
    data = companies,
    x = { it.revenue },
    y = { it.growth },
    size = { it.marketCap },
)
```

**Size means area.** A value twice as large draws a bubble occupying twice the
*area*, which is what a reader perceives it as. Mapping the value onto the
radius instead — the obvious implementation — makes that bubble look four times
as large, and is the most common way a bubble chart lies. `sizeMode =
SizeScaleMode.Radius` selects the direct mapping for the rare case where the
quantity genuinely is a radius.

One size scale spans every series in the chart, so two groups are measured
against the same domain rather than each being scaled to its own maximum.

Bubbles are translucent and outlined by default (`ScatterStyle.Bubble`), because
they overlap: opaque bubbles hide each other completely and a reader cannot tell
one large bubble from three stacked ones.

## Histogram

```kotlin
Histogram(
    data = requests,
    value = { it.durationMs },
    bins = HistogramBins.Count(20),
)
```

Raw observations go in and ChartKit does the binning — there is no bin type to
construct, and no counting to get wrong at the boundaries.

| Bin strategy | |
| --- | --- |
| `HistogramBins.Auto` | Freedman–Diaconis, falling back to Sturges when the interquartile range collapses. The default |
| `HistogramBins.Count(20)` | Exactly twenty equal-width bins |
| `HistogramBins.Width(50.0)` | Bins fifty wide, **aligned to multiples of fifty** so the boundaries are the numbers a reader expects |
| `HistogramBins.Custom(listOf(0.0, 50.0, 200.0, 1000.0))` | Explicit, and deliberately unequal |

| Metric | |
| --- | --- |
| `HistogramMetric.Count` | The number of observations. The default, and the literal one |
| `HistogramMetric.Percentage` | The bin's share, comparable across datasets of different sizes |
| `HistogramMetric.Density` | Share divided by width — the only honest metric when bins are unequal, where a count bar makes a wide bin look more populated for being wide |

A histogram's bars sit on a **continuous** axis and their widths carry meaning,
which is why this is not `BarChart` with a preprocessing step.

Bins are half-open `[start, end)`, except the last, which closes at its upper
bound — otherwise the single largest observation falls outside every bin and the
histogram silently loses its maximum.

Empty data, a single observation, a run of identical values, negatives and
`NaN`s all produce a chart rather than a crash. A constant dataset draws one bin
around its value, which is the truthful picture.

## Box plot

From raw samples:

```kotlin
data class Endpoint(val path: String, val latencies: List<Double>)

BoxPlot(
    data = endpoints,
    label = { it.path },
    values = { it.latencies },
)
```

From statistics that already exist:

```kotlin
BoxPlot(
    data = summaries,
    label = { it.endpoint },
    statistics = {
        BoxStatistics(
            minimum = it.p0, q1 = it.p25, median = it.p50, q3 = it.p75, maximum = it.p100,
        )
    },
)
```

Both routes are first class. An application very often already has its
quartiles — from a database, a reporting service, an analysis pipeline whose
definitions the organisation has agreed on — and forcing it to hand over raw
samples so ChartKit could recompute them would mean the chart quietly
disagreeing with the numbers printed next to it. The precomputed overload
recalculates nothing and applies no outlier rule.

**The quartile method is named, not merely implemented**: linear interpolation
between the order statistics either side of `(n − 1)p`, which R calls type 7 and
NumPy calls `"linear"`, and which is the default in both. There are at least
nine published definitions and they disagree by a visible amount on small
samples.

```text
h = (n - 1) p
Q = x[⌊h⌋] + (h - ⌊h⌋) · (x[⌊h⌋ + 1] - x[⌊h⌋])
```

Outliers use the conventional Tukey fence, with the multiplier exposed rather
than fixed — `1.5` is a convention, and a genuinely heavy-tailed dataset is
nothing but outliers under it:

```kotlin
outlierPolicy = OutlierPolicy.Tukey(multiplier = 3.0)
outlierPolicy = OutlierPolicy.None   // whiskers run to the extremes
```

Whiskers stop at a real observation, never at the fence: the fence is a rule for
classifying points, not a value the data reached.

The tooltip reports all five numbers, because a box plot's "value" is not one
number and reporting only the median would leave out most of what the mark
shows.

## Violin plot

```kotlin
ViolinPlot(
    data = endpoints,
    label = { it.path },
    values = { it.latencies },
    overlay = ViolinOverlay.Box,
)
```

A kernel density estimate mirrored about each category's centre, with a Gaussian
kernel and a Silverman bandwidth.

**Widths are comparable across categories.** Every violin is scaled by the
largest density *in the chart*, not by its own — normalising each to its own
peak makes every category the same width and discards the fact that one
distribution is more concentrated than another, which is half of what a violin
is for.

```kotlin
bandwidth = KernelBandwidth.Auto            // Silverman's robust rule
bandwidth = KernelBandwidth.Scaled(0.5)     // follow the data more closely
bandwidth = KernelBandwidth.Fixed(12.0)     // in the data's own units
```

The kernel spreads density a little past the extremes of the sample, so a
strictly non-negative quantity shows some density below zero; the curve is
evaluated three bandwidths beyond the data and no further. The `overlay` box or
median line shows where the observations actually were, which is why it is on by
default.

A category whose samples are all identical has no density to estimate and is
drawn as a **line** at that value — a flat violin would claim a uniform
distribution the data does not have.

## Statistics API

Everything above is available on its own, as plain Kotlin with no Compose and no
Android types:

```kotlin
val sorted = ChartStatistics.finiteSorted(samples)

ChartStatistics.median(sorted)
ChartStatistics.quartiles(sorted)             // Quartiles(q1, median, q3)
ChartStatistics.interquartileRange(sorted)
ChartStatistics.outliers(sorted, multiplier = 1.5)
ChartStatistics.whiskers(sorted)
ChartStatistics.standardDeviation(sorted)     // sample form, n − 1

BoxStatistics.from(samples)
HistogramBinner.bin(values, HistogramBins.Auto, HistogramMetric.Count)
DensityEstimator.estimate(samples)

MovingAverage.simple(closes, period = 20)
MovingAverage.exponential(closes, period = 20)
```

Every entry point filters `NaN` and infinities before computing anything, and
says so — a `NaN` quartile reaching a `drawPath` renders as nothing and looks
exactly like a layout bug.

The moving averages are here because a moving average is a **line**, and drawing
one beside a price series is a charting task. RSI, MACD, Bollinger bands and the
rest are analysis: they carry parameter conventions and interpretations that
belong in a domain library where they can be tested against a reference, not in
a renderer.

## Heatmap

```kotlin
data class Activity(val day: String, val hour: String, val requests: Int)

Heatmap(
    data = activity,
    x = { it.day },
    y = { it.hour },
    value = { it.requests },
)
```

Columns and rows come from the data in **input order**, first occurrence first,
and the first `y` listed appears at the top — the reading order a table has.
Sorting them would be a decision ChartKit has no basis for: "Mon, Tue, Wed" is
not alphabetical and is obviously right.

**Missing is not zero.** A cell the data does not contain is painted in the
theme's "no measurement" colour, which is deliberately not the low end of the
ramp — "closed on Sunday" and "open with no visitors" are different facts.
`showMissing = false` leaves such cells unpainted entirely.

```kotlin
cellLabels = HeatmapCellLabels.Auto   // written where they actually fit, measured
```

Rows sit at integer positions on the **value** axis, which is what lets a
two-categorical-axis chart run on the same Cartesian coordinate system as
everything else — the alternative was generalising the coordinate system that
every other layer depends on.

## Calendar heatmap

```kotlin
CalendarHeatmap(
    data = commits,
    date = { it.dateMillis },
    value = { it.count },
)
```

**The week starts where the locale says.** Not on Sunday, and not on Monday
either: `java.util.Calendar` already knows the first day of the week for every
locale Android ships, and hardcoding either convention puts a British reader's
Sundays at the top of the grid or an American reader's Mondays — and in both
cases every weekday label is wrong by a row.

**A day is a time-zone-dependent bucket of instants.** An event at 23:30 UTC
belongs to a different day in Nairobi than in New York, so `timeZone` says which
one and defaults to the device's. A chart of server-side data usually wants to
name one explicitly, so the grid does not reshuffle when the reader travels.

```kotlin
CalendarHeatmap(
    data = commits,
    date = { it.dateMillis },
    value = { it.count },
    from = yearStartMillis,
    to = yearEndMillis,
    timeZone = TimeZone.getTimeZone("UTC"),
)
```

Days inside the range that the data does not mention are painted as missing, not
as zero. Several observations on one day sum, which is what a count grid means.

Month labels are placed at the week each month begins in, and a month occupying
one column is left unlabelled rather than drawn on top of its neighbour's.

`date` accepts a `Long` of epoch milliseconds, a `java.util.Date` or a
`java.util.Calendar`. `java.time` is absent for the same reason it is absent
everywhere else in ChartKit — `LocalDate` is API 26 and the floor is 24 — so
consumers on those types pass `date.toEpochDay() * 86_400_000L`.

## Colour scales

The third kind of scale, alongside position and size. A `ColorScale` is an
ordinary value, so it is equally the right way to shade scatter points by a
third variable or to colour bars by severity — a `Heatmap` that owned its colour
logic would be the only chart able to do it.

```kotlin
// Derived from the theme, across the data's own range
val ramp = ChartColorScales.continuous(NumericDomain(0.0, maxRequests))

// Cut into readable bands
val stepped = ChartColorScales.quantized(NumericDomain(0.0, 100.0), steps = 5)

// Bands whose meaning is categorical even though their values are numeric
val severity = ChartColorScales.threshold(
    thresholds = listOf(20.0, 50.0, 80.0),
    labels = listOf("ok", "elevated", "high", "critical"),
)

// Equal counts per band, computed from the data itself
val quintiles = ChartColorScales.quantile(rates, groups = 5)

Heatmap(data = activity, x = { it.day }, y = { it.hour }, value = { it.requests },
    colorScale = severity)
```

Explicit colours where the theme is not the source:

```kotlin
ColorScale.Continuous(NumericDomain(0.0, 1.0), listOf(Color.White, Color.Blue))
ColorScale.Threshold(thresholds = listOf(50.0), colors = listOf(Color.Green, Color.Red))
ColorScale.Quantized(domain, listOf(low, high), steps = 4)
ColorScale.Quantile(rates, listOf(low, high), groups = 5)
ColorScale.Categorical(keys = listOf("ok", "down"), colors = listOf(green, red))
```

A **quantile** scale puts an equal count of observations in each band rather than
an equal slice of the range, which is what makes a skewed statistic readable —
income, population, incidence. Its breaks are values that exist in the data (the
nearest-rank definition), because a legend printing a break of 41.7 between two
observations at 40 and 43 invites the reader to look for a number nobody
measured. Tied values collapse into fewer bands rather than producing bands
nothing can fall into.

`ChartColorLegend` renders any of them: a gradient bar with labelled ends for a
continuous scale, labelled swatches for a banded one, and an optional "no data"
swatch.

Every implementation returns `null` for a value it cannot place — a missing
measurement, a `NaN` — and charts draw that as their theme's "no data"
treatment. Threshold bands are half-open **upward**, so "80 and above is
critical" puts 80 in the critical band.

Interpolation is component-wise in sRGB. It is not perceptually uniform, so a
ramp between two distant hues passes through a desaturated middle; the theme's
own ramps stay within one hue and do not have that problem.

## Size scales

```kotlin
val sizes = SizeScale(
    domain = NumericDomain(0.0, marketCapMax),
    minSize = with(density) { 5.dp.toPx() },
    maxSize = with(density) { 28.dp.toPx() },
    mode = SizeScaleMode.Area,
)
```

Plain Kotlin and in pixels, so the arithmetic that decides whether a bubble
chart is honest is testable on the JVM. `minSize` is never zero at any ChartKit
call site: a bubble of no size is indistinguishable from a missing observation.
Values outside the domain clamp rather than extrapolate.

## Candlestick chart

```kotlin
data class Candle(
    val time: Long,
    val open: Double, val high: Double, val low: Double, val close: Double,
    val volume: Double,
)

CandlestickChart(
    data = prices,
    x = { it.time },
    open = { it.open },
    high = { it.high },
    low = { it.low },
    close = { it.close },
    volume = { it.volume },
)
```

Five lambdas over your own type, exactly as a line chart takes two. There is no
candle type to convert into.

**Colour is semantic, not green and red.** The layer asks the theme for
`increase` and `decrease` by name. The rising-is-green convention is not
universal — several East Asian markets colour rising prices red — and it is
invisible to a reader with red-green colour vision deficiency, for whom the two
most important colours on the chart are the same colour:

```kotlin
ChartKitTheme(
    colors = materialDerivedChartColors().copy(
        financial = ChartFinancialColors(
            increase = brandRed, decrease = brandGreen,
            neutral = grey, wick = grey,
        ),
    ),
) { PriceScreen() }
```

**Gaps stay gaps.** Weekends, holidays and halted sessions are absences, and
nothing fabricates a flat candle for them.

**Inconsistent prices are a stated policy.** A period whose high is below its
own close describes something that did not happen, and drawing it gives a body
sticking out of its own wick:

| | |
| --- | --- |
| `OhlcPolicy.Repair` | Widen the extremes to contain the open and close — the smallest change that makes the four numbers consistent. The default, because a live feed with an occasional bad tick should still draw |
| `OhlcPolicy.Skip` | Drop the period, leaving a gap |
| `OhlcPolicy.Reject` | Throw, at the call site |

Prices are read as positions, not as lengths from zero, so the value axis does
not force zero — a stock trading between 180 and 190 would otherwise flatten
into a line at the top of the plot.

The default tooltip reports open, high, low, close and the change.

## OHLC chart

```kotlin
OhlcChart(
    data = prices,
    x = { it.time },
    open = { it.open }, high = { it.high }, low = { it.low }, close = { it.close },
)
```

The same four numbers drawn as bars: a high–low stem with a tick left for the
open and right for the close. Not a separate implementation — it is
`CandlestickChart` with `PriceMarkStyle.OhlcBar`, because the positioning,
validation, hit testing, tooltip and accessibility summary are identical and
only the strokes differ. OHLC bars stay legible at widths where a candle body
collapses to a line.

## Volume chart

```kotlin
VolumeChart(
    data = prices,
    x = { it.time },
    volume = { it.volume },
    open = { it.open },
    close = { it.close },
)
```

The price accessors are what let a bar be coloured by whether its period rose or
fell, which is the only reason a volume chart is coloured at all. They are
optional: without them every bar is drawn neutral, which is honest — a volume
with no price attached has no direction, and guessing one from the volumes would
be inventing a fact.

Unlike a price, a volume bar encodes a magnitude, so its axis includes zero.

## Linked charts

Price above, volume below, moving together:

```kotlin
val group = rememberChartInteractionGroup()

Column {
    CandlestickChart(
        data = prices,
        x = { it.time },
        open = { it.open }, high = { it.high }, low = { it.low }, close = { it.close },
        viewportState = group.viewport,
        sharedCrosshair = group.crosshair,
        xAxis = ChartAxis.Hidden,
        modifier = Modifier.fillMaxWidth().height(280.dp),
    )
    VolumeChart(
        data = prices,
        x = { it.time },
        volume = { it.volume },
        open = { it.open },
        close = { it.close },
        viewportState = group.viewport,
        sharedCrosshair = group.crosshair,
        modifier = Modifier.fillMaxWidth().height(110.dp),
    )
}
```

Zooming, panning or scrubbing either chart moves the other — not because either
knows the other exists, but because they are given the same two pieces of state.

**Aligned by domain value, not by pixel or fraction.** Two charts in a dashboard
rarely hold the same dataset: one may cover a longer period, or have gaps the
other does not. Sharing a pixel would align them by accident of layout, and
sharing a fraction of each chart's own domain would put the guides on different
dates whenever the domains differ. Each chart resolves the shared *x* through
its own scale.

**Y stays independent.** Prices are in the low hundreds and volumes in the
millions; a shared value axis would flatten one of them. What the two genuinely
share is the x, and that is all that is shared — each chart keeps its own
`ChartState` and its own selected point.

**No callback bouncing.** One piece of state is the source of truth and every
chart reads it. A chart writes only in response to a gesture of its own, never
in response to reading a change, which makes an update loop structurally
impossible rather than merely unlikely.

The group is a convenience over two ordinary hoistable states, not a
replacement:

```kotlin
val viewport = rememberChartViewportState()
val crosshair = rememberChartSharedCrosshairState()

LineChart(..., viewportState = viewport, sharedCrosshair = crosshair)
BarChart(...,  viewportState = viewport, sharedCrosshair = crosshair)

// Or drive the group from elsewhere entirely — a list selection, a playback cursor
crosshair.publish(ChartX.Time(selectedRow.timestamp))
crosshair.clear()
```

## Dashboard coordination

`rememberChartInteractionGroup()` is five hoistable states with one name:

```kotlin
val group = rememberChartInteractionGroup()

CandlestickChart(…, viewportState = group.viewport, sharedCrosshair = group.crosshair,
                 plotAlignment = group.alignment)
VolumeChart(…,      viewportState = group.viewport, sharedCrosshair = group.crosshair,
                 plotAlignment = group.alignment)
```

| Member | Shared |
| --- | --- |
| `viewport` | the visible window of the domain |
| `crosshair` | the domain position under the pointer |
| `filter` | which selections are active, across the screen |
| `brush` | the domain interval a reader dragged out |
| `alignment` | the plot gutters, so stacked charts line up |

Each can still be created and passed on its own; the group is one call instead
of five, and a name for what a dashboard's charts have in common.

**Selection is deliberately not in the group.** Two charts over different
quantities have different value domains, and a shared selected *point* would
assert that a price of 182 and a volume of 4.1 million are the same selection.

### Aligned plot areas

```text
without                     with
182 ┤▇▇▇▇▇▇▇▇▇▇▇▇▇▇        182 ┤▇▇▇▇▇▇▇▇▇▇▇
  4.1M ┤▇▇▇▇▇▇▇▇▇▇       4.1M ┤▇▇▇▇▇▇▇▇▇▇▇
       ↑ misaligned                       ↑ aligned
```

Two stacked charts whose value labels differ in width get plot areas starting at
different x, so the reader compares two time axes that do not line up — precisely
what a stacked financial or monitoring dashboard exists to make possible.

Each chart reports the gutters it *naturally* needs; the group publishes the
largest, and every chart pads out to it. It is a **measurement exchange, not a
layout engine**: Compose still does all the measuring and arranging, and the
charts remain ordinary siblings that can be wrapped in cards or separated by
other content. It settles in one extra frame and cannot oscillate, because a
chart's natural gutter does not depend on the padding added outside it.

### Cross-filtering

```text
Chart A selection
        ↓
ChartFilterState        ← ChartKit's part ends here
        ↓
the app transforms its data
        ↓
Charts B and C redraw
```

```kotlin
val filters = rememberChartFilterState()

BarChart(
    data = byRegion, category = { it.region }, value = { it.revenue },
    onSelectionChanged = { filters.toggle(ChartFilter("region", it?.item?.region)) },
)

val visible = remember(orders, filters.filters) {
    filters.apply(orders, "region") { it.region }
}
LineChart(data = visible, x = { it.month }, y = { it.amount })
```

ChartKit coordinates **which selections are active**. The filtering itself happens
in your code, over your data, with your semantics — because "filter orders by
region" means joining a table in one app and re-querying a server in another, and
a charting library that owned that decision would be wrong in both.

The vocabulary is deliberately thin: a dimension, a key, a label and which chart
published it. A richer schema would be ChartKit inventing a query language that
every application would then have to translate out of.

Dimensions combine with **and**; keys within a dimension with **or**. Toggling
the same key twice returns to where it started, which — together with the chart
writing only from its **own** gesture — makes a feedback loop structurally
impossible rather than merely unlikely.

### Brush selection

A brush is a range selection hoisted so more than one chart can see it:

```kotlin
val brush = rememberChartBrushState()

LineChart(
    data = readings, x = { it.at }, y = { it.value },
    interaction = ChartInteraction.RangeSelect,
    onRangeSelectionChanged = { brush.set(it) },
)
Button(onClick = { brush.zoom(viewport) }) { Text("Zoom to selection") }
```

Zooming to the brush is an explicit call rather than automatic behaviour:
"select these three days" and "zoom to these three days" are different
intentions, and a chart cannot tell which one a drag meant.

## Overview navigator

```text
MAIN
──────────────────────────

OVERVIEW
──────[██████]────────────
```

```kotlin
val viewport = rememberChartViewportState()

LineChart(
    data = readings, x = { it.at }, y = { it.value },
    interaction = ChartInteraction.Explorable,
    viewportState = viewport,
)
ChartNavigator(data = readings, x = { it.at }, y = { it.value }, viewportState = viewport)
```

The navigator writes the **same** `ChartViewportState` the main chart reads.
There is no second viewport, no synchronisation and no callback bouncing.

Drag the window to move it, drag its edges to resize it, or tap to recentre.
Dragging past an end **slides** rather than shrinking: a window that narrowed as
it was dragged would change how much data is on screen for a gesture that only
asked to move.

The overview draws with `ChartPerformance.Dense` — a whole dataset in a strip
fifty pixels tall, where every pixel column can hold one point.

The second overload takes any content as the overview, for a candlestick chart
navigated by a volume strip:

```kotlin
ChartNavigator(viewportState = viewport) {
    VolumeChart(data = candles, x = { it.time }, volume = { it.volume }, /* … */)
}
```

## Treemap

Nested rectangles whose **areas** are proportional to their values. Charts your
own recursive model — there is no node type to convert into:

```kotlin
data class Department(
    val id: String,
    val name: String,
    val revenue: Double?,
    val teams: List<Department> = emptyList(),
)

Treemap(
    data = company,
    children = { it.teams },
    value = { it.revenue },
    label = { it.name },
    key = { it.id },
    modifier = Modifier.fillMaxWidth().height(300.dp),
)
```

The packing is the **squarified** algorithm of Bruls, Huizing and van Wijk. The
naive alternative — slice the strip, take one slice per child — is a few lines
and produces rectangles of aspect ratio 200:1 the moment one child dominates; a
3-pixel sliver cannot be labelled, tapped or compared by eye.

```text
sliced                    squarified
┌─┬─┬───────────────┐     ┌────────┬──────┐
│ │ │               │     │        ├──┬───┤
│ │ │               │     │        │  │   │
└─┴─┴───────────────┘     └────────┴──┴───┘
```

| Parameter | Meaning |
| --- | --- |
| `children` | the node's children. An empty list is a leaf |
| `value` | the leaf's magnitude. A branch's own value is optional |
| `key` | stable identity. **Strongly preferred** — drill-down, selection and animation are keyed on it |
| `maxDepth` | levels below the current root to draw. Two by default |
| `labels` | `None`, `Label`, `LabelAndValue`, `LabelAndPercentage` |
| `valuePolicy` | how a parent's own value is reconciled with its children's |
| `interaction` | whether a tap, a double tap or nothing drills in |

### Areas that sum

A parent's value is the **sum of its children's** by default, because that is the
only arrangement in which comparing two rectangles means anything. A parent that
supplies its own value and disagrees with its children is reported through
`ChartHierarchy.valueConflicts` rather than silently honoured; pass
`HierarchyValuePolicy.PreferExplicit` when the parent genuinely measures
something the children do not account for.

Negative and non-finite values are **dropped** and counted: area encodes
magnitude, and a negative area does not exist. `HierarchyValueGuard.Reject`
throws instead.

### Malformed hierarchies

A `children` lambda that eventually returns an ancestor makes the input a graph,
and a naive traversal of it never terminates. The repeated reference is cut and
counted in `cyclesBroken`; the rest of the tree still draws. Depth is capped at
`maxDepth` for the same reason.

Identity is compared by **reference**, not by `equals` — two sibling nodes that
compare equal are legitimate data, and rejecting the second would silently delete
it.

## Sunburst

The same hierarchy as rings:

```kotlin
SunburstChart(
    data = company,
    children = { it.teams },
    value = { it.revenue },
    label = { it.name },
    key = { it.id },
    centerContent = { Text(hierarchy.currentRoot?.label.orEmpty()) },
)
```

```text
angle  = the node's share of its parent
radius = how far below the visible root it sits
```

Built on `PolarChartCore` — the same engine as pie, donut and radial bar — so the
coordinate system, the tooltip overlay, the legend, the selection state, the
centre-content slot and the accessibility layer are shared. What a sunburst adds
is one layout function.

`centerContent` is a Compose slot inside the hole: a total, the current level's
name, a back button. Double-tapping the centre goes back up a level.

## Hierarchy state and breadcrumbs

Hoist a `ChartHierarchyState` to control the level, read it, or keep a treemap
and a sunburst **in step**:

```kotlin
val hierarchy = rememberHierarchyChartState()

ChartBreadcrumbs(hierarchy)
Treemap(data = company, /* … */, hierarchyState = hierarchy)
SunburstChart(data = company, /* … */, hierarchyState = hierarchy)
```

```text
Company  ›  Engineering  ›  Android
```

Nothing synchronises anything: there is **one** piece of state and two charts
observing it. The state stores the current root's **id**, not the node, so a
refresh that produces new objects for the same logical tree does not reset the
view — which is why supplying `key` matters.

`ChartBreadcrumbs` renders each ancestor as a real `Text` with its own click
target and semantics node, so a screen reader announces "Company, button" and a
keyboard can reach it. The `entry` slot replaces the appearance entirely while
keeping the navigation.

`rememberSaveableHierarchyChartState()` survives configuration changes and
process death.

## Sankey diagram

Weighted flows between nodes, from two of your own lists:

```kotlin
SankeyChart(
    nodes = stages,
    links = transitions,
    nodeId = { it.id },
    nodeLabel = { it.name },
    source = { it.from },
    target = { it.to },
    value = { it.users },
    modifier = Modifier.fillMaxWidth().height(300.dp),
)
```

```text
Search ▇▇▇▇▇▇▇▇▇▇▇ Product ▇▇▇▇▇▇ Checkout ▇▇▇ Purchase
       ▒▒▒▒▒▒▒            ▒▒▒▒            ▒
```

A link's **width is its weight**. That is the entire claim the diagram makes, so
links are filled ribbons rather than strokes.

Columns come from each node's **longest** distance from a source. Shortest-path
assignment would let a link that skips a stage drag its target backwards, and the
diagram would then show flow running right to left.

### What it will not draw

A **cycle**: "A before B" and "B before A" cannot both hold, so the links closing
one are cut — deterministically, by a depth-first traversal in your own link
order — and the rest is drawn. Unknown node references, self-links and
non-positive weights are dropped for related reasons. All four are counted on the
graph, and `SankeyValidation.Reject` throws instead of repairing.

### Selecting

Tapping a node emphasises everything it connects to and lists its flows in the
tooltip; tapping a band selects that flow. Both hand back a typed selection —
`SankeyNodeSelection` or `SankeyLinkSelection` — carrying your own object.
Unconnected flows take a different **colour** as well as a lower opacity,
because connection state carried by opacity alone is invisible on a dense diagram.

## Funnel chart

```kotlin
FunnelChart(
    data = stages,
    label = { it.name },
    value = { it.users },
    labels = FunnelLabels.LabelAndConversion,
)
```

```text
▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇▇  Visited     12,000  100%
  ▇▇▇▇▇▇▇▇▇▇      Signed up    4,800   40%
    ▇▇▇▇▇▇        Activated    3,100   65%
      ▇▇          Subscribed     940   30%
```

Each stage narrows toward the next one's width, so the **slope** between two
bands is the drop-off. A stack of rectangles would show the same numbers and hide
the thing a funnel exists to show.

All four figures are computed whether or not they are drawn — the count, the
share of the first stage, the conversion from the previous stage and the number
lost — and the tooltip, the labels and the screen-reader announcement each read
the one they need from the same transform.

**A funnel need not decrease.** A stage counted from a different source, a
re-entry or a cohort that grew all produce a stage larger than the one before it.
The conversion is then above `1` and the drop-off is negative; nothing is clamped.
`FunnelTransform.isMonotonic` lets you check.

## Waterfall chart

```kotlin
WaterfallChart(
    data = movements,
    label = { it.name },
    value = { it.amount },
    kind = { WaterfallTransform.signedKind(it.amount) },
)
```

```text
         ┌──┐
 ┌───┐   │  │╌╌┌──┐            ┌────┐
 │   │╌╌╌┘  │  │  │╌╌┌──┐╌╌╌╌╌╌│    │
 └───┘      └──┘  └──┘  └──┘   └────┘
 Start     Rev   Cost  Tax     Total
```

Four step kinds — `Increase`, `Decrease`, `Subtotal`, `Total`. The **kind**
decides the direction and your value supplies the magnitude, so `Decrease` with
`20` and `Decrease` with `-20` both fall by twenty. Requiring you to negate your
own decreases is the convention that produces a chart wrong in exactly one bar.

A subtotal is drawn from zero and does not reset the running total; the
connectors are what turn a row of floating bars into a running total.

Colours are the four semantic roles in `ChartComparisonColors`, so an application
whose convention runs the other way swaps two values rather than forking a layer.

## Dumbbell and lollipop

```kotlin
DumbbellChart(
    data = teams,
    category = { it.name },
    start = { it.lastYear },
    end = { it.thisYear },
    startLabel = "2024",
    endLabel = "2025",
)
```

```text
Android   ○───────●
iOS         ○──●
Web       ●──────────○
```

The **distance** is the reading: two bars side by side show the same numbers and
leave you to subtract. Before against after, actual against target, any two
comparable measurements of the same thing.

`LollipopChart` is the same layer with the stem starting at the baseline rather
than at a second value — a bar chart with the ink removed, which is better when
the categories are many and the values are close together.

The two ends differ in **shape** as well as colour, so a reader who cannot
distinguish the two colours can still tell before from after.

## Bullet graph

```kotlin
BulletChart(
    data = metrics,
    label = { it.name },
    actual = { it.value },
    target = { it.target },
    ranges = {
        listOf(
            BulletRange(0.0, 50.0, "Below"),
            BulletRange(50.0, 75.0, "On track"),
            BulletRange(75.0, 100.0, "Ahead"),
        )
    },
)
```

```text
Revenue  ░░░░░▒▒▒▒▓▓▓▓
         ██████████│
                   ↑ target
```

Stephen Few's replacement for the gauge, which spends a whole circle on one
number. Several stack into the space one dial would take and — because they share
an axis — can be compared with each other.

The target is a perpendicular **tick**, not a second bar: as a bar it would
compete for attention and turn "did we hit it" into a comparison of two lengths.

Ranges are intervals you name. Whether a high number is good is your knowledge,
not ChartKit's.

## Gauge chart

```kotlin
GaugeChart(
    value = 72.0,
    min = 0.0,
    max = 100.0,
    bands = listOf(
        GaugeBand(0.0, 50.0, "Below target"),
        GaugeBand(50.0, 75.0, "On target"),
        GaugeBand(75.0, 100.0, "Ahead"),
    ),
    shape = GaugeShape.ThreeQuarter,
    indicator = GaugeIndicator.Arc,
    centerContent = { Text("72", style = MaterialTheme.typography.headlineMedium) },
)
```

`GaugeShape` covers `SemiCircle`, `ThreeQuarter`, `FullCircle`, `Custom` and
`between(start, end)`. `GaugeIndicator` is `Arc`, `Needle` or both.

For a **scale** — tick marks, numbers, a proper needle and pivot — see
[dial gauges](#dial-gauges) below. This one is the filled arc: fewer parts, and
readable in a dashboard tile where a dial's numbers would not be.

Built on the polar engine, not a second one. A value outside `[min, max]` is
drawn at the end of the arc — there is nowhere else — but is **announced and
reported as itself**: a gauge that renamed 130% as 100% would hide the reading
most worth seeing.

## Dial gauges

A speedometer: an angular scale, tick marks, numbers, threshold bands, a needle
and a pivot. The other half of the gauge family — [`GaugeChart`](#gauge-chart)
above draws a value as a filled arc, and this draws an instrument.

```kotlin
DialGauge(
    value = speed,
    min = 0.0,
    max = 200.0,
    shape = GaugeShape.between(startAngle = -90f, endAngle = 90f),
    label = "Speed",
    unit = "km/h",
    bands = listOf(
        GaugeBand(0.0, 120.0, "Normal"),
        GaugeBand(120.0, 160.0, "Caution"),
        GaugeBand(160.0, 200.0, "Over limit"),
    ),
    ticks = GaugeTickConfig(interval = 20.0, minorCount = 4),
    valuePosition = GaugeValuePosition.BelowCenter,
    valueContent = { animated -> Text("${animated.roundToInt()} km/h") },
    modifier = Modifier.size(320.dp),
)
```

```text
        60    80   100  120
     40 ╲  ╲   │   ╱  ╱ 140
   20 ─  ╲  ╲  │  ╱  ╱  ─ 160
  0 ──     ╲   │   ╱     ── 180
              ╲│╱
               ●        82 km/h
```

The simplest useful call is two numbers:

```kotlin
DialGauge(value = 72.0, min = 0.0, max = 100.0)
```

### Not a second polar engine

Built on the same `PolarChartCore` as the pie, donut, radial bar and sunburst.
The coordinate system, the animation clock, the theme, the legend, the tooltip,
the selection state and the accessibility layer are shared. What a dial adds is
`GaugeScale` — the map from a number to an angle — and the marks that read
against it.

```text
value  →  normalise against [min, max]  →  0..1  →  startAngle..endAngle
```

Everything the dial draws is one of those two directions, and both live in one
place. Ticks, bands, needles, markers and hit testing all ask the same object,
so none of them can disagree at the ends of the arc.

### Angles

Zero is at twelve o'clock and angles increase clockwise, as everywhere else in
ChartKit. A gauge is stated either way round:

```kotlin
shape = GaugeShape.between(-90f, 90f)      // semicircle, opening upward
shape = GaugeShape.between(-135f, 135f)    // three-quarter dial
shape = GaugeShape.SemiCircle              // the same as the first
shape = GaugeShape.ThreeQuarter
shape = GaugeShape.FullCircle
shape = GaugeShape.Custom(startAngle = 30f, sweepAngle = 300f)
direction = PolarDirection.CounterClockwise
```

A partial sweep is not centred in the square its full circle would need — that
wastes half the space and leaves the pivot floating in the middle of a card. The
**arc's own box** is fitted instead, so a semicircle in a wide, short card is
sized by the width and pivots at the bottom, where a speedometer's does.

```text
  centred in the circle's box        fitted to the arc's box
   ╭───────────────────╮             ╭───────────────────────╮
   │    ╱────────╲     │             │  ╱─────────────────╲  │
   │   │    ●     │    │             │ │         ●         │ │
   │    ╲________╱     │             ╰───────────────────────╯
   ╰───────────────────╯
```

### Range

Any finite interval, not just a zero-based one:

```kotlin
DialGauge(value = 14.2, min = -20.0, max = 40.0, unit = "°C")    // crosses zero
DialGauge(value = 1013.0, min = 900.0, max = 1100.0, unit = "hPa")
```

`min == max`, a backwards range, `NaN` and infinities are refused at
construction with a `GaugeException` saying what to do instead — every one of
them would otherwise produce a `NaN` angle and a dial with no marks on it.

### Ticks and labels

Generated in the **value** domain, through the same nice-number generator every
Cartesian axis uses — a tick every fifteen degrees is a decoration, and a tick
every twenty km/h is a scale.

```kotlin
ticks = GaugeTickConfig(
    interval = 20.0,      // or null to derive round numbers
    count = 6,            // approximate, when no interval is given
    minorCount = 4,       // subdivisions per major interval
    minorInterval = null, // or an explicit minor spacing
    includeEnd = true,    // a dial whose last mark is 180 of 200 reads as broken
    placement = GaugeTickPlacement.Inside,   // Inside · Outside · Cross
)
labelFormatter = ChartNumberFormatters.integer(locale)
```

The count is derived from the arc's drawn length when neither is given, so the
same gauge at two sizes gets two sensible scales.

**Labels thin; ticks do not.** A dial with more marks than numbers is a normal
dial — every wristwatch is one — and a dial with overlapping numbers is
unreadable. When there is no room, ChartKit drops labels at a uniform stride and
keeps every tick, so the reader still sees the granularity and can count between
the numbers that remain.

### Units

```kotlin
unit = "km/h"
```

Written after the value and spelled out for a screen reader. Never appended to
the tick labels, which would repeat it a dozen times around the arc.

### Bands

```kotlin
bands = listOf(
    GaugeBand(0.0, 120.0, "Normal"),
    GaugeBand(120.0, 160.0, "Caution"),
    GaugeBand(160.0, 200.0, "Over limit"),
)
bandStyles = mapOf(
    2 to GaugeBandStyle(thickness = 0.5f, position = 0.8f, rounded = true, alpha = 0.9f),
)
```

**Band colours are visual style, not meaning.** The default is a monochrome ramp
of increasing emphasis, not a traffic light — ChartKit does not know whether
high is good. On a battery gauge the last band is the desirable one and on a
temperature gauge it is the alarming one, and a library that painted the third
band red would be asserting a meaning it cannot have.

Supply your own colours where you have a real severity to show, and **label the
bands as well**: colour alone is not available to every reader, and it is the
label — never the colour — that reaches a screen reader.

Bands that do not fit are handled by policy rather than drawn wrong:

| Case | `Clamp` (default) | `Skip` | `Reject` |
| --- | --- | --- | --- |
| Reaches past `max` | trimmed, flagged | dropped | `GaugeException` |
| Entirely outside | dropped | dropped | `GaugeException` |
| Zero width | dropped | dropped | dropped |
| Backwards (`from > to`) | read as the interval it names | | |

Overlapping bands are allowed by default and drawn in declaration order, with
the **first** match answering "which band is this value in" — so the status does
not depend on draw order even though the picture does.
`GaugeBandOverlap.Reject` refuses them outright.

### The current band

```kotlin
onReadingChanged = { reading ->
    status = reading.bandLabel      // "Caution", or null
    outOfRange = reading.isOutOfRange
}
```

Published so an application does not repeat the threshold lookup the gauge
already did. A status chip beside a dial that computed its own bands is one
refactor away from disagreeing with the arc it sits next to.

### Needle, pivot and pane

```kotlin
needle = GaugeNeedleStyle(
    shape = GaugeNeedleShape.Needle,  // Line · Triangle · Needle · Arrow
    length = 0.86f,                   // fraction of the radius, never pixels
    tail = 0.12f,                     // the counterweight stub behind the pivot
    baseWidth = 4.dp,
    tipWidth = 1.5.dp,
)
pivot = GaugePivotStyle(radius = 0.06f)
pane = GaugePane.Themed               // the dial's face; None by default
showTrack = true
```

Lengths are fractions of the radius, not pixels: a needle specified at `0.85`
stays right at every size and one specified at `120.dp` is right at one.

A partial sweep gets a **wedge** face, not a disc — a semicircular dial on a
circular face is not a semicircular dial.

### Markers

For the quantities a dial refers to but does not read:

```kotlin
markers = listOf(
    GaugeMarker(value = 112.0, label = "Limit", shape = GaugeMarkerShape.Triangle),
    GaugeMarker(value = 160.0, label = "Redline", shape = GaugeMarkerShape.Line, position = 0.98f),
)
```

Lighter than a needle by design: a second needle says "another reading", and a
marker says "a line on the dial".

### Animation

The needle travels; it does not jump. Each needle animates independently through
the chart's own animation configuration, and `valueContent` receives the
**animated** value, so the number and the needle arrive together — a label
reading 80 beside a needle already at 140 is worse than no label.

```kotlin
var speed by remember { mutableStateOf(80.0) }
DialGauge(value = speed, min = 0.0, max = 200.0, animation = ChartAnimation.Default)
speed = 140.0   // the needle sweeps; no imperative update call exists
```

A value that changes again mid-flight **retargets** rather than restarting, so
`80 → 120 → 160` in quick succession is one continuous sweep rather than a
stutter back to 80 each time. That is what makes the dial usable on a live feed.

Needles are matched by `GaugeValue.id`, never by list position: inserting a
target needle above the current one would otherwise animate the current needle
to the target's value and back, which looks exactly like a data error.

### Overflow

```kotlin
overflow = GaugeOverflow.Clamp          // the needle stops at the end. The default.
overflow = GaugeOverflow.AllowOverflow  // it swings past — a tachometer's redline
overflow = GaugeOverflow.Reject         // an out-of-range reading is a bug
```

Under `Clamp` the needle is pinned, and the **announcement, the tooltip and
`onReadingChanged` still report the real number**. A gauge that renamed 250 km/h
as 200 would be hiding exactly the reading its owner most needs.

### Multiple needles

```kotlin
DialGauge(
    series = listOf(
        GaugeValue("current", speed, "Current speed"),
        GaugeValue("target", 130.0, "Target", style = GaugeNeedleStyle.Target),
        GaugeValue("average", 96.0, "Average", style = GaugeNeedleStyle.Thin),
    ),
    min = 0.0,
    max = 200.0,
    unit = "km/h",
    legend = LegendPosition.Bottom,
)
```

The first needle is the dial's primary reading — the one an interactive gauge
adjusts and the one a bare selection reports. The legend is off for a single
needle: a key for one needle is furniture repeating what the dial already says.

### Display or control

```kotlin
interaction = GaugeInteraction.None   // a display. The default.
interaction = GaugeInteraction.Tap    // a tap on the arc reads a value out of it
interaction = GaugeInteraction.Drag   // drag round the dial to set it
onValueChange = { speed = it }
step = 5.0
```

A speedometer is not a knob, so a dial is a display until told otherwise. The
gauge never owns the value: `onValueChange` is required for either interactive
mode to do anything.

A tap off the arc — below a dial that opens upward, say — is **not a reading**
and reports nothing. Wrapping it to the nearest end is how a naive `atan2` gauge
sets itself to maximum when a finger strays.

### Interactive gauges and accessibility

An adjustable dial publishes `ProgressBarRangeInfo` and a set-progress action,
so a screen reader gets **increase and decrease** in `step` increments, and
responds to the arrow keys:

```text
← ↓   decrease      → ↑   increase      Home  minimum      End  maximum
```

A gauge that could only be dragged would be a control that a keyboard user, a
switch user and anyone without a touchscreen cannot operate.

### Accessibility

```text
Speed. Current speed: 82 km/h. Range: 0 to 200 km/h. Current range: Normal.
```

The band is named **only because the caller named it**. ChartKit never invents
a severity: a gauge announcing "Danger" because a band was red would be
inferring meaning out of a colour, which is exactly what a screen-reader user
cannot check. An unlabelled band contributes nothing to the announcement.

```kotlin
DialGauge(
    value = 82.0, min = 0.0, max = 200.0,
    label = "Speed",                        // announced first
    unit = "kilometres per hour",           // spelled out, not "km/h"
    bands = listOf(GaugeBand(0.0, 120.0, "Normal")),  // the caller's own words
)
```

### Small dials

```kotlin
detail = GaugeDetail.Auto      // Full · Compact · Auto
```

Below about `78.dp` of radius, `Auto` drops the minor ticks and thins the
numbers. Every band, needle and marker is still drawn: **detail is reduced,
never data**. Under about 150dp a dial is the wrong instrument anyway — reach
for [`GaugeChart`](#gauge-chart)'s filled arc, which stays readable in a
dashboard tile.

### Live values

```kotlin
val load by viewModel.cpu.collectAsStateWithLifecycle()
DialGauge(value = load, min = 0.0, max = 100.0, unit = "%")
```

Nothing gauge-specific: it is ordinary Compose state, and ChartKit's streaming
helpers apply as they do to any other chart. The face, the bands, the ticks and
the numbers are computed once and reused, so a value change costs a needle and a
number rather than a whole dial.

### Choosing between the two

| | `GaugeChart` | `DialGauge` |
| --- | --- | --- |
| Shows | a filled arc | an instrument |
| Scale | none | ticks and numbers |
| Readable at | any size | about 150dp and up |
| For | KPI tiles, progress, battery | speedometers, dashboards, adjustable dials |

They share `GaugeBand`, `GaugeShape` and the polar geometry; their rendering
stays separate, because an arc gauge with tick marks is neither one thing nor
the other.

## Timeline, range and Gantt charts

One layer behind three charts, because a point event is an interval with no end
and a task is an interval with a progress overlay.

```kotlin
RangeChart(
    data = bookings,
    start = { it.from },
    end = { it.to },
    lane = { it.room },
    label = { it.guest },
)
```

```text
Room A   ▐████████▌      ▐██████▌
Room B        ▐██████████▌     ◆
Room C   ▐███▌      ▐███████████▌
         09:00   12:00   15:00
```

- `TimelineChart` — point events, no end accessor
- `RangeChart` — durations in lanes
- `GanttChart` — durations with progress, milestones and a dependency model

**Not tied to project management.** Bookings, shifts, machine uptime,
appointments, deploy windows and process durations are the same shape; only your
lambdas differ.

Times are epoch milliseconds, the same currency `ChartX.Time` uses — `java.time`
is API 26 and ChartKit's floor is 24. A caller on `LocalDate` passes
`date.toEpochDay() * 86_400_000L`.

### Rows and lanes

Lanes and their rows sit at **integer positions on the value axis**, exactly as a
heatmap's rows do, and the chart labels that axis through `ChartAxis.ticks`. That
is what lets the whole Cartesian engine work here unchanged: the time scale, the
viewport, zoom, pan, the crosshair, annotations and range selection.

Two entries in one lane that overlap in time are stacked onto separate rows.
Drawing them over each other would hide one, and hiding data is never the right
default for a chart whose content is when things happened.

### Gantt, and what it is not

Tasks, progress, milestones, lanes and a dependency model. It does **not**
attempt scheduling, critical-path analysis, resource levelling or automatic
dependency routing — those are an application's concerns, and a charting library
that guessed at them would be wrong in ways its users could not correct.
Dependencies are drawn as direct connectors; routing them around the bars in
between is an edge-routing pass, and doing it badly puts arrows through the tasks
they connect.

## Network graph

```kotlin
NetworkGraph(
    nodes = services,
    edges = dependencies,
    nodeId = { it.name },
    source = { it.from },
    target = { it.to },
    nodeWeight = { it.requests },
)
```

Two lists and three lambdas. Nothing is required of your types: no interface, no
conversion, no reflection, and a selection hands your own object back.

### Layouts

```kotlin
GraphLayoutStrategy.Circular(GraphLayout.ByDegree)
GraphLayoutStrategy.ForceDirected(seed = 20_240_101, iterations = 400)
```

The force layout is **seeded**, so the same graph draws the same picture every
time — a layout that reshuffled itself on every launch would be unusable and
untestable. It steps in batches on `Dispatchers.Default`, publishes immutable
snapshots at a frame's cadence, stops as soon as it settles, and restarts when a
node is dragged. Changing the data or the strategy cancels the outstanding work.

Above `ForceSimulation.MAX_SIMULATED` nodes the circular layout is used instead:
the pairwise repulsion beyond that costs more than the picture is worth, and a
graph of ten thousand nodes is not a picture.

### Dragging and the viewport

Dragging a node **pins** it and lets the simulation rearrange its neighbours
around it; `layoutState.releaseAll()` hands them back. `ChartPlanarViewportState`
zooms and pans in two dimensions — deliberately a different type from
`ChartViewportState`, which is a window along one axis because that is what
zooming a time series means.

Positions live in parallel arrays in a unit square, so the consumer's own list
stays immutable and a resize does not restart the simulation.

### Accessibility

The summary names the size and the most connected nodes; the selection names the
node, its degree and its neighbours. A semantics tree containing every edge of a
three-hundred-node graph is not access, it is noise — `graphDataTable()` is how a
reader gets at the connections.

## Set relationship diagrams

Venn and Euler diagrams over sets, intersections and containment:

```kotlin
VennDiagram(
    sets = listOf(
        SetDefinition(id = "android", label = "Android", value = 200),
        SetDefinition(id = "ios", label = "iOS", value = 160),
    ),
    intersections = listOf(
        SetIntersection(sets = setOf("android", "ios"), value = 70),
    ),
    modifier = Modifier.fillMaxWidth().height(280.dp),
)
```

That draws three regions — "Android only" at 130, "iOS only" at 90, "Android and
iOS" at 70 — and every one of those numbers is **derived**. You state totals; the
exclusive values come from inclusion–exclusion, so the regions partition the
union and the numbers in the picture add up.

### Sets, intersections and regions

Three quantities, and confusing any two of them is how a hand-built Venn diagram
ends up self-contradictory:

| | Means | Where it comes from |
| --- | --- | --- |
| `SetDefinition.value` | the set's **total** | you |
| `SetIntersection.value` | items in *at least* these sets | you |
| `SetRegion.value` | items in **exactly** these sets | derived |

`{android, ios} = 70` means seventy people have both, *including* any who also
have Web. The people who have Android and iOS and nothing else is a different,
smaller number, and it is what a tooltip over that region reports.

Intersections are keyed by a `Set`, so `{a, b}` and `{b, a}` are the same
combination by construction. An unstated combination is read as **empty**, not
as unknown.

### Venn or Euler

```kotlin
VennDiagram(sets = kingdoms, containments = listOf(SetContainment("animals", "mammals")))
EulerDiagram(sets = kingdoms, containments = listOf(SetContainment("animals", "mammals")))
```

Same data, two different pictures, and the difference is the point:

- A **Venn** diagram draws every combination the sets could produce, whether or
  not anything is in it. Three sets get seven regions even when two of them
  share nothing, and tapping the empty one reports zero. It is a template for
  discussing what *could* overlap.
- An **Euler** diagram draws only what occurs. Mammals is nested inside Animals
  because every mammal is an animal; Plants sits beside them touching nothing.
  There is no region for "a mammal that is a plant", because there is no such
  thing. It is a report of what *does*.

Both run on the same engine. They differ in one thing — the layout strategy — so
there is no second rendering stack, no second model, and no second set of
behaviour to keep in step.

### Containment

Nesting is stated directly rather than as a lattice of intersections:

```kotlin
EulerDiagram(
    sets = isles,
    containments = listOf(
        SetContainment("british-isles", "british-islands"),
        SetContainment("british-islands", "united-kingdom"),
        SetContainment("united-kingdom", "great-britain"),
    ),
    intersections = listOf(
        // The one relationship the tree cannot express: Northern Ireland is in
        // both the United Kingdom and the island of Ireland.
        SetIntersection(setOf("united-kingdom", "ireland-island"), 8),
    ),
)
```

`SetContainment` is a claim about the **data**, not a layout hint: it changes the
cardinalities, the regions, the validation and the accessibility text, and a Venn
layout of the same data still draws every theoretical region. Containment is
transitive, so stating `A ⊃ B` and `B ⊃ C` is enough, and the combinations it
implies are derived — including the ones it implies *downward*, which is what
makes an overlap deep in one branch propagate correctly to another.

Without it, an unstated combination is empty, so a four-level nesting would need
every triple and quadruple written out by hand.

### Sizing

```kotlin
VennDiagram(sizing = SetSizing.Conceptual)    // the default for Venn
VennDiagram(sizing = SetSizing.Proportional)
EulerDiagram(sizing = SetSizing.Proportional) // the default for Euler
```

**Conceptual** draws equal, readable shapes and shows the *structure* — right
for a teaching diagram, a marketing-scope diagram, or anything carrying icons,
where the reader is being shown which things overlap rather than how many of each
there are.

**Proportional** makes area track cardinality — area, not radius, so a set twice
the size is drawn twice as big rather than four times — and solves the centre
positions for the stated intersection areas.

### It is an approximation, and it says so

Area-proportional set diagrams are not always possible. Three circles have six
degrees of freedom and a three-set system has seven quantities to reproduce; no
four circles can produce all fifteen regions of a four-set Venn at all. So the
layout **measures** how close it got:

```kotlin
SetLayoutQuality(
    meanAreaError,     // mean |desired − actual| as a share of the union
    worstAreaError,    // and the combination it belongs to
    regionCoverage,    // the fraction of asked-for combinations actually drawn
    iterations,        // solver sweeps, bounded
)
```

Four sets switch to four congruent ellipses, which do produce all fifteen
regions. Five and above use an ellipse rosette whose coverage is sampled and
reported rather than assumed — a diagram of eight sets wants an UpSet plot, and
the model here is already the model that would feed one.

### The solver is deterministic

Same data, same configuration, same picture — on every recomposition, on every
device, in every test. There is no seed because there is no randomness: the
solver is a pattern search from a fixed analytic starting arrangement, bounded by
`SetLayoutConfig.maxIterations`.

```kotlin
layoutConfig = SetLayoutConfig(maxIterations = 220, tolerance = 1e-4)
layoutConfig = SetLayoutConfig.Fast   // fewer sweeps, for a diagram being scrubbed
```

An inconsistent dataset cannot spin it: the ceiling is reached rather than
exceeded.

### Placing the shapes yourself

For the diagram whose arrangement *is* the message — five circles positioned to
create exactly the overlaps being talked about — hand them over:

```kotlin
SetDiagram(
    data = SetAnalyzer.analyze(scopes),
    layout = SetDiagramLayout.Custom(
        mapOf(
            "ppc" to SetShape.Circle(-0.30, -0.55, 0.62),
            "seo" to SetShape.Circle(-0.70, 0.05, 0.62),
            …
        ),
    ),
)
```

Everything else still works: the geometry is fitted to the plot, regions are
found, labels are anchored, hit testing is exact.

### From real collections

```kotlin
VennDiagram(
    sets = listOf(
        SetItems("android", "Android", androidUsers),
        SetItems("ios", "iOS", iosUsers),
    ),
    itemKey = User::id,
)
```

Every intersection is counted rather than stated, so the numbers cannot disagree
with each other. The collections are read **once** and reduced to counts — the
diagram never holds a hundred thousand user records — and an item whose key
appears twice in one collection counts once, because these are sets and not
multisets.

### Impossible data is refused, not drawn

```text
A = 10, B = 20, A ∩ B = 50    ← the shared part cannot exceed either whole
```

Two rules are checked. **Monotonicity**: adding a set to a combination can only
remove items, so no intersection can exceed any part of it. **Non-negative
regions**: every derived exclusive value must be at least zero, which catches
three pairwise overlaps that individually fit but together demand more items than
the sets contain.

`SetValidationMode.Strict` — the default — throws a `SetDataException` naming the
offending combination. `Lenient` records diagnostics and draws the best
approximation, for live data where a transient inconsistency should degrade the
picture rather than take the screen down.

### Colour

```kotlin
colorMode = SetColorMode.Blend   // the default for Venn
colorMode = SetColorMode.BySet   // the default for Euler
```

Every logical region is drawn as a **real path** — the intersection of the shapes
it belongs to, minus every shape it does not — and filled once with its own
colour. That is what makes the rest of this possible.

**Blend** gives each region the mean of its members' colours. It is *order
independent*: the usual implementation paints translucent circles on top of one
another, so the same overlap comes out differently depending on which set was
declared first. Here it cannot. It is also opaque, so a region's colour is the
colour the legend promised rather than that colour composited over whatever was
behind the chart.

**BySet** gives each region the colour of its first member — flat and
unambiguous.

Either can be overridden per region, which is what a design system with exact
semantic colours needs:

```kotlin
intersectionStyle = { region ->
    if (region.memberships == setOf("android", "ios")) SetStyle(fill = Brand.Shared) else null
}
```

Set fills come from the theme's series palette, so a Venn diagram beside a bar
chart of the same categories colours them alike. Outlines, label colours, the
selection wash and the dimming come from `ChartKitTheme.colors.set`, and all of
them are derived from the Material scheme, so a set diagram is legible in light
and dark without being told.

### Labels, icons and logos

Canvas draws the shapes; **Compose** draws everything that has to be measured,
themed, or carry its own semantics:

```kotlin
VennDiagram(
    setLabel = { scope ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.leaf), contentDescription = null)
            Text(scope.set.label)
        }
    },
    regionLabel = { scope ->
        Text(namesFor[scope.region.id].orEmpty())   // "Viable", "Sustainable"…
    },
    regionContent = { scope ->
        SetIconGroup(items = tools[scope.region.id].orEmpty(), available = scope.clearance) {
            Image(painterResource(it.logo), contentDescription = it.name)
        }
    },
)
```

- A **set label** is anchored in the region where that set is the *innermost*
  one, not at its centre — the middle of a circle in a three-set Venn is the
  triple overlap, and a set labelled there names the wrong thing. A set that
  contains others is labelled near the bottom of its own ring, which is the
  conventional place and the one reliably clear of its children; one that
  contains nothing is pushed away from the diagram's centre, so it does not land
  on its own region's value.
- A **region label** replaces the default value text. Naming intersections
  instead of counting them is exactly what a conceptual diagram wants.
- **Region content** is anything at all. `SetIconGroup` packs a group into the
  square that fits inside the region and collapses the remainder to a `+N`,
  because an icon straddling a boundary belongs, as far as a reader is concerned,
  to whichever region it is mostly in.

ChartKit loads no images and depends on no icon library. It supplies the anchor
and the room; you supply the content.

Every anchor comes with a **clearance** — the radius of the largest circle that
fits there without leaving the region — so content knows how much space it has
before it decides to appear. `SetContentOverflow` says what happens when it does
not fit: `Hide` (the default, and the honest one), `Clip`, `ScaleDown` or
`Allow`.

### Selection

A tap selects a **region**, not a set:

```kotlin
onSelectionChanged = { selection ->
    val region = selection?.set ?: return@VennDiagram
    readout = "${region.label}: ${region.value}"     // "Android & iOS: 70"
}
```

Membership is decided by geometry, never by draw order. Every shape is tested,
the full membership is collected, and the region is looked up by it — so tapping
the middle of a three-circle overlap selects the triple intersection rather than
whichever circle happened to be painted last, and the triple intersection is
reachable at all.

`ChartSelectionDetails.Set` carries the memberships, the exclusive value, the
intersection total, the region's name, the set definitions behind it, and whether
the region is one Venn semantics drew despite it being empty.

`SetFocusMode.DimUnrelated` dims everything outside the selection, which is
easier to read than an outline when the selected region is a sliver.

### Naming, and other languages

```kotlin
naming = SetRegionNaming(separator = " et ", exclusiveSuffix = " seulement")
```

"only" and "&" are English. They are parameters rather than constants, so a
diagram shipped anywhere else says the right thing — in the tooltip, in the
selection callback and in the accessibility table alike.

### Accessibility

A set diagram encodes its data in *overlap*, and overlap is not describable in a
sentence. So the tables are not a fallback here; for a reader who cannot see the
picture they are the diagram:

```kotlin
ChartWithDataTable(table = setDataTable(data, caption = "Platform usage")) {
    VennDiagram(sets = platforms, intersections = overlaps)
}

ChartDataTableView(setRelationshipTable(data))
```

`setDataTable` lists the **regions**, which partition the union and therefore add
up — a table of set totals would double-count everyone in an overlap.
`setRelationshipTable` states the structure in words: *contains*, *is contained
within*, *overlaps*, *shares nothing with*.

Every sentence comes from the modelled cardinalities. "Scotland is inside Great
Britain" is said only when the numbers say so, never because the labels look
geographic — and the relationship words are parameters too.

### Performance

```text
data ──▶ analysis ──▶ solve (once, bounded)
                          │
             plot size ──▶ uniform fit  ──▶ region sampling ──▶ boolean paths
```

The analysis runs once per data change; the solve runs once per data-and-strategy
change; the fit and the region sampling run once per plot size; the boolean
region paths are cached against the arrangement. A selection, a hover, a tooltip,
a colour-mode switch and an animation frame do none of them.

The work is exponential in the number of **sets** and linear in the number of
**items**, which is why `SetAnalyzer.MAX_ANALYZED_SETS` caps the former and
nothing caps the latter. The conceptual arrangements are analytic and run no
solver at all.

Shapes, fills, outlines and the selection are one canvas pass. There is no
composable per set and none per region.

## Choropleth map

Regions shaded by a statistic — a bar chart whose category axis is geography:

```kotlin
val counties = remember { GeoJson.parse(assets.open("counties.geojson").readBytes().decodeToString()) }

ChoroplethMap(
    geometry = counties,
    data = unemployment,
    featureKey = { it.properties.string("fips") },
    dataKey = { it.fips },
    value = { it.rate },
    modifier = Modifier.fillMaxWidth().height(320.dp),
)
```

`featureKey` reads the join key out of each region's GeoJSON properties;
`dataKey` reads it from your own record. Both are strings, compared exactly, and
matched through a hash map rather than by scanning — a thousand counties against
a thousand rows is a thousand lookups.

### What this is, and what it is not

A **statistical** chart. It draws boundaries you supply, shades them by value,
and lets a reader tap one. There are no tiles, no basemap, no satellite imagery,
no routing, no search and no GPS, and there is no dependency on Google Maps,
Mapbox or MapLibre. Those need a mapping SDK; a charting library that offered
half of one would be worse than one that offers none.

### ChartKit ships no geography

Deliberately. A usable world boundary file is several megabytes and a county
file is tens, and bundling one would put that in every consumer's APK —
including everyone who only wanted a bar chart. It would also be the wrong file
for anyone mapping sales territories, delivery zones or postcodes, which is what
most thematic maps in an app actually are.

Load your own, parse it once, and hold it:

```kotlin
val geometry = remember { GeoJson.parse(json) }   // never inside the chart call
```

### GeoJSON

`GeoJson.parse` accepts a `FeatureCollection`, a bare `Feature`, or a bare
geometry, and reads `Polygon`, `MultiPolygon`, `Point` and `MultiPoint`,
including polygons with holes.

`LineString` and `MultiLineString` are not read: a choropleth cannot shade a
line, and pretending otherwise would draw a road as a collapsed region. They are
**skipped and reported** rather than ignored:

```kotlin
val collection = GeoJson.parse(json)              // GeoParsePolicy.Skip, the default
collection.skipped.forEach { Log.w("geo", "${it.identifier}: ${it.reason}") }

GeoJson.parse(json, GeoParsePolicy.Reject)        // throws on the first problem instead
```

A `MultiPolygon` is **one** region, not several: tapping an island selects the
country it belongs to. Holes are genuinely unfilled rather than painted in the
background colour, which is what makes an enclave — a country inside another
country — correct rather than merely convincing.

Numeric properties read back as strings without a decimal point, so a FIPS code
written as `47` in the file matches the string `"47"` in your data. That single
detail is the most common reason a choropleth comes out blank.

### Projections

```kotlin
ChoroplethMap(projection = GeoProjection.Mercator, …)
```

| Projection | Use it for | It distorts |
| --- | --- | --- |
| `Equirectangular` (default) | A country, a state, a set of counties | East–west distance, increasingly with latitude |
| `EquirectangularProjection(standardParallel = 60.0)` | A high-latitude region | Less: the scale is true at the parallel you name |
| `Mercator` | A map that must line up with a tiled basemap | **Area**, severely — Greenland is drawn fourteen times its true size |

Mercator's area distortion matters more for a choropleth than for a basemap: the
reader is comparing coloured areas, and Mercator makes high-latitude regions
shout. Latitude is clamped to ±85.0511°, the same cut-off every slippy map uses,
so a single Antarctic vertex cannot produce an infinite bound and collapse the
fit.

Any other projection is a `GeoProjection` of your own — Albers, Equal Earth,
Lambert, an orthographic globe. Nothing in the fit, the viewport, the hit test or
the layer knows which one it was given.

### Colour scales

The default is a **quantile** scale over the joined values, and that default is
the point: geographic statistics are nearly always skewed — one city holds a
fifth of a country's population — and an equal-width ramp over that paints nine
regions the same shade and one dark.

```kotlin
scale = ChartColorScales.quantile(values, groups = 5)          // equal counts per band
scale = ChartColorScales.threshold(listOf(2.0, 5.0, 10.0), …)  // your own break points
scale = ChartColorScales.continuous(NumericDomain(0.0, 100.0)) // a smooth ramp
```

Quantile breaks are values that **exist in the data** — the nearest-rank
definition, not the interpolating one — because a legend printing a break of
41.7 between two counties at 40 and 43 invites the reader to look for a number
nobody measured. Ties collapse into fewer bands rather than producing bands
nothing can fall into.

`ChartColorLegend` renders whichever form the scale is: a gradient bar with
labelled ends for a continuous scale, discrete swatches with range labels for a
banded one. It is shown by `legend = LegendPosition.Bottom`, the choropleth's
default.

### Missing is not zero

A region with no matching record is drawn in the theme's "no data" colour, not
in the colour of zero; its tooltip and its screen-reader sentence both say "no
data"; and the legend gets a swatch explaining the colour. Painting an
unmeasured county with the low end of the ramp asserts a measurement nobody
took.

### The join report

The commonest failure of any choropleth is a key mismatch — `"CA"` against
`"California"`, `"06"` against `"6"` — and its symptom is a blank map with no
error. So the join reports itself:

```kotlin
ChoroplethMap(
    onJoin = { report ->
        Log.d("geo", "${report.matched} matched, " +
            "no data for ${report.unmatchedFeatureKeys}, " +
            "no region for ${report.unmatchedDataKeys}")
    },
    …
)
```

Duplicate keys are a `GeoDuplicatePolicy`: `First` (the default), `Last`, `Sum`
— right for counts, wrong for rates, which is why it is not the default — or
`Reject`, which throws naming the key.

### Selecting a region

A tap runs a bounding-box pre-filter through a uniform spatial grid, then an
even–odd point-in-polygon test that respects holes. A point on a border between
two regions belongs to exactly one of them, so selection does not flicker along
every boundary.

```kotlin
onSelectionChanged = { selection ->
    val geo = selection?.geo ?: return@ChoroplethMap
    readout = if (geo.hasValue) "${geo.featureLabel}: ${selection.y}" else "${geo.featureLabel}: no data"
}
```

`ChartSelectionDetails.Geo` carries the feature's id, its join key, its label,
its whole `GeoProperties` bag, whether it had a value, and its geographic
bounds — the last of which is what "focus on this region" needs.

### Zoom, pan and focus

```kotlin
val camera = rememberChartGeoViewportState()

ChoroplethMap(viewportState = camera, …)
TextButton(onClick = { camera.reset() }, enabled = !camera.isReset) { Text("Reset") }
```

Pinch zooms about the point under the fingers and a drag pans once zoomed.
Arrow keys pan, `+`/`-` zoom and `0` resets, so the map is explorable without a
touchscreen.

There is deliberately **no double-tap gesture**. A double-tap handler makes
Compose withhold every single tap for the length of the double-tap window, and a
third of a second before a region highlights is the wrong price for a shortcut
that `reset()` and the `0` key already cover. `camera.focusOn(bounds)` frames a region: the
zoom and pan are computed on the next frame, once the plot size is known, and
eased into.

At full extent there is nothing to pan — the map already fits — and the pan is
clamped so the geography cannot be dragged off the plot.

### Labels

Off by default. `GeoLabels.Auto` draws a region's name only where it **measures**
as fitting inside that region's own on-screen box, so a dense county map labels
the large counties and leaves the rest to the tooltip; a label wider than the
county it names reads as belonging to the neighbour it spills into.
`GeoLabels.All` draws every one, for a map of a dozen regions where the caller
knows they fit. `GeoLabels.SelectedOnly` draws just the selected region's.

Labels sit at the **area centroid** of the largest component, so a country's name
lands on its mainland rather than in the sea between it and its islands.

### Accessibility

A map encodes its data in position and colour, and a reader who cannot see it
gets neither — shape and adjacency are not describable in a sentence. So the
table is not a fallback here; it is the chart:

```kotlin
ChartWithDataTable(
    table = geoDataTable(
        geometry = counties,
        data = unemployment,
        featureKey = { it.properties.string("fips") },
        dataKey = { it.fips },
        value = { it.rate },
        order = GeoTableOrder.ByValueDescending,
    ),
) {
    ChoroplethMap(geometry = counties, data = unemployment, …)
}
```

The summary names the regions and their values and makes no claim beyond them —
no "high", no "clustered in the north-east", because those are statistical
assertions ChartKit has not computed.

### Performance

Geography is projected **once** per geometry-and-projection pair and cached
separately from the thematic style, so changing the year, the metric, the colour
scale or the selection redraws without touching a vertex. Screen-space paths are
cached against the transform, so only a zoom, a pan or a resize rebuilds them.
Features outside the visible extent are culled by box, and above 64 features hit
testing runs through a uniform grid rather than a scan.

For a very dense boundary file, `simplification` drops vertices closer than a
tolerance to the line they sit on (Ramer–Douglas–Peucker, iterative, in projected
units). It is **off by default** — silently discarding your geographic fidelity
is not a decision a chart should make for you.

## Annotations

Reference marks in the chart's own coordinate space:

```kotlin
LineChart(
    data = revenue,
    x = { it.month },
    y = { it.amount },
    annotations = listOf(
        horizontalRule(value = 100_000.0, label = "Target"),
        verticalRule(at = "Mar", label = "v2.0"),
        valueRange(from = 30_000.0, to = 40_000.0, label = "On track"),
        domainRange(from = "Feb", to = "Apr", label = "Campaign"),
        region(domainFrom = "Apr", domainTo = "Jun",
               valueFrom = 38_000.0, valueTo = 46_000.0, label = "Q2 goal"),
        eventMarker(at = "May", value = 44_100.0, label = "Record"),
    ),
)
```

Annotations belong to the **coordinate system**, so the same list works on a
line chart, a bar chart, a scatter and a candlestick chart, and a combined chart
gets them once rather than once per layer.

Positions along the domain go through the same `ChartXResolver` the data does,
so `verticalRule(at = "Mar")` lands on the March band and
`verticalRule(at = releaseMillis)` lands on the release date, with no separate
annotation type per axis kind — and a custom resolver taught about your own date
type applies to your annotations too.

**Ordering.** Regions and bands draw behind the data; rules and markers draw in
front. A shaded target zone drawn over the line would hide the values it exists
to be compared against, and a threshold line drawn behind would be invisible
exactly where it crosses the series. Both are overridable per annotation with
`order = AnnotationOrder.Above` / `Behind`.

**Axes widen to fit.** A target above every observed value is invisible
otherwise, and a reader who cannot see the target cannot see the gap to it. Turn
it off per annotation with `extendsDomain = false`.

**Styling** falls back to the theme, so an annotation that only needs to exist
is one line:

```kotlin
horizontalRule(
    value = 100_000.0,
    label = "Target",
    style = AnnotationStyle(
        color = Color.Red,
        dashed = false,
        labelPlacement = AnnotationLabelPlacement.Start,
    ),
)
```

Rules are dashed by default: a reference line drawn like the data invites the
reader to take it for a series.

**Accessibility.** A labelled threshold or event is announced — "Target:
100,000" is the whole reason the line is there. An unlabelled rule is decoration
and is not, because padding the summary with facts nobody stated makes the
useful parts harder to hear.

Event markers are selectable and produce a tooltip. Rules and regions are not: a
threshold is a reference the reader drew themselves, and selecting it would
report a number they already chose while stealing the tap from the data.

## Axes

```kotlin
LineChart(
    data = revenue,
    x = { it.month },
    y = { it.amount },
    xAxis = ChartAxis(title = "Month"),
    yAxis = ChartAxis(title = "Revenue", tickCount = 4),
)
```

Bar charts name theirs `categoryAxis` and `valueAxis`, because "x" and "y" stop
being useful once the chart can be horizontal.

| Parameter | Purpose |
| --- | --- |
| `visible` | A hidden axis takes no space, so the plot widens |
| `showLine`, `showTicks`, `showLabels` | Independent |
| `title` | Drawn outside the ticks; rotated a quarter turn on a vertical axis |
| `tickCount` | *Approximate* — see below |
| `maxLabels` | A hard cap applied after measurement |
| `labelOverflow` | `Skip` (default), `Rotate`, `None` |
| `valueFormatter`, `timeFormatter`, `categoryFormatter` | Per axis |
| `domain` | A `DomainPolicy`, overriding the chart's default |

Presets: `ChartAxis.Default`, `ChartAxis.Hidden`, `ChartAxis.LabelsOnly`.

### Tick counts are approximate

A round step and an exact tick count are in conflict: `0..100` with exactly
seven ticks needs a step of `16.67`. ChartKit picks the round step every time —
`0 20 40 60 80 100`, not `0 19.4 38.8 …` — because the number of labels is a
layout preference and their legibility is not. Ask for five and expect four to
six.

### Label overlap

Labels are **measured**, then thinned at a uniform stride with the first and
last kept. Nothing is truncated to an ellipsis and nothing is drawn on top of
its neighbour. `AxisLabelOverflow.Rotate` turns them 45° so more fit before
thinning starts — better for long category names, harder to read, so not the
default.

Axis gutters come from the measured labels, so `1,250,000` gets the room it
needs and `0..5` does not waste half the width. When several axes share a side,
each one's offset is the total of the gutters inside it — measured, never a
constant. See [multi-axis combo charts](#multi-axis-combo-charts).

## Scales

Three kinds of scale, and they answer different questions:

| Kind | Maps | Used by |
| --- | --- | --- |
| **position** | a value to a pixel | every axis |
| **size** | a value to a radius | bubble charts, graph nodes |
| **colour** | a value to a colour | heatmaps, calendar heatmaps |

A position scale is stated on the **axis**, because that is what it is a
property of — a chart with a log value axis and a linear domain axis is
ordinary, and a parameter on the chart could not express it:

```kotlin
LineChart(
    data = latencies,
    x = { it.at },
    y = { it.micros },
    yAxis = ChartAxis(title = "Latency (µs)", scale = AxisScale.Log()),
)
```

### Logarithmic

```text
1      10     100    1000
├──────┼──────┼──────┤
```

Equal pixel distances are equal **ratios**. Base ten by default; two and `e` are
the other common choices, and any base above one works.

What a log axis cannot do is represent zero or a negative number — `log(0)` is
negative infinity and `log(-1)` is not a real position. There is no correct
silent answer, so the behaviour is stated:

| `LogValuePolicy` | Behaviour |
| --- | --- |
| `Clamp` | pin the value to the axis floor. The default: the point stays on the chart, drawn slightly below where it belongs |
| `Skip` | treat it as missing, so the line breaks. Right when zeros mean "no reading" |
| `Reject` | throw, naming the value |

Ticks are powers of the base, subdivided (`1 2 3 5 10`) when there are few
enough decades for the subdivisions to be readable and strided when there are
too many.

### Symmetric log

```text
-1000  -100   -10   0   10    100   1000
  ├──────┼─────┼────┼───┼──────┼──────┤
             linear ↑↑↑
```

Logarithmic in both tails, linear across zero — the answer to the one thing a
plain log axis cannot do. Profit and loss, temperature anomalies, net flows and
score deltas all cross zero and span orders of magnitude.

```kotlin
yAxis = ChartAxis(scale = AxisScale.Symlog(linearThreshold = 1.0))
```

`linearThreshold` is the magnitude below which the axis is straight. The two
halves are continuous at the join by construction, so no kink appears there.

### Not a second scale type

A logarithmic axis is a linear mapping of `log(v)`. Expressing it that way — as a
[`ScaleTransform`](src/main/java/io/devkit/chartkit/scale/ScaleTransform.kt)
applied before the linear step — means the grid, the axis renderer, hit testing,
annotations, the crosshair, range selection, the viewport and **every layer**
work on a log axis unchanged, because all of them go through `scale` and
`invert` and neither knows the difference. A parallel `LogScale` class would have
needed every one of those to learn about it.

### A scale of your own

The interface is open. A probability axis, a power scale, a perceptual lightness
scale — implement four members and pass it:

```kotlin
val squareRoot = object : ScaleTransform {
    override fun forward(value: Double) = sqrt(value.coerceAtLeast(0.0))
    override fun inverse(transformed: Double) = transformed * transformed
    override fun ticks(domain: NumericDomain, count: Int) =
        TickGenerator.ticks(domain, count)
}

yAxis = ChartAxis(scale = AxisScale.Custom(squareRoot))
```

The one requirement is that `forward` be **strictly increasing** over the
domain. A non-monotonic transform makes two values share a pixel, and every hit
test, inversion and tick placement downstream then reports one of them wrongly.

## Secondary value axes

A second value axis on the opposite edge, with each layer bound to one
explicitly:

```kotlin
CartesianChart(
    valueAxis = ChartAxis(title = "Revenue", valueFormatter = money),
    secondaryValueAxis = ChartAxis(title = "Conversion %"),
) {
    bars(series = listOf(revenueSeries), category = { it.month }, value = { it.amount })
    line(
        series = listOf(conversionSeries),
        x = { it.month },
        y = { it.rate },
        valueAxis = ValueAxisBinding.Secondary,
    )
}
```

### Explicit, never inferred

ChartKit will not guess. A revenue series in pounds and a conversion series in
percent could be told apart by their magnitudes on Tuesday and not on Wednesday,
and a chart that silently moved a series to the other axis when its numbers
changed would be the worst kind of bug: invisible, intermittent, and wrong by a
factor nobody can see.

### Dual axes mislead easily

Two independent scales in one plot let the author choose where the lines cross,
which is a claim about the data that the data did not make. Reach for a second
axis when the quantities genuinely differ in kind and a reader needs both;
prefer two [linked charts](#linked-charts) when they do not.

A layer bound to the second axis is **drawn and hit-tested** against it, so a tap
on the conversion line resolves against percentages rather than against pounds.

This is the two-axis shorthand. For three or more, for axes with units, for two
axes on the same side, or for tick alignment, see
[multi-axis combo charts](#multi-axis-combo-charts) — the same engine, named
axes instead of `Primary` and `Secondary`.

## Multi-axis combo charts

Several series, several units, several value axes, one shared X domain — and no
new chart type. `CartesianChart` gained an axis registry; every existing layer
resolves its scale through it.

```kotlin
val Rainfall = ChartAxisId("rainfall")
val Temperature = ChartAxisId("temperature")
val Pressure = ChartAxisId("pressure")

@OptIn(ExperimentalChartKitApi::class)
CartesianChart(
    modifier = Modifier.fillMaxWidth().height(300.dp),
    legend = LegendPosition.Bottom,
    crosshair = CrosshairConfig.Vertical,
) {
    yAxis(
        id = Rainfall,
        position = AxisPosition.Start,
        title = "Rainfall",
        unit = ChartUnit.Custom("mm", "millimetres"),
        domain = DomainPolicy.IncludeZero(),
        primary = true,
    )
    yAxis(
        id = Temperature,
        position = AxisPosition.End,
        title = "Temperature",
        unit = ChartUnit.Custom("°C", "degrees Celsius"),
        domain = DomainPolicy.Auto(),
    )
    yAxis(
        id = Pressure,
        position = AxisPosition.End,
        title = "Pressure",
        unit = ChartUnit.Custom("hPa", "hectopascals"),
        domain = DomainPolicy.Auto(),
    )

    bars(series = listOf(rainfall), category = { it.month }, value = { it.mm }, yAxis = Rainfall)
    line(series = listOf(temperature), x = { it.month }, y = { it.celsius }, yAxis = Temperature)
    line(series = listOf(pressure), x = { it.month }, y = { it.hPa }, yAxis = Pressure)
}
```

Three units, three scales, three formatters, one plot area:

```text
     Rainfall                                    Temperature   Pressure
 (mm) 100 ┤ ▄                          ▄  ▄ ├ 20 (°C)     ├ 1020 (hPa)
       75 ┤ █  ▄        ╭─────╮        █  █ ├ 15          ├ 1016
       50 ┤ █  █  ▄  ▄──╯     ╰──╮  ▄  █  █ ├ 10          ├ 1012
       25 ┤ █  █  █  █           ╰─ █  █  █ ├  5          ├ 1008
        0 ┼──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴ 0            ├ 1004
          Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec
```

### Axis identity

Layers name their axis; they do not index it.

```kotlin
val Temperature = ChartAxisId("temperature")   // not axisIndex = 1
```

`axisIndex = 1` means "the second axis I happened to declare". Insert an axis
above it, reorder a `when`, put one behind a feature flag, and every layer below
silently rebinds to a different quantity. Nothing fails; the chart just starts
measuring revenue against the conversion scale. A name cannot drift, and a layer
naming an axis that does not exist is a configuration error reported by name:

```text
Layer "line1" references Y axis "tempreature", but no Y axis with that id is
registered. Registered Y axes: "rainfall", "temperature", "pressure".
```

Never a silent fallback to the primary axis. A conversion rate drawn against a
revenue scale is not a degraded chart, it is a wrong one — and it still looks
like a line.

### The axis registry

`AxisRegistry` holds every axis and validates the declaration at composition:

| Mistake | What happens |
| --- | --- |
| Two axes with the same id | `ChartAxisException` — the later one would silently win |
| A Y axis at `Bottom` on a vertical chart | `ChartAxisException` — its scale would run across the plot |
| Two axes marked `primary` in one dimension | `ChartAxisException` — grid ownership would be arbitrary |
| A layer bound to an unregistered axis | `ChartAxisException` naming the layer, the axis and what exists |
| Stacked bars across two axes | `ChartAxisException` — one pile of segments in two units |
| A series in `°C` on an axis in `USD` | An `AxisDiagnostic`, and the chart still draws |

The last row is deliberately not an exception. A unit mismatch is often the
caller discovering that two of their own series really are in different units,
and a drawable chart with a diagnostic is more useful than a crash. Diagnostics
arrive through `onAxisDiagnostics`.

### Scale resolution

```text
layer  →  yAxis id  →  AxisRegistry  →  domain  →  LinearScale  →  pixels
```

Resolved **once**, before drawing: each layer is handed a `CartesianCoordinates`
built over its own axis' scale, sharing the chart's plot area and domain axis.
Draw loops hold a direct scale reference and never look an id up per point.

That is also why no layer changed. A bar layer draws against the coordinates it
is given; it has no idea whether the chart has one value axis or four.

### Independent domains

Each axis' interval comes from **its own** layers, never from the chart's:

```text
rainfall axis     ←  rainfall series only         0 … 100 mm
temperature axis  ←  temperature series only      4 … 18 °C
pressure axis     ←  pressure series only      1012 … 1019 hPa
```

All three map into the same `plotTop … plotBottom`. Several series can share one
axis — actual, forecast and historical average all on `temperature` — and the
axis takes the union of those and nothing else.

Each axis takes its own `DomainPolicy`: `Auto`, `IncludeZero`, `Fixed`,
`Bounded`. Bars want `IncludeZero`, because a bar length is only honest measured
from zero; lines usually do not, because forcing zero onto 4–18 °C flattens the
only variation worth seeing.

### Multiple axes per side, and automatic offsets

Two axes may share an edge. The layout engine measures each one's ticks, labels
and title and stacks them outward in declaration order:

```text
                     ┌ plot ┐
      Rainfall ──────┤      ├────── Temperature ── Pressure
                     └──────┘         offset 0     offset 38
```

No caller specifies `offset = 48.dp`. A chart with a `0..5` axis and a
`0..1,250,000` axis on the same side needs two different offsets, and no
constant is right for both. `ChartAxisSpec.offset` exists as an advanced
override and almost nothing should use it.

The measurement order is what makes it work:

```text
available width → title and legend → start-side axes → end-side axes
→ bottom and top axes → plot area → data layers
```

Plot bounds are never computed before the axes are measured.

### Tick alignment

```kotlin
CartesianChart(tickAlignment = AxisTickAlignment.Aligned) { … }
```

Not the tick *values* — three axes in millimetres, degrees and hectopascals have
no values in common. What aligns is the screen rows:

```text
Rainfall   Temperature   Pressure
     250            40       1040   ← one row
     200            30       1030
     150            20       1020
     100            10       1010
      50             0       1000
       0           -10        990   ← one row
```

Each axis still gets round numbers. Alignment chooses a **step** off the
nice-number ladder and extends the domain outward to a multiple of it, rather
than dividing the existing domain into equal parts — the first gives perfect
rows and labels like `17.3`, the second gives readable labels and a little empty
space at the top of the plot. ChartKit takes the second every time.

`Independent` is the default, and it is the honest one: an aligned axis has been
widened past its data to make the rows line up, and that trade is worth stating
rather than making silently everywhere.

A log axis opts out — its ticks are powers, and forcing them onto shared rows
would relabel it in numbers that are not powers of anything.

### Zero alignment

```kotlin
yAxis(id = ProfitAxis, alignZero = true, …)
yAxis(id = MarginAxis, alignZero = true, …)
```

Both axes put the same number of intervals below zero, so the two baselines land
on one row. Without it, a chart of profit change and margin change draws two
zero lines at different heights and appears to disagree with itself.

It can decline. An axis over `[999, 1001]` asked to share a zero row with one
over `[-5, 5]` would have to span `[-1000, 1000]`, flattening its own data into a
line. Past a 4× widening the axis keeps its own interval and an `AxisDiagnostic`
says which axis and why — the only outcome that is neither a lie nor a crash.

### Grid ownership

```kotlin
yAxis(id = Rainfall, grid = AxisGridMode.Primary, primary = true)   // draws the grid
yAxis(id = Pressure, grid = AxisGridMode.Hidden)                    // does not
```

By default only the primary axis owns the horizontal grid. One grid per value
axis produces three interleaved sets of lines at unrelated intervals — a moiré
in which every line looks meaningful and only a third of them are for any one
series.

The corollary is worth stating: **with `Independent` ticks, the grid represents
the primary axis' tick positions and nobody else's.** A line crossing a gridline
tells you something about the primary axis and nothing about the others. Turn on
`Aligned` when readers will use the grid to read a secondary axis.

### Axis visibility

```kotlin
yAxis(id = Pressure, visibility = AxisVisibility.Auto)   // the default
```

An axis with no visible layer on it is measuring nothing, so `Auto` hides it —
and the plot takes back the gutter. With `legendTogglesSeries = true`, hiding the
only series on an axis removes the axis, recomputes the layout and rescales the
remaining ones. The domains of the other axes are unaffected: they never
included that series.

### Series-to-axis colour

```kotlin
yAxis(id = Temperature, style = AxisStyleMode.MatchSeries)
```

Tints the axis' tick labels and title with its first series' colour. The axis
*line* is never tinted: a coloured rule across the edge of a plot reads as data,
and an axis is not data. `Neutral` is the default — three axes each painted in
their series' colour turn the edges of the plot into a second legend competing
with the first.

### Units

```kotlin
ChartUnit.Percent                              // 45%      "45 percent"
ChartUnit.Currency("GBP")                      // 82 GBP
ChartUnit.Count                                // 1,240
ChartUnit.Custom("°C", "degrees Celsius")      // 14.2 °C  "14.2 degrees Celsius"
```

There is no dimensional analysis here and no conversion. A unit labels the axis
and the tooltip consistently, gives a screen reader something pronounceable, and
lets ChartKit notice a series in `°C` bound to an axis in `USD`. A series states
its own with `ChartSeries(…, unit = …)`; stating nothing makes no claim and is
never reported.

### Mixed layers

Any combination the units justify. There is no `BarLineChart`,
`BarLineAreaChart` or `ThreeAxisWeatherChart`, and adding one would have been the
wrong answer to all of them:

```kotlin
bars(series = listOf(bookings), category = { it.month }, value = { it.count }, yAxis = BookingsAxis)
line(series = listOf(revenue),  x = { it.month }, y = { it.gbp },  yAxis = RevenueAxis)
line(series = listOf(rate),     x = { it.month }, y = { it.pct },  yAxis = ConversionAxis)
```

Candles, volume, scatter, area, waterfall, dumbbell, lollipop, bullet and custom
layers all take `yAxis` too:

```kotlin
volume(data = prices, x = { it.at }, volume = { it.volume }, yAxis = VolumeAxis)
candles(data = prices, x = { it.at }, open = { it.open }, high = { it.high },
        low = { it.low }, close = { it.close }, yAxis = PriceAxis)
line(series = listOf(movingAverage), x = { it.at }, y = { it.value }, yAxis = PriceAxis)
```

A candle's open, high, low and close all resolve through the one price axis; the
moving average shares it because it *is* a price.

Stacking is the one restriction. Stacked bars share a baseline and a scale, so
two stacked bar layers on different axes are refused rather than drawn — they
would occupy the same category bands and read as one pile of segments measured in
two units.

### Crosshair and tooltip

One vertical guide represents the shared X selection, never one per series. The
tooltip carries a row per axis, each written by that axis' own formatter:

```text
March
Rainfall       82 mm
Temperature  14.2 °C
Pressure    1018 hPa
```

Every entry knows where it came from:

```kotlin
tooltip = { data ->
    Column {
        Text(data.xLabel)
        data.entries.forEach { entry ->
            Text("${entry.seriesName} (${entry.axisTitle}): ${entry.text}")
            // entry.axisId, entry.unit, entry.value, entry.item, entry.position
        }
    }
}
```

Row order is deterministic — `ChartTooltipOrder.Declaration` (the default),
`ByAxis(listOf(…))`, or `Custom(comparator)`. Rows never come back in map
iteration order.

Per-axis readout chips are opt-in:

```kotlin
crosshair = CrosshairConfig(enabled = true, axisValueLabels = true)
```

```text
 82 mm ┤                            ├ 14.2 °C
       │            ╷               │
       │            ╷               ├ 1,018 hPa
```

Off by default: on a three-axis chart they duplicate a tooltip that already lists
the same three numbers. Turn them on for a chart with no tooltip, where the chips
*are* the readout.

A series with no value at the selected x is reported as missing rather than
interpolated.

### Annotations on a secondary axis

```kotlin
annotations = listOf(
    horizontalRule(value = 30.0, label = "Heat threshold", valueAxis = TemperatureAxis),
)
```

Unqualified annotations go to the primary axis, which is right for every
single-axis chart. On a multi-axis chart it is not optional information: a rule
at `30` means 30 °C on the temperature axis and 30 mm on the rainfall one, and a
chart that guessed would draw the line in the wrong place and look entirely
plausible doing it. Each annotation also widens the axis it names — and only
that one.

Vertical rules and domain ranges need no axis: they sit on the shared X domain.

### Zoom and pan

The shared X viewport is the single source of truth, so every layer moves
together and no two series can drift apart. `ChartViewportState` is unchanged;
multi-axis charts zoom in X exactly as single-axis ones do.

### Small screens

```kotlin
CartesianChart(axisDensity = AxisDensity.Auto)   // Full, Compact, Auto
```

Three axes are readable on a tablet and ruinous on a phone in portrait. `Auto`
compacts below roughly `112.dp` per axis plus one for the plot: fewer ticks,
abbreviated numbers. It never removes an axis and never overlaps text — the
reader loses resolution rather than a quantity. When even compaction leaves the
plot under about a third of the width, an `AxisDiagnostic` says so rather than
rendering something illegible.

### Accessibility

The summary names the measures before any of the numbers:

```text
3 measures. Rainfall: millimetres. Temperature: degrees Celsius.
Pressure: hectopascals. Rainfall: 12 data points. …
```

A selection is announced across every axis, each value in its own unit:

```text
March. Rainfall: 82 millimetres. Temperature: 14.2 degrees Celsius.
Pressure: 1018 hectopascals.
```

Titles and spelled-out units, never internal ids — `pressure-axis-2` would be
read out loud. `mm` is spelled `millimetres` for the same reason.

The data table adds a unit column, because `82`, `14.2` and `1018` under one
"Value" heading are three quantities presented as if they were comparable:

```kotlin
comboDataTable(
    ComboMeasure("Rainfall", listOf(rainSeries), { it.month }, { it.mm },
        unit = ChartUnit.Custom("mm", "millimetres")),
    ComboMeasure("Temperature", listOf(tempSeries), { it.month }, { it.celsius },
        unit = ChartUnit.Custom("°C", "degrees Celsius")),
    xColumn = "Month",
)
```

### Existing charts are untouched

`LineChart`, `BarChart`, `AreaChart`, `ScatterChart` and `CandlestickChart` take
no axis ids and need none. Internally they go through the same registry, with
`ChartAxisId.DefaultX` and `ChartAxisId.DefaultY` created for them — one code
path, not a simple one and a general one that drift. The older
`secondaryValueAxis` / `ValueAxisBinding.Secondary` API still works and resolves
to `ChartAxisId.SecondaryY`.

### Use two axes sparingly

Two independent scales in one plot let the author choose where the lines cross,
which is a claim about the data that the data did not make. ChartKit does not cap
the count — a legitimate three-axis chart is easy to think of, and a hard limit
would be an engine restriction standing in for editorial judgement — but **two or
three is the practical maximum** for something a reader can read.

When you do use them: label every axis, state every unit, keep the number small,
and turn on `Aligned` ticks when readers will compare against the grid. When the
quantities are not really related, prefer two [linked charts](#linked-charts).

## 3D columns

Grouped and stacked columns projected through a camera, on the same stack
engine, axes, legend, tooltip, selection, animation and accessibility model as
the flat [bar chart](#bar-chart).

```kotlin
data class Sale(val month: String, val total: Double)

ColumnChart3D(
    data = sales,
    category = { it.month },
    value = { it.total },
)
```

### What the third dimension is for

Depth carries **grouping**, not a quantity. There is no z axis to read a number
off, and there is deliberately no way to ask for one: a numeric depth axis under
perspective would encode values in the one direction the reader cannot measure.
Height is the measurement; depth says which pile a segment belongs to.

That distinction has a name in the API and it is worth keeping straight:

| | Same footprint? | Accumulates in y? |
|---|---|---|
| **Stacking** (`grouping`) | yes | yes |
| **Grouping** (`stack`) | no | no |
| **Depth** (`arrangement`) | no, in z | no |

### Grouped and stacked

`grouping` says whether the members of one stack are piled. `stack` says which
pile a series belongs to. Two piles are two footprints whether or not either is
stacked, which is what makes "grouped and stacked" one configuration rather than
a special case:

```kotlin
ColumnChart3D(
    series = listOf(
        ChartSeries("john", "John", john),
        ChartSeries("jane", "Jane", jane),
        ChartSeries("joe", "Joe", joe),
        ChartSeries("janet", "Janet", janet),
    ),
    category = { it.fruit },
    value = { it.count },
    grouping = BarGrouping.Stacked,
    // Two piles per category, side by side, each a stack of two people.
    stack = { series -> if (series.id in setOf("john", "joe")) "first" else "second" },
    valueAxis = ChartAxis(title = "Picked"),
    categoryAxis = ChartAxis(title = "Fruit"),
    legendTogglesSeries = true,
)
```

With `grouping = BarGrouping.Grouped` and no `stack` at all, every series is its
own pile and the chart is an ordinary grouped one. With one `stack` id shared by
every series, it is a single pile per category. `BarGrouping.StackedPercent`
normalises **each pile to its own total**, so two stacks compare shares rather
than counts.

### Side by side, or one behind the other

```kotlin
ColumnChart3D(..., arrangement = Column3DArrangement.Depth)
```

`Side` (the default) puts the piles across the category band at one depth, so
every column is the same distance from the reader and heights stay directly
comparable. `Depth` gives each pile its own row going back. `Depth` reads well
for two or three piles over few categories and badly beyond that — a column in
the back row is both smaller under perspective and partly hidden.

### Depth

```kotlin
ColumnChart3D(..., depth = Chart3DDepth.Relative(0.6))
```

`Auto` derives the depth from the column's own footprint, which is what keeps
the depth cue — the *ratio* of depth to width — the same on a phone and on a
tablet. `Relative(1.0)` gives a square footprint. `Absolute(pixels)` is there for
a chart that has to match another exactly.

### Camera

```kotlin
val camera = rememberChart3DCameraState(
    rotationX = 15.0,   // pitch: lifts the reader, revealing the tops
    rotationY = 20.0,   // yaw: brings the right-hand side forward
    distance = 3.2,     // in scene widths; smaller is a stronger perspective
)

ColumnChart3D(data = sales, category = { it.month }, value = { it.total }, cameraState = camera)

TextButton(onClick = { camera.reset() }) { Text("Reset view") }
```

The state is hoisted for the same reason the viewport is: the view is a property
of the reader's session, not of the data. `rotateBy`, `zoomBy`, `reset` and a
suspending `animateTo` are all on it, and `rememberSaveableChart3DCameraState`
keeps the view across a configuration change.

Presets: `Chart3DCamera.Default`, `Front`, `Isometric`, `Presentation`.

Both angles default to something, and neither defaults to zero — a chart at
`0, 0` is a bar chart with its columns hidden behind each other.
`Chart3DCameraLimits` keeps interactive rotation the right way up; loosen it
deliberately with `Chart3DCameraLimits.None`.

If you are coming from a Highcharts 3D chart, the mapping is: `alpha` →
`rotationX`, `beta` → `rotationY`, `depth` → the layer's `depth`, `viewDistance`
→ `distance`.

### Projection

```kotlin
ColumnChart3D(..., projection = Chart3DProjection.Orthographic)
```

`Perspective` (the default) divides by depth: things further away are smaller.
That is the depth cue, and its cost is that two equal values at different depths
are drawn at different heights.

`Orthographic` is parallel: depth changes position but never size, so two equal
values are drawn identically wherever they stand. **Prefer it whenever depth is
grouping rather than decoration** — which, on a column chart, it always is. The
cost is a flatter picture, and that two columns exactly in line can coincide;
depth ordering still resolves which is in front.

### Frame

```kotlin
ColumnChart3D(
    ...,
    frame = Chart3DFrame(
        floor = true,
        back = true,
        side = Chart3DSideWall.Auto,
        grid = Chart3DFrameGrid.Back,
    ),
)
```

`Chart3DFrame.Auto` is the default: a floor, a back wall, the far side wall, and
the value grid projected onto the back. `Chart3DFrame.None` turns it all off;
`Chart3DFrame.Visible` draws it more strongly and adds the floor's depth runs.

`Chart3DSideWall.Auto` picks whichever wall ends up *behind* the data, from the
camera's yaw. Drawing both would put one between the reader and the columns.

The grid lines sit at the chart's own value-axis ticks, so a line on the back
wall is at the same value as the number written beside it.

### Lighting

```kotlin
ColumnChart3D(
    ...,
    lighting = Chart3DLighting(ambient = 0.45, diffuse = 0.55, direction = Vector3D(0.7, -0.6, 0.4)),
)
```

One directional light, an ambient term and a diffuse term — enough to tell a
column's three visible faces apart, which is all shading has to do here. The
light is fixed to the *camera*, so rotating the chart turns the geometry under a
steady light rather than swinging the light across it.

Face colours are derived from the series colour rather than configured. Six
colours per series would make the palette six times as large and would let a
caller choose a set of faces no light source could produce, at which point the
shading stops reading as a solid and the depth cue is gone.
`Chart3DLighting.Flat` turns shading off entirely.

### Interaction

```kotlin
ColumnChart3D(..., interaction = Chart3DInteraction.RotateAndSelect)
```

`Select` (the default) resolves a tap to a column. `Rotate` turns the camera on
a drag and moves it on a pinch. `RotateAndSelect` does both; `None` makes the
chart a picture.

Rotation is **off by default**, and that is an analytical position rather than
caution about the implementation: a chart the reader can turn is a chart at an
angle nobody chose, so two readers of the same dashboard see two different
pictures — and, under perspective, two different apparent heights.

Selection is geometric. The projected faces are already computed for drawing;
a tap is resolved by testing them front to back, so an overlapped column can
never win a tap on the one in front of it. Selecting any visible face selects
the whole segment, and every visible face of it is emphasised.

Arrow keys still step through the categories, exactly as on a 2D bar chart. The
camera does not take them: a reader navigating with a keyboard is reading values,
and losing that to a rotation control would trade an accessible interaction for a
decorative one.

### Value labels

```kotlin
ColumnChart3D(..., valueLabels = Column3DLabelPlacement.Auto)
```

`Top`, `Inside`, `Auto` or `None` (the default). `Auto` writes the number inside
the segment where it fits and above the column where it does not — and nothing at
all when neither works, because a label over the wrong segment is worse than no
label. Labels inside a column are written in black or white against the shaded
fill's own luminance; the theme's label colour knows nothing about the series
colour it would land on.

Labels that would collide are dropped, nearest first, by the same rule the flat
[value labels](#value-labels) use — nothing is shrunk, rotated or ellipsised,
because a chart of half-readable numbers is worse than a chart of fewer whole
ones.

### The low-level API

```kotlin
@OptIn(ExperimentalChartKitApi::class)
CartesianChart3D(
    cameraState = camera,
    projection = Chart3DProjection.Orthographic,
    valueAxis = ChartAxis(title = "Picked"),
) {
    columns(
        series = harvest,
        category = { it.fruit },
        value = { it.count },
        grouping = BarGrouping.Stacked,
        stack = { if (it.id in setOf("john", "joe")) "first" else "second" },
    )
}
```

`ColumnChart3D` is this with one layer and a shorter parameter list. Both reach
the same engine, so the legend, tooltip, selection model, animation clock and
accessibility summary are not merely similar between them — they are the same
code.

`CartesianChart3D` and its scope carry `@ExperimentalChartKitApi`, exactly as
`CartesianChart` does and for the same reason: a layer grammar is where a real
multi-layer 3D scene will want room to move. `ColumnChart3D` does not, and is the
one to reach for unless you need the DSL. The scene plumbing in
`io.devkit.chartkit.three` — the projector, the scene, the faces, the hit tester
— is public because a future 3D chart type is built on it, and it may change
shape before 1.0; the configuration types you actually pass to a chart
(`Chart3DCamera`, `Chart3DProjection`, `Chart3DLighting`, `Chart3DFrame`,
`Chart3DDepth`, `Chart3DQuality`, `Column3DArrangement`) are the stable surface.

### Accessibility

A 3D chart announces the same things its 2D counterpart does: the category, the
series, the value and — on a stacked chart — the stack total. It never announces
a face, a depth, a camera angle or a projection, because none of those is data.

```
Sales chart. January. Product A: 42. Product B: 31. Stack total: 73.
```

Perspective makes precise magnitude comparison harder than a flat bar chart does.
That is worth knowing and it is not worth a warning on every chart, so it is
documented here instead. Two things follow from it: prefer
`Chart3DProjection.Orthographic` when the comparison matters, and offer the
numbers as a table.

```kotlin
ChartDataTableView(
    table = columns3DDataTable(
        series = harvest,
        category = { it.fruit },
        value = { it.count },
        stack = { if (it.id in setOf("john", "joe")) "first" else "second" },
    ),
)
```

Rows are category, series, stack and value — never projected coordinates.

### Architecture

3D is not a second chart engine. It is a scene system that the existing engine
feeds:

```
chart data
  → existing stack / group layout      (BarStacking, unchanged)
  → 3D world geometry                  (Cuboid3D, per segment)
  → camera transform                   (Matrix4)
  → projection                         (perspective or parallel)
  → back-face culling                  (normals, in camera space)
  → depth sorting                      (camera-space centroid, far to near)
  → lighting                           (per face)
  → ChartKit's Canvas renderer
  → shared interaction, theme, animation, accessibility
```

Everything in `io.devkit.chartkit.three` — `Point3D`, `Vector3D`, `Matrix4`,
`Bounds3D`, `Cuboid3D`, `Face3D`, the camera, both projections, the projector,
the culling, the sort, the lighting and the hit test — is plain Kotlin with no
Android or Compose types, and is tested on the JVM.

That claim has since been tested rather than asserted: the
[3D pie and donut](#3d-pie-and-donut) are a second shape in the *same* scene,
and adding them needed one new abstraction in the core — `Chart3DGeometry`, so a
scene can hold something that is not a box — and no radial copy of the camera,
the projection, the culling, the sort, the lighting or the hit test.

### Performance and limitations

- **Painter's algorithm.** Faces are sorted back to front by camera-space
  centroid depth. That is exact for non-intersecting boxes, which is what a
  column chart is, and it is not a general solution: two polygons that
  interpenetrate cannot be ordered by a single depth per face. ChartKit's own 3D
  geometry never produces that case.
- **Perspective distortion.** Under `Perspective`, a far column of the same value
  is drawn shorter. Use `Orthographic` when the comparison matters more than the
  arrangement.
- **Extreme angles.** At an edge-on view faces collapse to near-zero area and are
  dropped rather than painted as hairlines. Nothing produces `NaN`, but the chart
  stops being readable well before it stops being drawn — which is why the default
  camera limits stop short of it.
- **Dataset size.** 3D is not the right chart for a large dataset. The sample's
  twenty-category, six-series demo exists so that the cost is visible rather than
  described. Sorting is `O(f log f)` in the visible faces, culling leaves at most
  three faces of any box to draw, and a projection is reused across everything
  but a camera change — but the honest guidance is that a 3D column chart is for
  a handful of categories.

Measured, on a JVM, warm, over three runs of `Chart3DPerformanceTest`
(`measure the projection pass`) on an Apple-silicon laptop — the camera
transform, culling, sort and lighting for every face, which is the part that runs
again on every frame of a camera drag:

| Columns | Faces drawn | Per projection pass |
|---|---|---|
| 50 | 150 | 22–80 µs |
| 120 | 360 | 62–152 µs |
| 500 | 1,500 | 226–237 µs |

The spread at the small sizes is the machine, not the chart: a pass that takes
tens of microseconds is close enough to the noise floor of a laptop under load
that three runs disagree by more than the work does.

Those are figures for the projection stage on that machine and nothing else. They
are not frame times, they are not measured on a device, and they say nothing
about what Compose then costs to draw the paths. Run the test to reproduce them
on yours; no other performance claim is made here.

## 3D pie and donut

Extruded radial slices projected through a camera, on the same slice engine,
legend, tooltip, selection, animation and accessibility model as the flat
[pie chart](#pie-chart) — and on the same scene, camera, projection, lighting,
depth sorting and hit testing as the [3D columns](#3d-columns).

```kotlin
data class Share(val browser: String, val users: Double)

PieChart3D(
    data = shares,
    value = { it.users },
    label = { it.browser },
    modifier = Modifier.fillMaxWidth().height(320.dp),
)
```

No conversion step and no slice type: `data` is a `List<Share>` and stays one,
exactly as for a flat pie. Values are normalised, the first slice starts at
twelve o'clock and the chart runs clockwise, because this **is** `PieChart`'s
arithmetic — the same `computePolarSlices` call, with the same arguments — and
then extruded.

### What the third dimension costs

Under perspective a slice at the front is drawn larger than a slice of the same
share at the back. That is what perspective is, and it is the price of the depth
cue.

Where comparing shares precisely is the point of the chart, either use the flat
[`PieChart`](#pie-chart) or keep the extrusion and drop the distortion:

```kotlin
PieChart3D(
    data = shares,
    value = { it.users },
    label = { it.browser },
    projection = Chart3DProjection.Orthographic,
)
```

Nothing else moves with the camera. The values, the percentages, the tooltip, the
legend and everything a screen reader hears are computed from the data and never
measured from the picture, so they are identical at every angle.

### Donut

A donut is a pie with a hole, and the hole is what gives every slice an inner
wall:

```kotlin
DonutChart3D(
    data = shares,
    value = { it.users },
    label = { it.browser },
    innerRadiusRatio = 0.55f,
)
```

`innerRadiusRatio` is a fraction of the outer radius — the same units
[`DonutChart`](#donut-chart) uses, so a 2D and a 3D donut configured alike have
the same hole.

### Depth

How far the slices are extruded, as a fraction of the outer radius:

```kotlin
PieChart3D(
    data = shares,
    value = { it.users },
    label = { it.browser },
    depth = Chart3DDepth.Relative(0.35),
)
```

`Chart3DDepth.Auto` is a quarter of the radius. Relative rather than absolute so
one setting works at every chart size — the cue a reader uses is the *ratio* of
depth to radius, not either alone. `Chart3DDepth.Absolute(46.dp.toPx())` is
there for a chart that has to match another exactly, and is converted through
the radius it will actually be drawn at.

### Camera

The same `Chart3DCameraState` the 3D columns use, and it can be shared between
charts:

```kotlin
val camera = rememberChart3DCameraState(
    camera = Chart3DCamera.Radial,
    limits = Chart3DCameraLimits.Radial,
)

PieChart3D(data = shares, value = { it.users }, label = { it.browser }, cameraState = camera)
DonutChart3D(data = shares, value = { it.users }, label = { it.browser }, cameraState = camera)

TextButton(onClick = { camera.reset() }) { Text("Reset view") }
```

`Chart3DCamera.Radial` is the default: 45° of pitch and no yaw.

The disc lies **flat** — on the same floor a 3D column stands on — so pitch here
means what it means for a table. `0` is edge-on and useless; `90` looks straight
down and is a 2D pie drawn the expensive way; around 45° squashes the circle to
about seven tenths of its width and puts the near rim clearly below the surface.
That is why a radial chart wants far more pitch than a column chart, and why
`Chart3DCameraLimits.Radial` stops at 12° and 85° rather than at zero.

Yaw spins the plate about its own axis rather than tipping it. Harmless, but it
moves the twelve o'clock start, so it is zero by default and limited to ±30°.

### Exploded slices

Tapping a slice slides it out along its own mid-angle, and it stays out until
another is chosen:

```kotlin
PieChart3D(
    data = shares,
    value = { it.users },
    label = { it.browser },
    explodeSelected = true,               // the default
    explodeDistance = 18.dp,
)
```

Displacement rather than a colour change, because it survives being printed,
screenshotted, or read by somebody who cannot separate two colours.

Slices can also be displaced permanently, and several at once:

```kotlin
PieChart3D(
    data = shares,
    value = { it.users },
    label = { it.browser },
    explode = { it.browser == "Safari" || it.browser == "Edge" },
)
```

An exploded slice is *moved geometry*, not a drawing offset: the scene fit
accounts for it, and hit testing finds the slice where it now is rather than
where it started.

### Labels

```kotlin
PieChart3D(
    data = shares,
    value = { it.users },
    label = { it.browser },
    labelPosition = SliceLabelPosition.Outside,
    labelContent = SliceLabelContent.LabelAndPercentage,
)
```

Every anchor comes from a point on the slice's own front cap or rim, pushed
through the same projector the faces went through, so a label cannot drift from
what it names however the chart is turned. The *text* is then drawn upright:
skewing it onto the cap's plane would be more visually consistent and materially
less readable.

`SliceLabelPosition.Auto` puts a label inside the slice when the slice's
projected cap can hold it and outside with a leader line when it cannot — decided
from the *projected* extent, which is exactly right under perspective, where a
far slice is smaller than a near one of the same share. A label that would then
collide with one already placed is dropped rather than overlapped, by the same
[`LabelPlacer`](#value-labels) the bar chart's value labels use.

### Centre content

```kotlin
DonutChart3D(
    data = spend,
    value = { it.amount },
    label = { it.category },
    innerRadiusRatio = 0.58f,
    centerContent = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Total", style = MaterialTheme.typography.labelSmall)
            Text("1.1M", style = MaterialTheme.typography.titleMedium)
        }
    },
)
```

Ordinary Compose content, laid out inside the **projected** hole and never
tilted, skewed or projected onto the ring's plane. A total drawn in perspective
is a total that is harder to read, and the reason to put one in a donut is that
it is easy to read. What the camera changes is where the box goes and how big it
is. It takes no pointer input, so the hole stays inert and no slice loses a tap.

Measured from the front cap's inner rim — the nearest edge of the hole, and so
the conservative one — at a little under the inscribed square, so a long total
leaves a margin rather than touching the arc.

### Partial pies and donuts

`startAngle` and `sweepAngle` work exactly as they do on a flat pie, and the same
primitive draws all four:

```kotlin
PieChart3D(
    data = platforms,
    value = { it.users },
    label = { it.name },
    startAngle = 270f,
    sweepAngle = 180f,      // a semicircle
)
```

A partial chart's end faces are its slices' own radial walls, which the reader
then sees — so the extrusion reads as a solid rather than as a shadow.

### Quality

Curved surfaces are approximated by flat quads, and how many is derived rather
than configured:

```kotlin
PieChart3D(
    data = shares,
    value = { it.users },
    label = { it.browser },
    quality = Chart3DQuality.Auto,     // the default
)
```

`Auto` states a *tolerance* — half a pixel between a chord and the arc it
replaces — and works back to a segment count from the radius the chart is
actually drawn at. A small chart is therefore cut more coarsely than a large one
without anybody configuring it, which a fixed count cannot do. `Low`, `Medium`
and `High` are the same rule at 2px, 1.5px and 0.2px.

The count comes from each slice's *settled* sweep, not the one being animated, so
an arriving chart keeps a stable topology instead of re-cutting every arc on
every frame.

### Interaction

```kotlin
PieChart3D(
    data = shares,
    value = { it.users },
    label = { it.browser },
    interaction = Chart3DInteraction.RotateAndSelect,
)
```

Rotation is off by default, on the same reasoning as the 3D columns: a chart the
reader can turn is a chart at an angle nobody chose, and under perspective two
readers then see two different pictures of the same shares.

Hit testing is geometric and front-most-first, over the same depth-ordered faces
that were drawn. A tap on a cap, on the rim, on the wall of a donut's hole or on
a radial edge all select the same slice, because they are all the same slice; and
a slice hidden behind another cannot take a tap on the visible one.

### Accessibility

The semantics come from the data, so no camera angle can change them:

```
Browser share. 6 data points.
Chrome (42.1%): 4823, Safari (27.2%): 3112, Edge (16.1%): 1841, …
```

and a selected slice announces its label, its value and its share. There is no
mention of a camera, a projection, a face or a depth anywhere in it — a reader
who cannot see the picture is not helped by being told which surface faces them,
and a chart that announced its geometry would be describing a rendering choice
rather than a measurement.

For a table beside the chart:

```kotlin
ChartDataTableView(
    table = pieDataTable(
        data = shares,
        value = { it.users },
        label = { it.browser },
        categoryColumn = "Browser",
    ),
)
```

Category, value and **share** — the share is a column rather than a footnote,
because the proportion is what a part-to-whole chart communicates and a table of
raw numbers alone would drop it.

### The low-level API

The radial geometry is public and experimental. Nothing here is needed to draw a
pie; it is here so a future 3D radial chart is built on it rather than beside it:

```kotlin
val slice = RadialSector3D(
    innerRadius = 0.5,          // zero for a pie
    outerRadius = 1.0,
    startAngle = 0.0,           // twelve o'clock, clockwise
    sweepAngle = 90.0,
    baseY = -0.125,             // the disc is horizontal and extrudes upward
    topY = 0.125,
    segments = ArcTessellator3D.segmentsFor(90.0, radiusPx = 300.0),
)

slice.faces          // top and bottom, outer wall, inner wall, both radial walls
slice.fitPoints()    // its own rim, for fitting a scene to a disc
```

`Sector3D(...)` and `AnnularSector3D(...)` are named constructors over the same
type: a pie slice and a donut segment differ by one number, and two types would
have meant two copies of the cap tessellation, both walls and every winding
decision.

### Architecture

The pie is a shape in the **same scene** as the columns, and that is the point of
it:

```
chart data
  → existing slice engine              (computePolarSlices, unchanged)
  → radial 3D layout                   (RadialSector3D, per slice)
  → adaptive arc tessellation          (ArcTessellator3D)
  ↓  the disc lies in x–z and extrudes along +y — a plate on the floor
  → camera transform                   (Matrix4 — the column chart's)
  → projection                         (the column chart's, both modes)
  → back-face culling                  (the column chart's)
  → depth sorting                      (the column chart's, per face)
  → lighting                           (the column chart's, at a radial angle)
  → ChartKit's Canvas renderer
  → shared interaction, theme, animation, accessibility
```

There is no `Radial3DScene`, no `Radial3DCamera`, no radial projection, no radial
culler, no radial sorter and no radial hit test. Adding the pie needed one new
abstraction in the shared core — `Chart3DGeometry`, so a scene can hold something
that is not a box — and one new shape behind it.

#### The disc lies flat, and that is the whole orientation

A radial chart's disc is built in the **x–z plane** and extruded **upward** along
`y`, which is the same floor a 3D column stands on. Everything else follows from
that: the camera's existing meaning — positive pitch lifts the reader above the
scene — tips the plate toward them without a single sign flip, the near edge of
the rim appears *below* the surface where a solid disc's rim belongs, and looking
into a donut's hole shows the far half of its inner wall, exactly as a ring on a
table does.

The obvious alternative — standing the disc upright in the x–y plane and
extruding it away from the reader — produces a silhouette of almost identical
proportions and is wrong. The extrusion then runs away rather than down, so the
rim appears *above* the surface: the picture is the underside of the plate, seen
from below. It is worth stating because nothing about the ellipse gives it away;
`Radial3DPipelineTest` asserts which side the rim falls on for that reason.

Depth sorting happens **per face and not per slice**, and the caps are
tessellated as well as the walls partly for that reason: a 180° slice reaches
from the front of the chart to the back, and a single depth for the whole of it
would put its far half in front of a neighbour it actually passes behind. The
other reason is hit testing, which tests convex polygons — and a whole cap stops
being convex past a half turn.

Two caches sit behind a drawn frame. World geometry depends on the data, the
radius, the depth, the quality and the explode; the projection depends on all of
that *and* the camera. Turning the chart therefore reprojects and re-cuts no
arcs, and a tooltip appearing does neither.

### Performance and limitations

- **A pie is not a chart for many slices.** Twenty is drawn honestly and is in
  the sample so the cost is visible rather than described, but past a handful the
  slices stop being separable and the labels stop fitting. This is a limitation of
  pie charts, which 3D makes worse rather than better.
- **Perspective distortion.** Front slices are drawn larger than back slices of
  the same share. `Orthographic` keeps the extrusion and removes it.
- **Painter's algorithm.** Faces are sorted back to front by camera-space
  centroid depth. Exact for non-intersecting solids, which is what a ring of
  sectors is; it is not a general solution, and two interpenetrating polygons
  cannot be ordered by one depth each. ChartKit's own radial geometry never
  produces that case, and an explode moves slices apart rather than through each
  other.
- **Very thick extrusions.** Past about half the radius the rim starts to hide the
  caps of the far slices, and the chart becomes a cylinder with a pattern on the
  end.
- **Extreme camera angles.** Near edge-on the surfaces collapse to slivers and
  are dropped rather than painted as hairlines. Nothing produces `NaN`, but the
  chart stops being readable well before it stops being drawn — which is why
  `Chart3DCameraLimits.Radial` stops at 12°, and why it will not go below the
  floor at all: a plate seen from underneath is the same disc mirrored, with no
  cue that you are looking at the wrong side.
- **Labels are dropped, never shrunk.** On a crowded chart what survives is
  legible and the rest is left to the legend.

Measured, on a JVM, warm, over three runs of `Chart3DPerformanceTest` on an
Apple-silicon laptop. This is the projection pass — camera transform, culling,
sort and lighting for every face — which is the part that runs again on every
frame of a camera drag:

| Slices | Radius | Faces built | Faces drawn | Per projection pass |
|---|---|---|---|---|
| 5 | 300px | 246 | 121 | 34–36 µs |
| 10 | 300px | 256 | 126 | 34–35 µs |
| 20 | 300px | 292 | 144 | 35–42 µs |
| 20 | 700px | 412 | 203 | 36–68 µs |

And the world build — the tessellation itself, which a camera move skips
entirely:

| Slices | Per world build |
|---|---|
| 5 | 35–66 µs |
| 10 | 37–51 µs |
| 20 | 35–43 µs |

There is no clear trend across those three, and that is the honest reading: at
tens of microseconds the run-to-run spread on a laptop is larger than the
difference four times the slices makes.

Those are figures for those two stages on that machine and nothing else. They are
not frame times, they are not measured on a device, and they say nothing about
what Compose then costs to draw the paths. Run
`./gradlew :chartkit:testDebugUnitTest --tests '*Chart3DPerformanceTest*' -i` to
reproduce them on yours; no other performance claim is made here.

## Grid lines

```kotlin
LineChart(..., grid = ChartGrid.Horizontal)   // None, Horizontal, Vertical, Both
```

`ChartGrid` names directions **on screen**. Grid lines are drawn at the axis
ticks that were actually labelled — a grid whose lines land between the labelled
values invites the reader to measure against nothing.

A horizontal bar chart defaults to `ChartGrid.Vertical`, because that is where
its value axis runs.

## Formatting

```kotlin
BarChart(
    data = revenue,
    category = { it.month },
    value = { it.amount },
    valueFormatter = ChartNumberFormatters.compact(),   // 24K, 1.2M
)
```

| Formatter | Output |
| --- | --- |
| `ChartNumberFormatters.integer()` | `1,235` |
| `ChartNumberFormatters.decimal(2)` | `1,234.50` |
| `ChartNumberFormatters.compact()` | `1.2K`, `2.4M`, `3.1B` |
| `ChartNumberFormatters.percent(1)` | `42.5%` from `42.5` |
| `ChartNumberFormatters.fraction(0)` | `43%` from `0.4256` |
| `ChartNumberFormatters.currency("KES")` | Locale-formatted, with that currency |
| `ChartDateFormatters.pattern("d MMM")` | `14 Nov` |
| `ChartDateFormatters.shortDate()` | The locale's own short form |

Or a lambda:

```kotlin
valueFormatter = ChartValueFormatter { "%.1f kg".format(it) }
```

**Locale.** Every built-in takes a `Locale`, defaulting to the device's, and the
axis formatter is derived inside composition from the composition's own locale.
Nothing assumes US grouping, USD or `MM/DD/YYYY`. `currency` has no default
code — assuming USD is how a library labels Kenyan shillings with a dollar sign.

The one documented gap: `compact()`'s `K`/`M`/`B` suffixes are ASCII and not
localised. Doing it properly needs `CompactDecimalFormat`, whose output varies
with the device's ICU version; 0.1 states the limit rather than shipping
something that changes shape between devices.

Axis label precision is derived from the **tick values**, so an axis reads
`0.0 0.5 1.0` and never `0 0.5 1`.

## Legends

```kotlin
LineChart(..., legend = LegendPosition.Bottom, legendTogglesSeries = true)
```

`None`, `Top`, `Bottom`, `Start`, `End`. A long legend wraps; one taller than
its slot scrolls inside itself — twelve series on a phone in portrait will
otherwise push the plot out of the layout.

With `legendTogglesSeries = true` each entry becomes toggleable and announces
its state to a screen reader. A hidden series keeps its colour, dimmed, so you
can see what you turned off.

## Value labels

```kotlin
BarChart(..., valueLabels = true)
```

Off by default: excellent on a six-bar chart, unreadable on a sixty-bar one.
Labels whose measured box would overlap one already placed are **skipped**;
nothing is shrunk or ellipsised, so what survives is always legible.

---

## Interaction modes

Every gesture ChartKit recognises is arbitrated by **one** pointer handler, and
configured by one object:

```kotlin
LineChart(..., interaction = ChartInteraction.Explorable)
```

| Preset | Behaviour |
| --- | --- |
| `ChartInteraction.Default` | Tap selects, drag scrubs. Lines and areas |
| `ChartInteraction.TapOnly` | Tap selects, drag does nothing. Bars |
| `ChartInteraction.Explorable` | Pinch zooms, drag pans once zoomed, tap selects |
| `ChartInteraction.RangeSelect` | Drag selects a domain range, pinch still zooms |
| `ChartInteraction.None` | No pointer input at all |

Or state it directly:

```kotlin
ChartInteraction(
    tapSelects = true,
    dragMode = ChartDragMode.PanWhenZoomed,
    zoomEnabled = true,
)
```

### Why a drag mode and not three booleans

`ChartDragMode` is an enum because one finger moving across the plot cannot
simultaneously scrub, pan and drag out a range. Three booleans would let a
caller ask for all three and get whichever the implementation happened to check
first. Everything that *can* compose still does: tapping always selects, a pinch
always zooms when zoom is on, and the crosshair follows whatever the drag is
doing.

| `ChartDragMode` | A single-finger drag… |
| --- | --- |
| `None` | does nothing; the parent keeps its scroll |
| `Scrub` | moves the selection to the nearest point |
| `Pan` | moves the viewport |
| `Range` | drags out an interval of the domain |
| `PanWhenZoomed` | pans while zoomed in, scrubs at full extent |

### Gesture priority

```text
two fingers             → zoom, and pan from the centroid
one finger past slop    → whatever dragMode says
released without moving → tap
```

A second finger wins outright: a scrub continuing underneath a pinch would move
the selection while the reader was zooming. When the second finger lifts, a
chart that can pan keeps panning with the remaining one rather than switching to
scrubbing mid-gesture.

**Slop is measured along the domain axis only** — horizontally for a vertical
chart. A vertical drag therefore never passes slop, is never consumed, and the
surrounding scrollable keeps it. That is what lets a chart live in a scrolling
screen without trapping the finger.

## Selection

```kotlin
BarChart(
    data = revenue,
    category = { it.month },
    value = { it.amount },
    onSelectionChanged = { selection ->
        // selection?.item is your own Revenue
    },
)
```

`ChartSelection<T>` carries `seriesId`, `seriesName`, `seriesIndex`,
`pointIndex`, `x`, `y`, `position` and — the useful part — `item`, your original
object. A custom tooltip reads `selection.item.customerName` directly instead of
indexing back into the source list and hoping the two still line up.

Hit testing differs by chart, because the marks do:

- **Bars** — rectangle test first, then nearest-in-band. A bar is a real target
  and you aimed at it.
- **Lines and areas** — nearest along the domain axis, weighted so a horizontal
  drag follows your finger even when it is nowhere near the line vertically.
  Binary search on sorted data: 14 comparisons over 10,000 points, not 10,000.

`selectionMode` is `Tap` for bars and `TapAndScrub` for lines and areas.
`ChartSelectionMode.None` disables pointer input entirely.

## Scrubbing

Drag across a line or area chart and the selection follows the nearest x, with a
dashed guide line through it.

Dragging is detected on the **domain axis only** — horizontally for a vertical
chart, vertically for a horizontal one — so a chart inside a vertically
scrolling screen does not steal the scroll.

## Crosshair

```kotlin
LineChart(
    series = listOf(revenueSeries, expensesSeries, forecastSeries),
    x = { it.month },
    y = { it.amount },
    crosshair = CrosshairConfig.Vertical,
)
```

| Preset | Draws |
| --- | --- |
| `CrosshairConfig.None` | nothing |
| `CrosshairConfig.Vertical` | a vertical guide plus axis readouts |
| `CrosshairConfig.Both` | vertical and horizontal guides |

Or state it: `CrosshairConfig(enabled = true, vertical = true, horizontal =
false, showAxisLabels = true)`.

The crosshair is a **layer on the coordinate system**, not a feature of
`LineChart`. It works on lines, areas, bars and combined charts, and reuses the
same nearest-point search as scrubbing — there is no second gesture recogniser
and no second definition of "the selected x".

Its guides are expressed in domain and value terms, so on a horizontal bar chart
they run the correct way round without a second code path. The axis readouts are
small chips drawn in the gutter, formatted by the chart's **own axis
formatter**, so a chip never writes a date differently from the axis beneath it.

The guide drawn through a plain scrub selection is the same layer with its chips
turned off — `ChartDefaults.SelectionGuide`, which is what every chart uses by
default.

## Zoom and pan

```kotlin
val viewport = rememberChartViewportState()

LineChart(
    data = readings,
    x = { it.at },
    y = { it.value },
    interaction = ChartInteraction.Explorable,
    viewportState = viewport,
)

Button(onClick = { viewport.reset() }) { Text("Reset zoom") }
```

Pinch to zoom, drag to pan once zoomed. A chart that does not opt in never needs
a viewport state: the default interaction does not zoom, and charts create one
internally when none is supplied.

### Zoom changes the domain, not the canvas

ChartKit does **not** zoom with `Canvas.scale()`. A canvas transform is one line
and wrong in five places: the axis labels scale into unreadable sizes, the tick
values stop being round, stroke widths grow with the zoom, hit testing lands on
the wrong point, and the tooltip anchors where the data no longer is.

Instead the **viewport narrows the domain the scales map**, and everything
downstream is recomputed from it. Zoom into an hour of a year-long series and
the axis relabels itself in minutes; tap a point and you select the point you
tapped; drag out a range and it is reported in dates.

### The viewport

`ChartViewport` is a window over the full domain, expressed as `[start, end]` in
`0..1`. One type therefore serves a numeric axis, a time axis and a category
axis — "the middle third of the bands" is meaningful on all three — while the
state republishes it in logical values:

```kotlin
viewport.zoom                 // 12.5
viewport.visibleDomain        // NumericDomain(…) — epoch millis, or numbers
viewport.visibleCategoryRange // 3..7, on a banded axis
viewport.isFullyZoomedOut
```

### Focal-point zoom

A pinch keeps the value under the fingers where it is. Put two fingers on March
and March stays under them; zooming about the centre instead makes the data
slide away from the gesture. The invariant is asserted directly by a test.

### Limits and clamping

```kotlin
rememberChartViewportState(limits = ChartZoomLimits(maxZoom = 20.0))
```

The minimum zoom is fixed at the full domain: a chart zoomed out past its own
data shows blank space a reader cannot distinguish from missing data. Panning is
clamped the same way — there is no overscroll, because there is nothing out
there to come back for.

### Moving the viewport programmatically

```kotlin
viewport.viewport = ChartViewport.trailing(0.08)   // immediate
scope.launch { viewport.animateToFull() }          // eased
scope.launch { viewport.animateTo(ChartViewport.between(range.startFraction, range.endFraction)) }
```

Programmatic moves animate; **gesture-driven zoom and pan deliberately do not**,
because a viewport easing towards its target lags behind the fingers driving it.

`rememberSaveableChartViewportState()` survives configuration changes and
process death — a reader who rotated the device while zoomed into March would be
surprised to find themselves back at the whole year.

## Range selection

```kotlin
LineChart(
    ...,
    interaction = ChartInteraction(dragMode = ChartDragMode.Range),
    onRangeSelectionChanged = { range ->
        if (range?.phase == ChartRangeSelectionPhase.Completed) {
            filterTo(range.start, range.end)
        }
    },
)
```

Drag across the plot and the selected interval is drawn as a translucent band
with marked edges — the edges are not decoration: a region distinguished only by
a tint disappears for a reader with low contrast sensitivity.

`ChartRangeSelection` is stated in **logical domain values**, not pixels:

```kotlin
range.start          // ChartX.Time(…) — 23 Sep
range.end            // ChartX.Time(…) — 25 Oct
range.items          // the caller's own objects inside the range
range.startFraction  // 0.34 — what a viewport can be built from
range.phase          // InProgress · Completed · Cleared
```

The phase matters: a range still being dragged is a preview worth showing, and a
completed one is a decision worth acting on. Collapsing both into one callback
makes every drag frame look like a committed choice.

Because the range is held in full-domain fractions, it survives a zoom — the
band stays over the same dates — and "zoom to the selection" is one line:

```kotlin
viewport.animateTo(ChartViewport.between(range.startFraction, range.endFraction))
```

Interactive resize handles are not implemented; a range is dragged out afresh.
The edges are drawn as handles so that adding them later changes no geometry.

## Tooltips

Every chart's tooltip slot receives the same `ChartTooltipData`, whatever
produced the selection — a tapped bar, a scrubbed line, a crosshair over four
series, a pie slice:

```kotlin
LineChart(
    ...,
    tooltip = { data ->
        Card {
            Column(Modifier.padding(10.dp)) {
                Text(data.xLabel, fontWeight = FontWeight.SemiBold)
                data.entries.forEach { entry ->
                    Text("${entry.seriesName}: ${money.format(entry.value)}")
                }
            }
        }
    },
)
```

`ChartTooltipData` carries:

| Field | |
| --- | --- |
| `selection` | the nearest series — its `item` is your own object |
| `entries` | one per reported series: name, value, palette slot, and your item |
| `anchor` | where the tooltip should point, in pixels |
| `xLabel` | the domain value, already formatted by the chart's axis formatter |
| `valueFormatter` | how the chart's own value axis writes numbers |
| `isMultiSeries` | whether more than one series is reported |

The default tooltip uses `valueFormatter` when the caller supplied none, so a
tooltip and the axis beside it never disagree about how a price is written — a
chart whose axis reads `250` and whose tooltip reads `229.0358655001` is showing
two different quantities as far as a reader is concerned.

Or wrap the default rather than rewriting it:

```kotlin
tooltip = { ChartDefaults.Tooltip(it, showSeriesNames = false) }
```

`tooltip = null` disables it and leaves the selection callback working.

### Multi-series tooltips

```kotlin
LineChart(series = threeSeries, ..., crosshair = CrosshairConfig.Vertical)
```

```text
Jan
● Revenue    30,000
● Expenses   21,000
● Profit      9,000
```

`sharedTooltip` turns one selection into every series' value at the same domain
position. It defaults to on when a **full** crosshair is configured — that is,
when `showAxisLabels` is true, which only the `Vertical` and `Both` presets set
— because a crosshair over four lines that reported one of them is half a
feature. The plain selection guide keeps single-point tooltips. Set it directly
to override either way.

Values are matched on the resolved domain value, not on the point index: two
series over the same months need not have the same number of points, and index
matching would report February's revenue against March's expenses.

### The overlay engine

Tooltips are positioned by **one** engine, shared by every chart. Chart-specific
code supplies an anchor and the content; none of it knows how an overlay is
measured, flipped or clamped. That is what stops six chart types growing six
subtly different placements, and what makes a custom Compose tooltip behave
exactly like the built-in one.

Placement is measured, not offset by a constant: preferred above the anchor,
flipped below when there is no room, and pulled back inside the plot — so it
never leaves the chart at the first or last point, which on a rising series is
the most interesting one. `ChartOverlayPlacement` also offers `Below`, `Start`
and `End` for content that should sit beside its anchor.

## Hoisted state

```kotlin
val state = rememberChartState<Revenue>()

LineChart(data = revenue, x = { it.month }, y = { it.amount }, state = state)

Text("Selected: ${state.selection?.item?.month ?: "none"}")
```

`ChartState` exposes `selection`, `pointerPosition`, `hiddenSeriesIds`,
`select`, `clearSelection`, `setSeriesVisible` and `toggleSeries`. Measured
bounds, cached geometry and scales stay inside the chart: they change every
frame during an animation, and publishing them would tie every consumer's
recomposition scope to the chart's layout.

`rememberSaveableChartState()` persists **series visibility** across
configuration changes and process death. Not the selection: it references your
own object, which ChartKit cannot serialise and has no business trying to, and a
pointer gesture is not state worth restoring three seconds after the process was
killed.

---

## Custom layers

A visualisation ChartKit does not have, drawn at your own call site, sharing the
chart's scales, viewport, theme and animation clock:

```kotlin
@OptIn(ExperimentalChartKitApi::class)
CartesianChart {
    customLayer(id = "target-band") {
        val top = positionOfValue(32_000.0)
        val bottom = positionOfValue(24_000.0)
        drawRect(
            color = colors.annotation.region,
            topLeft = Offset(plotArea.left, minOf(top, bottom)),
            size = Size(plotArea.width, abs(bottom - top)),
        )
    }
    line(series = listOf(revenue), x = { it.month }, y = { it.amount })
}
```

The layer sits in the render list **where it was declared** — before the data
draws it underneath, after draws it on top. It stays aligned with the data
through a zoom, which a mark computing its own positions could not do.

`CartesianLayerScope` is a `DrawScope` and a `CartesianLayerContext` at once, so
the body reads like ordinary Compose drawing with the chart's geometry in scope:

| Member | Answers |
| --- | --- |
| `plotArea` | where the data is drawn |
| `positionOfValue(v)` / `valueAt(px)` | the value axis, both ways |
| `positionOfDomain(any)` | a domain value, resolved through the chart's own `ChartXResolver` — `"Mar"` on a category axis, a timestamp on a time axis |
| `pointAt(domain, value)` | a screen position |
| `domainOf` / `valueOf` | the two components of a screen position |
| `orientation`, `viewport`, `reveal`, `renderMode` | the frame's own state |
| `colors`, `typography`, `dimensions`, `textMeasurer`, `px(dp)` | the theme |
| `selection` | what is selected, if anything |

Everything deliberately **absent** is the renderer's mutable innards — the layer
list, other layers' geometry, the gesture coordinator, the animation
`Animatable`. Exposing those would make every internal refactor a breaking
change, and would let a custom layer put the chart into a state it cannot leave.

### Only `draw` is required

Hit testing, accessibility and legend rows are separate optional lambdas rather
than interface members, so a two-line layer stays two lines:

```kotlin
customLayer(
    id = "sla",
    hitTest = { point ->
        val y = positionOfValue(200.0)
        if (abs(point.y - y) < 12f) CustomLayerHit("SLA", 200.0, point) else null
    },
    describe = { listOf(CustomLayerItem("SLA", 200.0, detail = "SLA: 200 ms")) },
    legendEntries = listOf(CustomLayerLegendEntry("sla", "SLA")),
) { /* draw */ }
```

`describe` takes no geometry on purpose: what a reader needs to hear is a fact
about the data, not about pixels, and a description that depended on the plot
size would change when the device rotated.

**Geometry preparation** belongs in your own `remember`, outside the DSL — it is
both simpler and correctly keyed on your own inputs.

### Custom polar layers

A parameter on the existing polar charts rather than a separate DSL, because
what you usually want is to add a mark to *your* donut, not to reassemble one
from parts:

```kotlin
DonutChart(
    data = usage, value = { it.value }, label = { it.label },
    customLayers = listOf(
        polarLayer("target-ring") {
            drawCircle(
                color = colors.annotation.line,
                radius = innerRadius + (outerRadius - innerRadius) * 0.72f,
                center = Offset(polarCenter.x, polarCenter.y),
                style = Stroke(width = px(1.dp)),
            )
        },
    ),
)
```

`PolarLayerContext` is deliberately **not** the Cartesian one with nullable
halves. A polar layer has no value axis and no domain axis; forcing it to answer
`positionOfValue` would mean either lying or returning null from half the
methods, and a caller could not tell which were meaningful.

### Overlay content

For anything that has to be *composed* rather than drawn — a card, an image, a
button, a badge with an avatar in it:

```kotlin
CartesianChart(
    overlay = {
        Card(Modifier.chartAnchor(domain = "Mar", value = 90_000.0)) {
            Text("Launch", Modifier.padding(6.dp))
        }
    },
) {
    line(series = listOf(revenue), x = { it.month }, y = { it.amount })
}
```

`chartAnchor` resolves through the chart's own scales and viewport, so the
content stays on the value it names through a zoom — and is **not placed at all**
when that value leaves the plot, rather than sliding along the edge claiming to
mark something off screen.

Annotations stay canvas-drawn and this exists beside them: rasterising a card
onto a canvas would lose its layout, its theming, its click target and its
semantics node.

## Large datasets

Four mechanisms, each with a stated threshold, none of them adaptive:

```text
source data
    ↓  cull         keep the viewport's window, plus overscan
    ↓  downsample    reduce to roughly what the plot's width can show
    ↓  markers off   past a point they are a band, not information
    ↓  animation off interpolating rebuilds the path every frame
    ↓  geometry      build paths for what survives
```

All four change what appears on screen, so all four are configured rather than
hidden, and all four can be turned off:

```kotlin
LineChart(
    data = readings,          // 100,000 of them
    x = { it.at },
    y = { it.value },
    performance = ChartPerformance.Default,
)
```

| Preset | |
| --- | --- |
| `ChartPerformance.Default` | Markers under 40 points, animation under 500, LTTB sampling once the data outruns the plot, culling when zoomed |
| `ChartPerformance.Exact` | Draw every point, cull nothing, sample nothing. For a scatter of forty measurements, a printed figure, or ruling sampling out as the cause of something |
| `ChartPerformance.Dense` | Markers off, animation off, min/max sampling to 2,000. For tens of thousands of points where spikes are the information |

```kotlin
ChartPerformance(
    pointMarkerThreshold = 40,
    maxAnimatedPoints = 500,
    downsampling = ChartDownsampling.Auto(pointsPerPixel = 2f),
    cullToViewport = true,
    overscanFraction = 0.15f,
)
```

**Selection, tooltips and accessibility work against the source data
throughout.** Sampling changes what is drawn; it does not change what exists. A
scrub across a sampled line binary-searches the *whole* series and reports the
original observation nearest the finger — the sample screen's readout says
`Reading 48704 of 100,000`, not an index into the sampled subset.

**Culling** applies when a viewport is active. Zooming into a week of a
five-year series leaves 99% of the points off screen, and building geometry for
them costs a path the renderer then clips away. Both edges keep one point beyond
the window plus the overscan, so a line enters *and leaves* the plot rather than
appearing to begin partway in.

Culling and sampling need an ordered domain. An unordered series has no window
to cut and no buckets to reduce, so it is drawn in full whatever the
configuration says.

## Downsampling

```kotlin
performance = ChartPerformance(downsampling = ChartDownsampling.Lttb(1_000))
```

| | |
| --- | --- |
| `ChartDownsampling.None` | Draw everything. Right when every observation must be individually present |
| `ChartDownsampling.Auto` | Sample only when the data is denser than the plot can show, at about two points per pixel of plot width. The default |
| `ChartDownsampling.MinMax(n)` | Keep each bucket's extremes. **Cannot lose a spike**, because a spike is by definition a bucket extreme. Visibly saw-toothed at low budgets, which is the price of that guarantee |
| `ChartDownsampling.Lttb(n)` | Largest-Triangle-Three-Buckets. Preserves the *shape* far better than picking every *n*-th point, and does not exaggerate noise into a band the way min/max does. Can in principle miss a single-sample spike |

Both are offered rather than one being declared the winner: which is right
depends on whether the envelope or the shape is the information.

A downsampler returns **indices into the original data**, never a new list of
points. The caller's list is never copied, reordered or mutated, and everything
downstream still refers to the original observation.

```kotlin
// The algorithms directly, for your own pipeline
LttbDownsampler.sample(x, y, targetCount = 1_000)   // IntArray of source indices
MinMaxDownsampler.sample(x, y, targetCount = 1_000)
VisibleRange.of(xs, from, to, overscan = 20)        // IndexRange, by binary search
```

## Streaming

```kotlin
val stream = rememberStreamingChartData(
    flow = sensor.readings,
    window = ChartWindow.Duration(60.seconds),
    timestamp = { it.atMillis },
)

LineChart(data = stream.items, x = { it.atMillis }, y = { it.value })
```

**Streaming is an addition, never a replacement.** `stream.items` is an ordinary
`List<T>` and the chart is an ordinary chart — nothing about `LineChart` knows a
stream exists, and swapping a live source for a static list is one line. A
streaming variant of every chart would have doubled the public surface and
halved the confidence that the two behave identically.

The adapter, not the chart, collects. A chart composable never becomes a
collector.

**Windows.** A realtime chart cannot keep everything: a sensor at 50 Hz produces
four million samples a day.

```kotlin
ChartWindow.Count(500)              // the most recent 500 values
ChartWindow.Duration(60.seconds)    // values newer than the newest minus a minute
ChartWindow.Unbounded               // a bounded stream — a replay, a finite job
```

A duration window measures back from the **newest value's own timestamp**, not
from the wall clock, so a stalled stream keeps showing its last minute rather
than emptying itself — and a replayed stream behaves identically to a live one,
which is also what makes it testable without sleeping. Timestamps are read from
the value through your own lambda and never inferred from arrival order: an
event that arrived late still happened when it happened.

**Backpressure.** A source emitting a thousand events a second must not cause a
thousand recompositions a second — a display refreshes at sixty hertz, so nine
hundred and forty of those are invisible work, and left unthrottled the chart
cannot be scrolled, tapped or zoomed because the main thread never has a frame
to spare.

| Policy | |
| --- | --- |
| `ChartUpdatePolicy.Immediate` | Redraw on every emission. Right for a slow stream, wrong for anything fast |
| `ChartUpdatePolicy.Throttle(16.milliseconds)` | At most one redraw per interval, showing the newest value. Intermediate readings are **discarded** — right for a measurement whose latest value supersedes the previous one. The default |
| `ChartUpdatePolicy.Batch(100.milliseconds)` | Collect and append **all** of them. Nothing is dropped, which is what a stream of discrete events needs |
| `ChartUpdatePolicy.Aggregate(100.milliseconds, ChartAggregation.MinMax)` | Append one or two values summarising the interval |

Under every policy but `Immediate` the collector keeps consuming at the
producer's full rate and only *publishes* on the interval. Suspending the
collector instead would apply backpressure to a sensor or a socket that has
nowhere to put it.

`ChartAggregation` offers `Latest`, `Average`, `MinMax` and `Custom`. The
generic adapter has no numeric view of `T`, so `Average` and `MinMax` pick real
samples — the middle one, and the first and last — rather than demanding a
numeric accessor from every stream including the ones that never aggregate. A
stream that needs true arithmetic supplies `Custom`, which is exact and typed:

```kotlin
ChartUpdatePolicy.Aggregate(
    interval = 100.milliseconds,
    aggregation = ChartAggregation.Custom<Tick> { batch ->
        listOf(Tick(batch.last().at, batch.sumOf { it.value }))
    },
)
```

**Controls.**

```kotlin
stream.pause()          // keeps what is on screen and drops what arrives
stream.resume()         // does not flush a backlog
stream.clear()
stream.jumpToLatest()
stream.latest
stream.receivedCount    // what arrived, against stream.items.size — what is drawn
```

Pausing does not buffer: a paused live chart that flushed a backlog on resume
would jump forward through data the reader never saw.

**Follow-latest.** Pass a viewport and a zoomed chart stays pinned to the newest
data:

```kotlin
val viewport = rememberChartViewportState()
val stream = rememberStreamingChartData(flow = ticks, viewport = viewport, ...)
```

Pan back into the history and following turns itself off — otherwise the next
sample drags the reader forward again and the history is unreadable.
`jumpToLatest()` turns it back on.

## Theming

Charts render correctly with no configuration: colours and type are derived from
the enclosing `MaterialTheme`, in light and dark, with dynamic colour if your app
uses it.

```kotlin
ChartKitTheme(
    colors = materialDerivedChartColors().copy(
        palette = listOf(Color(0xFF00695C), Color(0xFFEF6C00), Color(0xFF6A1B9A)),
    ),
    dimensions = ChartDimensions(lineWidth = 4.dp, pointRadius = 5.dp),
) {
    App()
}
```

### Precedence

```text
explicit chart parameter  →  ChartKitTheme  →  MaterialTheme-derived default
```

All three exist because each solves a different problem: the derived default
means a chart dropped into your app already matches it; the theme states your
design system once; the parameter means changing one line's width does not
require declaring a theme. Every `ChartKitTheme` parameter is optional and
inherits from the enclosing one, so a nested `ChartKitTheme(dimensions = …)`
keeps the colours.

### The series palette

Generated by rotating the hue of `MaterialTheme.colorScheme.primary`, with
saturation and lightness pinned to values that hold contrast — and different
values in dark mode, because a colour readable on white is not readable on
near-black. The offsets (0°, 180°, then the quarters, then the eighths) spread
the first few series as far apart as possible, so no two adjacent series share a
neighbouring hue.

A hardcoded blue/red/green palette would ignore your design system; picking
`primary, secondary, tertiary, error` out of the scheme yields four colours, one
of which is red for no reason a reader can interpret.

Colour is never the only channel: selection is shown by size, a ring and a guide
line as well, and legend entries carry text.

Per-series override, for a colour with meaning:

```kotlin
ChartSeries(id = "forecast", name = "Forecast", data = forecast, color = 0xFF9E9E9E.toInt())
```

### Semantic colour groups

Chart families that need meaning rather than order get their own nested group,
all derived from the Material scheme and all overridable:

```kotlin
materialDerivedChartColors().copy(
    financial = ChartFinancialColors(
        increase = brandRed,      // several markets colour rising prices red
        decrease = brandGreen,
        neutral = grey,
        wick = grey,
    ),
    heatmap = ChartHeatmapColors(
        low = surfaceTint, high = accent,
        missing = hatched,        // never the low end of the ramp
        cellBorder = surface,
    ),
    statistical = ChartStatisticalColors(
        box = fill, boxBorder = stroke, median = onSurface,
        outlier = accent, densityFill = fill, densityOutline = stroke,
    ),
    annotation = ChartAnnotationColors(
        line = onSurface, region = tint,
        labelContainer = inverseSurface, labelContent = inverseOnSurface,
    ),
)
```

Every parameter after `emptyContent` on `ChartColors` defaults to a value
derived from the ones above it, so a `ChartColors(...)` written against an
earlier surface still compiles and still looks right — and an application that
customised four colours does not have to learn about twenty.

Financial colours are the clearest case for a semantic group rather than a
palette slot: nothing in ChartKit's rendering knows that "up" is green, because
the convention is not universal and is invisible to the eight percent of men
with red-green colour vision deficiency, for whom the two most important colours
on a candlestick chart are the same colour.

## Animation

```kotlin
LineChart(
    ...,
    animation = ChartAnimation(
        enabled = true,
        durationMillis = 450,
        dataChangeDurationMillis = 300,
    ),
)
```

Two behaviours, deliberately different:

- **Initial reveal** — bars grow from the baseline, lines draw in. Runs once.
- **Data change** — values *interpolate*. A bar whose value went from 40 to 60
  travels to 60; it does not collapse to the axis and regrow, which would read
  as "the chart reloaded" and, on a dashboard updating every few seconds, never
  settle.

Interpolation needs a correspondence between old and new values, established
from the **series ids** and point counts. Same ids and same counts: everything
animates. A series added or removed, or a different number of points, is a
change of *shape* with no honest correspondence to animate along — those snap.
Matching on ids rather than list position means a reordered legend animates
nothing, because nothing moved.

Reveal is applied by clipping rather than by rebuilding the path, so a
10,000-point line animates without re-interpolating its curve sixty times a
second.

`ChartAnimation.None` for previews, screenshot tests, and readers who have asked
the system to reduce motion.

## Accessibility

A chart drawn on a `Canvas` is, to a screen reader, one unlabelled rectangle.
Nothing in Compose fixes that automatically, so ChartKit constructs the
semantics deliberately.

```kotlin
BarChart(
    ...,
    accessibility = ChartAccessibility(
        title = "Monthly revenue",
        description = "Six months, in Kenyan shillings",
    ),
)
```

announces:

```text
Monthly revenue. Six months, in Kenyan shillings. 6 data points.
Jan: 24,000, Feb: 31,500, Mar: 28,200, Apr: 39,800, May: 44,100, Jun: 41,600.
```

Multi-series charts name their series first. Missing values are counted and
named rather than skipped. Past `MAX_ANNOUNCED_POINTS` (24) the values are
summarised by range instead of read out — a screen reader reading two hundred
numbers in sequence is not accessible, it is a polite way of making the chart
unusable.

**Strictly factual.** Values, counts, minima and maxima; never "trending
upward", "a strong correlation" or any other interpretation. Those are
statistical claims ChartKit has not computed, and a confidently wrong one is
worse than silence for a reader who cannot check it.

### Polar charts

A pie announces the share, because the share is what the picture communicates:

```text
Monthly expenses. 6 data points.
Rent (46.2%): 1,800, Food (18.5%): 720, Transport (13.8%): 540, …
```

A radial bar announces the value **against its range**, because a reader who
cannot see the ring has no other way to learn the maximum:

```text
System utilisation. 4 data points.
CPU: 72 out of 100, Memory: 46 out of 100, Disk: 88 out of 100, …
```

### Interaction state

Selection, viewport and range all land in the **same** description, so a screen
reader hears what is on screen and what has been selected without any of it
being announced twice by a separate node:

```text
… Showing Sep 2025 to Nov 2025. Jan: 24,000. Selected 23 Sep 2025 to 25 Oct 2025.
```

The viewport line appears only while zoomed, and the range line only once the
drag has **completed** — a live region updated on every pointer frame turns a
screen reader into a stream of half-sentences.

Selection information lives in the semantics whether or not a tooltip is shown,
so turning tooltips off never hides a value from a screen reader.

The current selection is announced through a polite live region as it changes.

Replace the whole description when you have better context:

```kotlin
accessibilitySummary = {
    "Revenue by month. Highest in May at 44,100; lowest in January at 24,000."
}
```

`ChartAccessibility.Concise` keeps the title, the series names and the counts
but drops the value list.

### Advanced charts

Every chart type describes itself in its own terms rather than as a generic
series of numbers, because a mark that carries five numbers cannot be announced
as one:

```text
Response time distribution. 4 data points.
/checkout: minimum 189.6, first quartile 300.6, median 347.8,
third quartile 401.3, maximum 500.2, 5 outliers. …
```

```text
Daily prices. 130 data points.
14 Mar: open 229.04, high 234.63, low 226.84, close 232.03. …
```

```text
Weekday activity. 56 data points. Grid: 7 columns by 8 rows,
54 measured cells, 2 with no data. Highest: Wed 12, 1,168. Lowest: Sun 00, 41.
```

```text
Height and weight. 160 data points.
x 191, y 89. x 174, y 71. …
```

A scatter announces **both** coordinates, and the size too where one is
encoded — reading out only the y would describe half the observation. A heatmap
announces the grid's shape and its extremes rather than one node per cell: a
200 × 24 matrix has 4,800 of them, and a screen reader given all of them is
given a way to spend an afternoon.

Nothing is interpreted. No "trending upward", no "strong correlation", no
inferred seasonality — those are statistical claims ChartKit has not computed,
and a confidently wrong one is worse than saying nothing to a reader who cannot
check it.

A series past `MAX_ANNOUNCED_POINTS` is summarised by its range rather than
listed, and past that size the entries are not even built — they would be
allocated and never read.

### Hierarchy, flow, graph and timeline

Each announces itself in the terms its own picture uses:

```text
Company, Engineering, Android. 4,200,000. 37 percent of Engineering.
16 percent of Company.
```

```text
Search to Checkout: 1,240 users.
```

```text
auth. 5 connections: gateway, notifications, orders, payments, search.
```

```text
Latency spike, Auth: 09:00 to 11:00.
```

A treemap node's meaning is entirely relative — "42" is not a reading, "42, which
is 18% of Engineering" is — so the share of the parent and of the visible root
are announced with the value.

A network graph announces its **size and its hubs**, and then whatever is
selected. A semantics tree containing every edge of a three-hundred-node graph is
not access, it is noise; [`graphDataTable()`](#data-tables) is how a reader gets
at the connections.

### Multi-axis charts

A chart measuring three quantities is not describable as "3 series": a reader
needs to know that one of them is in millimetres and another in degrees before
any of the numbers mean anything. So the summary names the measures first, and a
selection is announced across every axis in that axis' own unit:

```text
3 measures. Rainfall: millimetres. Temperature: degrees Celsius.
Pressure: hectopascals. Rainfall: 12 data points. …
```

```text
March. Rainfall: 82 millimetres. Temperature: 14.2 degrees Celsius.
Pressure: 1018 hectopascals.
```

Axis **titles** and spelled-out units, never ids — `pressure-axis-2` would be
read out loud — and `millimetres` rather than `mm`, which a screen reader
pronounces one letter at a time.

### Set diagrams

Overlap is not describable in a sentence, so the tables are the diagram for a
reader who cannot see it. `setDataTable` lists the **regions**, which partition
the union and therefore add up; `setRelationshipTable` states the structure —
contains, is contained within, overlaps, shares nothing with.

Every sentence comes from the modelled cardinalities. A containment is announced
only when the numbers establish it, never because two labels look as though one
ought to be inside the other; and both tables' words are parameters, so a
diagram in another language announces in that language.

Selecting a region announces the region, not the set: "Android and iOS: 70",
where 70 is the count of items in exactly that combination.

### Geographic

A map is the case where a summary genuinely cannot carry the content. Shape,
adjacency and area are not describable in a sentence, and the data is encoded in
position and colour — both of which a non-sighted reader gets none of. The
summary names the regions and their values; `geoDataTable()` is how a reader
actually reads the map, and `ChartWithDataTable` is how it is offered.

An unmeasured region announces as "no data", never as zero. Nothing announces an
interpretation — no "high", no "clustered in the north-east" — because those are
statistical claims ChartKit has not computed.

Pan, zoom and reset are on the keyboard: arrows, `+`/`-` and `0`.

### Keyboard and screen-reader navigation

Every Cartesian chart is focusable and steps its selection without a pointer:

```text
→ / ←     next / previous data point
↑ / ↓     next / previous series
Escape    clear the selection
```

On a horizontal chart the axes swap, so the keys always mean "along the domain"
and "across the series" rather than "right" and "down".

The same four moves are exposed as TalkBack custom actions — *Next data point*,
*Previous data point*, *Next series*, *Previous series* — so a screen-reader user
can walk the values without placing a finger accurately on a three-pixel line.

All of it resolves through the **same** hit test a scrub uses, produces the same
`ChartSelection`, sets the same state and moves the same shared crosshair. A
parallel "focused index" model would have been a second notion of what is
selected, and the two would disagree the first time a chart was driven both ways.

Turn it off with `keyboardNavigation = false` where a chart is decorative.

## Data tables

The summary makes a `Canvas` describable; a table makes it **navigable**. A
single description is read start to finish and cannot be searched, so there is
no way to reach the fortieth value or compare two series at one category.

```kotlin
var showTable by remember { mutableStateOf(false) }

LineChart(series = series, x = { it.month }, y = { it.amount })

TextButton(onClick = { showTable = !showTable }) { Text("View data table") }
if (showTable) {
    ChartDataTableView(
        table = chartDataTable(
            series = series,
            category = { it.month },
            value = { it.amount },
            valueFormatter = ChartNumberFormatters.integer(),
            caption = "Revenue and expenses, first half",
        ),
    )
}
```

Opt-in rather than always present: a hidden table attached to every chart would
double the semantics tree of every screen for a facility most of them do not
need.

Typed adapters for data that is not one number per category — no reflection,
the same lambdas the chart itself was given:

```kotlin
ohlcDataTable(data = prices, date = { it.label },
    open = { it.open }, high = { it.high }, low = { it.low }, close = { it.close },
    volume = { it.volume })

boxPlotDataTable(data = endpoints, label = { it.path },
    statistics = { BoxStatistics.from(it.latencies) })

table.asText()   // the whole thing as one string, for a share sheet or a log
```

**One node per row, not per cell.** Each row reads "Category: January, Series:
Revenue, Value: 24,000". A node per cell would be technically richer and
practically worse — three swipes for one fact, and the column name lost by the
time the reader reaches the number. Repeating the column name inside the row is
what makes the value readable out of context.

### Adapters for every shape

A chart whose rows are not `x, series, value` gets its own adapter, written by
hand against the model that produced it:

| Adapter | Columns |
| --- | --- |
| `chartDataTable` | x · series · value |
| `ohlcDataTable` | date · open · high · low · close |
| `boxPlotDataTable` | category · min · Q1 · median · Q3 · max |
| `hierarchyDataTable` | path · value · of parent · of root |
| `sankeyDataTable` | from · to · value |
| `funnelDataTable` | stage · value · of first · conversion · lost |
| `waterfallDataTable` | step · change · running total |
| `timelineDataTable` | event · lane · start · end |
| `graphDataTable` | node · connections · connected to |
| `geoDataTable` | region · value, with "no data" spelled out |
| `setDataTable` | region · value · share of the union |
| `setRelationshipTable` | set · relationship · set |
| `comboDataTable` | x · measure · series · value · unit |

`comboDataTable` is the multi-axis one, and the unit column is the point: `82`,
`14.2` and `1018` under a single "Value" heading are three quantities presented
as if they were comparable, which is exactly the misreading a second axis invites
in the picture and which a table has no excuse for.

The alternative — walking your objects and guessing at their fields — would need
reflection, would break under R8, and would produce column names from property
names rather than from what the chart plotted.

`ChartWithDataTable` pairs a chart with its table behind a tab strip, composing
**one or the other** so a screen reader is never handed both the summary and the
whole table:

```kotlin
ChartWithDataTable(table = chartDataTable(revenue, category = { it.month }, value = { it.amount })) {
    LineChart(data = revenue, x = { it.month }, y = { it.amount })
}
```

## Capture

```kotlin
val capture = rememberChartCaptureState()
val scope = rememberCoroutineScope()

LineChart(
    data = revenue, x = { it.month }, y = { it.amount },
    modifier = Modifier.fillMaxWidth().height(240.dp).chartCapture(capture),
)

Button(onClick = { scope.launch { share(capture.capture()) } }) { Text("Share") }
```

The capture goes through Compose's own `GraphicsLayer`: the composable is
recorded as it draws and rasterised on demand. Nothing reads the window, nothing
needs a `View`, nothing depends on the chart being unobscured, and no permission
is involved. The brittle alternatives — `PixelCopy` over the window, drawing a
`View` into a `Canvas`, `MediaProjection` — all fail differently on different
manufacturers' builds and all capture whatever happens to be in front.

Because it is a `Modifier`, it captures **whatever it is applied to**: one
chart, a chart with its own title and legend around it, or a whole dashboard.

The image contains everything the modified composable draws — plot, axes,
annotations, and the legend, which for ChartKit's own charts is part of the
chart. A tooltip or dropdown rendered in a `Popup` or `Dialog` is drawn in a
separate window and is **not** captured, and nor is content outside the modified
composable.

`capture()` throws if the chart has not drawn yet; `captureOrNull()` returns
`null`, and `isReady` says which it will be.

## Static rendering

```kotlin
LineChart(
    data = revenue, x = { it.month }, y = { it.amount },
    renderMode = ChartRenderMode.Static,
    modifier = Modifier.chartCapture(capture),
)
```

```text
gestures       no pointer input is installed at all
animation      settled — drawn at its final state, never mid-reveal
selection      drawn only if the caller set it programmatically
tooltip        suppressed unless explicitly requested
crosshair      suppressed: it follows a pointer that is not there
```

A report render is not "animation off". It is animation off *and* gestures off
*and* transient selection suppressed — decisions that are only correct together,
and which as five parameters would be set in four different combinations across a
codebase, producing a PDF with a tooltip frozen in the middle of it.

Nothing in a static render depends on a running clock or a pointer position, so
two captures of the same chart at the same size are identical — which is what
makes a screenshot test worth writing.

`ChartStaticOptions` says what a static render still shows:

| Preset | Shows |
| --- | --- |
| `Default` | a programmatic selection; no tooltip, no crosshair |
| `Annotated` | selection, tooltip and crosshair, for an annotated figure |
| `Bare` | data and furniture only |

Gestures are not installed at all rather than ignored: a modifier that consumed
events would still stop a parent from scrolling.

## Export

### Raster

```kotlin
val capture = rememberChartCaptureState()

LineChart(…, modifier = Modifier.chartCapture(capture))
Button(onClick = { scope.launch { share(capture.capture(ChartCaptureOptions.HighResolution)) } })
```

| Option | Meaning |
| --- | --- |
| `scale` | multiplies the output's pixel dimensions. The chart is **re-rasterised**, not upscaled, so text and strokes are genuinely sharper |
| `background` | `null` leaves it transparent — right for compositing, wrong for an unknown surface, where a dark-theme chart is invisible on white paper |
| `maxDimensionPx` | a ceiling on either edge. A capture at 8× of a full-screen chart is tens of megabytes, and an `OutOfMemoryError` from a share button is a poor way to find out |

At `scale = 1` with no background this is the recording's own rasterisation,
which is both faster and exact.

### Vector

```kotlin
val scene = rememberChartSceneState()

LineChart(…, sceneState = scene)
Button(onClick = {
    val current = scene.scene ?: return@Button
    if (current.isComplete) share(ChartSvg.render(current, title = "Monthly revenue"))
})
```

A `ChartScene` is the picture stated as **data** — lines, rectangles, circles,
arcs, paths, text and groups — which a draw call is not: once a layer has called
`drawPath`, nothing remains to serialise. `ChartSvg.render` writes it as SVG,
with each layer as a named `<g>` so the output is editable in a vector tool.

**Coverage is reported, not assumed.** Every layer is asked whether it can also
*describe* itself; one that cannot is named in `ChartScene.unexportedLayers`, and
`isComplete` is how a caller decides whether to ship the file or fall back to a
raster capture. A vector export that silently dropped a candlestick series would
be worse than none: the file opens, it looks like a chart, and the data is
missing.

| Exported | Not exported |
| --- | --- |
| grid, axes, lines, areas, straight-interpolated series | curved (monotone/step) lines — flattening a Bézier here would produce a *different* curve from the one on screen |
| bars, circular scatter and bubble marks | non-circular scatter shapes |
| every annotation: rules, bands, regions, markers, callouts, arrows, labels | candlestick, OHLC, volume, box plot, violin, heatmap, calendar |
| — | anything composed rather than drawn: tooltips, overlay content, legends |

Text carries the size the chart measured with and a generic `sans-serif` family.
A viewer with different metrics lays the glyphs out slightly differently; the
**positions** are exact, because the chart computed them, so labels stay on their
ticks.

This is a foundation rather than a finished feature, and it is one deliberately:
rebuilding every layer against a scene model to complete it is the rewrite that
was not worth doing. Raster capture covers every chart today.

## Loading, empty and error

```kotlin
LineChart(
    data = revenue,
    x = { it.month },
    y = { it.amount },
    isLoading = uiState.isLoading,
    error = uiState.error,
    loadingContent = { CircularProgressIndicator() },
    emptyContent = { Text("No revenue recorded yet") },
    errorContent = { Text("Could not load: ${it.message}") },
)
```

Composable slots with plain defaults. An empty dataset does **not** draw broken
axes — it draws the empty content over an empty plot.

ChartKit does not own retry. Retrying is an application concern with its own
backoff, authentication and navigation attached, and a library that grew a Retry
button would be guessing at all three.

No sealed `ChartUiState` is imposed. If your app has one, map it at the call
site; if it does not, three parameters are less ceremony than a wrapper type.

## Sizing

Size a chart with ordinary Compose modifiers:

```kotlin
LineChart(..., modifier = Modifier.fillMaxWidth().height(240.dp))
```

No internal fixed height, and no assumed width. Constraints are measured, so
charts behave on small phones, tablets, landscape, foldables and resizable
windows. `ChartDimensions.defaultChartHeight` (200.dp) applies only as a
`defaultMinSize` when the caller constrains neither dimension.

At narrow widths, axis labels thin, the legend wraps, and gutters shrink to the
measured text. Data is never removed to make a chart fit.

---

## X values

`x` and `category` are typed `(T) -> Any?`, and this is the one deliberate
looseness in the API. The alternatives were each worse: `(T) -> ChartX` makes
every call site read `x = { ChartX(it.month) }` — the conversion step this
library exists not to require; overloading on the lambda's return type does not
compile, because `(T) -> String` and `(T) -> Number` erase to the same JVM
signature; making the chart generic in `X` needs an implicit resolver per `X`,
which Kotlin has no mechanism to supply.

Everything else stays typed: `y` is `(T) -> Number?`, series are
`ChartSeries<T>`, and a selection hands back your own `T`.

| Your value | Axis |
| --- | --- |
| `Number` | Numeric |
| `CharSequence` | Category |
| `Boolean` | Category |
| `Enum` | Category, by `name` |
| `java.util.Date` | Time |
| anything else | Category, by `toString()` |

This is **type inspection, not reflection** — a `when` over `is Number`, no
`Class.forName`, no field lookup, no `kotlin-reflect`. Nothing costs a class
load or breaks under R8.

### Time axes

Pass epoch milliseconds and the time resolver:

```kotlin
LineChart(
    data = readings,
    x = { it.atMillis },
    y = { it.celsius },
    xResolver = ChartXResolver.Time,
    xAxis = ChartAxis(timeFormatter = ChartDateFormatters.pattern("HH:mm")),
)
```

Milliseconds rather than `Instant` or `LocalDate`, because those are API 26 and
ChartKit's floor is 24 — naming them would either raise the floor or oblige every
consumer to enable core-library desugaring for a chart. Milliseconds also carry
no time zone the library would have to guess at. On `java.time` types (with
desugaring enabled in your own build), convert at the call site where the right
zone is known:

```kotlin
x = { it.date.atStartOfDay(zone).toInstant().toEpochMilli() }
```

Positioning is proportional to elapsed time, so three readings a minute apart
followed by one an hour later is drawn with the gap visible.

### A domain type of your own

Supply a resolver rather than converting your data:

```kotlin
val fiscalQuarters = ChartXResolver { value ->
    when (value) {
        is FiscalQuarter -> ChartX.Category(value.shortLabel)
        else -> null   // fall through to the defaults
    }
}

BarChart(..., xResolver = fiscalQuarters)
```

## Missing values

A `null` from `y`, or a `NaN` or `Infinity`, is **missing** — never quietly zero.
A missing reading and a reading of zero are different facts.

| Policy | Behaviour |
| --- | --- |
| `Break` | Gap in the line. The default |
| `Connect` | Straight through the gap |
| `Zero` | Treated as zero |

`Zero` is correct for a genuinely additive quantity — no sales recorded means
zero sales — and wrong for anything measured, where it invents a reading. Hence
opt-in.

## Ordering

ChartKit does **not** sort your data. A line chart of unsorted points draws in
the order given, which is visible and fixable; a silent reorder produces a chart
that disagrees with the list you are looking at, invisibly.

```kotlin
LineChart(..., dataOrder = ChartDataOrder.SortedByX)   // opt in
```

Sorting applies to continuous axes only. On a category axis your list order *is*
the band order.

## Edge cases

Every one of these renders, and each is covered by a test:

| Input | Behaviour |
| --- | --- |
| `[]` | Empty content; no broken axes |
| `[42.0]` | One point or bar, centred |
| `[5, 5, 5]` | Zero data span; the domain is widened, the line sits mid-plot |
| `[0]` | Domain widened around zero |
| `[-10, -5]` | Wholly negative domain |
| `[-20, 0, 20]` | Baseline in the middle |
| `null` in a series | Gap, per the policy |
| `NaN` / `Infinity` | Treated as missing |
| 0.001 among thousands | Still drawn: a real value never rounds to invisible |
| A category totalling 0 in a 100% stack | Empty segments, no division by zero |
| A plot squeezed to nothing by its axes | Layers decline to draw |

Programming errors fail fast instead: a blank series id, duplicate ids in one
chart, `tickCount < 2`, `categoryPadding >= 1`, a `Fixed` domain with
`min >= max`, a blank currency code.

---

## Architecture

```text
Core
├── model        ChartSeries<T> · ChartX · ChartXResolver · PlotData
│                ChartSelection<T> (+ Cartesian / Polar details)
│                ChartTooltipData<T> · ChartRangeSelection<T>
├── stats        ChartStatistics · BoxStatistics · HistogramBinner
│                DensityEstimator · MovingAverage
├── data         ChartDownsampler (LTTB, min/max) · VisibleRange
├── scale        position  LinearScale · CategoryScale · TimeScale
│                size      SizeScale
│                colour    ColorScale (continuous, threshold, quantized, categorical)
│                NumericDomain · DomainPolicy · TickGenerator
├── geometry     ChartRect/Offset/Insets · bar stacking · line interpolation
│                PolarGeometry · RadialGeometry · ScatterIndex
│                CalendarGeometry · OHLC normalisation
├── axis         ChartAxis · ChartAxisId · ChartAxisSpec · AxisRegistry
│                ChartUnit · AxisTickAlignment · AxisAlignment · AxisDensity
│                AxisPosition · AxisDimension · AxisVisibility · AxisGridMode
│                MeasuredAxis · AxisDiagnostic
├── layout       ChartLayoutEngine → Cartesian plot area (axis gutters, per-axis
│                                    offsets when a side carries more than one)
│                                  → polar plot area (largest centred square)
├── viewport     ChartViewport · ChartZoomLimits
├── stream       ChartWindow · ChartUpdatePolicy · ChartAggregation
│                ChartStreamBuffer (ring) · ChartStreamCollector
├── animation    reveal fraction · value interpolation · selection emphasis
├── theme        ChartKitTheme → ChartColors / ChartTypography / ChartDimensions
│                nested: financial · heatmap · statistical · annotation
├── formatter    ChartValueFormatter · ChartTimeFormatter and built-ins
├── annotation   ChartAnnotation · AnnotationStyle · builders
│                rules · bands · regions · markers · callouts · arrows · labels
├── hierarchy    ChartHierarchy · HierarchyNode · TreemapLayout (squarified)
│                SunburstLayout
├── flow         SankeyGraph (validation, cycle cutting, column assignment)
│                SankeyLayout (barycentre ordering, node sizing, band routing)
├── graph        ChartGraph · GraphLayout (circular) · ForceSimulation
├── geo          GeoJson (hand-written reader) · GeoFeature · GeoGeometry
│                GeoProjection (equirectangular · Mercator) · ProjectedGeometry
│                GeoGeometryMath (ray casting · centroids · simplification)
│                GeoSpatialIndex (uniform grid)
├── set          SetDefinition · SetIntersection · SetContainment · SetRegion
│                SetAnalyzer (inclusion–exclusion, both directions) · SetValidator
│                SetRelationshipGraph · SetShape (circle · ellipse)
│                VennLayoutEngine · EulerLayoutEngine · CircleOptimizer
│                RegionGeometryIndex · SetHitTester
├── gauge        GaugeScale (value ↔ angle) · GaugeTickPlan · GaugeBandResolution
│                GaugeGeometry (needle and marker outlines · arc bounds · fit)
│                GaugeValue · GaugeNeedleStyle · GaugePivotStyle · GaugeMarker
│                GaugePane · GaugeDetail · GaugeInteraction · GaugeOverflow
├── three        Point3D · Vector3D · Matrix4 · Bounds3D · Face3D
│                Chart3DGeometry — Cuboid3D · RadialSector3D (Sector3D ·
│                AnnularSector3D) · ArcTessellator3D · Chart3DQuality
│                Chart3DCamera · Chart3DProjection (perspective · orthographic)
│                Chart3DScene · Chart3DObject · Chart3DLighting · Chart3DFrame
│                Chart3DDepth · Chart3DProjector (transform · cull · sort ·
│                light · fit) · Chart3DHitTest · Chart3DDiagnostics
│                Column3DLayoutEngine · Radial3DLayoutEngine
├── timeline     TimelineModel · lane and row assignment · dependencies
├── transform    WaterfallTransform · FunnelTransform
├── scene        ChartScene · ChartSceneNode · ChartSceneBuilder
├── export       ChartSvg
├── render       ChartRenderMode · ChartStaticOptions
├── accessibility factual summaries, selection, viewport, range · ChartDataTable
│                typed adapters per chart shape
├── capture      ChartCaptureState · ChartCaptureOptions · Modifier.chartCapture
└── state        ChartState<T> · ChartViewportState · ChartPlanarViewportState
                 ChartGeoViewportState · ChartSharedCrosshairState · ChartHierarchyState
                 ChartGraphLayoutState · ChartFilterState · ChartBrushState
                 ChartPlotAlignment · ChartInteractionGroup

Coordinates
├── CartesianCoordinates   DomainAxis + value scale + orientation
│                          (3D columns are drawn on these too: the third
│                           dimension is a scene above the same plot area,
│                           not a fifth coordinate system)
├── PolarCoordinates       centre + inner/outer radius + start/sweep + direction
│                          (3D pies and donuts are drawn on these too, on the
│                           same terms: an extruded scene above the same ring)
├── PlanarCoordinates      a plain rectangle, for layout-driven visualisations
└── GeoCoordinates         projection + fitted extent + two-dimensional camera

Layout engines
├── Cartesian     axis gutters → plot rectangle, axes stacked per side
├── Polar         largest centred square → ring; a partial sweep fits the
│                arc's own box instead, so a semicircle is not centred in the
│                square its full circle would need
├── Hierarchical  squarified treemap · sunburst rings
├── Flow          Sankey columns, node placement, band routing
├── Graph         circular · force-directed
├── Set           canonical Venn arrangements · Euler nesting · pattern search
└── Geographic    project once → fit uniformly → zoom and pan

Layers
├── Cartesian   grid · line (line + area + points) · bar · histogram
│               scatter (scatter + bubble) · box plot · violin · heatmap
│               candle (candlestick + OHLC) · volume · waterfall
│               connector marks (dumbbell + lollipop) · bullet · interval
│               value labels · crosshair · range selection
│               annotations (behind and above) · custom
├── Polar       slice (pie + donut) · radial bar · radar web · radar
│               sunburst · gauge arc · gauge dial · custom
├── 3D          columns (grouped · stacked · grouped and stacked · percent),
│               on Cartesian coordinates and the same stack engine
│               radial (pie · donut · partial · exploded), on polar
│               coordinates and the same slice engine — and on the same
│               scene, camera, projection, culling, sort, lighting and
│               hit test as the columns
├── Planar      treemap · Sankey · funnel · graph · set diagram
└── Geographic  choropleth

Interaction
└── ChartGestureCoordinator   tap · scrub · pan · pinch · range, arbitrated once
    ChartInteraction · ChartDragMode · CrosshairConfig · HitTestMode
    keyboard and D-pad stepping · TalkBack custom actions

Overlay
└── ChartOverlay              measured placement for tooltips and custom content
    ChartOverlayScope         Compose content anchored in chart coordinates

High-level charts
├── LineChart · AreaChart · BarChart · HorizontalBarChart · CartesianChart
│   ScatterChart · BubbleChart · Histogram · BoxPlot · ViolinPlot
│   Heatmap · CalendarHeatmap · CandlestickChart · OhlcChart · VolumeChart
│   WaterfallChart · DumbbellChart · LollipopChart · BulletChart
│   TimelineChart · RangeChart · GanttChart · ChartNavigator
├── PieChart · DonutChart · RadialBarChart · RadarChart
│   SunburstChart · GaugeChart
└── Treemap · SankeyChart · FunnelChart · NetworkGraph
```

Every Cartesian chart reduces to one call into `CartesianChartCore`, every polar
chart to one call into `PolarChartCore`, and every layout-driven one to a call
into `PlanarChartCore`. The three cores differ in exactly three things — the
layout call, the coordinate construction and the hit test. Everything else is the
same code.

**A line chart is** a Cartesian chart + a line layer + optional points + axes +
grid. **A bar chart is** a Cartesian chart + a bar layer. **A horizontal bar
chart is** the same bar layer with the orientation flipped. **A bubble chart is**
a scatter whose radius comes from a `SizeScale`. **An OHLC chart is** a
candlestick chart with a different mark style. **A histogram is** a bin layer on
a continuous domain. **A pie chart is** a polar chart + a slice layer. **A donut
is** a pie with an inner radius. **A radar chart is** a polar chart + a web layer
+ a polygon layer.

**A sunburst is** the treemap's hierarchy laid out on the polar engine. **A gauge
is** an arc on the same polar coordinates as the pie, and **a dial gauge is**
that arc plus a scale in the angular domain — ticks, numbers and a needle, all
derived from one `GaugeScale`. **A lollipop is** a
dumbbell whose first value is the baseline. **A timeline is** a range chart with
no end accessor, and **a Gantt chart is** one with a progress overlay. **A
logarithmic axis is** the ordinary linear scale over a transformed domain, which
is why the grid, the crosshair, hit testing and every layer work on it
unchanged.

### Data flows one way

```text
Data  →  Transforms  →  Scales  →  Coordinates  →  Layers  →  Viewport
      →  Interaction  →  Animation  →  Theme  →  Accessibility
```

**Transforms** are where binning, stacking, percent normalisation, density
estimation, OHLC repair and downsampling happen — before anything is positioned,
and none of them inside a draw pass. **Scales** are the three kinds: position,
size and colour. Nothing below Coordinates names an x or a y.

### Series-shaped data, and everything else

Lines, areas, bars and scatters come from `ChartSeries` and carry a `PlotData`.
Histograms, box plots, violins, heatmaps and price marks are not series at all —
a histogram's data is bins, a box plot's is five numbers per category — and
carry their own. Both kinds answer the same three questions the geometry builder
asks: which axis kind, which categories, and what interval do you need.
Everything downstream of those answers is shared, which is why a threshold
annotation, a crosshair, a shared viewport and an accessibility summary all work
on every one of them without knowing which is which.

### Portability

Everything in `model`, `scale` (bar the colour scale, which is Compose colours),
`geometry`, `layout`, `stats`, `data`, `formatter` and the streaming buffer is
plain Kotlin — no Compose, no `android.graphics`. `ChartOffset`, `ChartRect` and
`ChartInsets` exist instead of `Offset`, `Rect` and `PaddingValues` so the
arithmetic is testable on the JVM without Robolectric and could move to Compose
Multiplatform without unpicking Android types from the maths. Quartiles, kernel
density, LTTB, binning, visible-range lookup and OHLC normalisation are all in
that set, which is why they are verified directly rather than by looking at a
canvas.

### One engine, N axes

The Cartesian engine used to resolve a layer's scale by asking whether it was on
the primary or the secondary axis — a two-valued enum, checked with an `if`, in
three places. That is exactly as far as that design goes: there is no third
branch of that `if` that means "pressure".

`AxisRegistry` replaces the `if` with a lookup. Layers name an axis; the chart
resolves the name to a scale **once**, before drawing, and hands each layer a
`CartesianCoordinates` built over its own axis, sharing the plot area and the
domain axis:

```text
CartesianChart
├── AxisRegistry
│   ├── X: default-x
│   └── Y: rainfall (primary, grid) · temperature · pressure
├── Layers      Bars → rainfall · Line → temperature · Line → pressure
├── Shared      plot area · viewport · interaction · overlay · animation clock
└── Per axis    domain · scale · ticks · formatter · unit · coordinates
```

Nothing in the layer model knows how many axes exist, which is why bars, lines,
areas, scatters, candles, volume, boxes, violins, waterfalls, connectors,
bullets, intervals and custom layers all gained multi-axis support without being
touched. There is no `MultiAxisLineRenderer`, no `MultiAxisCoordinates` and no
combo chart type — multi-axis is a Cartesian *coordinate* concern, and it stayed
one.

### Room to grow

The architecture was built for a second coordinate system, and then got three.
`PolarCoordinates`, `PlanarCoordinates` and `GeoCoordinates` are siblings of
`CartesianCoordinates` under the same `CoordinateSystem` interface, and adding
any of them changed nothing in the layer model, the selection model, the overlay,
the animation clock, the theme or the accessibility layer. Radar then cost two
layers on top of the polar one and no new coordinate system at all; the treemap,
Sankey, funnel and graph charts cost one layer each on top of the planar one.

Thematic maps were the strongest test of that claim, because "add maps to a
charting library" usually means adding a mapping SDK. Here it meant a coordinate
system that projects and fits, one layer that draws polygons, and an engine file
that assembles the two — plus the geography package, which is pure Kotlin and
verified on the JVM. The tooltip, the legend, the selection, the animation, the
theme, the static render mode and the accessibility summary are the same code a
line chart uses.

`PlanarCoordinates` answers exactly one question — "where is the plot" — and
exists so that four layout-driven visualisations do not each get their own
`Canvas`. The alternative would have been four copies of the selection model, the
tooltip overlay, the legend, the animation clock, the theme lookup, the
accessibility summary and the capture modifier, and four places for them to
drift apart.

Set diagrams were the second such test, and they needed no coordinate system at
all: a Venn diagram is geometry placed inside a rectangle, which is what
`PlanarCoordinates` already answers. What they did need was a *model* — sets,
intersections, containment, logical regions — and that model is deliberately
separate from the layout that draws it. `VennLayoutEngine` and
`EulerLayoutEngine` are two strategies over one `SetDiagramData`, and an UpSet
plot would be a third: same analysis, same validation, same regions, same
selection, same accessibility, a completely different picture.

`ChartLayerRenderer` is small and defaulted, so a candlestick, a violin, a
heatmap or an annotation rule is a new implementation rather than a change to
the coordinate system, the layout engine or the interaction model. The
crosshair, the range overlay and both annotation layers are ordinary layers,
which is why they work on every Cartesian chart rather than on the one they were
written for.

## Performance

**Drawing primitives, not composables.** Paths, bars, arcs, cells, candles,
markers, grid lines, treemap tiles, sunburst arcs, flow bands, graph nodes and
axes are `DrawScope` calls. Composables are used for the tooltip, the legend, a
donut's centre content, breadcrumbs and custom overlays — the things that have to
measure text and take input. There is no composable per point, per cell, per
slice, per candle, per tile, per arc or per node.

A hierarchy of two thousand nodes as composables would be two thousand layout
nodes, two thousand semantics nodes and a recomposition per selection change; as
a canvas it is one draw pass over a precomputed list.

**Geometry is cached against its inputs.** Scales, ticks, interpolated paths,
bar rectangles, bins and spatial indices are computed inside a `remember` keyed
on the data, the measured size, the theme, the locale, the axis configuration
and the viewport. A tooltip appearing, a selection moving or an animation frame
ticking does not re-derive the domain of a hundred thousand points. Line paths
are cached again inside the layer against the plot rectangle.

**Culling and downsampling.** See [Large datasets](#large-datasets). Zooming
narrows the domain the scales map, so a zoomed chart processes only the visible
window plus an overscan, and a series denser than the plot's own width is
sampled down to it. Both are configurable and both can be turned off.

**Reveal is a clip, not a rebuild.** Animating a 10,000-point line does not
re-interpolate its curve every frame.

**Hit testing is sublinear where it can be.**

| Mark | Test |
| --- | --- |
| Bar | Rectangle, then a band fallback |
| Line, area, candle, volume | Binary search over the **source** domain values — 17 comparisons over 100,000 points against 100,000 for a scan, on every pointer frame |
| Scatter, bubble | A uniform spatial grid, widened ring by ring and stopped as soon as no closer point can exist. Scatter data has no order to binary-search |
| Pie, donut | One radius rejection, then an angle comparison |
| Radial bar, radar | A radius comparison; a vertex distance |
| Heatmap | A band lookup and a rounded row index |

None of it inspects pixels, and the line search runs against the full series
rather than the drawn subset — which is what makes downsampling honest.

**Gestures allocate nothing per frame.** The coordinator resolves a gesture's
meaning once, at the start of the drag, then reports intent — "pan by this
fraction" — rather than re-deriving chart geometry on every pointer event.

**Streaming does not recompose per event.** The collector consumes at the
producer's rate and publishes on an interval; the window is a ring buffer, so
retaining the newest 600 of a hundred-a-second stream overwrites one slot rather
than copying the buffer.

**Accessibility scales too.** A series past the announcement cap supplies its
range instead of materialising entries nobody will hear, and a heatmap describes
its shape and extremes rather than every cell.

**Measured behaviour.** The sample's Large datasets screen builds and draws
1,000 / 10,000 / 50,000 / 100,000-point series on demand, with every sampling
strategy switchable while it is on screen; at 100,000 with `Auto` the chart
draws immediately and scrubbing reports the source index — `Reading 48704 of
100,000`. `LargeSeriesGeometryTest` and `LargeDatasetTest` assert the same
properties without a device: that normalisation, scaling, segmentation and
nearest-point search stay proportionate, that culling narrows what is drawn
while leaving the source lists whole, and that binary search beats a scan.

These are sanity bounds, not benchmarks. The repository has no benchmarking
infrastructure and standing one up for a single library would have been a larger
change than the library; no throughput or frame-time figures are claimed here,
because measuring them properly is a separate piece of work.

Practical guidance: with the default performance profile, line and area series
in the tens of thousands are comfortable and 100,000 is usable. Scatter is not
downsampled — every observation is drawn, because dropping some would change
what the chart claims — so a scatter beyond a few tens of thousands wants a
larger marker budget or fewer points. Heatmaps are bounded by their cell count
rather than by their data: a 200 × 24 grid is 4,800 rectangles a frame.

### Layout is never computed while drawing

The expensive layouts are functions of the data and the plot rectangle, cached on
exactly those:

| Layout | Cost | Recomputed when |
| --- | --- | --- |
| squarified treemap | `O(n)` per level | the tree or the plot size changes |
| sunburst rings | `O(n)` | the tree, the radii or the drill level change |
| Sankey ordering | `O(passes × links × nodes)` | the graph or the plot size changes |
| force simulation | `O(n²)` per step | the graph or the strategy changes |
| timeline row packing | `O(n log n)` | the entries change |

Selecting a node, hovering a band, animating the reveal or moving the crosshair
does **not** touch any of them. The Sankey ordering in particular is the most
expensive thing in that diagram, and running it per frame would be the difference
between a diagram and a slideshow.

### The force simulation runs off the composition

A force layout is a loop over every pair of nodes. It steps in batches on
`Dispatchers.Default`, publishes immutable snapshots at roughly 30 Hz, and stops
the moment it settles — never one recomposition per iteration, and never inside a
draw pass. Changing the graph or the strategy cancels the outstanding work
through ordinary structured concurrency.

Repulsion is `O(n²)`, which is honest for the graph sizes a phone screen can show
anything useful of. Above `ForceSimulation.MAX_SIMULATED` (1,200) nodes the
circular layout is used instead; a Barnes–Hut tree would be the answer for tens
of thousands, and tens of thousands of nodes is not a picture.

### Geography is projected once

The most expensive mistake a thematic map can make is reprojecting its geography
because a number changed. So the projected geometry is cached against the
geometry-and-projection pair, and the screen-space paths against the transform:

```text
GeoJSON ──parse──▶ features ──project──▶ ProjectedGeometry   (once, per projection)
                                              │
value ─▶ join ─▶ ColorScale ─▶ FeatureStyle ──┴──▶ Canvas     (every frame, cheap)
```

Changing the year, the metric, the colour scale, the selection or the animation
frame touches the right-hand path only. A zoom, a pan or a resize rebuilds the
paths; a new dataset or a new projection rebuilds everything.

On top of that: features outside the visible extent are culled by bounding box,
hit testing runs through a uniform grid above 64 features (below it, a scan of
64 boxes is cheaper than the grid that would replace it), and candidates come
back smallest-box-first so a tap in a nested region picks the inner one.
Optional Ramer–Douglas–Peucker simplification drops vertices a screen cannot
show — off by default, because discarding a caller's geographic fidelity is not
a decision a chart should make for them.

### Culling in the planar charts

A zoomed graph skips nodes outside the plot and edges whose bounding box misses
it entirely. At full zoom the test is skipped too, so the common case pays
nothing for it.

## Current limitations

Stated plainly, because a roadmap read as a feature list is how a library gets
adopted for something it cannot do.

**Not supported:**

- Street maps, basemaps, tiles, satellite imagery, routing, POI search and
  geocoding. `ChoroplethMap` shades boundaries you supply and is not a mapping
  SDK
- 3D charts, chord and arc diagrams
- UpSet plots and set matrices. The set model is built to feed one — see the
  roadmap — but the layout does not exist yet
- Polar-area charts, and stacked **areas** — multi-series areas overlap, each
  measured from the baseline
- Interactive range **handles** on a chart's own range selection: a range is
  dragged out afresh rather than resized by its edges. The overview navigator's
  window *does* have draggable edges
- Zoom and pan on polar charts. Pie, donut, radial bar, radar, sunburst and gauge
  take tap selection and tooltips only; a viewport over an angle is a different
  interaction, not a reuse of this one. An adjustable `DialGauge` is the one
  polar chart that reads a *value* out of a pointer, and it does so through its
  own scale rather than through a viewport
- Curved tick labels on a dial. Numbers are drawn upright and placed radially,
  which is what a car's speedometer does; text following the arc needs
  per-glyph placement and is illegible below about 200dp anyway
- Gauge band tooltips. A band is a background, and tapping one on a dial that is
  also adjustable would mean two things at once. The band a value falls in is
  published through `onReadingChanged` instead
- Y-axis zoom. The Cartesian viewport narrows the domain axis only — which is
  also the right default for a multi-axis chart, where the axes share nothing but
  their X. `AxisRegistry` is where a per-axis Y viewport would go
- Multiple **X** axes. The registry models the dimension and would take a second
  one, but nothing builds a top axis yet: combo charts share one X domain, which
  is what makes them comparable at all
- Scrollable axes. When three axes will not fit, ChartKit compacts them and
  reports a diagnostic rather than putting them in a scroller — an axis you have
  to scroll to is an axis you cannot read the plot against
- Automatic axis assignment. A layer that does not name an axis is on the primary
  one, and ChartKit will not infer one from a series' magnitudes
- Fling/inertial panning — a drag pans directly and stops when it stops
- Technical indicators beyond simple and exponential moving averages
- Automatic dependency routing on a Gantt chart. Dependencies are modelled and
  drawn as direct connectors; routing them around the bars in between is an
  edge-routing pass, and doing it badly puts arrows through the tasks
- Project scheduling, critical-path analysis and resource levelling. The Gantt
  chart is a visualisation, not a planning tool
- Spatial indexing beyond a uniform grid — a k-d tree or an R-tree would beat it
  for a scatter with extreme clustering, and for geography where a few enormous
  regions overlap thousands of tiny ones
- Barnes–Hut approximation for the force layout, so graphs above 1,200 nodes fall
  back to the circular layout
- GPU / `RenderNode` rendering
- Screenshot/golden tests, and benchmarks — the repository has no such
  infrastructure
- Compose Multiplatform targets — the pure logic is portable, the module is not

**Known behavioural limits:**

- **Vector export is partial and says so.** Grid, axes, straight-interpolated
  lines and areas, bars, circular scatter and every annotation export as SVG;
  curved lines, non-circular scatter shapes, candlestick, OHLC, volume, box plot,
  violin, heatmap and calendar do not. Anything *composed* rather than drawn — a
  tooltip, overlay content, a legend — is not part of a scene at all. Layers that
  cannot be represented are named in `ChartScene.unexportedLayers`, and
  `isComplete` is how a caller decides whether to ship the file. Raster capture
  covers every chart
- A curved line is not exported because flattening its Bézier here would produce
  a *different* curve from the one on screen — a difference nobody would see
- An area's gradient fill exports as a flat translucent fill: the on-screen ramp
  depends on a plot height the exported file no longer has
- SVG text carries the measured size and a generic `sans-serif` family. The
  glyphs may lay out differently in another viewer; the positions are exact
- Plot alignment settles on the **second** frame — measuring something before
  agreeing on it takes one extra pass. It cannot oscillate
- The force layout is seeded and therefore reproducible, but its result is not a
  documented function of the input: tuning the forces would change every picture
- Cross-filtering coordinates *which* selections are active and performs no
  filtering. `ChartFilterState.apply` covers the simple `equals` case; anything
  else is the application's own code
- A log axis cannot represent zero or a negative number. `LogValuePolicy` states
  which of clamping, skipping or throwing happens; symmetric-log is the axis for
  data that genuinely crosses zero
- A second value axis is bound explicitly per layer and is never inferred.
  Annotations extend the **primary** axis only, because an annotation has no way
  to say which axis it is stated in
- `compact()` suffixes (`K`/`M`/`B`) are not localised
- `TimeScale` ticks use fixed durations, not calendar arithmetic: a month step
  is approximated at 30 days and a year at 365. Right for positioning a tick on
  a proportional axis; wrong for asserting "the first of the month"
- Colour-scale interpolation is component-wise in sRGB and is not perceptually
  uniform, so a ramp between two distant hues passes through a desaturated
  middle. The theme's own ramps stay within one hue
- A violin's kernel spreads density past the extremes of its sample, so a
  strictly non-negative quantity shows a little density below zero. The curve is
  bounded at three bandwidths beyond the data; the effect is reduced, not removed
- The generic `ChartAggregation.Average` and `MinMax` pick representative
  samples rather than computing arithmetic, because the adapter has no numeric
  view of `T`. `ChartAggregation.Custom` is exact
- `ChartWindow.Unbounded` is bounded at 200,000 values — a chart that grows
  until the process dies is not a feature
- Automatic histogram binning is capped at 512 bins; below that a rule applied
  to tightly clustered data can ask for tens of thousands
- Hierarchies are capped at 24 levels by default and cycles are cut rather than
  followed. Both are counted on the result, not hidden
- A Sankey diagram cannot lay out a cycle: the links closing one are cut
  deterministically and counted. `SankeyValidation.Reject` throws instead
- Downsampling applies to line and area layers. Scatter is culled to the
  viewport but never sampled
- 100% stacked charts normalise over absolute values; designed for
  non-negative data
- A data change animates only when the series ids and point counts are
  unchanged; anything else snaps
- The dark/light palette is chosen from the *system* setting, so an app forcing
  one theme against the system gets a palette tuned for the other. Pass
  `materialDerivedChartColors(isDark = …)` explicitly in that case
- Toggling a series through the legend hides or shows it immediately; it does
  not fade in or out
- A polar, treemap or flow legend is display-only. Hiding one slice of a
  part-to-whole chart would renormalise the rest, so the remaining shares would
  become percentages of a different total — a different chart, not a filtered one
- Labels that do not fit are skipped rather than repositioned — outside slice
  labels, radar spokes, treemap tiles, sunburst arcs, Sankey nodes and interval
  bars. Nothing is shrunk or ellipsised, so what survives is legible, but a
  crowded chart labels fewer marks than it has
- A captured image does not include a tooltip or dropdown drawn in a `Popup` or
  `Dialog`, because those are separate windows
- **ChartKit ships no geography.** `ChoroplethMap` draws the boundaries you give
  it and nothing else; there is no bundled world, country or county file
- A geographic bounding box may not wrap past ±180°. A region genuinely
  straddling the antimeridian — Fiji, Chukotka, a Pacific-centred world map —
  produces a box spanning nearly the globe, and the map draws correspondingly
  zoomed out. Handling the wrap properly means a second longitude convention
  running through the projection, the fit, the viewport and the hit test, and
  getting it half right would draw the region in two places
- A map label sits at the **area centroid**, which for a crescent or a strongly
  concave region is not inside the shape. The fix is a pole of inaccessibility,
  which is a different, iterative algorithm
- `GeoLabels.Auto` skips a label that does not fit its region rather than
  shrinking, rotating or ellipsising it. There is no label-placement engine, so
  a dense map labels fewer regions than it has
- Mercator clamps latitude at ±85.0511°, so a polygon crossing the clamp is
  drawn with a flat edge along it — Antarctica on a Mercator map is that edge
- `GeoJson.parse` reads `Polygon`, `MultiPolygon`, `Point` and `MultiPoint`.
  `LineString` and `MultiLineString` are skipped and reported, because a
  choropleth cannot shade a line
- Geographic hit testing and projection run on the calling thread. A very large
  boundary file is parsed and projected where you call it, so parse it off the
  main thread and hold the result
- Proportional-symbol maps, cartograms, flow maps and dot-density maps are not
  drawn. `Point` and `MultiPoint` geometry is parsed and carried, and is used
  for label placement, but nothing renders a sized marker from it yet
- **Area-proportional set diagrams are approximations, and the size of the
  approximation is reported.** Three circles have six degrees of freedom against
  a three-set system's seven quantities; four circles cannot produce all fifteen
  regions at all. `SetLayoutQuality` carries the mean and worst area error, the
  combination the worst belongs to, and the fraction of asked-for regions
  actually drawn
- A four-set Venn uses four congruent **ellipses**, because no four circles
  produce fifteen regions. Five sets and above use an ellipse rosette whose
  region coverage is **measured and reported** rather than assumed; above about
  five sets a Venn diagram has regions too thin to label or tap, and the right
  answer is a different visualisation
- An Euler layout **grows a parent to hold its children**, so a set that contains
  another is drawn at least as large as its contents even when its cardinality
  asks for less. The alternative — shrinking children — would make a nested set
  smaller than an unrelated set of the same size, which is worse. The resulting
  proportionality error is included in `SetLayoutQuality`
- Not every set system can be drawn exactly with circles and ellipses. A set
  buried in one branch that must overlap a set in another is placed by a bounded
  coarse search followed by a bounded pattern search; where the arrangement
  matters more than the arithmetic, `SetDiagramLayout.Custom` takes the shapes
  directly
- An unstated intersection is read as **empty**, not as unknown. Nested data
  should be declared with `SetContainment` rather than by writing out the
  combinations it implies
- Two sets that share every item are indistinguishable by cardinality and are
  drawn coincident. Which of them is on top is not defined; label them to tell
  them apart
- Set-diagram labels are skipped when they do not fit their region rather than
  being shrunk, rotated or ellipsised. There is no label-placement engine, so a
  dense diagram labels fewer regions than it has
- `SetAnalyzer.fromItems` is capped at 20 collections, because the intersection
  lattice is exponential in the number of sets. The number of *items* is not
  capped
- Set-diagram layout runs on the calling thread. It is bounded by
  `SetLayoutConfig.maxIterations` and cached against the data, so it happens once
  per data change rather than per frame; there is no background solver

## Roadmap

- Polar-area layers, and zoom over a polar angle
- Stacked areas
- Interactive range handles on a chart's own range selection
- Fling panning
- Completing vector export: a scene representation for the remaining layers
- A Barnes–Hut force layout, for graphs beyond the current cap
- A spatial index better suited to extreme clustering than a uniform grid
- Proportional-symbol overlays on a choropleth, from the `Point` geometry that
  is already parsed
- A pole-of-inaccessibility label point, for concave regions
- Curved dial labels, and a linear (thermometer) gauge over the same
  `GaugeScale`, which is a renderer rather than a model
- UpSet plots, over the `SetDefinition` / `SetIntersection` / `SetAnalyzer`
  model that already exists — a different layout, not a different model
- Path-based set shapes, for Euler systems circles and ellipses cannot represent
- A top X axis, and independent X domains, over the `AxisDimension` the registry
  already models
- Per-axis Y zoom — `None`, `PrimaryAxis`, `AllAxes`, `SpecificAxis` — once
  there is a gesture that means it
- Stabilising the `CartesianChart` layer DSL and the custom-layer API, and
  dropping the experimental marker
- Benchmark coverage, if the repository grows benchmarking infrastructure
- Compose Multiplatform, if DevKit adopts KMP

## Testing

```bash
./gradlew :chartkit:testDebugUnitTest          # 1,025 JVM tests
./gradlew :chartkit:connectedDebugAndroidTest  # 229 Compose UI tests
```

| Suite | Covers |
| --- | --- |
| `ScaleTest` | Linear, category and time mapping; inversion; clamping; degenerate domains |
| `LogScaleTest` | Base 10 and 2, decade spacing, inversion, tick subdivision and striding, zero and negative policies, domain lifting |
| `SymlogScaleTest` | Symmetry, the linear region, continuity at the threshold, decade spacing in the tails, inversion, ticks either side of zero |
| `TickGeneratorTest` | Round steps, negatives, small decimals, large values, constants, absurd counts |
| `BarGeometryTest` | Stacking, sign separation, percent normalisation, rectangles, orientation, reveal, corners |
| `LineGeometryTest` | Segmentation, monotone overshoot, binary search, ordering |
| `PolarGeometryTest` | Angle convention, wrap-around, slice normalisation, invalid values, gaps, hit testing, donut holes |
| `RadialGeometryTest` | Value-to-sweep mapping, custom ranges, out-of-range policy, concentric track lookup |
| `GaugeScaleTest` | Ends at the sweep's ends, midpoints, round trips, negative and offset domains, three-quarter and full sweeps, counter-clockwise, angle pairs, clamping without rewriting the value, overflow, reject, non-finite readings, every invalid domain and sweep, an angle off the arc reading as nothing, tolerance at the ends, snapping |
| `GaugeGeometryTest` | Arc extents for full, half, three-quarter and compass-crossing sweeps, excluding the centre, fitting a semicircle to a wide box and a full circle to the same one, the label reserve, empty boxes; needle direction, length, tail, tip shape and finiteness for every shape; marker outlines |
| `GaugeTickTest` | Explicit intervals, round automatic ticks, ends always marked, `includeEnd` off, subdivisions never duplicating a major, explicit minor intervals, tiny/huge/negative/offset ranges, a full circle not double-marking one angle, labels thinning while ticks stay, uniform label stride, hard caps, density growing with the dial, subdivisions too fine to draw |
| `GaugeBandTest` | Bands as arcs, tiling without gaps, trimming and flagging, dropping and reporting, zero width, backwards bounds, every overflow policy, overlap allowed and rejected, touching bands, the status lookup, palette slots surviving a dropped band, invalid styles |
| `RadarGeometryTest` | Spoke placement, start angles, vertex radii, angle round-trips |
| `HierarchyTest` | Normalisation, depth, paths, parents, aggregation, value conflicts, negative values, duplicate keys, forests, self-references, longer cycles, equal-but-distinct siblings, depth truncation |
| `TreemapLayoutTest` | Bounds conservation, area sums, per-tile proportionality, non-overlap, single item, zero values, aspect ratios against slicing, nesting containment |
| `SunburstLayoutTest` | Angle allocation, full-circle sums, ring depth, parent–child containment, ring radii, hit testing, the centre belonging to no arc, drill-down |
| `SankeyGraphTest` | Column assignment by longest path, throughput, unknown references, self-links, invalid weights, cycle cutting and its determinism, neighbours |
| `SankeyLayoutTest` | Box and band counts, column spacing, height proportionality, band thickness, bounds, stability across runs, band hit testing |
| `FunnelTransformTest` | Share of first, conversion, drop-off in both forms, increasing stages, zero stages, monotonicity, overall conversion |
| `FunnelGeometryTest` | Band division, stacking, zero stages, the neck, horizontal orientation |
| `WaterfallTransformTest` | Running totals, bar anchoring, kind over sign, subtotals, totals, the sign helper, extents including zero and below it, non-finite values |
| `ChartGraphTest` | Node order and identity, degree, symmetric adjacency, unknown endpoints, self-edges, the caller's object |
| `CircularLayoutTest` | Placement, unit bounds, even spacing, settling, reproducibility, degree ordering, single node, mapping into a rectangle |
| `ForceLayoutTest` | Seeded determinism, seed sensitivity, bounds, non-coincidence, settling, connected nodes ending closer, pinning, unsettling on drag, release, snapshot isolation, the simulation cap |
| `SetAnalyzerTest` | Exclusive regions against inclusion–exclusion in both directions, totals against exclusives, order independence, unstated combinations, relationships, containment closure including the downward rule, transitivity, cycles, every validation rule, lenient mode, collections, duplicate keys as sets rather than multisets, and that the two input routes agree |
| `SetLayoutTest` | Region counts for one to five sets, the four-ellipse construction, measured coverage above four, proportional sizing by area, solved overlaps, determinism, Euler nesting and disjointness, three-level nesting, cross-branch overlap, identical sets, the aspect-ratio-preserving fit, membership by geometry, anchors inside their own regions, custom arrangements, the iteration ceiling |
| `SetGeometryTest` | Lens areas against known geometry, tangency and coincidence without NaN, containment, symmetry, monotonicity, solving for a distance, ellipse containment and rotated bounds, sampled against closed-form areas, and hit testing for every region of a three-set diagram |
| `SetPresentationTest` | Order-independent and deterministic blending, region naming and its replaceable words, the regions table and its shares, and the relationship table saying nothing the labels did not earn |
| `SetPerformanceTest` | Two, three, four and five sets, a complex nested Euler and a conceptual icon-group diagram: solver budgets, analytic arrangements needing no solve, re-fitting without re-solving, twenty thousand items, and deterministic sampling |
| `GeoJsonTest` | Feature collections, bare features and bare geometries, properties, numeric keys as strings, closing points, holes, multi-polygons, points, unsupported geometry skipped and reported, the reject policy, malformed JSON, escapes and Unicode, degenerate rings, bounds |
| `GeoProjectionTest` | Equirectangular identity and inversion, standard parallels, Mercator growth and pole clamping, non-finite input, the fit's single scale, the y flip, padding, exact screen/projected inversion, visible extent under zoom, fit-and-centre on a target |
| `GeoGeometryTest` | Ray casting, concave rings, shared borders belonging to one region, holes, area centroids against vertex density, largest-component labels, simplification and its stack safety, projected geometry, the index against a brute-force scan, multi-polygon selection |
| `GeoJoinTest` | Feature-order rows, unmatched keys on both sides, missing against zero, non-finite values, every duplicate policy, quantile breaks and their nearest-rank definition, tie collapsing, missing colours, band labels |
| `GeoViewportTest` | Zoom clamping, pan bounds by overhang, focal-point anchoring, reset, non-finite gestures, focus requests consumed once |
| `GeoPerformanceTest` | 50 / 200 / 1,000-region fixtures: correct selection throughout, index narrowing, single index build, dense boundaries and simplification |
| `TimelineModelTest` | Lane order, point events, intervals, inverted intervals, overlap stacking, row numbering, extents, progress clamping, milestones, dependencies |
| `NavigatorViewportTest` | Window placement, sliding rather than shrinking at both ends, recentring, over-wide windows, edge resizing |
| `PlotAlignmentTest` | Agreeing on the largest gutter, per-chart differences, idempotent re-reporting, forgetting a departed chart |
| `ChartFilterStateTest` | Toggling, single and multi select, dimension independence, and/or combination, null keys, unfiltered dimensions, source identity |
| `ChartSceneTest` | Scene size, completeness, flattening, unexported reporting, well-formed XML, dimensions and view box, scaling, every primitive, named groups, escaping, separate opacity, titles, empty scenes |
| `ViewportTest` | Zoom in and out, focal-point preservation, limits, pan clamping, reset, domain and category conversion |
| `NormalizationTest` | Axis inference, missing values, ordering, visibility, palette slots, duplicate ids |
| `LayoutAndAxisTest` | Gutters, titles, overhang, squeezed plots, label thinning |
| `AxisRegistryTest` | Registration in both dimensions, lookup, duplicate ids, a missing axis naming the layer and what exists, no silent fallback to the primary, dimension mismatches, illegal edges on both orientations, two primaries, primary by declaration and by declaration order, stacking order per side, blank ids, the legacy binding's mapping |
| `AxisDomainTest` | Three domains mapping into one plot extent, and one value landing on a different row per axis |
| `AxisAlignmentTest` | Shared tick counts, ticks on identical screen rows, round values after alignment, still covering the data, zero on one row, zero as a real tick, declining rather than flattening an axis and saying which and why, exact interval fitting, independent ticks untouched, the nice-number ladder |
| `MultiAxisLayoutTest` | Stacking outward by the previous gutter, non-overlap, opposite sides both at the plot edge, the plot shrinking per axis, hidden axes taking no gutter, the explicit offset override, wider labels reserving wider gutters, unnamed axes |
| `ChartUnitTest` | Symbols and spoken forms, percent's spacing, counts adding nothing, unstated units never mismatching, two stated units that differ |
| `AxisDensityTest` | Full and Compact overriding, one axis never cramped, three axes on a phone against a tablet |
| `AxisGridOwnershipTest` | Primary-only by default, a secondary axis opting in, the primary opting out |
| `AxisVisibilityTest` | Auto hiding an axis with nothing on it, Visible keeping one, Hidden winning, a config-hidden axis staying hidden |
| `TooltipOrderTest` | Declaration order, by-axis grouping, unlisted axes sorting last, a custom comparator, an entry's own text |
| `FormatterTest` | Locale behaviour, compaction, percent, currency, dates, time zones |
| `AccessibilityAndPaletteTest` | Summary content, absence of statistical claims, palette separation, HSL round-trip |
| `AdvancedAccessibilityTest` | Per-mark phrasing, large-series range fallback, absence of interpretation |
| `SelectionModelTest` | Shared selection shape across coordinate systems, tooltip data, range model, interaction config |
| `CoordinatesAndOverlayTest` | Orientation mapping, baselines, overlay placement and flipping |
| `MissingValuePolicyTest` | That `Break`, `Connect` and `Zero` genuinely differ |
| `StatisticsTest` | Quartiles against the documented method, medians, IQR, outliers, whiskers, standard deviation, empty and constant samples, non-finite filtering |
| `HistogramTest` | Every bin strategy, boundary rules, metrics, constant and negative data, source-index mapping, caps |
| `DensityTest` | Finiteness, unit area, peak position, degenerate samples, bandwidth rules, determinism |
| `ScaleExtensionsTest` | Size scaling by area against radius, clamping; colour scale banding, ramps, quantization, missing values |
| `HeatmapGridTest` | Grid construction, input order, missing against zero, source indices |
| `CalendarGridTest` | Time-zone-correct epoch days, locale week starts, week and month boundaries, label collisions |
| `ScatterIndexTest` | Nearest-point search against a brute-force scan, tolerances, out-of-plot points |
| `AnnotationTest` | Domain resolution per axis kind, ordering defaults, axis widening, stable ids |
| `FinancialTest` | Direction, change, OHLC repair, skip and reject policies, dropped periods, volume, median period width |
| `MovingAverageTest` | Warm-up, alignment, gap handling for both averages |
| `DownsamplingTest` | Endpoint preservation, budgets, ordering, spike preservation, shape preservation, source immutability, strategy resolution |
| `VisibleRangeTest` | Binary search at every edge case, overscan, ordering detection |
| `LargeSeriesGeometryTest` | Culling and sampling end to end, and that the source lists stay whole |
| `ChartStreamBufferTest` | Ring-buffer eviction, snapshots, resizing |
| `StreamingTest` | Count and duration windows, throttle, batch, aggregation, backpressure, pause — on virtual time |
| `ChartDataTableTest` | Series, OHLC and box-plot adapters; missing values; text form |
| `LargeDatasetTest` | 1,000 / 5,000 / 10,000-point behaviour |
| `ChartRenderingTest` | Every Cartesian chart type, edge-case datasets, states, animated frames |
| `ChartAdvancedRenderingTest` | Scatter, bubble, histogram, box plot, violin, heatmap, calendar, radar, candlestick, OHLC, volume and every annotation kind — including empty datasets |
| `ChartPlatformRenderingTest` | Treemap, sunburst, breadcrumbs, Sankey (including a cyclic one), funnel, waterfall, dumbbell, lollipop, bullet, gauge, timeline, range, Gantt, network graph in both layouts, custom layers, second axes, log and symmetric-log axes, the advanced annotations, static mode and scene production |
| `ChartInteractionTest` | Tap, scrub, tooltips, hoisted state, legend toggling |
| `ChartPolarTest` | Pie and donut selection by angle, donut holes, centre content, radial track selection, invalid values, polar semantics |
| `ChartGaugeDialTest` | A dial from one value; every shape; every feature at once; a non-finite reading; three sizes and a very wide box; the needle travelling rather than jumping, retargeting mid-flight without going backwards, and settling at once when animation is off; a display ignoring taps; a tap reading a value off the arc; a tap off the sweep reporting nothing; a drag moving the value; step snapping; the current band and an out-of-range reading reported as itself; several needles drawn and legended, one needle not legended, needles animating independently; label, value, unit, range and caller-supplied band names announced; an unlabelled band adding no meaning; range info, the set-progress action and a screen reader adjusting the dial; a display publishing none of it; the arc gauge unchanged and sharing the band model; the readout clear of the pivot |
| `ChartViewportInteractionTest` | Pinch zoom, pan, clamping, reset, crosshair, shared tooltips, range selection in both directions |
| `ChartLinkedInteractionTest` | Shared viewport, shared crosshair, independent value scales, opt-in isolation |
| `ChartSemanticsAndThemeTest` | Announcements, custom summaries, theme precedence, light and dark |
| `ChartSetDiagramTest` | Two, three and four sets drawing; empty, coincident and tangent geometry; nested Euler; selection of exclusive, pairwise and triple regions; clearing outside; empty theoretical regions; static mode; the tooltip; custom set labels, region labels, region content and icon groups; reported clearance; colour modes leaving the data alone; explicit intersection colours; focus dimming; the generic API and custom arrangements; collection-driven counting; the data table; the animated reveal |
| `ChartMultiAxisTest` | Three axes drawn at three offsets, each labelled in its own unit and its own values; every series inside one plot area and none of them flattened; the plot shrinking as axes are added; compaction thinning ticks without dropping an axis; aligned ticks on identical rows; grid ownership; an axis hiding when its last series is hidden and giving back its gutter; legend toggling; a shared tooltip with a row per axis in its own unit and its own screen position; by-axis row ordering; selection resolving through the right axis; panning; an annotation drawn on its own axis' scale; an unregistered axis and cross-axis stacking failing; a unit mismatch reported and drawn; single-axis and legacy-secondary-axis charts unchanged; announcements naming axes and units and never ids |
| `ChartGeoTest` | A choropleth drawing under both projections, empty geometry, labels, the colour legend and its "no data" swatch, join reporting including a key mismatch, selection by region, unmeasured regions, clearing outside the geography, static mode taking no input, camera zoom/pan/reset, immediate tap selection, the tooltip, the data table |

## Licence

Apache-2.0. See [LICENSE.md](../LICENSE.md) and [NOTICE](../NOTICE).
