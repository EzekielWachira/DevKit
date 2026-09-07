import com.vanniktech.maven.publish.JavaPlatform
import io.devkit.gradle.DevKitPublishingExtension
import io.devkit.gradle.configureDevKitPublication
import io.devkit.gradle.devKitGroup

/**
 * Publishes a Gradle `java-platform` as a DevKit Maven BOM.
 *
 * Separate from `devkit.publish` because a platform has no Android variants and
 * no sources or javadoc jars — it is a POM and nothing else. Sharing one plugin
 * would have meant branching on the module type inside it, which is the shape
 * that eventually publishes an AAR as a BOM.
 *
 * ```kotlin
 * plugins {
 *     `java-platform`
 *     id("devkit.publish.platform")
 * }
 * ```
 *
 * The shared POM, signing, upload and validation behaviour is identical to the
 * Android path — both call `configureDevKitPublication`.
 */
plugins {
    id("com.vanniktech.maven.publish.base")
}

val devKitPublishing = extensions.create<DevKitPublishingExtension>("devKitPublishing")

group = devKitGroup()

mavenPublishing {
    configure(JavaPlatform())
}

afterEvaluate {
    configureDevKitPublication(project = project, extension = devKitPublishing)
}
