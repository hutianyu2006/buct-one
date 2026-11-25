package com.buct.one.platform.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CryptoTest {

    @Test
    fun sha256_matchesRfcTestVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BuiltinCrypto.sha256Hex("abc"),
        )
    }

    @Test
    fun hmacSha256_matchesRfcTestVector() {
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            BuiltinCrypto.hmacSha256Hex("key", "The quick brown fox jumps over the lazy dog"),
        )
    }

    @Test
    fun aesCbcDecrypt_matchesNistCbcAes128Vector() {
        val key = "2b7e151628aed2a6abf7158809cf4f3c".hexToBytes()
        val iv = "000102030405060708090a0b0c0d0e0f".hexToBytes()
        val cipher = (
            "7649abac8119b246cee98e9b12e9197d" +
                "5086cb9b507219ee95db113a917678b2" +
                "73bed6b8e3c1743b7116e69e22229516" +
                "3ff1caa1681fac09120eca307586e1a7"
            ).hexToBytes()
        val expected = (
            "6bc1bee22e409f96e93d7e117393172a" +
                "ae2d8a571e03ac9c9eb76fac45af8e51" +
                "30c81c46a35ce411e5fbc1191a0a52ef" +
                "f69f2445df4f9b17ad2b417be66c3710"
            ).hexToBytes()

        assertContentEquals(expected, BuiltinCrypto.aesCbcDecrypt(cipher, key, iv))
    }

    @Test
    fun base64_roundTripToleratesWhitespaceAndPadding() {
        val raw = "校园卡-AES-数据".encodeToByteArray()
        val encoded = base64Encode(raw)
        assertContentEquals(raw, base64Decode(encoded))
        assertContentEquals(raw, base64Decode(encoded.chunked(4).joinToString("
")))
    }
}
