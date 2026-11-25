package com.buct.one.data.source.tygl

import com.buct.one.data.model.TyglExamInfo
import com.buct.one.data.model.TyglExamRecord
import com.buct.one.data.model.TyglExerciseColumn
import com.buct.one.data.model.TyglExerciseTerm
import com.buct.one.data.model.TyglFitnessItem
import com.buct.one.data.model.TyglFitnessRecord
import com.buct.one.data.model.TyglPeCourseScore
import com.buct.one.data.model.TyglPrescription
import com.buct.one.data.model.TyglProfile
import com.buct.one.data.model.TyglRunPlan
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.buct.one.data.text.urlDecode

/**
 * 体质测试与体育管理平台（tygl.buct.edu.cn）纯函数 HTML 解析器。
 * 改动解析逻辑时必须将 PARSER_VERSION + 1。
 */
object TyglParser {
    const val PARSER_VERSION = 2

    private val YEAR_QUERY_REGEX = Regex("[?&]year=([^&]+)")
    private val CODE_IN_PARENS_REGEX = Regex("\\((\\d+)\\)")
    private val VIEW_DETAIL_REGEX = Regex("\\(\\s*查看明细\\s*\\)")

    data class SidPortalFormParams(
        val execution: String,
        val croypto: String,
        val errorCode: String,
        val second: String,
        val captchaUrl: String
    )

    fun parseSidPortalPage(html: String): SidPortalFormParams {
        val doc = Ksoup.parse(html)
        fun text(id: String) = doc.selectFirst("#$id")?.text()?.trim().orEmpty()
        return SidPortalFormParams(
            execution = text("login-page-flowkey"),
            croypto = text("login-croypto"),
            errorCode = text("login-error-msg").ifEmpty { text("login-error-code") },
            second = text("sso-second"),
            captchaUrl = text("captcha-url")
        )
    }

    fun isAuthenticatedPage(html: String): Boolean {
        if (html.isBlank()) return false
        if (html.contains("login-page-flowkey") ||
            html.contains("login-croypto") ||
            html.contains("accessDenied") ||
            html.contains("action=\"login.php\"") ||
            html.contains("action='login.php'")
        ) {
            return false
        }
        return html.contains("mainContent") ||
            html.contains("searchtermresults") ||
            html.contains("stu_ht_score") ||
            html.contains("学生登陆成功") ||
            html.contains("测试年度")
    }

    fun parseFitnessYearTabs(html: String): List<Pair<String, String>> {
        val doc = Ksoup.parse(html)
        val result = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        for (a in doc.select("table.table_t a[href*=stu_ht_score]")) {
            val href = a.attr("href")
            val match = YEAR_QUERY_REGEX.find(href) ?: continue
            val encoded = match.groupValues[1]
            val key = runCatching { urlDecode(encoded) }.getOrDefault(encoded).trim()
            if (key.isEmpty() || !seen.add(key)) continue
            val label = cleanText(a.text()).ifBlank { key }
            result.add(key to label)
        }
        return result
    }

