package com.wattup.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun instant(time: String): Long? {
    val normalized = time.replace(Regex("\\.\\d+(?=[Z+-])"), "").replace("Z", "+0000")
        .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply { isLenient = false }
    val date = try { parser.parse(normalized) } catch (_: java.text.ParseException) { null }
    return date?.time
}

internal fun koreanTime(time: String): String {
    val millis = instant(time) ?: return time
    val formatter = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
    formatter.timeZone = TimeZone.getTimeZone("Asia/Seoul")
    return formatter.format(Date(millis))
}
