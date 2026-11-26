package com.buct.one.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

/**
 * iOS 默认引擎：NSURLSession（系统 TLS / ATS 生效）。
 *
 * 说明：校内证书链的兜底校验（GlobalSign R3 / Sectigo E46）在 iOS 上依赖
 * `NSURLProtectionSpace.serverTrust` 与 `NSURLCredential.credentialForTrust`，
 * 当前 Kotlin/Native 平台绑定未暴露这两个符号，因此自定义 challenge 放到 Phase 5
 * 由 Swift 侧的 `URLSessionDelegate` 实现；此处保持系统默认处理（ATS 仍然严格校验，
 * 不会降级为信任任意证书）。
 *
 * 共享层的 [com.buct.one.platform.network.BuctCertificatePins] 提供同一份证书链，
 * 供 Android 的 `BuctTlsTrustManager` 与将来的 Swift 实现共同使用。
 */
actual fun createPlatformHttpEngine(): HttpClientEngine = Darwin.create()
