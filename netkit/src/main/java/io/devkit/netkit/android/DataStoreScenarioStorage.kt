package io.devkit.netkit.android

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.devkit.netkit.NetKit
import io.devkit.netkit.config.NetKitConfig
import io.devkit.netkit.scenario.persistence.ScenarioStorage
import kotlinx.coroutines.flow.first

private val Context.netKitScenarioDataStore by preferencesDataStore(name = "netkit_scenarios_v1")

/**
 * Saved scenarios on disk, backed by DataStore Preferences.
 *
 * The only Android-aware piece of NetKit's persistence, and the reason
 * everything above it — the repository, the manager, the serializer — can be
 * unit-tested with no emulator. One string key holds the whole versioned
 * document, which is the right shape for data that is small, always read
 * together, and needs a migration story rather than a query language.
 *
 * DataStore gives NetKit atomic writes and a coroutine API for free, so a save
 * interrupted by a process death leaves the previous document intact rather than
 * a half-written one.
 */
class DataStoreScenarioStorage(context: Context) : ScenarioStorage {

    private val store = context.applicationContext.netKitScenarioDataStore

    override suspend fun read(): String? = store.data.first()[DOCUMENT]

    override suspend fun write(document: String) {
        store.edit { it[DOCUMENT] = document }
    }

    override suspend fun clear() {
        store.edit { it.remove(DOCUMENT) }
    }

    private companion object {
        val DOCUMENT = stringPreferencesKey("scenario_document_v1")
    }
}

/**
 * Creates a NetKit runtime whose saved scenarios survive a restart.
 *
 * ```kotlin
 * val netKit = NetKit.create(context, NetKitConfig(builtInPacks = listOf(checkoutPack)))
 * ```
 *
 * Equivalent to `NetKit.create(config.copy(scenarioStorage = DataStoreScenarioStorage(context)))`,
 * and the form most applications want. An explicit `scenarioStorage` already in
 * [config] is respected, so a custom store is never silently replaced.
 */
fun NetKit.Companion.create(context: Context, config: NetKitConfig = NetKitConfig()): NetKit =
    create(
        config.copy(
            scenarioStorage = config.scenarioStorage ?: DataStoreScenarioStorage(context),
        ),
    )

/** [NetKit.initialize] with scenario persistence wired to [context]. */
fun NetKit.Companion.initialize(context: Context, config: NetKitConfig = NetKitConfig()): NetKit =
    initialize(
        config.copy(
            scenarioStorage = config.scenarioStorage ?: DataStoreScenarioStorage(context),
        ),
    )
