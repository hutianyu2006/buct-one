package com.buct.one.platform.network

import com.buct.one.platform.buildHttpClient
import com.buct.one.platform.security.InMemorySecureVault
import com.buct.one.platform.security.SecureVault
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebVpnSessionTest {

    private var clock = 1_000_000L

    private fun session(
        engine: MockEngine,
        vault: SecureVault = InMemorySecureVault(),
        credentials: (() -> Pair<String, CharArray>?)? = null,
    ) = WebVpnSession(
        httpClient = buildHttpClient(engine, followRedirects = false),
        userAgent = "test-UA",
        vault = vault,
        credentialsProvider = credentials,
        clock = { clock },
    )

    // ---------- 降级状态 ----------

    @Test
    fun directFailurePrefersVpnForTtl() {
        val session = session(MockEngine { respond("") })
        assertTrue(!session.shouldPreferVpn("scs.buct.edu.cn"))

        session.markDirectFailed("SCS.BUCT.EDU.CN")
        assertTrue(session.shouldPreferVpn("scs.buct.edu.cn"))

        clock += WebVpnSession.PREFER_VPN_TTL_MILLIS + 1
        assertTrue(!session.shouldPreferVpn("scs.buct.edu.cn"))
    }

    @Test
    fun directSuccessClearsPreference() {
        val session = session(MockEngine { respond("") })
        session.markDirectFailed("ecard.buct.edu.cn")
        assertTrue(session.shouldPreferVpn("ecard.buct.edu.cn"))
        session.markDirectSucceeded("ecard.buct.edu.cn")
        assertTrue(!session.shouldPreferVpn("ecard.buct.edu.cn"))
    }

    @Test
    fun proxyUrlOnlyRewritesWhenNeeded() {
        val session = session(MockEngine { respond("") })
        // 未标记失败：校内限定域也直连
        assertEquals(
            "https://scs.buct.edu.cn/api",
            session.proxyUrlIfNeeded("https://scs.buct.edu.cn/api"),
        )
        session.markDirectFailed("scs.buct.edu.cn")
        assertTrue(session.proxyUrlIfNeeded("https://scs.buct.edu.cn/api").startsWith("https://w.buct.edu.cn/"))
        // 非校内限定域永不改写
        assertEquals(
            "https://jwglxt.buct.edu.cn/x",
            session.proxyUrlIfNeeded("https://jwglxt.buct.edu.cn/x", forceVpn = true),
        )
    }

    // ---------- 直连受限判定 ----------

    @Test
    fun classifyResponse_detectsCampusBlock() {
        val session = session(MockEngine { respond("") })
        assertEquals(
            DirectAccessVerdict.BLOCKED,
            session.classifyResponse(403, "text/html", null, "https://ecard.buct.edu.cn/", ""),
        )
        assertEquals(
            DirectAccessVerdict.BLOCKED,
            session.classifyResponse(
                200,
                "text/html",
                null,
                "https://ecard.buct.edu.cn/",
                "<html>请使用VPN访问</html>",
            ),
        )
        // 业务页（含业务标记）不算被拦截
        assertEquals(
            DirectAccessVerdict.OK,
            session.classifyResponse(
                200,
                "text/html",
                null,
                "https://ecard.buct.edu.cn/",
                "<html>一卡通查询平台</html>",
            ),
        )
        // JSON 的 403 是业务错误，不是防火墙
        assertEquals(
            DirectAccessVerdict.OK,
            session.classifyResponse(403, "application/json", null, "https://x/y", "{\"error\":1}"),
        )
    }

    @Test
    fun classifyResponse_detectsVpnLoginRedirect() {
        val session = session(MockEngine { respond("") })
        assertEquals(
            DirectAccessVerdict.NEEDS_TICKET,
            session.classifyResponse(
                302,
                null,
                "https://w.buct.edu.cn/login",
                "https://scs.buct.edu.cn/api",
                null,
            ),
        )
        assertEquals(
            DirectAccessVerdict.NEEDS_TICKET,
            session.classifyResponse(
                302,
                null,
                "https://sidportal.buct.edu.cn/login?service=https%3A%2F%2Fw.buct.edu.cn%2Flogin",
                "https://scs.buct.edu.cn/api",
                null,
            ),
        )
    }

    // ---------- 票据换取 ----------

    @Test
    fun ensureTicket_activatesAndPersists() = runTest {
        val vault = InMemorySecureVault()
        val engine = MockEngine { request ->
            when {
                // 激活请求（带 ticket）必须先匹配，否则会被"初始票据"分支吞掉
                request.url.encodedPath == "/login" && request.url.parameters["ticket"] == "ST-123" ->
                    respond(
                        "",
                        HttpStatusCode.OK,
                        headersOf(
                            HttpHeaders.SetCookie to listOf("wengine_vpn_ticketw_buct_edu_cn=active-ticket; Path=/"),
                        ),
                    )
                request.url.encodedPath == "/login" && request.url.host == "w.buct.edu.cn" ->
                    respond(
                        "",
                        HttpStatusCode.OK,
                        headersOf(
                            HttpHeaders.SetCookie to listOf("wengine_vpn_ticketw_buct_edu_cn=init-ticket; Path=/"),
                        ),
                    )
                request.url.host == "sidportal.buct.edu.cn" ->
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(
                            HttpHeaders.Location to listOf("https://w.buct.edu.cn/login?cas_login=true&ticket=ST-123"),
                        ),
                    )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val session = session(engine, vault)
        val ticket = session.ensureTicket()
        assertEquals("active-ticket", ticket)
        // 票据加密落盘（SecureVault 的 webvpn alias）
        assertEquals("active-ticket", vault.get(SecureVault.ALIAS_WEBVPN, "ticket"))
    }

    @Test
    fun ensureTicket_reusesPersistedTicketWithoutNetwork() = runTest {
        val vault = InMemorySecureVault()
        vault.put(SecureVault.ALIAS_WEBVPN, "ticket", "persisted")
        var requests = 0
        val engine = MockEngine { requests++; respond("", HttpStatusCode.NotFound) }
        val session = session(engine, vault)
        assertEquals("persisted", session.ensureTicket())
        assertEquals(0, requests)
    }

    @Test
    fun clearSession_erasesPersistedTicket() = runTest {
        val vault = InMemorySecureVault()
        vault.put(SecureVault.ALIAS_WEBVPN, "ticket", "t")
        val session = session(MockEngine { respond("") }, vault)
        session.markDirectFailed("scs.buct.edu.cn")
        session.clearSession()
        assertNull(vault.get(SecureVault.ALIAS_WEBVPN, "ticket"))
        assertTrue(!session.shouldPreferVpn("scs.buct.edu.cn"))
    }

    @Test
    fun ensureTicket_returnsNullWithoutCredentialsWhenSessionExpired() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/login" && request.url.host == "w.buct.edu.cn") {
                respond(
                    "",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.SetCookie to listOf("wengine_vpn_ticketw_buct_edu_cn=init; Path=/")),
                )
            } else {
                respond(
                    "<html><p id=\"login-page-flowkey\">fk</p><p id=\"login-croypto\">k</p></html>",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/html"),
                )
            }
        }
        val session = session(engine, credentials = null)
        assertNull(session.ensureTicket())
        assertTrue(session.lastFailure().isNotBlank())
    }
}
