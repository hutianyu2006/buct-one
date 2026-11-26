package com.buct.one.platform.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * AES-128-CFB（无填充）测试：WebVPN 的 URL 加密依赖它。
 * 向量来自 NIST SP 800-38A CFB128-AES128（用 Node crypto 复算确认）。
 */
class AesCfbTest {

    private val key = hexToBytes("2b7e151628aed2a6abf7158809cf4f3c")
    private val iv = hexToBytes("000102030405060708090a0b0c0d0e0f")

    @Test
    fun matchesNistCfb128Vector() {
        val plain = hexToBytes(
            "6bc1bee22e409f96e93d7e117393172a" + "ae2d8a571e03ac9c9eb76fac45af8e51",
        )
        val expected = hexToBytes(
            "3b3fd92eb72dad20333449f8e83cfb4a" + "c8a64537a0b3a93fcde3cdad9f1ce58b",
        )
        assertEquals(expected.toHexString(), AesCbc.encryptCfbNoPadding(plain, key, iv).toHexString())
        assertEquals(plain.toHexString(), AesCbc.decryptCfbNoPadding(expected, key, iv).toHexString())
    }

    @Test
    fun handlesPartialFinalBlock() {
        val plain = "https://scs.buct.edu.cn/api/x?a=1".encodeToByteArray()
        val expected = "388a13bcea571d99a96a44c7f9da8f1428e4c583e9d4868a492ea74b4d549b3d30"
        val cipher = AesCbc.encryptCfbNoPadding(plain, key, iv)
        assertEquals(expected, cipher.toHexString())
        assertContentEquals(plain, AesCbc.decryptCfbNoPadding(cipher, key, iv))
    }

    @Test
    fun emptyInputProducesEmptyOutput() {
        assertContentEquals(ByteArray(0), AesCbc.encryptCfbNoPadding(ByteArray(0), key, iv))
        assertContentEquals(ByteArray(0), AesCbc.decryptCfbNoPadding(ByteArray(0), key, iv))
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
}
