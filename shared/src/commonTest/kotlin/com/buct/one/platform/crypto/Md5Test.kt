package com.buct.one.platform.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/** RFC 1321 测试向量：MD5 只用于复刻校方前端 Csrf-Value，不承担安全职责。 */
class Md5Test {

    @Test
    fun matchesRfc1321Vectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", BuiltinCrypto.md5Hex(""))
        assertEquals("0cc175b9c0f1b6a831c399e269772661", BuiltinCrypto.md5Hex("a"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", BuiltinCrypto.md5Hex("abc"))
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", BuiltinCrypto.md5Hex("message digest"))
        assertEquals(
            "c3fcd3d76192e4007dfb496cca67e13b",
            BuiltinCrypto.md5Hex("abcdefghijklmnopqrstuvwxyz"),
        )
        assertEquals(
            "d174ab98d277d9f5a5611c2c9f419d9f",
            BuiltinCrypto.md5Hex("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"),
        )
        assertEquals(
            "57edf4a22be3c955ac49da2e2107b67a",
            BuiltinCrypto.md5Hex("12345678901234567890123456789012345678901234567890123456789012345678901234567890"),
        )
    }

    @Test
    fun handlesUtf8Input() {
        // 与 JVM MessageDigest 结果一致（用于中文 CSRF 输入）
        assertEquals("7eca689f0d3389d9dea66ae112e5cfd7", BuiltinCrypto.md5Hex("你好"))
    }
}
