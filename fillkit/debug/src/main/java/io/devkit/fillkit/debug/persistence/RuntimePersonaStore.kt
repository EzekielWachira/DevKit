package io.devkit.fillkit.debug.persistence

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.devkit.fillkit.FillDate
import io.devkit.fillkit.FillLocale
import io.devkit.fillkit.FillPersona
import io.devkit.fillkit.FillValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.fillKitPersonaDataStore by preferencesDataStore(name = "fillkit_runtime_personas_v1")

/** Debug-only persistence boundary. DataStore never appears in FillKit's public API. */
internal interface RuntimePersonaPersistence {
    val personas: Flow<List<FillPersona>>
    suspend fun save(persona: FillPersona)
    suspend fun delete(id: String)
    suspend fun clear()
}

internal class RuntimePersonaStore(context: Context) : RuntimePersonaPersistence {
    private val store = context.applicationContext.fillKitPersonaDataStore

    override val personas: Flow<List<FillPersona>> = store.data.map { preferences ->
        PersonaJsonCodec.decode(preferences[PERSONAS].orEmpty())
    }

    override suspend fun save(persona: FillPersona) {
        store.edit { preferences ->
            val current = PersonaJsonCodec.decode(preferences[PERSONAS].orEmpty())
            preferences[PERSONAS] = PersonaJsonCodec.encode(current.filterNot { it.id == persona.id } + persona)
        }
    }

    override suspend fun delete(id: String) {
        store.edit { preferences ->
            preferences[PERSONAS] = PersonaJsonCodec.encode(
                PersonaJsonCodec.decode(preferences[PERSONAS].orEmpty()).filterNot { it.id == id },
            )
        }
    }

    override suspend fun clear() {
        store.edit { it.remove(PERSONAS) }
    }

    private companion object {
        val PERSONAS = stringPreferencesKey("personas_json_v1")
    }
}

internal object PersonaJsonCodec {
    fun encode(personas: List<FillPersona>): String = JSONArray().apply {
        personas.forEach { put(it.toJson()) }
    }.toString()

    fun decode(value: String): List<FillPersona> {
        if (value.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            List(array.length()) { array.getJSONObject(it).toPersona() }
        }.getOrElse { emptyList() }
    }

    private fun FillPersona.toJson() = JSONObject().apply {
        put("version", 1)
        put("id", id)
        put("name", name)
        put("locale", (locale as? FillLocale.Code)?.value)
        put("values", JSONObject().apply { values.forEach { (key, value) -> put(key, value.toJson()) } })
        put("metadata", JSONObject(metadata))
    }

    private fun FillValue.toJson() = JSONObject().apply {
        when (this@toJson) {
            is FillValue.Text -> { put("type", "string"); put("value", value) }
            is FillValue.Integer -> { put("type", "int"); put("value", value) }
            is FillValue.Decimal -> { put("type", "double"); put("value", value) }
            is FillValue.BooleanValue -> { put("type", "boolean"); put("value", value) }
            is FillValue.DateValue -> { put("type", "date"); put("value", value.toString()) }
        }
    }

    private fun JSONObject.toPersona(): FillPersona {
        require(optInt("version") == 1) { "unsupported persona persistence version" }
        val valuesObject = getJSONObject("values")
        val values = linkedMapOf<String, FillValue>()
        valuesObject.keys().forEach { key -> values[key] = valuesObject.getJSONObject(key).toFillValue() }
        val metadataObject = optJSONObject("metadata") ?: JSONObject()
        val metadata = linkedMapOf<String, String>()
        metadataObject.keys().forEach { key -> metadata[key] = metadataObject.getString(key) }
        return FillPersona(
            id = getString("id"),
            name = getString("name"),
            locale = optString("locale").takeIf(String::isNotBlank)?.let(FillLocale::Code),
            values = values,
            metadata = metadata,
        )
    }

    private fun JSONObject.toFillValue(): FillValue = when (getString("type")) {
        "string" -> FillValue.Text(getString("value"))
        "int" -> FillValue.Integer(getInt("value"))
        "double" -> FillValue.Decimal(getDouble("value"))
        "boolean" -> FillValue.BooleanValue(getBoolean("value"))
        "date" -> getString("value").split('-').map(String::toInt).let {
            FillValue.DateValue(FillDate(it[0], it[1], it[2]))
        }
        else -> error("unsupported persisted FillValue type")
    }
}
