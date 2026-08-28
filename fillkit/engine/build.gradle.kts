plugins {
    alias(libs.plugins.android.library)
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
