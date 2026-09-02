package io.devkit.netkit.scenario.persistence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place NetKit touches durable storage.
 *
 * Scenarios are a small, document-like body of data, so the interface is a
 * single string in and a single string out rather than a table. That keeps the
 * whole persistence surface to two suspending methods, makes schema evolution a
 * matter of versioning one document, and means an implementation can be
 * DataStore, a file, an in-memory map or a QA harness with no change above it.
 *
 * Implementations must be safe to call from any thread and must not block the
 * caller: NetKit only ever calls these from a coroutine, never from the
 * interceptor.
 */
interface ScenarioStorage {

    /** The stored document, or `null` when nothing has been written yet. */
    suspend fun read(): String?

    /** Replaces the stored document. */
    suspend fun write(document: String)

    /** Removes the stored document. */
    suspend fun clear()
}

/**
 * Storage that keeps the document in memory.
 *
 * The default when NetKit is created without a `Context`: scenarios still work
 * for the life of the process, they just do not survive a restart. Also the
 * implementation every persistence test uses.
 */
class InMemoryScenarioStorage(initial: String? = null) : ScenarioStorage {

    private val mutex = Mutex()
    private var document: String? = initial

    override suspend fun read(): String? = mutex.withLock { document }

    override suspend fun write(document: String) {
        mutex.withLock { this.document = document }
    }

    override suspend fun clear() {
        mutex.withLock { document = null }
    }
}

/** Storage that discards everything, for a NetKit built with persistence off. */
object NoOpScenarioStorage : ScenarioStorage {
    override suspend fun read(): String? = null
    override suspend fun write(document: String) = Unit
    override suspend fun clear() = Unit
}
