package com.buct.one.data.store

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.JwAnnouncementItem
import com.buct.one.data.model.JwNoticeSnapshot
import com.buct.one.data.model.JwReminderCategory
import com.buct.one.data.model.JwReminderItem
import com.buct.one.data.model.Provenance
import com.buct.one.data.model.RefreshState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 教务系统消息与通知公告（jwglxt.buct.edu.cn）本地快照存储。
 *
 * 严格遵循数据层四条铁律：
 * 1. 失败永不产生新 data
 * 2. NEVER != EMPTY_CONFIRMED
 * 3. parserVersion 只增不减
 * 4. capturedAt 单调不减
 */
class JwNoticeStore(context: Context) {

    private val baseDir: File = File(context.applicationContext.filesDir, "campus/default").apply {
        if (!exists()) mkdirs()
    }

    private val snapshotFile = File(baseDir, "jw_notice.snapshot.v1.json")

    private data class StoredRecord(
        val slice: DomainSlice<JwNoticeSnapshot>,
        val readReminderIds: List<String>,
        val readAnnouncementIds: List<String>
    )

    @Synchronized
    fun read(currentParserVersion: Int): DomainSlice<JwNoticeSnapshot> =
        readInternal(currentParserVersion).slice

    @Synchronized
    fun commitSuccess(
        snapshot: JwNoticeSnapshot,
        parserVersion: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): DomainSlice<JwNoticeSnapshot> {
        val prev = readInternal(parserVersion)
        val merged = applyReadSets(snapshot, prev.readReminderIds.toSet(), prev.readAnnouncementIds.toSet())
        val monotonicTime = max(nowMillis, prev.slice.provenance.capturedAt)
        val isEmpty = merged.reminders.isEmpty() && merged.announcements.isEmpty()
        val state = if (isEmpty) RefreshState.EMPTY_CONFIRMED else RefreshState.FRESH
        val next = DomainSlice(
            data = merged,
            provenance = Provenance(
                source = SOURCE_ID,
                capturedAt = monotonicTime,
                parserVersion = max(parserVersion, prev.slice.provenance.parserVersion),
                complete = true,
                incompleteReason = null,
                refreshState = state
            ),
            lastError = null
        )
        writeInternal(next, prev.readReminderIds, prev.readAnnouncementIds)
        return next
    }

    @Synchronized
    fun commitFailure(errorMessage: String, parserVersion: Int): DomainSlice<JwNoticeSnapshot> {
        val prev = readInternal(parserVersion)
        val nextState = if (prev.slice.data != null) RefreshState.FAILED_FALLBACK else RefreshState.NEVER
        val next = DomainSlice(
            data = prev.slice.data,
            provenance = prev.slice.provenance.copy(
                parserVersion = max(parserVersion, prev.slice.provenance.parserVersion),
                refreshState = nextState
            ),
            lastError = errorMessage.ifBlank { "获取教务消息与公告失败" }
        )
        writeInternal(next, prev.readReminderIds, prev.readAnnouncementIds)
        return next
    }

    @Synchronized
    fun markReminderRead(id: String, parserVersion: Int): DomainSlice<JwNoticeSnapshot> {
        val prev = readInternal(parserVersion)
        if (id.isBlank()) return prev.slice
        val updatedReadIds = appendReadIds(prev.readReminderIds, listOf(id))
        val updatedData = prev.slice.data?.let { snap ->
            snap.copy(
                reminders = snap.reminders.map { item ->
                    if (item.id == id && !item.isRead) item.copy(isRead = true) else item
                }
            )
        }
        val nextSlice = prev.slice.copy(data = updatedData)
        writeInternal(nextSlice, updatedReadIds, prev.readAnnouncementIds)
        return nextSlice
    }

    @Synchronized
    fun markAllRemindersRead(parserVersion: Int): DomainSlice<JwNoticeSnapshot> {
        val prev = readInternal(parserVersion)
        val snap = prev.slice.data ?: return prev.slice
        val allIds = snap.reminders.mapNotNull { it.id.takeIf { id -> id.isNotBlank() } }
        val updatedReadIds = appendReadIds(prev.readReminderIds, allIds)
        val updatedData = snap.copy(
            reminders = snap.reminders.map { item ->
                if (!item.isRead) item.copy(isRead = true) else item
            }
        )
        val nextSlice = prev.slice.copy(data = updatedData)
        writeInternal(nextSlice, updatedReadIds, prev.readAnnouncementIds)
        return nextSlice
    }

