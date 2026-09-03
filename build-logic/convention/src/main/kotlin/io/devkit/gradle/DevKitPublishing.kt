package io.devkit.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.authentication.http.BasicAuthentication
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.plugins.signing.SigningExtension
import java.net.URI

/**
 * The per-module half of a DevKit publication.
 *
 * Everything a module has to state about itself, and nothing it shares with the
 * others. Group, URL, SCM, developer, licence and signing all come from
 * `gradle.properties` via [DevKitPomMetadata], so a module's build file says
 * only what makes it different:
 *
 * ```kotlin
 * devKitPublishing {
 *     artifactId.set("netkit")
 *     displayName.set("NetKit")
 *     description.set("Network scenario and failure simulation toolkit…")
 *     versionKey.set("netkit")
 * }
 * ```
 *
 * The alternative — a `publishing { publications { … } }` block per module —
 * was rejected because eight modules would then each carry a copy of the same
 * twenty lines of POM boilerplate, and the copies would diverge the first time
 * one of them was edited in a hurry.
 */
interface DevKitPublishingExtension {

    /** The Maven artifact id, e.g. `netkit`. Required. */
    val artifactId: Property<String>

    /** The human name in the POM, e.g. `NetKit`. Required. */
    val displayName: Property<String>

    /**
     * The POM description. Required, and required to be *specific*: a generic
     * line repeated across artifacts is worse than none, because it is what a
     * consumer reads on the artifact page before deciding to depend on it.
     */
    val description: Property<String>

    /**
     * Which version stream this artifact belongs to, e.g. `netkit`, `fillkit`
     * or `ecosystem`. Resolved against `devkit.version.<key>` in
     * `gradle.properties`.
     *
     * A key rather than a literal version, for two reasons. It keeps every
     * published version in one file, and it makes shared streams explicit:
     * FillKit's four artifacts all declare `fillkit`, which is the mechanism
     * that stops them drifting apart into a combination that cannot compile
     * against itself.
     */
    val versionKey: Property<String>
}

/**
 * POM fields every DevKit publication shares, read once from `gradle.properties`.
 *
 * Resolved through [Project.getProviders] so the values are configuration-cache
 * inputs rather than ambient project state, which is what lets the whole
 * publishing pipeline run with the configuration cache enabled.
 */
internal data class DevKitPomMetadata(
    val group: String,
    val url: String,
    val scmConnection: String,
    val scmDeveloperConnection: String,
    val developerId: String,
    val developerName: String,
    val developerUrl: String,
    val licenseName: String,
    val licenseUrl: String,
    val licenseDistribution: String,
) {
    /**
     * True when a licence has been configured.
     *
     * Kept as a check rather than an assumption: Maven Central refuses a POM
     * with no licence, and the failure arrives late and unhelpfully. Catching it
     * here means a missing or half-edited `devkit.pom.license.*` is reported
     * before anything is uploaded. Local publishing does not require it, so the
     * pipeline stays testable either way.
     */
    val hasLicense: Boolean get() = licenseName.isNotBlank() && licenseUrl.isNotBlank()
}

internal fun Project.devKitPomMetadata(): DevKitPomMetadata {
    fun read(name: String): String = providers.gradleProperty(name).orNull.orEmpty().trim()
    return DevKitPomMetadata(
        group = read("devkit.group"),
        url = read("devkit.pom.url"),
        scmConnection = read("devkit.pom.scm.connection"),
        scmDeveloperConnection = read("devkit.pom.scm.developerConnection"),
        developerId = read("devkit.pom.developer.id"),
        developerName = read("devkit.pom.developer.name"),
        developerUrl = read("devkit.pom.developer.url"),
        licenseName = read("devkit.pom.license.name"),
        licenseUrl = read("devkit.pom.license.url"),
        licenseDistribution = read("devkit.pom.license.distribution").ifBlank { "repo" },
    )
}

/**
 * The published version of one kit, from `gradle.properties`.
 *
 * `devKitVersion("netkit")` reads `devkit.version.netkit`. Failing loudly on a
 * missing key matters more than it looks: the default when a version cannot be
 * resolved is Gradle's `unspecified`, which publishes successfully and produces
 * an artifact nobody can depend on.
 */
