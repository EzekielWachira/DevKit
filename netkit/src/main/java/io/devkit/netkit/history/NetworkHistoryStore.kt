package io.devkit.netkit.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Stores the requests that passed through the NetKit interceptor.
 *
 * Implementations are written to from OkHttp threads and read from Compose, so
 * they must be thread-safe and must publish immutable lists.
 */
interface NetworkHistoryStore {

    /** Newest first. Emits a new immutable list on every change. */
    val records: StateFlow<List<NetworkRecord>>

    /** Process-unique id for the next record. */
    fun nextRecordId(): Long

    /** Appends [record], evicting the oldest entries past the configured limit. */
    fun record(record: NetworkRecord)

    /** Drops every stored record. Scenario configuration is untouched. */
    fun clear()
}

/**
 * Bounded in-memory history.
 *
 * NetKit 0.1 keeps history in RAM only: it disappears with the process, which is
 * the right trade-off for a debug tool and avoids writing captured traffic to
 * disk. Writes are serialised on a private lock; reads go through a
 * [StateFlow] and never block the writer.
 *
 * @param maxEntries the newest N records to keep; must be positive.
 */
class InMemoryNetworkHistoryStore(
    private val maxEntries: Int,
) : NetworkHistoryStore {

    init {
        require(maxEntries > 0) { "NetKit history limit must be positive (was $maxEntries)" }
    }

    private val lock = Any()
    private val ids = AtomicLong(0)

    // Newest first, mirroring the UI order so no sorting happens during composition.
    private val buffer = ArrayDeque<NetworkRecord>()
    private val _records = MutableStateFlow<List<NetworkRecord>>(emptyList())

    override val records: StateFlow<List<NetworkRecord>> = _records.asStateFlow()

    override fun nextRecordId(): Long = ids.incrementAndGet()

    override fun record(record: NetworkRecord) {
        val snapshot = synchronized(lock) {
            buffer.addFirst(record)
            while (buffer.size > maxEntries) buffer.removeLast()
            buffer.toList()
        }
        _records.value = snapshot
    }

    override fun clear() {
        synchronized(lock) { buffer.clear() }
        _records.value = emptyList()
    }
}

/**
 * History sink that stores nothing, installed when
 * [io.devkit.netkit.config.NetKitConfig.historyEnabled] is false.
 */
object NoOpNetworkHistoryStore : NetworkHistoryStore {
    private val ids = AtomicLong(0)
    private val empty = MutableStateFlow<List<NetworkRecord>>(emptyList())

    override val records: StateFlow<List<NetworkRecord>> = empty.asStateFlow()
    override fun nextRecordId(): Long = ids.incrementAndGet()
    override fun record(record: NetworkRecord) = Unit
    override fun clear() = Unit
}
