# ChartKit

Compose-native data visualisation for Android. Line, area, bar, scatter,
bubble, histogram, box plot, violin, heatmap, calendar heatmap, candlestick,
OHLC and volume on a Cartesian coordinate system; pie, donut, radial bar and
radar on a polar one — all on **one engine**, sharing scales, layout, layers,
viewport, interaction, animation, theming, overlays and accessibility.

It also handles the parts that decide whether a chart survives real data:
annotations, linked charts, viewport culling and downsampling for datasets in
the tens of thousands, and a Flow adapter for live streams.

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
KoalaPlot — a wrapper inherits somebody else's data model, view interop and
theming, which is the opposite of the point.

## Contents

- [Install](#install) · [Requirements](#requirements) · [Run the sample](#run-the-sample)
- Cartesian charts: [Line](#line-chart) · [Area](#area-chart) · [Bar](#bar-chart) · [Horizontal](#horizontal-bars) · [Grouped](#grouped-bars) · [Stacked](#stacked-bars) · [100% stacked](#100-stacked-bars) · [Multi-series](#multiple-series) · [Combined](#combined-charts)
- Polar charts: [Pie](#pie-chart) · [Donut](#donut-chart) · [Radial bar](#radial-bar-chart) · [Radar](#radar-chart) · [Polar coordinates](#polar-coordinates)
- Statistical: [Scatter](#scatter-chart) · [Bubble](#bubble-chart) · [Histogram](#histogram) · [Box plot](#box-plot) · [Violin](#violin-plot) · [Statistics API](#statistics-api)
- Density: [Heatmap](#heatmap) · [Calendar heatmap](#calendar-heatmap) · [Colour scales](#colour-scales)
- Financial: [Candlestick](#candlestick-chart) · [OHLC](#ohlc-chart) · [Volume](#volume-chart) · [Linked charts](#linked-charts)
- [Annotations](#annotations)
- Configuration: [Axes](#axes) · [Grid](#grid-lines) · [Formatting](#formatting) · [Legends](#legends) · [Value labels](#value-labels)
- Interaction: [Interaction modes](#interaction-modes) · [Selection](#selection) · [Scrubbing](#scrubbing) · [Crosshair](#crosshair) · [Zoom and pan](#zoom-and-pan) · [Range selection](#range-selection) · [Tooltips](#tooltips) · [State](#hoisted-state)
- Scale: [Large datasets](#large-datasets) · [Downsampling](#downsampling) · [Streaming](#streaming)
- Presentation: [Theming](#theming) · [Animation](#animation) · [Accessibility](#accessibility) · [Data tables](#data-tables) · [Capture](#capture) · [Loading, empty and error](#loading-empty-and-error) · [Sizing](#sizing)
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
    implementation(platform("io.github.ezekielwachira.devkit:devkit-bom:0.1.0"))
    implementation("io.github.ezekielwachira.devkit:chartkit")
}
```

Or as part of the release-safe umbrella:

```kotlin
dependencies {
    implementation("io.github.ezekielwachira.devkit:devkit:0.1.0")
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

Heatmap(data = activity, x = { it.day }, y = { it.hour }, value = { it.requests },
    colorScale = severity)
```

Explicit colours where the theme is not the source:

```kotlin
ColorScale.Continuous(NumericDomain(0.0, 1.0), listOf(Color.White, Color.Blue))
ColorScale.Threshold(thresholds = listOf(50.0), colors = listOf(Color.Green, Color.Red))
ColorScale.Quantized(domain, listOf(low, high), steps = 4)
ColorScale.Categorical(keys = listOf("ok", "down"), colors = listOf(green, red))
```

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
needs and `0..5` does not waste half the width.

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
├── layout       ChartLayoutEngine → Cartesian plot area (axis gutters)
│                                  → polar plot area (largest centred square)
├── viewport     ChartViewport · ChartZoomLimits
├── stream       ChartWindow · ChartUpdatePolicy · ChartAggregation
│                ChartStreamBuffer (ring) · ChartStreamCollector
├── animation    reveal fraction · value interpolation · selection emphasis
├── theme        ChartKitTheme → ChartColors / ChartTypography / ChartDimensions
│                nested: financial · heatmap · statistical · annotation
├── formatter    ChartValueFormatter · ChartTimeFormatter and built-ins
├── annotation   ChartAnnotation · AnnotationStyle · builders
├── accessibility factual summaries, selection, viewport, range · ChartDataTable
├── capture      ChartCaptureState · Modifier.chartCapture
└── state        ChartState<T> · ChartViewportState
                 ChartSharedCrosshairState · ChartInteractionGroup

Coordinates
├── CartesianCoordinates   DomainAxis + value scale + orientation
└── PolarCoordinates       centre + inner/outer radius + start/sweep + direction

Layers
├── Cartesian   grid · line (line + area + points) · bar · histogram
│               scatter (scatter + bubble) · box plot · violin · heatmap
│               candle (candlestick + OHLC) · volume · value labels
│               crosshair · range selection · annotations (behind and above)
└── Polar       slice (pie + donut) · radial bar · radar web · radar

Interaction
└── ChartGestureCoordinator   tap · scrub · pan · pinch · range, arbitrated once
    ChartInteraction · ChartDragMode · CrosshairConfig · HitTestMode

Overlay
└── ChartOverlay              measured placement for tooltips and custom content

High-level charts
├── LineChart · AreaChart · BarChart · HorizontalBarChart · CartesianChart
│   ScatterChart · BubbleChart · Histogram · BoxPlot · ViolinPlot
│   Heatmap · CalendarHeatmap · CandlestickChart · OhlcChart · VolumeChart
└── PieChart · DonutChart · RadialBarChart · RadarChart
```

Every Cartesian chart reduces to one call into `CartesianChartCore`, and every
polar chart to one call into `PolarChartCore`. The two cores differ in exactly
three things — the layout call, the coordinate construction and the hit test.
Everything else is the same code.

**A line chart is** a Cartesian chart + a line layer + optional points + axes +
grid. **A bar chart is** a Cartesian chart + a bar layer. **A horizontal bar
chart is** the same bar layer with the orientation flipped. **A bubble chart is**
a scatter whose radius comes from a `SizeScale`. **An OHLC chart is** a
candlestick chart with a different mark style. **A histogram is** a bin layer on
a continuous domain. **A pie chart is** a polar chart + a slice layer. **A donut
is** a pie with an inner radius. **A radar chart is** a polar chart + a web layer
+ a polygon layer.

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

### Room to grow

The architecture was built for a second coordinate system, and then got one:
`PolarCoordinates` is a sibling of `CartesianCoordinates` under the same
`CoordinateSystem` interface, and adding it changed nothing in the layer model,
the selection model, the overlay, the animation clock, the theme or the
accessibility layer. Radar then cost two layers on top of it and no new
coordinate system at all.

`ChartLayerRenderer` is small and defaulted, so a candlestick, a violin, a
heatmap or an annotation rule is a new implementation rather than a change to
the coordinate system, the layout engine or the interaction model. The
crosshair, the range overlay and both annotation layers are ordinary layers,
which is why they work on every Cartesian chart rather than on the one they were
written for.

## Performance

**Drawing primitives, not composables.** Paths, bars, arcs, cells, candles,
markers, grid lines and axes are `DrawScope` calls. Composables are used for the
tooltip, the legend, a donut's centre content and custom overlays — the things
that have to measure text and take input. There is no composable per point, per
cell, per slice or per candle.

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

## Current limitations

Stated plainly, because a roadmap read as a feature list is how a library gets
adopted for something it cannot do.

**Not supported:**

- Sankey, sunburst, treemap, funnel, network graphs, geographical maps
- Polar-area charts, and stacked **areas** — multi-series areas overlap, each
  measured from the baseline
- Secondary value axes — the architecture supports them; the API does not
  expose them
- Interactive range **handles**: a range is dragged out afresh rather than
  resized by its edges
- Zoom and pan on polar charts. Pie, donut, radial bar and radar take tap
  selection and tooltips only; a viewport over an angle is a different
  interaction, not a reuse of this one
- Y-axis zoom. The viewport narrows the domain axis only
- Fling/inertial panning — a drag pans directly and stops when it stops
- Keyboard chart exploration (selection is architected for it; not wired)
- Technical indicators beyond simple and exponential moving averages
- Spatial indexing beyond a uniform grid — a k-d tree or an R-tree would beat it
  for a scatter with extreme clustering
- GPU / `RenderNode` rendering
- Screenshot/golden tests, and benchmarks — the repository has no such
  infrastructure
- Compose Multiplatform targets — the pure logic is portable, the module is not

**Known behavioural limits:**

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
- A polar legend is display-only. Hiding one slice of a part-to-whole chart
  would renormalise the rest, so the remaining shares would become percentages
  of a different total — a different chart, not a filtered one
- Outside slice labels and radar spoke labels are skipped rather than
  repositioned when they do not fit. Nothing is shrunk or ellipsised, so what
  survives is legible, but a crowded pie will label fewer slices than it has
- A captured image does not include a tooltip or dropdown drawn in a `Popup` or
  `Dialog`, because those are separate windows

## Roadmap

- Polar-area layers, and zoom over a polar angle
- Stacked areas
- Secondary value axes
- Interactive range handles
- Fling panning and keyboard chart exploration
- A spatial index better suited to extreme clustering than a uniform grid
- Stabilising the `CartesianChart` layer DSL and dropping the experimental marker
- Benchmark coverage, if the repository grows benchmarking infrastructure
- Compose Multiplatform, if DevKit adopts KMP

## Testing

```bash
./gradlew :chartkit:testDebugUnitTest          # 495 JVM tests
./gradlew :chartkit:connectedDebugAndroidTest  # 104 Compose UI tests
```

| Suite | Covers |
| --- | --- |
| `ScaleTest` | Linear, category and time mapping; inversion; clamping; degenerate domains |
| `TickGeneratorTest` | Round steps, negatives, small decimals, large values, constants, absurd counts |
| `BarGeometryTest` | Stacking, sign separation, percent normalisation, rectangles, orientation, reveal, corners |
| `LineGeometryTest` | Segmentation, monotone overshoot, binary search, ordering |
| `PolarGeometryTest` | Angle convention, wrap-around, slice normalisation, invalid values, gaps, hit testing, donut holes |
| `RadialGeometryTest` | Value-to-sweep mapping, custom ranges, out-of-range policy, concentric track lookup |
| `RadarGeometryTest` | Spoke placement, start angles, vertex radii, angle round-trips |
| `ViewportTest` | Zoom in and out, focal-point preservation, limits, pan clamping, reset, domain and category conversion |
| `NormalizationTest` | Axis inference, missing values, ordering, visibility, palette slots, duplicate ids |
| `LayoutAndAxisTest` | Gutters, titles, overhang, squeezed plots, label thinning |
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
| `ChartInteractionTest` | Tap, scrub, tooltips, hoisted state, legend toggling |
| `ChartPolarTest` | Pie and donut selection by angle, donut holes, centre content, radial track selection, invalid values, polar semantics |
| `ChartViewportInteractionTest` | Pinch zoom, pan, clamping, reset, crosshair, shared tooltips, range selection in both directions |
| `ChartLinkedInteractionTest` | Shared viewport, shared crosshair, independent value scales, opt-in isolation |
| `ChartSemanticsAndThemeTest` | Announcements, custom summaries, theme precedence, light and dark |

## Licence

Apache-2.0. See [LICENSE.md](../LICENSE.md) and [NOTICE](../NOTICE).
