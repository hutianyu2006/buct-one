package com.buct.one.data.store

import com.buct.one.data.model.JwAnnouncementItem
import com.buct.one.data.model.JwNoticeSnapshot
import com.buct.one.data.model.JwReminderCategory
import com.buct.one.data.model.JwReminderItem
import com.buct.one.platform.KeyValueStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 教务消息快照缓存（双端共用）：Android 可写 SharedPreferences，iOS 写 NSUserDefaults。
 * 只缓存非敏感数据（标题/正文/时间），不含 Cookie 与凭据。
 */
class JwNoticeSnapshotStore(
    private val store: KeyValueStore,
    private val key: String = DEFAULT_KEY,
) {

    fun load(): JwNoticeSnapshot? {
        val raw = store.getString(key)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            decode(Json.parseToJsonElement(raw).jsonObject)
        } catch (_: Exception) {
            null
        }
    }

    fun save(snapshot: JwNoticeSnapshot) {
        store.putString(key, encode(snapshot).toString())
    }

    fun clear() {
        store.remove(key)
    }

    private fun encode(snapshot: JwNoticeSnapshot): JsonObject = buildJsonObject {
        put("reminderTotal", snapshot.reminderTotal)
        put("announcementTotal", snapshot.announcementTotal)
        put(
            "reminders",
            kotlinx.serialization.json.buildJsonArray {
                for (item in snapshot.reminders) {
                    add(
                        buildJsonObject {
                            put("id", item.id)
                            put("title", item.title)
                            put("content", item.content)
                            put("createdAt", item.createdAt)
                            put("isRead", item.isRead)
                            put("category", item.category.name)
                        },
                    )
                }
            },
        )
        put(
            "announcements",
            kotlinx.serialization.json.buildJsonArray {
                for (item in snapshot.announcements) {
                    add(
                        buildJsonObject {
                            put("id", item.id)
                            put("title", item.title)
                            put("publisher", item.publisher)
                            put("publishedAt", item.publishedAt)
                            put("isPinned", item.isPinned)
                            put("isRead", item.isRead)
                            put("externalUrl", item.externalUrl)
                            put("inlineHtml", item.inlineHtml)
                        },
                    )
                }
            },
        )
    }

    private fun decode(root: JsonObject): JwNoticeSnapshot {
        fun JsonObject.str(name: String, fallback: String = "") =
            this[name]?.jsonPrimitive?.contentOrNullCompat() ?: fallback

        fun JsonObject.bool(name: String, fallback: Boolean = false) =
            this[name]?.jsonPrimitive?.contentOrNullCompat()?.toBooleanStrictOrNull() ?: fallback

        fun JsonObject.int(name: String, fallback: Int): Int =
            this[name]?.jsonPrimitive?.contentOrNullCompat()?.toIntOrNull() ?: fallback

        val reminders = root["reminders"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id")
            val title = obj.str("title")
            if (id.isBlank() && title.isBlank()) return@mapNotNull null
            JwReminderItem(
                id = id,
                title = title,
                content = obj.str("content"),
                createdAt = obj.str("createdAt"),
                isRead = obj.bool("isRead"),
                category = JwReminderCategory.entries.firstOrNull { it.name == obj.str("category") }
                    ?: JwReminderCategory.OTHER,
            )
        }
        val announcements = root["announcements"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id")
            val title = obj.str("title")
            if (id.isBlank() && title.isBlank()) return@mapNotNull null
            JwAnnouncementItem(
                id = id,
                title = title,
                publisher = obj.str("publisher"),
                publishedAt = obj.str("publishedAt"),
                isPinned = obj.bool("isPinned"),
                isRead = obj.bool("isRead"),
                externalUrl = obj.str("externalUrl"),
                inlineHtml = obj.str("inlineHtml"),
            )
        }
        return JwNoticeSnapshot(
            reminders = reminders,
            reminderTotal = root.int("reminderTotal", reminders.size),
            announcements = announcements,
            announcementTotal = root.int("announcementTotal", announcements.size),
        )
    }

    companion object {
        const val DEFAULT_KEY = "cache.jwnotice.snapshot"
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullCompat(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
