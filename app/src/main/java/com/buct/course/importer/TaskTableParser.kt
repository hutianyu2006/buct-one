package com.buct.course.importer

import com.buct.course.model.CustomExam
import com.buct.one.data.model.ImportDiffStatus
import com.buct.one.data.model.ImportedExamDraft
import com.buct.one.data.model.ImportedTaskDraft
import com.buct.one.data.model.LocalTodoItem
import com.buct.one.data.model.TaskImportReport
import com.buct.one.data.model.TheolHomeworkItem
import com.buct.one.data.model.TheolTestItem
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * 班级任务汇总表语义解析器：
 * - 同时兼容「BUCT One 标准模板（v1）」与多 Sheet `.xlsx`（如 线下学习 / U校园AI / 慕课 / 北化在线）
 * - 支持同一 Sheet 内多区块表头（考试表 + 作业表）、课程名空值向上继承、Excel 日期序列号
 * - 自动过滤公式列（剩余时间）、空占位行与底部平台链接行，并做历史导入与北化在线去重标注
 */
object TaskTableParser {

    val STANDARD_TEMPLATE_CSV: String = buildString {
        append('\uFEFF')
        appendLine("类型,课程名称,任务内容,截止时间,提交方式,适用范围,地点,备注,链接")
        appendLine("作业,物理化学(Ⅰ),\"《物理化学教程》p79 T17 T20；p103 T1-T3\",2026-11-24 20:00,企业微信班级群收集表,全体,,注意上传pdf文件和命名格式,")
        appendLine("线下,有机化学实验 B（I）,完善实验报告：环己烯的制备,2026-11-26 08:00,第十二周实验课线下提交,分班,E-402,注意拍照存档,")
        appendLine("测验,体育(Ⅲ),完成理论考试,2026-11-27 16:30,健康云平台,全体,,,https://tygl.buct.edu.cn/login.php")
        appendLine("考试,新视野-英语听说（III）,期末考试,2026-12-16 13:30,,全体,实验楼F-304,,")
    }

    private enum class BlockKind { NONE, TASK, EXAM }

    private data class ColumnMapping(
        val kind: BlockKind,
        val courseCol: Int = -1,
        val contentCol: Int = -1,
        val deadlineCol: Int = -1,
        val typeCol: Int = -1,
        val submitWayCol: Int = -1,
        val scopeCol: Int = -1,
        val locationCol: Int = -1,
        val noteCol: Int = -1,
        val linkCol: Int = -1
    )

