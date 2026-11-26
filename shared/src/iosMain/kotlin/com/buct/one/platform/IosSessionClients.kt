package com.buct.one.platform

import com.buct.one.platform.security.KeychainSecureVault
import com.buct.one.platform.security.PersistentCookieStore
import com.buct.one.platform.security.SecureVault
import com.buct.one.platform.session.CookieStore
import io.ktor.client.HttpClient

/**
 * iOS 侧共享的 Keychain 实例：登录态、WebVPN 票据等凭据都从这里读写，
 * 条目受 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` 约束（不进 iCloud 备份）。
 */
internal val iosSecureVault: SecureVault by lazy { KeychainSecureVault() }

/**
 * iOS 会话 Cookie 存储：Keychain 加密持久化，App 重启后仍保持登录态。
 * 退出登录需调用 [clearIosSessionCookies] 立即擦除密文。
 */
internal val iosSessionCookieStore: CookieStore by lazy {
    PersistentCookieStore(iosSecureVault, SecureVault.ALIAS_SSO)
}

internal fun clearIosSessionCookies() {
    iosSessionCookieStore.clear()
    iosSecureVault.clearAlias(SecureVault.ALIAS_SSO)
}

/**
 * iOS 登录/会话客户端：Darwin 引擎 + 共享 Cookie 存储 + 关闭自动重定向
 * （CAS 登录必须自己观察 302 与 Set-Cookie）。
 */
internal fun createIosSessionClient(
    cookieStore: CookieStore = iosSessionCookieStore,
): HttpClient = buildHttpClient(
    engine = createPlatformHttpEngine(),
    followRedirects = false,
    cookieStore = cookieStore,
)
