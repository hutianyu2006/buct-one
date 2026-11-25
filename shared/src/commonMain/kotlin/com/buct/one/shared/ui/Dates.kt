package com.buct.one.shared.ui

/** 公告发布时间按北京时间展示：服务端与全校用户均在国内，避免为一行日期引入时区依赖。 */
private const val BEIJING_UTC_OFFSET_MILLIS = 8L * 3_600_000L

/** 把 epoch 毫秒按北京时间格式化为 yyyy-MM-dd。 */
fun formatNoticeDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val epochDay = (epochMillis + BEIJING_UTC_OFFSET_MILLIS) / MILLIS_PER_DAY
    val (year, month, day) = civilFromEpochDay(epochDay)
    return "$year-${twoDigits(month)}-${twoDigits(day)}"
}

private const val MILLIS_PER_DAY = 86_400_000L

private fun twoDigits(value: Int): String = if (value < 10) "0$value" else value.toString()

/** Howard Hinnant 的 civil_from_days 算法（纯整数，跨平台无时区依赖）。 */
internal fun civilFromEpochDay(epochDay: Long): Triple<Int, Int, Int> {
    val z = epochDay + 719468L
    val era = (if (z >= 0) z else z - 146096L) / 146097L
    val dayOfEra = z - era * 146097L
    val yearOfEra = (dayOfEra - dayOfEra / 1460L + dayOfEra / 36524L - dayOfEra / 146096L) / 365L
    val year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = if (monthPrime < 10L) monthPrime + 3L else monthPrime - 9L
    val civilYear = if (month <= 2L) year + 1L else year
    return Triple(civilYear.toInt(), month.toInt(), day.toInt())
}