    fun parseFitnessPage(
        html: String,
        fallbackYearKey: String = "",
        fallbackYearLabel: String = ""
    ): Pair<TyglProfile, TyglFitnessRecord?> {
        val doc = Ksoup.parse(html)

        var activeKey = fallbackYearKey
        var activeLabel = fallbackYearLabel
        val activeLink = doc.selectFirst("table.table_t td.menu4l_active a[href*=stu_ht_score]")
            ?: doc.selectFirst("table.table_t td.menu4l_active a")
        if (activeLink != null) {
            val match = YEAR_QUERY_REGEX.find(activeLink.attr("href"))
            if (match != null) {
                val enc = match.groupValues[1]
                activeKey = runCatching { urlDecode(enc) }.getOrDefault(enc).trim()
            }
            activeLabel = cleanText(activeLink.text())
        }
        if (activeKey.isBlank()) {
            val firstTab = parseFitnessYearTabs(html).firstOrNull()
            if (firstTab != null) {
                activeKey = firstTab.first
                activeLabel = firstTab.second
            }
        }
        if (activeLabel.isBlank()) activeLabel = activeKey

        val tables = doc.select("table.table_bg")
        var profile = TyglProfile()
        var testYear = ""
        var updatedAt = ""
        var totalScoreText = ""
        var level = ""
        var gradeName = ""
        var heightText = ""
        var weightText = ""
        var bonusScoreText = ""
        var bonusReason = ""
        val items = ArrayList<TyglFitnessItem>()
        val prescriptions = ArrayList<TyglPrescription>()
        var hasScoreSummary = false

        for (table in tables) {
            val text = cleanText(table.text())
            when {
                text.contains("姓名") && text.contains("学号") && !text.contains("当前学期") -> {
                    val map = extractKeyValues(table)
                    profile = TyglProfile(
                        studentName = map["姓名"].orEmpty(),
                        studentId = map["学号"].orEmpty(),
                        gender = map["性别"].orEmpty(),
                        gradeName = map["年级"].orEmpty(),
                        className = map["行政班"].orEmpty(),
                        collegeName = map["学院"].orEmpty(),
                        isTested = map["是否测试"].orEmpty(),
                        specialNote = map["特殊情况"].orEmpty()
                    )
                }

                text.contains("测试年度") && text.contains("最后更新") -> {
                    hasScoreSummary = true
                    val map = extractKeyValues(table)
                    testYear = map["测试年度"].orEmpty()
                    updatedAt = map["最后更新"].orEmpty()
                    totalScoreText = map["成绩"].orEmpty()
                    level = map["等级"].orEmpty()
                    gradeName = map["年级"].orEmpty()
                    heightText = map["身高"].orEmpty()
                    weightText = map["体重"].orEmpty()
                }

                text.contains("项目") && text.contains("成绩") && text.contains("得分") -> {
                    val rows = table.select("tr")
                    for ((rowIdx, row) in rows.withIndex()) {
                        val cells = row.select("td, th").map { cleanText(it.text()) }
                        if (cells.isEmpty()) continue
                        if (rowIdx == 0 && cells[0] == "项目") continue

                        // Left quartet (indices 0..3)
                        if (cells.size >= 4) {
                            val name = cells[0]
                            val rawRes = cells[1]
                            val scText = cells[2]
                            val lvl = cells[3]
                            if (name.isNotBlank() && !(name.contains("无需测试") && rawRes.isBlank() && scText.isBlank() && lvl.isBlank())) {
                                items.add(
                                    TyglFitnessItem(
                                        name = name,
                                        rawResult = rawRes,
                                        score = scText.toDoubleOrNull(),
                                        scoreText = scText,
                                        level = lvl
                                    )
                                )
                            }
                        }

                        // Right quartet (indices 4..7)
                        if (cells.size >= 8) {
                            val name = cells[4]
                            val col5 = cells[5]
                            val col6 = cells[6]
                            val col7 = cells[7]
                            if (name == "加分") {
                                bonusScoreText = col5
                                bonusReason = if (col6.contains("原因")) col7 else col6
                            } else if (name.isNotBlank() && !(name.contains("无需测试") && col5.isBlank() && col6.isBlank() && col7.isBlank())) {
                                items.add(
                                    TyglFitnessItem(
                                        name = name,
                                        rawResult = col5,
                                        score = col6.toDoubleOrNull(),
                                        scoreText = col6,
                                        level = col7
                                    )
                                )
                            }
                        }
                    }
                }

                text.contains("运动处方") -> {
                    for (row in table.select("tr")) {
                        val rowText = cleanText(row.text())
                        if (rowText.contains("运动处方") || rowText.contains("当前尚未生成运动处方")) continue
                        val cells = row.select("td, th").map { cleanText(it.text()) }
                        if (cells.size >= 4 && cells[0] != "行号") {
                            prescriptions.add(
                                TyglPrescription(
                                    rowNo = cells[0],
                                    diagnosis = cells[1],
                                    prescription = cells[2],
                                    note = cells[3]
                                )
                            )
                        }
                    }
                }
            }
        }

        val record = if (hasScoreSummary || items.isNotEmpty()) {
            val resolvedKey = activeKey.ifBlank { testYear.ifBlank { "default" } }
            val resolvedLabel = activeLabel.ifBlank {
                if (testYear.isNotBlank()) "${testYear}年" else resolvedKey
            }
            TyglFitnessRecord(
                yearKey = resolvedKey,
                yearLabel = resolvedLabel,
                testYear = testYear,
                updatedAt = updatedAt,
                totalScore = totalScoreText.toDoubleOrNull(),
                totalScoreText = totalScoreText,
                level = level,
                gradeName = gradeName.ifBlank { profile.gradeName },
                heightText = heightText,
                weightText = weightText,
                bonusScoreText = bonusScoreText,
                bonusReason = bonusReason,
                items = items,
                prescriptions = prescriptions
            )
        } else {
            null
        }

        return profile to record
    }

