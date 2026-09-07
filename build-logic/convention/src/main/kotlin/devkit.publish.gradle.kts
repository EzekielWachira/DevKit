import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import io.devkit.gradle.DevKitPublishingExtension
import io.devkit.gradle.configureDevKitPublication
import io.devkit.gradle.devKitGroup

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
 * Everything else — group, POM, sources and javadoc jars, signing, upload,
 * validation — comes from here and from `gradle.properties`.
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
    // The `.base` plugin, not the full one: the full plugin auto-detects the
    // module type and configures a platform itself, which then collides with
    // the explicit `configure(...)` below. The base plugin configures nothing
    // until asked, which is what a convention plugin wants.
    id("com.vanniktech.maven.publish.base")
}

val devKitPublishing = extensions.create<DevKitPublishingExtension>("devKitPublishing")

group = devKitGroup()

// Eagerly, *not* in `afterEvaluate`. Declaring the platform reaches into AGP's
// `publishing { singleVariant(…) }`, and AGP reads that block while evaluating
// the module — by `afterEvaluate` it has been read and refuses further changes.
mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            // Sources are part of the product: a debug tool nobody can step
            // into is a debug tool that gets deleted the first time it
            // misbehaves.
            sourcesJar = true,
            // Required, not optional. Central rejects a release with no
            // `-javadoc.jar` — and rejects it during validation, after the
            // upload has already succeeded, which makes it an easy thing to
            // discover far too late. The jar is empty unless a documentation
            // engine is applied; Central checks that it exists, not what is in
            // it.
            publishJavadocJar = true,
        ),
    )
}

// The metadata half does need `afterEvaluate`: the module's own
// `devKitPublishing { }` values are not set before then.
afterEvaluate {
    configureDevKitPublication(project = project, extension = devKitPublishing)
}
