package com.buct.one.data.model

/**
 * 班级任务汇总表（.xlsx / .csv）导入审核草稿模型（零外部 import）。
 */
enum class ImportDiffStatus(val label: String) {
    NEW("新任务"),
    UPDATE_AVAILABLE("有更新"),
    ALREADY_IMPORTED("已导入"),
    THEOL_DUPLICATE("北化在线已有"),
    EXPIRED("已过期")
}

data class ImportedTaskDraft(
    val tempId: String,
    val sheetName: String,
    val courseName: String,
    val cleanCourseName: String,
    val title: String,
    val deadlineMillis: Long?,
    val deadlineText: String,
    val taskType: String = "作业",
    val submitWay: String = "",
    val scope: String = "全体",
    val location: String = "",
    val note: String = "",
    val linkUrl: String = "",
    val importKey: String,
    val diffStatus: ImportDiffStatus,
    val selectedByDefault: Boolean,
    val warnings: List<String> = emptyList()
)

data class ImportedExamDraft(
    val tempId: String,
    val sheetName: String,
    val courseName: String,
    val cleanCourseName: String,
    val title: String,
    val startAt: Long,
    val endAt: Long = 0L,
    val timeText: String = "",
    val location: String = "",
    val note: String = "",
    val diffStatus: ImportDiffStatus,
    val selectedByDefault: Boolean
)

data class TaskImportReport(
    val sourceName: String,
    val tasks: List<ImportedTaskDraft>,
    val exams: List<ImportedExamDraft>,
    val skippedRows: Int = 0,
    val errors: List<String> = emptyList()
)
