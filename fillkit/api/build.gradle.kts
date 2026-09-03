plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("devkit.publish")
}

android {
    namespace = "io.devkit.fillkit.api"
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
}

dependencies {
    api(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    testImplementation(libs.junit)
}

devKitPublishing {
    artifactId.set("fillkit-api")
    displayName.set("FillKit API")
    description.set(
        "The release-safe half of FillKit: the Compose modifier, field models, locale and " +
            "persona DSLs, reproduction specs and token codec, and a no-op release runtime. " +
            "The only FillKit artifact intended for production builds.",
    )
    versionKey.set("fillkit")
}
