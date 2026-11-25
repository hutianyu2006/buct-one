package com.buct.one.data.source.jwnotice

import com.buct.one.data.login.CasLoginOutcome
import com.buct.one.data.login.CasPasswordLoginClient
import com.buct.one.data.text.urlDecode
import com.buct.one.platform.buildHttpClient
import com.buct.one.platform.session.InMemoryCookieStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JwNoticeGatewayTest {

    private fun parseForm(body: String): Map<String, String> =
        body.split("&").filter { it.isNotEmpty() }.associate {
            val index = it.indexOf('=')
            urlDecode(it.substring(0, index)) to urlDecode(it.substring(index + 1))
        }

    @Test
    fun reminders_sendsExpectedFormAndParsesItems() = runTest {
        var seenBody = ""
        var seenHeaders: Headers? = null
        val json = """{"totalResult":"2","items":[{"xxbt":"调停课提醒","xxnr":"周三 5-6 节停课","cjsj":"2026-11-20 10:00","clzt":"0"}]}"""
        val engine = MockEngine { request ->
            seenBody = request.body.toByteArray().decodeToString()
            seenHeaders = request.headers
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val gateway = JwNoticeGateway(buildHttpClient(engine), userAgent = "test-UA")
        val (total, items) = gateway.fetchRemindersPage(page = 2, pageSize = 10, keyword = "高数")

        assertEquals(2, total)
        assertEquals(1, items.size)
        assertEquals("调停课提醒", items[0].title)

        val form = parseForm(seenBody)
        assertEquals("10", form["queryModel.showCount"])
        assertEquals("2", form["queryModel.currentPage"])
        assertEquals("高数", form["gnmkmc"])
        assertEquals("XMLHttpRequest", seenHeaders?.get("X-Requested-With"))
        assertEquals("https://jwglxt.buct.edu.cn", seenHeaders?.get(HttpHeaders.Origin))
    }

    @Test
    fun loginPageResponse_raisesAuthExpired() = runTest {
        val loginHtml = "<html><body><p id=\"login-page-flowkey\">x</p></body></html>"
        val engine = MockEngine { respond(loginHtml, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }
        val gateway = JwNoticeGateway(buildHttpClient(engine), userAgent = "test-UA")
        assertFailsWith<JwAuthExpiredException> { gateway.fetchAnnouncementsPage() }
    }

    @Test
    fun detail_parsesTitleAndBody() = runTest {
        val html = """
            <html><body>
              <div class="newsdisp">
                <h3 class="text-center">关于选课的通知</h3>
                <h5 class="news_title1"><span>发布人：教务处</span><span>发布时间：2026-11-19</span></h5>
                <div class="news_con"><p>请同学们按时选课。</p></div>
              </div>
            </body></html>
        """.trimIndent()
        val engine = MockEngine { respond(html, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }
        val gateway = JwNoticeGateway(buildHttpClient(engine), userAgent = "test-UA")

        val item = com.buct.one.data.model.JwAnnouncementItem(
            id = "123",
            title = "关于选课的通知",
            publisher = "",
            publishedAt = "",
            isPinned = false,
            isRead = false,
            externalUrl = "",
            inlineHtml = "",
        )
        val detail = gateway.fetchAnnouncementDetail(item)
        assertEquals("关于选课的通知", detail.title)
        assertEquals("教务处", detail.publisher)
        assertTrue(detail.plainText.contains("选课"))
    }

    @Test
    fun login_followsServiceRedirectsAndStoresBothCookies() = runTest {
        val cookieStore = InMemoryCookieStore()
        val loginPage = """
            <html><body>
              <p id="login-page-flowkey">flow-1</p>
              <p id="login-croypto">MDEyMzQ1Njc4OWFiY2RlZg==</p>
            </body></html>
        """.trimIndent()
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get && request.url.host == "sidportal.buct.edu.cn" ->
                    respond(loginPage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                request.method == HttpMethod.Post ->
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(
                            HttpHeaders.Location to listOf("https://jwglxt.buct.edu.cn/sso/jziotlogin?ticket=t1"),
                            HttpHeaders.SetCookie to listOf("SESSION=sid; Path=/"),
                        ),
                    )
                request.url.host == "jwglxt.buct.edu.cn" && request.url.encodedPath == "/sso/jziotlogin" ->
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(
                            HttpHeaders.Location to listOf("/jwglxt/xtgl/index_initMenu.html"),
                            HttpHeaders.SetCookie to listOf("JSESSIONID=jw; Path=/"),
                        ),
                    )
                else -> respond("ok", HttpStatusCode.OK)
            }
        }
        val client = buildHttpClient(engine, followRedirects = false, cookieStore = cookieStore)
        val outcome = CasPasswordLoginClient(client, userAgent = "test-UA").login("20230001", "p@ss")

        assertEquals(CasLoginOutcome.Success, outcome)
        val cookies = cookieStore.load()
        assertTrue(cookies.any { it.name == "SESSION" && it.domain == "sidportal.buct.edu.cn" })
        assertTrue(cookies.any { it.name == "JSESSIONID" && it.domain == "jwglxt.buct.edu.cn" })
    }
}
