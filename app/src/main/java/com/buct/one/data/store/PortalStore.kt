package com.buct.one.data.store

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.PortalBookItem
import com.buct.one.data.model.PortalCalendarSnapshot
import com.buct.one.data.model.PortalHolidayItem
import com.buct.one.data.model.PortalLibrarySnapshot
import com.buct.one.data.model.PortalNewsArticle
import com.buct.one.data.model.PortalNewsColumnPage
import com.buct.one.data.model.PortalNewsSnapshot
import com.buct.one.data.model.PortalTermInfo
import com.buct.one.data.model.Provenance
import com.buct.one.data.model.RefreshState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * i北化信息门户（官方校历 / 图书馆在借 / 校内综合资讯）本地快照存储。
 *
 * 严格遵循数据层四条铁律：
 * 1. 失败永不产生新 data
 * 2. NEVER != EMPTY_CONFIRMED
 * 3. parserVersion 只增不减
 * 4. capturedAt 单调不减
 */
class PortalStore(context: Context) {

    private val baseDir: File = File(context.applicationContext.filesDir, "campus/default").apply {
        if (!exists()) mkdirs()
    }

    private val calendarFile = File(baseDir, "portal_calendar.snapshot.v1.json")
    private val libraryFile = File(baseDir, "portal_library.snapshot.v1.json")
    private val newsFile = File(baseDir, "portal_news.snapshot.v1.json")

    @Synchronized
    fun read(currentParserVersion: Int): DomainSlice<PortalCalendarSnapshot> =
        readSliceFile(calendarFile, SOURCE_PORTAL_ID, currentParserVersion, ::deserializeCalendar)

    @Synchronized
    fun commitSuccess(
        snapshot: PortalCalendarSnapshot,
        parserVersion: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): DomainSlice<PortalCalendarSnapshot> = commitSuccessSlice(
        file = calendarFile,
        sourceId = SOURCE_PORTAL_ID,
        snapshot = snapshot,
        parserVersion = parserVersion,
        nowMillis = nowMillis,
        isEmpty = snapshot.beginDate.isBlank() && snapshot.terms.isEmpty(),
        deserialize = ::deserializeCalendar,
        serialize = ::serializeCalendar
    )

    @Synchronized
    fun commitFailure(errorMessage: String, parserVersion: Int): DomainSlice<PortalCalendarSnapshot> =
        commitFailureSlice(
            file = calendarFile,
            sourceId = SOURCE_PORTAL_ID,
            defaultError = "官方校历同步失败",
            errorMessage = errorMessage,
            parserVersion = parserVersion,
            deserialize = ::deserializeCalendar,
            serialize = ::serializeCalendar
        )

    @Synchronized
    fun readLibrary(currentParserVersion: Int): DomainSlice<PortalLibrarySnapshot> =
        readSliceFile(libraryFile, SOURCE_PORTAL_ID, currentParserVersion, ::deserializeLibrary)

    @Synchronized
    fun commitLibrarySuccess(
        snapshot: PortalLibrarySnapshot,
        parserVersion: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): DomainSlice<PortalLibrarySnapshot> = commitSuccessSlice(
        file = libraryFile,
        sourceId = SOURCE_PORTAL_ID,
        snapshot = snapshot,
        parserVersion = parserVersion,
        nowMillis = nowMillis,
        isEmpty = snapshot.totalCount == 0 && snapshot.books.isEmpty(),
        deserialize = ::deserializeLibrary,
        serialize = ::serializeLibrary
    )

    @Synchronized
    fun commitLibraryFailure(errorMessage: String, parserVersion: Int): DomainSlice<PortalLibrarySnapshot> =
        commitFailureSlice(
            file = libraryFile,
            sourceId = SOURCE_PORTAL_ID,
            defaultError = "图书借阅同步失败",
            errorMessage = errorMessage,
            parserVersion = parserVersion,
            deserialize = ::deserializeLibrary,
            serialize = ::serializeLibrary
        )

    @Synchronized
    fun readNews(currentParserVersion: Int): DomainSlice<PortalNewsSnapshot> =
        readSliceFile(newsFile, SOURCE_MNEWS_ID, currentParserVersion, ::deserializeNews)

    @Synchronized
    fun commitNewsSuccess(
        snapshot: PortalNewsSnapshot,
        parserVersion: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): DomainSlice<PortalNewsSnapshot> = commitSuccessSlice(
        file = newsFile,
        sourceId = SOURCE_MNEWS_ID,
        snapshot = snapshot,
        parserVersion = parserVersion,
        nowMillis = nowMillis,
        isEmpty = snapshot.columns.values.all { it.articles.isEmpty() },
        deserialize = ::deserializeNews,
        serialize = ::serializeNews
    )

