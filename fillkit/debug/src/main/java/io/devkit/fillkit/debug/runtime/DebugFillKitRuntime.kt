package io.devkit.fillkit.debug.runtime

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.FillKitController
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.debug.ui.FillKitOverlay
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
        val initialLocale = when (val locale = config.locale) {
            FillLocale.System -> Locale.getDefault().toLanguageTag()
            is FillLocale.Code -> locale.value
        }
        val registry = remember(formId, config, initialLocale) {
            FormRegistry(formId, initialLocale, config) { Log.w("FillKit", it) }
        }
        DisposableEffect(controller, registry) {
            FillKitRuntimeProvider.bind(controller, registry)
            onDispose { FillKitRuntimeProvider.bind(controller, null) }
        }

        CompositionLocalProvider(LocalFillKitRegistry provides registry) {
            Box(modifier = modifier) {
                content()
                FillKitOverlay(registry, config)
            }
        }
    }
}
