package com.buct.one.data.source.sjcx

import com.buct.one.data.model.SjcxCreditRecordItem
import com.buct.one.data.model.SjcxExperimentReportItem
import com.buct.one.data.model.SjcxExperimentScheduleItem
import com.buct.one.data.model.SjcxExperimentScoreItem
import com.buct.one.data.model.SjcxLectureBatchItem
import com.buct.one.data.model.SjcxMyLectureItem
import com.buct.one.data.json.JSONArray
import com.buct.one.data.json.JSONObject

/**
 * 实践教学综合服务平台（sjcx.buct.edu.cn）响应解析器。
 * 遵循数据层铁律 3：改动解析逻辑必须递增 PARSER_VERSION。
 */
object SjcxParser {
    const val PARSER_VERSION: Int = 3

    data class CasLoginInfo(
        val rsaPublicKeyBase64: String,
        val yearterm: String
    )

    private fun JSONObject.optCleanString(key: String, fallback: String = ""): String {
        if (isNull(key)) return fallback
        val v = optString(key, fallback).trim()
        return if (v.equals("null", ignoreCase = true)) fallback else v
    }

    fun parseCasLoginInfo(rawJson: String): CasLoginInfo {
        val root = JSONObject(rawJson)
        val code = root.optInt("code", -1)
        if (code != 200) {
            throw IllegalStateException(root.optCleanString("msg", "实践教学平台认证失败（code=$code）"))
        }
        val data = root.optJSONObject("data")
            ?: throw IllegalStateException("实践教学平台认证响应缺少 data")
        val pubKey = data.optCleanString("random")
        if (pubKey.isBlank()) {
            throw IllegalStateException("实践教学平台未返回签名公钥")
        }
        return CasLoginInfo(
            rsaPublicKeyBase64 = pubKey,
            yearterm = data.optCleanString("yearterm")
        )
    }

    fun parseCurrentWeekFromProducts(rawJson: String): Int {
        return runCatching {
            val root = JSONObject(rawJson)
            root.optJSONObject("data")?.optInt("week", 1)?.coerceAtLeast(1) ?: 1
        }.getOrDefault(1)
    }

    private fun shouldPreferReportItem(
        candidate: SjcxExperimentReportItem,
        existing: SjcxExperimentReportItem
    ): Boolean {
        if (candidate.isSubmitted != existing.isSubmitted) return candidate.isSubmitted
        val candHasDoc = candidate.uploadedDocName.isNotBlank()
        val existHasDoc = existing.uploadedDocName.isNotBlank()
        if (candHasDoc != existHasDoc) return candHasDoc
        return candidate.state > existing.state
    }

    fun parseExperimentReports(rawJson: String): List<SjcxExperimentReportItem> {
        val root = JSONObject(rawJson)
        val code = root.optInt("code", -1)
        if (code != 200) {
            throw IllegalStateException(root.optCleanString("msg", "获取实验报告失败"))
        }
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val deduped = LinkedHashMap<String, SjcxExperimentReportItem>()
        for (i in 0 until list.length()) {
            val courseObj = list.optJSONObject(i) ?: continue
            val courseId = courseObj.optCleanString("course_id")
            val courseCode = courseObj.optCleanString("course_code", courseId)
            val courseName = courseObj.optCleanString("course_name", "未命名实验课")
            val exps = courseObj.optJSONArray("course_exp") ?: continue
            for (j in 0 until exps.length()) {
                val exp = exps.optJSONObject(j) ?: continue
                val expId = exp.optCleanString("exp_id")
                val expName = exp.optCleanString("exp_name")
                if (expName.isBlank()) continue
                val item = SjcxExperimentReportItem(
                    courseId = courseId,
                    courseCode = courseCode,
                    courseName = courseName,
                    expId = expId,
                    expCode = exp.optCleanString("exp_code"),
                    expName = expName,
                    stuId = exp.optCleanString("stu_id"),
                    groupId = exp.optCleanString("group_id"),
                    reportId = exp.optCleanString("report_id"),
                    templateName = exp.optCleanString("report"),
                    uploadedDocName = exp.optCleanString("doc_name"),
                    canUpload = exp.optInt("canupload", 0) == 1,
                    state = exp.optInt("state", 1),
                    opinion = exp.optCleanString("opinion"),
                    yearterm = exp.optCleanString("yearterm")
                )
                val dedupKey = "$courseId|$expId|${item.expCode}|$expName|${item.stuId}|${item.groupId}|${item.reportId}|${item.yearterm}"
                val existing = deduped[dedupKey]
                if (existing == null || shouldPreferReportItem(item, existing)) {
                    deduped[dedupKey] = item
                }
            }
        }
        return deduped.values.toList()
    }

