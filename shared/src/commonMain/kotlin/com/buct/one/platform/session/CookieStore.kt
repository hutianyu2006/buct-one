package com.buct.one.platform.session

import kotlinx.serialization.Serializable

/**
 * 会话 Cookie 存储抽象（默认只在内存中，与 Android 侧 `MatchingMemoryCookieJar` 一致）。
 *
 * 安全约定：会话 Cookie 等同于短期凭据，**默认不落盘**；如需持久化必须走加密存储
 * （Android Keystore / iOS Keychain），不能直接写明文文件或 NSUserDefaults。
 */
interface CookieStore {
    fun load(): List<StoredCookie>
    fun save(cookies: List<StoredCookie>)
    fun clear()
}

@Serializable
data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val secure: Boolean = true,
    val httpOnly: Boolean = true,
)

/** 进程内会话 Cookie 存储（登录态在本进程有效，与 Android 现有行为对齐）。 */
class InMemoryCookieStore : CookieStore {
    private val cookies = mutableListOf<StoredCookie>()

    override fun load(): List<StoredCookie> = cookies.toList()

    override fun save(cookies: List<StoredCookie>) {
        this.cookies.clear()
        this.cookies.addAll(cookies)
    }

    override fun clear() {
        cookies.clear()
    }
}
