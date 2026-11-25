package com.buct.one.platform

import platform.Foundation.NSUserDefaults

/**
 * iOS 侧 [KeyValueStore] 实现：落在 NSUserDefaults（与 Android SharedPreferences 语义最接近）。
 * 敏感凭据（密码 / Cookie）不落这里，沿用 Android 侧「凭据不落盘」的约定，只放非敏感状态。
 */
class UserDefaultsKeyValueStore(
    private val suiteName: String? = null,
) : KeyValueStore {

    private val defaults: NSUserDefaults =
        if (suiteName == null) NSUserDefaults.standardUserDefaults else NSUserDefaults(suiteName = suiteName)

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String?) {
        if (value == null) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(value, key)
        }
    }

    override fun getLong(key: String, fallback: Long): Long {
        val stored = defaults.objectForKey(key) ?: return fallback
        val text = stored.toString()
        return text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong() ?: fallback
    }

    override fun putLong(key: String, value: Long) {
        defaults.setObject(value.toString(), key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }

    override fun keys(): Set<String> =
        defaults.dictionaryRepresentation().keys.mapNotNull { it as? String }.toSet()

    override fun clear() {
        for (key in keys()) defaults.removeObjectForKey(key)
    }
}
