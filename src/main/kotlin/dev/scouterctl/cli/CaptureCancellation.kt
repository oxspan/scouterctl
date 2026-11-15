package dev.scouterctl.cli

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class CaptureCancellation : AutoCloseable {
    private val requested = AtomicBoolean()
    private val finished = CountDownLatch(1)
    private val hook = Thread({
        requested.set(true)
        finished.await(5, TimeUnit.SECONDS)
    }, "scouterctl-capture-checkpoint")

    init { Runtime.getRuntime().addShutdownHook(hook) }

    fun cancelled(): Boolean = requested.get() || Thread.currentThread().isInterrupted

    override fun close() {
        finished.countDown()
        try { Runtime.getRuntime().removeShutdownHook(hook) } catch (_: IllegalStateException) {
            // Shutdown already owns the hook; its bounded wait has been released.
        }
    }
}
