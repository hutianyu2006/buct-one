package com.buct.one.platform.security

import com.buct.one.platform.session.CookieStore
import com.buct.one.platform.session.StoredCookie
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 加密持久化的会话 Cookie 存储：让 iOS / Android 重启后仍保持登录态。
 *
 * - 序列化后的 Cookie 列表整体加密写入 [SecureVault]（Android Keystore / iOS Keychain）；
 * - **绝不**写入普通 `NSUserDefaults` / SharedPreferences 明文；
 * - [clear] 在退出登录或登录失败时立即擦除密文。
 *
 * 注意：内存中仍保留一份，避免每次请求都做一次解密。
 */
class PersistentCookieStore(
    private val vault: SecureVault,
    private val alias: String = SecureVault.ALIAS_SSO,
    private val key: String = DEFAULT_KEY,
) : CookieStore {

    private var cache: List<StoredCookie>? = null

    override fun load(): List<StoredCookie> {
        cache?.let { return it }
        val raw = vault.get(alias, key)?.takeIf { it.isNotBlank() }
        val decoded = if (raw == null) emptyList() else decodeOrEmpty(raw)
        cache = decoded
        return decoded
    }

    override fun save(cookies: List<StoredCookie>) {
        cache = cookies.toList()
        if (cookies.isEmpty()) {
            vault.remove(alias, key)
            return
        }
        vault.put(alias, key, encode(cookies))
    }

    override fun clear() {
        cache = emptyList()
        vault.remove(alias, key)
    }

    private fun encode(cookies: List<StoredCookie>): String = buildJsonObject {
        put(
            "cookies",
            buildJsonArray {
                for (cookie in cookies) {
                    add(
                        buildJsonObject {
                            put("name", cookie.name)
                            put("value", cookie.value)
                            put("domain", cookie.domain)
                            put("path", cookie.path)
                            put("secure", cookie.secure)
                            put("httpOnly", cookie.httpOnly)
                        },
                    )
                }
            },
        )
    }.toString()

    private fun decodeOrEmpty(raw: String): List<StoredCookie> = try {
        val root = Json.parseToJsonElement(raw).jsonObject
        (root["cookies"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            fun text(name: String, fallback: String = "") =
                (obj[name] as? JsonPrimitive)?.content ?: fallback
            fun flag(name: String, fallback: Boolean) =
                (obj[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: fallback
            val name = text("name")
            val value = text("value")
            val domain = text("domain")
            if (name.isBlank() || domain.isBlank()) return@mapNotNull null
            StoredCookie(
                name = name,
                value = value,
                domain = domain,
                path = text("path", "/"),
                secure = flag("secure", true),
                httpOnly = flag("httpOnly", true),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        const val DEFAULT_KEY = "session.cookies"
    }
}