    @Synchronized
    fun markAnnouncementRead(id: String, parserVersion: Int): DomainSlice<JwNoticeSnapshot> {
        val prev = readInternal(parserVersion)
        if (id.isBlank()) return prev.slice
        val updatedReadIds = appendReadIds(prev.readAnnouncementIds, listOf(id))
        val updatedData = prev.slice.data?.let { snap ->
            snap.copy(
                announcements = snap.announcements.map { item ->
                    if (item.id == id && !item.isRead) item.copy(isRead = true) else item
                }
            )
        }
        val nextSlice = prev.slice.copy(data = updatedData)
        writeInternal(nextSlice, prev.readReminderIds, updatedReadIds)
        return nextSlice
    }

    @Synchronized
    fun isReminderMarkedRead(id: String, parserVersion: Int): Boolean {
        if (id.isBlank()) return false
        return id in readInternal(parserVersion).readReminderIds
    }

    @Synchronized
    fun isAnnouncementMarkedRead(id: String, parserVersion: Int): Boolean {
        if (id.isBlank()) return false
        return id in readInternal(parserVersion).readAnnouncementIds
    }

    @Synchronized
    fun applyReadToReminders(items: List<JwReminderItem>, parserVersion: Int): List<JwReminderItem> {
        val readIds = readInternal(parserVersion).readReminderIds.toSet()
        if (readIds.isEmpty()) return items
        return items.map { item -> if (!item.isRead && item.id in readIds) item.copy(isRead = true) else item }
    }

    @Synchronized
    fun applyReadToAnnouncements(items: List<JwAnnouncementItem>, parserVersion: Int): List<JwAnnouncementItem> {
        val readIds = readInternal(parserVersion).readAnnouncementIds.toSet()
        if (readIds.isEmpty()) return items
        return items.map { item -> if (!item.isRead && item.id in readIds) item.copy(isRead = true) else item }
    }

    private fun appendReadIds(existing: List<String>, additions: List<String>): List<String> {
        val merged = LinkedHashSet<String>(existing.size + additions.size)
        merged.addAll(existing)
        for (id in additions) {
            if (id.isNotBlank()) {
                merged.remove(id)
                merged.add(id)
            }
        }
        val list = merged.toList()
        return if (list.size > MAX_READ_IDS) list.takeLast(MAX_READ_IDS) else list
    }

    private fun applyReadSets(
        snapshot: JwNoticeSnapshot,
        readReminderIds: Set<String>,
        readAnnouncementIds: Set<String>
    ): JwNoticeSnapshot {
        return snapshot.copy(
            reminders = snapshot.reminders.map { item ->
                if (!item.isRead && item.id in readReminderIds) item.copy(isRead = true) else item
            },
            announcements = snapshot.announcements.map { item ->
                if (!item.isRead && item.id in readAnnouncementIds) item.copy(isRead = true) else item
            }
        )
    }

    private fun readInternal(currentParserVersion: Int): StoredRecord {
        if (!snapshotFile.exists()) {
            return StoredRecord(
                slice = DomainSlice.never(source = SOURCE_ID, parserVersion = currentParserVersion),
                readReminderIds = emptyList(),
                readAnnouncementIds = emptyList()
            )
        }
        return runCatching {
            val root = JSONObject(snapshotFile.readText(Charsets.UTF_8))
            val provObj = root.getJSONObject("provenance")
            val savedParserVersion = provObj.optInt("parserVersion", 1)
            val savedState = RefreshState.valueOf(
                provObj.optString("refreshState", RefreshState.NEVER.name)
            )
            val readReminderIds = readStringList(root.optJSONArray("localReadReminderIds"))
            val readAnnouncementIds = readStringList(root.optJSONArray("localReadAnnouncementIds"))
            val dataObj = root.optJSONObject("data")
            val snapshot = dataObj?.let {
                applyReadSets(
                    deserializeSnapshot(it),
                    readReminderIds.toSet(),
                    readAnnouncementIds.toSet()
                )
            }
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
            val slice = DomainSlice(
                data = snapshot,
                provenance = provenance,
                lastError = root.optString("lastError", "").ifBlank { null }
            )
            StoredRecord(slice, readReminderIds, readAnnouncementIds)
        }.getOrElse {
            StoredRecord(
                slice = DomainSlice.never(source = SOURCE_ID, parserVersion = currentParserVersion),
                readReminderIds = emptyList(),
                readAnnouncementIds = emptyList()
            )
        }
    }

