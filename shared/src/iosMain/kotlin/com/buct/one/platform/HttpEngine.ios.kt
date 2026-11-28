package com.buct.one.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlin.concurrent.Volatile
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential

/**
 * Swift 侧传入的 TLS 质询裁决结果。
 *
 * `NSURLProtectionSpace.serverTrust` 与 `NSURLCredential.credentialForTrust` 在
 * Kotlin/Native 平台绑定中不可用，由 Swift 宿主完成 `SecTrust` 校验并构造该结果回传。
 */
class IosTrustDecision private constructor(
    internal val disposition: NSURLSessionAuthChallengeDisposition,
    internal val credential: NSURLCredential?,
) {
    companion object {
        internal fun performDefault(): IosTrustDecision =
            IosTrustDecision(NSURLSessionAuthChallengePerformDefaultHandling, null)

        internal fun useCredential(credential: NSURLCredential): IosTrustDecision =
            IosTrustDecision(NSURLSessionAuthChallengeUseCredential, credential)

        internal fun cancel(): IosTrustDecision =
            IosTrustDecision(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
    }
}

/**
 * iOS 服务端证书校验桥接：由 Swift 宿主在应用启动时注册 `SecTrust` 兜底处理器，
 * Ktor Darwin 引擎遇到 `ServerTrust` 质询时转发给它；未注册时保持系统默认校验。
 */
object IosTlsBridge {
    @Volatile
    private var handler: ((NSURLAuthenticationChallenge) -> IosTrustDecision)? = null

    fun registerServerTrustHandler(
        evaluator: (NSURLAuthenticationChallenge) -> IosTrustDecision,
    ) {
        handler = evaluator
    }

    fun performDefault(): IosTrustDecision = IosTrustDecision.performDefault()

    fun useCredential(credential: NSURLCredential): IosTrustDecision =
        IosTrustDecision.useCredential(credential)

    fun cancel(): IosTrustDecision = IosTrustDecision.cancel()

    internal fun evaluate(challenge: NSURLAuthenticationChallenge): IosTrustDecision =
        handler?.invoke(challenge) ?: IosTrustDecision.performDefault()
}

/**
 * iOS 默认引擎：NSURLSession（系统 TLS / ATS 生效）。
 *
 * 通过 [IosTlsBridge] 接入 Swift 侧的双链兜底校验（仅限 `*.buct.edu.cn`，
 * 先走系统默认 `SecTrustEvaluateWithError`，失败时才用 [com.buct.one.platform.network.BuctCertificatePins] 重试）。
 */
actual fun createPlatformHttpEngine(): HttpClientEngine = Darwin.create {
    handleChallenge { _, _, challenge, completionHandler ->
        if (challenge.protectionSpace.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            return@handleChallenge
        }
        val decision = IosTlsBridge.evaluate(challenge)
        completionHandler(decision.disposition, decision.credential)
    }
}
