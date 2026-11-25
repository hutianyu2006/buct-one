package com.buct.one.data.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextToolsTest {

    @Test
    fun dateTime_roundTripInBeijingTime() {
        val epochMillis = 1795539232996L // 2026-11-25 00:53:52 +08:00
        val text = formatDateTime(epochMillis)
        assertEquals("2026-11-25 00:53:52", text)
        // formatDateTime 精确到秒，回环比较时按秒对齐
        assertEquals(epochMillis / 1000L * 1000L, parseDateTime(text))
        assertNull(parseDateTime("2026-13-01 00:00:00"))
    }

    @Test
    fun resolveUrl_handlesAbsoluteRelativeAndDotSegments() {
        // RFC 3986：相对路径引用会丢弃 base 的 query
        assertEquals(
            "https://a.example.com/x/y",
            resolveUrl("https://a.example.com/x/z?q=1", "y"),
        )
        assertEquals(
            "https://a.example.com/x/b/c",
            resolveUrl("https://a.example.com/x/a.html", "./b/c"),
        )
        assertEquals(
            "https://a.example.com/a/b",
            resolveUrl("https://a.example.com/a/c/d", "../b"),
        )
        assertEquals("https://other.example.com/p", resolveUrl("https://a.example.com/", "https://other.example.com/p"))
        assertEquals("https://a.example.com/z", resolveUrl("https://a.example.com/", "/z"))
    }

    @Test
    fun urlParts_extractAndReplace() {
        val url = "https://jwglxt.buct.edu.cn/jwglxt/xtgl/index_initMenu.html?x=1#frag"
        assertEquals("https", urlSchemeOf(url))
        assertEquals("jwglxt.buct.edu.cn", urlHostOf(url))
        assertEquals("/jwglxt/xtgl/index_initMenu.html", urlPathOf(url))
        assertEquals("x=1", urlQueryOf(url))
        assertEquals(
            "https://jwglxt.buct.edu.cn/new/path?x=1#frag",
            replaceUrlPath(url, "/new/path"),
        )
    }

    @Test
    fun urlCodec_matchesJavaSemantics() {
        assertEquals("a b+c", urlDecode("a%20b%2Bc"))
        assertEquals("a b c", urlDecode("a+b+c"))
        assertEquals("%E6%A0%A1%E5%9B%AD", urlEncode("校园"))
    }
}
