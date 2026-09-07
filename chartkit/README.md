# ChartKit

Compose-native data visualisation for Android. Line, area, bar, horizontal,
grouped, stacked and 100% stacked charts — all on one Cartesian engine, with
shared scales, axes, layout, interaction, animation, theming and accessibility.

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
- Charts: [Line](#line-chart) · [Area](#area-chart) · [Bar](#bar-chart) · [Horizontal](#horizontal-bars) · [Grouped](#grouped-bars) · [Stacked](#stacked-bars) · [100% stacked](#100-stacked-bars) · [Multi-series](#multiple-series) · [Combined](#combined-charts)
- Configuration: [Axes](#axes) · [Grid](#grid-lines) · [Formatting](#formatting) · [Legends](#legends) · [Value labels](#value-labels)
- Interaction: [Selection](#selection) · [Scrubbing](#scrubbing) · [Tooltips](#tooltips) · [State](#hoisted-state)
- Presentation: [Theming](#theming) · [Animation](#animation) · [Accessibility](#accessibility) · [Loading, empty and error](#loading-empty-and-error) · [Sizing](#sizing)
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
| Chart gallery | Every 0.1 chart type, with live toggles for grid, points, legend, labels, smoothing and animation |
| Chart interaction | Tap selection, scrubbing, a custom tooltip, legend toggling |
| Chart theming | `ChartKitTheme` overrides, dark mode, compact/currency/percent/date formatting |
| Chart states | Loading, empty and error slots; single-point, constant and gapped datasets; 5,000 points; accessibility semantics |

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

## Tooltips

The default tooltip shows the series, the x value and the value. Replace it
wholesale:

```kotlin
LineChart(
    ...,
    tooltip = { selection ->
        Card {
            Column(Modifier.padding(10.dp)) {
                Text(selection.item.month, fontWeight = FontWeight.SemiBold)
                Text(money.format(selection.item.amount))
            }
        }
    },
)
```

Or wrap the default rather than rewriting it:

```kotlin
tooltip = { ChartDefaults.Tooltip(it, showSeriesName = false) }
```

Placement is **measured**: preferred above the anchor, flipped below when there
is no room, and pulled back inside the plot horizontally — so it never leaves
the chart at the first or last point, which on a rising series is the most
interesting one.

`tooltip = null` disables it and leaves the selection callback working.

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

The current selection is announced through a polite live region as it changes.

Replace the whole description when you have better context:

```kotlin
accessibilitySummary = {
    "Revenue by month. Highest in May at 44,100; lowest in January at 24,000."
}
```

`ChartAccessibility.Concise` keeps the title, the series names and the counts
but drops the value list.

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
CartesianChartCore              one engine, every chart
├── model      ChartSeries<T>, ChartX, ChartXResolver, PlotData, ChartSelection<T>
├── scale      LinearScale, CategoryScale, TimeScale, NumericDomain, DomainPolicy, TickGenerator
├── coordinate CoordinateSystem → CartesianCoordinates (DomainAxis + value scale + orientation)
├── layout     ChartLayoutEngine: bounds − axis gutters − label overhang → PlotArea
├── axis       ChartAxis, AxisPosition, measurement, label thinning, rendering
├── layer      grid · line (line + area + points) · bar · value labels · selection
├── interaction ChartSelectionMode, HitTestMode
├── animation  reveal fraction + value interpolation
├── theme      ChartKitTheme → ChartColors / ChartTypography / ChartDimensions
├── formatter  ChartValueFormatter, ChartTimeFormatter and built-ins
├── state      ChartState<T>
└── accessibility factual summaries and selection announcements
```

`LineChart`, `AreaChart`, `BarChart`, `HorizontalBarChart` and `CartesianChart`
all reduce to one call into `CartesianChartCore`. There is one plot area, one
pair of scales, one hit-testing pass and one animation clock in the library.

**A line chart is** a Cartesian chart + a line layer + optional points + axes +
grid. **A bar chart is** a Cartesian chart + a bar layer + axes + grid. **A
horizontal bar chart is** the same bar layer with the orientation flipped.

### Portability

Everything in `model`, `scale`, `geometry`, `layout` and `formatter` is plain
Kotlin — no Compose, no `android.graphics`. `ChartOffset`, `ChartRect` and
`ChartInsets` exist instead of `Offset`, `Rect` and `PaddingValues` so the
arithmetic is testable on the JVM without Robolectric and could move to Compose
Multiplatform without unpicking Android types from the maths. The Compose layer
converts at the boundary.

### Room to grow

`CoordinateSystem` is an abstraction *over* Cartesian, not a synonym for it, so
a polar system for pie, donut, radar and radial bar is a sibling rather than a
rewrite. `ChartLayerRenderer` is small and defaulted, so an annotation rule, an
event marker or a candlestick is a new implementation and not a change to the
coordinate system, the layout engine or the interaction model.

## Performance

ChartKit 0.1 does not downsample and does not claim a million points. What it
does claim is that nothing degrades catastrophically as the dataset grows.

**Drawing primitives, not composables.** Paths, bars, grid lines, axes and
points are `DrawScope` calls. Composables are used for the tooltip, the legend
and custom overlays — the things that have to measure text and take input.
There is no composable per point, per grid line or per line segment.

**Geometry is cached against its inputs.** Scales, ticks, interpolated paths and
bar rectangles are computed inside a `remember` keyed on the data, the measured
size, the theme, the locale and the axis configuration. A tooltip appearing, a
selection moving or an animation frame ticking does not re-derive the domain of
ten thousand points. Line paths are cached again inside the layer against the
plot rectangle.

**Reveal is a clip, not a rebuild.** Animating a 10,000-point line does not
re-interpolate its curve every frame.

**Hit testing is sublinear where it can be.** Bars are a rectangle test; line
points use binary search when the series is x-ordered — 14 comparisons over
10,000 points against 10,000 for a scan, on every pointer move during a scrub.

```kotlin
ChartPerformance(
    pointMarkerThreshold = 40,   // Auto stops drawing markers past this
    maxAnimatedPoints = 500,     // above this, a data change snaps
)
```

`maxAnimatedPoints` is a stated trade-off: interpolating a data change rebuilds
the path every frame of the transition, which is affordable for a few hundred
points and not for tens of thousands — and the animation is the part worth
losing.

**Measured behaviour.** 1,000 / 5,000 / 10,000-point line series render and
scrub without pathology; the sample's Chart states screen draws 5,000 points.
`LargeDatasetTest` asserts that normalisation, scaling, segmentation and nearest-
point search stay proportionate, and that binary search actually beats a scan.
These are sanity bounds, not benchmarks: the repository has no benchmarking
infrastructure, and standing one up for a single library would have been a
larger change than the library.

Practical guidance: up to a few thousand points per series is comfortable. Past
that, downsample in your own layer — 0.1 has no built-in decimation.

## Current limitations

Stated plainly, because a roadmap read as a feature list is how a library gets
adopted for something it cannot do.

**Not implemented in 0.1:**

- Pie, donut, radial bar, radar — no polar coordinate system yet
- Scatter, bubble, histogram, box plot, violin, heatmap
- Candlestick and OHLC
- Sankey, sunburst, treemap, funnel, network graphs
- Zoom, pinch, pan, range selection
- A full financial crosshair (the scrub guide line is its foundation)
- Stacked **areas** — multi-series areas overlap, each measured from the baseline
- Public annotations (rules, ranges, event markers, text)
- Secondary axes (the architecture supports them; the API does not expose them)
- Keyboard chart exploration (selection is architected for it; not wired)
- Downsampling or decimation
- GPU/`RenderNode` rendering
- Screenshot/golden tests — the repository has no such infrastructure
- Compose Multiplatform targets — the pure logic is portable, the module is not

**Known behavioural limits:**

- `compact()` suffixes (`K`/`M`/`B`) are not localised
- `TimeScale` ticks use fixed durations, not calendar arithmetic: a month step
  is approximated at 30 days and a year at 365. Right for positioning a tick on
  a proportional axis; wrong for asserting "the first of the month"
- 100% stacked charts normalise over absolute values; designed for
  non-negative data
- A data change animates only when the series ids and point counts are
  unchanged; anything else snaps
- The dark/light palette is chosen from the *system* setting, so an app forcing
  one theme against the system gets a palette tuned for the other. Pass
  `materialDerivedChartColors(isDark = …)` explicitly in that case
- Toggling a series through the legend hides or shows it immediately; it does
  not fade in or out. Enter/exit transitions are 0.2 work

## Roadmap

**0.2 — polar and the visualisation grammar**

- `PolarCoordinates` alongside `CartesianCoordinates`; pie, donut, radial bar,
  radar on it
- Scatter and bubble layers, which need the layer model to carry more than one
  y per mark
- Public annotation layers: horizontal and vertical rules, ranges, event markers
- Zoom, pan and a viewport state, with a range-selection API
- A full crosshair, built on the existing selection guide
- Stacked areas
- Secondary value axes
- Stabilising the `CartesianChart` layer DSL and dropping the experimental marker

**Later**

- Statistical layers (histogram, box plot) once the layer model carries
  distributions
- Financial layers (candlestick, OHLC)
- Downsampling for very large series
- Keyboard and focus-based chart exploration
- Compose Multiplatform, if DevKit adopts KMP

## Testing

```bash
./gradlew :chartkit:testDebugUnitTest          # 187 JVM tests
./gradlew :chartkit:connectedDebugAndroidTest  # 35 Compose UI tests
```

| Suite | Covers |
| --- | --- |
| `ScaleTest` | Linear, category and time mapping; inversion; clamping; degenerate domains |
| `TickGeneratorTest` | Round steps, negatives, small decimals, large values, constants, absurd counts |
| `BarGeometryTest` | Stacking, sign separation, percent normalisation, rectangles, orientation, reveal, corners |
| `LineGeometryTest` | Segmentation, monotone overshoot, binary search, ordering |
| `NormalizationTest` | Axis inference, missing values, ordering, visibility, palette slots, duplicate ids |
| `LayoutAndAxisTest` | Gutters, titles, overhang, squeezed plots, label thinning |
| `FormatterTest` | Locale behaviour, compaction, percent, currency, dates, time zones |
| `AccessibilityAndPaletteTest` | Summary content, absence of statistical claims, palette separation, HSL round-trip |
| `CoordinatesAndOverlayTest` | Orientation mapping, baselines, tooltip placement |
| `MissingValuePolicyTest` | That `Break`, `Connect` and `Zero` genuinely differ |
| `LargeDatasetTest` | 1,000 / 5,000 / 10,000-point behaviour |
| `ChartRenderingTest` | Every chart type, edge-case datasets, states, animated frames |
| `ChartInteractionTest` | Tap, scrub, tooltips, hoisted state, legend toggling |
| `ChartSemanticsAndThemeTest` | Announcements, custom summaries, theme precedence, light and dark |

## Licence

Apache-2.0. See [LICENSE.md](../LICENSE.md) and [NOTICE](../NOTICE).
