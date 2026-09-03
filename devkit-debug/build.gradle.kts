plugins {
    alias(libs.plugins.android.library)
    id("devkit.publish")
}

android {
    namespace = "io.devkit.umbrella.debug"
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

/**
 * The developer and QA umbrella: every DevKit debug tool, in one dependency.
 *
 * Intended for `debugImplementation`, and only that:
 *
 * ```kotlin
 * debugImplementation("io.github.ezekielwachira.devkit:devkit-debug:<version>")
 * ```
 *
 * ### What arrives with it
 *
 * `fillkit-debug` and `netkit` directly; `fillkit-engine` and `fillkit-api`
 * transitively, because FillKit's debug panel needs both. So a consumer who
 * uses this **and** wants FillKit's release-safe modifier API in production
 * still declares `implementation("…:fillkit-api")` separately — this artifact
 * puts it on the debug classpath only, which is where a debug umbrella belongs.
 *
 * ### What is deliberately absent
 *
 * `fillkit-testing`. It exposes JUnit and Compose test rules, which do not
 * belong on an application's classpath even in debug; it stays an
 * `androidTestImplementation` artifact that consumers add explicitly.
 *
 * Contains no source of its own.
 */
dependencies {
    api(project(":fillkit:debug"))
    api(project(":netkit"))
    // Future debug/QA kits are added here.
}

devKitPublishing {
    artifactId.set("devkit-debug")
    displayName.set("DevKit Debug")
    description.set(
        "Every DevKit developer and QA tool in one debugImplementation dependency: the " +
            "FillKit debug panel and NetKit. Not for release builds.",
    )
    versionKey.set("ecosystem")
}
