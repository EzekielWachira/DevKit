package io.devkit.fillkit

import io.devkit.core.DevKitDistribution
import io.devkit.core.DevKitTool

/**
 * This FillKit build's version and its identity within the DevKit ecosystem.
 *
 * FillKit ships as four artifacts that share one version, because they are one
 * library split by consumption scope rather than four independent things. This
 * object lives in `fillkit-api` — the only one of the four that reaches a
 * release build — so an application can report which FillKit it was built
 * against without depending on the debug half.
 *
 * The descriptors differ in [DevKitTool.distribution], which is the part worth
 * being able to read at runtime: it is the difference between an artifact that
 * is safe in production and one that is not.
 */
object FillKitVersion {

    /** `0.1.0` */
    const val NAME: String = "0.1.0"

    /** The release-safe modifier and model API. Belongs in `implementation`. */
    val api: DevKitTool = DevKitTool(
        id = "fillkit-api",
        displayName = "FillKit API",
        version = NAME,
        distribution = DevKitDistribution.RUNTIME,
    )

    /** The data generation engine. Debug-only. */
    val engine: DevKitTool = DevKitTool(
        id = "fillkit-engine",
        displayName = "FillKit Engine",
        version = NAME,
        distribution = DevKitDistribution.DEBUG,
    )

    /** The developer panel and QA launcher. Debug-only. */
    val debug: DevKitTool = DevKitTool(
        id = "fillkit-debug",
        displayName = "FillKit Debug",
        version = NAME,
        distribution = DevKitDistribution.DEBUG,
    )

    /** The Compose test support. `androidTestImplementation` only. */
    val testing: DevKitTool = DevKitTool(
        id = "fillkit-testing",
        displayName = "FillKit Testing",
        version = NAME,
        distribution = DevKitDistribution.TEST,
    )
}
