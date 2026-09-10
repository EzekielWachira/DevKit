---
hide:
  - navigation
---

# DevKit

Debug and QA tooling for Android, in three libraries that share one foundation
and one release-safety model.

<div class="grid cards" markdown>

-   **[ChartKit](chartkit/index.md)**

    Compose-native charts over your own data classes. Line, area, bar, scatter,
    histogram, box plot, heatmap and candlestick on Cartesian coordinates; pie,
    donut, radar and gauge on polar ones; treemap, Sankey and network graphs on
    planar ones; 3D columns, pie, donut and a true X/Y/Z scatter; and world
    maps reading GeoJSON and TopoJSON.

    **Ships in release builds.**

-   **[FillKit](fillkit/index.md)**

    Fill Compose forms with coherent synthetic data, then turn any interesting
    form state into something you can reproduce, share, launch from ADB, and
    convert into a regression test.

    **Debug-only, with a no-op release runtime.**

-   **[NetKit](netkit/index.md)**

    Simulate offline, latency, timeouts and HTTP failures against a real
    OkHttp stack — with saved scenarios, seeded chaos, and a reproduction
    export that turns a bug into a test.

    **Debug-only.**

</div>

## Install

Use the BOM so the versions stay aligned:

```kotlin
dependencies {
    implementation(platform("io.github.ezekielwachira.devkit:devkit-bom:0.3.0"))

    // Release-safe
    implementation("io.github.ezekielwachira.devkit:chartkit")
    implementation("io.github.ezekielwachira.devkit:fillkit-api")

    // Debug-only
    debugImplementation("io.github.ezekielwachira.devkit:fillkit-debug")
    debugImplementation("io.github.ezekielwachira.devkit:netkit")

    // Tests
    androidTestImplementation("io.github.ezekielwachira.devkit:fillkit-testing")
}
```

The two umbrellas take a whole side at once — `devkit` for everything
release-safe, `devkit-debug` for every developer tool. See
[the ecosystem](ecosystem/index.md) for what is in each, and
[publishing](publishing/index.md) for how the versions relate.

## Release safety is a property of the artifact

The split is not a convention to remember. It is enforced by which
configuration an artifact can be declared in:

| Artifact | Configuration | Reaches production |
| --- | --- | --- |
| `chartkit` | `implementation` | Yes — it draws your app's charts |
| `fillkit-api` | `implementation` | Yes, as a **no-op**: the modifier does nothing |
| `fillkit-engine`, `fillkit-debug` | `debugImplementation` | No |
| `netkit` | `debugImplementation` | No |
| `fillkit-testing` | `androidTestImplementation` | No |

FillKit is four artifacts rather than one precisely because of this: a single
artifact could only be declared in one configuration, and that choice would be
wrong for the other three.

## Where to start

- **New here?** [The ecosystem](ecosystem/index.md) explains the modules, the
  requirements and how to run the sample app.
- **Drawing something?** [ChartKit](chartkit/index.md) — start with the
  [line chart](chartkit/line-chart.md) and the
  [gallery](ecosystem/run-the-sample.md).
- **Filling forms?** [FillKit](fillkit/index.md) — start with
  [TextFieldState and one fill target](fillkit/textfieldstate-and-one-fill-target.md).
- **Breaking the network?** [NetKit](netkit/index.md) — start with
  [why](netkit/why.md) and [attaching it to OkHttp](netkit/attach-it-to-okhttp.md).
- **Cutting a release?** [Publishing](publishing/index.md).

## About this site

Every page here is generated from the repository's own Markdown by
`scripts/build_docs.py`. The READMEs stay canonical — GitHub renders them,
contributors edit them, and this site is a navigable view of them rather than a
second copy that drifts out of date. A cross-reference that cannot be resolved
fails the build rather than shipping as a broken link.

The charts are real. Each picture is rendered on a device by ChartKit itself
and exported through its own [SVG writer](chartkit/export.md#export) where the
layers support it, or captured as a bitmap where they do not — never drawn by
hand or screenshotted from a design. The short clips are recordings of actual
gestures on actual charts. ChartKit is an Android library rather than a
Compose Multiplatform one, so a live chart cannot honestly be embedded in a web
page: a JavaScript reimplementation would be a different chart wearing this
one's name, and a recording of the real thing is the truthful alternative.
