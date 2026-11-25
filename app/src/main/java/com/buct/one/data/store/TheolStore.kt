package com.buct.one.data.store

import android.content.Context
import com.buct.one.data.model.BlacklistedCourse
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.LocalTodoItem
import com.buct.one.data.model.Provenance
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.TheolFilterPrefs
import com.buct.one.data.model.TheolCourseInfo
import com.buct.one.data.model.TheolHomeworkAttachment
import com.buct.one.data.model.TheolHomeworkDetail
import com.buct.one.data.model.TheolHomeworkItem
import com.buct.one.data.model.TheolSnapshot
import com.buct.one.data.model.TheolTestDetail
import com.buct.one.data.model.TheolTestItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 北化在线作业与本地待办的分片 JSON 原子存储。
 * 强制遵守数据层四条铁律：
 * 1. 失败永不产生新 data（失败路径保留旧快照）
 * 2. NEVER != EMPTY_CONFIRMED
 * 3. parserVersion 只增不减
 * 4. capturedAt 单调不减（时钟回拨保护）
 */
class TheolStore(private val rootDir: File) {

    constructor(context: Context, accountKey: String = "default") : this(
        File(File(context.filesDir, "campus"), sanitizeAccountKey(accountKey))
    )

    private val theolFile: File get() = File(rootDir, "theol.snapshot.v1.json")
    private val todoFile: File get() = File(rootDir, "local.todos.v1.json")
    private val filterFile: File get() = File(rootDir, "theol.filters.v1.json")
    private val hwDetailsFile: File get() = File(rootDir, "theol.hw_details.v1.json")
    private val testDetailsFile: File get() = File(rootDir, "theol.test_details.v1.json")

    @Synchronized
    fun readTheol(defaultParserVersion: Int): DomainSlice<TheolSnapshot> {
        if (!theolFile.exists()) {
            return DomainSlice.never(SOURCE_ID, defaultParserVersion)
        }
        return runCatching {
            val root = JSONObject(theolFile.readText(Charsets.UTF_8))
            val provObj = root.getJSONObject("provenance")
            val provenance = Provenance(
                source = provObj.optString("source", SOURCE_ID),
                capturedAt = provObj.optLong("capturedAt", 0L),
                parserVersion = provObj.optInt("parserVersion", defaultParserVersion),
                refreshState = runCatching {
                    RefreshState.valueOf(provObj.optString("refreshState", RefreshState.NEVER.name))
                }.getOrDefault(RefreshState.NEVER),
                complete = provObj.optBoolean("complete", true),
                incompleteReason = provObj.optString("incompleteReason", "").ifEmpty { null }
            )
            val dataObj = root.optJSONObject("data")
            val snapshot = dataObj?.let(::decodeSnapshot)
            val lastError = root.optString("lastError", "").ifEmpty { null }
            DomainSlice(snapshot, provenance, lastError)
        }.getOrElse {
            DomainSlice.never(SOURCE_ID, defaultParserVersion)
        }
    }

    /** 成功提交：校验单调性与 NEVER != EMPTY_CONFIRMED */
    @Synchronized
    fun commitSuccess(
        snapshot: TheolSnapshot,
        parserVersion: Int,
        nowMillis: Long,
        complete: Boolean = true,
        incompleteReason: String? = null
    ): DomainSlice<TheolSnapshot> {
        val prev = readTheol(parserVersion)
        val monotonicCapturedAt = maxOf(nowMillis, prev.provenance.capturedAt)
        val monotonicParserVersion = maxOf(parserVersion, prev.provenance.parserVersion)
        val isEmpty = snapshot.homeworks.isEmpty() && snapshot.tests.isEmpty()
        val state = if (isEmpty) RefreshState.EMPTY_CONFIRMED else RefreshState.FRESH

        val slice = DomainSlice(
            data = snapshot,
            provenance = Provenance(
                source = SOURCE_ID,
                capturedAt = monotonicCapturedAt,
                parserVersion = monotonicParserVersion,
                refreshState = state,
                complete = complete,
                incompleteReason = incompleteReason
            ),
            lastError = null
        )
        writeTheolSlice(slice)
        return slice
    }

    /** 失败提交：铁律 1 — 失败永不产生新 data，原样保留旧快照 */
    @Synchronized
    fun commitFailure(errorMessage: String, parserVersion: Int): DomainSlice<TheolSnapshot> {
        val prev = readTheol(parserVersion)
        val hasPreviousData = prev.data != null
        val nextState = if (hasPreviousData) RefreshState.FAILED_FALLBACK else prev.provenance.refreshState
        val slice = DomainSlice(
            data = prev.data,
            provenance = prev.provenance.copy(
                parserVersion = maxOf(parserVersion, prev.provenance.parserVersion),
                refreshState = nextState
            ),
            lastError = errorMessage.ifBlank { "同步失败" }
        )
        writeTheolSlice(slice)
        return slice
    }

