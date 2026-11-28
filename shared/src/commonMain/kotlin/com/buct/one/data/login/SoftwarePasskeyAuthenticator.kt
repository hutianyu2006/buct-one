package com.buct.one.data.login

import com.buct.one.platform.crypto.P256
import com.buct.one.platform.crypto.Sha256
import com.buct.one.platform.crypto.base64Decode
import com.buct.one.platform.crypto.base64Encode
import com.buct.one.platform.secureRandomBytes
import com.buct.one.platform.security.SecureVault

/**
 * 与 Android `BuctSoftwarePasskey` 等价的**跨端软件 WebAuthn 认证器**（核心逻辑只写一份）。
 *
 * 为什么不用系统认证器：系统通行密钥要求 RP 声明关联域
 * （Android `assetlinks.json` / iOS AASA），学校均未提供；两端因此都在 App 内按 WebAuthn 标准
 * 自行生成 EC P-256 密钥并构造 attestation / assertion。
 *
 * 平台差异通过两个钩子注入：
 * - [protect]：把私钥标量写进平台安全存储（Android Keystore / iOS Keychain），返回是否成功；
 * - [unprotect]：用别名取回私钥标量；取不到（用户换机、生物识别变更）返回 null。
 *
 * 安全红线：私钥标量只在内存里短暂存在，用完立即清零；任何路径都不得写日志。
 */
