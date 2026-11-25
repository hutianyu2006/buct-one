package com.buct.one.data.model

/**
 * 实践教学综合服务平台（sjcx.buct.edu.cn）领域模型。
 * 遵循包结构规则：model 层零外部依赖。
 */

data class SjcxExperimentReportItem(
    val courseId: String,
    val courseCode: String,
    val courseName: String,
    val expId: String,
    val expCode: String,
    val expName: String,
    val stuId: String,
    val groupId: String,
    val reportId: String = "",
    /** 教师提供的实验报告模板文件名（如 "实验报告PDF版.pdf"），空字符串代表未设置模板 */
    val templateName: String = "",
    /** 学生已上传的实验报告文件名（如 "扫描文件_20261022_211608.pdf"） */
    val uploadedDocName: String = "",
    /** 1 = 允许上传, 0 = 暂未开放或已截止 */
    val canUpload: Boolean = false,
    /** 1 = 未上传, 2 = 未评阅(已上传), 3 = 已评阅, 4 = 驳回 */
    val state: Int = 1,
    val opinion: String = "",
    val yearterm: String = ""
) {
    val stateLabel: String
        get() = when (state) {
            1 -> "未上传"
            2 -> "已上传 · 待评阅"
            3 -> "已评阅"
            4 -> "已驳回"
            else -> "未知"
        }

    val isSubmitted: Boolean
        get() = state == 2 || state == 3 || uploadedDocName.isNotBlank()

    val isPendingUpload: Boolean
        get() = state == 1 || state == 4
}

data class SjcxExperimentScheduleItem(
    val courseName: String,
    val expName: String,
    val roomName: String,
    val teacherName: String,
    val week: Int,
    val weekDay: Int,
    /** 节次描述，如 "第07到09节" */
    val jcText: String,
    val seatNumber: String = "",
    /** 1 = 自选, 2 = 预置, 其他 = 代选 */
    val formType: Int = 2
) {
    val weekDayLabel: String
        get() = when (weekDay) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            7 -> "周日"
            else -> "周$weekDay"
        }

    val formLabel: String
        get() = when (formType) {
            1 -> "自选"
            2 -> "预置"
            else -> "代选"
        }
}

data class SjcxExperimentScoreItem(
    val courseId: String,
    val courseName: String,
    val expName: String,
    val teacherName: String,
    val week: Int,
    val weekDay: Int,
    val jcText: String,
    val totalScore: String = "",
    val subScoresLabel: String = ""
)

data class SjcxLectureBatchItem(
    val id: String,
    val projectId: String,
    val projectName: String,
    val batch: String,
    val place: String,
    val speaker: String,
    val teacherName: String = "",
    val campus: String = "",
    val campusName: String = "",
    /** 报名开始时间 YYYY-MM-DD HH:mm:ss */
    val signupBeginTime: String = "",
    /** 报名截止时间 YYYY-MM-DD HH:mm:ss */
    val signupEndTime: String = "",
    /** 讲座开始时间 YYYY-MM-DD HH:mm:ss */
    val lectureBeginTime: String = "",
    /** 讲座结束时间 YYYY-MM-DD HH:mm:ss */
    val lectureEndTime: String = "",
    val registrationNum: Int = 0,
    val totalNum: Int = 0,
    /** 1 = 需审核, 0 = 免审核直接录取 */
    val requireReview: Boolean = false
) {
    val isFull: Boolean
        get() = totalNum > 0 && registrationNum >= totalNum
}

data class SjcxMyLectureItem(
    val creProjectStuId: String,
    val projectId: String,
    val projectName: String,
    val batchName: String,
    val place: String,
    val speaker: String,
    val teacherName: String = "",
    val beginTime: String = "",
    val endTime: String = "",
    val pendingName: String = "",
    val experience: String = "",
    /** "1" = 已认定学分（不可再修改心得或取消） */
    val isRecognized: Boolean = false
)

data class SjcxCreditRecordItem(
    val creStuId: String,
    val projectName: String,
    val standardName: String,
    val typeName: String,
    val pendingName: String,
    val credit: Double,
    val addTime: String
)

data class SjcxSnapshot(
    val yearterm: String = "",
    val currentWeek: Int = 1,
    val reports: List<SjcxExperimentReportItem> = emptyList(),
    val schedules: List<SjcxExperimentScheduleItem> = emptyList(),
    val scores: List<SjcxExperimentScoreItem> = emptyList(),
    val openLectures: List<SjcxLectureBatchItem> = emptyList(),
    val endedLectures: List<SjcxLectureBatchItem> = emptyList(),
    val myLectures: List<SjcxMyLectureItem> = emptyList(),
    val creditRecords: List<SjcxCreditRecordItem> = emptyList(),
    val totalCredit: Double = 0.0,
    val noSignCount: Int = 0
)

data class SjcxDownloadedFile(
    val localPath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
)
