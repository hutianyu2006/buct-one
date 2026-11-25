package com.buct.one.platform.crypto

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Sm2LoginCryptoTest {

    // 非法公钥必须明确失败，不能产出半成品密文
    @Test
    fun rejectsInvalidPublicKeys() {
        assertFailsWith<Sm2LoginCrypto.CryptoException> { Sm2LoginCrypto.encrypt("x", "!!!not-base64!!!") }
        assertFailsWith<Sm2LoginCrypto.CryptoException> { Sm2LoginCrypto.encrypt("x", base64Encode(ByteArray(10))) }
        // 04 前缀 + 不在曲线上的点
        val bogus = ByteArray(65).also { it[0] = 0x04; it[1] = 0x01; it[64] = 0x02 }
        assertFailsWith<Sm2LoginCrypto.CryptoException> { Sm2LoginCrypto.encrypt("x", base64Encode(bogus)) }
    }

    @Test
    fun ciphertextFormatMatchesSchoolLayout() {
        // 用曲线基点 G 作为公钥（合法点），只校验长度与结构
        val publicKey = base64Encode(
            (
                "04" +
                    "32C4AE2C1F1981195F9904466A39C9948FE30BBFF2660BE1715A4589334C74C7" +
                    "BC3736A2F4F6779C59BDCEE36B692153D0A9877CC62A474002DF32E52139F0A0"
                ).hexToBytes(),
        )
        val cipher = base64Decode(Sm2LoginCrypto.encrypt("北化一站", publicKey))
        // C1(64) + C3(32) + C2(明文长度)
        assertTrue(cipher.size >= 64 + 32 + "北化一站".encodeToByteArray().size)
    }
}
