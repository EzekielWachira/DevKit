package io.devkit.fillkit.debug.persistence

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.fillKitQaDataStore by preferencesDataStore(name = "fillkit_qa_v1")

/** One remembered launch. Stored as a flat key so no database is involved. */
internal data class RecentScenario(
    val packId: String?,
    val scenarioId: String,
    val formId: String,
) {
    val key: String get() = "${packId.orEmpty()}/$scenarioId/$formId"

    companion object {
        fun parse(key: String): RecentScenario? {
            val parts = key.split('/')
            if (parts.size != 3 || parts[1].isBlank() || parts[2].isBlank()) return null
            return RecentScenario(parts[0].takeIf(String::isNotBlank), parts[1], parts[2])
        }
    }
}

/** Lightweight QA launcher history and favorites; debug builds only. */
internal class QaPreferencesStore(context: Context) {
    private val store = context.applicationContext.fillKitQaDataStore

    val recent: Flow<List<RecentScenario>> = store.data.map { preferences ->
        decode(preferences[RECENT]).mapNotNull(RecentScenario::parse)
    }

    val favorites: Flow<Set<String>> = store.data.map { preferences -> decode(preferences[FAVORITES]).toSet() }

    suspend fun remember(entry: RecentScenario, limit: Int) {
        if (limit <= 0) return
        store.edit { preferences ->
            val current = decode(preferences[RECENT]).filterNot { it == entry.key }
            preferences[RECENT] = encode(listOf(entry.key) + current.take(limit - 1))
        }
    }

    suspend fun toggleFavorite(key: String) {
        store.edit { preferences ->
            val current = decode(preferences[FAVORITES]).toMutableList()
            if (!current.remove(key)) current += key
            preferences[FAVORITES] = encode(current)
        }
    }

    suspend fun clearRecent() {
        store.edit { it.remove(RECENT) }
    }

    private fun encode(values: List<String>): String = JSONArray().apply { values.forEach(::put) }.toString()

    private fun decode(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            List(array.length()) { array.getString(it) }
        }.getOrElse { emptyList() }
    }

    private companion object {
        val RECENT = stringPreferencesKey("recent_scenarios_v1")
        val FAVORITES = stringPreferencesKey("favorite_scenarios_v1")
    }
}
