plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
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
