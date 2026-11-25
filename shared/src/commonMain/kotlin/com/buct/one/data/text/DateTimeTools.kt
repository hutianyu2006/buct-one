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


/**
 * 宽松日期解析：支持教务系统常见的 "yyyy年M月d日 HH:mm[:ss]" / "yyyy-MM-dd HH:mm[:ss]" /
 * "yyyy-MM-dd'T'HH:mm[:ss]" 等写法（替代 SimpleDateFormat 多格式轮询）。
 */
fun parseDateTimeLoose(text: String, offsetSeconds: Int = BEIJING_OFFSET_SECONDS): Long? {
    val normalized = text.trim()
        .replace('年', '-')
        .replace('月', '-')
        .replace("日", " ")
        .replace('T', ' ')
        .replace('t', ' ')
        .replace(Regex("\\s+"), " ")
    if (normalized.isEmpty()) return null

    val parts = normalized.split(' ')
    val datePart = parts.getOrNull(0) ?: return null
    val timePart = parts.getOrNull(1).orEmpty()

    val dateFields = datePart.split('-', '/')
    if (dateFields.size < 3) return null
    val year = dateFields[0].toIntOrNull() ?: return null
    val month = dateFields[1].toIntOrNull() ?: return null
    val day = dateFields[2].toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31) return null

    var hour = 0
    var minute = 0
    var second = 0
    if (timePart.isNotEmpty()) {
        val timeFields = timePart.split(':')
        hour = timeFields.getOrNull(0)?.toIntOrNull() ?: return null
        minute = timeFields.getOrNull(1)?.toIntOrNull() ?: 0
        second = timeFields.getOrNull(2)?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
    }

    val epochDay = daysFromCivil(year, month, day)
    val seconds = epochDay * SECONDS_PER_DAY + hour * 3600L + minute * 60L + second
    return seconds * 1000L - offsetSeconds * 1000L
}

/** 最小 HTML 实体反转义（替代 jsoup Parser.unescapeEntities 在无 DOM 场景下的用法）。 */
fun htmlUnescape(value: String): String {
    if ('&' !in value) return value
    val builder = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char != '&') {
            builder.append(char)
            index++
            continue
        }
        val end = value.indexOf(';', index + 1)
        if (end < 0 || end - index > 10) {
            builder.append(char)
            index++
            continue
        }
        val entity = value.substring(index + 1, end)
        val decoded = when {
            entity == "amp" -> "&"
            entity == "lt" -> "<"
            entity == "gt" -> ">"
            entity == "quot" -> "\""
            entity == "apos" -> "'"
            entity == "nbsp" -> " "
            entity.startsWith("#x") || entity.startsWith("#X") ->
                entity.substring(2).toIntOrNull(16)?.let { codePointsToString(it) }
            entity.startsWith("#") ->
                entity.substring(1).toIntOrNull()?.let { codePointsToString(it) }
            else -> null
        }
        if (decoded != null) {
            builder.append(decoded)
            index = end + 1
        } else {
            builder.append(char)
            index++
        }
    }
    return builder.toString()
}

private fun codePointsToString(codePoint: Int): String? {
    if (codePoint <= 0 || codePoint > 0x10FFFF) return null
    return if (codePoint <= 0xFFFF) {
        codePoint.toChar().toString()
    } else {
        val adjusted = codePoint - 0x10000
        val high = ((adjusted shr 10) + 0xD800).toChar()
        val low = ((adjusted and 0x3FF) + 0xDC00).toChar()
        "" + high + low
    }
}
