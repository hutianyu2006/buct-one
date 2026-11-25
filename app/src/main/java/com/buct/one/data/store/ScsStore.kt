package com.buct.one.data.store

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.Provenance
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.ScsActivityItem
import com.buct.one.data.model.ScsCategoryDetail
import com.buct.one.data.model.ScsCurrentEvalWindow
import com.buct.one.data.model.ScsEvalDimension
import com.buct.one.data.model.ScsEvalItem
import com.buct.one.data.model.ScsSnapshot
import com.buct.one.data.model.ScsTermScore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 第二课堂（scs.buct.edu.cn）本地快照存储。
 *
 * 严格遵循数据层四条铁律：
 * 1. 失败永不产生新 data
 * 2. NEVER != EMPTY_CONFIRMED
 * 3. parserVersion 只增不减
 * 4. capturedAt 单调不减
 */
class ScsStore(context: Context) {

    private val baseDir: File = File(context.applicationContext.filesDir, "campus/default").apply {
        if (!exists()) mkdirs()
    }

    private val snapshotFile = File(baseDir, "scs.snapshot.v1.json")

    @Synchronized
    fun read(currentParserVersion: Int): DomainSlice<ScsSnapshot> {
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
        snapshot: ScsSnapshot,
        parserVersion: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): DomainSlice<ScsSnapshot> {
        val prev = read(parserVersion)
        val monotonicTime = max(nowMillis, prev.provenance.capturedAt)
        val isEmpty = snapshot.terms.isEmpty() && snapshot.activities.isEmpty()
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
    fun commitFailure(errorMessage: String, parserVersion: Int): DomainSlice<ScsSnapshot> {
        val prev = read(parserVersion)
        val nextState = if (prev.data != null) RefreshState.FAILED_FALLBACK else RefreshState.NEVER
        val next = DomainSlice(
            data = prev.data,
            provenance = prev.provenance.copy(
                parserVersion = max(parserVersion, prev.provenance.parserVersion),
                refreshState = nextState
            ),
            lastError = errorMessage.ifBlank { "第二课堂同步失败" }
        )
        writeSlice(next)
        return next
    }

    private fun writeSlice(slice: DomainSlice<ScsSnapshot>) {
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
        val tmp = File(baseDir, "scs.snapshot.v1.json.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(root.toString().toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        if (!tmp.renameTo(snapshotFile)) {
            snapshotFile.delete()
            tmp.renameTo(snapshotFile)
        }
    }

    private fun serializeSnapshot(s: ScsSnapshot): JSONObject = JSONObject().apply {
        put("studentName", s.studentName)
        put("studentId", s.studentId)
        put("collegeName", s.collegeName)
        put("className", s.className)
        put("gradeName", s.gradeName)
        s.currentWindow?.let { w ->
            put(
                "currentWindow",
                JSONObject().apply {
                    put("yearId", w.yearId)
                    put("termId", w.termId)
                    put("yearName", w.yearName)
                    put("termName", w.termName)
                    put("isOpen", w.isOpen)
                    put("startTime", w.startTime)
                    put("endTime", w.endTime)
                }
            )
        }
        put(
            "dimensions",
            JSONArray().apply {
                for (d in s.dimensions) {
                    put(
                        JSONObject().apply {
                            put("typeId", d.typeId)
                            put("name", d.name)
                            put("totalMax", d.totalMax)
                            put("basicMax", d.basicMax)
                            put("extMax", d.extMax)
                        }
                    )
                }
            }
        )
        put(
            "terms",
            JSONArray().apply {
                for (t in s.terms) {
                    put(
                        JSONObject().apply {
                            put("termId", t.termId)
                            put("yearId", t.yearId)
                            put("yearName", t.yearName)
                            put("termName", t.termName)
                            put("isFullYear", t.isFullYear)
                            put("status", t.status)
                            putNullableDouble(this, "totalScore", t.totalScore)
                            putNullableDouble(this, "moral", t.moral)
                            putNullableDouble(this, "academic", t.academic)
                            putNullableDouble(this, "physical", t.physical)
                            putNullableDouble(this, "aesthetics", t.aesthetics)
                            putNullableDouble(this, "labor", t.labor)
                            put("isOpenSelfEva", t.isOpenSelfEva)
                            put(
                                "details",
                                JSONArray().apply {
                                    for (c in t.details) {
                                        put(
                                            JSONObject().apply {
                                                put("typeId", c.typeId)
                                                put("title", c.title)
                                                putNullableDouble(this, "basicScore", c.basicScore)
                                                putNullableDouble(this, "extScore", c.extScore)
                                                putNullableDouble(this, "totalScore", c.totalScore)
                                                putNullableDouble(this, "maxTotalScore", c.maxTotalScore)
                                                put(
                                                    "items",
                                                    JSONArray().apply {
                                                        for (it in c.items) {
                                                            put(
                                                                JSONObject().apply {
                                                                    put("id", it.id)
                                                                    put("typeId", it.typeId)
                                                                    put("isBasic", it.isBasic)
                                                                    put("shortDesc", it.shortDesc)
                                                                    put("description", it.description)
                                                                    putNullableDouble(this, "score", it.score)
                                                                    put("mark", it.mark)
                                                                    put("teacherComments", it.teacherComments)
                                                                    putNullableDouble(this, "laborHours", it.laborHours)
                                                                    put("stuAble", it.stuAble)
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
                        }
                    )
                }
            }
        )
        put(
            "activities",
            JSONArray().apply {
                for (a in s.activities) {
                    put(
                        JSONObject().apply {
                            put("id", a.id)
                            put("title", a.title)
                            put("categoryName", a.categoryName)
                            put("startTime", a.startTime)
                            put("endTime", a.endTime)
                            put("location", a.location)
                            put("score", a.score)
                            put("status", a.status)
                        }
                    )
                }
            }
        )
    }

    private fun deserializeSnapshot(o: JSONObject): ScsSnapshot {
        val winObj = o.optJSONObject("currentWindow")
        val window = winObj?.let {
            ScsCurrentEvalWindow(
                yearId = it.optInt("yearId", 0),
                termId = it.optInt("termId", 0),
                yearName = it.optString("yearName", ""),
                termName = it.optString("termName", ""),
                isOpen = it.optBoolean("isOpen", false),
                startTime = it.optString("startTime", ""),
                endTime = it.optString("endTime", "")
            )
        }
        val dimArr = o.optJSONArray("dimensions") ?: JSONArray()
        val dims = buildList(dimArr.length()) {
            for (i in 0 until dimArr.length()) {
                val d = dimArr.optJSONObject(i) ?: continue
                add(
                    ScsEvalDimension(
                        typeId = d.optInt("typeId", i + 1),
                        name = d.optString("name", ""),
                        totalMax = d.optDouble("totalMax", 0.0),
                        basicMax = d.optDouble("basicMax", 0.0),
                        extMax = d.optDouble("extMax", 0.0)
                    )
                )
            }
        }
        val termArr = o.optJSONArray("terms") ?: JSONArray()
        val terms = buildList(termArr.length()) {
            for (i in 0 until termArr.length()) {
                val t = termArr.optJSONObject(i) ?: continue
                val detArr = t.optJSONArray("details") ?: JSONArray()
                val details = buildList(detArr.length()) {
                    for (j in 0 until detArr.length()) {
                        val c = detArr.optJSONObject(j) ?: continue
                        val itArr = c.optJSONArray("items") ?: JSONArray()
                        val items = buildList(itArr.length()) {
                            for (k in 0 until itArr.length()) {
                                val it = itArr.optJSONObject(k) ?: continue
                                add(
                                    ScsEvalItem(
                                        id = it.optLong("id", 0L),
                                        typeId = it.optInt("typeId", 0),
                                        isBasic = it.optBoolean("isBasic", true),
                                        shortDesc = it.optString("shortDesc", ""),
                                        description = it.optString("description", ""),
                                        score = optNullableDouble(it, "score"),
                                        mark = it.optString("mark", ""),
                                        teacherComments = it.optString("teacherComments", ""),
                                        laborHours = optNullableDouble(it, "laborHours"),
                                        stuAble = it.optBoolean("stuAble", false)
                                    )
                                )
                            }
                        }
                        add(
                            ScsCategoryDetail(
                                typeId = c.optInt("typeId", j + 1),
                                title = c.optString("title", ""),
                                basicScore = optNullableDouble(c, "basicScore"),
                                extScore = optNullableDouble(c, "extScore"),
                                totalScore = optNullableDouble(c, "totalScore"),
                                maxTotalScore = optNullableDouble(c, "maxTotalScore"),
                                items = items
                            )
                        )
                    }
                }
                add(
                    ScsTermScore(
                        termId = t.optInt("termId", 0),
                        yearId = t.optInt("yearId", 0),
                        yearName = t.optString("yearName", ""),
                        termName = t.optString("termName", ""),
                        isFullYear = t.optBoolean("isFullYear", false),
                        status = t.optInt("status", 0),
                        totalScore = optNullableDouble(t, "totalScore"),
                        moral = optNullableDouble(t, "moral"),
                        academic = optNullableDouble(t, "academic"),
                        physical = optNullableDouble(t, "physical"),
                        aesthetics = optNullableDouble(t, "aesthetics"),
                        labor = optNullableDouble(t, "labor"),
                        isOpenSelfEva = t.optBoolean("isOpenSelfEva", false),
                        details = details
                    )
                )
            }
        }
        val actArr = o.optJSONArray("activities") ?: JSONArray()
        val activities = buildList(actArr.length()) {
            for (i in 0 until actArr.length()) {
                val a = actArr.optJSONObject(i) ?: continue
                add(
                    ScsActivityItem(
                        id = a.optString("id", ""),
                        title = a.optString("title", ""),
                        categoryName = a.optString("categoryName", ""),
                        startTime = a.optString("startTime", ""),
                        endTime = a.optString("endTime", ""),
                        location = a.optString("location", ""),
                        score = a.optString("score", ""),
                        status = a.optString("status", "")
                    )
                )
            }
        }
        return ScsSnapshot(
            studentName = o.optString("studentName", ""),
            studentId = o.optString("studentId", ""),
            collegeName = o.optString("collegeName", ""),
            className = o.optString("className", ""),
            gradeName = o.optString("gradeName", ""),
            currentWindow = window,
            dimensions = dims,
            terms = terms,
            activities = activities
        )
    }

    private fun putNullableDouble(obj: JSONObject, key: String, value: Double?) {
        if (value != null && !value.isNaN()) {
            obj.put(key, value)
        }
    }

    private fun optNullableDouble(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val v = obj.optDouble(key, Double.NaN)
        return if (v.isNaN()) null else v
    }

    companion object {
        private const val SOURCE_ID = "scs.buct.edu.cn"
    }
}