    private fun writeInternal(
        slice: DomainSlice<JwNoticeSnapshot>,
        readReminderIds: List<String>,
        readAnnouncementIds: List<String>
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
            slice.data?.let { put("data", serializeSnapshot(it)) }
            if (slice.lastError != null) {
                put("lastError", slice.lastError)
            }
            put("localReadReminderIds", JSONArray(readReminderIds))
            put("localReadAnnouncementIds", JSONArray(readAnnouncementIds))
        }
        val tmp = File(baseDir, "jw_notice.snapshot.v1.json.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(root.toString().toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        if (!tmp.renameTo(snapshotFile)) {
            snapshotFile.delete()
            tmp.renameTo(snapshotFile)
        }
    }

    private fun serializeSnapshot(s: JwNoticeSnapshot): JSONObject = JSONObject().apply {
        put("reminderTotal", s.reminderTotal)
        put("announcementTotal", s.announcementTotal)
        put(
            "reminders",
            JSONArray().apply {
                for (r in s.reminders) {
                    put(
                        JSONObject().apply {
                            put("id", r.id)
                            put("title", r.title)
                            put("content", r.content)
                            put("createdAt", r.createdAt)
                            put("isRead", r.isRead)
                            put("category", r.category.name)
                        }
                    )
                }
            }
        )
        put(
            "announcements",
            JSONArray().apply {
                for (a in s.announcements) {
                    put(
                        JSONObject().apply {
                            put("id", a.id)
                            put("title", a.title)
                            put("publisher", a.publisher)
                            put("publishedAt", a.publishedAt)
                            put("isPinned", a.isPinned)
                            put("isRead", a.isRead)
                            put("externalUrl", a.externalUrl)
                            put("inlineHtml", a.inlineHtml)
                        }
                    )
                }
            }
        )
    }

    private fun deserializeSnapshot(o: JSONObject): JwNoticeSnapshot {
        val reminders = ArrayList<JwReminderItem>()
        val remArr = o.optJSONArray("reminders")
        if (remArr != null) {
            for (i in 0 until remArr.length()) {
                val r = remArr.optJSONObject(i) ?: continue
                val cat = runCatching {
                    JwReminderCategory.valueOf(r.optString("category", JwReminderCategory.OTHER.name))
                }.getOrDefault(JwReminderCategory.OTHER)
                reminders.add(
                    JwReminderItem(
                        id = r.optString("id"),
                        title = r.optString("title"),
                        content = r.optString("content"),
                        createdAt = r.optString("createdAt"),
                        isRead = r.optBoolean("isRead", false),
                        category = cat
                    )
                )
            }
        }

        val announcements = ArrayList<JwAnnouncementItem>()
        val annArr = o.optJSONArray("announcements")
        if (annArr != null) {
            for (i in 0 until annArr.length()) {
                val a = annArr.optJSONObject(i) ?: continue
                announcements.add(
                    JwAnnouncementItem(
                        id = a.optString("id"),
                        title = a.optString("title"),
                        publisher = a.optString("publisher"),
                        publishedAt = a.optString("publishedAt"),
                        isPinned = a.optBoolean("isPinned", false),
                        isRead = a.optBoolean("isRead", false),
                        externalUrl = a.optString("externalUrl"),
                        inlineHtml = a.optString("inlineHtml")
                    )
                )
            }
        }

        return JwNoticeSnapshot(
            reminders = reminders,
            reminderTotal = o.optInt("reminderTotal", reminders.size),
            announcements = announcements,
            announcementTotal = o.optInt("announcementTotal", announcements.size)
        )
    }

    private fun readStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) list.add(s)
        }
        return if (list.size > MAX_READ_IDS) list.takeLast(MAX_READ_IDS) else list
    }

    companion object {
        private const val SOURCE_ID = "jwglxt.buct.edu.cn"
        private const val MAX_READ_IDS = 500
    }
}