    fun parseSunScorePage(html: String): Triple<TyglRunPlan?, List<TyglExerciseColumn>, List<TyglExerciseTerm>> {
        val doc = Ksoup.parse(html)

        val runPlanTable = doc.selectFirst("#runPlanContent table")
        val runPlan = runPlanTable?.let { tbl ->
            val map = extractKeyValues(tbl)
            if (map.isEmpty()) null
            else TyglRunPlan(
                projectCode = map["项目编号"].orEmpty(),
                projectName = map["项目名称"].orEmpty(),
                dateRange = map["日期限制"].orEmpty(),
                timeRange = map["运动时间"].orEmpty(),
                distanceLimit = map["距离限制"].orEmpty(),
                paceLimit = map["配速限制"].orEmpty(),
                weekDays = map["星期范围"].orEmpty()
            )
        }

        var columns: List<TyglExerciseColumn> = emptyList()
        val terms = ArrayList<TyglExerciseTerm>()

        for (table in doc.select("table.table_bg")) {
            val rows = table.select("tr")
            if (rows.isEmpty()) continue
            val headerCells = rows[0].select("td, th").map { cleanText(it.text()) }
            if (headerCells.size >= 3 && headerCells[0] == "学年" && headerCells[1] == "学期") {
                columns = (2 until headerCells.size).map { idx ->
                    val rawHeader = headerCells[idx]
                    val code = CODE_IN_PARENS_REGEX.find(rawHeader)?.groupValues?.get(1) ?: "col_$idx"
                    val label = rawHeader.replace("/次", "").trim()
                    TyglExerciseColumn(code = code, label = label)
                }

                for (rIdx in 1 until rows.size) {
                    val cells = rows[rIdx].select("td, th").map { cleanText(it.text()) }
                    if (cells.size < 2) continue
                    val year = cells[0]
                    val term = cells[1]
                    if (year.isBlank() || year.contains("暂无")) continue
                    val counts = LinkedHashMap<String, String>()
                    for (cIdx in columns.indices) {
                        val rawCell = cells.getOrNull(2 + cIdx).orEmpty()
                        val cleaned = rawCell.replace(VIEW_DETAIL_REGEX, "").trim()
                        counts[columns[cIdx].code] = cleaned
                    }
                    terms.add(
                        TyglExerciseTerm(
                            academicYear = year,
                            term = term,
                            countsByCode = counts
                        )
                    )
                }
                break
            }
        }

        return Triple(runPlan, columns, terms)
    }

