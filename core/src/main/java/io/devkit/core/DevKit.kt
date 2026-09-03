package io.devkit.core

/**
 * How a DevKit library is meant to be wired into a consuming build.
 *
 * DevKit's central safety property is that developer tooling does not reach a
 * release binary, and that property is enforced by **dependency scoping** — a
 * kit is kept out of production by being declared `debugImplementation`, not by
 * a runtime check that fails the app. This enum is the machine-readable form of
 * that classification, so the umbrella artifacts, the documentation and any
 * future tooling all agree on which kit belongs where rather than each keeping
 * its own list.
 *
 * It deliberately does **not** enforce anything at runtime. A library that
 * crashed because it noticed it was in a release build would turn a packaging
 * mistake into an outage, which is a worse outcome than the mistake.
 */
enum class DevKitDistribution {

    /**
     * Safe to ship in a release build; belongs in `implementation`.
     *
     * Aggregated by the `devkit` umbrella artifact.
     */
    RUNTIME,

    /**
     * Developer and QA tooling; belongs in `debugImplementation`.
     *
     * Aggregated by the `devkit-debug` umbrella artifact.
     */
    DEBUG,

    /**
     * Test-only support; belongs in `androidTestImplementation`.
     *
     * Aggregated by neither umbrella. Test helpers pull JUnit and Compose test
     * rules, which have no business in an application's dependency graph even
     * in debug.
     */
    TEST,
    ;

    /** The Gradle configuration this distribution should be declared in. */
    val gradleConfiguration: String
        get() = when (this) {
            RUNTIME -> "implementation"
            DEBUG -> "debugImplementation"
            TEST -> "androidTestImplementation"
        }
}

/**
 * Identity of one DevKit library at runtime.
 *
 * Small on purpose. Each kit already knows its own name and version; what it
 * had no way to state before was *which ecosystem it belongs to and how it is
 * meant to be consumed*. A debug panel that wants to print "NetKit 0.3.0
 * (debug-only), DevKit 0.1.0" needed both halves, and each kit inventing its
 * own shape for that is how two libraries in one repository end up disagreeing
 * about their own release.
 *
 * @param id the Maven artifact id, e.g. `netkit`. The same string the consumer
 *   typed into their build file, so a support conversation can start from it.
 * @param displayName the human name, e.g. `NetKit`.
 * @param version the kit's own version, which moves independently of the
 *   ecosystem version — see [DevKit.VERSION].
 * @param distribution how the kit is meant to be wired in.
 */
data class DevKitTool(
    val id: String,
    val displayName: String,
    val version: String,
    val distribution: DevKitDistribution,
) {
    init {
        require(id.isNotBlank()) { "A DevKit tool needs an artifact id" }
        require(displayName.isNotBlank()) { "A DevKit tool needs a display name" }
        require(version.isNotBlank()) { "A DevKit tool needs a version" }
    }

    /** `NetKit 0.3.0` — for a debug panel header or a log line. */
    val label: String get() = "$displayName $version"

    /**
     * `NetKit 0.3.0 (debug-only)` — the label plus a reminder of the scope.
     *
     * Used where the distinction is worth restating, such as an about screen a
     * QA engineer might screenshot into a ticket.
     */
    val qualifiedLabel: String
        get() = when (distribution) {
            DevKitDistribution.RUNTIME -> label
            DevKitDistribution.DEBUG -> "$label (debug-only)"
            DevKitDistribution.TEST -> "$label (test-only)"
        }

    /** `io.github.ezekielwachira.devkit:netkit:0.3.0` */
    fun coordinates(group: String = DevKit.GROUP): String = "$group:$id:$version"
}

/**
 * The DevKit ecosystem itself.
 *
 * [VERSION] is the *ecosystem* version — the one shared by the `devkit`,
 * `devkit-debug` and `devkit-bom` artifacts. It names a tested compatible set
 * of kit versions and is not the version of any individual kit; NetKit can be
 * `0.3.0` inside DevKit `0.1.0`. See `docs/PUBLISHING.md`.
 */
object DevKit {

    /** The Maven group every DevKit artifact is published under. */
    const val GROUP: String = "io.github.ezekielwachira.devkit"

    /**
     * The ecosystem version: the version of `devkit`, `devkit-debug` and
     * `devkit-bom`, and of the compatible set they describe.
     */
    const val VERSION: String = "0.1.0"

    /** This artifact's own version, which moves with the shared foundation. */
    const val CORE_VERSION: String = "0.1.0"
}
