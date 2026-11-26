package com.buct.one.platform.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebVpnCodecTest {

    @Test
    fun encryptHost_tokenLayoutAndRoundTrip() {
        val token = WebVpnCodec.encryptHost("scs.buct.edu.cn")
        // 32 位 IV hex + 密文 hex
        assertEquals(32 + "scs.buct.edu.cn".length * 2, token.length)
        assertTrue(token.all { it in "0123456789abcdef" })
        assertEquals("scs.buct.edu.cn", WebVpnCodec.decryptHost(token))
    }

    @Test
    fun encryptHost_isCaseInsensitiveAndLowercaseOutput() {
        val lower = WebVpnCodec.encryptHost("scs.buct.edu.cn")
        val upper = WebVpnCodec.encryptHost("SCS.BUCT.EDU.CN")
        assertEquals(lower, upper)
    }

    @Test
    fun decryptHost_rejectsGarbage() {
        assertNull(WebVpnCodec.decryptHost(""))
        assertNull(WebVpnCodec.decryptHost("abc"))
        assertNull(WebVpnCodec.decryptHost("zz".repeat(20)))
        // 偶数长度但解出的不是合法主机名
        assertNull(WebVpnCodec.decryptHost("00".repeat(20)))
    }

    @Test
    fun toVpnUrl_rewritesSchemeHostAndKeepsPathAndQuery() {
        val vpn = WebVpnCodec.toVpnUrl("https://scs.buct.edu.cn/api/activity?page=2")
        assertTrue(vpn.startsWith("https://w.buct.edu.cn/https/"))
        assertTrue(vpn.endsWith("/api/activity?page=2"))
        assertEquals("https://scs.buct.edu.cn/api/activity?page=2", WebVpnCodec.fromVpnUrl(vpn))
    }

    @Test
    fun toVpnUrl_encodesNonDefaultPort() {
        val vpn = WebVpnCodec.toVpnUrl("http://tree.buct.edu.cn:8800/home")
        assertTrue(vpn.startsWith("https://w.buct.edu.cn/http-8800/"))
        assertEquals("http://tree.buct.edu.cn:8800/home", WebVpnCodec.fromVpnUrl(vpn))
    }

    @Test
    fun alreadyVpnUrlIsUnchanged() {
        val vpn = "https://w.buct.edu.cn/https/abc/def"
        assertEquals(vpn, WebVpnCodec.toVpnUrl(vpn))
        assertNull(WebVpnCodec.fromVpnUrl("https://scs.buct.edu.cn/api"))
    }

    @Test
    fun campusOnlyHosts() {
        assertTrue(WebVpnCodec.isCampusOnlyHost("scs.buct.edu.cn"))
        assertTrue(WebVpnCodec.isCampusOnlyHost("TREE.BUCT.EDU.CN"))
        assertTrue(!WebVpnCodec.isCampusOnlyHost("jwglxt.buct.edu.cn"))
        assertEquals(7, WebVpnCodec.CAMPUS_ONLY_HOSTS.size)
    }

    @Test
    fun roundTripPreservesFragment() {
        val vpn = WebVpnCodec.toVpnUrl("https://i.buct.edu.cn/index.html#section")
        assertEquals("https://i.buct.edu.cn/index.html#section", WebVpnCodec.fromVpnUrl(vpn))
    }
}
