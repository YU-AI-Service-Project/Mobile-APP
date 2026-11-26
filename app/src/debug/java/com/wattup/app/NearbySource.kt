package com.wattup.app

import android.content.Context
import com.google.gson.JsonParser
import com.wattup.app.data.MockStationRepository

/** Simulates the backend's output-based filter with separate, pre-counted fixture groups. */
internal class NearbySource(private val open: (String) -> java.io.InputStream) : StationSource {
    constructor(context: Context) : this(context.assets::open)

    private val repository = MockStationRepository(open, MockStationRepository.Scenario.NORMAL)
    private val groups by lazy {
        open("mock/speed_groups.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    }
    override val description = "샘플 데이터"
    private val samples = listOf(
        Place("동성로 검색 지점", "대구 중구 동성로 일대", SearchPoint.DAEGU),
        Place("중앙로 검색 지점", "대구 중구 중앙대로 일대", SearchPoint(35.873, 128.597))
    )

    override fun places(query: String) = matchingPlaces(query, emptyList(), samples)

    override fun recommendations(point: SearchPoint, filter: NearbyFilter): Recommendations {
        val candidates = nearby(point, filter).rows.associateBy { it.id }
        val response = repository.recommendations()
        val items = response.getAsJsonArray("items").mapNotNull { element ->
            val item = element.asJsonObject
            val row = candidates[item["stationId"].asString] ?: return@mapNotNull null
            Recommendation(row, detail(row, filter), "선택한 조건의 가용 충전기 ${row.available}대", item["rank"].asInt)
        }.take(3)
        // The fixture supplies a preset order; the device never computes a recommendation score.
        val fallback = items.any { it.detail.percent == null }
        val method = if (fallback) "현재 상태 기준" else "가중치 기준"
        val cards = items.map {
            val reason = when {
                it.row.status == Availability.UNKNOWN -> "갱신 지연으로 현재 상태 확인 필요"
                fallback -> it.reason
                else -> "${it.reason} · 30분 후 가용 확률 ${it.detail.forecastText}"
            }
            it.copy(reason = reason)
        }
        return Recommendations(method, cards, candidates.size)
    }

    private val detailProfiles by lazy {
        open("mock/detail_profiles.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    }

    override fun favoriteDetail(bookmark: Bookmark, onCurrent: (StationDetail) -> Unit): StationDetail {
        val filter = NearbyFilter()
        val row = nearby(bookmark.point, filter).rows.firstOrNull { it.id == bookmark.id }
            ?: throw java.io.IOException("충전소 정보 없음")
        return detail(row, filter, onCurrent)
    }

    override fun detail(row: StationRow, filter: NearbyFilter, onCurrent: (StationDetail) -> Unit): StationDetail {
        val station = repository.detail(row.id)
        val profiles = detailProfiles.getAsJsonObject(row.id)
        val selected = profiles.getAsJsonObject(if (filter.dcCombo) "FAST" else filter.speed.name)
        val delayed = row.status == Availability.UNKNOWN
        val time = profiles.getAsJsonObject(if (delayed) "delayed" else "fresh")
        val probability = if (filter.dcCombo && filter.speed == Speed.ALL) station.getAsJsonObject("prediction")["probability"].takeUnless { it.isJsonNull || delayed }?.asDouble else selected["probability"].takeUnless {
            it.isJsonNull || delayed || selected["profile"].isJsonNull
        }?.asDouble
        fun optional(key: String) = station[key]?.takeUnless { it.isJsonNull }?.asString
        val chargers = mutableListOf<ChargerObservation>()
        val observed = time["asOf"].asString
        if (filter.speed != Speed.SLOW) {
            for (item in station.getAsJsonArray("chargers")) {
                val unit = item.asJsonObject
                val status = when (unit["status"].asString) {
                    "AVAILABLE" -> "이용 가능"
                    "CHARGING" -> "충전 중"
                    else -> "정보 확인 필요"
                }
                chargers += ChargerObservation("${unit["chgerId"].asString} · DC콤보 · ${unit["outputKw"].asInt} kW", status, observed)
            }
        }
        if (filter.speed != Speed.FAST && !filter.dcCombo) {
            val slow = profiles.getAsJsonObject("slowCharger")
            chargers += ChargerObservation("${slow["id"].asString} · AC 완속 · 7 kW", slow["state"].asString, observed)
        }
        val detail = StationDetail(time["asOf"].asString, null, null,
            selected["connector"].asString, selected["output"].asString,
            optional("address"), optional("chargingRate"), optional("parkingFee"), optional("hours"), current = row.copy(asOf = observed), chargers = chargers, speedText = row.speedText)
        onCurrent(detail)
        return detail.copy(targetAt = time["targetAt"].takeIf { probability != null }?.asString, probability = probability)
    }

    override fun nearby(point: SearchPoint, filter: NearbyFilter): NearbyResult {
        val stations = repository.nearby(point.latitude, point.longitude, filter.radiusM).getAsJsonArray("stations")
        val rows = stations.mapNotNull { element ->
            val station = element.asJsonObject
            val id = station["stationId"].asString
            val selected = groups.getAsJsonArray(id).map { it.asJsonObject }.filter {
                (filter.speed == Speed.ALL || it["speed"].asString == filter.speed.name) &&
                    (!filter.dcCombo || it["speed"].asString == "FAST") // All FAST chargers in this fixture are DC combo.
            }
            if (selected.isEmpty()) return@mapNotNull null
            val knownAvailable = selected.filterNot { it["stale"].asBoolean }.sumOf { it["available"].asInt }
            val total = selected.sumOf { it["total"].asInt }
            val status = when {
                selected.any { it["stale"].asBoolean } -> Availability.UNKNOWN
                knownAvailable == 0 -> Availability.FULL
                knownAvailable == 1 -> Availability.BUSY
                else -> Availability.FREE
            }
            if (filter.availableOnly && (knownAvailable == 0 || status == Availability.UNKNOWN)) return@mapNotNull null
            StationRow(id, station["name"].asString, SearchPoint(station["latitude"].asDouble,
                station["longitude"].asDouble), station["distanceM"].asInt,
                groups.getAsJsonArray(id).joinToString("·") { Speed.valueOf(it.asJsonObject["speed"].asString).title },
                knownAvailable, total, status,
                asOf = detailProfiles.getAsJsonObject(id).getAsJsonObject(
                    if (status == Availability.UNKNOWN) "delayed" else "fresh")["asOf"].asString,
                charging = selected.sumOf { it["charging"].asInt }, other = selected.sumOf { it["other"].asInt },
                unknown = selected.sumOf { it["unknown"].asInt })
        }.sortedBy { it.meters }
        return NearbyResult(rows, rows.size)
    }
}
