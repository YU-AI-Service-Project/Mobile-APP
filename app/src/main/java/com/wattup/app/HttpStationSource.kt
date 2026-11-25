package com.wattup.app

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal class UnsupportedFilter : IOException("지원하지 않는 충전 조건")

/** Public Spring Boot API. The base URL includes /api. */
internal class HttpStationSource(private val base: String, private val now: () -> Long = System::currentTimeMillis,
    private val demoForecast: Boolean = false,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : StationSource {
    override val description = if (demoForecast) "충전소 서버 · 예측 시연" else "충전소 서버"
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun <T> get(path: String, params: Map<String, String>, decode: (JSONObject) -> T): T {
        val localDemo = demoForecast && URL(base).let { it.protocol == "http" && it.host in setOf("10.0.2.2", "127.0.0.1", "localhost") }
        if (!localDemo && !base.startsWith("https://")) throw IOException("HTTPS 서버 주소 설정 필요")
        val query = params.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
        val connection = connect(URL(base.trimEnd('/') + path + "?" + query))
        try {
            LatestWork.track(connection)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode == 422) throw UnsupportedFilter()
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { decode(JSONObject(it.readText())) }
        } catch (e: JSONException) {
            throw IOException("응답 형식 오류", e)
        } finally {
            LatestWork.release(connection)
            connection.disconnect()
        }
    }

    private fun JSONObject.optional(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONObject.items(key: String = "items"): List<JSONObject> {
        val array = getJSONArray(key)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun query(point: SearchPoint, filter: NearbyFilter) = mutableMapOf(
        "latitude" to point.latitude.toString(), "longitude" to point.longitude.toString(),
        "radiusM" to filter.radiusM.toString(), "profile" to filter.profile, "availableOnly" to filter.availableOnly.toString()
    ).apply { if (filter.speed == Speed.FAST) put("minOutputKw", "50") }

    private fun fresh(time: String, flag: String) =
        flag == "FRESH" && instant(time)?.let { now() - it in 0 until 600000L } == true

    private fun JSONObject.count(key: String) = optInt(key, -1).takeIf { it >= 0 }

    private fun speedText(station: JSONObject): String? {
        val speeds = station.optJSONArray("supportedSpeeds")
        val labels = (0 until (speeds?.length() ?: 0)).mapNotNull { index ->
            when (speeds?.optString(index)) {
                "FAST" -> "급속"
                "SLOW" -> "완속"
                else -> null
            }
        }.distinct()
        return if (labels.isEmpty()) station.optional("supportedSpeedText") else labels.joinToString(" · ")
    }

    private fun chargerSpeeds(chargers: List<JSONObject>): String? {
        val labels = mutableListOf<String>()
        if (chargers.any { it.optDouble("outputKw") >= 50 }) labels += "급속"
        if (chargers.any { it.optString("typeCode") == "02" }) labels += "완속"
        return labels.joinToString(" · ").ifBlank { null }
    }

    override fun supportedSpeeds(stationId: String): String? =
        get("/charging-stations/" + encode(stationId) + "/chargers", emptyMap()) { response ->
            if (response.optString("stationId") == stationId) chargerSpeeds(response.items()) else null
        }

    private fun validCounts(current: JSONObject, requireTotal: Boolean): Boolean {
        val keys = listOf("availableCount", "chargingCount", "otherCount", "unknownCount")
        fun valid(key: String): Boolean {
            val value = current.opt(key) as? Number ?: return false
            return value.toDouble() in 0.0..Int.MAX_VALUE.toDouble() && value.toDouble() == value.toInt().toDouble()
        }
        if (!valid("availableCount") || (requireTotal && !valid("totalCount"))) return false
        val present = keys.filterNot(current::isNull)
        if (present.any { !valid(it) }) return false
        if (current.isNull("totalCount")) return true
        if (!valid("totalCount")) return false
        val sum = present.sumOf { current.getLong(it) }
        val total = current.getLong("totalCount")
        return sum <= total && (present.size < keys.size || sum == total)
    }

    private fun availability(current: JSONObject, isFresh: Boolean): Availability {
        val available = current.optInt("availableCount", 0)
        return when {
            !isFresh -> Availability.UNKNOWN
            available == 0 && current.optInt("unknownCount") > 0 -> Availability.UNKNOWN
            available == 0 && current.optInt("totalCount") > 0 &&
                current.optInt("chargingCount", -1) == current.optInt("totalCount") -> Availability.FULL
            available == 0 -> Availability.UNAVAILABLE
            available == 1 -> Availability.BUSY
            else -> Availability.FREE
        }
    }

    override fun places(query: String): List<Place> {
        if (query.isBlank()) return emptyList()
        return get("/locations/search", mapOf("query" to query, "limit" to "5")) { response ->
            response.items().map { Place(it.getString("name"), it.optString("address"),
                SearchPoint(it.getDouble("latitude"), it.getDouble("longitude"))) }
        }
    }

    override fun nearby(point: SearchPoint, filter: NearbyFilter): NearbyResult =
        get("/charging-stations", query(point, filter)) { response ->
            val rows = response.items().map { s ->
                val c = s.getJSONObject("current")
                if (c.getString("profile") != filter.profile) throw IOException("충전 조건 불일치")
                val available = c.optInt("availableCount", 0)
                val time = s.optional("asOf").orEmpty()
                val isFresh = fresh(time, s.getString("freshness"))
                val reliable = validCounts(c, requireTotal = true)
                val state = availability(c, isFresh && reliable)
                val supported = speedText(s)
                StationRow(s.getString("stationId"), s.getString("name"),
                    SearchPoint(s.getDouble("latitude"), s.getDouble("longitude")),
                    s.getInt("straightDistanceM"), supported ?: when {
                        c.optInt("totalCount", 0) == 0 -> "지원 종류 정보 없음"
                        filter.speed == Speed.FAST -> "급속 조건 일치"
                        filter.speed == Speed.SLOW -> "완속 조건 일치"
                        filter.dcCombo -> "DC콤보 조건 일치"
                        else -> "지원 종류 정보 없음"
                    }, available, c.optInt("totalCount", 0), state, time, instant(time)?.plus(600000L),
                    c.count("chargingCount"), c.count("otherCount"), c.count("unknownCount"), staleObservation = !isFresh,
                    countsReliable = reliable, needsSpeedLookup = supported == null)
            }.filter { !filter.availableOnly || (it.available > 0 && it.status != Availability.UNKNOWN) }
            NearbyResult(rows, response.count("total"))
        }

    private fun prediction(p: JSONObject?, row: StationRow, filter: NearbyFilter, observationTimeMissing: Boolean = false): Double? {
        if ((row.status == Availability.UNKNOWN && !observationTimeMissing) || filter.speed == Speed.FAST ||
            p?.optString("status") != "OK" || (row.asOf.isNotEmpty() && instant(p.optString("asOf")) != instant(row.asOf)) ||
            !fresh(p.optString("asOf"), "FRESH") || p.isNull("forecastAt")) return null
        val start = instant(p.optString("asOf")) ?: return null
        if (instant(p.optString("forecastAt")) != start + 30 * 60000L) return null
        return p.optDouble("probability").takeIf { it in 0.0..1.0 }
    }

    override fun detail(row: StationRow, filter: NearbyFilter, onCurrent: (StationDetail) -> Unit): StationDetail {
        val path = "/charging-stations/" + encode(row.id)
        val params = mapOf("profile" to filter.profile)
        val station = get(path, params) { it }
        if (station.optString("stationId") != row.id) throw IOException("충전소 불일치")
        val location = station.optJSONObject("location")
        val located = if (location != null && !location.isNull("latitude") && !location.isNull("longitude"))
            row.copy(point = SearchPoint(location.getDouble("latitude"), location.getDouble("longitude"))) else row
        val current = if (filter.speed == Speed.FAST) {
            located.copy(status = Availability.UNKNOWN, countsReliable = false,
                asOf = "", expiresAt = null, staleObservation = false)
        } else getCurrentRow(station, located, filter)
        val chargers = station.optJSONArray("chargers")
        val units = (0 until (chargers?.length() ?: 0)).mapNotNull { chargers?.optJSONObject(it) }
        val selected = units.filter { charger ->
                val type = charger.optString("typeCode")
                (!filter.dcCombo || type in setOf("04","05","06","08","10")) &&
                    (filter.speed != Speed.SLOW || type == "02") &&
                    (filter.speed != Speed.FAST || charger.optDouble("outputKw") >= 50)
            }
        val detail = StationDetail(current.asOf, null, null,
            selected.mapNotNull { it.optional("connector") }.distinct().joinToString(" · ").ifEmpty { null },
            selected.mapNotNull { it.optional("outputKw") }.distinct().joinToString(" / ").takeIf { it.isNotEmpty() }?.plus(" kW"),
            station.optional("address"), station.optional("feeInfo"), station.optJSONObject("parkingInfo")?.let { if (it.isNull("free")) null else if (it.getBoolean("free")) "무료 주차" else "무료 주차 아님" } ?: station.optional("parkingInfo"), station.optional("operatingHours"), current,
            station.optional("usageRestrictions"), station.optJSONObject("operator")?.optional("name"),
            station.optJSONObject("operator")?.optional("phone"), selected.map {
                val observed = it.optional("asOf")
                val status = if (observed == null || !fresh(observed, it.optString("freshness", "FRESH"))) "정보 확인 필요" else when (it.optString("status")) {
                    "AVAILABLE" -> "이용 가능"
                    "CHARGING" -> "충전 중"
                    "UNAVAILABLE" -> "사용 불가"
                    "OTHER" -> "기타 상태"
                    else -> "정보 확인 필요"
                }
                val power = it.optional("outputKw")?.plus(" kW") ?: "출력 정보 없음"
                ChargerObservation("${it.optional("chargerId") ?: "ID 정보 없음"} · ${it.optional("connector") ?: "커넥터 정보 없음"} · $power",
                    status, observed, observed?.let(::instant)?.plus(600000L))
            }, speedText(station) ?: chargerSpeeds(units))
        if (filter.speed == Speed.FAST) {
            onCurrent(detail)
            val point = located.point ?: return detail
            val filtered = try {
                nearby(point, filter.copy(availableOnly = false)).rows.find { it.id == row.id }
            } catch (_: IOException) { null } ?: return detail
            return detail.copy(current = filtered, asOf = filtered.asOf)
        }
        if (current.status == Availability.UNKNOWN) return detail
        onCurrent(detail)
        if (demoForecast) return previewForecast(detail, current, filter)
        val p = try {
            get("$path/prediction", params + ("mode" to "FIXED_30M")) {
                if (it.optString("stationId") == row.id && it.optString("profile") == filter.profile &&
                    it.optString("mode") == "FIXED_30M") it.optJSONObject("prediction") else null
            }
        } catch (_: IOException) { null }
        val probability = prediction(p, current, filter)
        return detail.copy(probability = probability, targetAt = p?.optional("forecastAt")?.takeIf { probability != null })
    }

    private fun previewForecast(detail: StationDetail, row: StationRow, filter: NearbyFilter): StationDetail {
        val observed = instant(row.asOf) ?: return detail
        if (row.at(now()).status == Availability.UNKNOWN || filter.speed == Speed.FAST) return detail
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return detail.copy(probability = 0.75, demoForecast = true,
            targetAt = format.format(java.util.Date(observed + 1800000L)), predictionExpiresAt = row.expiresAt)
    }

    private fun getCurrentRow(station: JSONObject, row: StationRow, filter: NearbyFilter): StationRow {
        val counts = station.optJSONObject("current") ?: throw IOException("현재 상태 누락")
        if (counts.optString("profile") != filter.profile) throw IOException("충전 조건 불일치")
        return try {
            val time = station.optional("asOf").orEmpty()
            val isFresh = fresh(time, station.getString("freshness"))
            val reliable = validCounts(counts, requireTotal = true)
            row.copy(asOf = time, expiresAt = instant(time)?.plus(600000L), available = counts.optInt("availableCount", 0), total = counts.count("totalCount"),
                status = availability(counts, isFresh && reliable), countsReliable = reliable, staleObservation = !isFresh,
                charging = counts.count("chargingCount"), other = counts.count("otherCount"), unknown = counts.count("unknownCount"))
        } catch (e: JSONException) { throw IOException("현재 상태 응답 오류", e) }
    }

    override fun recommendations(point: SearchPoint, filter: NearbyFilter): Recommendations {
        val mode = if (demoForecast) "ARRIVAL_ETA" else "FIXED_30M"
        return get("/recommendations", query(point, filter) + mapOf("mode" to mode, "limit" to "3")) { response ->
            if (response.optString("profile") != filter.profile || response.optString("mode") != mode)
                throw IOException("추천 조건 불일치")
            val items = response.items().sortedBy { it.getInt("rank") }.mapNotNull { item ->
                val p = item.optJSONObject("prediction")
                val c = item.getJSONObject("current")
                val time = c.optional("asOf") ?: item.optional("asOf") ?: ""
                val isFresh = fresh(time, c.optString("freshness"))
                val expires = instant(time)?.plus(600000L)
                val reliable = validCounts(c, requireTotal = false)
                val state = availability(c, isFresh && reliable)
                val available = c.optInt("availableCount", 0)
                if (filter.availableOnly && (available == 0 || state == Availability.UNKNOWN)) return@mapNotNull null
                val row = StationRow(item.getString("stationId"), item.getString("name"), null,
                    item.getInt("straightDistanceM"), "지원 종류 정보 없음", available,
                    c.count("totalCount"), state, time, expires, c.count("chargingCount"), c.count("otherCount"), c.count("unknownCount"), staleObservation = !isFresh, countsReliable = reliable)
                val probability = if (demoForecast) null else prediction(p, row, filter, observationTimeMissing =
                    time.isEmpty() && reliable && availability(c, c.optString("freshness") == "FRESH") != Availability.UNKNOWN)
                val info = StationDetail(row.asOf, p?.optional("forecastAt")?.takeIf { probability != null },
                    probability, null, item.optional("outputKw")?.plus(" kW"), null, null, null, null,
                    predictionExpiresAt = p?.optional("asOf")?.let(::instant)?.plus(600000L))
                Recommendation(row, if (demoForecast) previewForecast(info, row, filter) else info, item.optString("reason"), item.getInt("rank"))
            }.take(3)
            val basis = when (response.optString("rankingBasis")) {
                "DISTANCE_CURRENT_STATUS_OUTPUT" -> "거리·현재 상태·출력 기준"
                "CURRENT_AVAILABILITY", "CURRENT_STATUS" -> "현재 상태 기준"
                "DISTANCE_CURRENT_STATUS_OUTPUT_PREDICTION" -> "거리·현재 상태·출력·예측 기준"
                else -> "서버 추천"
            }
            val warnings = response.optJSONArray("warnings")
            val messages = (0 until (warnings?.length() ?: 0)).map { warnings!!.getString(it) }.toMutableList()
            if (filter.availableOnly && response.optJSONObject("appliedFilters")?.optBoolean("availableOnly") != true) {
                messages += "서버 추천 범위에서 이용 가능한 곳만 표시합니다. 전체 후보의 필터 적용 여부는 미확인입니다."
            }
            Recommendations(basis, items, response.optInt("totalCandidates", -1).takeIf { it >= 0 },
                messages)
        }
    }
}
