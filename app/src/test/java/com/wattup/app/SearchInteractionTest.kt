package com.wattup.app

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class SearchInteractionTest {
    @Test fun newestRequestReplacesQueuedWorkAndDisconnectsTheActiveRequest() {
        val lane = LatestWork()
        val started = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val latest = CountDownLatch(1)
        val superseded = AtomicBoolean(false)
        val connection = object : HttpURLConnection(URL("https://example.test")) {
            override fun connect() {}
            override fun disconnect() = disconnected.countDown()
            override fun usingProxy() = false
        }
        try {
            lane.submit {
                LatestWork.track(connection)
                started.countDown()
                try { disconnected.await() } catch (_: InterruptedException) {}
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            lane.submit(60000) { superseded.set(true) }
            lane.submit { latest.countDown() }
            assertTrue(latest.await(5, TimeUnit.SECONDS))
            assertTrue(disconnected.await(5, TimeUnit.SECONDS))
            assertFalse(superseded.get())
        } finally {
            lane.close()
        }
    }

    @Test fun targetTimeAndFilterMatchWhatTheDriverSelected() {
        assertEquals("11월 24일 00:30", koreanTime("2026-11-23T15:30:00Z"))
        assertEquals("11월 24일 00:30", koreanTime("2026-11-24T00:30:00+09:00"))
        assertEquals("급속 50kW 이상 · DC콤보 · 이용 가능만",
            NearbyFilter(speed = Speed.FAST, dcCombo = true, availableOnly = true).title)
        assertEquals("DC콤보", NearbyFilter(dcCombo = true).title)
    }
}
