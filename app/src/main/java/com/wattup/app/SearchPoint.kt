package com.wattup.app

data class SearchPoint(val latitude: Double, val longitude: Double) {
    companion object {
        val DAEGU = SearchPoint(35.8714, 128.6014)
    }
}
