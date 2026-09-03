package io.devkit.netkit.scenario.run

import io.devkit.netkit.scenario.model.ScenarioId
import io.devkit.netkit.scenario.random.NetKitRandom
import io.devkit.netkit.scenario.random.RandomPurpose
import io.devkit.netkit.scenario.random.RunRandomSource
import io.devkit.netkit.scenario.random.SeedGenerator
import io.devkit.netkit.scenario.runtime.ScenarioExecutionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns every piece of mutable state that makes a run reproducible.
 *
 * ### Why this exists as its own class
 *
 * By 0.2, `ScenarioManager` already owned definitions, activation, packs, import
 * and export. Bolting seeds, counters, statistics and a timeline onto it would
 * have produced exactly the God class the architecture had so far avoided. The
 * split is along a real seam:
 *
 * ```text
 * ScenarioManager      what scenarios exist, which is active   (definitions)
 * ScenarioRunManager   what this execution has done so far     (runtime)
 * ```
 *
 * Definitions are persisted and immutable; runtime state is in-memory,
 * fast-changing and thrown away on every restart. Nothing here is ever written
 * into a saved scenario.
 *
 * ### Thread safety
 *
 * Every field is atomic or immutable, and the hot path — [beginEvaluation],
 * [nextRuleHit], [randomFor] — touches only atomics with no lock. That matters:
 * several OkHttp threads evaluate concurrently, and a lock on the request path
 * would both slow the app and, worse, change the concurrency the scenario is
 * meant to be reproducing.
 *
 * The one guarded section is [startRun], which has to swap five things together
 * so that no evaluation can see the new seed with the old counters.
 *
 * @param executionState the response-sequence cursors, reset together with
 *   everything else when a run restarts.
 * @param timeline the bounded decision log.
 * @param nowMillis the clock, injected so tests need not sleep.
 */