    private val urlRegex = Regex("https?://[^\\s\\u4e00-\\u9fa5\"'<>]+")
    private val courseSuffixRegex = Regex("[★●○]+$")
    private val dateFormats = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy.MM.dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/MM/dd"
    )

    fun parse(
        sheets: List<ParsedSheetTable>,
        sourceName: String,
        existingTodos: List<LocalTodoItem> = emptyList(),
        existingTheolHomeworks: List<TheolHomeworkItem> = emptyList(),
        existingTheolTests: List<TheolTestItem> = emptyList(),
        existingExams: List<CustomExam> = emptyList(),
        nowMillis: Long = System.currentTimeMillis()
    ): TaskImportReport {
        val tasks = mutableListOf<ImportedTaskDraft>()
        val exams = mutableListOf<ImportedExamDraft>()
        var skippedRows = 0

        val existingByImportKey = existingTodos
            .filter { !it.deleted && it.importKey.isNotBlank() }
            .associateBy { it.importKey }

        for (sheet in sheets) {
            var mapping = ColumnMapping(BlockKind.NONE)
            var lastCourse = ""
            var rowSeq = 0

            for ((rowIdx, rawRow) in sheet.rows.withIndex()) {
                val row = rawRow.map { it.trim() }
                if (row.all { it.isEmpty() }) {
                    lastCourse = ""
                    continue
                }
                val joined = row.joinToString("")
                if (joined.startsWith("单击可访问") || joined.startsWith("点击可访问")) {
                    skippedRows++
                    continue
                }

                val headerCandidate = detectHeader(row)
                if (headerCandidate != null) {
                    mapping = headerCandidate
                    lastCourse = ""
                    continue
                }

                if (mapping.kind == BlockKind.NONE) continue

                val rawCourse = row.getOrNull(mapping.courseCol).orEmpty()
                val resolvedCourse = rawCourse.ifBlank { lastCourse }
                if (rawCourse.isNotBlank()) lastCourse = rawCourse

                val contentRaw = row.getOrNull(mapping.contentCol).orEmpty()
                val rawDeadline = row.getOrNull(mapping.deadlineCol).orEmpty()
                val rawType = row.getOrNull(mapping.typeCol).orEmpty()
                val rawSubmitWay = row.getOrNull(mapping.submitWayCol).orEmpty()
                val rawScope = row.getOrNull(mapping.scopeCol).orEmpty()
                val rawLocation = row.getOrNull(mapping.locationCol).orEmpty()
                val rawNote = row.getOrNull(mapping.noteCol).orEmpty()
                val rawLink = row.getOrNull(mapping.linkCol).orEmpty()

                val cellLink = (0 until row.size)
                    .firstNotNullOfOrNull { col -> sheet.hyperlinks[rowIdx to col] }
                    .orEmpty()
                val extractedUrl = rawLink.ifBlank { cellLink }.ifBlank {
                    urlRegex.find(rawNote)?.value.orEmpty()
                }
                val cleanNote = if (rawNote.trim() == extractedUrl) "" else rawNote.trim()

                val cleanCourse = cleanCourseName(resolvedCourse)
                val isExamRow = mapping.kind == BlockKind.EXAM || rawType.contains("考试")

                if (isExamRow) {
                    val startAt = parseDateTimeMillis(rawDeadline)
                        ?: findFallbackSerialMillis(row, mapping.deadlineCol)
                    if (cleanCourse.isBlank() || startAt == null) {
                        skippedRows++
                        continue
                    }
                    val examTitle = contentRaw.ifBlank { "考试" }.trim()
                    val duplicateExam = existingExams.any { !it.hidden && isSameExam(it, cleanCourse, startAt) }
                    val expired = startAt < nowMillis
                    val status = when {
                        duplicateExam -> ImportDiffStatus.ALREADY_IMPORTED
                        expired -> ImportDiffStatus.EXPIRED
                        else -> ImportDiffStatus.NEW
                    }
                    exams += ImportedExamDraft(
                        tempId = "exam_${sheet.name}_${rowIdx}_${rowSeq++}",
                        sheetName = sheet.name,
                        courseName = resolvedCourse,
                        cleanCourseName = cleanCourse,
                        title = if (examTitle == "考试") "$cleanCourse 考试" else "$cleanCourse · $examTitle",
                        startAt = startAt,
                        timeText = formatMillis(startAt),
                        location = rawLocation,
                        note = cleanNote,
                        diffStatus = status,
                        selectedByDefault = status == ImportDiffStatus.NEW
                    )
                    continue
                }

                // 任务行：无任务描述（如占位课）则跳过
                if (contentRaw.isBlank()) {
                    skippedRows++
                    continue
                }

                val deadlineMillis = parseDateTimeMillis(rawDeadline)
                val submitWay = rawSubmitWay.ifBlank { inferSubmitWayFromSheet(sheet.name) }
                val normalizedTitle = normalizeTitle(contentRaw)
                val importKey = computeImportKey(cleanCourse, normalizedTitle)

                val existingLocal = existingByImportKey[importKey]
                val isTheolPlatform = submitWay.contains("北化在线") || sheet.name.contains("北化在线")
                val matchesTheol = existingTheolHomeworks.any {
                    val theolDeadline = it.deadlineMillis
                    isCourseMatched(cleanCourse, it.courseName) &&
                        (theolDeadline == null || deadlineMillis == null ||
                            abs(theolDeadline - deadlineMillis) <= 48 * 3600_000L)
                } || existingTheolTests.any {
                    val theolEndTime = it.endTimeMillis
                    isCourseMatched(cleanCourse, it.courseName) &&
                        (theolEndTime == null || deadlineMillis == null ||
                            abs(theolEndTime - deadlineMillis) <= 48 * 3600_000L)
                }

                val expired = deadlineMillis != null && deadlineMillis < nowMillis
                val hasChanged = existingLocal != null && (
                    existingLocal.deadlineMillis != deadlineMillis ||
                        existingLocal.submitWay != submitWay ||
                        (cleanNote.isNotBlank() && !existingLocal.note.contains(cleanNote))
                    )

                val diffStatus = when {
                    existingLocal != null && hasChanged -> ImportDiffStatus.UPDATE_AVAILABLE
                    existingLocal != null -> ImportDiffStatus.ALREADY_IMPORTED
                    isTheolPlatform || matchesTheol -> ImportDiffStatus.THEOL_DUPLICATE
                    expired -> ImportDiffStatus.EXPIRED
                    else -> ImportDiffStatus.NEW
                }

                val warnings = buildList {
                    val scopeVal = rawScope.trim()
                    if (scopeVal.isNotEmpty() && scopeVal != "全体") {
                        add("适用: $scopeVal")
                    } else if (resolvedCourse.contains("○") || cleanCourse.contains("实验")) {
                        add("分班任务请确认")
                    }
                }

                val taskType = when {
                    rawType.isNotBlank() -> rawType
                    contentRaw.contains("测试") || contentRaw.contains("测验") -> "测验"
                    submitWay.contains("线下") || rawLocation.isNotBlank() -> "线下"
                    else -> "作业"
                }

                tasks += ImportedTaskDraft(
                    tempId = "task_${sheet.name}_${rowIdx}_${rowSeq++}",
                    sheetName = sheet.name,
                    courseName = resolvedCourse,
                    cleanCourseName = cleanCourse,
                    title = normalizedTitle,
                    deadlineMillis = deadlineMillis,
                    deadlineText = deadlineMillis?.let(::formatMillis) ?: rawDeadline,
                    taskType = taskType,
                    submitWay = submitWay,
                    scope = rawScope.ifBlank { "全体" },
                    location = rawLocation,
                    note = cleanNote,
                    linkUrl = extractedUrl,
                    importKey = importKey,
                    diffStatus = diffStatus,
                    selectedByDefault = diffStatus == ImportDiffStatus.NEW || diffStatus == ImportDiffStatus.UPDATE_AVAILABLE,
                    warnings = warnings
                )
            }
        }

        return TaskImportReport(
            sourceName = sourceName,
            tasks = tasks,
            exams = exams,
            skippedRows = skippedRows
        )
    }

    private fun detectHeader(row: List<String>): ColumnMapping? {
        var courseCol = -1
        var contentCol = -1
        var deadlineCol = -1
        var typeCol = -1
        var submitWayCol = -1
        var scopeCol = -1
        var locationCol = -1
        var noteCol = -1
        var linkCol = -1
        var hasExamHeader = false

        row.forEachIndexed { idx, cell ->
            val h = cell.replace(" ", "").replace("\n", "")
            when {
                h in setOf("课程名称", "课程名", "课程") -> courseCol = idx
                h in setOf("任务描述", "任务内容", "作业内容", "任务") -> contentCol = idx
                h in setOf("考试时间") -> {
                    deadlineCol = idx
                    hasExamHeader = true
                }
                h in setOf("截止日期", "截止时间", "DDL", "ddl") -> deadlineCol = idx
                h in setOf("类型", "任务类型") -> typeCol = idx
                h in setOf("提交方式", "平台", "提交渠道") -> submitWayCol = idx
                h in setOf("适用范围", "范围", "班级") -> scopeCol = idx
                h in setOf("考试地点", "地点", "考场", "教室") -> {
                    locationCol = idx
                    if (h == "考试地点" || h == "考场") hasExamHeader = true
                }
                h in setOf("备注", "说明", "注意事项") -> noteCol = idx
                h in setOf("链接", "网址", "URL", "url") -> linkCol = idx
            }
        }

        if (hasExamHeader && courseCol >= 0 && deadlineCol >= 0) {
            return ColumnMapping(
                kind = BlockKind.EXAM,
                courseCol = courseCol,
                contentCol = contentCol,
                deadlineCol = deadlineCol,
                typeCol = typeCol,
                locationCol = locationCol,
                noteCol = noteCol
            )
        }
        if (courseCol >= 0 && contentCol >= 0 && deadlineCol >= 0) {
            return ColumnMapping(
                kind = BlockKind.TASK,
                courseCol = courseCol,
                contentCol = contentCol,
                deadlineCol = deadlineCol,
                typeCol = typeCol,
                submitWayCol = submitWayCol,
                scopeCol = scopeCol,
                locationCol = locationCol,
                noteCol = noteCol,
                linkCol = linkCol
            )
        }
        return null
    }

    private fun inferSubmitWayFromSheet(sheetName: String): String {
        val s = sheetName.trim()
        return when {
            s.contains("U校园", ignoreCase = true) -> "U校园AI"
            s.contains("慕课") -> "慕课"
            s.contains("北化在线") -> "北化在线"
            s.contains("雨课堂") -> "雨课堂"
            else -> ""
        }
    }

    private fun findFallbackSerialMillis(row: List<String>, skipCol: Int): Long? {
        for (i in row.indices) {
            if (i == skipCol) continue
            val d = row[i].toDoubleOrNull() ?: continue
            val parsed = XlsxSheetReader.parseExcelSerialToMillis(d)
            if (parsed != null) return parsed
        }
        return null
    }

    internal fun cleanCourseName(raw: String): String =
        raw.trim().replace(courseSuffixRegex, "").trim()

    private fun normalizeTitle(raw: String): String =
        raw.trim()
            .replace("\r\n", "\n")
            .lines()
            .map { it.trim().replace(Regex("\\s{2,}"), " ") }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

    private fun computeImportKey(cleanCourse: String, title: String): String {
        val base = "${normalizeMatchKey(cleanCourse)}|${normalizeMatchKey(title)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(base.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun normalizeMatchKey(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace('（', '(')
            .replace('）', ')')
            .replace("Ⅰ", "I")
            .replace("Ⅱ", "II")
            .replace("Ⅲ", "III")
            .replace(Regex("[\\s·,，。:：;；!！?？]+"), "")

    private fun isCourseMatched(a: String, b: String): Boolean {
        val na = normalizeMatchKey(cleanCourseName(a))
        val nb = normalizeMatchKey(cleanCourseName(b))
        if (na.isEmpty() || nb.isEmpty()) return false
        return na == nb || na.contains(nb) || nb.contains(na)
    }

    private fun isSameExam(existing: CustomExam, cleanCourse: String, startAt: Long): Boolean {
        val sameTime = abs(existing.startAt - startAt) <= 5 * 60_000L
        if (!sameTime) return false
        return isCourseMatched(existing.course.ifBlank { existing.title }, cleanCourse)
    }

    internal fun parseDateTimeMillis(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        trimmed.toDoubleOrNull()?.let { serial ->
            XlsxSheetReader.parseExcelSerialToMillis(serial)?.let { return it }
        }

        // 兼容 "2026/12/16 13:30-**:**" 或 "2026/12/16 13:30-15:30"
        val cleaned = trimmed
            .replace("：", ":")
            .substringBefore('-')
            .let { candidate ->
                // 如果只截断了日期里的 "-"（如 2026-11-22），则保留完整串，只截断时间段里的 "-"
                val dashParts = trimmed.split('-')
                if (dashParts.size >= 3 && trimmed.contains(':')) {
                    // 形如 2026-11-22 23:59:00 或 2026-12-16 13:30-15:30
                    val timeDashIdx = trimmed.indexOf('-', startIndex = trimmed.indexOf(':').coerceAtLeast(0))
                    if (timeDashIdx > 0) trimmed.substring(0, timeDashIdx).trim() else trimmed
                } else {
                    candidate.trim()
                }
            }

        for (pattern in dateFormats) {
            val sdf = SimpleDateFormat(pattern, Locale.CHINA).apply { isLenient = false }
            val parsed = runCatching { sdf.parse(cleaned) }.getOrNull() ?: continue
            if (!pattern.contains("HH")) {
                val cal = Calendar.getInstance().apply {
                    time = parsed
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                return cal.timeInMillis
            }
            return parsed.time
        }
        return null
    }

    private fun formatMillis(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(millis)
}
