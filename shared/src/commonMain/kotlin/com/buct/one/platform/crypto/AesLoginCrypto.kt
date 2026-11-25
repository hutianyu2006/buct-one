package com.buct.one.platform.crypto

/**
 * 北化统一身份认证（金智 linkid 平台）的密码加密，双端共用。
 *
 * 前端实现为 `CryptoJS.AES.encrypt(明文, key=Base64.parse(croypto), {mode: ECB, padding: Pkcs7})`，
 * 即 AES-ECB + PKCS7，密钥来自登录页 `login-croypto` 的 Base64 解码字节，输出 Base64。
 * 原本是 Android 的 `BuctAesCrypto`，iOS 侧无 JCE，改为共享纯 Kotlin 实现。
 */
object AesLoginCrypto {

    class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun encrypt(b64Key: String, plain: String): String {
        val keyBytes = try {
            base64Decode(b64Key)
        } catch (error: Exception) {
            throw CryptoException("统一认证密钥格式无效", error)
        }
        if (keyBytes.isEmpty()) throw CryptoException("统一认证密钥为空")
        if (keyBytes.size != 16 && keyBytes.size != 24 && keyBytes.size != 32) {
            throw CryptoException("统一认证密钥长度不支持")
        }
        val cipherBytes = try {
            BuiltinCrypto.aesEcbEncrypt(plain.encodeToByteArray(), keyBytes)
        } catch (error: Exception) {
            throw CryptoException("密码加密失败", error)
        }
        return base64Encode(cipherBytes)
    }
}