    @Synchronized
    fun commitNewsFailure(errorMessage: String, parserVersion: Int): DomainSlice<PortalNewsSnapshot> =
        commitFailureSlice(
            file = newsFile,
            sourceId = SOURCE_MNEWS_ID,
            defaultError = "校内资讯同步失败",
            errorMessage = errorMessage,
            parserVersion = parserVersion,
            deserialize = ::deserializeNews,
            serialize = ::serializeNews
        )

    private fun <T> readSliceFile(
        file: File,
        sourceId: String,
        currentParserVersion: Int,
        deserialize: (JSONObject) -> T
    ): DomainSlice<T> {
        if (!file.exists()) {
            return DomainSlice.never(source = sourceId, parserVersion = currentParserVersion)
        }
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val provObj = root.getJSONObject("provenance")
            val savedParserVersion = provObj.optInt("parserVersion", 1)
            val savedState = RefreshState.valueOf(
                provObj.optString("refreshState", RefreshState.NEVER.name)
            )
            val dataObj = root.optJSONObject("data")
            val snapshot = dataObj?.let(deserialize)
            val effectiveState = if (snapshot != null && savedParserVersion < currentParserVersion) {
                RefreshState.STALE
            } else {
                savedState
            }
            val provenance = Provenance(
                source = provObj.optString("source", sourceId),
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
            DomainSlice.never(source = sourceId, parserVersion = currentParserVersion)
        }
    }

    private fun <T> commitSuccessSlice(
        file: File,
        sourceId: String,
        snapshot: T,
        parserVersion: Int,
        nowMillis: Long,
        isEmpty: Boolean,
        deserialize: (JSONObject) -> T,
        serialize: (T) -> JSONObject
    ): DomainSlice<T> {
        val prev = readSliceFile(file, sourceId, parserVersion, deserialize)
        val monotonicTime = max(nowMillis, prev.provenance.capturedAt)
        val state = if (isEmpty) RefreshState.EMPTY_CONFIRMED else RefreshState.FRESH
        val next = DomainSlice(
            data = snapshot,
            provenance = Provenance(
                source = sourceId,
                capturedAt = monotonicTime,
                parserVersion = max(parserVersion, prev.provenance.parserVersion),
                complete = true,
                incompleteReason = null,
                refreshState = state
            ),
            lastError = null
        )
        writeSliceFile(file, next, serialize)
        return next
    }

    private fun <T> commitFailureSlice(
        file: File,
        sourceId: String,
        defaultError: String,
        errorMessage: String,
        parserVersion: Int,
        deserialize: (JSONObject) -> T,
        serialize: (T) -> JSONObject
    ): DomainSlice<T> {
        val prev = readSliceFile(file, sourceId, parserVersion, deserialize)
        val nextState = if (prev.data != null) RefreshState.FAILED_FALLBACK else RefreshState.NEVER
        val next = DomainSlice(
            data = prev.data,
            provenance = prev.provenance.copy(
                parserVersion = max(parserVersion, prev.provenance.parserVersion),
                refreshState = nextState
            ),
            lastError = errorMessage.ifBlank { defaultError }
        )
        writeSliceFile(file, next, serialize)
        return next
    }

