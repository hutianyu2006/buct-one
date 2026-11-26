package com.buct.one.platform.network

import com.buct.one.platform.crypto.base64Decode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecCertificateRef
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustRef
import platform.Security.SecTrustSetAnchorCertificates
import platform.Security.SecTrustSetAnchorCertificatesOnly

/**
 * iOS 侧的服务端证书校验兜底，与 Android `BuctTlsTrustManager` 语义一致：
 *
 * 1. **先**用系统默认策略（`SecTrustEvaluateWithError`）校验；
 * 2. 系统校验失败时，**仅对 `*.buct.edu.cn`** 追加内置信任点（GlobalSign R3 / Sectigo E46）后重新校验；
 * 3. 仍然失败则拒绝连接（`NSURLSessionAuthChallengeCancelAuthenticationChallenge`）。
 *
 * 安全红线：任何路径都不会无条件接受证书；非校内域名一律只走系统校验。
 */
@OptIn(ExperimentalForeignApi::class)
object IosServerTrustEvaluator {

    /** 内置信任点（懒加载一次）。 */
    private val anchorCertificates: List<SecCertificateRef> by lazy {
        BuctCertificatePins.trustPointBase64.mapNotNull { base64 -> createCertificate(base64) }
    }

    /**
     * 校验服务端信任对象。
     *
     * @param trust 系统给出的 `SecTrustRef`
     * @param host 目标主机名，用于判断是否允许内置链兜底
     * @return true 表示允许建立连接
     */
    fun evaluate(trust: SecTrustRef?, host: String?): Boolean {
        if (trust == null) return false

        // 1) 系统默认校验（hostname 由 NSURLSession 单独校验，这里只判断链与有效期）
        if (SecTrustEvaluateWithError(trust, null)) return true

        // 2) 非校内域名不允许兜底
        val targetHost = host?.takeIf { it.isNotBlank() } ?: return false
        if (!BuctCertificatePins.isBuctHost(targetHost)) return false

        // 3) 追加内置信任点后重试
        val anchors = anchorCertificates
        if (anchors.isEmpty()) return false
        val cfArray = CFBridgingRetain(anchors)
        try {
            SecTrustSetAnchorCertificates(trust, cfArray?.reinterpret())
            // 关键：只信任内置锚点 + 系统锚点（false 表示系统锚点仍有效）
            SecTrustSetAnchorCertificatesOnly(trust, false)
            return SecTrustEvaluateWithError(trust, null)
        } finally {
            cfArray?.let { CFRelease(it) }
        }
    }

    /** 供诊断：内置信任点数量（0 表示证书加载失败，应视为配置错误）。 */
    fun anchorCount(): Int = anchorCertificates.size

    private fun createCertificate(base64: String): SecCertificateRef? {
        val der = runCatching { base64Decode(base64) }.getOrNull() ?: return null
        if (der.isEmpty()) return null
        val data: NSData = der.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = der.size.toULong())
        }
        return SecCertificateCreateWithData(kCFAllocatorDefault, data)
    }
}
