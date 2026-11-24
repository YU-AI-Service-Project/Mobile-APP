package com.wattup.app

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class HttpStationSourceTest {
    private val point = SearchPoint.DAEGU
    private val filter = NearbyFilter(dcCombo = true)
    private val time = "2025-01-01T00:00:00Z"
    private val clock = 1735689600000L
    private val station = """{"stationId":"KECO:0001","name":"Test","latitude":35,"longitude":128,
        "straightDistanceM":50,"current":{"profile":"DC_COMBO","availableCount":1,"totalCount":2},
        "asOf":"$time","freshness":"FRESH"}"""
    private val nearby = """{"items":[$station]}"""
    private val prediction = """{"status":"OK","probability":0.0,"asOf":"$time","forecastAt":"2025-01-01T00:30:00Z"}"""
    private fun source(age: Long = 0, reply: (URL) -> Pair<Int,String>) =
        HttpStationSource("https://example.test/api", { clock + age }) { url ->
            val (status, body) = reply(url)
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = status
                override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
            }
        }

    @Test fun staleAtTenMinutesAndEmptyAreDistinctFromFailure() {
        assertEquals(Availability.UNKNOWN, source(600000) { 200 to nearby }.nearby(point, filter).rows.single().status)
        assertTrue(source { 200 to """{"items":[]}""" }.nearby(point, filter).rows.isEmpty())
        assertThrows(IOException::class.java) { source { 503 to "" }.nearby(point, filter) }
    }

    @Test fun exactProfileZeroAndUnavailablePredictionKeepCurrent() {
        for (status in listOf(200, 503)) {
            var current: StationDetail? = null
            val api = source { url -> when {
                url.path.endsWith("/prediction") -> {
                    assertEquals("예측 요청 전에 현재 상태가 전달되어야 함", 1, current?.current?.available)
                    assertNull(current?.probability)
                    status to """{"stationId":"KECO:0001","profile":"DC_COMBO","mode":"FIXED_30M","prediction":$prediction}"""
                }
                url.path.endsWith("/charging-stations") -> 200 to nearby
                else -> 200 to station
            } }
            val row = api.nearby(point, filter).rows.single()
            val detail = api.detail(row, filter) { current = it }
            assertEquals(time, current?.asOf)
            assertEquals(if (status == 200) 0 else null, detail.percent)
            assertEquals(1, detail.current?.available)
        }
    }

    @Test fun outputRestrictedRequestsKeepConnectorButNeverBorrowProfilePrediction() {
        val availabilityQueries = mutableListOf<String>()
        val fast = filter.copy(speed = Speed.FAST, availableOnly = true)
        val api = source { url ->
            if (url.path.endsWith("/charging-stations")) {
                availabilityQueries += url.query
                assertTrue(url.query.contains("minOutputKw=50") && url.query.contains("profile=DC_COMBO"))
                200 to nearby
            } else {
                assertFalse(url.path.endsWith("/prediction"))
                200 to station
            }
        }
        assertNull(api.detail(api.nearby(point, fast).rows.single(), fast).probability)
        assertTrue(availabilityQueries.first().contains("availableOnly=true"))
        assertTrue(availabilityQueries.last().contains("availableOnly=false"))
    }

    @Test fun placeAndRecommendationUseBackendResults() {
        val api = source { url -> when {
            url.path.endsWith("/locations/search") -> 200 to """{"items":[{"name":"Test","address":"Addr","latitude":35,"longitude":128}]}"""
            url.path.endsWith("/recommendations") -> 200 to """{"mode":"FIXED_30M","profile":"DC_COMBO","rankingBasis":"CURRENT_STATUS",
                "totalCandidates":12,"warnings":["관측 범위 제한"],"items":[{"rank":2,"stationId":"KECO:9999","name":"Separate candidate","straightDistanceM":231,"current":{"availableCount":1,"freshness":"FRESH","asOf":"$time"},"reason":"server reason","prediction":$prediction}]}"""
            else -> 200 to nearby
        } }
        assertEquals(SearchPoint(35.0,128.0), api.places("장소").single().point)
        val result = api.recommendations(point, filter)
        assertEquals("현재 상태 기준", result.method)
        assertEquals(2, result.items.single().rank)
        assertEquals("KECO:9999", result.items.single().row.id)
        assertEquals(12, result.totalCandidates)
        assertEquals(listOf("관측 범위 제한"), result.warnings)
        assertEquals("server reason", result.items.single().reason)
        assertEquals(0, result.items.single().detail.percent)
    }

    @Test fun smallProbabilitiesAndSnapshotExpiry() {
        val base = StationDetail(time, null, null, null, null, null, null, null, null)
        assertEquals("1% 미만", base.copy(probability = 0.004).forecastText)
        assertEquals("99% 초과", base.copy(probability = 0.996).forecastText)
        assertEquals("0%", base.copy(probability = 0.0).forecastText)
        val row = source { 200 to nearby }.nearby(point,filter).rows.single()
        assertEquals(Availability.UNKNOWN, row.at(clock + 600000).status)
        assertEquals(time, row.at(clock + 600000).asOf)
        assertThrows(UnsupportedFilter::class.java) { source { 422 to "" }.nearby(point, filter) }
    }

    @Test fun unavailableIsNotAlwaysFull() {
        fun status(charging: Int): Availability {
            val body = nearby.replace("\"availableCount\":1", "\"availableCount\":0,\"chargingCount\":$charging")
            return source { 200 to body }.nearby(point, filter).rows.single().status
        }
        assertEquals(Availability.UNAVAILABLE, status(0))
        assertEquals(Availability.FULL, status(2))
    }

    @Test fun detailsKeepOperatorRestrictionsAndIndividualConnectorPairs() {
        val body = station.dropLast(1) + ""","usageRestrictions":"입주민 전용","operator":{"name":"운영사","phone":"123"},
            "chargers":[{"chargerId":"01","connector":"DC_COMBO","typeCode":"04","status":"CHARGING","outputKw":80,"asOf":"$time"},
            {"chargerId":"02","connector":"AC","typeCode":"02","status":"AVAILABLE","outputKw":7}]}"""
        val api = source { url -> when {
            url.path.endsWith("/prediction") -> 503 to ""
            url.path.endsWith("/charging-stations") -> 200 to nearby
            else -> 200 to body
        } }
        val detail = api.detail(api.nearby(point, filter).rows.single(), filter)
        assertEquals("입주민 전용", detail.restrictions)
        assertEquals("운영사", detail.operator)
        assertEquals("123", detail.phone)
        assertTrue(detail.chargers.single().label(clock, false).startsWith("01 · DC_COMBO · 80 kW · 충전 중"))
    }

    @Test fun missingForecastKeepsFreshRecommendationAndExpiresIt() {
        fun result(age: Long, freshness: String): Recommendations = source(age) { url ->
            assertTrue(url.query.contains("availableOnly=true"))
            assertTrue(url.query.contains("radiusM=5000"))
            200 to """{"mode":"FIXED_30M","profile":"DC_COMBO","generatedAt":"$time","appliedFilters":{"availableOnly":true},
                "items":[{"rank":1,"stationId":"KECO:0001","name":"A","straightDistanceM":30,
                "current":{"availableCount":1,"freshness":"$freshness","asOf":"$time"},"prediction":{"status":"INPUT_INCOMPLETE","probability":null}}]}"""
        }.recommendations(point, filter.copy(radiusM = 5000, availableOnly = true))
        val fresh = result(0, "FRESH")
        assertEquals(1, fresh.items.single().row.available)
        assertEquals(time, fresh.items.single().row.asOf)
        assertNull(fresh.items.single().detail.probability)
        assertTrue(fresh.warnings.isEmpty())
        assertTrue(fresh.at(clock + 600000, true).items.isEmpty())
        assertTrue(result(600000, "FRESH").items.isEmpty())
        assertTrue(result(0, "STALE").items.isEmpty())
    }

    @Test fun nearbyKeepsRadiusTotalCapabilitiesAndCounts() {
        val record = org.json.JSONObject(station)
        record.put("supportedSpeeds", org.json.JSONArray().put("FAST").put("SLOW"))
        record.getJSONObject("current").put("chargingCount", 1).put("otherCount", 0).put("unknownCount", 0)
        val response = org.json.JSONObject().put("total", 25).put("items", org.json.JSONArray().put(record)).toString()
        val page = source { url ->
            assertTrue(url.query.contains("radiusM=500"))
            200 to response
        }.nearby(point, filter.copy(radiusM = 500))
        assertEquals("전체 25곳 중 1곳 표시", page.summary(page.rows.size))
        assertEquals("급속 · 완속", page.rows.single().speedText)
        assertEquals(1, page.rows.single().charging)
        assertEquals(0, page.rows.single().unknown)
    }

    @Test fun observedChargerExpiresBeforeStationAndKeepsItsIdentity() {
        val body = station.dropLast(1) + ""","chargers":[{"chargerId":"01","typeCode":"04","connector":"DC_COMBO","outputKw":50,
            "status":"UNAVAILABLE","asOf":"2024-12-31T23:55:00Z"}]}"""
        val api = source { url -> when {
            url.path.endsWith("/prediction") -> 503 to ""
            url.path.endsWith("/charging-stations") -> 200 to nearby
            else -> 200 to body
        } }
        val detail = api.detail(api.nearby(point, filter).rows.single(), filter)
        val charger = detail.chargers.single()
        assertTrue(charger.label(clock, false).contains("사용 불가"))
        assertTrue(charger.label(clock + 300000, false).startsWith("01 · DC_COMBO · 50 kW · 정보 확인 필요"))
        assertEquals(Availability.BUSY, detail.current!!.at(clock + 300000).status)
    }

    @Test fun missingSpeedSummaryUsesUnfilteredStationChargers() {
        val api = source { url ->
            assertTrue(url.path.endsWith("/KECO%3A0001/chargers"))
            assertFalse(url.query.orEmpty().contains("profile"))
            200 to """{"stationId":"KECO:0001","items":[{"outputKw":50,"typeCode":"04"},{"outputKw":7,"typeCode":"02"}]}"""
        }
        assertEquals("급속 · 완속", api.supportedSpeeds("KECO:0001"))
        val unknown = source { 200 to """{"stationId":"KECO:0001","items":[{"outputKw":null,"typeCode":"99"}]}""" }
        assertNull(unknown.supportedSpeeds("KECO:0001"))
    }

    @Test fun predictionTimeDoesNotExtendCurrentObservationAndViceVersa() {
        val response = """{"mode":"FIXED_30M","profile":"DC_COMBO","generatedAt":"2025-01-01T00:05:00Z",
            "items":[{"rank":1,"stationId":"KECO:0001","name":"Test","straightDistanceM":100,
            "current":{"availableCount":1,"freshness":"FRESH","asOf":"$time"},"prediction":$prediction}]}"""
        val candidates = source(300000) { 200 to response }.recommendations(point, filter)
        assertEquals(0, candidates.items.single().detail.percent)
        assertTrue(candidates.at(clock + 600000, true).items.isEmpty())
        val missing = org.json.JSONObject(response)
        missing.getJSONArray("items").getJSONObject(0).getJSONObject("current").remove("asOf")
        val legacy = source(300000) { 200 to missing.toString() }.recommendations(point, filter)
        assertEquals(Availability.UNKNOWN, legacy.items.single().row.status)
        assertNull(legacy.items.single().row.expiresAt)
        assertEquals(0, legacy.items.single().detail.percent)

    }

    @Test fun invalidCountsAffectOnlyOneStation() {
        for (bad in listOf(-1, 3, 0.5, "invalid")) {
            val record = org.json.JSONObject(station)
            record.getJSONObject("current").put("availableCount", bad)
            val page = source { 200 to """{"items":[$record,$station]}""" }.nearby(point, filter)
            assertFalse(page.rows.first().countsReliable)
            assertEquals(Availability.UNKNOWN, page.rows.first().status)
            assertEquals(Availability.BUSY, page.rows.last().status)
        }
        val record = org.json.JSONObject(station)
        record.getJSONObject("current").put("chargingCount", 0).put("otherCount", 0).put("unknownCount", 0)
        assertFalse(source { 200 to """{"items":[$record]}""" }.nearby(point, filter).rows.single().countsReliable)
    }

    @Test fun failedFastCountsPreservePublishedStation() {
        val row = source { 200 to nearby }.nearby(point, filter).rows.single()
        var published = false
        val api = source { url ->
            if (url.path.endsWith("/charging-stations")) {
                assertTrue(published)
                503 to ""
            } else 200 to org.json.JSONObject(station).put("address", "대구").toString()
        }
        val detail = api.detail(row, filter.copy(speed = Speed.FAST)) {
            assertEquals("대구", it.address)
            published = true
        }
        assertEquals("대구", detail.address)
        assertFalse(detail.current!!.countsReliable)
        assertEquals(Availability.UNKNOWN, detail.current!!.status)
    }

    @Test fun legacyRecommendationFilterExplainsLimitedRanking() {
        val response = """{"mode":"FIXED_30M","profile":"DC_COMBO","items":[{"rank":1,"stationId":"KECO:0002","name":"Test",
            "straightDistanceM":100,"current":{"availableCount":0,"freshness":"FRESH","asOf":"$time"}}]}"""
        val rec = source { 200 to response }.recommendations(point, filter.copy(availableOnly = true))
        assertTrue(rec.items.isEmpty())
        assertTrue(rec.warnings.any { it.contains("필터 적용 여부") })
    }

    @Test fun sampleRadiusFilterDetailsAndFavoritesUseSameStation() {
        val api = NearbySource { path -> java.io.File("src/debug/assets", path).inputStream() }
        val center = SearchPoint(point.latitude + 0.01, point.longitude)
        val default = NearbyFilter()
        assertTrue(api.nearby(center, default.copy(radiusM = 500)).rows.isEmpty())
        assertTrue(api.recommendations(center, default.copy(radiusM = 500)).items.isEmpty())
        val page = api.nearby(center, default)
        assertEquals(2, page.total)
        val row = page.rows.first { it.id == "KECO:0001" }
        val detail = api.detail(row, default)
        assertEquals(3, detail.current?.available)
        assertEquals(1, detail.current?.charging)
        assertEquals(76, detail.percent)
        assertEquals(4, detail.chargers.size)
        assertEquals(detail, api.favoriteDetail(Bookmark(row.id, row.name, row.point!!)).let {
            it.copy(current = it.current?.copy(meters = row.meters))
        })
        val dc = default.copy(dcCombo = true)
        val full = api.nearby(center, dc).rows.single { it.id == "KECO:0002" }
        assertEquals(Availability.FULL, full.status)
        assertEquals(0, api.detail(full, dc).percent)
        assertEquals(1, api.recommendations(center, dc.copy(availableOnly = true)).items.size)
    }
}
