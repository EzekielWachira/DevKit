package io.devkit.fillkit.debug.activation

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.devkit.fillkit.FillActivationRequest
import io.devkit.fillkit.FillActivationSource
import kotlinx.coroutines.flow.first

private val Context.fillKitPendingDataStore by preferencesDataStore(name = "fillkit_pending_activation_v1")

/** One consume-once activation waiting for its form to compose. */
internal data class PendingActivation(
    val request: FillActivationRequest,
    val createdAtMillis: Long,
) {
    fun isExpired(now: Long, ttlMillis: Long): Boolean = now - createdAtMillis >= ttlMillis
}

/**
 * Debug-only persistence so a cold-start deep link survives process creation.
 * Never holds more than one request and is deleted on consumption or expiry.
 */
internal class PendingActivationStore(context: Context) {
    private val store = context.applicationContext.fillKitPendingDataStore

    suspend fun load(): PendingActivation? =
        PendingActivationCodec.decode(store.data.first()[PENDING])

    suspend fun save(pending: PendingActivation) {
        store.edit { it[PENDING] = PendingActivationCodec.encode(pending) }
    }

    suspend fun clear() {
        store.edit { it.remove(PENDING) }
    }

    private companion object {
        val PENDING = stringPreferencesKey("pending_activation_json_v1")
    }
}

/**
 * Flat `key=value;` encoding. Deliberately dependency-free so it round-trips in a
 * plain JVM unit test, and small enough that a corrupt record is simply dropped.
 */
internal object PendingActivationCodec {
    private const val VERSION = 1

    fun encode(pending: PendingActivation): String = buildString {
        val request = pending.request
        field("v", VERSION.toString())
        field("t", pending.createdAtMillis.toString())
        field("f", request.formId)
        request.scenarioPackId?.let { field("sp", it) }
        request.scenarioId?.let { field("sc", it) }
        request.personaPackId?.let { field("pp", it) }
        request.personaId?.let { field("p", it) }
        request.locale?.let { field("l", it) }
        request.seed?.let { field("s", it.toString()) }
        field("g", request.generation.toString())
        request.configurationFingerprint?.let { field("c", it) }
        field("src", request.source.name)
    }

    fun decode(value: String?): PendingActivation? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val fields = linkedMapOf<String, String>()
            value.split(';').filter(String::isNotEmpty).forEach { entry ->
                val separator = entry.indexOf('=')
                require(separator > 0) { "malformed pending activation entry" }
                fields[entry.take(separator)] = entry.substring(separator + 1)
            }
            require(fields["v"]?.toIntOrNull() == VERSION) { "unsupported pending activation version" }
            PendingActivation(
                request = FillActivationRequest(
                    formId = requireNotNull(fields["f"]),
                    scenarioPackId = fields["sp"],
                    scenarioId = fields["sc"],
                    personaPackId = fields["pp"],
                    personaId = fields["p"],
                    locale = fields["l"],
                    seed = fields["s"]?.toLong(),
                    generation = fields["g"]?.toInt() ?: 0,
                    configurationFingerprint = fields["c"],
                    source = fields["src"]?.let { name ->
                        runCatching { FillActivationSource.valueOf(name) }.getOrNull()
                    } ?: FillActivationSource.DeepLink,
                ),
                createdAtMillis = requireNotNull(fields["t"]).toLong(),
            )
        }.getOrNull()
    }

    private fun StringBuilder.field(key: String, value: String) {
        require(!value.contains(';') && !value.contains('=')) { "pending activation value cannot contain ; or =" }
        append(key).append('=').append(value).append(';')
    }
}
