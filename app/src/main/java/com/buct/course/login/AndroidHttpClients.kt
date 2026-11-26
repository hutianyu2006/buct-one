package com.buct.course.login

import com.buct.one.platform.buildHttpClient
import com.buct.one.platform.security.PersistentCookieStore
import com.buct.one.platform.security.SecureVault
import com.buct.one.platform.session.CookieStore
import com.buct.one.platform.session.InMemoryCookieStore
import com.buct.one.platform.session.StoredCookie
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android 侧共享网络入口：OkHttp 引擎 + 校方证书链（BuctTlsTrustManager）+ 校内 DNS（BuctDns）。
 * 共享层的所有 Ktor 客户端都必须从这里取 [HttpClient]，避免绕过 TLS 校验。
 */
object AndroidHttpClients {

    /**
     * 会话 Cookie 存储：默认内存实现（进程内有效）。
     * 调用 [useEncryptedCookieStore] 后切换为 Keystore 加密落盘，实现重启不掉登录态。
     */
    @Volatile
    private var activeCookieStore: CookieStore = InMemoryCookieStore()

    /** 稳定的转发壳：客户端与网关持有它，切换底层存储时无需重建客户端。 */
    private val cookieStoreDelegate = object : CookieStore {
        override fun load(): List<StoredCookie> = activeCookieStore.load()
        override fun save(cookies: List<StoredCookie>) = activeCookieStore.save(cookies)
        override fun clear() = activeCookieStore.clear()
    }

    /** 当前生效的 Cookie 存储（登录/业务网关共用的同一份视图）。 */
    val sessionCookieStore: CookieStore get() = cookieStoreDelegate

    /**
     * 启用加密持久化 Cookie（必须在创建 [session] 客户端之前调用，通常在 Application 启动时）。
     * 密文进 `files/vault/secure_sso.enc`，已从备份规则排除；退出登录需调用 [clearSessionCookies]。
     */
    fun useEncryptedCookieStore(vault: SecureVault) {
        activeCookieStore = PersistentCookieStore(vault, SecureVault.ALIAS_SSO)
    }

    /** 退出登录：立即擦除落盘的会话 Cookie 密文。 */
    fun clearSessionCookies() {
        activeCookieStore.clear()
    }

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
