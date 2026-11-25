package com.buct.one.data.model

/**
 * 体质测试与体育管理平台（tygl.buct.edu.cn）纯数据模型。
 * 严禁新增任何 import，保持 model 包零依赖门禁。
 */
data class TyglProfile(
    val studentName: String = "",
    val studentId: String = "",
    val gender: String = "",
    val gradeName: String = "",
    val className: String = "",
    val collegeName: String = "",
    val isTested: String = "",
    val specialNote: String = ""
)

data class TyglFitnessItem(
    val name: String,
    val rawResult: String = "",
    val score: Double? = null,
    val scoreText: String = "",
    val level: String = ""
)

data class TyglPrescription(
    val rowNo: String = "",
    val diagnosis: String = "",
    val prescription: String = "",
    val note: String = ""
)

data class TyglFitnessRecord(
    val yearKey: String,
    val yearLabel: String,
    val testYear: String = "",
    val updatedAt: String = "",
    val totalScore: Double? = null,
    val totalScoreText: String = "",
    val level: String = "",
    val gradeName: String = "",
    val heightText: String = "",
    val weightText: String = "",
    val bonusScoreText: String = "",
    val bonusReason: String = "",
    val items: List<TyglFitnessItem> = emptyList(),
    val prescriptions: List<TyglPrescription> = emptyList()
)

data class TyglRunPlan(
    val projectCode: String = "",
    val projectName: String = "",
    val dateRange: String = "",
    val timeRange: String = "",
    val distanceLimit: String = "",
    val paceLimit: String = "",
    val weekDays: String = ""
)

data class TyglExerciseColumn(
    val code: String,
    val label: String
)

data class TyglExerciseTerm(
    val academicYear: String,
    val term: String,
    val countsByCode: Map<String, String> = emptyMap()
)

data class TyglPeCourseScore(
    val academicYear: String,
    val term: String,
    val courseName: String,
    val actualScore: String = "",
    val actualLevel: String = "",
    val termScore: String = "",
    val termLevel: String = "",
    val specialNote: String = "",
    val teacher: String = "",
    val statusMessage: String = ""
)

data class TyglExamRecord(
    val rowNo: String = "",
    val peClass: String = "",
    val startTime: String = "",
    val duration: String = "",
    val score: String = "",
    val paperId: String = ""
)

data class TyglExamInfo(
    val currentTerm: String = "",
    val peClass: String = "",
    val remainingAttempts: Int? = null,
    val bestScore: String = "",
    val notice: String = "",
    val scheduleNotice: String = "",
    val records: List<TyglExamRecord> = emptyList()
)

data class TyglSnapshot(
    val profile: TyglProfile = TyglProfile(),
    val fitnessRecords: List<TyglFitnessRecord> = emptyList(),
    val runPlan: TyglRunPlan? = null,
    val exerciseColumns: List<TyglExerciseColumn> = emptyList(),
    val exerciseTerms: List<TyglExerciseTerm> = emptyList(),
    val peCourses: List<TyglPeCourseScore> = emptyList(),
    val examInfo: TyglExamInfo? = null
)