    fun parseExperimentSchedules(rawJson: String): List<SjcxExperimentScheduleItem> {
        val root = JSONObject(rawJson)
        val code = root.optInt("code", -1)
        if (code != 200) {
            throw IllegalStateException(root.optCleanString("msg", "获取实验课表失败"))
        }
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val result = ArrayList<SjcxExperimentScheduleItem>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val expName = item.optCleanString("exp_name")
            val courseName = item.optCleanString("course_name")
            if (expName.isBlank() && courseName.isBlank()) continue
            result.add(
                SjcxExperimentScheduleItem(
                    courseName = courseName,
                    expName = expName,
                    roomName = item.optCleanString("room_name"),
                    teacherName = item.optCleanString("tea_name"),
                    week = item.optInt("week", 0),
                    weekDay = item.optInt("week_day", 0),
                    jcText = item.optCleanString("jc"),
                    seatNumber = item.optCleanString("seat_number"),
                    formType = item.optInt("form", 2)
                )
            )
        }
        return result.distinct().sortedWith(compareBy<SjcxExperimentScheduleItem> { it.week }.thenBy { it.weekDay }.thenBy { it.jcText })
    }

    fun parseExperimentScores(rawJson: String): List<SjcxExperimentScoreItem> {
        val root = JSONObject(rawJson)
        val code = root.optInt("code", -1)
        if (code != 200) {
            throw IllegalStateException(root.optCleanString("msg", "获取实验成绩失败"))
        }
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val result = ArrayList<SjcxExperimentScoreItem>()
        for (i in 0 until list.length()) {
            val courseObj = list.optJSONObject(i) ?: continue
            val courseId = courseObj.optCleanString("course_id")
            val courseName = courseObj.optCleanString("course_name", "未命名实验课")
            val exps = courseObj.optJSONArray("course_exp") ?: continue
            for (j in 0 until exps.length()) {
                val exp = exps.optJSONObject(j) ?: continue
                val expName = exp.optCleanString("exp_name")
                if (expName.isBlank()) continue
                val subParts = buildList {
                    for (k in 1..5) {
                        val s = exp.optCleanString("score$k")
                        if (s.isNotBlank()) add("分项$k: $s")
                    }
                }
                result.add(
                    SjcxExperimentScoreItem(
                        courseId = courseId,
                        courseName = courseName,
                        expName = expName,
                        teacherName = exp.optCleanString("tea_name"),
                        week = exp.optInt("week", 0),
                        weekDay = exp.optInt("week_day", 0),
                        jcText = exp.optCleanString("jc"),
                        totalScore = exp.optCleanString("score"),
                        subScoresLabel = subParts.joinToString(" | ")
                    )
                )
            }
        }
        return result.distinct()
    }

    fun parseLectureBatches(rawJson: String): List<SjcxLectureBatchItem> {
        val root = JSONObject(rawJson)
        val code = root.optInt("code", -1)
        if (code != 200) {
            throw IllegalStateException(root.optCleanString("msg", "获取讲座列表失败"))
        }
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val result = ArrayList<SjcxLectureBatchItem>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optCleanString("id")
            val projectName = item.optCleanString("project_name")
            if (id.isBlank() || projectName.isBlank()) continue
            result.add(
                SjcxLectureBatchItem(
                    id = id,
                    projectId = item.optCleanString("project_id"),
                    projectName = projectName,
                    batch = item.optCleanString("batch"),
                    place = item.optCleanString("place"),
                    speaker = item.optCleanString("speaker"),
                    teacherName = item.optCleanString("tea_name"),
                    campus = item.optCleanString("campus"),
                    campusName = item.optCleanString("campus_name"),
                    signupBeginTime = item.optCleanString("project_begin_time"),
                    signupEndTime = item.optCleanString("project_end_time"),
                    lectureBeginTime = item.optCleanString("begin_time"),
                    lectureEndTime = item.optCleanString("end_time"),
                    registrationNum = item.optInt("registration_num", 0),
                    totalNum = item.optInt("total_num", 0),
                    requireReview = item.optInt("reg_review", 0) == 1
                )
            )
        }
        return result.distinctBy { it.id }
    }

    fun parseMyLectures(rawJson: String): List<SjcxMyLectureItem> {
        val root = JSONObject(rawJson)
        val code = root.optInt("code", -1)
        if (code != 200) {
            throw IllegalStateException(root.optCleanString("msg", "获取我的讲座列表失败"))
        }
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val result = ArrayList<SjcxMyLectureItem>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val stuId = item.optCleanString("cre_project_stu_id")
            val projectName = item.optCleanString("project_name")
            if (stuId.isBlank() && projectName.isBlank()) continue
            result.add(
                SjcxMyLectureItem(
                    creProjectStuId = stuId,
                    projectId = item.optCleanString("project_id"),
                    projectName = projectName,
                    batchName = item.optCleanString("batch_name"),
                    place = item.optCleanString("place"),
                    speaker = item.optCleanString("speaker"),
                    teacherName = item.optCleanString("tea_name"),
                    beginTime = item.optCleanString("begin_time"),
                    endTime = item.optCleanString("end_time"),
                    pendingName = item.optCleanString("pending_name"),
                    experience = item.optCleanString("experience"),
                    isRecognized = item.optCleanString("isrec") == "1"
                )
            )
        }
        return result.distinctBy { "${it.creProjectStuId}|${it.projectId}|${it.batchName}|${it.beginTime}" }
    }

    fun parseCreditRecords(rawJson: String): Pair<Double, List<SjcxCreditRecordItem>> {
        val root = JSONObject(rawJson)
        val code = root.optInt("code", -1)
        if (code != 200) {
            throw IllegalStateException(root.optCleanString("msg", "获取创新学分失败"))
        }
        val totalCredit = root.optDouble("totalCredit", 0.0).let { if (it.isNaN()) 0.0 else it }
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val result = ArrayList<SjcxCreditRecordItem>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val projectName = item.optCleanString("project_name")
            if (projectName.isBlank()) continue
            result.add(
                SjcxCreditRecordItem(
                    creStuId = item.optCleanString("cre_stu_id"),
                    projectName = projectName,
                    standardName = item.optCleanString("cre_standard_name"),
                    typeName = item.optCleanString("type_name"),
                    pendingName = item.optCleanString("pending_name"),
                    credit = item.optDouble("credit", 0.0).let { if (it.isNaN()) 0.0 else it },
                    addTime = item.optCleanString("add_time")
                )
            )
        }
        val deduped = result.distinct()
        val computedTotal = if (totalCredit > 0.0) totalCredit else deduped.sumOf { it.credit }
        return computedTotal to deduped
    }
}
