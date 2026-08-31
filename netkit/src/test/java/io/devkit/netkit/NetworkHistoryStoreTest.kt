package io.devkit.netkit

import io.devkit.netkit.history.InMemoryNetworkHistoryStore
import io.devkit.netkit.history.NetworkOutcome
import io.devkit.netkit.history.NetworkRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NetworkHistoryStoreTest {

    private fun record(store: InMemoryNetworkHistoryStore, path: String) = NetworkRecord(
        id = store.nextRecordId(),
        startedAtMillis = 0,
        durationMillis = 1,
        method = "GET",
        scheme = "https",
        host = "api.example.com",
        path = path,
        url = "https://api.example.com$path",
        outcome = NetworkOutcome.Completed(200, "OK"),
    )

    @Test
    fun `records are stored newest first`() {
        val store = InMemoryNetworkHistoryStore(maxEntries = 10)

        store.record(record(store, "/first"))
        store.record(record(store, "/second"))

        assertEquals(listOf("/second", "/first"), store.records.value.map { it.path })
    }

    @Test
    fun `the oldest records are evicted past the limit`() {
        val store = InMemoryNetworkHistoryStore(maxEntries = 3)

        repeat(5) { index -> store.record(record(store, "/request-$index")) }

        assertEquals(3, store.records.value.size)
        assertEquals(
            listOf("/request-4", "/request-3", "/request-2"),
            store.records.value.map { it.path },
        )
    }

    @Test
    fun `ids are unique and increasing`() {
        val store = InMemoryNetworkHistoryStore(maxEntries = 10)

        val ids = (1..5).map { store.nextRecordId() }

        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `clear empties the store`() {
        val store = InMemoryNetworkHistoryStore(maxEntries = 10)
        store.record(record(store, "/first"))

        store.clear()

        assertTrue(store.records.value.isEmpty())
    }

    @Test
    fun `concurrent writers never exceed the limit or lose the newest record`() {
        val store = InMemoryNetworkHistoryStore(maxEntries = 50)
        val writers = 8
        val perWriter = 200
        val pool = Executors.newFixedThreadPool(writers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)

        repeat(writers) { writer ->
            pool.execute {
                start.await()
                repeat(perWriter) { index -> store.record(record(store, "/w$writer-$index")) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdown()

        val records = store.records.value
        assertEquals(50, records.size)
        assertEquals("no record may be duplicated", records.size, records.map { it.id }.toSet().size)
        // Ids are handed out in order, so the retained window must be the newest ids.
        val expectedHighest = (writers * perWriter).toLong()
        assertEquals(expectedHighest, records.maxOf { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive limit is rejected`() {
        InMemoryNetworkHistoryStore(maxEntries = 0)
    }
}
