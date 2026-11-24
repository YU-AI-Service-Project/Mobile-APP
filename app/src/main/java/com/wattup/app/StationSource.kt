package com.wattup.app

internal interface StationSource {
    val description: String
    fun places(query: String): List<Place>
    fun supportedSpeeds(stationId: String): String? = null
    fun nearby(point: SearchPoint, filter: NearbyFilter): NearbyResult
    fun detail(row: StationRow, filter: NearbyFilter, onCurrent: (StationDetail) -> Unit = {}): StationDetail
    fun favoriteDetail(bookmark: Bookmark, onCurrent: (StationDetail) -> Unit = {}): StationDetail = detail(bookmark.pendingRow(), NearbyFilter(), onCurrent)
    fun recommendations(point: SearchPoint, filter: NearbyFilter): Recommendations
}
