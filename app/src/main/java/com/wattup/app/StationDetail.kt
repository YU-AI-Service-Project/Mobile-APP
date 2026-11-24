package com.wattup.app

import kotlin.math.roundToInt

data class StationDetail(val asOf: String, val targetAt: String?, val probability: Double?,
                         val connector: String?, val output: String?, val address: String?,
                         val chargingRate: String?, val parkingFee: String?, val hours: String?,
                         val current: StationRow? = null, val restrictions: String? = null,
                         val operator: String? = null, val phone: String? = null,
                         val chargers: List<ChargerObservation> = emptyList(), val speedText: String? = null,
                         val predictionExpiresAt: Long? = null) {
    val percent = probability?.takeIf { it in 0.0..1.0 }?.let { (it * 100).roundToInt() }
    val targetLabel: String? by lazy { targetAt?.let(::koreanTime) }
    val forecastLabel get() = targetLabel?.let { "30분 후 가용 확률\n$forecastText · $it" } ?: "30분 후 · $forecastText"
    val forecastText get() = when {
        percent == null -> "예측 정보 부족"
        probability!! > 0 && percent == 0 -> "1% 미만"
        probability < 1 && percent == 100 -> "99% 초과"
        else -> "$percent%"
    }
}

data class ChargerObservation(val specification: String, val state: String, val observed: String?,
                              val expiresAt: Long? = null) {
    private val observedLabel by lazy { observed?.let(::koreanTime) }
    fun label(now: Long, stationStale: Boolean): String {
        val status = if (stationStale || (expiresAt != null && now >= expiresAt)) "정보 확인 필요" else state
        return "$specification · $status" + (observedLabel?.let { " (관측 $it)" } ?: "")
    }
}
