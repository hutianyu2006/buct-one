package com.buct.one.data.model

/**
 * 信息门户（i.buct.edu.cn）学期起止日期信息。
 * 包结构硬约束：model/ 禁止任何 import。
 */
data class PortalTermInfo(
    val wid: String,
    val academicYear: Int,
    val semester: Int,
    val termId: String,
    val beginDate: String,
    val endDate: String,
    val isCurrent: Boolean
)

/**
 * 信息门户校历接口返回的法定节假日或教学周日期条目。
 */
data class PortalHolidayItem(
    val date: String,
    val holidayName: String,
    val isHoliday: Boolean,
    val teachingWeek: Int
)

/**
 * i北化官方校历快照。
 */
data class PortalCalendarSnapshot(
    val currentTermId: String,
    val beginDate: String,
    val endDate: String,
    val currentTeachingWeek: Int,
    val terms: List<PortalTermInfo>,
    val holidays: List<PortalHolidayItem>
)

/**
 * 将校历接口返回的节假日全称规范为适合课表表头展示的两字短徽标。
 */
fun shortHolidayBadge(name: String): String {
    val clean = name.trim()
    if (clean.isEmpty()) return ""
    return when {
        clean.contains("国庆") -> "国庆"
        clean.contains("中秋") -> "中秋"
        clean.contains("劳动") || clean.contains("五一") -> "五一"
        clean.contains("端午") -> "端午"
        clean.contains("清明") -> "清明"
        clean.contains("元旦") -> "元旦"
        clean.contains("春节") -> "春节"
        clean.contains("寒假") -> "寒假"
        clean.contains("暑假") -> "暑假"
        else -> clean.removeSuffix("节").trim().take(2)
    }
}

/**
 * i北化图书馆在借图书条目（来自 getYktOrBook.jsp?type=book）。
 */
data class PortalBookItem(
    val title: String,
    val barcode: String,
    val borrowDate: String,
    val dueDate: String
)

/**
 * 图书馆待还书数量与在借清单快照。
 */
data class PortalLibrarySnapshot(
    val totalCount: Int,
    val books: List<PortalBookItem>
)

/**
 * 校内综合资讯栏位定义（来自 mnews.buct.edu.cn，siteId = 100000251）。
 */
enum class PortalNewsColumn(
    val columnId: String,
    val label: String,
    val sourceHint: String
) {
    NOTICE("100011560", "通知公告", "学工办 / 资助网"),
    ACTIVITY("100011559", "活动公告", "校团委"),
    CAREER("100011558", "招聘信息", "就业信息网"),
    NEWS("100011562", "北化新闻", "学校新闻网")
}

/**
 * 校内综合资讯文章条目。
 */
data class PortalNewsArticle(
    val id: String,
    val columnId: String,
    val title: String,
    val sourceOrg: String,
    val publishTimestampMs: Long,
    val summary: String,
    val content: String,
    val publicUrl: String,
    val isTop: Boolean,
    val isHot: Boolean,
    val isNewest: Boolean,
    val visitCount: String
)

/**
 * 单个资讯栏目的分页数据。
 */
data class PortalNewsColumnPage(
    val columnId: String,
    val totalCount: Int,
    val articles: List<PortalNewsArticle>
)

/**
 * 校内综合资讯四栏聚合快照。
 */
data class PortalNewsSnapshot(
    val columns: Map<String, PortalNewsColumnPage>
)
