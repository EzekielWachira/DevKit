package io.devkit.chartkit

import io.devkit.core.DevKitDistribution
import io.devkit.core.DevKitTool

/**
 * This ChartKit build's version and its identity within the DevKit ecosystem.
 *
 * ChartKit is a **runtime** library: it draws an application's own data and
 * belongs in `implementation`, on the release classpath, alongside `fillkit-api`
 * — not in `debugImplementation` with the developer tooling. That distinction
 * is what [DevKitDistribution] records, and what decides which umbrella
 * artifact aggregates it.
 */
object ChartKitVersion {

    /** `0.1.0` */
    const val NAME: String = "0.1.0"

    /** The published artifact descriptor. */
    val chartKit: DevKitTool = DevKitTool(
        id = "chartkit",
        displayName = "ChartKit",
        version = NAME,
        distribution = DevKitDistribution.RUNTIME,
    )
}