class ScenarioRunManager(
    private val executionState: ScenarioExecutionState,
    val timeline: ExecutionTimeline = ExecutionTimeline(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()

    private val _run = MutableStateFlow<ScenarioRun?>(null)

    /** The run in progress, or `null` when nothing stochastic is active. */
    val run: StateFlow<ScenarioRun?> = _run.asStateFlow()

    private val _ruleStats = MutableStateFlow<Map<String, RuleStatistics>>(emptyMap())

    /** Per-rule funnel counters for the current run, keyed on rule id. */
    val ruleStatistics: StateFlow<Map<String, RuleStatistics>> = _ruleStats.asStateFlow()

    private val _chaosStats = MutableStateFlow(ChaosStatistics())

    /** Chaos counters for the current run. */
    val chaosStatistics: StateFlow<ChaosStatistics> = _chaosStats.asStateFlow()

    /**
     * The source every stochastic decision draws from.
     *
     * Replaced wholesale on restart rather than reseeded in place, so an
     * evaluation that read the old reference finishes against a coherent seed
     * instead of half of each.
     */
    private val randomSource = AtomicReference(RunRandomSource(SeedGenerator.next()))

    private val evaluationCounter = AtomicLong(0)
    private val ruleHits = ConcurrentHashMap<String, AtomicLong>()
    private val ruleExecutions = ConcurrentHashMap<String, AtomicLong>()
    private val simulatedFailureSeen = AtomicBoolean(false)

    /** The seed the current run is using. */
    val seed: Long get() = randomSource.get().seed

    /** True when a run is in progress. */
    val isRunning: Boolean get() = _run.value != null

    // ---- lifecycle ---------------------------------------------------------

    /**
     * Starts a new run, discarding every counter, cursor and statistic.
     *
     * This is the single place a run boundary is drawn, and everything that
     * should reset does so here — sequence cursors, rule hit counts, previous-
     * result state, chaos statistics, the timeline and the random source. Having
     * one place means a 0.4 feature with its own runtime state has exactly one
     * function to add itself to, rather than five reset paths to remember.
     *
     * @param seed the seed to run with; a fresh one is generated when `null`.
     * @return the run that was started.
     */
    fun startRun(
        scenarioId: ScenarioId?,
        scenarioName: String?,
        reason: RunStartReason,
        seed: Long? = null,
    ): ScenarioRun = synchronized(lock) {
        val resolved = seed ?: SeedGenerator.next()
        val started = ScenarioRun(
            id = ScenarioRunId.random(),
            scenarioId = scenarioId,
            scenarioName = scenarioName,
            seed = resolved,
            startedAtMillis = nowMillis(),
            startReason = reason,
        )
        randomSource.set(RunRandomSource(resolved))
        clearRuntimeState()
        _run.value = started
        timeline.clear()
        timeline.record(
            ExecutionEvent(
                evaluationIndex = 0,
                atMillis = started.startedAtMillis,
                type = if (reason == RunStartReason.ACTIVATED) {
                    ExecutionEventType.RUN_STARTED
                } else {
                    ExecutionEventType.RUN_RESTARTED
                },
                ruleLabel = scenarioName,
                detail = "Seed $resolved",
                reason = reason.label,
            ),
        )
        started
    }

    /**
     * Restarts the current run on the same seed.
     *
     * The reproduction workflow's other half: a developer who imported a
     * scenario and a seed presses this and gets the same decision sequence the
     * reporter saw.
     *
     * @return the new run, or `null` when nothing is running.
     */
    fun restartWithSameSeed(): ScenarioRun? {
        val current = _run.value ?: return null
        return startRun(
            scenarioId = current.scenarioId,
            scenarioName = current.scenarioName,
            reason = RunStartReason.RESTARTED,
            seed = current.seed,
        )
    }

    /** Restarts on a freshly generated seed. `null` when nothing is running. */
    fun restartWithNewSeed(): ScenarioRun? = restartWithSeed(SeedGenerator.next())

    /** Restarts on [seed], normalising it into the readable range. */
    fun restartWithSeed(seed: Long): ScenarioRun? {
        val current = _run.value ?: return null
        return startRun(
            scenarioId = current.scenarioId,
            scenarioName = current.scenarioName,
            reason = RunStartReason.SEED_CHANGED,
            seed = SeedGenerator.normalize(seed),
        )
    }

    /**
     * Clears runtime state without starting a new run.
     *
     * Distinct from a restart on purpose: the run keeps its id and its start
     * time, so a QA engineer who reset counters mid-session has not invalidated
     * the reproduction metadata they already copied. The seed is untouched, but
     * note that evaluation indices restart from 1, which is what makes the
     * decisions after a reset match the decisions after a restart.
     */
    fun resetRuntimeState() {
        synchronized(lock) {
            clearRuntimeState()
            timeline.record(
                ExecutionEvent(
                    evaluationIndex = 0,
                    atMillis = nowMillis(),
                    type = ExecutionEventType.RUN_RESET,
                    detail = "Counters and cursors cleared",
                ),
            )
        }
    }

    /** Ends the run. Called when the scenario is deactivated and chaos is off. */
    fun stopRun() {
        synchronized(lock) {
            if (_run.value == null) return
            clearRuntimeState()
            _run.value = null
            timeline.clear()
        }
    }

    /** Everything a run boundary clears. Callers hold [lock]. */
    private fun clearRuntimeState() {
        evaluationCounter.set(0)
        ruleHits.clear()
        ruleExecutions.clear()
        simulatedFailureSeen.set(false)
        executionState.resetAll()
        _ruleStats.value = emptyMap()
        _chaosStats.value = ChaosStatistics()
        _run.update { current ->
            current?.copy(
                evaluationCount = 0,
                simulatedCount = 0,
                passThroughCount = 0,
                latencyInjectedCount = 0,
                totalInjectedLatencyMillis = 0,
            )
        }
    }

    // ---- per-request hot path ----------------------------------------------

    /**
     * Claims the next evaluation index.
     *
     * A single `getAndIncrement`, and the reason the whole design is reproducible
     * under concurrency: whichever thread wins the race gets a stable number, and
     * every random draw that request makes is derived from that number rather
     * than from a shared cursor. Two requests can never draw the same values, and
     * a request's values never depend on how many other requests are in flight.
     */
    fun beginEvaluation(): Long = evaluationCounter.incrementAndGet()

    /** The stream for one purpose in one evaluation. */
    fun randomFor(evaluationIndex: Long, purpose: RandomPurpose): NetKitRandom =
        randomSource.get().streamFor(evaluationIndex, purpose)

    /** The stream for one purpose in one evaluation, keyed on a rule id. */
    fun randomFor(evaluationIndex: Long, purpose: RandomPurpose, key: String): NetKitRandom =
        randomSource.get().streamFor(evaluationIndex, purpose, key)

    /**
     * Claims the next 1-based hit index for [ruleId].
     *
     * Called when a rule matches a request's method and path, *before* conditions
     * run — so "first request only" means the first request that reached the
     * rule, not the first that satisfied it. That is the reading that makes
     * `Exactly(1)` and `AtLeast(2)` partition the traffic between them.
     */
    fun nextRuleHit(ruleId: String): Long =
        ruleHits.computeIfAbsent(ruleId) { AtomicLong(0) }.incrementAndGet()

    /** How many times [ruleId] has produced a decision in this run. */
    fun executionsOf(ruleId: String): Long = ruleExecutions[ruleId]?.get() ?: 0

    /** True when any rule has already simulated a failure in this run. */
    val hasSimulatedFailure: Boolean get() = simulatedFailureSeen.get()

    // ---- statistics --------------------------------------------------------

    /**
     * Applies one evaluation's worth of rule statistics in a single update.
     *
     * Batched by [io.devkit.netkit.engine.ScenarioExecutionContext] rather than
     * written stage by stage: the published map is copy-on-write, and five
     * updates per rule per request would be real work on the request path for a
     * distinction nobody can observe.
     */
    fun applyRuleDeltas(deltas: List<RuleStatDelta>) {
        if (deltas.isEmpty()) return
        for (index in deltas.indices) {
            val delta = deltas[index]
            if (delta.executed) {
                ruleExecutions.computeIfAbsent(delta.ruleId) { AtomicLong(0) }.incrementAndGet()
            }
        }
        _ruleStats.update { current ->
            val next = current.toMutableMap()
            for (index in deltas.indices) {
                val delta = deltas[index]
                val existing = next[delta.ruleId]
                    ?: RuleStatistics(delta.ruleId, delta.ruleLabel)
                next[delta.ruleId] = delta.applyTo(existing)
            }
            next
        }
    }

    /** Records the outcome of one whole evaluation against the run totals. */
    fun recordOutcome(simulated: Boolean, injectedLatencyMillis: Long) {
        if (simulated) simulatedFailureSeen.set(true)
        _run.update { current ->
            current?.copy(
                evaluationCount = current.evaluationCount + 1,
                simulatedCount = current.simulatedCount + if (simulated) 1 else 0,
                passThroughCount = current.passThroughCount + if (simulated) 0 else 1,
                latencyInjectedCount = current.latencyInjectedCount +
                    if (injectedLatencyMillis > 0) 1 else 0,
                totalInjectedLatencyMillis = current.totalInjectedLatencyMillis +
                    injectedLatencyMillis.coerceAtLeast(0),
            )
        }
    }

    /** Applies [transform] to the chaos counters. */
    fun updateChaos(transform: (ChaosStatistics) -> ChaosStatistics) {
        _chaosStats.update(transform)
    }

    // ---- timeline ----------------------------------------------------------

    /** Appends [event] to the timeline, when the timeline is enabled. */
    fun record(event: ExecutionEvent) = timeline.record(event)

    /** Builds and records a request-scoped event at the current time. */
    fun record(
        evaluationIndex: Long,
        type: ExecutionEventType,
        method: String? = null,
        path: String? = null,
        ruleLabel: String? = null,
        detail: String? = null,
        reason: String? = null,
    ) {
        if (!timeline.enabled) return
        timeline.record(
            ExecutionEvent(
                evaluationIndex = evaluationIndex,
                atMillis = nowMillis(),
                type = type,
                method = method,
                path = path,
                ruleLabel = ruleLabel,
                detail = detail,
                reason = reason,
            ),
        )
    }

    /** The rule statistics as of now, most-executed first. */
    fun topRules(limit: Int = 5): List<RuleStatistics> = _ruleStats.value.values
        .filter { !it.isUntouched }
        .sortedWith(compareByDescending<RuleStatistics> { it.executed }.thenByDescending { it.matched })
        .take(limit)
}
