package com.wattup.app

data class Recommendation(val row: StationRow, val detail: StationDetail, val reason: String, val rank: Int)
data class Recommendations(val method: String, val items: List<Recommendation>, val totalCandidates: Int? = null, val warnings: List<String> = emptyList())

internal fun Recommendations.at(now: Long, availableOnly: Boolean): Recommendations {
    var changed = false
    val visible = items.mapNotNull { item ->
        val row = item.row.at(now)
        val expiredPrediction = item.detail.probability != null && item.detail.predictionExpiresAt?.let { now >= it } == true
        if (row === item.row && !expiredPrediction) return@mapNotNull item
        changed = true
        if (availableOnly && row.status == Availability.UNKNOWN) return@mapNotNull null
        item.copy(row = row, detail = item.detail.copy(probability = null, targetAt = null),
            reason = "추천 당시 관측이 만료되었습니다. 새로고침해 주세요.")
    }
    return if (changed) copy(items = visible) else this
}
