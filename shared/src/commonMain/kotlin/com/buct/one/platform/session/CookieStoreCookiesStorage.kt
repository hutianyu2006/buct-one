package com.buct.one.platform.session

import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 把 [CookieStore] 接到 Ktor 的 HttpCookies 插件上：
 * 双端共用同一套域名/路径匹配与会话 Cookie 生命周期。
 */
class CookieStoreCookiesStorage(private val store: CookieStore) : CookiesStorage {

    private val mutex = Mutex()

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) = mutex.withLock {
        if (cookie.value.isEmpty()) return@withLock
        val domain = cookie.domain?.removePrefix(".")?.takeIf { it.isNotBlank() } ?: requestUrl.host
        val path = cookie.path?.takeIf { it.isNotBlank() } ?: "/"
        val cookies = store.load().toMutableList()
        cookies.removeAll { it.name == cookie.name && it.domain == domain && it.path == path }
        cookies.add(
            StoredCookie(
                name = cookie.name,
                value = cookie.value,
                domain = domain,
                path = path,
                secure = cookie.secure,
                httpOnly = cookie.httpOnly,
            ),
        )
        store.save(cookies)
    }

    override suspend fun get(requestUrl: Url): List<Cookie> = mutex.withLock {
        val host = requestUrl.host
        val path = requestUrl.encodedPath.ifEmpty { "/" }
        store.load()
            .filter { matches(it, host, path) }
            .map {
                Cookie(
                    name = it.name,
                    value = it.value,
                    domain = it.domain,
                    path = it.path,
                    secure = it.secure,
                    httpOnly = it.httpOnly,
                )
            }
    }

    override fun close() = Unit

    private fun matches(cookie: StoredCookie, host: String, path: String): Boolean {
        val domainMatches = host == cookie.domain || host.endsWith("." + cookie.domain)
        if (!domainMatches) return false
        if (!path.startsWith(cookie.path)) return false
        return true
    }
}
