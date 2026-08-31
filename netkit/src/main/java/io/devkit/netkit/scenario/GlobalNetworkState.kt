package io.devkit.netkit.scenario

/**
 * The connectivity behaviour applied to every request that no [EndpointRule] claimed.
 *
 * Latency is modelled separately in [GlobalNetworkConfig] so that "online but
 * slow" and "offline" are independent choices rather than mutually exclusive
 * states.
 */
sealed interface GlobalNetworkMode {

    /** Label rendered by the mode selector. */
    val label: String

    /** Requests reach the real server. */
    data object Normal : GlobalNetworkMode {
        override val label: String get() = "Normal"
    }

    /** Every request fails as if the device had no connectivity. */
    data object Offline : GlobalNetworkMode {
        override val label: String get() = "Offline"
    }

    /** Every request fails with a simulated socket timeout of [type]. */
    data class Timeout(val type: TimeoutType) : GlobalNetworkMode {
        override val label: String get() = type.label
    }

    companion object {
        /** The modes offered by the debug UI, in display order. */
        val selectable: List<GlobalNetworkMode> = listOf(
            Normal,
            Offline,
            Timeout(TimeoutType.CONNECT),
            Timeout(TimeoutType.READ),
        )
    }
}

/**
 * Global network behaviour: a connectivity [mode] plus an independent artificial
 * [latencyMillis] applied to requests that still reach the server.
 *
 * @param latencyMillis extra delay in milliseconds; must not be negative.
 */
data class GlobalNetworkConfig(
    val mode: GlobalNetworkMode = GlobalNetworkMode.Normal,
    val latencyMillis: Long = 0,
) {
    init {
        require(latencyMillis >= 0) { "NetKit latency cannot be negative (was $latencyMillis)" }
    }

    /** True when this configuration changes nothing about the network. */
    val isNormal: Boolean get() = mode == GlobalNetworkMode.Normal && latencyMillis == 0L

    /** One-line summary, e.g. `Normal · 2500ms`. */
    val summary: String get() = when {
        mode != GlobalNetworkMode.Normal && latencyMillis > 0 -> "${mode.label} · ${latencyMillis}ms"
        mode != GlobalNetworkMode.Normal -> mode.label
        latencyMillis > 0 -> "Normal · ${latencyMillis}ms"
        else -> "Normal"
    }
}