    private fun <T> writeSliceFile(
        file: File,
        slice: DomainSlice<T>,
        serialize: (T) -> JSONObject
    ) {
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
            slice.data?.let { put("data", serialize(it)) }
            if (slice.lastError != null) {
                put("lastError", slice.lastError)
            }
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(root.toString().toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun serializeCalendar(s: PortalCalendarSnapshot): JSONObject = JSONObject().apply {
        put("currentTermId", s.currentTermId)
        put("beginDate", s.beginDate)
        put("endDate", s.endDate)
        put("currentTeachingWeek", s.currentTeachingWeek)
        put(
            "terms",
            JSONArray().apply {
                for (t in s.terms) {
                    put(
                        JSONObject().apply {
                            put("wid", t.wid)
                            put("academicYear", t.academicYear)
                            put("semester", t.semester)
                            put("termId", t.termId)
                            put("beginDate", t.beginDate)
                            put("endDate", t.endDate)
                            put("isCurrent", t.isCurrent)
                        }
                    )
                }
            }
        )
        put(
            "holidays",
            JSONArray().apply {
                for (h in s.holidays) {
                    put(
                        JSONObject().apply {
                            put("date", h.date)
                            put("holidayName", h.holidayName)
                            put("isHoliday", h.isHoliday)
                            put("teachingWeek", h.teachingWeek)
                        }
                    )
                }
            }
        )
    }

    private fun deserializeCalendar(o: JSONObject): PortalCalendarSnapshot {
        val terms = ArrayList<PortalTermInfo>()
        val termsArr = o.optJSONArray("terms")
        if (termsArr != null) {
            for (i in 0 until termsArr.length()) {
                val t = termsArr.optJSONObject(i) ?: continue
                terms.add(
                    PortalTermInfo(
                        wid = t.optString("wid"),
                        academicYear = t.optInt("academicYear", 0),
                        semester = t.optInt("semester", 1),
                        termId = t.optString("termId"),
                        beginDate = t.optString("beginDate"),
                        endDate = t.optString("endDate"),
                        isCurrent = t.optBoolean("isCurrent", false)
                    )
                )
            }
        }

        val holidays = ArrayList<PortalHolidayItem>()
        val holidaysArr = o.optJSONArray("holidays")
        if (holidaysArr != null) {
            for (i in 0 until holidaysArr.length()) {
                val h = holidaysArr.optJSONObject(i) ?: continue
                holidays.add(
                    PortalHolidayItem(
                        date = h.optString("date"),
                        holidayName = h.optString("holidayName"),
                        isHoliday = h.optBoolean("isHoliday", false),
                        teachingWeek = h.optInt("teachingWeek", 0)
                    )
                )
            }
        }

        return PortalCalendarSnapshot(
            currentTermId = o.optString("currentTermId"),
            beginDate = o.optString("beginDate"),
            endDate = o.optString("endDate"),
            currentTeachingWeek = o.optInt("currentTeachingWeek", 0),
            terms = terms,
            holidays = holidays
        )
    }

    private fun serializeLibrary(s: PortalLibrarySnapshot): JSONObject = JSONObject().apply {
        put("totalCount", s.totalCount)
        put(
            "books",
            JSONArray().apply {
                for (b in s.books) {
                    put(
                        JSONObject().apply {
                            put("title", b.title)
                            put("barcode", b.barcode)
                            put("borrowDate", b.borrowDate)
                            put("dueDate", b.dueDate)
                        }
                    )
                }
            }
        )
    }

    private fun deserializeLibrary(o: JSONObject): PortalLibrarySnapshot {
        val books = ArrayList<PortalBookItem>()
        val arr = o.optJSONArray("books")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                books.add(
                    PortalBookItem(
                        title = b.optString("title"),
                        barcode = b.optString("barcode"),
                        borrowDate = b.optString("borrowDate"),
                        dueDate = b.optString("dueDate")
                    )
                )
            }
        }
        return PortalLibrarySnapshot(
            totalCount = o.optInt("totalCount", books.size),
            books = books
        )
    }

    private fun serializeNews(s: PortalNewsSnapshot): JSONObject = JSONObject().apply {
        put(
            "columns",
            JSONArray().apply {
                for (page in s.columns.values) {
                    put(
                        JSONObject().apply {
                            put("columnId", page.columnId)
                            put("totalCount", page.totalCount)
                            put(
                                "articles",
                                JSONArray().apply {
                                    for (a in page.articles) {
                                        put(
                                            JSONObject().apply {
                                                put("id", a.id)
                                                put("columnId", a.columnId)
                                                put("title", a.title)
                                                put("sourceOrg", a.sourceOrg)
                                                put("publishTimestampMs", a.publishTimestampMs)
                                                put("summary", a.summary)
                                                put("content", a.content)
                                                put("publicUrl", a.publicUrl)
                                                put("isTop", a.isTop)
                                                put("isHot", a.isHot)
                                                put("isNewest", a.isNewest)
                                                put("visitCount", a.visitCount)
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

    private fun deserializeNews(o: JSONObject): PortalNewsSnapshot {
        val columns = LinkedHashMap<String, PortalNewsColumnPage>()
        val colArr = o.optJSONArray("columns")
        if (colArr != null) {
            for (i in 0 until colArr.length()) {
                val c = colArr.optJSONObject(i) ?: continue
                val colId = c.optString("columnId").trim()
                if (colId.isEmpty()) continue
                val articles = ArrayList<PortalNewsArticle>()
                val artArr = c.optJSONArray("articles")
                if (artArr != null) {
                    for (j in 0 until artArr.length()) {
                        val a = artArr.optJSONObject(j) ?: continue
                        articles.add(
                            PortalNewsArticle(
                                id = a.optString("id"),
                                columnId = a.optString("columnId", colId),
                                title = a.optString("title"),
                                sourceOrg = a.optString("sourceOrg"),
                                publishTimestampMs = a.optLong("publishTimestampMs", 0L),
                                summary = a.optString("summary"),
                                content = a.optString("content"),
                                publicUrl = a.optString("publicUrl"),
                                isTop = a.optBoolean("isTop", false),
                                isHot = a.optBoolean("isHot", false),
                                isNewest = a.optBoolean("isNewest", false),
                                visitCount = a.optString("visitCount")
                            )
                        )
                    }
                }
                columns[colId] = PortalNewsColumnPage(
                    columnId = colId,
                    totalCount = c.optInt("totalCount", articles.size),
                    articles = articles
                )
            }
        }
        return PortalNewsSnapshot(columns = columns)
    }

    companion object {
        private const val SOURCE_PORTAL_ID = "i.buct.edu.cn"
        private const val SOURCE_MNEWS_ID = "mnews.buct.edu.cn"
    }
}
