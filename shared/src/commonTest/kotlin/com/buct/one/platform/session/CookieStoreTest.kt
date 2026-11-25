package com.buct.one.platform.session

import com.buct.one.data.login.CasLoginOutcome
import com.buct.one.data.login.CasPasswordLoginClient
import com.buct.one.platform.buildHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CookieStoreTest {

    @Test
    fun cookiesSurviveClientRecreation() = runTest {
        val cookieStore = InMemoryCookieStore()
        val setCookieEngine = MockEngine { request ->
            if (request.url.encodedPath == "/login" && request.method == HttpMethod.Get) {
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.SetCookie to listOf("SESSION=abc123; Path=/")),
                )
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }
        buildHttpClient(setCookieEngine, followRedirects = false, cookieStore = cookieStore)
            .get("https://sidportal.buct.edu.cn/login")

        assertEquals(1, cookieStore.load().size)

        // 模拟重新创建客户端（例如换一次登录流程），Cookie 必须从 store 恢复
        var cookieHeader: String? = null
        val secondEngine = MockEngine { request ->
            cookieHeader = request.headers[HttpHeaders.Cookie]
            respond("ok", HttpStatusCode.OK)
        }
        buildHttpClient(secondEngine, cookieStore = cookieStore)
            .get("https://sidportal.buct.edu.cn/account")

        assertTrue(cookieHeader?.contains("SESSION=abc123") == true)
    }

    @Test
    fun cookiesAreDomainScoped() = runTest {
        val cookieStore = InMemoryCookieStore()
        cookieStore.save(listOf(StoredCookie(name = "SESSION", value = "x", domain = "sidportal.buct.edu.cn")))

        var cookieHeader: String? = null
        val engine = MockEngine { request ->
            cookieHeader = request.headers[HttpHeaders.Cookie]
            respond("ok", HttpStatusCode.OK)
        }
        val client = buildHttpClient(engine, cookieStore = cookieStore)
        client.get("https://jwglxt.buct.edu.cn/").bodyAsText()
        assertFalse(cookieHeader?.contains("SESSION=x") == true)

        client.get("https://sidportal.buct.edu.cn/account").bodyAsText()
        assertTrue(cookieHeader?.contains("SESSION=x") == true)
    }

    @Test
    fun casLogin_usesSharedCookieStore() = runTest {
        val cookieStore = InMemoryCookieStore()
        val loginPage = """
            <html><body>
              <p id="login-page-flowkey">flow-1</p>
              <p id="login-croypto">MDEyMzQ1Njc4OWFiY2RlZg==</p>
            </body></html>
        """.trimIndent()
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                respond(loginPage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            } else {
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.SetCookie to listOf("SESSION=logged-in; Path=/")),
                )
            }
        }
        val client = buildHttpClient(engine, followRedirects = false, cookieStore = cookieStore)
        val outcome = CasPasswordLoginClient(client, userAgent = "test-UA").login("20230001", "p@ss")
        assertEquals(CasLoginOutcome.Success, outcome)
        assertTrue(cookieStore.load().any { it.name == "SESSION" && it.value == "logged-in" })
    }
}

private suspend fun io.ktor.client.statement.HttpResponse.bodyAsText(): String =
    io.ktor.client.statement.bodyAsText(this)
