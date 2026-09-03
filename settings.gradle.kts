pluginManagement {
    // The convention plugins that configure Maven publishing. Included here
    // rather than depended on as an artifact so they are always built from the
    // same commit as the modules they configure.
    includeBuild("build-logic")

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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DevKit"

// ---- sample ---------------------------------------------------------------

include(":app")

// ---- shared foundation ----------------------------------------------------

include(":core")

// ---- kits -----------------------------------------------------------------
//
// Each kit is independently published and independently versioned. FillKit is
// four modules because its pieces have genuinely different consumption scopes —
// `api` ships to release, `engine` and `debug` are debug-only, `testing` is
// androidTest-only — and collapsing them into one artifact would force the
// release-safe half into a debug-only dependency.

include(":fillkit:api")
include(":fillkit:engine")
include(":fillkit:debug")
include(":fillkit:testing")
include(":netkit")

// ---- ecosystem artifacts --------------------------------------------------
//
// Aggregators only. These contain dependency declarations, never source.

include(":devkit")
include(":devkit-debug")
include(":devkit-bom")
