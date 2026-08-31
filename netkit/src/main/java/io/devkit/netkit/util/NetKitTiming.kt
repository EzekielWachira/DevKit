package io.devkit.netkit.util

/**
 * Wall-clock and elapsed-time source.
 *
 * Injected so tests can assert timestamps and durations without sleeping, and
 * so history timestamps stay independent of the interceptor implementation.
 */
internal interface NetKitClock {
    /** Wall-clock milliseconds, for history timestamps. */
    fun nowMillis(): Long

    /** Monotonic nanoseconds, for durations. Not comparable across processes. */
    fun elapsedNanos(): Long

    companion object {
        val System: NetKitClock = object : NetKitClock {
            override fun nowMillis(): Long = java.lang.System.currentTimeMillis()
            override fun elapsedNanos(): Long = java.lang.System.nanoTime()
        }
    }
}

/**
 * Blocks the calling thread for a simulated delay.
 *
 * OkHttp interceptors are synchronous by contract: `intercept` blocks the call's
 * own thread until it returns a response, exactly as a slow server would. NetKit
 * therefore sleeps that same thread rather than suspending — which keeps a
 * simulated delay indistinguishable from a real one for `Call.enqueue`,
 * coroutine adapters and RxJava schedulers alike.
 *
 * The interceptor is never invoked on the Android main thread by OkHttp itself:
 * `Call.execute()` blocks whichever thread the application chose, and
 * `Call.enqueue()` runs on the dispatcher's pool. An app that calls
 * `execute()` on the main thread already crashes with `NetworkOnMainThreadException`
 * before NetKit is reached.
 */
internal fun interface Sleeper {
    /** Sleeps [millis]; a zero or negative value returns immediately. */
    fun sleep(millis: Long)

    companion object {
        val Thread: Sleeper = Sleeper { millis ->
            if (millis > 0) {
                try {
                    java.lang.Thread.sleep(millis)
                } catch (interrupted: InterruptedException) {
                    // Preserve the interrupt so OkHttp's own cancellation still works:
                    // a cancelled call must not be swallowed by a simulated delay.
                    java.lang.Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("NetKit delay interrupted")
                        .initCause(interrupted) as java.io.InterruptedIOException
                }
            }
        }
    }
}
