plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("devkit.publish")
}

android {
    namespace = "io.devkit.fillkit.testing"
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

    buildFeatures {
        compose = true
    }
}

// Consumed through androidTestImplementation only. Nothing here belongs in a
// production dependency graph: it exposes Compose test rules and JUnit types.
dependencies {
    api(project(":fillkit:api"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.test.junit4)
    api(libs.junit)
    implementation(libs.androidx.compose.ui)
}

devKitPublishing {
    artifactId.set("fillkit-testing")
    displayName.set("FillKit Testing")
    description.set(
        "Compose test support for FillKit: field finders, assertions, FillKitTestDriver and " +
            "reproduction helpers. androidTestImplementation only — it exposes JUnit and " +
            "Compose test rules.",
    )
    versionKey.set("fillkit")
}
