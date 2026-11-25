package com.buct.one.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android（JVM 变体）默认引擎。
 * 注意：访问 *.buct.edu.cn 的请求必须由 `:app` 传入挂了 BuctTlsTrustManager / BuctDns 的引擎，
 * 这里的默认实现只适合公网站点（如 notice.buct.one）。
 */
actual fun createPlatformHttpEngine(): HttpClientEngine = OkHttp.create()
