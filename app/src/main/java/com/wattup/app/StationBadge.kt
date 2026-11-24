package com.wattup.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.ceil

internal fun stationBadge(station: StationRow, density: Float): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * density }
    val padding = 5 * density
    val width = ceil(paint.measureText(station.badge) + 2 * padding).toInt()
    val height = ceil(paint.descent() - paint.ascent() + 2 * padding).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    paint.color = station.status.color
    canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), padding, padding, paint)
    paint.color = Color.WHITE
    canvas.drawText(station.badge, padding, padding - paint.ascent(), paint)
    return bitmap
}
