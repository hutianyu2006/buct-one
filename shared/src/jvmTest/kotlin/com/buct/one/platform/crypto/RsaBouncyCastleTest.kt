package com.buct.one.platform.crypto

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RSA 差分校验（仅 JVM 测试）：现场生成 2048 位密钥对，
 * 用共享实现做 PKCS#1 v1.5 加密，再用 JCA 私钥解密还原原文，
 * 证明与标准实现（校方前端 JSEncrypt 走的同一套填充）逐字节兼容。
 */
class RsaBouncyCastleTest {

    @Test
    fun sharedEncryptionIsDecryptableByStandardImplementation() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()
        val der = keyPair.public.encoded

        val plain = "buct-passkey-session-key".encodeToByteArray()
        val cipherBytes = Rsa.encryptPkcs1(der, plain)
        assertEquals(256, cipherBytes.size)

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private as RSAPrivateKey)
        assertEquals(plain.decodeToString(), cipher.doFinal(cipherBytes).decodeToString())
    }

    @Test
    fun sharedEncryptionSupportsEmptyAndMaxLengthPlaintext() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val keyPair = generator.generateKeyPair()
        val der = keyPair.public.encoded
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")

        // 空明文
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private as RSAPrivateKey)
        assertEquals(0, cipher.doFinal(Rsa.encryptPkcs1(der, ByteArray(0))).size)

        // 最大长度 256 - 11 = 245 字节
        val maxPlain = ByteArray(245) { (it % 255 + 1).toByte() }
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private as RSAPrivateKey)
        assertEquals(maxPlain.size, cipher.doFinal(Rsa.encryptPkcs1(der, maxPlain)).size)
    }
}
