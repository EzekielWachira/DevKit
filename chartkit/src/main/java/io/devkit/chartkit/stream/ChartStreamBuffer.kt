package io.devkit.chartkit.stream

/**
 * The bounded buffer behind a streaming chart.
 *
 * ### Why a ring and not a list
 *
 * A count window implemented as `list + value` then `drop(1)` copies the whole
 * buffer on **every sample**. At 600 retained values and 100 samples a second
 * that is sixty thousand element copies a second, spent entirely on discarding
 * one value. A ring buffer overwrites one slot.
 *
 * ### Snapshots are immutable and are taken, not shared
 *
 * [snapshot] materialises a plain `List` in arrival order. The renderer reads
 * that list and nothing else, so a sample arriving mid-draw cannot mutate the
 * data a path is being built from — which is the failure a naïvely shared
 * mutable list produces, and it produces it as an intermittent crash rather
 * than as a wrong chart.
 *
 * Plain Kotlin, no Compose, no coroutines: the windowing and eviction rules are
 * the part worth testing directly.
 */
internal class ChartStreamBuffer<T>(capacity: Int) {

    private var storage = arrayOfNulls<Any?>(capacity.coerceAtLeast(1))
    private var head = 0
    private var count = 0

    /** How many values are held. */
    val size: Int get() = count

    /** The most values this buffer will hold before evicting. */
    val capacity: Int get() = storage.size

    val isEmpty: Boolean get() = count == 0

    /** Appends [value], evicting the oldest when the buffer is full. */
    fun add(value: T) {
        val index = (head + count) % storage.size
        storage[index] = value
        if (count < storage.size) {
            count++
        } else {
            // Full: the write overwrote the oldest slot, so the head advances.
            head = (head + 1) % storage.size
        }
    }

    /** Appends every value in order. */
    fun addAll(values: Iterable<T>) {
        values.forEach(::add)
    }

    /**
     * Resizes to [newCapacity], keeping the most recent values.
     *
     * Growing keeps everything; shrinking drops the oldest, which is what a
     * narrowed window means.
     */
    fun resize(newCapacity: Int) {
        val target = newCapacity.coerceAtLeast(1)
        if (target == storage.size) return
        val kept = snapshot().takeLast(target)
        storage = arrayOfNulls(target)
        head = 0
        count = 0
        kept.forEach(::add)
    }

    /**
     * Drops values from the front while [shouldEvict] holds.
     *
     * Front-to-back and stopping at the first value that stays, which is what
     * makes a duration window `O(evicted)` rather than `O(size)`: values arrive
     * in time order, so once one is inside the window every later one is too.
     */
    @Suppress("UNCHECKED_CAST")
    fun evictWhile(shouldEvict: (T) -> Boolean) {
        while (count > 0) {
            val value = storage[head] as T
            if (!shouldEvict(value)) return
            storage[head] = null
            head = (head + 1) % storage.size
            count--
        }
    }

    /** The newest value, or `null`. */
    @Suppress("UNCHECKED_CAST")
    fun last(): T? = if (count == 0) null else storage[(head + count - 1) % storage.size] as T

    /** An immutable copy in arrival order. */
    @Suppress("UNCHECKED_CAST")
    fun snapshot(): List<T> {
        if (count == 0) return emptyList()
        val result = ArrayList<T>(count)
        for (index in 0 until count) {
            result += storage[(head + index) % storage.size] as T
        }
        return result
    }

    fun clear() {
        java.util.Arrays.fill(storage, null)
        head = 0
        count = 0
    }
}
