package com.buct.one.platform.crypto

/**
 * 双端共用的加密原语。
 *
 * 解析层与后续网络层需要的能力：AES-CBC 解密（校园卡）、SHA-256 / HMAC-SHA256（电费签名等）。
 * 默认实现 [BuiltinCrypto] 是纯 Kotlin，Android 与 iOS 跑同一份，并由 commonTest 的
 * 标准测试向量（NIST / RFC）守护；如需硬件加速，可替换为平台实现而不用改调用方。
 */
interface AppCrypto {
    /** AES-CBC 解密（NoPadding，调用方自行处理尾部填充）。 */
    fun aesCbcDecrypt(cipherBytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray

    /** AES-ECB + PKCS7（校方统一认证前端 CryptoJS.AES.encrypt 的等价实现）。 */
    fun aesEcbEncrypt(plainBytes: ByteArray, key: ByteArray): ByteArray

    fun aesEcbDecrypt(cipherBytes: ByteArray, key: ByteArray): ByteArray

    /**
     * RSA 公钥加密（PKCS#1 v1.5），用于通行密钥（WebAuthn）握手：
     * 校方前端用 JSEncrypt 把 AES 会话密钥用固定 RSA 公钥加密后放进 `privateKey` 请求头。
     * 平台实现（Android JCA / iOS Security）由 `platform/crypto` 各自提供，共享层只调接口。
     */
    fun rsaEncryptPkcs1(publicKeyDer: ByteArray, plainBytes: ByteArray): ByteArray

    /** 仅用于复刻校方前端 Csrf-Value（MD5），不用于安全目的。 */
    fun md5(bytes: ByteArray): ByteArray

    fun sha256(bytes: ByteArray): ByteArray

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray
}

object BuiltinCrypto : AppCrypto {

    override fun aesCbcDecrypt(cipherBytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        AesCbc.decrypt(cipherBytes, key, iv)

    override fun aesEcbEncrypt(plainBytes: ByteArray, key: ByteArray): ByteArray =
        AesCbc.encryptEcbPkcs7(plainBytes, key)

    override fun aesEcbDecrypt(cipherBytes: ByteArray, key: ByteArray): ByteArray =
        AesCbc.decryptEcbPkcs7(cipherBytes, key)

    override fun rsaEncryptPkcs1(publicKeyDer: ByteArray, plainBytes: ByteArray): ByteArray =
        Rsa.encryptPkcs1(publicKeyDer, plainBytes)

    override fun md5(bytes: ByteArray): ByteArray = Md5.hash(bytes)

    override fun sha256(bytes: ByteArray): ByteArray = Sha256.hash(bytes)

    override fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray = Sha256.hmac(key, message)
}

fun ByteArray.toHexString(): String {
    val digits = "0123456789abcdef"
    val builder = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        builder.append(digits[value shr 4]).append(digits[value and 0x0F])
    }
    return builder.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string length must be even" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

/** UTF-8 文本 → MD5 十六进制（仅用于复刻校方前端 Csrf-Value，不用于安全目的）。 */
fun AppCrypto.md5Hex(text: String): String = md5(text.encodeToByteArray()).toHexString()

/** UTF-8 文本 → SHA-256 十六进制（与 Android 侧 MessageDigest 行为一致）。 */
fun AppCrypto.sha256Hex(text: String): String = sha256(text.encodeToByteArray()).toHexString()

/** UTF-8 文本 → HMAC-SHA256 十六进制（如电费网关签名）。 */
fun AppCrypto.hmacSha256Hex(key: String, message: String): String =
    hmacSha256(key.encodeToByteArray(), message.encodeToByteArray()).toHexString()

/** Base64 编码/解码（stdlib 实现，容忍换行与缺失的 '=' 填充）。 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
fun base64Decode(text: String): ByteArray {
    val compact = text.filterNot { it.isWhitespace() }
    if (compact.isEmpty()) return ByteArray(0)
    val padded = when (compact.length % 4) {
        0 -> compact
        2 -> "$compact=="
        3 -> "$compact="
        else -> throw IllegalArgumentException("非法 Base64 长度")
    }
    return kotlin.io.encoding.Base64.decode(padded)
}

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
fun base64Encode(bytes: ByteArray): String = kotlin.io.encoding.Base64.encode(bytes)
