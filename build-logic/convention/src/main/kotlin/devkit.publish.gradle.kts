import com.android.build.api.dsl.LibraryExtension
import io.devkit.gradle.DevKitPublishingExtension
import io.devkit.gradle.configureDevKitPublication
import io.devkit.gradle.devKitGroup
import io.devkit.gradle.devKitVersion

/**
 * Publishes an Android library as a DevKit Maven artifact.
 *
 * Apply it alongside `com.android.library` and describe the module:
 *
 * ```kotlin
 * plugins {
 *     alias(libs.plugins.android.library)
 *     id("devkit.publish")
 * }
 *
 * devKitPublishing {
 *     artifactId.set("netkit")
 *     displayName.set("NetKit")
 *     description.set("Network scenario and failure simulation toolkit…")
 *     versionKey.set("netkit")
 * }
 * ```
 *
 * Everything else — group, POM, sources jar, signing, repositories, validation —
 * comes from here and from `gradle.properties`.
 *
 * ### Only the release variant is published
 *
 * A debug-only library still publishes its **release** variant. The two are not
 * the same idea: `debugImplementation` describes which of the *consumer's*
 * build types the artifact is wired into, while the published variant is what
 * that artifact was compiled from. Publishing a debug AAR would ship a
 * debuggable, unoptimised binary and give consumers a variant their release
 * builds could not resolve.
 */
plugins {
    id("maven-publish")
}

val devKitPublishing = extensions.create<DevKitPublishingExtension>("devKitPublishing")

group = devKitGroup()

extensions.configure<LibraryExtension> {
    publishing {
        singleVariant("release") {
            // Sources are part of the product: a debug tool nobody can step
            // into is a debug tool that gets deleted the first time it
            // misbehaves.
            withSourcesJar()
        }
    }
}

// `afterEvaluate` is required rather than merely convenient: AGP does not
// register the `release` software component until the module's own
// `android { }` block has been evaluated, and the module's
// `devKitPublishing { }` values are not set before then either.
afterEvaluate {
    version = devKitVersion(devKitPublishing.versionKey.get())

    configureDevKitPublication(
        project = project,
        extension = devKitPublishing,
        publicationName = "release",
    ) {
        from(components["release"])
    }
}
