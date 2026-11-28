package com.buct.one.data.login

import com.buct.one.platform.buildHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CasLoginControllerTest {

    private val loginPage = """
        <html><body>
          <p id="login-page-flowkey">flow-1</p>
          <p id="login-croypto">MDEyMzQ1Njc4OWFiY2RlZg==</p>
        </body></html>
    """.trimIndent()

    private fun controller(postStatus: HttpStatusCode, postBody: String): CasLoginController {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                respond(loginPage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            } else {
                respond(postBody, postStatus, headersOf(HttpHeaders.ContentType, "text/html"))
            }
        }
        return CasLoginController(
            CasPasswordLoginClient(
                buildHttpClient(engine, followRedirects = false),
                userAgent = "test-UA",
            ),
        )
    }

    @Test
    fun success_movesToSuccessState() = runTest {
        val controller = controller(HttpStatusCode.Found, "")
        controller.login("20230001", "p@ss")
        assertEquals(LoginState.Success("20230001"), controller.state.value)
    }

    @Test
    fun invalidCredentials_movesToFailedState() = runTest {
        val controller = controller(HttpStatusCode.OK, "<p id=\"login-error-code\">1030027</p>")
        controller.login("20230001", "wrong")
        assertEquals(LoginState.Failed("用户名或密码错误"), controller.state.value)
    }

    @Test
    fun captcha_movesToCaptchaState() = runTest {
        val controller = controller(
            HttpStatusCode.OK,
            "<p id=\"login-croypto\">MDEyMzQ1Njc4OWFiY2RlZg==</p><p id=\"captcha-url\">/captcha</p>",
        )
        controller.login("20230001", "p@ss")
        val state = controller.state.value
        assertTrue(state is LoginState.CaptchaRequired)
    }

    @Test
    fun captchaState_carriesPngBytesForUiDecoding() = runTest {
        val png = ByteArray(48) { 0x11 }
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/api/captcha/generate/DEFAULT" ->
                    respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                request.method == HttpMethod.Get ->
                    respond(loginPage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                else -> respond(
                    "<p id=\"login-croypto\">MDEyMzQ1Njc4OWFiY2RlZg==</p><p id=\"captcha-url\">/captcha</p>",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/html"),
                )
            }
        }
        val controller = CasLoginController(
            CasPasswordLoginClient(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA"),
        )
        controller.login("20230001", "p@ss")
        val state = controller.state.value as? LoginState.CaptchaRequired
        assertTrue(state != null)
        assertEquals(48, state!!.pngBytes.size)
    }

    @Test
    fun captchaState_pngEmptyWhenFetchFails() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/api/captcha/generate/DEFAULT" ->
                    respond("", HttpStatusCode.InternalServerError)
                request.method == HttpMethod.Get ->
                    respond(loginPage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                else -> respond(
                    "<p id=\"login-croypto\">MDEyMzQ1Njc4OWFiY2RlZg==</p><p id=\"captcha-url\">/captcha</p>",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "text/html"),
                )
            }
        }
        val controller = CasLoginController(
            CasPasswordLoginClient(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA"),
        )
        controller.login("20230001", "p@ss")
        val state = controller.state.value as? LoginState.CaptchaRequired
        assertTrue(state != null)
        assertEquals(0, state!!.pngBytes.size)
    }
}