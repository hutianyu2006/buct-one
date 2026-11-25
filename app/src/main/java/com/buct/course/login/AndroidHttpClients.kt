package com.buct.course.login

import com.buct.one.platform.buildHttpClient
import com.buct.one.platform.session.InMemoryCookieStore
import com.buct.one.platform.session.StoredCookie
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android 侧共享网络入口：OkHttp 引擎 + 校方证书链（BuctTlsTrustManager）+ 校内 DNS（BuctDns）。
 * 共享层的所有 Ktor 客户端都必须从这里取 [HttpClient]，避免绕过 TLS 校验。
 */
object AndroidHttpClients {

    /** 会话 Cookie 存储（内存）：登录/业务网关共用的同一份 Cookie 视图。 */
    val sessionCookieStore = InMemoryCookieStore()

    /** 登录/会话客户端：关闭自动重定向以便观察 CAS 的 302 与 Set-Cookie，Cookie 走内存存储。 */
    val session: HttpClient by lazy {
        val trustManager = BuctTlsTrustManager()
        buildHttpClient(
            OkHttp.create {
                config {
                    sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
                    dns(BuctDns)
                    followRedirects(false)
                }
            },
            followRedirects = false,
            cookieStore = sessionCookieStore,
        )
    }

    /**
     * 把既有会话 Cookie 头（如 OkHttp CookieJar 导出的 `a=1; b=2`）种入共享 Cookie 存储，
     * 供共享层网关（JwNoticeGateway 等）直接复用同一登录态。
     */
    fun seedCookies(host: String, cookieHeader: String) {
        if (cookieHeader.isBlank()) return
        val cookies = sessionCookieStore.load().toMutableList()
        for (segment in cookieHeader.split(";")) {
            val part = segment.trim()
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            cookies.removeAll { it.name == name && it.domain == host }
            cookies.add(StoredCookie(name = name, value = value, domain = host))
        }
        sessionCookieStore.save(cookies)
    }

    val shared: HttpClient by lazy {
        val trustManager = BuctTlsTrustManager()
        buildHttpClient(
            OkHttp.create {
                config {
                    sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
                    dns(BuctDns)
                    followRedirects(true)
                }
            },
        )
    }
}
