package io.devkit.fillkit.debug.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.devkit.fillkit.FillGenerator
import io.devkit.fillkit.FillKitConfig
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillScenario
import io.devkit.fillkit.FillType
import io.devkit.fillkit.FillValue
import io.devkit.fillkit.ScenarioValidationMode
import io.devkit.fillkit.engine.FakeDataEngine
import io.devkit.fillkit.engine.FakePersona
import io.devkit.fillkit.runtime.FillKitCommands
import io.devkit.fillkit.runtime.FillKitField
import io.devkit.fillkit.runtime.FillKitRegistry

internal data class StoredField(
    val owner: Any,
    val id: String,
    val label: String,
    val group: String?,
    val type: FillType<*>,
    val currentValue: Any?,
    val onFill: (Any) -> Unit,
    val onClear: (() -> Unit)?,
)

internal class FormRegistry(
    val formId: String,
    initialLocaleTag: String,
    private val config: FillKitConfig,
    private val logger: (String) -> Unit,
) : FillKitRegistry, FillKitCommands {
    private val registrations = mutableStateMapOf<Any, StoredField>()
    private val registrationOrder = mutableStateListOf<Any>()
    private var engine = FakeDataEngine(config.seed, initialLocaleTag)

    var localeTag by mutableStateOf(engine.locale.locale)
        private set

    var persona by mutableStateOf(engine.newPersona())
        private set

    val fields: List<StoredField> get() = registrationOrder.mapNotNull(registrations::get)

    override fun <T : Any> register(owner: Any, field: FillKitField<T>) {
        val duplicate = registrations.values.firstOrNull { it.id == field.id && it.owner !== owner }
        if (duplicate != null) log("duplicate field ID \"${field.id}\" in form \"$formId\"")
        if (owner !in registrations) registrationOrder += owner
        registrations[owner] = field.erase(owner)
    }

    override fun <T : Any> update(owner: Any, field: FillKitField<T>) {
        if (owner !in registrations) register(owner, field) else registrations[owner] = field.erase(owner)
    }

    override fun unregister(owner: Any) {
        registrations.remove(owner)
        registrationOrder.remove(owner)
    }

    override fun fillAll() {
        fields.forEach(::fillGenerated)
    }

    override fun regenerateAll() {
        persona = engine.newPersona()
        fillAll()
    }

    override fun clearAll() {
        fields.forEach(::clear)
    }

    override fun fill(fieldId: String) {
        field(fieldId)?.let(::fillGenerated)
    }

    override fun clear(fieldId: String) {
        field(fieldId)?.let(::clear)
    }

    override fun applyScenario(scenarioId: String) {
        val scenario = config.scenarios.firstOrNull { it.id == scenarioId }
        if (scenario == null) {
            problem("scenario \"$scenarioId\" is not configured for form \"$formId\"")
            return
        }
        applyScenario(scenario)
    }

    fun changeLocale(tag: String) {
        engine = FakeDataEngine(config.seed, tag)
        localeTag = engine.locale.locale
        persona = engine.newPersona()
        fillAll()
    }

    private fun applyScenario(scenario: FillScenario) {
        scenario.values.forEach { (id, value) ->
            val field = field(id)
            when {
                field == null -> problem("scenario \"${scenario.id}\" references unknown field \"$id\"")
                !field.type.accepts(value) -> problem(
                    "scenario \"${scenario.id}\", field \"$id\": expected ${field.type.valueName()}, got ${value.raw::class.simpleName}",
                )
                else -> field.onFill(value.raw)
            }
        }
    }

    private fun fillGenerated(field: StoredField) {
        try {
            val generated = when (val type = field.type) {
                is FillType.Custom<*> -> customValue(type)
                else -> generateBuiltIn(type)
            }
            field.onFill(generated)
        } catch (error: Exception) {
            problem("cannot fill field \"${field.id}\": ${error.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun generateBuiltIn(type: FillType<*>): Any =
        engine.generate(type as FillType<Any>, persona)

    private fun customValue(type: FillType.Custom<*>): Any {
        val generator = config.customGenerators[type.key]
            ?: error("no custom generator registered for key \"${type.key}\"")
        @Suppress("UNCHECKED_CAST")
        val value = (generator as FillGenerator<Any>).generate(engine.generationContext(FillLocale.Code(localeTag)))
        require(type.valueClass.isInstance(value)) {
            "custom generator \"${type.key}\" returned ${value::class.simpleName}, expected ${type.valueClass.simpleName}"
        }
        return value
    }

    private fun clear(field: StoredField) {
        when {
            field.onClear != null -> field.onClear.invoke()
            field.currentValue is String -> field.onFill("")
            else -> log("field \"${field.id}\" has no clear behavior")
        }
    }

    private fun field(id: String): StoredField? = registrations.values.firstOrNull { it.id == id }

    private fun problem(message: String) {
        val diagnostic = "FillKit configuration error: $message"
        if (config.scenarioValidationMode == ScenarioValidationMode.Strict) error(diagnostic) else log(diagnostic)
    }

    private fun log(message: String) {
        if (config.loggingEnabled) logger(message)
    }
}

private fun <T : Any> FillKitField<T>.erase(owner: Any) = StoredField(
    owner = owner,
    id = id,
    label = label ?: id.humanize(),
    group = group,
    type = type,
    currentValue = currentValue,
    onFill = { value ->
        @Suppress("UNCHECKED_CAST")
        onFill(value as T)
    },
    onClear = onClear,
)

private fun String.humanize(): String =
    replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('-', ' ').replaceFirstChar(Char::uppercase)

private fun FillType<*>.valueName(): String = when (this) {
    FillType.Age, is FillType.Integer -> "Int"
    is FillType.Decimal -> "Double"
    is FillType.BooleanValue -> "Boolean"
    FillType.DateOfBirth, is FillType.Date -> "FillDate"
    is FillType.Custom<*> -> valueClass.simpleName ?: "custom value"
    else -> "String"
}

private fun FillType<*>.accepts(value: FillValue): Boolean = when (this) {
    FillType.Age, is FillType.Integer -> value is FillValue.Integer
    is FillType.Decimal -> value is FillValue.Decimal
    is FillType.BooleanValue -> value is FillValue.BooleanValue
    FillType.DateOfBirth, is FillType.Date -> value is FillValue.DateValue
    is FillType.Custom<*> -> valueClass.isInstance(value.raw)
    else -> value is FillValue.Text
}
