package com.buct.one.platform

import com.buct.one.platform.network.IosServerTrustEvaluator
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLProtectionSpace
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLAuthenticationChallenge

/**
 * iOS 默认引擎：NSURLSession（系统 TLS / ATS 生效）。
 *
 * 追加一层服务端证书兜底（见 [IosServerTrustEvaluator]）：
 * 系统校验失败时，**仅对 `*.buct.edu.cn`** 用内置 GlobalSign R3 / Sectigo E46 信任点重试，
 * 其余一律走系统默认处理，绝不无条件接受。
 */
actual fun createPlatformHttpEngine(): HttpClientEngine = Darwin.create {
    handleChallenge { _, _, challenge: NSURLAuthenticationChallenge, completionHandler ->
        val space: NSURLProtectionSpace = challenge.protectionSpace
        if (space.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            return@handleChallenge
        }
        val trust = space.serverTrust
        val accepted = IosServerTrustEvaluator.evaluate(trust, space.host)
        if (accepted) {
            // 复用系统给出的服务端信任凭据（不再放宽任何校验）
            val credential: NSURLCredential? = trust?.let { NSURLCredential.credentialForTrust(it) }
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, credential)
        } else {
            completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
        }
    }
}
