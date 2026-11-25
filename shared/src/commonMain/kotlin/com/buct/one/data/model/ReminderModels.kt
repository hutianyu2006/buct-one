package com.buct.one.data.model

/**
 * 本地智能提醒设置与规则引擎。
 * 包结构硬约束：model/ 禁止任何 import。
 */
data class ReminderSettings(
    val enabled: Boolean = true,
    val theolDdlEnabled: Boolean = true,
    val theolOffsetsHours: List<Int> = listOf(24, 3),
    val libraryDueEnabled: Boolean = true,
    val backgroundSyncEnabled: Boolean = true,
    val syncIntervalHours: Int = 4,
    val jwNoticeEnabled: Boolean = true,
    val electricityLowEnabled: Boolean = true,
    val cardLowEnabled: Boolean = true,
    val cardLowThresholdYuan: Int = 20,
    val quietHoursEnabled: Boolean = true
)

data class ReminderRunSummary(
    val lastRunAtMillis: Long = 0L,
    val lastResult: String = "",
    val notifiedCount: Int = 0
)

object ReminderRuleEngine {

    val VALID_DDL_OFFSETS_HOURS = listOf(24, 12, 3, 1)
    val VALID_SYNC_INTERVALS_HOURS = listOf(4, 6, 12)
    val VALID_CARD_THRESHOLDS_YUAN = listOf(10, 20, 30, 50)

    /** 判断当前时间是否处于夜间免打扰窗口（23:30 – 07:30）。 */
    fun isInQuietHours(hourOfDay: Int, minute: Int, quietHoursEnabled: Boolean): Boolean {
        if (!quietHoursEnabled) return false
        val totalMinutes = hourOfDay * 60 + minute
        return totalMinutes >= (23 * 60 + 30) || totalMinutes < (7 * 60 + 30)
    }

    /** 仅当余额从「高于阈值」跌破至「低于阈值」时触发一次提醒，避免重复轰炸。 */
    fun shouldTriggerLowBalance(isLowNow: Boolean, wasLowBefore: Boolean): Boolean =
        isLowNow && !wasLowBefore

    /** 解析 YYYY-MM-DD 或 YYYY/MM/DD（可带尾随时间），返回 (year, month, day)。 */
    fun parseSimpleDateYmd(raw: String): Triple<Int, Int, Int>? {
        val trimmed = raw.trim()
        if (trimmed.length < 8) return null
        val datePart = trimmed.split(' ', 'T').firstOrNull()?.trim().orEmpty()
        val sep = when {
            '-' in datePart -> '-'
            '/' in datePart -> '/'
            else -> return null
        }
        val parts = datePart.split(sep)
        if (parts.size < 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (year !in 2000..2100 || month !in 1..12 || day !in 1..31) return null
        return Triple(year, month, day)
    }
}
