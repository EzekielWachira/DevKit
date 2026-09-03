package io.devkit.netkit.state

import io.devkit.netkit.scenario.run.ChaosStatistics
import io.devkit.netkit.scenario.run.ExecutionEvent
import io.devkit.netkit.scenario.run.RuleStatistics
import io.devkit.netkit.scenario.run.ScenarioRun
import io.devkit.netkit.scenario.serialization.ReproductionExport
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything a UI does with the **current execution** of a scenario.
 *
 * Split out of [NetKitController] and [ScenarioController] along the same seam
 * 0.3 draws everywhere else:
 *
 * ```text
 * NetKitController    the network right now      (temporary overrides, chaos)
 * ScenarioController  definitions on disk        (saved scenarios, packs)
 * RunController       this execution             (seed, counters, timeline)
 * ```
 *
 * Keeping runs out of `ScenarioController` matters for more than tidiness: a run
 * has no persistence, no validation and no import path, and folding it into the
 * definition API would have invited exactly the mistake 0.3 is built to avoid —
 * runtime state leaking into a saved scenario.
 *
 * Free of Compose, Android and OkHttp types, so an IDE bridge or an
 * instrumentation harness can drive a reproduction remotely.
 *
 * **Thread safety.** Reads are `StateFlow`s and are safe from any thread; the
 * restart methods are safe to call from any thread and apply atomically.
 */
interface RunController {

    /** The run in progress, or `null` when nothing is active. */
    val current: StateFlow<ScenarioRun?>

    /** Decisions made during this run, oldest first and bounded. */
    val timeline: StateFlow<List<ExecutionEvent>>

    /** Per-rule funnel counters for this run, keyed on rule id. */
    val ruleStatistics: StateFlow<Map<String, RuleStatistics>>

    /** Chaos counters for this run. */
    val chaosStatistics: StateFlow<ChaosStatistics>

    /** The seed in force, or `null` when no run is active. */
    val seed: Long?

    // ---- restarting --------------------------------------------------------

    /**
     * Restarts the run on the **same seed**.
     *
     * The reproduction workflow's payoff. Resets the random stream, rule hit
     * counters, response-sequence cursors, previous-result state, statistics and
     * the timeline, then starts counting again from evaluation #1 — so the same
     * sequence of requests produces the same sequence of decisions.
     *
     * Distinct from [ScenarioController.resetAllSequences], which rewinds only
     * response sequences and leaves everything stochastic where it was.
     *
     * @return the new run, or `null` when nothing was running.
     */
    fun restartWithSameSeed(): ScenarioRun?

    /** Restarts on a freshly generated seed. `null` when nothing is running. */
    fun restartWithNewSeed(): ScenarioRun?

    /**
     * Restarts on [seed] — the developer's side of "reproduce what QA saw".
     *
     * The value is normalised into NetKit's readable seed range, so a seed
     * transcribed from a screenshot works whether or not it was truncated.
     *
     * @return the new run, or `null` when nothing is running.
     */
    fun restartWithSeed(seed: Long): ScenarioRun?

    /**
     * Clears counters, cursors and statistics **without** starting a new run.
     *
     * Keeps the run id and start time, so reproduction metadata already copied
     * into a ticket stays accurate.
     */
    fun resetRuntimeState()

    // ---- reproduction ------------------------------------------------------

    /**
     * The copyable reproduction summary: scenario, id, seed, schema, run and
     * NetKit version.
     *
     * Contains no credentials, no headers and no payloads — see
     * [io.devkit.netkit.scenario.serialization.ReproductionExporter].
     *
     * @return the summary, or `null` when no run is active.
     */
    fun reproductionSummary(): String?

    /**
     * A safe, shareable execution trace of this run.
     *
     * @return the trace, or `null` when no run is active.
     */
    fun traceSummary(): String?

    /**
     * A portable `.netkit-run.json` carrying the scenario, the seed and
     * optionally the trace, for a developer to import and replay.
     *
     * @param includeTrace whether to embed the (already sanitised) trace.
     * @return the export, or `null` when no run is active.
     */
    fun exportReproduction(includeTrace: Boolean = true): ReproductionExport?

    /** Turns the execution timeline on or off. */
    fun setTimelineEnabled(enabled: Boolean)

    /** Whether the execution timeline is recording. */
    val isTimelineEnabled: Boolean
}
