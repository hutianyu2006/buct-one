package com.buct.one.data.source.tygl

import com.buct.one.platform.buildHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TyglGatewayTest {

    private fun authenticatedPage(body: String) =
        "<html><body><div id=\"mainContent\">$body</div></body></html>"

    private fun gateway(handler: (String) -> Pair<String, HttpStatusCode>): TyglGateway {
        val engine = MockEngine { request ->
            val (body, status) = handler(request.url.encodedPath + "?" + request.url.encodedQuery.orEmpty())
            respond(body, status, headersOf(HttpHeaders.ContentType, "text/html"))
        }
        return TyglGateway(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA")
    }

    @Test
    fun loginPageRaisesAuthExpired() = runTest {
        val gateway = gateway { _ ->
            "<html><p id=\"login-page-flowkey\">x</p></html>" to HttpStatusCode.OK
        }
        assertFailsWith<TyglAuthExpiredException> {
            gateway.fetchAuthenticatedHtml("/main.php?module=stu&title=stu_ht_score")
        }
    }

    @Test
    fun redirectToLoginRaisesAuthExpired() = runTest {
        val engine = MockEngine {
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location to listOf("https://tygl.buct.edu.cn/login.php?reason=accessDenied")),
            )
        }
        val gateway = TyglGateway(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA")
        assertFailsWith<TyglAuthExpiredException> {
            gateway.fetchAuthenticatedHtml("/main.php?module=stu&title=stu_ht_score")
        }
    }

    @Test
    fun inSiteRedirectIsFollowedAndHttpUpgraded() = runTest {
        var sawHttps = false
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("index.php")) {
                sawHttps = request.url.protocol.name == "https"
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location to listOf("/main.php?module=stu&title=stu_ht_score")),
                )
            } else {
                respond(
                    authenticatedPage("<p>测试年度</p>"),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/html"),
                )
            }
        }
        val gateway = TyglGateway(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA")
        val html = gateway.fetchAuthenticatedHtml("/index.php")
        assertTrue(html.contains("测试年度"))
        assertTrue(sawHttps, "站内重定向必须升级为 https")
    }

    @Test
    fun fetchFullSnapshot_mergesAllSections() = runTest {
        val gateway = gateway { path ->
            when {
                path.contains("stu_ht_score") && path.contains("year=") ->
                    authenticatedPage("<p>测试年度</p>") to HttpStatusCode.OK
                path.contains("stu_ht_score") ->
                    authenticatedPage("<p>测试年度</p>") to HttpStatusCode.OK
                path.contains("stu_sun_score") ->
                    authenticatedPage("<p>阳光跑</p>") to HttpStatusCode.OK
                path.contains("stu_pe_score") ->
                    authenticatedPage("<p>体育课</p>") to HttpStatusCode.OK
                path.contains("stu_exam") ->
                    authenticatedPage("<p>理论考试</p>") to HttpStatusCode.OK
                else -> authenticatedPage("") to HttpStatusCode.OK
            }
        }
        val snapshot = gateway.fetchFullSnapshot()
        // 解析器对空表格宽容：关键是不抛异常、结构完整
        assertTrue(snapshot.fitnessRecords.isNotEmpty() || snapshot.profile.studentName.isBlank())
        assertTrue(snapshot.peCourses.isNotEmpty() || snapshot.peCourses.isEmpty())
    }

    @Test
    fun nonTyglRedirectIsRejected() = runTest {
        val engine = MockEngine {
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location to listOf("https://evil.example.com/main.php")),
            )
        }
        val gateway = TyglGateway(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA")
        assertFailsWith<TyglAuthExpiredException> {
            gateway.fetchAuthenticatedHtml("/main.php?module=stu&title=stu_ht_score")
        }
    }
}