    @Synchronized
    fun readLocalTodos(): List<LocalTodoItem> {
        if (!todoFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(todoFile.readText(Charsets.UTF_8))
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        LocalTodoItem(
                            id = o.getString("id"),
                            title = o.getString("title"),
                            courseName = o.optString("courseName", ""),
                            deadlineMillis = if (o.has("deadlineMillis") && !o.isNull("deadlineMillis")) o.getLong("deadlineMillis") else null,
                            priority = o.optInt("priority", 1),
                            completed = o.optBoolean("completed", false),
                            deleted = o.optBoolean("deleted", false),
                            remind24h = o.optBoolean("remind24h", true),
                            remind12h = o.optBoolean("remind12h", true),
                            remind1h = o.optBoolean("remind1h", true),
                            createdAt = o.optLong("createdAt", 0L),
                            submitWay = o.optString("submitWay", ""),
                            note = o.optString("note", ""),
                            linkUrl = o.optString("linkUrl", ""),
                            importKey = o.optString("importKey", "")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun writeLocalTodos(items: List<LocalTodoItem>) {
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("courseName", item.courseName)
                    if (item.deadlineMillis != null) put("deadlineMillis", item.deadlineMillis)
                    put("priority", item.priority)
                    put("completed", item.completed)
                    put("deleted", item.deleted)
                    put("remind24h", item.remind24h)
                    put("remind12h", item.remind12h)
                    put("remind1h", item.remind1h)
                    put("createdAt", item.createdAt)
                    if (item.submitWay.isNotEmpty()) put("submitWay", item.submitWay)
                    if (item.note.isNotEmpty()) put("note", item.note)
                    if (item.linkUrl.isNotEmpty()) put("linkUrl", item.linkUrl)
                    if (item.importKey.isNotEmpty()) put("importKey", item.importKey)
                }
            )
        }
        atomicWrite(todoFile, arr.toString())
    }

    @Synchronized
    fun readFilterPrefs(): TheolFilterPrefs {
        if (!filterFile.exists()) return TheolFilterPrefs()
        return runCatching {
            val root = JSONObject(filterFile.readText(Charsets.UTF_8))
            val hwArr = root.optJSONArray("hiddenHomeworkIds") ?: JSONArray()
            val hiddenHw = buildSet {
                for (i in 0 until hwArr.length()) {
                    val v = hwArr.optString(i, "")
                    if (v.isNotEmpty()) add(v)
                }
            }
            val testArr = root.optJSONArray("hiddenTestKeys") ?: JSONArray()
            val hiddenTests = buildSet {
                for (i in 0 until testArr.length()) {
                    val v = testArr.optString(i, "")
                    if (v.isNotEmpty()) add(v)
                }
            }
            val blArr = root.optJSONArray("blacklistedCourses") ?: JSONArray()
            val blacklisted = buildList(blArr.length()) {
                for (i in 0 until blArr.length()) {
                    val o = blArr.getJSONObject(i)
                    val key = o.optString("key", "")
                    if (key.isNotEmpty()) {
                        add(
                            BlacklistedCourse(
                                key = key,
                                lid = o.optString("lid", ""),
                                courseName = o.optString("courseName", "")
                            )
                        )
                    }
                }
            }
            TheolFilterPrefs(
                hiddenHomeworkIds = hiddenHw,
                hiddenTestKeys = hiddenTests,
                blacklistedCourses = blacklisted
            )
        }.getOrDefault(TheolFilterPrefs())
    }

    @Synchronized
    fun writeFilterPrefs(prefs: TheolFilterPrefs) {
        val root = JSONObject().apply {
            put("hiddenHomeworkIds", JSONArray(prefs.hiddenHomeworkIds.toList()))
            put("hiddenTestKeys", JSONArray(prefs.hiddenTestKeys.toList()))
            put(
                "blacklistedCourses",
                JSONArray().apply {
                    for (bc in prefs.blacklistedCourses) {
                        put(
                            JSONObject().apply {
                                put("key", bc.key)
                                put("lid", bc.lid)
                                put("courseName", bc.courseName)
                            }
                        )
                    }
                }
            )
        }
        atomicWrite(filterFile, root.toString())
    }

    private fun writeTheolSlice(slice: DomainSlice<TheolSnapshot>) {
        val root = JSONObject().apply {
            put(
                "provenance",
                JSONObject().apply {
                    put("source", slice.provenance.source)
                    put("capturedAt", slice.provenance.capturedAt)
                    put("parserVersion", slice.provenance.parserVersion)
                    put("refreshState", slice.provenance.refreshState.name)
                    put("complete", slice.provenance.complete)
                    if (slice.provenance.incompleteReason != null) {
                        put("incompleteReason", slice.provenance.incompleteReason)
                    }
                }
            )
            slice.data?.let { put("data", encodeSnapshot(it)) }
            if (slice.lastError != null) {
                put("lastError", slice.lastError)
            }
        }
        atomicWrite(theolFile, root.toString())
    }

