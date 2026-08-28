package io.devkit.fillkit.debug.activation

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.devkit.fillkit.FillActivationRejection
import io.devkit.fillkit.FillActivationRequest
import io.devkit.fillkit.FillActivationResult
import io.devkit.fillkit.FillKit
import io.devkit.fillkit.debug.runtime.FormRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The single orchestration path for every FillKit entry point.
 *
 * The developer panel, the QA launcher, Compose tests and deep links all submit a
 * [FillActivationRequest] here. When the target form is composed the request is
 * applied immediately; otherwise it is stored consume-once until that form
 * appears, survives process death, and expires rather than surprising a screen
 * opened much later.
 */
internal object FillKitActivationEngine {
    private val hosts = mutableStateListOf<FormRegistry>()
    private var store: PendingActivationStore? = null
    private var scope: CoroutineScope? = null

    /** Exposed as Compose state so the QA UI can show that something is queued. */
    var pending by mutableStateOf<PendingActivation?>(null)
        private set

    private var navigationRequested = false

    val liveForms: List<String> get() = hosts.map(FormRegistry::formId).distinct()

    fun attach(context: Context) {
        if (store != null) return
        val applicationContext = context.applicationContext
        store = PendingActivationStore(applicationContext)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { active ->
            active.launch {
                val restored = runCatching { store?.load() }.getOrNull() ?: return@launch
                if (restored.isExpired(now(), ttlMillis())) {
                    log("discarded a pending activation for \"${restored.request.formId}\" because it expired")
                    clearPersisted()
                } else if (pending == null) {
                    pending = restored
                    navigationRequested = false
                    pump()
                }
            }
        }
    }

    /**
     * Entry point for every activation. Applies to a live host when one exists,
     * otherwise queues consume-once and asks the application to navigate.
     */
    fun submit(request: FillActivationRequest): FillActivationResult {
        val host = hosts.lastOrNull { it.formId == request.formId }
        if (host != null) return host.activate(request)

        val queued = PendingActivation(request, now())
        pending = queued
        navigationRequested = false
        persist(queued)
        pump()
        return FillActivationResult.Pending(request.formId, queued.createdAtMillis + ttlMillis())
    }

    /** Blocking variant for the deep-link activity, which must persist before it finishes. */
    fun submitFromColdStart(context: Context, request: FillActivationRequest): FillActivationResult {
        attach(context)
        val queued = PendingActivation(request, now())
        pending = queued
        navigationRequested = false
        runCatching { runBlocking { PendingActivationStore(context).save(queued) } }
            .onFailure { log("could not persist the pending activation: ${it.message}") }
        return FillActivationResult.Pending(request.formId, queued.createdAtMillis + ttlMillis())
    }

    fun registerHost(registry: FormRegistry) {
        if (hosts.none { it === registry }) hosts += registry
    }

    fun unregisterHost(registry: FormRegistry) {
        hosts.removeAll { it === registry }
    }

    fun cancelPending() {
        pending = null
        navigationRequested = false
        clearPersisted()
    }

    fun reset() {
        hosts.clear()
        pending = null
        navigationRequested = false
        clearPersisted()
    }

    /**
     * Applies a queued request to a matching host, or asks the application
     * navigator to open the form. Safe to call on every composition: a request is
     * consumed exactly once and navigation is requested at most once.
     */
    /**
     * Applies a queued request to a matching host, or asks the application
     * navigator to open the form.
     *
     * Hosts call this one frame after they compose: a form's fields attach their
     * modifier nodes after the host's own effects run, so consuming earlier would
     * fill an empty registry.
     */
    fun pump() {
        val queued = pending ?: return
        if (queued.isExpired(now(), ttlMillis())) {
            log("discarded a pending activation for \"${queued.request.formId}\" because it expired")
            cancelPending()
            return
        }
        val host = hosts.lastOrNull { it.formId == queued.request.formId }
        if (host != null) {
            pending = null
            navigationRequested = false
            clearPersisted()
            val result = host.activate(queued.request)
            if (result is FillActivationResult.Rejected) {
                log("pending activation for \"${queued.request.formId}\" was rejected: ${result.message}")
            }
            return
        }
        if (!navigationRequested) {
            navigationRequested = true
            val navigator = FillKit.debugConfig.navigator
            if (navigator == null) {
                log(
                    "no FillKit navigation adapter is configured; open \"${queued.request.formId}\" " +
                        "yourself and the queued scenario will be applied",
                )
            } else if (!navigator.navigateTo(queued.request)) {
                log("the application navigator declined to open \"${queued.request.formId}\"")
            }
        }
    }

    fun rejectUnknownForm(formId: String) = FillActivationResult.Rejected(
        FillActivationRejection.InvalidRequest,
        "no FillKit host is registered for form \"$formId\"",
    )

    private fun persist(pending: PendingActivation) {
        val target = store ?: return
        scope?.launch { runCatching { target.save(pending) } }
    }

    private fun clearPersisted() {
        val target = store ?: return
        scope?.launch { runCatching { target.clear() } }
    }

    private fun ttlMillis(): Long = FillKit.debugConfig.pendingActivationTtlMillis

    private fun now(): Long = System.currentTimeMillis()

    private fun log(message: String) = Log.w("FillKit", message)
}
