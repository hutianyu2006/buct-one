package com.buct.one.platform.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AesLoginCryptoTest {

    // 用 Node crypto（AES-128-ECB + PKCS7）生成的等价向量，与校方 CryptoJS 行为一致
    private val keyB64 = "MDEyMzQ1Njc4OWFiY2RlZg==" // "0123456789abcdef"

    @Test
    fun aesEcb_matchesCryptoJsVectors() {
        assertEquals("0kN0ypx63t0m09KFjULmnA==", AesLoginCrypto.encrypt(keyB64, "test"))
        assertEquals("nXJtsMwU2SUWjqS+ILxrPQ==", AesLoginCrypto.encrypt(keyB64, "北化一站"))
        assertEquals("y3DdwloqIEW0wTCEQYqauw==", AesLoginCrypto.encrypt(keyB64, "{}"))
    }

    @Test
    fun aesEcb_roundTrip() {
        val key = base64Decode(keyB64)
        val plain = "校园统一身份认证".encodeToByteArray()
        val cipher = BuiltinCrypto.aesEcbEncrypt(plain, key)
        assertContentEquals(plain, BuiltinCrypto.aesEcbDecrypt(cipher, key))
    }

    @Test
    fun aesEcb_rejectsInvalidKey() {
        assertFailsWith<AesLoginCrypto.CryptoException> { AesLoginCrypto.encrypt("", "x") }
        assertFailsWith<AesLoginCrypto.CryptoException> { AesLoginCrypto.encrypt("MDEyMw==", "x") }
    }
}
