package com.buct.one.data.store

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.Provenance
import com.buct.one.data.model.RefreshState
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
import com.buct.one.data.model.TyglSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 体测与体育管理平台（tygl.buct.edu.cn）本地快照存储。
 *
 * 严格遵循数据层四条铁律：
 * 1. 失败永不产生新 data
 * 2. NEVER != EMPTY_CONFIRMED
 * 3. parserVersion 只增不减
 * 4. capturedAt 单调不减
 */
class TyglStore(context: Context) {

    private val baseDir: File = File(context.applicationContext.filesDir, "campus/default").apply {
        if (!exists()) mkdirs()
    }

    private val snapshotFile = File(baseDir, "tygl.snapshot.v1.json")

    @Synchronized
    fun read(currentParserVersion: Int): DomainSlice<TyglSnapshot> {
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
            val effectiveState = if (snapshot != null && savedParserVersion < currentParserVersion) {
                RefreshState.STALE
            } else {
                savedState
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
        snapshot: TyglSnapshot,
        parserVersion: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): DomainSlice<TyglSnapshot> {
        val prev = read(parserVersion)
        val monotonicTime = max(nowMillis, prev.provenance.capturedAt)
        val isEmpty = snapshot.fitnessRecords.isEmpty() &&
            snapshot.exerciseTerms.isEmpty() &&
            snapshot.peCourses.isEmpty() &&
            snapshot.runPlan == null &&
            snapshot.examInfo == null
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
    fun commitFailure(errorMessage: String, parserVersion: Int): DomainSlice<TyglSnapshot> {
        val prev = read(parserVersion)
        val nextState = if (prev.data != null) RefreshState.FAILED_FALLBACK else RefreshState.NEVER
        val next = DomainSlice(
            data = prev.data,
            provenance = prev.provenance.copy(
                parserVersion = max(parserVersion, prev.provenance.parserVersion),
                refreshState = nextState
            ),
            lastError = errorMessage.ifBlank { "体测系统同步失败" }
        )
        writeSlice(next)
        return next
    }

    private fun writeSlice(slice: DomainSlice<TyglSnapshot>) {
        val root = JSONObject().apply {
            put(
                "provenance",
                JSONObject().apply {
                    put("source", slice.provenance.source)
                    put("capturedAt", slice.provenance.capturedAt)
                    put("parserVersion", slice.provenance.parserVersion)
                    put("complete", slice.provenance.complete)
                    if (slice.provenance.incompleteReason != null) {
                        put("incompleteReason", slice.provenance.incompleteReason)
                    }
                    put("refreshState", slice.provenance.refreshState.name)
                }
            )
            slice.data?.let { put("data", serializeSnapshot(it)) }
            if (slice.lastError != null) {
                put("lastError", slice.lastError)
            }
        }
        val tmp = File(baseDir, "tygl.snapshot.v1.json.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(root.toString().toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        if (!tmp.renameTo(snapshotFile)) {
            snapshotFile.delete()
            tmp.renameTo(snapshotFile)
        }
    }

    private fun serializeSnapshot(s: TyglSnapshot): JSONObject = JSONObject().apply {
        put(
            "profile",
            JSONObject().apply {
                put("studentName", s.profile.studentName)
                put("studentId", s.profile.studentId)
                put("gender", s.profile.gender)
                put("gradeName", s.profile.gradeName)
                put("className", s.profile.className)
                put("collegeName", s.profile.collegeName)
                put("isTested", s.profile.isTested)
                put("specialNote", s.profile.specialNote)
            }
        )
        put(
            "fitnessRecords",
            JSONArray().apply {
                for (r in s.fitnessRecords) {
                    put(
                        JSONObject().apply {
                            put("yearKey", r.yearKey)
                            put("yearLabel", r.yearLabel)
                            put("testYear", r.testYear)
                            put("updatedAt", r.updatedAt)
                            putNullableDouble(this, "totalScore", r.totalScore)
                            put("totalScoreText", r.totalScoreText)
                            put("level", r.level)
                            put("gradeName", r.gradeName)
                            put("heightText", r.heightText)
                            put("weightText", r.weightText)
                            put("bonusScoreText", r.bonusScoreText)
                            put("bonusReason", r.bonusReason)
                            put(
                                "items",
                                JSONArray().apply {
                                    for (it in r.items) {
                                        put(
                                            JSONObject().apply {
                                                put("name", it.name)
                                                put("rawResult", it.rawResult)
                                                putNullableDouble(this, "score", it.score)
                                                put("scoreText", it.scoreText)
                                                put("level", it.level)
                                            }
                                        )
                                    }
                                }
                            )
                            put(
                                "prescriptions",
                                JSONArray().apply {
                                    for (p in r.prescriptions) {
                                        put(
                                            JSONObject().apply {
                                                put("rowNo", p.rowNo)
                                                put("diagnosis", p.diagnosis)
                                                put("prescription", p.prescription)
                                                put("note", p.note)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    )
                }
            }
        )
        s.runPlan?.let { rp ->
            put(
                "runPlan",
                JSONObject().apply {
                    put("projectCode", rp.projectCode)
                    put("projectName", rp.projectName)
                    put("dateRange", rp.dateRange)
                    put("timeRange", rp.timeRange)
                    put("distanceLimit", rp.distanceLimit)
                    put("paceLimit", rp.paceLimit)
                    put("weekDays", rp.weekDays)
                }
            )
        }
        put(
            "exerciseColumns",
            JSONArray().apply {
                for (c in s.exerciseColumns) {
                    put(
                        JSONObject().apply {
                            put("code", c.code)
                            put("label", c.label)
                        }
                    )
                }
            }
        )
        put(
            "exerciseTerms",
            JSONArray().apply {
                for (t in s.exerciseTerms) {
                    put(
                        JSONObject().apply {
                            put("academicYear", t.academicYear)
                            put("term", t.term)
                            put(
                                "countsByCode",
                                JSONObject().apply {
                                    for ((k, v) in t.countsByCode) {
                                        put(k, v)
                                    }
                                }
                            )
                        }
                    )
                }
            }
        )
        put(
            "peCourses",
            JSONArray().apply {
                for (c in s.peCourses) {
                    put(
                        JSONObject().apply {
                            put("academicYear", c.academicYear)
                            put("term", c.term)
                            put("courseName", c.courseName)
                            put("actualScore", c.actualScore)
                            put("actualLevel", c.actualLevel)
                            put("termScore", c.termScore)
                            put("termLevel", c.termLevel)
                            put("specialNote", c.specialNote)
                            put("teacher", c.teacher)
                            put("statusMessage", c.statusMessage)
                        }
                    )
                }
            }
        )
        s.examInfo?.let { ex ->
            put(
                "examInfo",
                JSONObject().apply {
                    put("currentTerm", ex.currentTerm)
                    put("peClass", ex.peClass)
                    if (ex.remainingAttempts != null) {
                        put("remainingAttempts", ex.remainingAttempts)
                    } else {
                        put("remainingAttempts", JSONObject.NULL)
                    }
                    put("bestScore", ex.bestScore)
                    put("notice", ex.notice)
                    put("scheduleNotice", ex.scheduleNotice)
                    put(
                        "records",
                        JSONArray().apply {
                            for (rec in ex.records) {
                                put(
                                    JSONObject().apply {
                                        put("rowNo", rec.rowNo)
                                        put("peClass", rec.peClass)
                                        put("startTime", rec.startTime)
                                        put("duration", rec.duration)
                                        put("score", rec.score)
                                        put("paperId", rec.paperId)
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }
    }

    private fun deserializeSnapshot(o: JSONObject): TyglSnapshot {
        val profObj = o.optJSONObject("profile")
        val profile = if (profObj == null) TyglProfile() else TyglProfile(
            studentName = profObj.optString("studentName"),
            studentId = profObj.optString("studentId"),
            gender = profObj.optString("gender"),
            gradeName = profObj.optString("gradeName"),
            className = profObj.optString("className"),
            collegeName = profObj.optString("collegeName"),
            isTested = profObj.optString("isTested"),
            specialNote = profObj.optString("specialNote")
        )

        val records = ArrayList<TyglFitnessRecord>()
        val recArr = o.optJSONArray("fitnessRecords")
        if (recArr != null) {
            for (i in 0 until recArr.length()) {
                val r = recArr.optJSONObject(i) ?: continue
                val items = ArrayList<TyglFitnessItem>()
                val itemsArr = r.optJSONArray("items")
                if (itemsArr != null) {
                    for (j in 0 until itemsArr.length()) {
                        val it = itemsArr.optJSONObject(j) ?: continue
                        items.add(
                            TyglFitnessItem(
                                name = it.optString("name"),
                                rawResult = it.optString("rawResult"),
                                score = optNullableDouble(it, "score"),
                                scoreText = it.optString("scoreText"),
                                level = it.optString("level")
                            )
                        )
                    }
                }
                val prescriptions = ArrayList<TyglPrescription>()
                val presArr = r.optJSONArray("prescriptions")
                if (presArr != null) {
                    for (j in 0 until presArr.length()) {
                        val p = presArr.optJSONObject(j) ?: continue
                        prescriptions.add(
                            TyglPrescription(
                                rowNo = p.optString("rowNo"),
                                diagnosis = p.optString("diagnosis"),
                                prescription = p.optString("prescription"),
                                note = p.optString("note")
                            )
                        )
                    }
                }
                records.add(
                    TyglFitnessRecord(
                        yearKey = r.optString("yearKey"),
                        yearLabel = r.optString("yearLabel"),
                        testYear = r.optString("testYear"),
                        updatedAt = r.optString("updatedAt"),
                        totalScore = optNullableDouble(r, "totalScore"),
                        totalScoreText = r.optString("totalScoreText"),
                        level = r.optString("level"),
                        gradeName = r.optString("gradeName"),
                        heightText = r.optString("heightText"),
                        weightText = r.optString("weightText"),
                        bonusScoreText = r.optString("bonusScoreText"),
                        bonusReason = r.optString("bonusReason"),
                        items = items,
                        prescriptions = prescriptions
                    )
                )
            }
        }

        val rpObj = o.optJSONObject("runPlan")
        val runPlan = rpObj?.let {
            TyglRunPlan(
                projectCode = it.optString("projectCode"),
                projectName = it.optString("projectName"),
                dateRange = it.optString("dateRange"),
                timeRange = it.optString("timeRange"),
                distanceLimit = it.optString("distanceLimit"),
                paceLimit = it.optString("paceLimit"),
                weekDays = it.optString("weekDays")
            )
        }

        val columns = ArrayList<TyglExerciseColumn>()
        val colArr = o.optJSONArray("exerciseColumns")
        if (colArr != null) {
            for (i in 0 until colArr.length()) {
                val c = colArr.optJSONObject(i) ?: continue
                columns.add(
                    TyglExerciseColumn(
                        code = c.optString("code"),
                        label = c.optString("label")
                    )
                )
            }
        }

        val terms = ArrayList<TyglExerciseTerm>()
        val termArr = o.optJSONArray("exerciseTerms")
        if (termArr != null) {
            for (i in 0 until termArr.length()) {
                val t = termArr.optJSONObject(i) ?: continue
                val map = LinkedHashMap<String, String>()
                val countsObj = t.optJSONObject("countsByCode")
                if (countsObj != null) {
                    val keys = countsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k] = countsObj.optString(k)
                    }
                }
                terms.add(
                    TyglExerciseTerm(
                        academicYear = t.optString("academicYear"),
                        term = t.optString("term"),
                        countsByCode = map
                    )
                )
            }
        }

        val peCourses = ArrayList<TyglPeCourseScore>()
        val peArr = o.optJSONArray("peCourses")
        if (peArr != null) {
            for (i in 0 until peArr.length()) {
                val c = peArr.optJSONObject(i) ?: continue
                peCourses.add(
                    TyglPeCourseScore(
                        academicYear = c.optString("academicYear"),
                        term = c.optString("term"),
                        courseName = c.optString("courseName"),
                        actualScore = c.optString("actualScore"),
                        actualLevel = c.optString("actualLevel"),
                        termScore = c.optString("termScore"),
                        termLevel = c.optString("termLevel"),
                        specialNote = c.optString("specialNote"),
                        teacher = c.optString("teacher"),
                        statusMessage = c.optString("statusMessage")
                    )
                )
            }
        }

        val exObj = o.optJSONObject("examInfo")
        val examInfo = exObj?.let { ex ->
            val exRecs = ArrayList<TyglExamRecord>()
            val exArr = ex.optJSONArray("records")
            if (exArr != null) {
                for (i in 0 until exArr.length()) {
                    val rec = exArr.optJSONObject(i) ?: continue
                    exRecs.add(
                        TyglExamRecord(
                            rowNo = rec.optString("rowNo"),
                            peClass = rec.optString("peClass"),
                            startTime = rec.optString("startTime"),
                            duration = rec.optString("duration"),
                            score = rec.optString("score"),
                            paperId = rec.optString("paperId")
                        )
                    )
                }
            }
            val attempts = if (ex.isNull("remainingAttempts")) null else ex.optInt("remainingAttempts")
            TyglExamInfo(
                currentTerm = ex.optString("currentTerm"),
                peClass = ex.optString("peClass"),
                remainingAttempts = attempts,
                bestScore = ex.optString("bestScore"),
                notice = ex.optString("notice"),
                scheduleNotice = ex.optString("scheduleNotice"),
                records = exRecs
            )
        }

        return TyglSnapshot(
            profile = profile,
            fitnessRecords = records,
            runPlan = runPlan,
            exerciseColumns = columns,
            exerciseTerms = terms,
            peCourses = peCourses,
            examInfo = examInfo
        )
    }

    private fun putNullableDouble(obj: JSONObject, key: String, value: Double?) {
        if (value == null || value.isNaN()) {
            obj.put(key, JSONObject.NULL)
        } else {
            obj.put(key, value)
        }
    }

    private fun optNullableDouble(obj: JSONObject, key: String): Double? {
        if (obj.isNull(key)) return null
        val v = obj.optDouble(key, Double.NaN)
        return if (v.isNaN()) null else v
    }

    companion object {
        private const val SOURCE_ID = "tygl.buct.edu.cn"
    }
}
