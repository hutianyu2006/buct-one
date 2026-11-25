package com.buct.one.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies

/** 平台默认 Ktor 引擎：Android（JVM 变体）用 OkHttp，iOS 用 Darwin。 */
expect fun createPlatformHttpEngine(): HttpClientEngine

/**
 * 统一的客户端构造入口：超时、重试等公共配置只写一份。
 *
 * 安全约定：Android 侧必须通过 [engine] 传入挂好 `BuctTlsTrustManager` / `BuctDns` 的
 * OkHttp 引擎（由 `:app` 构造后注入），不能直接用默认引擎访问 *.buct.edu.cn。
 */
fun buildHttpClient(
    engine: HttpClientEngine = createPlatformHttpEngine(),
    requestTimeoutMillis: Long = 15_000,
    connectTimeoutMillis: Long = 10_000,
    socketTimeoutMillis: Long = 15_000,
    followRedirects: Boolean = true,
    keepCookies: Boolean = true,
): HttpClient = HttpClient(engine) {
    this.followRedirects = followRedirects
    install(HttpTimeout) {
        this.requestTimeoutMillis = requestTimeoutMillis
        this.connectTimeoutMillis = connectTimeoutMillis
        this.socketTimeoutMillis = socketTimeoutMillis
    }
    if (keepCookies) {
        install(HttpCookies) {
            storage = AcceptAllCookiesStorage()
        }
    }
}
