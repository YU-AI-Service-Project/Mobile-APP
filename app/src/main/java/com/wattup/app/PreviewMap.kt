package com.wattup.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** Offline coordinate picker. Its centre is fixed until the user requests GPS. */
class PreviewMap(context: Context, private val select: (SearchPoint) -> Unit,
                 private val openStation: (StationRow) -> Unit) : View(context) {
    var center = SearchPoint.DAEGU
    var pin = center
    var stations: List<StationRow> = emptyList()
        set(value) {
            field = value
            hits.clear()
            badges = value.map { stationBadge(it, resources.displayMetrics.density) }
            invalidate()
        }
    private var badges = emptyList<android.graphics.Bitmap>()
    private val hits = mutableListOf<Pair<StationRow, android.graphics.RectF>>()
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
    private val span = 0.04

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xfff9fafb.toInt())
        brush.color = 0xffe5e7eb.toInt()
        for (i in 1..7) {
            val x = width * i / 8f
            val y = height * i / 8f
            canvas.drawLine(x, 0f, x, height.toFloat(), brush)
            canvas.drawLine(0f, y, width.toFloat(), y, brush)
        }
        hits.clear()
        stations.forEachIndexed { index, station ->
            val point = station.point ?: return@forEachIndexed
            val sx = ((0.5 + (point.longitude - center.longitude) / span) * width).toFloat()
            val sy = ((0.5 - (point.latitude - center.latitude) / span) * height).toFloat()
            val badge = badges[index]
            val top = if (index % 2 == 0) sy + 6 else sy - badge.height - 6
            brush.color = station.status.color
            canvas.drawCircle(sx, sy, 4f, brush)
            canvas.drawBitmap(badge, sx - badge.width / 2f, top, null)
            hits += station to android.graphics.RectF(sx - badge.width / 2f, minOf(top, sy - 5),
                sx + badge.width / 2f, maxOf(top + badge.height, sy + 5))
        }
        brush.color = 0xff374151.toInt()
        val x = (0.5 + (pin.longitude - center.longitude) / span) * width
        val y = (0.5 - (pin.latitude - center.latitude) / span) * height
        canvas.drawCircle(x.toFloat(), y.toFloat(), 8 * resources.displayMetrics.density, brush)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && width > 0 && height > 0) {
            val hit = hits.lastOrNull { it.second.contains(event.x, event.y) }
            if (hit != null) {
                openStation(hit.first)
                performClick()
                return true
            }
            val lat = center.latitude + (0.5 - event.y.coerceIn(0f, height.toFloat()) / height) * span
            val lon = center.longitude + (event.x.coerceIn(0f, width.toFloat()) / width - 0.5) * span
            select(SearchPoint(lat.coerceIn(-90.0, 90.0), (lon + 540) % 360 - 180))
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
