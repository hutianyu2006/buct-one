package com.buct.one.data.model

/**
 * 教务消息（调停课提醒）分类。
 * 包结构硬约束：model/ 禁止任何 import。
 */
enum class JwReminderCategory {
    RESCHEDULE,
    MAKEUP,
    CANCEL,
    EXAM,
    OTHER
}

/**
 * 教务系统个人消息条目（来自 index_cxDbsy.html）。
 */
data class JwReminderItem(
    val id: String,
    val title: String,
    val content: String,
    val createdAt: String,
    val isRead: Boolean,
    val category: JwReminderCategory
)

/**
 * 教务系统通知公告列表条目（来自 xwck_cxMoreXwList.html）。
 */
data class JwAnnouncementItem(
    val id: String,
    val title: String,
    val publisher: String,
    val publishedAt: String,
    val isPinned: Boolean,
    val isRead: Boolean,
    val externalUrl: String,
    val inlineHtml: String
)

/**
 * 教务通知公告详情页正文（来自 xwck_ckXw.html）。
 */
data class JwAnnouncementDetail(
    val id: String,
    val title: String,
    val publisher: String,
    val publishedAt: String,
    val viewCount: String,
    val bodyHtml: String,
    val plainText: String
)

/**
 * 教务系统消息与通知公告完整快照。
 */
data class JwNoticeSnapshot(
    val reminders: List<JwReminderItem>,
    val reminderTotal: Int,
    val announcements: List<JwAnnouncementItem>,
    val announcementTotal: Int
)

fun classifyJwReminder(title: String, content: String): JwReminderCategory {
    val combined = "$title\n$content"
    return when {
        combined.contains("调课") -> JwReminderCategory.RESCHEDULE
        combined.contains("补课") -> JwReminderCategory.MAKEUP
        combined.contains("停课") -> JwReminderCategory.CANCEL
        combined.contains("考试") || combined.contains("缓考") || combined.contains("补考") -> JwReminderCategory.EXAM
        else -> JwReminderCategory.OTHER
    }
}

fun jwReminderCategoryLabel(category: JwReminderCategory): String = when (category) {
    JwReminderCategory.RESCHEDULE -> "调课"
    JwReminderCategory.MAKEUP -> "补课"
    JwReminderCategory.CANCEL -> "停课"
    JwReminderCategory.EXAM -> "考试"
    JwReminderCategory.OTHER -> "消息"
}