class SoftwarePasskeyAuthenticator(
    private val vault: SecureVault,
    private val protect: (alias: String, privateKey: ByteArray) -> Boolean = { alias, key ->
        vault.put(ALIAS_PASSKEY, alias, base64Encode(key))
        true
    },
    private val unprotect: (alias: String) -> ByteArray? = { alias ->
        vault.get(ALIAS_PASSKEY, alias)?.let { stored -> runCatching { base64Decode(stored) }.getOrNull() }
    },
    private val rpIdFallback: String = DEFAULT_RP_ID,
    private val originFallback: String = DEFAULT_ORIGIN,
) {

    /** 该学号在本机是否已有可用凭据。 */
    fun hasCredential(username: String): Boolean =
        !vault.get(ALIAS_PASSKEY, credentialIdAlias(username)).isNullOrBlank() &&
            vault.get(ALIAS_PASSKEY, credentialKeyAlias(username)).isNullOrBlank().not()

    /**
     * 创建凭据：生成密钥对、按 WebAuthn 组包 attestationObject，返回与浏览器等价的
     * PublicKeyCredential JSON（`fmt=none` 的自签名 attestation）。
     */
    fun createCredential(username: String, creationOptionsJson: String): String? {
        val options = runCatching { PasskeyOptions.parse(creationOptionsJson) }.getOrNull() ?: return null
        val challenge = options.challenge ?: return null
        val rpId = options.rpId?.takeIf { it.isNotBlank() } ?: rpIdFallback

        val privateKey = P256.generatePrivateKey()
        val publicKey = P256.publicKeyOf(privateKey) ?: return null
        val credentialId = secureRandomBytes(32)

        val protected = protect(credentialKeyAlias(username), privateKey)
        privateKey.fill(0)
        if (!protected) return null
        vault.put(ALIAS_PASSKEY, credentialIdAlias(username), b64Url(credentialId))

        val rpIdHash = Sha256.hash(rpId.encodeToByteArray())
        val coseKey = coseEc2Key(publicKey)
        val credentialIdLength = byteArrayOf((credentialId.size ushr 8).toByte(), credentialId.size.toByte())
        val authData = authDataPrefix(rpIdHash, flags = 0x45, signCount = 0) +
            ByteArray(16) + credentialIdLength + credentialId + coseKey
        val attestationObject = cborTextMap(
            "fmt" to cborText("none"),
            "attStmt" to cborEmptyMap(),
            "authData" to cborBytes(authData),
        )
        val clientDataJson = clientData(type = "webauthn.create", challenge = challenge)
        val encodedId = b64Url(credentialId)

        return "{\"type\":\"public-key\",\"id\":\"" + encodedId +
            "\",\"rawId\":\"" + encodedId +
            "\",\"response\":{\"attestationObject\":\"" + b64Url(attestationObject) +
            "\",\"clientDataJSON\":\"" + b64Url(clientDataJson) +
            "\"},\"clientExtensionResults\":{}}"
    }

    /**
     * 断言签名：按 WebAuthn 组装 authenticatorData + clientDataJSON，用 ECDSA-SHA256 签名。
     * 私钥缺失（换机 / 未创建）返回 null，界面据此引导密码登录后重新创建。
     */
    fun createAssertion(username: String, requestOptionsJson: String): String? {
        val options = runCatching { PasskeyOptions.parse(requestOptionsJson) }.getOrNull() ?: return null
        val challenge = options.challenge ?: return null
        val rpId = options.rpId?.takeIf { it.isNotBlank() } ?: rpIdFallback

        val credentialIdText = vault.get(ALIAS_PASSKEY, credentialIdAlias(username))?.trim().orEmpty()
        if (credentialIdText.isEmpty()) return null

        options.allowCredentialIds?.let { allowed ->
            if (allowed.isNotEmpty() && credentialIdText !in allowed) return null
        }

        val privateKey = unprotect(credentialKeyAlias(username)) ?: return null
        try {
            val signCount = nextSignCount(username)
            val rpIdHash = Sha256.hash(rpId.encodeToByteArray())
            val authData = authDataPrefix(rpIdHash, flags = 0x05, signCount = signCount)
            val clientDataJson = clientData(type = "webauthn.get", challenge = challenge)
            val signature = P256.sign(privateKey, authData + Sha256.hash(clientDataJson)) ?: return null
            return "{\"type\":\"public-key\",\"id\":\"" + credentialIdText +
                "\",\"rawId\":\"" + credentialIdText +
                "\",\"response\":{\"authenticatorData\":\"" + b64Url(authData) +
                "\",\"clientDataJSON\":\"" + b64Url(clientDataJson) +
                "\",\"signature\":\"" + b64Url(signature) +
                "\",\"userHandle\":null},\"clientExtensionResults\":{}}"
        } finally {
            privateKey.fill(0)
        }
    }

    /** 删除本机凭据（退出登录 / 重新创建前）。 */
    fun clearCredential(username: String) {
        vault.remove(ALIAS_PASSKEY, credentialIdAlias(username))
        vault.remove(ALIAS_PASSKEY, credentialKeyAlias(username))
        vault.remove(ALIAS_PASSKEY, signCountAlias(username))
    }

    // ---------- 内部 ----------

    private fun nextSignCount(username: String): Int {
        val alias = signCountAlias(username)
        val current = vault.get(ALIAS_PASSKEY, alias)?.toIntOrNull() ?: 0
        val next = if (current >= Int.MAX_VALUE) 1 else current + 1
        vault.put(ALIAS_PASSKEY, alias, next.toString())
        return next
    }

    /** rpIdHash || flags || signCount（大端 4 字节）。 */
    private fun authDataPrefix(rpIdHash: ByteArray, flags: Int, signCount: Int): ByteArray = rpIdHash + byteArrayOf(
        flags.toByte(),
        ((signCount ushr 24) and 0xFF).toByte(),
        ((signCount ushr 16) and 0xFF).toByte(),
        ((signCount ushr 8) and 0xFF).toByte(),
        (signCount and 0xFF).toByte(),
    )

    /** COSE_Key（EC2 / ES256 / P-256）：{1:2, 3:-7, -1:1, -2:x, -3:y}。 */
    private fun coseEc2Key(publicKeyX963: ByteArray): ByteArray {
        require(publicKeyX963.size == 65 && publicKeyX963[0].toInt() == 0x04) { "P-256 公钥长度无效" }
        val x = publicKeyX963.copyOfRange(1, 33)
        val y = publicKeyX963.copyOfRange(33, 65)
        return cborMap(
            cborInt(1) to cborInt(2),
            cborInt(3) to cborInt(-7),
            cborInt(-1) to cborInt(1),
            cborInt(-2) to cborBytes(x),
            cborInt(-3) to cborBytes(y),
        )
    }

    private fun clientData(type: String, challenge: String): ByteArray =
        ("{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" +
            originFallback + "\",\"crossOrigin\":false}").encodeToByteArray()

    private fun credentialIdAlias(username: String): String = "credential." + username.trim() + ".id"

    private fun credentialKeyAlias(username: String): String = "credential." + username.trim() + ".key"

    private fun signCountAlias(username: String): String = "credential." + username.trim() + ".signCount"

    // ---------- 极简 CBOR 编码 ----------

    private fun cborMap(vararg pairs: Pair<ByteArray, ByteArray>): ByteArray =
        pairs.fold(cborHead(5, pairs.size)) { acc, (key, value) -> acc + key + value }

    private fun cborTextMap(vararg pairs: Pair<String, ByteArray>): ByteArray =
        cborMap(*pairs.map { (key, value) -> cborText(key) to value }.toTypedArray())

    private fun cborEmptyMap(): ByteArray = cborHead(5, 0)

    private fun cborText(value: String): ByteArray {
        val bytes = value.encodeToByteArray()
        return cborHead(3, bytes.size) + bytes
    }

    private fun cborBytes(value: ByteArray): ByteArray = cborHead(2, value.size) + value

    private fun cborInt(value: Int): ByteArray =
        if (value >= 0) cborHead(0, value) else cborHead(1, -1 - value)

    private fun cborHead(major: Int, value: Int): ByteArray = when {
        value < 24 -> byteArrayOf(((major shl 5) or value).toByte())
        value < 0x100 -> byteArrayOf(((major shl 5) or 24).toByte(), value.toByte())
        value < 0x10000 -> byteArrayOf(
            ((major shl 5) or 25).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
        else -> byteArrayOf(
            ((major shl 5) or 26).toByte(),
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }

    // ---------- base64url ----------

    private fun b64Url(bytes: ByteArray): String =
        base64Encode(bytes).replace('+', '-').replace('/', '_').trimEnd('=')

    private fun b64UrlDecode(text: String): ByteArray =
        base64Decode(text.replace('-', '+').replace('_', '/'))

    companion object {
        const val DEFAULT_RP_ID = "sidportal.buct.edu.cn"
        const val DEFAULT_ORIGIN = "https://sidportal.buct.edu.cn"

        /** 软件凭据在 [SecureVault] 里的独立 alias（与 SSO / WebVPN 密文互不影响）。 */
        const val ALIAS_PASSKEY = "passkey"
    }
}

/** 认证选项 / 创建选项里本类真正需要的字段（其余字段原样交给服务端，不做解释）。 */
internal data class PasskeyOptions(
    val challenge: String?,
    val rpId: String?,
    val allowCredentialIds: List<String>?,
) {
    companion object {
        /** 只做字符串扫描，避免为几个字段引入完整 JSON 解析依赖。 */
        fun parse(json: String): PasskeyOptions = PasskeyOptions(
            challenge = extractString(json, "challenge"),
            rpId = extractString(json, "rpId"),
            allowCredentialIds = extractArrayStrings(json, "allowCredentials", "id"),
        )

        private fun extractString(json: String, field: String): String? {
            val marker = "\"" + field + "\""
            var index = json.indexOf(marker)
            while (index >= 0) {
                val colon = json.indexOf(':', index + marker.length)
                if (colon < 0) return null
                var start = colon + 1
                while (start < json.length && json[start].isWhitespace()) start++
                if (start < json.length && json[start] == '"') {
                    val end = json.indexOf('"', start + 1)
                    if (end > start) return json.substring(start + 1, end)
                }
                index = json.indexOf(marker, index + marker.length)
            }
            return null
        }

        private fun extractArrayStrings(json: String, arrayField: String, elementField: String): List<String>? {
            val index = json.indexOf("\"" + arrayField + "\"")
            if (index < 0) return null
            val start = json.indexOf('[', index)
            if (start < 0) return null
            var depth = 0
            var end = -1
            for (position in start until json.length) {
                when (json[position]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            end = position
                            break
                        }
                    }
                }
            }
            if (end < 0) return null
            val body = json.substring(start + 1, end)
            val ids = mutableListOf<String>()
            var cursor = 0
            while (true) {
                val found = body.indexOf("\"" + elementField + "\"", cursor)
                if (found < 0) break
                val colon = body.indexOf(':', found)
                if (colon < 0) break
                var valueStart = colon + 1
                while (valueStart < body.length && body[valueStart].isWhitespace()) valueStart++
                if (valueStart < body.length && body[valueStart] == '"') {
                    val valueEnd = body.indexOf('"', valueStart + 1)
                    if (valueEnd > valueStart) ids += body.substring(valueStart + 1, valueEnd)
                }
                cursor = found + 1
            }
            return ids
        }
    }
}