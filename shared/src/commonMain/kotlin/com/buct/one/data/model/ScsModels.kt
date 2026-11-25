package com.buct.one.data.model

/**
 * 第二课堂（scs.buct.edu.cn / eapi.buct.edu.cn）纯数据模型（零依赖）。
 */
data class ScsEvalDimension(
    val typeId: Int,
    val name: String,
    val totalMax: Double,
    val basicMax: Double,
    val extMax: Double
)

data class ScsEvalItem(
    val id: Long,
    val typeId: Int,
    val isBasic: Boolean,
    val shortDesc: String,
    val description: String,
    val score: Double?,
    val mark: String = "",
    val teacherComments: String = "",
    val laborHours: Double? = null,
    val stuAble: Boolean = false
)

data class ScsCategoryDetail(
    val typeId: Int,
    val title: String,
    val basicScore: Double?,
    val extScore: Double?,
    val totalScore: Double?,
    val maxTotalScore: Double?,
    val items: List<ScsEvalItem> = emptyList()
)

data class ScsTermScore(
    val termId: Int,
    val yearId: Int,
    val yearName: String,
    val termName: String,
    val isFullYear: Boolean,
    val status: Int,
    val totalScore: Double?,
    val moral: Double?,
    val academic: Double?,
    val physical: Double?,
    val aesthetics: Double?,
    val labor: Double?,
    val isOpenSelfEva: Boolean = false,
    val details: List<ScsCategoryDetail> = emptyList()
)

data class ScsCurrentEvalWindow(
    val yearId: Int,
    val termId: Int,
    val yearName: String,
    val termName: String,
    val isOpen: Boolean,
    val startTime: String = "",
    val endTime: String = ""
)

data class ScsActivityItem(
    val id: String,
    val title: String,
    val categoryName: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val location: String = "",
    val score: String = "",
    val status: String = ""
)

data class ScsSnapshot(
    val studentName: String = "",
    val studentId: String = "",
    val collegeName: String = "",
    val className: String = "",
    val gradeName: String = "",
    val currentWindow: ScsCurrentEvalWindow? = null,
    val dimensions: List<ScsEvalDimension> = emptyList(),
    val terms: List<ScsTermScore> = emptyList(),
    val activities: List<ScsActivityItem> = emptyList()
)
