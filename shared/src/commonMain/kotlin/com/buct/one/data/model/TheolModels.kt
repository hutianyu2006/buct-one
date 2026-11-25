package com.buct.one.data.model

/**
 * 北化在线（THEOL / MEOL）课程、作业、测验与本地自定义待办的纯数据模型（零依赖）。
 */
data class TheolCourseInfo(
    val lid: String,
    val courseName: String,
    val type: String, // "homework" | "test" | "course"
    val url: String
)

data class TheolHomeworkItem(
    val hwtid: String,
    val lid: String,
    val courseName: String,
    val title: String,
    val deadline: String,
    val deadlineMillis: Long? = null,
    val score: String = "",
    val publisher: String = "",
    val canSubmit: Boolean = false,
    val hasResult: Boolean = false,
    val isGroup: Boolean = false,
    val status: String = "",
    val detailHref: String = "",
    val submitHref: String = "",
    val resultHref: String = ""
)

data class TheolHomeworkAttachment(
    val name: String,
    val url: String
)

data class TheolDownloadedFile(
    val filePath: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isHtmlPage: Boolean = false
)

data class TheolHomeworkDetail(
    val hwtid: String,
    val title: String = "",
    val deadline: String = "",
    val score: String = "",
    val publisher: String = "",
    val contentText: String = "",
    val attachments: List<TheolHomeworkAttachment> = emptyList(),
    val fetchedAt: Long = 0L
)

data class TheolTestItem(
    val testId: String,
    val lid: String,
    val courseName: String,
    val title: String,
    val startTime: String,
    val endTime: String,
    val endTimeMillis: Long? = null,
    val duration: String = "",
    val allowedAttempts: String = "",
    val canStart: Boolean = false,
    val hasResult: Boolean = false,
    val status: String = "",
    val testUrl: String = "",
    val resultHref: String = ""
)

data class TheolTestDetail(
    val testId: String,
    val title: String = "",
    val description: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val duration: String = "",
    val totalScore: String = "",
    val questionCount: String = "",
    val fetchedAt: Long = 0L
)

data class TheolResourceItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val url: String,
    val sizeText: String = "",
    val updatedAt: String = "",
    val fileExt: String = "",
    val previewUrl: String = ""
)

data class TheolCourseUnit(
    val columnId: String,
    val title: String,
    val url: String
)

data class TheolResourcePage(
    val lid: String,
    val courseName: String,
    val currentUrl: String,
    val folderTitle: String,
    val items: List<TheolResourceItem>
)

data class TheolSnapshot(
    val homeworks: List<TheolHomeworkItem> = emptyList(),
    val tests: List<TheolTestItem> = emptyList(),
    val failedCourseCount: Int = 0,
    val courses: List<TheolCourseInfo> = emptyList()
)

/** 被用户拉黑的北化在线课程（例如已退课但系统仍残留在待办列表中） */
data class BlacklistedCourse(
    val key: String,
    val lid: String,
    val courseName: String
)

/** 用户在作业页的本地过滤配置：手动删除的作业/测验与课程黑名单（刷新时持久化保持过滤） */
data class TheolFilterPrefs(
    val hiddenHomeworkIds: Set<String> = emptySet(),
    val hiddenTestKeys: Set<String> = emptySet(),
    val blacklistedCourses: List<BlacklistedCourse> = emptyList()
) {
    fun isCourseBlacklisted(lid: String, courseName: String): Boolean =
        blacklistedCourses.any { bc ->
            (lid.isNotBlank() && bc.lid == lid) ||
                (courseName.isNotBlank() && bc.courseName == courseName)
        }

    fun isHomeworkVisible(item: TheolHomeworkItem): Boolean =
        item.hwtid !in hiddenHomeworkIds && !isCourseBlacklisted(item.lid, item.courseName)

    fun isTestVisible(item: TheolTestItem): Boolean =
        "${item.lid}:${item.testId}" !in hiddenTestKeys && !isCourseBlacklisted(item.lid, item.courseName)
}

/**
 * 用户本地自定义待办 / 作业提醒（参考 BUCT-course-remind 模型）。
 * 支持三档提醒：DDL 前 24h / 12h / 1h，软删除进回收站。
 */
data class LocalTodoItem(
    val id: String,
    val title: String,
    val courseName: String = "",
    val deadlineMillis: Long? = null,
    val priority: Int = 1, // 0=低 1=普通 2=高
    val completed: Boolean = false,
    val deleted: Boolean = false,
    val remind24h: Boolean = true,
    val remind12h: Boolean = true,
    val remind1h: Boolean = true,
    val createdAt: Long = 0L,
    val submitWay: String = "",
    val note: String = "",
    val linkUrl: String = "",
    val importKey: String = ""
)
