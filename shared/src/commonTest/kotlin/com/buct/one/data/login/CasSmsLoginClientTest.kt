package com.buct.one.data.login

import com.buct.one.data.text.urlDecode
import com.buct.one.platform.buildHttpClient
import com.buct.one.platform.session.InMemoryCookieStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CasSmsLoginClientTest {

    private val loginPage = """
        <html><body><p id="login-page-flowkey">flow-sms</p></body></html>
    """.trimIndent()

    /** 记录每次请求的 Device-Id，用于验证「三步同一指纹」这一硬约束。 */
    private class Recorder {
        val deviceIds = mutableListOf<String>()
        val csrfKeys = mutableListOf<String>()
        var postedForm = ""
        var smsSendPath = ""
    }

    private fun client(
        recorder: Recorder,
        captchaBytes: ByteArray = ByteArray(64) { 0x89.toByte() },
        sendBody: String = """{"result":true}""",
        verifyBody: String = """{"code":200}""",
        loginStatus: HttpStatusCode = HttpStatusCode.Found,
    ): CasSmsLoginClient {
        val engine = MockEngine { request ->
            recorder.deviceIds += request.headers["Device-Id"].orEmpty()
            recorder.csrfKeys += request.headers["Csrf-Key"].orEmpty()
            when {
                request.url.encodedPath.endsWith("/sms/publicNoToken/generate") ->
                    respond(captchaBytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                request.url.encodedPath.contains("sendCheckCaptcha") -> {
                    recorder.smsSendPath = request.url.encodedPath
                    respond(sendBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                request.url.encodedPath.endsWith("/sms/checkTokenResult") ->
                    respond(verifyBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                request.method == HttpMethod.Get && request.url.encodedPath == "/login" ->
                    respond(loginPage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                request.url.encodedPath == "/login" -> {
                    recorder.postedForm = request.body.toByteArray().decodeToString()
                    respond("", loginStatus, headersOf(HttpHeaders.Location, "/account"))
                }
                else -> respond("ok", HttpStatusCode.OK)
            }
        }
        return CasSmsLoginClient(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA")
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split("&").filter { it.isNotEmpty() }.associate {
            val index = it.indexOf('=')
            urlDecode(it.substring(0, index)) to urlDecode(it.substring(index + 1))
        }

    @Test
    fun fetchCaptcha_returnsPngBytes() = runTest {
        val recorder = Recorder()
        val outcome = client(recorder).fetchCaptcha("20230001")
        assertTrue(outcome is CasSmsOutcome.CaptchaRequired)
        assertEquals(64, (outcome as CasSmsOutcome.CaptchaRequired).pngBytes.size)
        assertEquals(CasSmsLoginClient.FIXED_DEVICE_ID, recorder.deviceIds.single())
    }

    @Test
    fun fullFlow_reusesSameDeviceIdAndSendsPlainSmsCode() = runTest {
        val recorder = Recorder()
        val client = client(recorder)

        assertTrue(client.fetchCaptcha("20230001") is CasSmsOutcome.CaptchaRequired)
        val sent = client.sendSmsCode("20230001", "abcd")
        assertEquals(CasSmsOutcome.SmsSent(120), sent)
        assertEquals(CasSmsOutcome.Success, client.loginWithSmsCode("20230001", "123456"))

        // 业务号必须是 0008
        assertTrue(recorder.smsSendPath.endsWith("/DEFAULT/abcd/20230001/0008"), recorder.smsSendPath)
        // 三个短信 API（生成图形码 / 发短信 / 校验）必须共用同一个固定 Device-Id；
        // 登录页与表单提交按校方协议不带该头（与 Android 实现一致）。
        val smsApiDeviceIds = recorder.deviceIds.filter { it.isNotBlank() }
        assertEquals(3, smsApiDeviceIds.size)
        assertEquals(setOf(CasSmsLoginClient.FIXED_DEVICE_ID), smsApiDeviceIds.toSet())
        // 同一个 Csrf-Key 贯穿（同一实例缓存）
        assertEquals(1, recorder.csrfKeys.filter { it.isNotBlank() }.toSet().size)

        val form = parseForm(recorder.postedForm)
        assertEquals("smsLogin", form["type"])
        assertEquals("123456", form["password"]) // 短信码明文
        assertEquals("flow-sms", form["execution"])
        assertEquals("submit", form["_eventId"])
        assertEquals("20230001", form["username"])
    }

    @Test
    fun sendSms_mapsErrorCodes() = runTest {
        val recorder = Recorder()
        val invalidCaptcha = client(recorder, sendBody = """{"errorCode":"2180000"}""")
        assertEquals(CasSmsOutcome.CaptchaInvalid, invalidCaptcha.sendSmsCode("20230001", "abcd"))

        val tooOften = client(Recorder(), sendBody = """{"errorCode":"13002022"}""")
        val rejected = tooOften.sendSmsCode("20230001", "abcd")
        assertTrue(rejected is CasSmsOutcome.SmsSendRejected)
    }

    @Test
    fun verify_mapsWrongCode() = runTest {
        val recorder = Recorder()
        val outcome = client(recorder, verifyBody = """{"errorCode":"1320009"}""")
            .loginWithSmsCode("20230001", "000000")
        assertEquals(CasSmsOutcome.SmsCodeInvalid("验证码有误，请重新输入"), outcome)
    }

    @Test
    fun shortCaptchaIsRejectedLocally() = runTest {
        val outcome = client(Recorder()).sendSmsCode("20230001", "ab")
        assertEquals(CasSmsOutcome.CaptchaInvalid, outcome)
    }

    @Test
    fun shortSmsCodeIsRejectedLocally() = runTest {
        val outcome = client(Recorder()).loginWithSmsCode("20230001", "12")
        assertTrue(outcome is CasSmsOutcome.SmsCodeInvalid)
    }
}
