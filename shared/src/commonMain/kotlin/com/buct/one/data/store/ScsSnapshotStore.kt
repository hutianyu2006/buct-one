package com.buct.one.data.store

import com.buct.one.data.model.ScsActivityItem
import com.buct.one.data.model.ScsCategoryDetail
import com.buct.one.data.model.ScsCurrentEvalWindow
import com.buct.one.data.model.ScsEvalDimension
import com.buct.one.data.model.ScsEvalItem
import com.buct.one.data.model.ScsSnapshot
import com.buct.one.data.model.ScsTermScore
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
 * 第二课堂快照缓存（双端共用），只存非敏感数据：
 * 姓名/学号属于校内公开信息，与 Android 侧既有缓存一致；凭据与 Cookie 一律不进这里。
 */
class ScsSnapshotStore(
    private val store: KeyValueStore,
    private val key: String = DEFAULT_KEY,
) {

    fun load(): ScsSnapshot? {
        val raw = store.getString(key)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            decode(Json.parseToJsonElement(raw).jsonObject)
        } catch (_: Exception) {
            null
        }
    }

    fun save(snapshot: ScsSnapshot, capturedAt: Long = 0L) {
        store.putString(key, encode(snapshot).toString())
        if (capturedAt > 0L) store.putLong(capturedAtKey(), capturedAt) else store.remove(capturedAtKey())
    }

    /** 缓存快照的采集时间（未知时返回 0）。 */
    fun capturedAt(): Long = store.getLong(capturedAtKey(), 0L)

    fun clear() {
        store.remove(key)
        store.remove(capturedAtKey())
    }

    private fun capturedAtKey(): String = "$key.capturedAt"

    private fun encode(snapshot: ScsSnapshot): JsonObject = buildJsonObject {
        put("studentName", snapshot.studentName)
        put("studentId", snapshot.studentId)
        put("collegeName", snapshot.collegeName)
        put("className", snapshot.className)
        put("gradeName", snapshot.gradeName)
        snapshot.currentWindow?.let { window ->
            put(
                "currentWindow",
                buildJsonObject {
                    put("yearId", window.yearId)
                    put("termId", window.termId)
                    put("yearName", window.yearName)
                    put("termName", window.termName)
                    put("isOpen", window.isOpen)
                    put("startTime", window.startTime)
                    put("endTime", window.endTime)
                },
            )
        }
        put(
            "dimensions",
            buildJsonArray {
                for (dimension in snapshot.dimensions) {
                    add(
                        buildJsonObject {
                            put("typeId", dimension.typeId)
                            put("name", dimension.name)
                            put("totalMax", dimension.totalMax)
                            put("basicMax", dimension.basicMax)
                            put("extMax", dimension.extMax)
                        },
                    )
                }
            },
        )
        put(
            "terms",
            buildJsonArray {
                for (term in snapshot.terms) {
                    add(
                        buildJsonObject {
                            put("termId", term.termId)
                            put("yearId", term.yearId)
                            put("yearName", term.yearName)
                            put("termName", term.termName)
                            put("isFullYear", term.isFullYear)
                            put("status", term.status)
                            term.totalScore?.let { put("totalScore", it) }
                            term.moral?.let { put("moral", it) }
                            term.academic?.let { put("academic", it) }
                            term.physical?.let { put("physical", it) }
                            term.aesthetics?.let { put("aesthetics", it) }
                            term.labor?.let { put("labor", it) }
                            put("isOpenSelfEva", term.isOpenSelfEva)
                            put(
                                "details",
                                buildJsonArray {
                                    for (detail in term.details) {
                                        add(encodeCategoryDetail(detail))
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
        put(
            "activities",
            buildJsonArray {
                for (activity in snapshot.activities) {
                    add(
                        buildJsonObject {
                            put("id", activity.id)
                            put("title", activity.title)
                            put("categoryName", activity.categoryName)
                            put("startTime", activity.startTime)
                            put("endTime", activity.endTime)
                            put("location", activity.location)
                            put("score", activity.score)
                            put("status", activity.status)
                        },
                    )
                }
            },
        )
    }

    private fun encodeCategoryDetail(detail: ScsCategoryDetail): JsonObject = buildJsonObject {
        put("typeId", detail.typeId)
        put("title", detail.title)
        detail.basicScore?.let { put("basicScore", it) }
        detail.extScore?.let { put("extScore", it) }
        detail.totalScore?.let { put("totalScore", it) }
        detail.maxTotalScore?.let { put("maxTotalScore", it) }
        put(
            "items",
            buildJsonArray {
                for (item in detail.items) {
                    add(
                        buildJsonObject {
                            put("id", item.id)
                            put("typeId", item.typeId)
                            put("isBasic", item.isBasic)
                            put("shortDesc", item.shortDesc)
                            put("description", item.description)
                            item.score?.let { put("score", it) }
                            put("mark", item.mark)
                            put("teacherComments", item.teacherComments)
                            item.laborHours?.let { put("laborHours", it) }
                            put("stuAble", item.stuAble)
                        },
                    )
                }
            },
        )
    }

    private fun decode(root: JsonObject): ScsSnapshot {
        fun JsonObject.text(name: String, fallback: String = ""): String =
            (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: fallback

        fun JsonObject.int(name: String, fallback: Int = 0): Int =
            (this[name] as? JsonPrimitive)?.content?.toIntOrNull() ?: fallback

        fun JsonObject.long(name: String, fallback: Long = 0L): Long =
            (this[name] as? JsonPrimitive)?.content?.toLongOrNull() ?: fallback

        fun JsonObject.bool(name: String, fallback: Boolean = false): Boolean =
            (this[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: fallback

        fun JsonObject.doubleOrNull(name: String): Double? =
            (this[name] as? JsonPrimitive)?.content?.toDoubleOrNull()

        val window = root["currentWindow"]?.jsonObject?.let { obj ->
            ScsCurrentEvalWindow(
                yearId = obj.int("yearId"),
                termId = obj.int("termId"),
                yearName = obj.text("yearName"),
                termName = obj.text("termName"),
                isOpen = obj.bool("isOpen"),
                startTime = obj.text("startTime"),
                endTime = obj.text("endTime"),
            )
        }
        val dimensions = root["dimensions"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            ScsEvalDimension(
                typeId = obj.int("typeId"),
                name = obj.text("name"),
                totalMax = obj.doubleOrNull("totalMax") ?: 0.0,
                basicMax = obj.doubleOrNull("basicMax") ?: 0.0,
                extMax = obj.doubleOrNull("extMax") ?: 0.0,
            )
        }
        val terms = root["terms"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            ScsTermScore(
                termId = obj.int("termId"),
                yearId = obj.int("yearId"),
                yearName = obj.text("yearName"),
                termName = obj.text("termName"),
                isFullYear = obj.bool("isFullYear"),
                status = obj.int("status"),
                totalScore = obj.doubleOrNull("totalScore"),
                moral = obj.doubleOrNull("moral"),
                academic = obj.doubleOrNull("academic"),
                physical = obj.doubleOrNull("physical"),
                aesthetics = obj.doubleOrNull("aesthetics"),
                labor = obj.doubleOrNull("labor"),
                isOpenSelfEva = obj.bool("isOpenSelfEva"),
                details = obj["details"]?.jsonArray.orEmpty().mapNotNull { detailElement ->
                    val detailObj = detailElement as? JsonObject ?: return@mapNotNull null
                    ScsCategoryDetail(
                        typeId = detailObj.int("typeId"),
                        title = detailObj.text("title"),
                        basicScore = detailObj.doubleOrNull("basicScore"),
                        extScore = detailObj.doubleOrNull("extScore"),
                        totalScore = detailObj.doubleOrNull("totalScore"),
                        maxTotalScore = detailObj.doubleOrNull("maxTotalScore"),
                        items = detailObj["items"]?.jsonArray.orEmpty().mapNotNull { itemElement ->
                            val itemObj = itemElement as? JsonObject ?: return@mapNotNull null
                            ScsEvalItem(
                                id = itemObj.long("id"),
                                typeId = itemObj.int("typeId"),
                                isBasic = itemObj.bool("isBasic"),
                                shortDesc = itemObj.text("shortDesc"),
                                description = itemObj.text("description"),
                                score = itemObj.doubleOrNull("score"),
                                mark = itemObj.text("mark"),
                                teacherComments = itemObj.text("teacherComments"),
                                laborHours = itemObj.doubleOrNull("laborHours"),
                                stuAble = itemObj.bool("stuAble"),
                            )
                        },
                    )
                },
            )
        }
        val activities = root["activities"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            ScsActivityItem(
                id = obj.text("id"),
                title = obj.text("title"),
                categoryName = obj.text("categoryName"),
                startTime = obj.text("startTime"),
                endTime = obj.text("endTime"),
                location = obj.text("location"),
                score = obj.text("score"),
                status = obj.text("status"),
            )
        }
        return ScsSnapshot(
            studentName = root.text("studentName"),
            studentId = root.text("studentId"),
            collegeName = root.text("collegeName"),
            className = root.text("className"),
            gradeName = root.text("gradeName"),
            currentWindow = window,
            dimensions = dimensions,
            terms = terms,
            activities = activities,
        )
    }

    companion object {
        const val DEFAULT_KEY = "cache.scs.snapshot"
    }
}
