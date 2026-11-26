package com.wattup.app

enum class Speed(val title: String) { ALL("전체"), FAST("급속"), SLOW("완속") }

data class NearbyFilter(val speed: Speed = Speed.ALL, val availableOnly: Boolean = false, val dcCombo: Boolean = false, val radiusM: Int = 3000) {
    val radiusText get() = if (radiusM % 1000 == 0) "${radiusM / 1000}km" else "${radiusM}m"
    val profile get() = when {
        dcCombo -> "DC_COMBO"
        speed == Speed.SLOW -> "AC_SLOW"
        else -> "ALL"
    }
    val title get() = listOfNotNull(
        when (speed) {
            Speed.FAST -> "급속 50kW 이상"
            Speed.SLOW -> "완속"
            Speed.ALL -> if (dcCombo) null else "전체"
        },
        "DC콤보".takeIf { dcCombo },
        "이용 가능만".takeIf { availableOnly }
    ).joinToString(" · ")
}

enum class Availability(val title: String, val color: Int) {
    FREE("여유", 0xff126b48.toInt()), BUSY("혼잡", 0xff996000.toInt()),
    UNAVAILABLE("이용 가능 0대", 0xff715d54.toInt()), FULL("만차", 0xffa63232.toInt()), UNKNOWN("정보 확인 필요", 0xff505a61.toInt())
}

data class StationRow(val id: String, val name: String, val point: SearchPoint?,
                      val meters: Int, val speedText: String, val available: Int,
                      val total: Int?, val status: Availability, val asOf: String = "", val expiresAt: Long? = null,
                      val charging: Int? = null, val other: Int? = null, val unknown: Int? = null,
                      val staleObservation: Boolean = status == Availability.UNKNOWN,
                      val needsSpeedLookup: Boolean = false, val countsReliable: Boolean = true) {
    val observedLabel by lazy { asOf.takeIf { it.isNotBlank() }?.let(::koreanTime) }
    val badge: String get() = if (status == Availability.UNKNOWN) status.title
        else if (status == Availability.UNAVAILABLE) status.title
        else "${status.title} · 가용 $available${total?.let { "/$it" }.orEmpty()}대"

    fun at(now: Long): StationRow = if (expiresAt != null && now >= expiresAt && !staleObservation)
        copy(status = Availability.UNKNOWN, staleObservation = true) else this
}


data class NearbyResult(val rows: List<StationRow>, val total: Int?) {
    fun summary(displayed: Int): String = when {
        total != null && total > displayed -> "전체 ${total}곳 중 ${displayed}곳 표시"
        displayed == 0 -> "조건에 맞는 충전소 없음 · 반경이나 필터를 변경해 주세요."
        else -> "충전소 ${displayed}곳 표시"
    }
}
