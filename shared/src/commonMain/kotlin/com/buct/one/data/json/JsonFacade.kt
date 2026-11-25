package com.buct.one.data.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject as KotlinxJsonObject

/**
 * 只有读取能力的 org.json 风格 JSON 外观（底层 kotlinx.serialization）。
 *
 * 迁移动机：Android 端解析器原来直接用 [org.json]，iOS 端没有该库。这里提供与 org.json 同名的
 * 只读方法（optString/optInt/optJSONObject/...），解析器的业务代码可以原样复用，双端共用一份。
 * 语义差异：显式 null 值统一回退到 fallback（org.json 会返回字符串 "null"），对现有解析器更安全。
 */
class JSONObject internal constructor(internal val raw: KotlinxJsonObject) {

    constructor(text: String) : this(parseObjectOrThrow(text))

    fun length(): Int = raw.size

    fun keys(): Set<String> = raw.keys

    fun has(key: String): Boolean = raw.containsKey(key)

    fun isNull(key: String): Boolean {
        val value = raw[key]
        return value == null || value is JsonNull
    }

    fun optString(key: String, fallback: String = ""): String = stringValue(raw[key]) ?: fallback

    fun optInt(key: String, fallback: Int = 0): Int = intValue(raw[key]) ?: fallback

    fun optLong(key: String, fallback: Long = 0L): Long = longValue(raw[key]) ?: fallback

    fun optDouble(key: String, fallback: Double = 0.0): Double = doubleValue(raw[key]) ?: fallback

    fun optBoolean(key: String, fallback: Boolean = false): Boolean = booleanValue(raw[key]) ?: fallback

    /** 原样取值（org.json 的 opt 语义）：字符串原样、对象/数组包装，缺失 & null 返回 null。 */
    fun opt(key: String): Any? = when (val value = raw[key]) {
        null, is JsonNull -> null
        is JsonArray -> JSONArray(value)
        is KotlinxJsonObject -> JSONObject(value)
        is JsonPrimitive -> value.content
    }

    fun optJSONObject(key: String): JSONObject? = (raw[key] as? KotlinxJsonObject)?.let(::JSONObject)

    fun optJSONArray(key: String): JSONArray? = (raw[key] as? JsonArray)?.let(::JSONArray)

    override fun toString(): String = raw.toString()
}

/** [JSONObject] 的数组版本，方法与 org.json 对齐。 */
class JSONArray internal constructor(internal val raw: JsonArray) {

    constructor() : this(JsonArray(emptyList()))

    constructor(text: String) : this(parseArrayOrThrow(text))

    fun length(): Int = raw.size

    fun optJSONObject(index: Int): JSONObject? = (raw.getOrNull(index) as? KotlinxJsonObject)?.let(::JSONObject)

    fun optJSONArray(index: Int): JSONArray? = (raw.getOrNull(index) as? JsonArray)?.let(::JSONArray)

    fun optString(index: Int, fallback: String = ""): String = stringValue(raw.getOrNull(index)) ?: fallback

    fun optInt(index: Int, fallback: Int = 0): Int = intValue(raw.getOrNull(index)) ?: fallback

    fun optLong(index: Int, fallback: Long = 0L): Long = longValue(raw.getOrNull(index)) ?: fallback

    fun optDouble(index: Int, fallback: Double = 0.0): Double = doubleValue(raw.getOrNull(index)) ?: fallback

    fun optBoolean(index: Int, fallback: Boolean = false): Boolean = booleanValue(raw.getOrNull(index)) ?: fallback

    override fun toString(): String = raw.toString()
}

private fun parseObjectOrThrow(text: String): KotlinxJsonObject =
    (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? KotlinxJsonObject)
        ?: throw IllegalArgumentException("JSON 对象解析失败")

private fun parseArrayOrThrow(text: String): JsonArray =
    (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray)
        ?: throw IllegalArgumentException("JSON 数组解析失败")

private fun stringValue(element: JsonElement?): String? = when (element) {
    null, is JsonNull -> null
    is JsonPrimitive -> element.content
    else -> null
}

private fun intValue(element: JsonElement?): Int? {
    val content = (element as? JsonPrimitive)?.content ?: return null
    return content.toIntOrNull() ?: content.toDoubleOrNull()?.toInt()
}

private fun longValue(element: JsonElement?): Long? {
    val content = (element as? JsonPrimitive)?.content ?: return null
    return content.toLongOrNull() ?: content.toDoubleOrNull()?.toLong()
}

private fun doubleValue(element: JsonElement?): Double? =
    (element as? JsonPrimitive)?.content?.toDoubleOrNull()

private fun booleanValue(element: JsonElement?): Boolean? =
    (element as? JsonPrimitive)?.content?.lowercase()?.let {
        when (it) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }
