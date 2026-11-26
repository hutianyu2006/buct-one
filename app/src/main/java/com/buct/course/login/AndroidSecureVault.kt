package com.buct.course.login

import android.content.Context
import com.buct.one.data.session.KeystoreCipher
import com.buct.one.platform.security.SecureVault
import org.json.JSONObject
import java.io.File

/**
 * Android 侧 [SecureVault]：委托现有 [KeystoreCipher]（AES-256-GCM + Android Keystore），
 * 密文写 `files/vault/secure_<alias>.enc`（与既有 `sso_vault.enc` 等互不干扰）。
 *
 * 安全约定（与既有 vault 一致，不可放宽）：
 * - 每个 alias 独立密钥，互不共用；
 * - `backup_rules.xml` / `data_extraction_rules.xml` 已排除 vault 目录，密文不随备份外流；
 * - 解密失败（密钥被清除、文件损坏）时返回空，**绝不**回退明文。
 */
class AndroidSecureVault(context: Context) : SecureVault {

    private val appContext = context.applicationContext

    private fun cipherFor(alias: String): KeystoreCipher = KeystoreCipher(keyAliasFor(alias))

    private fun vaultFile(alias: String): File =
        File(File(appContext.filesDir, "vault"), "secure_${sanitize(alias)}.enc")

    override fun get(alias: String, key: String): String? = readEntries(alias)[key]

    override fun put(alias: String, key: String, value: String) {
        val entries = readEntries(alias).toMutableMap()
        entries[key] = value
        writeEntries(alias, entries)
    }

    override fun remove(alias: String, key: String) {
        val entries = readEntries(alias).toMutableMap()
        if (entries.remove(key) == null) return
        if (entries.isEmpty()) {
            runCatching { vaultFile(alias).delete() }
        } else {
            writeEntries(alias, entries)
        }
    }

    override fun clearAlias(alias: String) {
        runCatching { vaultFile(alias).delete() }
    }

    private fun readEntries(alias: String): Map<String, String> {
        val file = vaultFile(alias)
        if (!file.exists()) return emptyMap()
        return runCatching {
            val decrypted = cipherFor(alias).decrypt(file.readText(Charsets.UTF_8)) ?: return emptyMap()
            val json = JSONObject(decrypted)
            buildMap {
                for (key in json.keys()) {
                    put(key, json.optString(key, ""))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeEntries(alias: String, entries: Map<String, String>) {
        runCatching {
            val file = vaultFile(alias)
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val json = JSONObject()
            for ((key, value) in entries) json.put(key, value)
            file.writeText(cipherFor(alias).encrypt(json.toString()), Charsets.UTF_8)
        }
    }

    private fun sanitize(alias: String): String = alias.replace(Regex("[^a-zA-Z0-9_.-]"), "_")

    private fun keyAliasFor(alias: String): String = when (alias) {
        SecureVault.ALIAS_SSO -> KeystoreCipher.ALIAS_SSO
        SecureVault.ALIAS_ECARD -> KeystoreCipher.ALIAS_ECARD
        SecureVault.ALIAS_MAIL -> KeystoreCipher.ALIAS_MAIL
        SecureVault.ALIAS_WEBVPN -> KeystoreCipher.ALIAS_WECOM
        else -> "buct_${sanitize(alias)}_master_key"
    }
}
