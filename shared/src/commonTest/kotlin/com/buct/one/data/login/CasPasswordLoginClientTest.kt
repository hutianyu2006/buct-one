package com.buct.one.data.login

import com.buct.one.data.text.urlDecode
import com.buct.one.platform.buildHttpClient
import com.buct.one.platform.crypto.BuiltinCrypto
import com.buct.one.platform.crypto.base64Decode
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CasPasswordLoginClientTest {

    private val aesKey = "MDEyMzQ1Njc4OWFiY2RlZg==" // 0123456789abcdef

    private fun loginPage(errorCode: String = "", captchaUrl: String = ""): String = """
        <html><body>
          <p style="display:none" id="login-page-flowkey">flow-abc</p>
          <p style="display:none" id="login-croypto">$aesKey</p>
          <p style="display:none" id="login-error-code">$errorCode</p>
          <p style="display:none" id="captcha-url">$captchaUrl</p>
        </body></html>
    """.trimIndent()

    private fun parseForm(body: String): Map<String, String> =
        body.split("&").filter { it.isNotEmpty() }.associate {
            val index = it.indexOf('=')
            urlDecode(it.substring(0, index)) to urlDecode(it.substring(index + 1))
        }

    @Test
    fun successFlow_encryptsPasswordAndStoresSessionCookie() = runTest {
        var postedBody = ""
        var accountCookieHeader: String? = null
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/login" && request.method == HttpMethod.Get ->
                    respond(loginPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                request.url.encodedPath == "/login" -> {
                    postedBody = request.body.toByteArray().decodeToString()
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(
                            HttpHeaders.Location to listOf("/account"),
                            HttpHeaders.SetCookie to listOf("SESSION=abc123; Path=/"),
                        ),
                    )
                }
                request.url.encodedPath == "/account" -> {
                    accountCookieHeader = request.headers[HttpHeaders.Cookie]
                    respond("ok", HttpStatusCode.OK)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = buildHttpClient(engine, followRedirects = false, keepCookies = true)
        val outcome = CasPasswordLoginClient(client, userAgent = "test-UA").login("20230001", "p@ss")

        assertEquals(CasLoginOutcome.Success, outcome)
        val fields = parseForm(postedBody)
        assertEquals("UsernamePassword", fields["type"])
        assertEquals("submit", fields["_eventId"])
        assertEquals("20230001", fields["username"])
        assertEquals("flow-abc", fields["execution"])
        assertEquals(aesKey, fields["croypto"])
        val decrypted = BuiltinCrypto.aesEcbDecrypt(
            base64Decode(fields.getValue("password")),
            base64Decode(fields.getValue("croypto")),
        ).decodeToString()
        assertEquals("p@ss", decrypted)
        assertEquals("{}", BuiltinCrypto.aesEcbDecrypt(
            base64Decode(fields.getValue("captcha_payload")),
            base64Decode(aesKey),
        ).decodeToString())

        // 302 已播种会话 Cookie，后续请求必须自动带上
        client.get("https://sidportal.buct.edu.cn/account").bodyAsText()
        assertTrue(accountCookieHeader?.contains("SESSION=abc123") == true)
    }

    @Test
    fun invalidCredentials_returnsFailed() = runTest {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                respond(loginPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            } else {
                respond(
                    loginPage(errorCode = "1030027"),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/html"),
                )
            }
        }
        val client = buildHttpClient(engine, followRedirects = false)
        val outcome = CasPasswordLoginClient(client, userAgent = "test-UA").login("20230001", "wrong")
        assertEquals(CasLoginOutcome.Failed("用户名或密码错误"), outcome)
    }

    @Test
    fun captchaError_returnsCaptchaRequired() = runTest {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                respond(loginPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            } else {
                respond(
                    loginPage(errorCode = "1320007"),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/html"),
                )
            }
        }
        val client = buildHttpClient(engine, followRedirects = false)
        val outcome = CasPasswordLoginClient(client, userAgent = "test-UA").login("20230001", "p@ss")
        assertEquals(CasLoginOutcome.CaptchaRequired(true, "验证码错误，请重新输入"), outcome)
    }

    @Test
    fun missingKey_returnsFailed() = runTest {
        val brokenPage = "<html><body><p id=\"login-page-flowkey\">flow</p></body></html>"
        val engine = MockEngine { respond(brokenPage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }
        val client = buildHttpClient(engine, followRedirects = false)
        val outcome = CasPasswordLoginClient(client, userAgent = "test-UA").login("20230001", "p@ss")
        assertEquals(CasLoginOutcome.Failed("统一认证密钥缺失，请重新登录"), outcome)
    }

    @Test
    fun fetchCaptcha_sharesCookieSessionWithLogin() = runTest {
        var captchaCookie: String? = null
        var loginCookie: String? = null
        var captchaRequested = false
        val png = ByteArray(64) { 0x42 }
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/api/captcha/generate/DEFAULT" -> {
                    captchaRequested = true
                    captchaCookie = request.headers[HttpHeaders.Cookie]
                    respond(
                        png,
                        HttpStatusCode.OK,
                        headersOf(
                            HttpHeaders.ContentType to listOf("image/png"),
                            // 验证码凭据种在会话 Cookie 上，随后的表单提交必须带同一个
                            HttpHeaders.SetCookie to listOf("captcha_session=abc; Path=/"),
                        ),
                    )
                }
                request.url.encodedPath == "/login" && request.method == HttpMethod.Get ->
                    respond(loginPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                request.url.encodedPath == "/login" -> {
                    loginCookie = request.headers[HttpHeaders.Cookie]
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location to listOf("/account")))
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = buildHttpClient(engine, followRedirects = false, keepCookies = true)
        val cas = CasPasswordLoginClient(client, userAgent = "test-UA")

        val bytes = cas.fetchCaptchaPng()
        assertTrue(captchaRequested)
        assertEquals(64, bytes!!.size)
        assertTrue(captchaCookie.isNullOrBlank())

        val outcome = cas.login("20230001", "p@ss", captchaCode = "1234")
        assertEquals(CasLoginOutcome.Success, outcome)
        // 同一个 HttpClient（同一个 Cookie 存储）→ 登录表单带上了验证码会话 Cookie
        assertTrue(loginCookie?.contains("captcha_session=abc") == true)
    }

    @Test
    fun fetchCaptcha_returnsNullOnTinyOrErrorResponse() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/captcha/generate/DEFAULT" -> respond(
                    ByteArray(4),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "image/png"),
                )
                else -> respond("", HttpStatusCode.InternalServerError)
            }
        }
        val client = buildHttpClient(engine, followRedirects = false)
        val cas = CasPasswordLoginClient(client, userAgent = "test-UA")
        assertNull(cas.fetchCaptchaPng())
    }

    @Test
    fun parseInputExecutionFallback() {
        val html = """
            <html><body>
              <input type="hidden" name="execution" value="exec-from-input" />
              <p id="login-croypto">$aesKey</p>
            </body></html>
        """.trimIndent()
        val parsed = CasLoginPage.parse(html)
        assertEquals("exec-from-input", parsed.execution)
        assertEquals(aesKey, parsed.aesKey)
    }
}
