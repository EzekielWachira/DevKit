plugins {
    alias(libs.plugins.android.library)
    id("devkit.publish")
}

android {
    namespace = "io.devkit.fillkit.engine"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":fillkit:api"))
    testImplementation(libs.junit)
}

devKitPublishing {
    artifactId.set("fillkit-engine")
    displayName.set("FillKit Engine")
    description.set(
        "FillKit's data generation engine: locale registries, persona and value generation, " +
            "scenario composition and deterministic random streams. Debug-only; normally " +
            "arrives transitively with fillkit-debug.",
    )
    versionKey.set("fillkit")
}
