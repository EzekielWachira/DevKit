package io.devkit.netkit

import io.devkit.core.DevKitDistribution
import io.devkit.core.DevKitTool

/**
 * This NetKit build's version.
 *
 * One authoritative place rather than a string literal in each of the handful of
 * spots that need it: the `generator` field of an exported `.netkit.json`, the
 * README's dependency line, and anything a consumer wants to log.
 *
 * The scenario **file format** has its own, independent number — see
 * [io.devkit.netkit.scenario.serialization.ScenarioSchema.CURRENT_VERSION].
 * A NetKit release can ship without changing the schema, and must be able to.
 */
object NetKitVersion {

    /** `0.1.0` */
    const val NAME: String = "0.1.0"

    /** `netkit/0.1.0` — the value written into an exported file. */
    const val GENERATOR: String = "netkit/$NAME"

    /**
     * NetKit's identity within the DevKit ecosystem.
     *
     * Additive: [NAME] and [GENERATOR] are unchanged and remain the values the
     * serializer writes. This carries the two things a string could not — the
     * artifact id a consumer typed into their build file, and the fact that
     * NetKit is `debugImplementation`-only — so a debug panel or a bug report
     * can state both without hard-coding them.
     */
    val tool: DevKitTool = DevKitTool(
        id = "netkit",
        displayName = "NetKit",
        version = NAME,
        distribution = DevKitDistribution.DEBUG,
    )
}
