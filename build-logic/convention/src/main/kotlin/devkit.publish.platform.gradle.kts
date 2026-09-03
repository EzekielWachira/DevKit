import io.devkit.gradle.DevKitPublishingExtension
import io.devkit.gradle.configureDevKitPublication
import io.devkit.gradle.devKitGroup
import io.devkit.gradle.devKitVersion

/**
 * Publishes a Gradle `java-platform` as a DevKit Maven BOM.
 *
 * Separate from `devkit.publish` because a platform has no Android variants, no
 * sources jar and a different software component (`javaPlatform` rather than
 * `release`). Sharing one plugin would have meant branching on the module type
 * inside it, which is the shape that eventually publishes an AAR as a BOM.
 *
 * ```kotlin
 * plugins {
 *     `java-platform`
 *     id("devkit.publish.platform")
 * }
 * ```
 *
 * The shared POM, signing, repository and validation behaviour is identical to
 * the Android path — both call `configureDevKitPublication`.
 */
plugins {
    id("maven-publish")
}

val devKitPublishing = extensions.create<DevKitPublishingExtension>("devKitPublishing")

group = devKitGroup()

afterEvaluate {
    version = devKitVersion(devKitPublishing.versionKey.get())

    configureDevKitPublication(
        project = project,
        extension = devKitPublishing,
        publicationName = "devKitPlatform",
    ) {
        from(components["javaPlatform"])
    }
}
