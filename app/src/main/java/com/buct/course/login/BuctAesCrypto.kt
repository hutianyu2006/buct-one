package com.buct.course.login

import com.buct.one.platform.crypto.AesLoginCrypto

/**
 * 北化统一身份认证密码加密（AES-ECB + PKCS7）。
 * 实现已下沉到共享层 [AesLoginCrypto]，这里保留 Android 侧的历史 API 与异常类型。
 */
internal object BuctAesCrypto {
    class CryptoException(message: String) : Exception(message)

    fun encrypt(b64Key: String, plain: String): String =
        try {
            AesLoginCrypto.encrypt(b64Key, plain)
        } catch (error: AesLoginCrypto.CryptoException) {
            throw CryptoException(error.message ?: "统一认证密码加密失败")
        }
}
