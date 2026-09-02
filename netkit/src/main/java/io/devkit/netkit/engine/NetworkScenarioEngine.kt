package io.devkit.netkit.engine

import io.devkit.netkit.scenario.RequestTarget
import io.devkit.netkit.scenario.runtime.ActiveNetworkConfiguration
import io.devkit.netkit.scenario.runtime.ScenarioExecutionState

/**
 * Decides what happens to each request.
 *
 * The engine is the only component the interceptor talks to, and it is the seam
 * a future Android Studio bridge or automation API plugs into: everything above
 * it manipulates state, everything below it executes decisions.
 *
 * Implementations must be safe to call concurrently from OkHttp threads.
 */
interface NetworkScenarioEngine {

    /**
     * True when no configured behaviour could affect any request. The
     * interceptor uses this to keep the untouched path cheap.
     */
    val isIdle: Boolean

    /** Decides what to do with [target]. Must not throw. */
    fun evaluate(target: RequestTarget): ScenarioDecision
}

/**
 * The engine NetKit installs.
 *
 * Reads the current configuration through [configurationProvider], which is
 * backed by the controller's `StateFlow` — a plain volatile read, never a
 * `Flow` collection or a storage lookup, so an active scenario costs the same
 * per request as a temporary rule did in 0.1.
 *
 * Each call reads the provider exactly once, so a request always evaluates
 * against a single consistent snapshot even if the debug UI changes the
 * configuration mid-evaluation.
 *
 * @param configurationProvider supplies the latest immutable snapshot.
 * @param executionState the cursor layer response sequences advance.
 */
class DefaultNetworkScenarioEngine(
    private val executionState: ScenarioExecutionState,
    private val configurationProvider: () -> ActiveNetworkConfiguration,
) : NetworkScenarioEngine {

    override val isIdle: Boolean get() = configurationProvider().isIdle

    /**
     * Reads the provider **once** and both short-circuits and evaluates against
     * that one value. Asking `isIdle` and then `evaluate` would read twice, and
     * a request arriving as the console toggles the master switch could see one
     * answer from each.
     */
    override fun evaluate(target: RequestTarget): ScenarioDecision {
        val configuration = configurationProvider()
        if (configuration.isIdle) return IDLE
        return ScenarioEvaluator.evaluate(configuration, target, executionState)
    }

    private companion object {
        /** The overwhelmingly common decision should not allocate. */
        val IDLE = ScenarioDecision.PassThrough()
    }
}
