plugins {
    alias(libs.plugins.android.library)
    id("devkit.publish")
}

android {
    namespace = "io.devkit.umbrella"
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
 * The production umbrella: every release-safe DevKit library, in one dependency.
 *
 * `api` rather than `implementation` is the whole point of the module — an
 * umbrella exists to expose its children transitively, and `implementation`
 * would hide the very types the consumer added it for. This is the one place in
 * the repository where blanket `api` is correct.
 *
 * ### What is deliberately absent
 *
 * No NetKit, no `fillkit-debug`, no `fillkit-engine`, no test helpers. Those are
 * developer tooling and belong to `devkit-debug`. Putting them here would mean
 * that a consumer writing `implementation("…:devkit")` — the most natural thing
 * to type — silently shipped a network interceptor and a debug panel to
 * production. The split between this artifact and `devkit-debug` is the single
 * most load-bearing decision in the DevKit distribution model, and it is
 * enforced here by omission.
 *
 * Contains no source of its own. Kit code is never copied into an umbrella.
 */
dependencies {
    api(project(":core"))
    api(project(":fillkit:api"))
    // Future runtime-safe kits (ChartKit, and so on) are added here.
}

devKitPublishing {
    artifactId.set("devkit")
    displayName.set("DevKit")
    description.set(
        "Every release-safe DevKit library in one dependency: DevKit Core and the FillKit " +
            "API. Contains no developer or QA tooling — those live in devkit-debug.",
    )
    versionKey.set("ecosystem")
}
