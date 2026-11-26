package com.wattup.app

import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A screen's replaceable work. submit, cancel and close are called by the UI thread. */
internal class LatestWork {
    private val executor = ScheduledThreadPoolExecutor(2).apply { removeOnCancelPolicy = true }
    private var future: Future<*>? = null
    private var connection = AtomicReference<HttpURLConnection?>()

    fun submit(delayMillis: Long = 0, work: () -> Unit) {
        cancel()
        val active = AtomicReference<HttpURLConnection?>()
        connection = active
        future = executor.schedule({
            currentConnection.set(active)
            try {
                work()
            } finally {
                currentConnection.remove()
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    fun cancel() {
        future?.cancel(true)
        future = null
        connection.getAndSet(null)?.let { old -> disconnects.execute { old.disconnect() } }
    }

    fun close() {
        cancel()
        executor.shutdownNow()
    }

    companion object {
        private val currentConnection = ThreadLocal<AtomicReference<HttpURLConnection?>>()
        private val disconnects = Executors.newSingleThreadExecutor()

        fun track(connection: HttpURLConnection) {
            currentConnection.get()?.set(connection)
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("조회 취소")
        }

        fun release(connection: HttpURLConnection) {
            currentConnection.get()?.compareAndSet(connection, null)
        }
    }
}