    fun parsePeScorePage(html: String): List<TyglPeCourseScore> {
        val doc = Ksoup.parse(html)
        val table = doc.selectFirst("table#searchtermresults") ?: return emptyList()
        val rows = table.select("tr")
        val list = ArrayList<TyglPeCourseScore>()

        for (row in rows) {
            val tds = row.select("td, th")
            if (tds.size < 3) continue
            val year = cleanText(tds[0].text())
            if (year.isBlank() || year == "学年" || year.contains("暂无")) continue
            val term = cleanText(tds[1].text())
            val courseName = cleanText(tds[2].text())

            val cell3 = tds.getOrNull(3)
            val colspan = cell3?.attr("colspan")?.toIntOrNull() ?: 1
            if (colspan >= 4) {
                list.add(
                    TyglPeCourseScore(
                        academicYear = year,
                        term = term,
                        courseName = courseName,
                        specialNote = cleanText(tds.getOrNull(4)?.text().orEmpty()),
                        teacher = cleanText(tds.getOrNull(5)?.text().orEmpty()),
                        statusMessage = cleanText(cell3?.text().orEmpty())
                    )
                )
            } else {
                list.add(
                    TyglPeCourseScore(
                        academicYear = year,
                        term = term,
                        courseName = courseName,
                        actualScore = cleanText(tds.getOrNull(3)?.text().orEmpty()),
                        actualLevel = cleanText(tds.getOrNull(4)?.text().orEmpty()),
                        termScore = cleanText(tds.getOrNull(5)?.text().orEmpty()),
                        termLevel = cleanText(tds.getOrNull(6)?.text().orEmpty()),
                        specialNote = cleanText(tds.getOrNull(7)?.text().orEmpty()),
                        teacher = cleanText(tds.getOrNull(8)?.text().orEmpty())
                    )
                )
            }
        }
        return list
    }

    fun parseExamPage(html: String): TyglExamInfo? {
        val doc = Ksoup.parse(html)
        var currentTerm = ""
        var peClass = ""
        var remainingAttempts: Int? = null
        var bestScore = ""
        var notice = ""
        val records = ArrayList<TyglExamRecord>()

        for (table in doc.select("table.table_bg")) {
            val rows = table.select("tr")
            var historyHeaderRow = -1
            for ((idx, row) in rows.withIndex()) {
                val tds = row.select("td, th")
                val texts = tds.map { cleanText(it.text()) }
                if (texts.isEmpty()) continue
                if (texts.size >= 2 && texts[0] == "序号" && texts[1] == "体育班") {
                    historyHeaderRow = idx
                    continue
                }
                if (historyHeaderRow in 0 until idx) {
                    val rowText = texts.joinToString(" ")
                    if (rowText.contains("暂无考试记录")) continue
                    if (texts.size >= 6) {
                        records.add(
                            TyglExamRecord(
                                rowNo = texts[0],
                                peClass = texts[1],
                                startTime = texts[2],
                                duration = texts[3],
                                score = texts[4],
                                paperId = texts[5]
                            )
                        )
                    }
                    continue
                }

                var i = 0
                while (i + 1 < texts.size) {
                    val k = texts[i]
                    val v = texts[i + 1]
                    when {
                        k == "当前学期" && v.isNotBlank() -> currentTerm = v
                        k == "体育班" && v.isNotBlank() -> peClass = v
                        k == "剩余答题次数" && v.isNotBlank() -> remainingAttempts = v.toIntOrNull()
                        k == "最高考试成绩" && v.isNotBlank() -> bestScore = v
                        k.contains("通知公告") && v.isNotBlank() -> notice = v
                    }
                    i += 2
                }
            }
        }

        val scheduleNotice = cleanText(doc.selectFirst("table#paperContent")?.text().orEmpty())
        if (currentTerm.isBlank() && peClass.isBlank() && remainingAttempts == null &&
            bestScore.isBlank() && notice.isBlank() && scheduleNotice.isBlank() && records.isEmpty()
        ) {
            return null
        }

        return TyglExamInfo(
            currentTerm = currentTerm,
            peClass = peClass,
            remainingAttempts = remainingAttempts,
            bestScore = bestScore,
            notice = notice,
            scheduleNotice = scheduleNotice,
            records = records
        )
    }

    private fun extractKeyValues(table: Element): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (row in table.select("tr")) {
            val cells = row.select("td, th").map { cleanText(it.text()) }
            var i = 0
            while (i + 1 < cells.size) {
                val key = cells[i]
                val value = cells[i + 1]
                if (key.isNotBlank()) {
                    map[key] = value
                }
                i += 2
            }
        }
        return map
    }

    private fun cleanText(raw: String): String =
        raw.replace('\u00A0', ' ').trim().replace(Regex("\\s+"), " ")
}
