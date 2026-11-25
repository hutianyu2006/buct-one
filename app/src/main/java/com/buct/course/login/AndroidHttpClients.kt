package com.buct.course.login

import com.buct.one.platform.buildHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android 侧共享网络入口：OkHttp 引擎 + 校方证书链（BuctTlsTrustManager）+ 校内 DNS（BuctDns）。
 * 共享层的所有 Ktor 客户端都必须从这里取 [HttpClient]，避免绕过 TLS 校验。
 */
object AndroidHttpClients {

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
            cookieStore = com.buct.one.platform.session.InMemoryCookieStore(),
        )
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
