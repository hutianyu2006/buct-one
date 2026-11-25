package com.buct.one.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

/** iOS 默认引擎：NSURLSession（系统 TLS / ATS 生效）。 */
actual fun createPlatformHttpEngine(): HttpClientEngine = Darwin.create()