fun Project.devKitVersion(key: String): String {
    val property = "devkit.version.$key"
    val value = providers.gradleProperty(property).orNull?.trim()
    if (value.isNullOrEmpty()) {
        throw GradleException(
            "No DevKit version for '$key'. Add `$property=<version>` to gradle.properties — " +
                "it is the single source of truth for published versions.",
        )
    }
    return value
}

/** The DevKit Maven group, from `gradle.properties`. */
fun Project.devKitGroup(): String {
    val value = providers.gradleProperty("devkit.group").orNull?.trim()
    if (value.isNullOrEmpty()) {
        throw GradleException(
            "No DevKit Maven group. Add `devkit.group=<group>` to gradle.properties.",
        )
    }
    return value
}

/**
 * Applies the shared POM, validation, signing and repository configuration to
 * every publication in [project].
 *
 * Called by both the Android-library and the java-platform convention plugins,
 * so an AAR, a JAR and a BOM all end up with byte-identical shared metadata.
 */
internal fun configureDevKitPublication(
    project: Project,
    extension: DevKitPublishingExtension,
    publicationName: String,
    configurePublication: MavenPublication.() -> Unit,
) {
    val metadata = project.devKitPomMetadata()
    val publishing = project.extensions.getByType<PublishingExtension>()
    val resolvedVersion = project.devKitVersion(extension.versionKey.get())

    publishing.publications.create<MavenPublication>(publicationName) {
        configurePublication()

        groupId = metadata.group
        artifactId = extension.artifactId.get()
        version = resolvedVersion

        pom {
            name.set(extension.displayName)
            description.set(extension.description)
            url.set(metadata.url)

            scm {
                url.set(metadata.url)
                connection.set(metadata.scmConnection)
                developerConnection.set(metadata.scmDeveloperConnection)
            }

            developers {
                developer {
                    id.set(metadata.developerId)
                    name.set(metadata.developerName)
                    url.set(metadata.developerUrl)
                }
            }

            if (metadata.hasLicense) {
                licenses {
                    license {
                        name.set(metadata.licenseName)
                        url.set(metadata.licenseUrl)
                        distribution.set(metadata.licenseDistribution)
                    }
                }
            }
        }
    }

    configureDevKitRepositories(project, publishing)
    configureDevKitSigning(project, publishing)
    registerDevKitPublicationValidation(project, extension, metadata)
}

/**
 * The remote repository, configured only when credentials are present.
 *
 * Declaring it unconditionally would add a `publishAllPublicationsToMavenCentralRepository`
 * task to every module on every developer machine, where it can only fail. It
 * appears when `MAVEN_CENTRAL_USERNAME`/`MAVEN_CENTRAL_PASSWORD` (or the
 * matching Gradle properties) are set, which is the CI case.
 *
 * `mavenLocal()` needs no declaration — `publishToMavenLocal` is built in.
 */
private fun configureDevKitRepositories(project: Project, publishing: PublishingExtension) {
    val user = project.secret("MAVEN_CENTRAL_USERNAME", "mavenCentralUsername")
    val token = project.secret("MAVEN_CENTRAL_PASSWORD", "mavenCentralPassword")
    if (user.isNullOrBlank() || token.isNullOrBlank()) return

    val snapshot = project.version.toString().endsWith("SNAPSHOT")
    publishing.repositories.maven {
        name = "mavenCentral"
        setUrl(
            URI(
                if (snapshot) {
                    "https://central.sonatype.com/repository/maven-snapshots/"
                } else {
                    "https://ossrh-staging-api.central.sonatype.com/" +
                        "service/local/staging/deploy/maven2/"
                },
            ),
        )
        credentials {
            username = user
            password = token
        }
        // Forces Gradle to send Basic credentials **preemptively**.
        //
        // Without this, Gradle makes the request unauthenticated first and only
        // retries with credentials if the server answers with a challenge it
        // recognises. Sonatype's staging API does not send one Gradle acts on,
        // so the 401 is surfaced rather than retried — and it looks exactly like
        // a bad token, which is a genuinely expensive thing to misdiagnose
        // (`curl -u` sends Basic immediately, so it succeeds where the build
        // fails).
        authentication {
            create<BasicAuthentication>("basic")
        }
    }
}

