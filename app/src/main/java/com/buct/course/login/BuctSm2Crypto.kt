package com.buct.course.login

import com.buct.one.platform.crypto.Sm2LoginCrypto

/**
 * 北化统一身份认证的 SM2 国密加密。
 * 实现已下沉到共享层 [Sm2LoginCrypto]（BouncyCastle 依赖仅剩 Android 侧其他用途），
 * 这里保留 Android 侧的历史 API 与异常类型。
 */
internal object BuctSm2Crypto {
    class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun encrypt(plaintext: String, publicKeyBase64: String): String =
        try {
            Sm2LoginCrypto.encrypt(plaintext, publicKeyBase64)
        } catch (error: Sm2LoginCrypto.CryptoException) {
            throw CryptoException(error.message ?: "SM2 加密失败", error)
        }
}
