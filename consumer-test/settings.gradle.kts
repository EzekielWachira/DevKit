// A standalone Gradle build that consumes DevKit by **Maven coordinates**.
//
// Deliberately not a module of the main build. A `project(":netkit")`
// dependency resolves through Gradle's project graph and proves nothing about
// what a real consumer gets: it bypasses the POM, the Gradle module metadata,
// the artifact ids and the published versions — which is exactly the layer this
// restructuring changed. Resolving from `mavenLocal()` is the only way to catch
// a broken publication before a user does.
//
// Run `./gradlew publishToMavenLocal` in the parent build first, then
// `./gradlew --project-dir consumer-test verifyAll`.

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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // First, so a stale artifact from a public repository can never mask a
        // freshly published local one.
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "devkit-consumer-test"

include(":app")
