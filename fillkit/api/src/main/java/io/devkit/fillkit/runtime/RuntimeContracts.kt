package io.devkit.fillkit.runtime

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.FillKitController
import io.devkit.fillkit.FillType

/** Runtime SPI used by the separately packaged debug artifact. */
interface FillKitRuntime {
    @Composable
    fun Host(
        formId: String,
        modifier: Modifier,
        config: FillKitConfig,
        controller: FillKitController?,
        content: @Composable () -> Unit,
    )
}

/** Immutable registration passed from a modifier node to its nearest host. */
data class FillKitField<T : Any>(
    val id: String,
    val label: String?,
    val group: String?,
    val type: FillType<T>,
    val currentValue: T?,
    val onFill: (T) -> Unit,
    val onClear: (() -> Unit)?,
)

/** Composition-scoped registry contract. Implementations must not retain detached owners. */
interface FillKitRegistry {
    fun <T : Any> register(owner: Any, field: FillKitField<T>)
    fun <T : Any> update(owner: Any, field: FillKitField<T>)
    fun unregister(owner: Any)
}

interface FillKitCommands {
    fun fillAll()
    fun regenerateAll()
    fun clearAll()
    fun fill(fieldId: String)
    fun clear(fieldId: String)
    fun applyScenario(scenarioId: String)
}

val LocalFillKitRegistry = staticCompositionLocalOf<FillKitRegistry?> { null }

/**
 * Process-wide runtime strategy. `fillkit-debug` installs its implementation
 * from a manifest provider before the first activity is created.
 */
object FillKitRuntimeProvider {
    @Volatile
    var current: FillKitRuntime = NoOpFillKitRuntime
        private set

    fun install(runtime: FillKitRuntime) {
        current = runtime
    }

    fun bind(controller: FillKitController?, commands: FillKitCommands?) {
        controller?.bind(commands)
    }
}

private object NoOpFillKitRuntime : FillKitRuntime {
    @Composable
    override fun Host(
        formId: String,
        modifier: Modifier,
        config: FillKitConfig,
        controller: FillKitController?,
        content: @Composable () -> Unit,
    ) {
        Box(modifier = modifier) { content() }
    }
}
