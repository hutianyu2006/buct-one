package com.buct.one.platform.network

import com.buct.one.platform.crypto.base64Decode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 内置证书链的健全性检查：Base64 必须可解码、长度像 DER 证书、且是校内域兜底。
 * （真正的链校验在 Android 由 `BuctTlsTrustManager`、iOS 由 `SecTrust` 完成。）
 */
class BuctCertificatePinsTest {

    private val pins = listOf(
        "GlobalSign 中间证书" to BuctCertificatePins.GLOBALSIGN_INTERMEDIATE_B64,
        "GlobalSign Root CA - R3" to BuctCertificatePins.GLOBALSIGN_ROOT_B64,
        "ZeroSSL 中间证书" to BuctCertificatePins.ZEROSSL_INTERMEDIATE_B64,
        "Sectigo E46" to BuctCertificatePins.SECTIGO_E46_ROOT_B64,
    )

    @Test
    fun allPinsAreDecodableDer() {
        for ((name, base64) in pins) {
            val der = base64Decode(base64)
            assertTrue(der.size > 500, "$name DER 过短：${der.size}")
            // DER SEQUENCE 起始字节必须是 0x30
            assertEquals(0x30.toByte(), der[0], "$name 不是 DER SEQUENCE")
        }
    }

    @Test
    fun trustPointsAreTheTwoRoots() {
        assertEquals(2, BuctCertificatePins.trustPointBase64.size)
        assertTrue(BuctCertificatePins.trustPointBase64.contains(BuctCertificatePins.GLOBALSIGN_ROOT_B64))
        assertTrue(BuctCertificatePins.trustPointBase64.contains(BuctCertificatePins.SECTIGO_E46_ROOT_B64))
    }

    @Test
    fun onlyBuctHostsMayUseFallback() {
        assertTrue(BuctCertificatePins.isBuctHost("portal.buct.edu.cn"))
        assertTrue(BuctCertificatePins.isBuctHost("BUCT.EDU.CN"))
        assertTrue(BuctCertificatePins.isBuctHost("scs.buct.edu.cn"))
        // 关键安全边界：外部域名绝不能命中兜底
        assertTrue(!BuctCertificatePins.isBuctHost("buct.edu.cn.evil.com"))
        assertTrue(!BuctCertificatePins.isBuctHost("evil.com"))
        assertTrue(!BuctCertificatePins.isBuctHost(""))
        assertTrue(!BuctCertificatePins.isBuctHost("notbuct.edu.cn"))
    }
}
