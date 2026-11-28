package com.buct.one.data.sync

import com.buct.one.platform.session.CookieStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SsoCredentialSourcesTest {

    private fun store(vararg cookies: com.buct.one.platform.session.StoredCookie): CookieStore =
        com.buct.one.platform.session.InMemoryCookieStore().apply { save(cookies.toList()) }

    private fun cookie(name: String, value: String, domain: String) =
        com.buct.one.platform.session.StoredCookie(name = name, value = value, domain = domain)

    @Test
    fun snapshot_onlyKeepsCampusDomainAndIsStable() {
        val snapshot = store(
            cookie("CASTGC", "tgt-1", "sidportal.buct.edu.cn"),
            cookie("SESSION", "sess-1", ".buct.edu.cn"),
            cookie("JSESSIONID", "foreign", "example.com"),
        ).sidPortalCookieSnapshot()

        assertTrue(snapshot.contains("CASTGC=tgt-1"))
        assertTrue(snapshot.contains("SESSION=sess-1"))
        assertTrue(!snapshot.contains("foreign"))
        // 排序稳定：同一份 Cookie 集合每次生成的快照字符串一致，才能用于"是否被别人刷新过"的比较
        assertEquals(snapshot, store(
            cookie("SESSION", "sess-1", ".buct.edu.cn"),
            cookie("CASTGC", "tgt-1", "sidportal.buct.edu.cn"),
        ).sidPortalCookieSnapshot())
    }

    @Test
    fun snapshot_emptyWhenNoCampusCookies() {
        assertEquals("", store(cookie("JSESSIONID", "foreign", "example.com")).sidPortalCookieSnapshot())
        assertEquals("", com.buct.one.platform.session.InMemoryCookieStore().sidPortalCookieSnapshot())
    }

    @Test
    fun credentialsSource_isFunctionalInterface() {
        val source = SsoCredentialsSource { SsoCredentials("20230001", "p@ss".toCharArray()) }
        val credentials = source.current()
        assertTrue(credentials != null)
        assertEquals("20230001", credentials!!.username)
        credentials.clear()
        assertTrue(credentials.passwordChars.all { it == '\u0000' })
    }
}
