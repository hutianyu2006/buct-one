package com.buct.one.platform.security

import com.buct.one.platform.session.StoredCookie
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureVaultTest {

    @Test
    fun inMemoryVault_isolatesAliases() {
        val vault = InMemorySecureVault()
        vault.put(SecureVault.ALIAS_SSO, "k", "sso-value")
        vault.put(SecureVault.ALIAS_ECARD, "k", "ecard-value")

        assertEquals("sso-value", vault.get(SecureVault.ALIAS_SSO, "k"))
        assertEquals("ecard-value", vault.get(SecureVault.ALIAS_ECARD, "k"))
        assertNull(vault.get("unknown", "k"))

        vault.clearAlias(SecureVault.ALIAS_SSO)
        assertNull(vault.get(SecureVault.ALIAS_SSO, "k"))
        assertEquals("ecard-value", vault.get(SecureVault.ALIAS_ECARD, "k"))
    }

    @Test
    fun persistentCookieStore_roundTripsThroughVault() {
        val vault = InMemorySecureVault()
        val store = PersistentCookieStore(vault)

        store.save(
            listOf(
                StoredCookie(name = "SESSION", value = "abc", domain = "sidportal.buct.edu.cn"),
                StoredCookie(name = "JSESSIONID", value = "jw", domain = "jwglxt.buct.edu.cn", path = "/jwglxt"),
            ),
        )

        // 新建实例（模拟进程重启）必须从 vault 恢复
        val reopened = PersistentCookieStore(vault)
        val cookies = reopened.load()
        assertEquals(2, cookies.size)
        assertEquals("abc", cookies.first { it.name == "SESSION" }.value)
        assertEquals("/jwglxt", cookies.first { it.name == "JSESSIONID" }.path)
    }

    @Test
    fun clear_removesCiphertextFromVault() {
        val vault = InMemorySecureVault()
        val store = PersistentCookieStore(vault)
        store.save(listOf(StoredCookie(name = "SESSION", value = "abc", domain = "sidportal.buct.edu.cn")))
        assertTrue(vault.get(SecureVault.ALIAS_SSO, PersistentCookieStore.DEFAULT_KEY) != null)

        store.clear()
        assertNull(vault.get(SecureVault.ALIAS_SSO, PersistentCookieStore.DEFAULT_KEY))
        assertTrue(PersistentCookieStore(vault).load().isEmpty())
    }

    @Test
    fun corruptedVaultContentIsIgnored() {
        val vault = InMemorySecureVault(
            mapOf(SecureVault.ALIAS_SSO to mapOf(PersistentCookieStore.DEFAULT_KEY to "{broken")),
        )
        assertTrue(PersistentCookieStore(vault).load().isEmpty())
    }

    @Test
    fun savingEmptyListErasesEntry() {
        val vault = InMemorySecureVault()
        val store = PersistentCookieStore(vault)
        store.save(listOf(StoredCookie(name = "A", value = "1", domain = "x.buct.edu.cn")))
        store.save(emptyList())
        assertNull(vault.get(SecureVault.ALIAS_SSO, PersistentCookieStore.DEFAULT_KEY))
    }
}
