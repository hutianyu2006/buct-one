package com.buct.one.platform

import com.buct.one.platform.session.CookieStore
import com.buct.one.platform.session.InMemoryCookieStore
import io.ktor.client.HttpClient

/**
 * iOS 登录/会话客户端：Darwin 引擎 + 共享 Cookie 存储 + 关闭自动重定向
 * （CAS 登录必须自己观察 302 与 Set-Cookie）。
 */
internal fun createIosSessionClient(
    cookieStore: CookieStore = InMemoryCookieStore(),
): HttpClient = buildHttpClient(
    engine = createPlatformHttpEngine(),
    followRedirects = false,
    cookieStore = cookieStore,
)
