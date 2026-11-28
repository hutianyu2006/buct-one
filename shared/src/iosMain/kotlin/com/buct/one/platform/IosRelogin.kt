package com.buct.one.platform

import com.buct.one.data.login.CasLoginOutcome
import com.buct.one.data.login.CasPasswordLoginClient
import com.buct.one.data.sync.SsoCredentials
import com.buct.one.data.sync.SsoCredentialsSource
import com.buct.one.data.sync.SsoSilentReloginCoordinator
import com.buct.one.data.sync.sidPortalCookieSnapshot
import com.buct.one.platform.security.SecureVault
import com.buct.one.platform.session.CookieStore

/**
 * iOS 侧「记住密码」存储：与 Android 的 Keystore 凭据库对等，只写 Keychain
 * （`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`，不进 iCloud 备份）。
 *
 * 只有用户在登录页显式勾选「记住密码」才会写入；退出登录立即擦除。
 */
internal object IosCredentialStore {

    private const val KEY_USERNAME = "saved.username"
    private const val KEY_PASSWORD = "saved.password"

    fun save(vault: SecureVault, username: String, password: CharArray) {
        if (username.isBlank() || password.isEmpty()) return
        val chars = password.copyOf()
        try {
            vault.put(SecureVault.ALIAS_SSO, KEY_USERNAME, username.trim())
            vault.put(SecureVault.ALIAS_SSO, KEY_PASSWORD, chars.concatToString())
        } finally {
            chars.fill('\u0000')
            password.fill('\u0000')
        }
    }

    fun clear(vault: SecureVault) {
        vault.remove(SecureVault.ALIAS_SSO, KEY_USERNAME)
        vault.remove(SecureVault.ALIAS_SSO, KEY_PASSWORD)
    }

    fun hasSaved(vault: SecureVault): Boolean =
        !vault.get(SecureVault.ALIAS_SSO, KEY_PASSWORD).isNullOrEmpty()

    /** 凭据来源：没有保存过密码时返回 null（界面不会尝试自动重登）。 */
    fun source(vault: SecureVault): SsoCredentialsSource = SsoCredentialsSource {
        val username = vault.get(SecureVault.ALIAS_SSO, KEY_USERNAME)?.trim().orEmpty()
        val password = vault.get(SecureVault.ALIAS_SSO, KEY_PASSWORD).orEmpty()
        if (username.isEmpty() || password.isEmpty()) {
            null
        } else {
            SsoCredentials(username, password.toCharArray())
        }
    }
}

/**
 * iOS 静默重登协调器：凭据来自 Keychain，sidportal 快照来自加密 Cookie 存储。
 * 单飞、30s 冷却、凭据清零全部由共享层负责，这里只做注入。
 */
internal fun createIosReloginCoordinator(
    vault: SecureVault = iosSecureVault,
    cookieStore: CookieStore = iosSessionCookieStore,
): SsoSilentReloginCoordinator {
    val source = IosCredentialStore.source(vault)
    return SsoSilentReloginCoordinator(
        credentialsProvider = { source.current() },
        sidCookieProvider = { cookieStore.sidPortalCookieSnapshot() },
    )
}

/**
 * 用 Keychain 里的凭据重新登录一次，成功后 Cookie 由共享 CookieStore 持有。
 * 返回 true 表示统一认证已通过，调用方可以重试原请求。
 */
internal suspend fun reloginWithSavedCredentials(
    loginClient: CasPasswordLoginClient,
    username: String,
    passwordChars: CharArray,
): Boolean {
    if (passwordChars.isEmpty()) return false
    val password = passwordChars.concatToString()
    return try {
        loginClient.login(username, password) is CasLoginOutcome.Success
    } finally {
        passwordChars.fill('\u0000')
    }
}
