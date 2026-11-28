package com.buct.one.data.login

import com.buct.one.data.text.resolveUrl
import com.buct.one.data.text.urlHostOf
import com.buct.one.data.text.urlSchemeOf
import com.buct.one.platform.crypto.BuiltinCrypto
import com.buct.one.platform.crypto.base64Decode
import com.buct.one.platform.crypto.base64Encode
import com.buct.one.platform.secureRandomBytes
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

/** 通行密钥（WebAuthn）各阶段结果，双端共用。 */
sealed interface PasskeyOutcome {
    /**
     * 已取得认证选项：UI 交给平台认证器做断言签名，再用 [CasPasskeyClient.finishAuthentication] 提交。
     *
     * @param requestOptionsJson 原样透传给平台认证器的 publicKeyCredentialRequestOptions。
     */
    data class OptionsReady(
        val requestId: String,
        val requestOptionsJson: String,
        val finishPath: String,
        val sessionToken: String?,
    ) : PasskeyOutcome

    /** 注册（创建通行密钥）：把 [creationOptionsJson] 交给平台认证器。 */
    data class CreationOptionsReady(
        val requestId: String,
        val creationOptionsJson: String,
        val finishPath: String,
        val sessionToken: String?,
    ) : PasskeyOutcome

    /** 断言提交成功，会话 Cookie 由注入的 [HttpClient] 持有。 */
    data object Success : PasskeyOutcome

    /** 注册成功（未登录）。 */
    data object Registered : PasskeyOutcome

    /** 该学号尚未注册通行密钥（界面应引导先用密码登录并创建）。 */
    data object NotRegistered : PasskeyOutcome

    data class Failed(val message: String) : PasskeyOutcome
}

/**
 * 北化统一身份认证「通行密钥（WebAuthn / FIDO2）」协议客户端，双端共用。
 *
 * 协议（与 Android BuctWebAuthnManager 逐项对齐）：
 * 1. GET /login?service=... 建立会话并取 login-page-flowkey；
 * 2. POST /webauthn/authenticate：body 为 AES-ECB 加密的 {"username":"学号"}，
 *    头部 hasCrypto=true 与 privateKey（RSA-PKCS#1 加密的 AES 密钥）；
 *    响应同样是 AES-ECB 密文，解出 requestId / publicKeyCredentialRequestOptions / actions.finish；
 * 3. 平台认证器完成断言签名（[PlatformPasskeySigner]）；
 * 4. POST {finish} 提交 {requestId, credential, sessionToken}；
 * 5. 用返回的 sessionToken 以 _eventId=validateWebAuthn 完成 CAS 登录，跟随票据到业务系统。
 *
 * 安全红线：私钥只在平台安全区（Android Keystore / iOS Keychain），
 * 本类只处理公钥协议字段；断言失败不得把凭据或密钥写日志。
 */
