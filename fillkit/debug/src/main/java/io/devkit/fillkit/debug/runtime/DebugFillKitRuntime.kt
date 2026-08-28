package io.devkit.fillkit.debug.runtime

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.semantics
import io.devkit.fillkit.FillActivationRequest
import io.devkit.fillkit.FillActivationResult
import io.devkit.fillkit.FillKit
import io.devkit.fillkit.FillKitCommand
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.FillKitController
import io.devkit.fillkit.FillKitFormSnapshot
import io.devkit.fillkit.FillKitPack
import io.devkit.fillkit.FillKitTestSemantics
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.debug.activation.FillKitActivationEngine
import io.devkit.fillkit.debug.ui.FillKitOverlay
import io.devkit.fillkit.debug.persistence.RuntimePersonaStore
import io.devkit.fillkit.runtime.FillKitRuntime
import io.devkit.fillkit.runtime.FillKitRuntimeProvider
import io.devkit.fillkit.runtime.LocalFillKitRegistry
import java.util.Locale

object DebugFillKitRuntime : FillKitRuntime {
    @Composable
    override fun Host(
        formId: String,
        modifier: Modifier,
        config: FillKitConfig,
        controller: FillKitController?,
        content: @Composable () -> Unit,
    ) {
        val context = LocalContext.current
        val persistence = remember(context.applicationContext) { RuntimePersonaStore(context.applicationContext) }
        val persistenceScope = rememberCoroutineScope()
        val applicationPacks = FillKit.debugConfig.packs
        val effectiveConfig = remember(config, applicationPacks) { config.withApplicationPacks(applicationPacks) }
        val initialLocale = when (val locale = config.locale) {
            FillLocale.System -> Locale.getDefault().toLanguageTag()
            is FillLocale.Code -> locale.value
        }
        val registry = remember(formId, effectiveConfig, initialLocale) {
            FormRegistry(
                formId, initialLocale, effectiveConfig, { Log.w("FillKit", it) }, persistence, applicationPacks,
            )
        }
        DisposableEffect(context.applicationContext) { FillKitActivationEngine.attach(context); onDispose {} }
        // One frame late on purpose: field modifier nodes attach after this
        // composition applies, and a queued scenario must fill a populated form.
        LaunchedEffect(registry, FillKitActivationEngine.pending?.request) {
            withFrameNanos { }
            FillKitActivationEngine.pump()
        }
        DisposableEffect(controller, registry) {
            FillKitRuntimeProvider.bind(controller, registry)
            registry.attachPersistence(persistenceScope)
            FillKitActivationEngine.registerHost(registry)
            onDispose {
                FillKitActivationEngine.unregisterHost(registry)
                registry.detachPersistence()
                FillKitRuntimeProvider.bind(controller, null)
            }
        }

        CompositionLocalProvider(LocalFillKitRegistry provides registry) {
            Box(modifier = modifier.fillKitTestControls(registry)) {
                content()
                FillKitOverlay(registry, effectiveConfig)
            }
        }
    }
}

/**
 * Publishes FillKit's debug control surface as Compose semantics.
 *
 * Only this runtime applies these keys, so a release build — which uses the no-op
 * runtime in `fillkit-api` — has no FillKit actions in its semantics tree at all.
 */
private fun Modifier.fillKitTestControls(registry: FormRegistry): Modifier = semantics {
    this[FillKitTestSemantics.ControlFormId] = registry.formId
    this[FillKitTestSemantics.Activate] = AccessibilityAction<
        (FillActivationRequest, MutableList<FillActivationResult>) -> Boolean,
        >("FillKitActivate") { request, results ->
        results += registry.activate(request)
        true
    }
    this[FillKitTestSemantics.Command] = AccessibilityAction<(FillKitCommand) -> Boolean>("FillKitCommand") { command ->
        registry.execute(command)
    }
    this[FillKitTestSemantics.Snapshot] = AccessibilityAction<
        (MutableList<FillKitFormSnapshot>) -> Boolean,
        >("FillKitSnapshot") { out ->
        out += registry.snapshot()
        true
    }
}

/**
 * Application-wide packs sit below form-local packs: field, then form, then
 * application, then built-in. A collision that would make the merged config
 * invalid leaves the form's own configuration untouched.
 */
internal fun FillKitConfig.withApplicationPacks(applicationPacks: List<FillKitPack>): FillKitConfig {
    if (applicationPacks.isEmpty()) return this
    val local = packs.mapTo(mutableSetOf(), FillKitPack::id)
    val extra = applicationPacks.filterNot { it.id in local }
    if (extra.isEmpty()) return this
    return runCatching { copy(packs = extra + packs) }.getOrElse { error ->
        Log.w("FillKit", "application packs were ignored for this form: ${error.message}")
        this
    }
}