    private fun encodeSnapshot(snapshot: TheolSnapshot): JSONObject = JSONObject().apply {
        put("failedCourseCount", snapshot.failedCourseCount)
        put(
            "homeworks",
            JSONArray().apply {
                for (hw in snapshot.homeworks) {
                    put(
                        JSONObject().apply {
                            put("hwtid", hw.hwtid)
                            put("lid", hw.lid)
                            put("courseName", hw.courseName)
                            put("title", hw.title)
                            put("deadline", hw.deadline)
                            if (hw.deadlineMillis != null) put("deadlineMillis", hw.deadlineMillis)
                            put("score", hw.score)
                            put("publisher", hw.publisher)
                            put("canSubmit", hw.canSubmit)
                            put("hasResult", hw.hasResult)
                            put("isGroup", hw.isGroup)
                            put("status", hw.status)
                            put("detailHref", hw.detailHref)
                            put("submitHref", hw.submitHref)
                            put("resultHref", hw.resultHref)
                        }
                    )
                }
            }
        )
        put(
            "tests",
            JSONArray().apply {
                for (t in snapshot.tests) {
                    put(
                        JSONObject().apply {
                            put("testId", t.testId)
                            put("lid", t.lid)
                            put("courseName", t.courseName)
                            put("title", t.title)
                            put("startTime", t.startTime)
                            put("endTime", t.endTime)
                            if (t.endTimeMillis != null) put("endTimeMillis", t.endTimeMillis)
                            put("duration", t.duration)
                            put("allowedAttempts", t.allowedAttempts)
                            put("canStart", t.canStart)
                            put("hasResult", t.hasResult)
                            put("status", t.status)
                            put("testUrl", t.testUrl)
                            put("resultHref", t.resultHref)
                        }
                    )
                }
            }
        )
        put(
            "courses",
            JSONArray().apply {
                for (c in snapshot.courses) {
                    put(
                        JSONObject().apply {
                            put("lid", c.lid)
                            put("courseName", c.courseName)
                            put("type", c.type)
                            put("url", c.url)
                        }
                    )
                }
            }
        )
    }

    private fun decodeSnapshot(obj: JSONObject): TheolSnapshot {
        val hwArr = obj.optJSONArray("homeworks") ?: JSONArray()
        val homeworks = buildList(hwArr.length()) {
            for (i in 0 until hwArr.length()) {
                val o = hwArr.getJSONObject(i)
                add(
                    TheolHomeworkItem(
                        hwtid = o.getString("hwtid"),
                        lid = o.optString("lid", ""),
                        courseName = o.optString("courseName", ""),
                        title = o.optString("title", ""),
                        deadline = o.optString("deadline", ""),
                        deadlineMillis = if (o.has("deadlineMillis") && !o.isNull("deadlineMillis")) o.getLong("deadlineMillis") else null,
                        score = o.optString("score", ""),
                        publisher = o.optString("publisher", ""),
                        canSubmit = o.optBoolean("canSubmit", false),
                        hasResult = o.optBoolean("hasResult", false),
                        isGroup = o.optBoolean("isGroup", false),
                        status = o.optString("status", ""),
                        detailHref = o.optString("detailHref", ""),
                        submitHref = o.optString("submitHref", ""),
                        resultHref = o.optString("resultHref", "")
                    )
                )
            }
        }
        val testArr = obj.optJSONArray("tests") ?: JSONArray()
        val tests = buildList(testArr.length()) {
            for (i in 0 until testArr.length()) {
                val o = testArr.getJSONObject(i)
                add(
                    TheolTestItem(
                        testId = o.getString("testId"),
                        lid = o.optString("lid", ""),
                        courseName = o.optString("courseName", ""),
                        title = o.optString("title", ""),
                        startTime = o.optString("startTime", ""),
                        endTime = o.optString("endTime", ""),
                        endTimeMillis = if (o.has("endTimeMillis") && !o.isNull("endTimeMillis")) o.getLong("endTimeMillis") else null,
                        duration = o.optString("duration", ""),
                        allowedAttempts = o.optString("allowedAttempts", ""),
                        canStart = o.optBoolean("canStart", false),
                        hasResult = o.optBoolean("hasResult", false),
                        status = o.optString("status", ""),
                        testUrl = o.optString("testUrl", ""),
                        resultHref = o.optString("resultHref", "")
                    )
                )
            }
        }
        val coursesArr = obj.optJSONArray("courses") ?: JSONArray()
        val courses = buildList(coursesArr.length()) {
            for (i in 0 until coursesArr.length()) {
                val o = coursesArr.optJSONObject(i) ?: continue
                val lid = o.optString("lid", "")
                if (lid.isNotBlank()) {
                    add(
                        TheolCourseInfo(
                            lid = lid,
                            courseName = o.optString("courseName", ""),
                            type = o.optString("type", "course"),
                            url = o.optString("url", "")
                        )
                    )
                }
            }
        }
        return TheolSnapshot(
            homeworks = homeworks,
            tests = tests,
            failedCourseCount = obj.optInt("failedCourseCount", 0),
            courses = courses
        )
    }

