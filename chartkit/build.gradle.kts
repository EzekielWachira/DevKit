plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("devkit.publish")
}

android {
    namespace = "io.devkit.chartkit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // The chart engine — scales, ticks, geometry, stacking — is plain
            // Kotlin and runs on the JVM. This only covers the handful of tests
            // that brush against an Android type on the way in.
            isReturnDefaultValues = true
        }
    }
}

// ChartKit owns its rendering. There is no charting dependency here on purpose:
// wrapping MPAndroidChart or Vico would inherit their data models, their view
// interop and their theming, which is the opposite of the point.
//
// `api` for the Compose artifacts whose types appear in ChartKit's own
// signatures — `Modifier`, `Color`, `TextStyle`, `DrawScope` — because a
// consumer cannot call `LineChart(modifier = …)` without them on the compile
// classpath. Material 3 is `implementation`: ChartKit reads `MaterialTheme` to
// derive its defaults but exposes no Material type, so consumers need it at
// runtime and never at compile time.
dependencies {
    api(project(":core"))
    // `api`, not `implementation`: `Flow` appears in ChartKit's own signatures
    // — `rememberStreamingChartData(flow = …)` — so a consumer cannot call it
    // without coroutines on the compile classpath.
    api(libs.kotlinx.coroutines.core)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    // Virtual time, so the streaming tests assert throttling and windowing
    // without a real `Thread.sleep` in a unit test.
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

devKitPublishing {
    artifactId.set("chartkit")
    displayName.set("ChartKit")
    description.set(
        "Compose-native data visualisation for Android, on one engine: line, area, bar, " +
            "scatter, bubble, histogram, box plot, violin, heatmap, calendar heatmap, " +
            "candlestick, OHLC, volume, waterfall, dumbbell, lollipop, bullet, timeline, " +
            "range and Gantt on Cartesian coordinates, with grouped and stacked 3D " +
            "columns projected over the same engine; pie, donut, radial bar, radar, " +
            "sunburst and gauge on polar ones; treemap, Sankey, funnel and network graphs " +
            "on planar ones. Axes, logarithmic and symmetric-log scales, secondary value " +
            "axes, legends, annotations, tooltips, crosshair, tap, scrub, pinch zoom, pan " +
            "and range selection, hierarchical drill-down, linked charts, cross-filtering, " +
            "an overview navigator, custom layers, viewport culling and downsampling for " +
            "large datasets, Flow-based streaming, animation, theming, accessibility " +
            "semantics, static report rendering and image export. Release-safe.",
    )
    versionKey.set("chartkit")
}
