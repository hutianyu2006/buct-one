package com.buct.one.platform

import com.buct.one.data.login.PlatformPasskeySigner
import com.buct.one.data.login.SoftwarePasskeyAuthenticator
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * iOS 通行密钥认证器：[PlatformPasskeySigner] 的平台实现。
 *
 * 与 Android 同一套策略（学校未提供关联域，系统认证器会拒绝该 RP）：
 * - WebAuthn 组包与 ECDSA 签名全部复用共享 [SoftwarePasskeyAuthenticator]（只写一份）；
 * - 私钥标量进 Keychain（`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`，不进 iCloud 备份）；
 * - 每次签名前用 `LAContext` 要求 Face ID / Touch ID / 设备密码，未通过不产出断言。
 */
class IosPasskeySigner(
    private val authenticator: SoftwarePasskeyAuthenticator =
        SoftwarePasskeyAuthenticator(iosSecureVault),
) : PlatformPasskeySigner {

    override fun hasCredential(username: String): Boolean = authenticator.hasCredential(username)

    override suspend fun createAssertion(username: String, requestOptionsJson: String): String? {
        if (!authenticate(reason = "使用通行密钥登录")) return null
        return authenticator.createAssertion(username, requestOptionsJson)
    }

    override suspend fun createCredential(
        username: String,
        displayName: String,
        creationOptionsJson: String,
    ): String? {
        if (!authenticate(reason = "创建通行密钥")) return null
        return authenticator.createCredential(username, creationOptionsJson)
    }

    /** 系统级用户验证：生物识别优先，失败可回退设备密码；用户取消返回 false。 */
    private suspend fun authenticate(reason: String): Boolean = suspendCancellableCoroutine { continuation ->
        val context = LAContext()
        context.localizedFallbackTitle = "使用设备密码"
        context.evaluatePolicy(
            policy = LAPolicyDeviceOwnerAuthentication,
            localizedReason = reason,
        ) { success, _ ->
            if (continuation.isActive) continuation.resume(success)
        }
    }
}
