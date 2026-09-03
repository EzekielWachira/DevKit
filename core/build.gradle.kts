plugins {
    alias(libs.plugins.android.library)
    id("devkit.publish")
}

android {
    namespace = "io.devkit.core"
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

// Deliberately dependency-free.
//
// `core` is the foundation, so anything it depends on is imposed on every kit
// that touches it. It has no Compose, no OkHttp, no serialization and no
// AndroidX beyond the plugin baseline, which is what keeps a consumer who wants
// only FillKit's release-safe half from inheriting a networking stack.
//
// An Android library rather than a plain JVM one: every other DevKit module is
// an AAR, and matching that keeps variant resolution uniform for Android
// consumers. It also leaves room for the shared Compose primitives that are the
// obvious next thing to land here.
dependencies {
    testImplementation(libs.junit)
}

devKitPublishing {
    artifactId.set("core")
    displayName.set("DevKit Core")
    description.set(
        "Shared foundation for the DevKit libraries: ecosystem version metadata and the " +
            "runtime/debug/test distribution classification. Normally arrives transitively; " +
            "consumers rarely depend on it directly.",
    )
    versionKey.set("core")
}
