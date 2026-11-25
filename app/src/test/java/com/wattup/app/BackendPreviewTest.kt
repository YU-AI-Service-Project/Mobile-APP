package com.wattup.app

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class BackendPreviewTest {
    @Test fun readsSeededSpringServerThroughAndroidRepository() {
        val base = System.getenv("WATTUP_TEST_FIXTURE_URL")
        assumeTrue("격리된 Spring 테스트 DB가 필요합니다", !base.isNullOrBlank())
        val source = HttpStationSource(requireNotNull(base), demoForecast = true)
        val filter = NearbyFilter()
        val row = source.nearby(SearchPoint.DAEGU, filter).rows.single { it.id == "KECO:DEMOLOCAL" }
        assertEquals(1, row.available)
        val detail = source.detail(row, filter)
        assertEquals("Backend integration fixture", detail.address)
        assertEquals("무료 주차", detail.parkingFee)
        assertEquals("시연값 75%", detail.forecastText)
        assertEquals(instant(row.asOf)!! + 1800000L, instant(detail.targetAt!!))
        val top = source.recommendations(SearchPoint.DAEGU, filter).items.single()
        assertEquals(row.id, top.row.id)
        assertEquals(1, top.rank)
        assertEquals(detail.forecastText, top.detail.forecastText)
        val favorite = source.favoriteDetail(Bookmark(row.id, row.name, requireNotNull(row.point)))
        assertEquals(detail.address, favorite.address)
        assertEquals(detail.forecastText, favorite.forecastText)
        assertTrue(source.nearby(SearchPoint(37.5, 127.0), filter).rows.isEmpty())
        val fast = filter.copy(speed = Speed.FAST)
        assertNull(source.detail(row, fast).probability)
    }
}