    @Synchronized
    fun readHomeworkDetails(): Map<String, TheolHomeworkDetail> {
        if (!hwDetailsFile.exists()) return emptyMap()
        return runCatching {
            val arr = JSONArray(hwDetailsFile.readText(Charsets.UTF_8))
            val result = LinkedHashMap<String, TheolHomeworkDetail>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val hwtid = o.optString("hwtid", "")
                if (hwtid.isBlank()) continue
                val attArr = o.optJSONArray("attachments") ?: JSONArray()
                val attachments = buildList(attArr.length()) {
                    for (j in 0 until attArr.length()) {
                        val a = attArr.getJSONObject(j)
                        val url = a.optString("url", "")
                        if (url.isNotBlank()) {
                            add(TheolHomeworkAttachment(name = a.optString("name", "附件"), url = url))
                        }
                    }
                }
                result[hwtid] = TheolHomeworkDetail(
                    hwtid = hwtid,
                    title = o.optString("title", ""),
                    deadline = o.optString("deadline", ""),
                    score = o.optString("score", ""),
                    publisher = o.optString("publisher", ""),
                    contentText = o.optString("contentText", ""),
                    attachments = attachments,
                    fetchedAt = o.optLong("fetchedAt", 0L)
                )
            }
            result
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun writeHomeworkDetails(details: Map<String, TheolHomeworkDetail>) {
        val trimmed = details.values
            .sortedByDescending { it.fetchedAt }
            .take(MAX_DETAIL_CACHE_ENTRIES)
        val arr = JSONArray()
        for (d in trimmed) {
            arr.put(
                JSONObject().apply {
                    put("hwtid", d.hwtid)
                    put("title", d.title)
                    put("deadline", d.deadline)
                    put("score", d.score)
                    put("publisher", d.publisher)
                    put("contentText", d.contentText)
                    put("fetchedAt", d.fetchedAt)
                    put(
                        "attachments",
                        JSONArray().apply {
                            for (a in d.attachments) {
                                put(
                                    JSONObject().apply {
                                        put("name", a.name)
                                        put("url", a.url)
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }
        atomicWrite(hwDetailsFile, arr.toString())
    }

    @Synchronized
    fun readTestDetails(): Map<String, TheolTestDetail> {
        if (!testDetailsFile.exists()) return emptyMap()
        return runCatching {
            val arr = JSONArray(testDetailsFile.readText(Charsets.UTF_8))
            val result = LinkedHashMap<String, TheolTestDetail>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val testId = o.optString("testId", "")
                if (testId.isBlank()) continue
                result[testId] = TheolTestDetail(
                    testId = testId,
                    title = o.optString("title", ""),
                    description = o.optString("description", ""),
                    startTime = o.optString("startTime", ""),
                    endTime = o.optString("endTime", ""),
                    duration = o.optString("duration", ""),
                    totalScore = o.optString("totalScore", ""),
                    questionCount = o.optString("questionCount", ""),
                    fetchedAt = o.optLong("fetchedAt", 0L)
                )
            }
            result
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun writeTestDetails(details: Map<String, TheolTestDetail>) {
        val trimmed = details.values
            .sortedByDescending { it.fetchedAt }
            .take(MAX_DETAIL_CACHE_ENTRIES)
        val arr = JSONArray()
        for (d in trimmed) {
            arr.put(
                JSONObject().apply {
                    put("testId", d.testId)
                    put("title", d.title)
                    put("description", d.description)
                    put("startTime", d.startTime)
                    put("endTime", d.endTime)
                    put("duration", d.duration)
                    put("totalScore", d.totalScore)
                    put("questionCount", d.questionCount)
                    put("fetchedAt", d.fetchedAt)
                }
            )
        }
        atomicWrite(testDetailsFile, arr.toString())
    }

    private fun atomicWrite(target: File, content: String) {
        if (!rootDir.exists()) rootDir.mkdirs()
        val tmp = File(rootDir, "${target.name}.${UUID.randomUUID()}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        const val SOURCE_ID = "theol.homework"
        private const val MAX_DETAIL_CACHE_ENTRIES = 80

        private fun sanitizeAccountKey(raw: String): String {
            val cleaned = raw.replace(Regex("""[^a-zA-Z0-9._-]"""), "_").take(64)
            return cleaned.ifBlank { "default" }
        }
    }
}
