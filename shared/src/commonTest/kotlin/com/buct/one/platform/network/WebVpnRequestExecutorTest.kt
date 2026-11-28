package com.buct.one.platform.network

import com.buct.one.platform.buildHttpClient
import com.buct.one.platform.security.InMemorySecureVault
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebVpnRequestExecutorTest {

    private var clock = 1_000_000L

    private fun session(engine: MockEngine, vault: InMemorySecureVault = InMemorySecureVault()) = WebVpnSession(
        httpClient = buildHttpClient(engine, followRedirects = false),
        userAgent = "test-UA",
        vault = vault,
        clock = { clock },
    )

    @Test
    fun campusHostDirectSuccess_keepsOriginalUrl() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += request.url.toString()
            respond("""{"ok":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val webVpn = session(engine)
        val executor = WebVpnRequestExecutor(buildHttpClient(engine, followRedirects = false), webVpn)
        val body = executor.execute("https://scs.buct.edu.cn/api/score") { it.body }
        assertEquals("""{"ok":true}""", body)
        assertEquals(listOf("https://scs.buct.edu.cn/api/score"), requested)
        assertTrue(!webVpn.shouldPreferVpn("scs.buct.edu.cn"))
    }

    @Test
    fun blockedDirectRequest_retriesThroughProxyAndRemembersForFiveMinutes() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += request.url.toString()
            if (request.url.host == "w.buct.edu.cn") {
                respond("""{"ok":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond("<html>请使用VPN访问</html>", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "text/html"))
            }
        }
        val webVpn = session(engine)
        val executor = WebVpnRequestExecutor(buildHttpClient(engine, followRedirects = false), webVpn)

        val first = executor.execute("https://scs.buct.edu.cn/api/score") { it.body }
        assertEquals("""{"ok":true}""", first)
        // 第一次直连被拦 → 记忆 5 分钟
        assertTrue(webVpn.shouldPreferVpn("scs.buct.edu.cn"))
        assertTrue(requested.first().startsWith("https://scs.buct.edu.cn"))
        assertTrue(requested.any { it.startsWith("https://w.buct.edu.cn/") })

        // 记忆期内：直接走代理，不再撞墙
        requested.clear()
        executor.execute("https://scs.buct.edu.cn/api/other") { it.body }
        assertTrue(requested.isNotEmpty())
        assertTrue(requested.all { it.startsWith("https://w.buct.edu.cn/") }, "unexpected: $requested")

        // 5 分钟后恢复直连优先
        clock += WebVpnSession.PREFER_VPN_TTL_MILLIS + 1
        assertTrue(!webVpn.shouldPreferVpn("scs.buct.edu.cn"))
    }

    @Test
    fun nonCampusHost_neverRewrittenEvenWhenForceVpn() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += request.url.toString()
            respond("ok", HttpStatusCode.OK)
        }
        val webVpn = session(engine)
        val executor = WebVpnRequestExecutor(buildHttpClient(engine, followRedirects = false), webVpn)
        executor.execute("https://jwglxt.buct.edu.cn/x", forceVpn = true) { it.body }
        assertEquals(listOf("https://jwglxt.buct.edu.cn/x"), requested)
        assertTrue(!webVpn.shouldPreferVpn("jwglxt.buct.edu.cn"))
    }

    @Test
    fun ticketRotationIsPersistedToVault() = runTest {
        val vault = InMemorySecureVault()
        val engine = MockEngine {
            respond(
                "ok",
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.SetCookie to listOf("wengine_vpn_ticketw_buct_edu_cn=rotated; Path=/"),
                ),
            )
        }
        val webVpn = session(engine, vault)
        val executor = WebVpnRequestExecutor(buildHttpClient(engine, followRedirects = false), webVpn)
        executor.execute("https://scs.buct.edu.cn/api") { it.body }
        assertEquals("rotated", vault.get(com.buct.one.platform.security.SecureVault.ALIAS_WEBVPN, "ticket"))
    }
}
