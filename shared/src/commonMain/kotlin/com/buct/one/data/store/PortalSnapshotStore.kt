package com.buct.one.data.store

import com.buct.one.data.model.PortalBookItem
import com.buct.one.data.model.PortalCalendarSnapshot
import com.buct.one.data.model.PortalHolidayItem
import com.buct.one.data.model.PortalLibrarySnapshot
import com.buct.one.data.model.PortalNewsArticle
import com.buct.one.data.model.PortalNewsColumnPage
import com.buct.one.data.model.PortalNewsSnapshot
import com.buct.one.data.model.PortalTermInfo
import com.buct.one.platform.KeyValueStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * i北化信息门户快照缓存（双端共用），三份快照各用一个键：
 * - [DEFAULT_CALENDAR_KEY]：官方校历 / 教学周 / 节假日；
 * - [DEFAULT_LIBRARY_KEY]：图书馆在借清单；
 * - [DEFAULT_NEWS_KEY]：校内综合资讯四栏。
 *
 * 只存校方公开发布的教学与资讯数据，**不**存任何凭据或 Cookie；
 * 每份快照的采集时间单独记在 `<key>.capturedAt`，供 [com.buct.one.data.sync.DomainSync.restoreFromCache] 还原。
 */
class PortalSnapshotStore(
    private val store: KeyValueStore,
    private val calendarKey: String = DEFAULT_CALENDAR_KEY,
    private val libraryKey: String = DEFAULT_LIBRARY_KEY,
    private val newsKey: String = DEFAULT_NEWS_KEY,
) {

    // ---------- 校历 ----------

    fun loadCalendar(): PortalCalendarSnapshot? {
        val raw = store.getString(calendarKey)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            decodeCalendar(Json.parseToJsonElement(raw).jsonObject)
        } catch (_: Exception) {
            null
        }
    }

    fun saveCalendar(snapshot: PortalCalendarSnapshot, capturedAt: Long = 0L) {
        store.putString(calendarKey, encodeCalendar(snapshot).toString())
        writeCapturedAt(calendarKey, capturedAt)
    }

    fun calendarCapturedAt(): Long = store.getLong(capturedAtKey(calendarKey), 0L)

    // ---------- 在借图书 ----------

    fun loadLibrary(): PortalLibrarySnapshot? {
        val raw = store.getString(libraryKey)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            decodeLibrary(Json.parseToJsonElement(raw).jsonObject)
        } catch (_: Exception) {
            null
        }
    }

    fun saveLibrary(snapshot: PortalLibrarySnapshot, capturedAt: Long = 0L) {
        store.putString(libraryKey, encodeLibrary(snapshot).toString())
        writeCapturedAt(libraryKey, capturedAt)
    }

    fun libraryCapturedAt(): Long = store.getLong(capturedAtKey(libraryKey), 0L)

    // ---------- 校内资讯 ----------

    fun loadNews(): PortalNewsSnapshot? {
        val raw = store.getString(newsKey)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            decodeNews(Json.parseToJsonElement(raw).jsonObject)
        } catch (_: Exception) {
            null
        }
    }

    fun saveNews(snapshot: PortalNewsSnapshot, capturedAt: Long = 0L) {
        store.putString(newsKey, encodeNews(snapshot).toString())
        writeCapturedAt(newsKey, capturedAt)
    }

    fun newsCapturedAt(): Long = store.getLong(capturedAtKey(newsKey), 0L)

    fun clear() {
        for (key in listOf(calendarKey, libraryKey, newsKey)) {
            store.remove(key)
            store.remove(capturedAtKey(key))
        }
    }

    private fun writeCapturedAt(key: String, capturedAt: Long) {
        if (capturedAt > 0L) store.putLong(capturedAtKey(key), capturedAt) else store.remove(capturedAtKey(key))
    }

    private fun capturedAtKey(key: String): String = "$key.capturedAt"

    // ---------- 编码 ----------

    private fun encodeCalendar(snapshot: PortalCalendarSnapshot): JsonObject = buildJsonObject {
        put("currentTermId", snapshot.currentTermId)
        put("beginDate", snapshot.beginDate)
        put("endDate", snapshot.endDate)
        put("currentTeachingWeek", snapshot.currentTeachingWeek)
        put(
            "terms",
            buildJsonArray {
                for (term in snapshot.terms) {
                    add(
                        buildJsonObject {
                            put("wid", term.wid)
                            put("academicYear", term.academicYear)
                            put("semester", term.semester)
                            put("termId", term.termId)
                            put("beginDate", term.beginDate)
                            put("endDate", term.endDate)
                            put("isCurrent", term.isCurrent)
                        },
                    )
                }
            },
        )
        put(
            "holidays",
            buildJsonArray {
                for (holiday in snapshot.holidays) {
                    add(
                        buildJsonObject {
                            put("date", holiday.date)
                            put("holidayName", holiday.holidayName)
                            put("isHoliday", holiday.isHoliday)
                            put("teachingWeek", holiday.teachingWeek)
                        },
                    )
                }
            },
        )
    }

    private fun encodeLibrary(snapshot: PortalLibrarySnapshot): JsonObject = buildJsonObject {
        put("totalCount", snapshot.totalCount)
        put(
            "books",
            buildJsonArray {
                for (book in snapshot.books) {
                    add(
                        buildJsonObject {
                            put("title", book.title)
                            put("barcode", book.barcode)
                            put("borrowDate", book.borrowDate)
                            put("dueDate", book.dueDate)
                        },
                    )
                }
            },
        )
    }

    private fun encodeNews(snapshot: PortalNewsSnapshot): JsonObject = buildJsonObject {
        put(
            "columns",
            buildJsonArray {
                for (page in snapshot.columns.values) {
                    add(
                        buildJsonObject {
                            put("columnId", page.columnId)
                            put("totalCount", page.totalCount)
                            put(
                                "articles",
                                buildJsonArray {
                                    for (article in page.articles) {
                                        add(
                                            buildJsonObject {
                                                put("id", article.id)
                                                put("columnId", article.columnId)
                                                put("title", article.title)
                                                put("sourceOrg", article.sourceOrg)
                                                put("publishTimestampMs", article.publishTimestampMs)
                                                put("summary", article.summary)
                                                put("content", article.content)
                                                put("publicUrl", article.publicUrl)
                                                put("isTop", article.isTop)
                                                put("isHot", article.isHot)
                                                put("isNewest", article.isNewest)
                                                put("visitCount", article.visitCount)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }

    // ---------- 解码 ----------

    private fun decodeCalendar(root: JsonObject): PortalCalendarSnapshot {
        val terms = root["terms"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            PortalTermInfo(
                wid = obj.text("wid"),
                academicYear = obj.int("academicYear"),
                semester = obj.int("semester", 1),
                termId = obj.text("termId"),
                beginDate = obj.text("beginDate"),
                endDate = obj.text("endDate"),
                isCurrent = obj.bool("isCurrent"),
            )
        }
        val holidays = root["holidays"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            PortalHolidayItem(
                date = obj.text("date"),
                holidayName = obj.text("holidayName"),
                isHoliday = obj.bool("isHoliday"),
                teachingWeek = obj.int("teachingWeek"),
            )
        }
        return PortalCalendarSnapshot(
            currentTermId = root.text("currentTermId"),
            beginDate = root.text("beginDate"),
            endDate = root.text("endDate"),
            currentTeachingWeek = root.int("currentTeachingWeek"),
            terms = terms,
            holidays = holidays,
        )
    }

    private fun decodeLibrary(root: JsonObject): PortalLibrarySnapshot {
        val books = root["books"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            PortalBookItem(
                title = obj.text("title"),
                barcode = obj.text("barcode"),
                borrowDate = obj.text("borrowDate"),
                dueDate = obj.text("dueDate"),
            )
        }
        return PortalLibrarySnapshot(
            totalCount = root.int("totalCount", books.size),
            books = books,
        )
    }

    private fun decodeNews(root: JsonObject): PortalNewsSnapshot {
        val columns = LinkedHashMap<String, PortalNewsColumnPage>()
        for (element in root["columns"]?.jsonArray.orEmpty()) {
            val obj = element as? JsonObject ?: continue
            val columnId = obj.text("columnId").trim()
            if (columnId.isEmpty()) continue
            val articles = obj["articles"]?.jsonArray.orEmpty().mapNotNull { articleElement ->
                val article = articleElement as? JsonObject ?: return@mapNotNull null
                PortalNewsArticle(
                    id = article.text("id"),
                    columnId = article.text("columnId", columnId),
                    title = article.text("title"),
                    sourceOrg = article.text("sourceOrg"),
                    publishTimestampMs = article.long("publishTimestampMs"),
                    summary = article.text("summary"),
                    content = article.text("content"),
                    publicUrl = article.text("publicUrl"),
                    isTop = article.bool("isTop"),
                    isHot = article.bool("isHot"),
                    isNewest = article.bool("isNewest"),
                    visitCount = article.text("visitCount"),
                )
            }
            columns[columnId] = PortalNewsColumnPage(
                columnId = columnId,
                totalCount = obj.int("totalCount", articles.size),
                articles = articles,
            )
        }
        return PortalNewsSnapshot(columns = columns)
    }

    private fun JsonObject.text(name: String, fallback: String = ""): String =
        (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: fallback

    private fun JsonObject.int(name: String, fallback: Int = 0): Int =
        (this[name] as? JsonPrimitive)?.content?.toIntOrNull() ?: fallback

    private fun JsonObject.long(name: String, fallback: Long = 0L): Long =
        (this[name] as? JsonPrimitive)?.content?.toLongOrNull() ?: fallback

    private fun JsonObject.bool(name: String, fallback: Boolean = false): Boolean =
        (this[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: fallback

    companion object {
        const val DEFAULT_CALENDAR_KEY = "cache.portal.calendar"
        const val DEFAULT_LIBRARY_KEY = "cache.portal.library"
        const val DEFAULT_NEWS_KEY = "cache.portal.news"
    }
}
