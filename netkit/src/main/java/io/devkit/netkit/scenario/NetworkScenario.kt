package io.devkit.netkit.scenario

/**
 * An immutable snapshot of everything the scenario engine needs.
 *
 * The controller publishes a new instance on every change; the interceptor reads
 * the latest one per request. Because the snapshot is immutable, a request that
 * is already in flight always evaluates against a single consistent scenario
 * even while the debug UI is being used on another thread.
 *
 * @param enabled master switch. When false NetKit passes every request through.
 * @param global behaviour for requests no rule claimed.
 * @param rules endpoint overrides, evaluated in order; the first match wins.
 */
data class NetworkScenario(
    val enabled: Boolean = true,
    val global: GlobalNetworkConfig = GlobalNetworkConfig(),
    val rules: List<EndpointRule> = emptyList(),
) {
    /** Rules currently eligible for matching. */
    val activeRules: List<EndpointRule> get() = rules.filter(EndpointRule::enabled)

    /**
     * True when the scenario cannot change any request. The engine short-circuits
     * on this so an enabled-but-empty NetKit costs one boolean per request.
     */
    val isIdle: Boolean
        get() = !enabled || (global.isNormal && rules.none(EndpointRule::enabled))

    /** The first enabled rule claiming [target], or `null`. */
    fun findRule(target: RequestTarget): EndpointRule? {
        // Indexed loop: this runs on every request and must not allocate an iterator.
        for (index in rules.indices) {
            val rule = rules[index]
            if (rule.enabled && rule.matches(target)) return rule
        }
        return null
    }

    companion object {
        /** NetKit enabled, nothing simulated — the state [reset] restores. */
        val Default: NetworkScenario = NetworkScenario()
    }
}