/**
 * Signing, using an in-memory ASCII-armoured key.
 *
 * In-memory rather than a keyring file because CI has secrets, not files, and
 * because a keyring path in a build script is the kind of thing that ends up
 * pointing at a developer's home directory. Unsigned publication stays possible
 * — `publishToMavenLocal` must work without a key — but remote publication is
 * refused without one; see [registerDevKitPublicationValidation].
 */
private fun configureDevKitSigning(project: Project, publishing: PublishingExtension) {
    val key = project.secret("SIGNING_KEY", "signingInMemoryKey")
    val keyPassword = project.secret("SIGNING_PASSWORD", "signingInMemoryKeyPassword")
    if (key.isNullOrBlank()) return

    project.pluginManager.apply("signing")
    project.extensions.getByType<SigningExtension>().apply {
        useInMemoryPgpKeys(key, keyPassword.orEmpty())
        sign(publishing.publications)
    }
}

/**
 * Reads a secret from the environment or a Gradle property, in that order.
 *
 * Returned as a plain `String?` and never logged. Gradle redacts neither, so
 * the discipline is to keep the value inside the credential objects that
 * consume it and never put it in a task input, a log line or an error message.
 */
private fun Project.secret(environmentName: String, propertyName: String): String? =
    (
        providers.environmentVariable(environmentName).orNull
            ?: providers.gradleProperty(propertyName).orNull
        )
        // Trimmed because a trailing newline is easy to introduce when setting a
        // secret — `echo "token" | gh secret set …` adds one — and inside an
        // HTTP Basic header it corrupts the credential into an opaque 401 with
        // nothing in the message to suggest whitespace is the problem.
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * Fails a **remote** publish that would produce an artifact nobody can use.
 *
 * Deliberately scoped to remote publication. `publishToMavenLocal` stays usable
 * with no licence and no signing key, because local publication is how the
 * whole pipeline is smoke-tested and gating it behind release credentials would
 * make it untestable — which is the failure mode this validation exists to
 * prevent in the first place.
 */
private fun registerDevKitPublicationValidation(
    project: Project,
    extension: DevKitPublishingExtension,
    metadata: DevKitPomMetadata,
) {
    val path = project.path
    val hasSigningKey = !project.secret("SIGNING_KEY", "signingInMemoryKey").isNullOrBlank()

    val problems = project.provider {
        buildList {
            if (extension.artifactId.orNull.isNullOrBlank()) add("artifactId is not set")
            if (extension.displayName.orNull.isNullOrBlank()) add("displayName is not set")
            if (extension.description.orNull.isNullOrBlank()) add("description is not set")
            if (extension.versionKey.orNull.isNullOrBlank()) {
                add("versionKey is not set (would publish as 'unspecified')")
            }
            if (metadata.group.isBlank()) add("devkit.group is not set")
            if (!metadata.hasLicense) {
                add(
                    "no licence configured. Maven Central requires one: set " +
                        "devkit.pom.license.name and devkit.pom.license.url in " +
                        "gradle.properties, matching LICENSE.md",
                )
            }
            if (!hasSigningKey) add("no signing key (set the SIGNING_KEY environment variable)")
        }
    }

    project.tasks
        .matching { task ->
            task.name.startsWith("publishAllPublicationsTo") ||
                task.name.endsWith("ToMavenCentralRepository")
        }
        .configureEach {
            doFirst {
                val found = problems.get()
                if (found.isNotEmpty()) {
                    throw GradleException(
                        buildString {
                            appendLine("Cannot publish $path remotely:")
                            found.forEach { appendLine("  - $it") }
                            appendLine()
                            append(
                                "See docs/PUBLISHING.md. " +
                                    "`publishToMavenLocal` works without these.",
                            )
                        },
                    )
                }
            }
        }
}
