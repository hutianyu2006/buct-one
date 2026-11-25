package com.buct.one.platform.crypto

/**
 * 北化统一身份认证（portal.buct.edu.cn）的 SM2 国密加密，双端共用。
 *
 * 输出与前端 `js/sm2.min.js` 的 `window.sm2.encrypt` 保持一致：
 * - 公钥：Base64(04||X||Y)
 * - 密文：Base64(C1 无 04 前缀 || C3 || C2)
 * 原实现为 Android 的 `BuctSm2Crypto`（依赖 BouncyCastle），iOS 侧改用共享纯 Kotlin 实现。
 */
object Sm2LoginCrypto {

    class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun encrypt(plaintext: String, publicKeyBase64: String): String {
        val publicKeyBytes = try {
            base64Decode(publicKeyBase64)
        } catch (error: Exception) {
            throw CryptoException("SM2 公钥不是有效的 Base64", error)
        }
        val point = try {
            Sm2.parsePublicKey(publicKeyBytes)
        } catch (error: Exception) {
            throw CryptoException(error.message ?: "SM2 公钥点无效", error)
        }
        val cipherBytes = try {
            Sm2.encrypt(plaintext.encodeToByteArray(), point)
        } catch (error: Exception) {
            throw CryptoException("SM2 加密失败", error)
        }
        return base64Encode(cipherBytes)
    }
}
