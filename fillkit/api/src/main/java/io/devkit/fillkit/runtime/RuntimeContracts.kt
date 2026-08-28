package io.devkit.fillkit.runtime

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.geometry.Rect
import io.devkit.fillkit.FillActivationRejection
import io.devkit.fillkit.FillActivationRequest
import io.devkit.fillkit.FillActivationResult
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.FillKitController
import io.devkit.fillkit.FillGenerator
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillTarget
import io.devkit.fillkit.FillType
import io.devkit.fillkit.CallbackFillTarget
import io.devkit.fillkit.FieldMetadata
import io.devkit.fillkit.FieldOverlayBehavior
import io.devkit.fillkit.FillTypeSuggestion
import io.devkit.fillkit.ContentTypeMapper

/**
 * Runtime SPI for scenario activation. `fillkit-debug` installs the real engine;
 * without it every request is rejected, so an application can call
 * [io.devkit.fillkit.FillKit.activate] from shared code.
 */
fun interface FillKitActivator {
    fun activate(request: FillActivationRequest): FillActivationResult
}

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
    val target: FillTarget<T>,
    val generator: FillGenerator<T>? = null,
    val overlay: FieldOverlayBehavior = FieldOverlayBehavior.Default,
    /** Overrides the form locale for this field only. */
    val locale: FillLocale? = null,
) {
    constructor(
        id: String,
        label: String?,
        group: String?,
        type: FillType<T>,
        currentValue: T?,
        onFill: (T) -> Unit,
        onClear: (() -> Unit)?,
        generator: FillGenerator<T>? = null,
    ) : this(id, label, group, type, CallbackFillTarget(currentValue, onFill, onClear), generator)
}

data class FillKitSuggestionCandidate(
    val metadata: FieldMetadata,
    val target: FillTarget<String>?,
    val contentType: ContentType? = null,
)

data class FillKitContentTypeField(
    val id: String,
    val label: String?,
    val group: String?,
    val contentType: ContentType,
    val target: FillTarget<String>,
    val mapper: ContentTypeMapper? = null,
    val generator: FillGenerator<String>? = null,
    val overlay: FieldOverlayBehavior = FieldOverlayBehavior.Default,
    val locale: FillLocale? = null,
)

data class RegisteredSuggestion(
    val owner: Any,
    val id: String,
    val label: String,
    val suggestions: List<FillTypeSuggestion>,
    val accepted: Boolean,
)

/** Composition-scoped registry contract. Implementations must not retain detached owners. */
interface FillKitRegistry {
    val formId: String

    /**
     * Focus and bounds reporting for the contextual field overlay. Both come
     * from ordinary Compose focus and layout callbacks on the field's own
     * modifier node; FillKit never inspects accessibility focus globally.
     */
    fun setFieldFocus(owner: Any, focused: Boolean) {}
    fun setFieldBounds(owner: Any, bounds: Rect) {}

    fun <T : Any> register(owner: Any, field: FillKitField<T>)
    fun <T : Any> update(owner: Any, field: FillKitField<T>)
    fun unregister(owner: Any)
    fun registerContentType(owner: Any, field: FillKitContentTypeField) {}
    fun updateContentType(owner: Any, field: FillKitContentTypeField) {}
    fun registerSuggestion(owner: Any, candidate: FillKitSuggestionCandidate) {}
    fun updateSuggestion(owner: Any, candidate: FillKitSuggestionCandidate) {}
    fun unregisterSuggestion(owner: Any) {}
}

interface FillKitCommands {
    fun fillAll()
    fun regenerateAll()
    fun clearAll()
    fun fill(fieldId: String)
    fun clear(fieldId: String)
    fun applyScenario(scenarioId: String)
    fun selectPersona(personaId: String)
    fun selectRandomPersona()
    fun changeLocale(locale: FillLocale)
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

    @Volatile
    var activator: FillKitActivator? = null

    fun bind(controller: FillKitController?, commands: FillKitCommands?) {
        controller?.bind(commands)
    }

    fun activate(request: FillActivationRequest): FillActivationResult =
        activator?.activate(request) ?: FillActivationResult.Rejected(
            FillActivationRejection.NoRuntime,
            "FillKit activation needs the fillkit-debug runtime; this build has none.",
        )
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
