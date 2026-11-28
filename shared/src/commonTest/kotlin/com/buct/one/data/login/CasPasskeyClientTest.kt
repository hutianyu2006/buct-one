package com.buct.one.data.login

import com.buct.one.platform.buildHttpClient
import com.buct.one.platform.crypto.BuiltinCrypto
import com.buct.one.platform.crypto.base64Decode
import com.buct.one.platform.crypto.base64Encode
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

class CasPasskeyClientTest {

    private val loginPage = """
        <html><body>
          <p id="login-page-flowkey">flow-passkey</p>
        </body></html>
    """.trimIndent()

    /** 认证选项响应：AES-ECB 加密的 JSON（与校方服务端同一格式）。 */
    private fun encryptedOptions(aesKey: ByteArray): String {
        val json = """
            {"request":{"requestId":"req-1",
            "publicKeyCredentialRequestOptions":{"challenge":"Y2hhbGxlbmdl","rpId":"sidportal.buct.edu.cn","allowCredentials":[]},
            "sessionToken":"sess-1"},
            "actions":{"finish":"webauthn/authenticate/finish"},"success":true}
        """.trimIndent()
        return base64Encode(BuiltinCrypto.aesEcbEncrypt(json.encodeToByteArray(), aesKey))
    }

    @Test
    fun requestAuthentication_decryptsOptionsAndReturnsSignerPayload() = runTest {
        var capturedAesKey: ByteArray? = null
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/login" && request.method == HttpMethod.Get ->
                    respond(loginPage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                request.url.encodedPath == "/webauthn/authenticate" -> {
                    // 服务端只会用 privateKey 头里的 AES 密钥解密请求体，这里直接取回测试用密钥
                    val aesKey = TestKeys.current
                    capturedAesKey = aesKey
                    respond(
                        encryptedOptions(aesKey),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = CasPasskeyClient(
            buildHttpClient(engine, followRedirects = false),
            userAgent = "test-UA",
            aesKeyFactory = { TestKeys.current },
        )
        val outcome = client.requestAuthentication("20230001")
        assertTrue(outcome is PasskeyOutcome.OptionsReady, "expected OptionsReady, got $outcome")
        val ready = outcome as PasskeyOutcome.OptionsReady
        assertEquals("req-1", ready.requestId)
        assertEquals("webauthn/authenticate/finish", ready.finishPath)
        assertEquals("sess-1", ready.sessionToken)
        assertTrue(ready.requestOptionsJson.contains("Y2hhbGxlbmdl"))
        assertTrue(capturedAesKey != null)
    }

    @Test
    fun requestAuthentication_unregisteredAccountReturnsNotRegistered() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/login" -> respond(loginPage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                "/webauthn/authenticate" -> {
                    val encrypted = base64Encode(
                        BuiltinCrypto.aesEcbEncrypt(
                            """{"messages":["username.not.registered"],"success":false}""".encodeToByteArray(),
                            TestKeys.current,
                        ),
                    )
                    respond(encrypted, HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "text/plain"))
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = CasPasskeyClient(
            buildHttpClient(engine, followRedirects = false),
            userAgent = "test-UA",
            aesKeyFactory = { TestKeys.current },
        )
        assertEquals(PasskeyOutcome.NotRegistered, client.requestAuthentication("20230001"))
    }

    @Test
    fun finishAuthentication_submitsAssertionThenCompletesCasLogin() = runTest {
        var finishBody = ""
        var loginFormBody = ""
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "/login" && request.method == HttpMethod.Get ->
                    respond(loginPage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                request.url.encodedPath == "/webauthn/authenticate" ->
                    respond(encryptedOptions(TestKeys.current), HttpStatusCode.OK)
                request.url.encodedPath == "/webauthn/authenticate/finish" -> {
                    finishBody = request.body.toByteArray().decodeToString()
                    respond(
                        """{"success":true,"sessionToken":"auth-token"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                request.url.encodedPath == "/login" -> {
                    loginFormBody = request.body.toByteArray().decodeToString()
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location to listOf("/account")),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = CasPasskeyClient(
            buildHttpClient(engine, followRedirects = false),
            userAgent = "test-UA",
            aesKeyFactory = { TestKeys.current },
        )
        val options = client.requestAuthentication("20230001") as PasskeyOutcome.OptionsReady
        val credential = """{"id":"cred","rawId":"cred","type":"public-key","response":{}}"""
        val outcome = client.finishAuthentication(
            requestId = options.requestId,
            finishPath = options.finishPath,
            credentialJson = credential,
            sessionToken = options.sessionToken,
            username = "20230001",
        )
        assertEquals(PasskeyOutcome.Success, outcome)
        assertTrue(finishBody.contains("\"requestId\":\"req-1\""))
        assertTrue(finishBody.contains("\"sessionToken\":\"sess-1\""))
        assertTrue(finishBody.contains("\"credential\":"))
        // CAS 登录用 _eventId=validateWebAuthn 与断言返回的 token
        assertTrue(loginFormBody.contains("_eventId=validateWebAuthn"))
        assertTrue(loginFormBody.contains("token=auth-token"))
        assertTrue(loginFormBody.contains("execution=flow-passkey"))
    }

    @Test
    fun finishAuthentication_rejectedAssertionFails() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/login" -> respond(loginPage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                "/webauthn/authenticate" ->
                    respond(encryptedOptions(TestKeys.current), HttpStatusCode.OK)
                "/webauthn/authenticate/finish" ->
                    respond("""{"success":false}""", HttpStatusCode.BadRequest)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = CasPasskeyClient(
            buildHttpClient(engine, followRedirects = false),
            userAgent = "test-UA",
            aesKeyFactory = { TestKeys.current },
        )
        val options = client.requestAuthentication("20230001") as PasskeyOutcome.OptionsReady
        val outcome = client.finishAuthentication(
            options.requestId,
            options.finishPath,
            """{"id":"x"}""",
            options.sessionToken,
            "20230001",
        )
        assertEquals(PasskeyOutcome.Failed("通行密钥验证失败，请重试"), outcome)
    }

    @Test
    fun finishAuthentication_rejectsForeignHost() = runTest {
        val client = CasPasskeyClient(
            buildHttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }, followRedirects = false),
            userAgent = "test-UA",
            aesKeyFactory = { TestKeys.current },
        )
        val outcome = client.finishAuthentication("r", "https://evil.example.com/finish", "{}", null, "20230001")
        assertEquals(PasskeyOutcome.Failed("通行密钥提交地址无效"), outcome)
    }

    /**
     * MockEngine 无法解开真实请求头里的 RSA 密文，因此用注入的 aesKeyFactory 桥接：
     * 客户端与服务端 mock 使用同一把测试 AES 密钥（生产实现为安全随机 16 字节）。
     */
    private object TestKeys {
        var current: ByteArray = ByteArray(16) { 0x2A }
    }
}