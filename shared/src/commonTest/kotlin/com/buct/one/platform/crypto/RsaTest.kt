package com.buct.one.platform.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * RSA PKCS#1 v1.5 公钥加密的纯函数单测（无平台依赖）：
 * 结构、长度与随机性；与标准实现的互通校验见 jvmTest 的 RsaBouncyCastleTest。
 */
class RsaTest {

    /** 2048 位测试公钥（与校方通行密钥同一格式的 X.509 DER；私钥未保留，不能用于真实环境）。 */
    private val publicKeyBase64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAjVr1zKwohU3xA0afprWLSQvIymaSH/V27MedFc+CecX" +
            "SnORIFMAp4uEIb4taDq/2X4eMeTI66Mu/rB5GKSFDbExF2Gu4NaO/CNDpf1gHMScUrIFCh4CDqzBnx17kclvezLkIK" +
            "0T8FVa4cRsINvzjbnA6jUSMaf6Fm1n9wTAtW6QYBjssGOEtCj+c38PTBdFMmJbXp3brt1tEBesz6lb3Fjp76FGvD" +
            "Z08xtYG8fxYPuiMwKU04eS+mcX/BunwgpU3zwekHYB+PWRIvq0lBry9Wms25sJE5T/RAv5fEuMLbBkfcZK3+7ivS" +
            "ZthTmPpr2Ap/ji70ZZ6u2jvR5VJq+LJHQIDAQAB"

    private val der: ByteArray = base64Decode(publicKeyBase64)

    @Test
    fun encryptsToModulusLengthAndIsRandomized() {
        val plain = "0123456789abcdef".encodeToByteArray()
        val first = Rsa.encryptPkcs1(der, plain)
        val second = Rsa.encryptPkcs1(der, plain)
        assertEquals(256, first.size)
        assertEquals(256, second.size)
        // PKCS#1 v1.5 每次填充不同 → 同一明文两次密文不同
        assertNotEquals(first.toHexString(), second.toHexString())
    }

    @Test
    fun rejectsOversizedPlaintext() {
        assertFailsWith<IllegalArgumentException> { Rsa.encryptPkcs1(der, ByteArray(246)) }
    }

    @Test
    fun rejectsGarbageDer() {
        assertFailsWith<IllegalArgumentException> { Rsa.encryptPkcs1(ByteArray(8) { 0x7F }, ByteArray(4)) }
    }

    @Test
    fun encryptsEmptyPlaintext() {
        val cipher = Rsa.encryptPkcs1(der, ByteArray(0))
        assertEquals(256, cipher.size)
        assertTrue(cipher.any { it.toInt() != 0 })
    }
}
