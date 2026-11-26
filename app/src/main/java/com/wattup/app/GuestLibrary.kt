package com.wattup.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Place(val name: String, val address: String, val point: SearchPoint)
data class Bookmark(val id: String, val name: String, val point: SearchPoint) {
    fun pendingRow() = StationRow(id, name, point, 0, "지원 종류 정보 없음", 0, null, Availability.UNKNOWN)
}

internal fun matchingPlaces(query: String, history: List<Place>, places: List<Place>): List<Place> {
    val term = query.trim()
    return if (term.isEmpty()) (history + places).distinctBy { it.point }
        else places.filter { it.name.contains(term, true) || it.address.contains(term, true) }
}

internal fun recentPlaces(previous: List<Place>, selected: Place): List<Place> =
    (listOf(selected) + previous.filterNot { it.point == selected.point }).take(5)

internal class GuestLibrary(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("wattup_guest", Context.MODE_PRIVATE))

    private fun entries(key: String): List<JSONObject> {
        val array = JSONArray(prefs.getString(key, "[]") ?: "[]")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun point(item: JSONObject) = SearchPoint(item.getDouble("lat"), item.getDouble("lon"))
    private fun entry(name: String, point: SearchPoint) = JSONObject()
        .put("name", name).put("lat", point.latitude).put("lon", point.longitude)

    fun history() = entries("history").map { Place(it.getString("name"), it.getString("address"), point(it)) }
    fun remember(place: Place) {
        val items = recentPlaces(history(), place).map { entry(it.name, it.point).put("address", it.address) }
        prefs.edit().putString("history", JSONArray(items).toString()).apply()
    }

    fun favorites() = entries("favorites").map { Bookmark(it.getString("id"), it.getString("name"), point(it)) }
    fun toggle(bookmark: Bookmark) {
        val saved = favorites().toMutableList()
        if (!saved.removeAll { it.id == bookmark.id }) saved.add(0, bookmark)
        val items = saved.map { entry(it.name, it.point).put("id", it.id) }
        prefs.edit().putString("favorites", JSONArray(items).toString()).apply()
    }
}
