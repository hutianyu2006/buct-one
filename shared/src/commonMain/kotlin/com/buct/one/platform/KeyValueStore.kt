package com.buct.one.platform

/**
 * 双端键值存储抽象：数据层 Store 只需面向它编程。
 * - Android 侧由 `:app` 用 SharedPreferences 实现；
 * - iOS 侧由 [UserDefaultsKeyValueStore] 用 NSUserDefaults 实现；
 * - 单测用 [InMemoryKeyValueStore]。
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getLong(key: String, fallback: Long = 0L): Long
    fun putLong(key: String, value: Long)
    fun remove(key: String)
    fun keys(): Set<String>
    fun clear()
}

/** 内存实现：单测与降级路径（例如无法初始化持久化时）。 */
class InMemoryKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    private val values = initial.toMutableMap()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }

    override fun getLong(key: String, fallback: Long): Long = values[key]?.toLongOrNull() ?: fallback

    override fun putLong(key: String, value: Long) {
        values[key] = value.toString()
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun keys(): Set<String> = values.keys.toSet()

    override fun clear() = values.clear()
}
