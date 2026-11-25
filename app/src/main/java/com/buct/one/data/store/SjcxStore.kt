package com.buct.one.data.store

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.Provenance
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.SjcxCreditRecordItem
import com.buct.one.data.model.SjcxExperimentReportItem
import com.buct.one.data.model.SjcxExperimentScheduleItem
import com.buct.one.data.model.SjcxExperimentScoreItem
import com.buct.one.data.model.SjcxLectureBatchItem
import com.buct.one.data.model.SjcxMyLectureItem
import com.buct.one.data.model.SjcxSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 实践教学平台（sjcx.buct.edu.cn）本地快照存储。
 *
 * 严格遵循数据层四条铁律：
 * 1. 失败永不产生新 data（失败路径只改 refreshState / lastError，旧 data 原样保留）
 * 2. NEVER != EMPTY_CONFIRMED
 * 3. parserVersion 只增不减
 * 4. capturedAt 单调不减
 */
class SjcxStore(context: Context) {

    private val baseDir: File = File(context.applicationContext.filesDir, "campus/default").apply {
        if (!exists()) mkdirs()
    }

    private val snapshotFile = File(baseDir, "sjcx.snapshot.v1.json")

    @Synchronized
    fun read(currentParserVersion: Int): DomainSlice<SjcxSnapshot> {
        if (!snapshotFile.exists()) {
            return DomainSlice.never(source = SOURCE_ID, parserVersion = currentParserVersion)
        }
        return runCatching {
            val root = JSONObject(snapshotFile.readText(Charsets.UTF_8))
            val provObj = root.getJSONObject("provenance")
            val savedParserVersion = provObj.optInt("parserVersion", 1)
            val savedState = RefreshState.valueOf(
                provObj.optString("refreshState", RefreshState.NEVER.name)
            )
            val dataObj = root.optJSONObject("data")
            val snapshot = dataObj?.let { deserializeSnapshot(it) }

            val effectiveState = when {
                snapshot != null && savedParserVersion < currentParserVersion -> RefreshState.STALE
                else -> savedState
            }

            val provenance = Provenance(
                source = provObj.optString("source", SOURCE_ID),
                capturedAt = provObj.optLong("capturedAt", 0L),
                parserVersion = max(savedParserVersion, currentParserVersion),
                complete = provObj.optBoolean("complete", true),
                incompleteReason = provObj.optString("incompleteReason", "").ifBlank { null },
                refreshState = effectiveState
            )
            DomainSlice(
                data = snapshot,
                provenance = provenance,
                lastError = root.optString("lastError", "").ifBlank { null }
            )
        }.getOrElse {
            DomainSlice.never(source = SOURCE_ID, parserVersion = currentParserVersion)
        }
    }

    @Synchronized
    fun commitSuccess(
        snapshot: SjcxSnapshot,
        parserVersion: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): DomainSlice<SjcxSnapshot> {
        val prev = read(parserVersion)
        val monotonicTime = max(nowMillis, prev.provenance.capturedAt)
        val isEmpty = snapshot.reports.isEmpty() &&
            snapshot.schedules.isEmpty() &&
            snapshot.openLectures.isEmpty() &&
            snapshot.myLectures.isEmpty()
        val state = if (isEmpty) RefreshState.EMPTY_CONFIRMED else RefreshState.FRESH
        val next = DomainSlice(
            data = snapshot,
            provenance = Provenance(
                source = SOURCE_ID,
                capturedAt = monotonicTime,
                parserVersion = max(parserVersion, prev.provenance.parserVersion),
                complete = true,
                incompleteReason = null,
                refreshState = state
            ),
            lastError = null
        )
        writeSlice(next)
        return next
    }

    @Synchronized
    fun commitFailure(
        errorMessage: String,
        parserVersion: Int
    ): DomainSlice<SjcxSnapshot> {
        val prev = read(parserVersion)
        val nextState = if (prev.data != null) RefreshState.FAILED_FALLBACK else RefreshState.NEVER
        val next = DomainSlice(
            data = prev.data,
            provenance = prev.provenance.copy(
                parserVersion = max(parserVersion, prev.provenance.parserVersion),
                refreshState = nextState
            ),
            lastError = errorMessage
        )
        writeSlice(next)
        return next
    }

    private fun writeSlice(slice: DomainSlice<SjcxSnapshot>) {
        val root = JSONObject().apply {
            put(
                "provenance",
                JSONObject().apply {
                    put("source", slice.provenance.source)
                    put("capturedAt", slice.provenance.capturedAt)
                    put("parserVersion", slice.provenance.parserVersion)
                    put("complete", slice.provenance.complete)
                    put("incompleteReason", slice.provenance.incompleteReason ?: "")
                    put("refreshState", slice.provenance.refreshState.name)
                }
            )
            put("lastError", slice.lastError ?: "")
            slice.data?.let { put("data", serializeSnapshot(it)) }
        }
        atomicWrite(snapshotFile, root.toString())
    }

    private fun serializeSnapshot(s: SjcxSnapshot): JSONObject = JSONObject().apply {
        put("yearterm", s.yearterm)
        put("currentWeek", s.currentWeek)
        put("totalCredit", s.totalCredit)
        put("noSignCount", s.noSignCount)
        put(
            "reports",
            JSONArray().apply {
                s.reports.forEach { r ->
                    put(
                        JSONObject().apply {
                            put("courseId", r.courseId)
                            put("courseCode", r.courseCode)
                            put("courseName", r.courseName)
                            put("expId", r.expId)
                            put("expCode", r.expCode)
                            put("expName", r.expName)
                            put("stuId", r.stuId)
                            put("groupId", r.groupId)
                            put("reportId", r.reportId)
                            put("templateName", r.templateName)
                            put("uploadedDocName", r.uploadedDocName)
                            put("canUpload", r.canUpload)
                            put("state", r.state)
                            put("opinion", r.opinion)
                            put("yearterm", r.yearterm)
                        }
                    )
                }
            }
        )
        put(
            "schedules",
            JSONArray().apply {
                s.schedules.forEach { item ->
                    put(
                        JSONObject().apply {
                            put("courseName", item.courseName)
                            put("expName", item.expName)
                            put("roomName", item.roomName)
                            put("teacherName", item.teacherName)
                            put("week", item.week)
                            put("weekDay", item.weekDay)
                            put("jcText", item.jcText)
                            put("seatNumber", item.seatNumber)
                            put("formType", item.formType)
                        }
                    )
                }
            }
        )
        put(
            "scores",
            JSONArray().apply {
                s.scores.forEach { sc ->
                    put(
                        JSONObject().apply {
                            put("courseId", sc.courseId)
                            put("courseName", sc.courseName)
                            put("expName", sc.expName)
                            put("teacherName", sc.teacherName)
                            put("week", sc.week)
                            put("weekDay", sc.weekDay)
                            put("jcText", sc.jcText)
                            put("totalScore", sc.totalScore)
                            put("subScoresLabel", sc.subScoresLabel)
                        }
                    )
                }
            }
        )
        put("openLectures", serializeLectures(s.openLectures))
        put("endedLectures", serializeLectures(s.endedLectures))
        put(
            "myLectures",
            JSONArray().apply {
                s.myLectures.forEach { m ->
                    put(
                        JSONObject().apply {
                            put("creProjectStuId", m.creProjectStuId)
                            put("projectId", m.projectId)
                            put("projectName", m.projectName)
                            put("batchName", m.batchName)
                            put("place", m.place)
                            put("speaker", m.speaker)
                            put("teacherName", m.teacherName)
                            put("beginTime", m.beginTime)
                            put("endTime", m.endTime)
                            put("pendingName", m.pendingName)
                            put("experience", m.experience)
                            put("isRecognized", m.isRecognized)
                        }
                    )
                }
            }
        )
        put(
            "creditRecords",
            JSONArray().apply {
                s.creditRecords.forEach { c ->
                    put(
                        JSONObject().apply {
                            put("creStuId", c.creStuId)
                            put("projectName", c.projectName)
                            put("standardName", c.standardName)
                            put("typeName", c.typeName)
                            put("pendingName", c.pendingName)
                            put("credit", c.credit)
                            put("addTime", c.addTime)
                        }
                    )
                }
            }
        )
    }

    private fun serializeLectures(list: List<SjcxLectureBatchItem>): JSONArray = JSONArray().apply {
        list.forEach { l ->
            put(
                JSONObject().apply {
                    put("id", l.id)
                    put("projectId", l.projectId)
                    put("projectName", l.projectName)
                    put("batch", l.batch)
                    put("place", l.place)
                    put("speaker", l.speaker)
                    put("teacherName", l.teacherName)
                    put("campus", l.campus)
                    put("campusName", l.campusName)
                    put("signupBeginTime", l.signupBeginTime)
                    put("signupEndTime", l.signupEndTime)
                    put("lectureBeginTime", l.lectureBeginTime)
                    put("lectureEndTime", l.lectureEndTime)
                    put("registrationNum", l.registrationNum)
                    put("totalNum", l.totalNum)
                    put("requireReview", l.requireReview)
                }
            )
        }
    }