class CasPasskeyClient(
    private val httpClient: HttpClient,
    private val userAgent: String,
    private val portalBaseUrl: String = CasPasswordLoginClient.DEFAULT_PORTAL,
    private val serviceUrl: String = CasPasswordLoginClient.DEFAULT_SERVICE,
    private val rsaPublicKeyDer: ByteArray = DEFAULT_RSA_PUBLIC_KEY_DER,
    private val maxRedirects: Int = 8,
    /** AES 会话密钥生成器（生产为安全随机 16 字节；单测注入固定密钥以便验证解密路径）。 */
    private val aesKeyFactory: () -> ByteArray = { secureRandomBytes(16) },
) {

    private var lastExecution: String? = null

    /** 阶段一：建会话并请求认证选项。 */
    suspend fun requestAuthentication(username: String): PasskeyOutcome {
        val name = username.trim()
        if (name.isEmpty()) return PasskeyOutcome.Failed("请输入学号")
        val loginUrl = loginUrl()
        val html = try {
            httpClient.get(loginUrl) { header(HttpHeaders.UserAgent, userAgent) }.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return PasskeyOutcome.Failed("无法连接统一认证，请检查网络")
        }
        lastExecution = CasLoginPage.parse(html).execution

        val aesKey = aesKeyFactory()
        val aesKeyB64 = base64Encode(aesKey)
        val plainBody = "{\"username\":\"${escapeJson(name)}\"}"
        val encryptedBody = base64Encode(BuiltinCrypto.aesEcbEncrypt(plainBody.encodeToByteArray(), aesKey))
        val encryptedAesKey = try {
            base64Encode(BuiltinCrypto.rsaEncryptPkcs1(rsaPublicKeyDer, aesKeyB64.encodeToByteArray()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return PasskeyOutcome.Failed("通行密钥请求加密失败")
        }

        val response = try {
            httpClient.post("$portalBaseUrl/webauthn/authenticate") {
                header(HttpHeaders.UserAgent, userAgent)
                header("X-CSRF-TOKEN", "csrfToken")
                header("hasCrypto", "true")
                header("privateKey", encryptedAesKey)
                header(HttpHeaders.Accept, "*/*")
                header(HttpHeaders.Origin, portalBaseUrl)
                header(HttpHeaders.Referrer, loginUrl)
                contentType(ContentType.Application.Json)
                setBody(encryptedBody)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return PasskeyOutcome.Failed("请求通行密钥失败，请检查网络")
        }

        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val reason = decryptFirstMessage(aesKey, text)
            return when (reason) {
                "username.not.registered" -> PasskeyOutcome.NotRegistered
                "operation.not.allowed", "operation not allowed" ->
                    PasskeyOutcome.Failed("当前操作不被允许，请稍后重试")
                null -> PasskeyOutcome.Failed("通行密钥请求失败（${response.status.value}）")
                else -> PasskeyOutcome.Failed("通行密钥请求失败：$reason")
            }
        }
        val decrypted = try {
            BuiltinCrypto.aesEcbDecrypt(base64Decode(text.trim()), aesKey).decodeToString()
        } catch (_: Exception) {
            text // 后端可能回退明文 JSON
        }
        return parseOptions(decrypted)
    }

    /** 阶段二：提交断言并完成 CAS 登录（成功后会话 Cookie 由 [httpClient] 持有）。 */
    suspend fun finishAuthentication(
        requestId: String,
        finishPath: String,
        credentialJson: String,
        sessionToken: String?,
        username: String,
    ): PasskeyOutcome {
        val finishUrl = resolveFinishUrl(finishPath) ?: return PasskeyOutcome.Failed("通行密钥提交地址无效")
        val response = try {
            httpClient.post(finishUrl) {
                header(HttpHeaders.UserAgent, userAgent)
                header("X-CSRF-TOKEN", "csrfToken")
                header(HttpHeaders.Accept, "*/*")
                header(HttpHeaders.Origin, portalBaseUrl)
                header(HttpHeaders.Referrer, loginUrl())
                contentType(ContentType.Application.Json)
                setBody(buildFinishBody(requestId, credentialJson, sessionToken))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return PasskeyOutcome.Failed("通行密钥提交失败，请检查网络")
        }
        val text = response.bodyAsText()
        if (!(response.status.value in 200..299 && text.contains("\"success\":true"))) {
            return PasskeyOutcome.Failed("通行密钥验证失败，请重试")
        }
        val authToken = extractJsonString(text, "sessionToken")
        if (authToken.isNullOrBlank()) return PasskeyOutcome.Failed("通行密钥认证未返回会话令牌")
        return completeLogin(username, authToken)
    }

    /** 阶段一（注册）：用当前 sidportal 会话请求创建选项。 */
    suspend fun requestRegistration(
        sidCookie: String,
        username: String,
        displayName: String,
        nickname: String,
    ): PasskeyOutcome {
        val form = buildString {
            append("username=").append(encodeForm(username))
            append("&displayName=").append(encodeForm(displayName))
            append("&credentialNickname=").append(encodeForm(nickname))
            append("&requireResidentKey=false&sessionToken=null")
        }
        val response = try {
            httpClient.post("$portalBaseUrl/webauthn/register") {
                header(HttpHeaders.UserAgent, userAgent)
                header("X-CSRF-TOKEN", "csrfToken")
                header(HttpHeaders.Accept, "*/*")
                header(HttpHeaders.Origin, portalBaseUrl)
                header(HttpHeaders.Referrer, "$portalBaseUrl/account")
                if (sidCookie.isNotBlank()) header(HttpHeaders.Cookie, sidCookie)
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(form)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return PasskeyOutcome.Failed("请求创建通行密钥失败，请检查网络")
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val reason = extractFirstMessage(text)
            return if (reason != null) {
                PasskeyOutcome.Failed("创建通行密钥失败：$reason")
            } else {
                PasskeyOutcome.Failed("创建通行密钥请求失败（${response.status.value}）")
            }
        }
        return parseCreationOptions(text)
    }

    /**
     * 注册前预热「通行密钥管理页」上下文（网页端也是先进「个人中心 → 安全设置」再创建）。
     *
     * 金智在缺少该上下文时会以 `400 operation not allowed` 拒绝 `/webauthn/register`；
     * 预热失败不阻塞后续流程（由服务端最终判定），因此这里吞掉异常。
     */
    suspend fun warmUpAccountContext(sidCookie: String) {
        try {
            httpClient.get("$portalBaseUrl/account") {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
                header(HttpHeaders.Referrer, "$portalBaseUrl/login")
                if (sidCookie.isNotBlank()) header(HttpHeaders.Cookie, sidCookie)
            }.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 预热是尽力而为：失败时继续走 register，让服务端给出明确原因
        }
    }

    /** 阶段二（注册）：提交创建结果。 */
    suspend fun finishRegistration(
        sidCookie: String,
        requestId: String,
        finishPath: String,
        credentialJson: String,
        sessionToken: String?,
    ): PasskeyOutcome {
        val finishUrl = resolveFinishUrl(finishPath) ?: return PasskeyOutcome.Failed("创建通行密钥提交地址无效")
        val response = try {
            httpClient.post(finishUrl) {
                header(HttpHeaders.UserAgent, userAgent)
                header("X-CSRF-TOKEN", "csrfToken")
                header(HttpHeaders.Accept, "*/*")
                header(HttpHeaders.Origin, portalBaseUrl)
                header(HttpHeaders.Referrer, "$portalBaseUrl/account")
                if (sidCookie.isNotBlank()) header(HttpHeaders.Cookie, sidCookie)
                contentType(ContentType.Application.Json)
                setBody(buildFinishBody(requestId, credentialJson, sessionToken))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return PasskeyOutcome.Failed("提交创建失败，请检查网络")
        }
        val text = response.bodyAsText()
        val success = response.status.value in 200..299 && text.contains("\"success\":true")
        return if (success) PasskeyOutcome.Registered else PasskeyOutcome.Failed("创建通行密钥验证失败，请重试")
    }

    // ---------- 内部 ----------

    private fun loginUrl(): String = "$portalBaseUrl/login?service=${encodeQuery(serviceUrl)}"

    private suspend fun completeLogin(username: String, sessionToken: String): PasskeyOutcome {
        val url = loginUrl()
        val execution = lastExecution ?: try {
            CasLoginPage.parse(
                httpClient.get(url) { header(HttpHeaders.UserAgent, userAgent) }.bodyAsText(),
            ).execution
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return PasskeyOutcome.Failed("统一认证会话已失效，请重新开始")

        val response = try {
            httpClient.submitForm(
                url = url,
                formParameters = Parameters.build {
                    append("username", username.trim())
                    append("_eventId", "validateWebAuthn")
                    append("geolocation", "")
                    append("execution", execution)
                    append("token", sessionToken)
                },
                encodeInQuery = false,
            ) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Origin, portalBaseUrl)
                header(HttpHeaders.Referrer, url)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PasskeyOutcome.Failed("登录跳转失败，请重试")
        }
        if (response.status.value !in 300..399) {
            return PasskeyOutcome.Failed("通行密钥认证未完成（${response.status.value}）")
        }
        // 票据跳转失败不影响"认证已通过"，错误留给后续业务请求。
        runCatching { followTicket(response.headers[HttpHeaders.Location]) }
        return PasskeyOutcome.Success
    }

    private suspend fun followTicket(initialLocation: String?) {
        var current = resolveUrl(loginUrl(), initialLocation ?: return)
        repeat(maxRedirects) {
            val host = urlHostOf(current)?.lowercase() ?: return
            if (host != "buct.edu.cn" && !host.endsWith(".buct.edu.cn")) return
            val target = if (urlSchemeOf(current) == "http") current.replaceFirst("http://", "https://") else current
            val response = httpClient.get(target) { header(HttpHeaders.UserAgent, userAgent) }
            if (response.status.value !in 300..399) return
            current = resolveUrl(target, response.headers[HttpHeaders.Location] ?: return)
        }
    }

    private fun parseOptions(json: String): PasskeyOutcome {
        val requestId = extractJsonString(json, "requestId")
            ?: return PasskeyOutcome.Failed("通行密钥响应解析失败")
        val options = extractJsonObject(json, "publicKeyCredentialRequestOptions")
            ?: return PasskeyOutcome.Failed("通行密钥响应解析失败")
        val finish = extractJsonString(json, "finish")
            ?: return PasskeyOutcome.Failed("通行密钥响应解析失败")
        val sessionToken = extractJsonString(json, "sessionToken")?.takeIf { it.isNotBlank() && it != "null" }
        return PasskeyOutcome.OptionsReady(requestId, options, finish, sessionToken)
    }

    private fun parseCreationOptions(json: String): PasskeyOutcome {
        val requestId = extractJsonString(json, "requestId")
            ?: return PasskeyOutcome.Failed("创建通行密钥响应解析失败")
        val options = extractJsonObject(json, "publicKeyCredentialCreationOptions")
            ?: return PasskeyOutcome.Failed("创建通行密钥响应解析失败")
        val finish = extractJsonString(json, "finish")
            ?: return PasskeyOutcome.Failed("创建通行密钥响应解析失败")
        val sessionToken = extractJsonString(json, "sessionToken")?.takeIf { it.isNotBlank() && it != "null" }
        return PasskeyOutcome.CreationOptionsReady(requestId, options, finish, sessionToken)
    }

    private fun resolveFinishUrl(path: String): String? {
        if (path.isBlank()) return null
        val resolved = if (path.startsWith("http")) path else "$portalBaseUrl/${path.trimStart('/')}"
        val host = urlHostOf(resolved)?.lowercase() ?: return null
        if (host != "buct.edu.cn" && !host.endsWith(".buct.edu.cn")) return null
        return resolved
    }

    private fun buildFinishBody(requestId: String, credentialJson: String, sessionToken: String?): String {
        val token = sessionToken
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { "\"${escapeJson(it)}\"" }
            ?: "null"
        return "{\"requestId\":\"${escapeJson(requestId)}\",\"credential\":$credentialJson,\"sessionToken\":$token}"
    }

    private fun decryptFirstMessage(aesKey: ByteArray, text: String): String? {
        val decrypted = try {
            BuiltinCrypto.aesEcbDecrypt(base64Decode(text.trim()), aesKey).decodeToString()
        } catch (_: Exception) {
            text
        }
        return extractFirstMessage(decrypted)
    }

    private fun extractFirstMessage(json: String): String? {
        val index = json.indexOf("\"messages\"")
        if (index < 0) return null
        val arrayStart = json.indexOf('[', index)
        if (arrayStart < 0) return null
        val valueStart = json.indexOf('"', arrayStart + 1)
        if (valueStart < 0) return null
        val valueEnd = json.indexOf('"', valueStart + 1)
        if (valueEnd <= valueStart) return null
        return json.substring(valueStart + 1, valueEnd).takeIf { it.isNotBlank() }
    }

    private fun extractJsonString(json: String, field: String): String? {
        val marker = "\"$field\""
        val index = json.indexOf(marker)
        if (index < 0) return null
        val colon = json.indexOf(':', index + marker.length)
        if (colon < 0) return null
        var start = colon + 1
        while (start < json.length && json[start].isWhitespace()) start++
        if (start >= json.length) return null
        if (json[start] == '"') {
            val end = json.indexOf('"', start + 1)
            return if (end > start) json.substring(start + 1, end) else null
        }
        var end = start
        while (end < json.length && json[end] != ',' && json[end] != '}') end++
        return json.substring(start, end).trim().takeIf { it.isNotEmpty() }
    }

    /** 取出嵌套对象原文（把 publicKeyCredentialRequestOptions 原样交给平台认证器）。 */
    private fun extractJsonObject(json: String, field: String): String? {
        val marker = "\"$field\""
        val index = json.indexOf(marker)
        if (index < 0) return null
        val colon = json.indexOf(':', index + marker.length)
        if (colon < 0) return null
        var start = colon + 1
        while (start < json.length && json[start].isWhitespace()) start++
        if (start >= json.length || json[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (position in start until json.length) {
            val char = json[position]
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> depth++
                char == '}' -> {
                    depth--
                    if (depth == 0) return json.substring(start, position + 1)
                }
            }
        }
        return null
    }

    private fun escapeJson(value: String): String = buildString(value.length) {
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }

    private fun encodeForm(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val intValue = byte.toInt() and 0xFF
            val char = intValue.toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-_.~") {
                append(char)
            } else {
                append('%').append(HEX[intValue shr 4]).append(HEX[intValue and 0x0F])
            }
        }
    }

    private fun encodeQuery(value: String): String = encodeForm(value)

    companion object {
        private const val HEX = "0123456789ABCDEF"

        /** 金智 webauthn.js 内置 RSA 公钥（与 Android BuctWebAuthnManager 同一把）。 */
        private val DEFAULT_RSA_PUBLIC_KEY_DER: ByteArray = base64Decode(
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAjVr1zKwohU3xA0afprWLSQvIymaSH/V27MedFc+CecX" +
                "SnORIFMAp4uEIb4taDq/2X4eMeTI66Mu/rB5GKSFDbExF2Gu4NaO/CNDpf1gHMScUrIFCh4CDqzBnx17kclvezLkIK" +
                "0T8FVa4cRsINvzjbnA6jUSMaf6Fm1n9wTAtW6QYBjssGOEtCj+c38PTBdFMmJbXp3brt1tEBesz6lb3Fjp76FGvD" +
                "Z08xtYG8fxYPuiMwKU04eS+mcX/BunwgpU3zwekHYB+PWRIvq0lBry9Wms25sJE5T/RAv5fEuMLbBkfcZK3+7ivS" +
                "ZthTmPpr2Ap/ji70ZZ6u2jvR5VJq+LJHQIDAQAB",
        )
    }
}

/**
 * 平台通行密钥认证器（断言签名 / 创建凭据）。
 *
 * 实现方（Android Credential Manager 或 iOS AuthenticationServices）负责：
 * - 把 requestOptionsJson / creationOptionsJson 交给系统认证器，弹指纹/面部；
 * - 返回与浏览器 PublicKeyCredential 等价的 JSON
 *   （{id, rawId, response:{clientDataJSON, authenticatorData, signature}, type}）。
 *
 * 红线：私钥只进平台安全区，本接口不得返回或记录任何私钥材料。
 */
interface PlatformPasskeySigner {
    /** 是否为该学号保存了可用凭据（否则界面直接引导密码登录 + 创建）。 */
    fun hasCredential(username: String): Boolean

    /** 执行断言签名；用户取消或失败返回 null。 */
    suspend fun createAssertion(username: String, requestOptionsJson: String): String?

    /** 创建新凭据；失败返回 null。 */
    suspend fun createCredential(username: String, displayName: String, creationOptionsJson: String): String?
}
