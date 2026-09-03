package io.example.consumer

import io.devkit.core.DevKitDistribution
import io.devkit.fillkit.FillKitVersion
import io.devkit.netkit.NetKitVersion
import io.devkit.netkit.scenario.EndpointRule
import io.devkit.netkit.scenario.HttpMethod
import io.devkit.netkit.scenario.NetworkAction

/**
 * Debug-only usage, reached through the `devkit-debug` umbrella.
 *
 * Touches real NetKit types rather than only version constants, so the
 * published AAR is proven to carry its classes and its OkHttp dependency, not
 * merely to resolve.
 */
object DebugUsage {

    fun describe(): String = "${NetKitVersion.tool.qualifiedLabel}, ${FillKitVersion.debug.qualifiedLabel}"

    /** Both are debug tooling, and say so. */
    fun bothAreDebugTools(): Boolean =
        NetKitVersion.tool.distribution == DevKitDistribution.DEBUG &&
            FillKitVersion.debug.distribution == DevKitDistribution.DEBUG

    /** A real NetKit rule, so the scenario API is exercised and not just imported. */
    fun sampleRule(): EndpointRule = EndpointRule.forPath(
        path = "/api/v1/bookings",
        method = HttpMethod.GET,
        action = NetworkAction.ReturnResponse(500),
    )
}
