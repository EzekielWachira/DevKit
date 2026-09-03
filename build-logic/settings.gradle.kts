// The build-logic composite build.
//
// Included by the root `settings.gradle.kts` via `pluginManagement { includeBuild(...) }`,
// so its convention plugins are resolvable by plugin id from any module without
// a `buildscript` block or a published artifact.
//
// It reuses the main build's version catalog rather than declaring its own, so
// the Android Gradle Plugin the convention plugins compile against can never
// drift from the one the modules apply.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

include(":convention")