    private fun deserializeSnapshot(obj: JSONObject): SjcxSnapshot {
        val repArr = obj.optJSONArray("reports") ?: JSONArray()
        val reports = ArrayList<SjcxExperimentReportItem>(repArr.length())
        for (i in 0 until repArr.length()) {
            val r = repArr.optJSONObject(i) ?: continue
            reports.add(
                SjcxExperimentReportItem(
                    courseId = r.optString("courseId"),
                    courseCode = r.optString("courseCode"),
                    courseName = r.optString("courseName"),
                    expId = r.optString("expId"),
                    expCode = r.optString("expCode"),
                    expName = r.optString("expName"),
                    stuId = r.optString("stuId"),
                    groupId = r.optString("groupId"),
                    reportId = r.optString("reportId"),
                    templateName = r.optString("templateName"),
                    uploadedDocName = r.optString("uploadedDocName"),
                    canUpload = r.optBoolean("canUpload", false),
                    state = r.optInt("state", 1),
                    opinion = r.optString("opinion"),
                    yearterm = r.optString("yearterm")
                )
            )
        }

        val schArr = obj.optJSONArray("schedules") ?: JSONArray()
        val schedules = ArrayList<SjcxExperimentScheduleItem>(schArr.length())
        for (i in 0 until schArr.length()) {
            val s = schArr.optJSONObject(i) ?: continue
            schedules.add(
                SjcxExperimentScheduleItem(
                    courseName = s.optString("courseName"),
                    expName = s.optString("expName"),
                    roomName = s.optString("roomName"),
                    teacherName = s.optString("teacherName"),
                    week = s.optInt("week", 0),
                    weekDay = s.optInt("weekDay", 0),
                    jcText = s.optString("jcText"),
                    seatNumber = s.optString("seatNumber"),
                    formType = s.optInt("formType", 2)
                )
            )
        }

        val scArr = obj.optJSONArray("scores") ?: JSONArray()
        val scores = ArrayList<SjcxExperimentScoreItem>(scArr.length())
        for (i in 0 until scArr.length()) {
            val sc = scArr.optJSONObject(i) ?: continue
            scores.add(
                SjcxExperimentScoreItem(
                    courseId = sc.optString("courseId"),
                    courseName = sc.optString("courseName"),
                    expName = sc.optString("expName"),
                    teacherName = sc.optString("teacherName"),
                    week = sc.optInt("week", 0),
                    weekDay = sc.optInt("weekDay", 0),
                    jcText = sc.optString("jcText"),
                    totalScore = sc.optString("totalScore"),
                    subScoresLabel = sc.optString("subScoresLabel")
                )
            )
        }

        val openLectures = deserializeLectures(obj.optJSONArray("openLectures"))
        val endedLectures = deserializeLectures(obj.optJSONArray("endedLectures"))

        val myArr = obj.optJSONArray("myLectures") ?: JSONArray()
        val myLectures = ArrayList<SjcxMyLectureItem>(myArr.length())
        for (i in 0 until myArr.length()) {
            val m = myArr.optJSONObject(i) ?: continue
            myLectures.add(
                SjcxMyLectureItem(
                    creProjectStuId = m.optString("creProjectStuId"),
                    projectId = m.optString("projectId"),
                    projectName = m.optString("projectName"),
                    batchName = m.optString("batchName"),
                    place = m.optString("place"),
                    speaker = m.optString("speaker"),
                    teacherName = m.optString("teacherName"),
                    beginTime = m.optString("beginTime"),
                    endTime = m.optString("endTime"),
                    pendingName = m.optString("pendingName"),
                    experience = m.optString("experience"),
                    isRecognized = m.optBoolean("isRecognized", false)
                )
            )
        }

        val crArr = obj.optJSONArray("creditRecords") ?: JSONArray()
        val creditRecords = ArrayList<SjcxCreditRecordItem>(crArr.length())
        for (i in 0 until crArr.length()) {
            val c = crArr.optJSONObject(i) ?: continue
            creditRecords.add(
                SjcxCreditRecordItem(
                    creStuId = c.optString("creStuId"),
                    projectName = c.optString("projectName"),
                    standardName = c.optString("standardName"),
                    typeName = c.optString("typeName"),
                    pendingName = c.optString("pendingName"),
                    credit = c.optDouble("credit", 0.0),
                    addTime = c.optString("addTime")
                )
            )
        }

        return SjcxSnapshot(
            yearterm = obj.optString("yearterm"),
            currentWeek = obj.optInt("currentWeek", 1),
            reports = reports,
            schedules = schedules,
            scores = scores,
            openLectures = openLectures,
            endedLectures = endedLectures,
            myLectures = myLectures,
            creditRecords = creditRecords,
            totalCredit = obj.optDouble("totalCredit", 0.0),
            noSignCount = obj.optInt("noSignCount", 0)
        )
    }

    private fun deserializeLectures(arr: JSONArray?): List<SjcxLectureBatchItem> {
        if (arr == null) return emptyList()
        val list = ArrayList<SjcxLectureBatchItem>(arr.length())
        for (i in 0 until arr.length()) {
            val l = arr.optJSONObject(i) ?: continue
            list.add(
                SjcxLectureBatchItem(
                    id = l.optString("id"),
                    projectId = l.optString("projectId"),
                    projectName = l.optString("projectName"),
                    batch = l.optString("batch"),
                    place = l.optString("place"),
                    speaker = l.optString("speaker"),
                    teacherName = l.optString("teacherName"),
                    campus = l.optString("campus"),
                    campusName = l.optString("campusName"),
                    signupBeginTime = l.optString("signupBeginTime"),
                    signupEndTime = l.optString("signupEndTime"),
                    lectureBeginTime = l.optString("lectureBeginTime"),
                    lectureEndTime = l.optString("lectureEndTime"),
                    registrationNum = l.optInt("registrationNum", 0),
                    totalNum = l.optInt("totalNum", 0),
                    requireReview = l.optBoolean("requireReview", false)
                )
            )
        }
        return list
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        const val SOURCE_ID = "sjcx.buct.edu.cn"
    }
}
