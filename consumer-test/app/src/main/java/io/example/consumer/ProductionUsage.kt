package io.example.consumer

import io.devkit.core.DevKitDistribution
import io.devkit.fillkit.FillKitVersion

/**
 * Release-safe usage: `fillkit-api` and `core` only.
 *
 * Compiled into every variant, so if either artifact were debug-only this file
 * would not build.
 */
object ProductionUsage {

    fun describe(): String = FillKitVersion.api.qualifiedLabel

    /** The release-safe half really is classified as such. */
    fun isReleaseSafe(): Boolean =
        FillKitVersion.api.distribution == DevKitDistribution.RUNTIME
}
