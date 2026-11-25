package com.buct.one.data.text

/**
 * 解析器需要的日期时间工具（替代 java.text.SimpleDateFormat）。
 * 校园场景统一按北京时间展示与解析，避免引入时区库。
 */
private const val BEIJING_OFFSET_SECONDS = 8 * 3600
private const val SECONDS_PER_DAY = 86_400L

/** 格式化为 yyyy-MM-dd HH:mm:ss（默认北京时间）。 */
fun formatDateTime(epochMillis: Long, offsetSeconds: Int = BEIJING_OFFSET_SECONDS): String {
    if (epochMillis <= 0L) return ""
    val localMillis = epochMillis + offsetSeconds * 1000L
    val epochDay = floorDiv(localMillis, SECONDS_PER_DAY * 1000L)
    val secondOfDay = localMillis / 1000L - epochDay * SECONDS_PER_DAY
    val (year, month, day) = civilFromEpochDay(epochDay)
    val hour = (secondOfDay / 3600L).toInt()
    val minute = ((secondOfDay % 3600L) / 60L).toInt()
    val second = (secondOfDay % 60L).toInt()
    return "$year-${twoDigits(month)}-${twoDigits(day)} ${twoDigits(hour)}:${twoDigits(minute)}:${twoDigits(second)}"
}

/** 解析 yyyy-MM-dd HH:mm:ss（默认北京时间），格式非法返回 null。 */
fun parseDateTime(text: String, offsetSeconds: Int = BEIJING_OFFSET_SECONDS): Long? {
    val trimmed = text.trim()
    if (trimmed.length < 19) return null
    val year = trimmed.substring(0, 4).toIntOrNull() ?: return null
    val month = trimmed.substring(5, 7).toIntOrNull() ?: return null
    val day = trimmed.substring(8, 10).toIntOrNull() ?: return null
    val hour = trimmed.substring(11, 13).toIntOrNull() ?: return null
    val minute = trimmed.substring(14, 16).toIntOrNull() ?: return null
    val second = trimmed.substring(17, 19).toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
    val epochDay = daysFromCivil(year, month, day)
    val seconds = epochDay * SECONDS_PER_DAY + hour * 3600L + minute * 60L + second
    return seconds * 1000L - offsetSeconds * 1000L
}

internal fun twoDigits(value: Int): String = if (value < 10) "0$value" else value.toString()

/** Howard Hinnant 的 days_from_civil 算法。 */
internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1L else year.toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val monthPrime = if (month > 2) month - 3L else month + 9L
    val dayOfYear = (153 * monthPrime + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146097L + dayOfEra - 719468L
}

private fun floorDiv(value: Long, divisor: Long): Long {
    val q = value / divisor
    return if (value % divisor != 0L && (value xor divisor) < 0) q - 1 else q
}
