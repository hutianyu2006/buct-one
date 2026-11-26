package com.buct.one.platform.security

/**
 * 双端加密凭据存储抽象（会话 Cookie、WebVPN 票据、一卡通密码等）。
 *
 * 实现约定（安全红线）：
 * - **Android**：委托 `KeystoreCipher`（AES-256-GCM + Android Keystore），密文写 `files/vault/`，
 *   且 `backup_rules.xml` / `data_extraction_rules.xml` 必须排除 vault 目录；
 * - **iOS**：Keychain，必须使用 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
 *   （不进 iCloud 备份、不随设备迁移）；
 * - 任何实现都**不得**在明文存储、日志或崩溃上报中出现凭据内容。
 *
 * [alias] 用于隔离不同用途的密钥/条目（如 `sso`、`ecard`、`webvpn`），互不可读。
 */
interface SecureVault {

    fun get(alias: String, key: String): String?

    fun put(alias: String, key: String, value: String)

    fun remove(alias: String, key: String)

    /** 清空某个 alias 下的全部条目（退出登录、登录失败抹除密文时使用）。 */
    fun clearAlias(alias: String)

    companion object {
        const val ALIAS_SSO = "sso"
        const val ALIAS_ECARD = "ecard"
        const val ALIAS_WEBVPN = "webvpn"
        const val ALIAS_MAIL = "mail"
    }
}

/**
 * 内存实现：单测、以及平台安全存储不可用时的降级路径
 * （降级即"本次进程内有效"，绝不退化为明文落盘）。
 */
class InMemorySecureVault(initial: Map<String, Map<String, String>> = emptyMap()) : SecureVault {

    private val aliases = initial
        .mapValues { (_, entries) -> entries.toMutableMap() }
        .toMutableMap()

    override fun get(alias: String, key: String): String? = aliases[alias]?.get(key)

    override fun put(alias: String, key: String, value: String) {
        aliases.getOrPut(alias) { mutableMapOf() }[key] = value
    }

    override fun remove(alias: String, key: String) {
        aliases[alias]?.remove(key)
        if (aliases[alias]?.isEmpty() == true) aliases.remove(alias)
    }

    override fun clearAlias(alias: String) {
        aliases.remove(alias)
    }
}
