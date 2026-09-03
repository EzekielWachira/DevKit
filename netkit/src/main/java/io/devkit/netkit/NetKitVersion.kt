package io.devkit.netkit

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

    /** `0.3.0` */
    const val NAME: String = "0.3.0"

    /** `netkit/0.3.0` — the value written into an exported file. */
    const val GENERATOR: String = "netkit/$NAME"
}
