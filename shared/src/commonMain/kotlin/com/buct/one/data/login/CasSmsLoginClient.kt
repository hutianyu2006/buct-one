package com.buct.one.data.login

import com.buct.one.data.text.htmlUnescape
import com.buct.one.data.text.resolveUrl
import com.buct.one.data.text.urlHostOf
import com.buct.one.data.text.urlSchemeOf
import com.buct.one.platform.crypto.BuiltinCrypto
import com.buct.one.platform.crypto.base64Encode
import com.buct.one.platform.crypto.md5Hex
import com.buct.one.platform.secureRandomBytes
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType

/** 短信登录各阶段结果。 */
sealed interface CasSmsOutcome {
    /** 需要图形验证码：返回 PNG 字节，UI 负责解码渲染。 */
    data class CaptchaRequired(val pngBytes: ByteArray) : CasSmsOutcome

    /** 短信已下发，[expireSeconds] 为有效秒数。 */
    data class SmsSent(val expireSeconds: Int) : CasSmsOutcome

    /** 图形验证码错误或已过期，需要重新获取。 */
    data object CaptchaInvalid : CasSmsOutcome

    /** 短信发送过于频繁/在有效期内重复发送。 */
    data class SmsSendRejected(val message: String) : CasSmsOutcome

    /** 短信验证码错误。 */
    data class SmsCodeInvalid(val message: String) : CasSmsOutcome

    /** 登录成功：会话 Cookie 由注入的 [HttpClient] 的 Cookie 存储持有。 */
    data object Success : CasSmsOutcome

    data class Failed(val message: String) : CasSmsOutcome
}

/**
 * 北化统一身份认证的**短信验证码登录**，双端共用（iOS 目前只有这条免密码路径）。
 *
 * 协议（与 Android `BuctSmsLoginManager` 逐项对齐）：
 * 1. `POST /sso-extend/protected/api/aggregate/sms/publicNoToken/generate`
 *    body `{"phone":学号,"type":"DEFAULT"}` → 200 PNG 图形验证码；
 * 2. `GET /sso-extend/protected/api/aggregate/sms/publicNoToken/sendCheckCaptcha/DEFAULT/{图形码}/{学号}/0008`
 *    → 触发短信（业务号**必须 0008**，0024 能发但校验查不到记录）；
 * 3. `POST /api/protected/sms/checkTokenResult` body `{"phone":学号,"token":短信码,"delete":false}`；
 * 4. `GET /login?service=...` 取新 `login-page-flowkey`，再 `POST /login` 表单
 *    `type=smsLogin, password=短信码（明文）, _eventId=submit`；
 * 5. 302 视为成功，继续跟随票据到业务系统建立会话。
 *
 * **关键约束**：第 1~3 步必须带**同一个固定 `Device-Id`**，且复用**同一个 Cookie 会话**，
 * 否则 `checkTokenResult` 恒返回 `1320009`。本类把这两点固化在实例内。
 */
class CasSmsLoginClient(
    private val httpClient: HttpClient,
    private val userAgent: String,
    private val portalBaseUrl: String = CasPasswordLoginClient.DEFAULT_PORTAL,
    private val serviceUrl: String = CasPasswordLoginClient.DEFAULT_SERVICE,
    private val deviceId: String = defaultDeviceId(),
    private val maxRedirects: Int = 5,
) {

    private var csrfKeyCache: String? = null
    private var csrfValueCache: String? = null

    /** 阶段一：拉取图形验证码（PNG）。 */
    suspend fun fetchCaptcha(username: String): CasSmsOutcome {
        val response = httpClient.post("$portalBaseUrl/sso-extend/protected/api/aggregate/sms/publicNoToken/generate") {
            contentType(ContentType.Application.Json)
            commonHeaders()
            setBody("""{"phone":"${escapeJson(username)}","type":"DEFAULT"}""")
        }
        if (response.status.value !in 200..299) {
            return CasSmsOutcome.Failed("图形验证码获取失败（HTTP ${response.status.value}）")
        }
        val bytes = response.bodyAsBytes()
        return if (bytes.size > 16) {
            CasSmsOutcome.CaptchaRequired(bytes)
        } else {
            CasSmsOutcome.Failed("图形验证码获取失败")
        }
    }

    /** 阶段二：提交图形码触发短信下发。 */
    suspend fun sendSmsCode(username: String, captchaCode: String): CasSmsOutcome {
        val captcha = captchaCode.trim()
        if (captcha.length < 3) return CasSmsOutcome.CaptchaInvalid
        val url = "$portalBaseUrl/sso-extend/protected/api/aggregate/sms/publicNoToken/sendCheckCaptcha" +
            "/DEFAULT/${encodePathSegment(captcha)}/${encodePathSegment(username.trim())}/0008"
        val response = httpClient.get(url) { commonHeaders() }
        val text = response.bodyAsText()
        val errorCode = extractJsonField(text, "errorCode").orEmpty()
        return when {
            response.status.value !in 200..299 -> CasSmsOutcome.Failed("发送短信失败（HTTP ${response.status.value}）")
            text.contains("\"result\":true") || text.contains("\"code\":200") -> CasSmsOutcome.SmsSent(120)
            errorCode == "2180000" -> CasSmsOutcome.CaptchaInvalid
            errorCode == "13001016" -> CasSmsOutcome.SmsSendRejected("验证码还在有效期内，请勿重复发送")
            errorCode == "13002022" || text.contains("BUSINESS_LIMIT") ->
                CasSmsOutcome.SmsSendRejected("短信发送过于频繁，请稍后再试")
            else -> CasSmsOutcome.Failed("发送短信失败：${errorCode.ifEmpty { text.take(120) }}")
        }
    }

    /** 阶段三：校验短信码并完成登录（成功时返回 302 的票据跳转已跟随完毕）。 */
    suspend fun loginWithSmsCode(username: String, smsCode: String): CasSmsOutcome {
        val code = smsCode.trim()
        if (code.length < 4) return CasSmsOutcome.SmsCodeInvalid("请输入短信验证码")

        val verify = httpClient.post("$portalBaseUrl/api/protected/sms/checkTokenResult") {
            contentType(ContentType.Application.Json)
            commonHeaders()
            setBody("""{"phone":"${escapeJson(username)}","token":"${escapeJson(code)}","delete":false}""")
        }
        val verifyText = verify.bodyAsText()
        if (verify.status.value !in 200..299 || !verifyText.contains("\"code\":200")) {
            val errorCode = extractJsonField(verifyText, "errorCode")
            return CasSmsOutcome.SmsCodeInvalid(
                if (errorCode == "1320009") "验证码有误，请重新输入" else "验证码校验失败，请重试",
            )
        }
        return submitSmsLoginForm(username, code)
    }

    private suspend fun submitSmsLoginForm(username: String, smsCode: String): CasSmsOutcome {
        val loginUrl = "$portalBaseUrl/login?service=${encodeQueryValue(serviceUrl)}"
        val pageHtml = httpClient.get(loginUrl) {
            header(HttpHeaders.UserAgent, userAgent)
        }.bodyAsText()
        val execution = CasLoginPage.parse(pageHtml).execution
            ?: return CasSmsOutcome.Failed("登录会话已失效，请重新开始")

        val response = httpClient.submitForm(
            url = loginUrl,
            formParameters = Parameters.build {
                append("username", username.trim())
                append("password", smsCode)
                append("type", "smsLogin")
                append("_eventId", "submit")
                append("geolocation", "")
                append("execution", execution)
                append("captcha_code", "")
            },
            encodeInQuery = false,
        ) {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Referrer, loginUrl)
        }

        val status = response.status.value
        if (status !in 300..399) {
            return CasSmsOutcome.Failed("登录失败（$status），请重新输入验证码")
        }
        // 票据跳转失败不影响"认证已通过"，错误留给后续业务请求。
        runCatching { followTicketRedirects(response.headers[HttpHeaders.Location], loginUrl) }
        return CasSmsOutcome.Success
    }

    private suspend fun followTicketRedirects(initialLocation: String?, baseUrl: String) {
        var current = resolveUrl(baseUrl, initialLocation ?: return)
        repeat(maxRedirects) {
            val host = urlHostOf(current)?.lowercase() ?: return
            if (host != "buct.edu.cn" && !host.endsWith(".buct.edu.cn")) return
            val target = if (urlSchemeOf(current) == "http") current.replaceFirst("http://", "https://") else current
            val response = httpClient.get(target) {
                header(HttpHeaders.UserAgent, userAgent)
            }
            if (response.status.value !in 300..399) return
            val next = response.headers[HttpHeaders.Location] ?: return
            current = resolveUrl(target, next)
        }
    }

    /** 第 1~3 步共用的头部：Device-Id 必须固定，Csrf 由固定算法生成。 */
    private fun io.ktor.client.request.HttpRequestBuilder.commonHeaders() {
        header(HttpHeaders.UserAgent, userAgent)
        header(HttpHeaders.Accept, "application/json, text/plain, */*")
        header(HttpHeaders.AcceptLanguage, "zh-CN,zh")
        header("sid-language", "zh_CN")
        header("Csrf-Key", csrfKey())
        header("Csrf-Value", csrfValue())
        header("Device-Id", deviceId)
        header(HttpHeaders.Origin, portalBaseUrl)
        header(HttpHeaders.Referrer, "$portalBaseUrl/login")
    }

    private fun csrfKey(): String {
        csrfKeyCache?.let { return it }
        val key = buildString(32) {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            for (byte in secureRandomBytes(32)) {
                append(chars[(byte.toInt() and 0xFF) % chars.length])
            }
        }
        val encoded = base64Encode(key.encodeToByteArray())
        val half = encoded.length / 2
        val value = BuiltinCrypto.md5Hex(encoded.substring(0, half) + encoded + encoded.substring(half))
        csrfKeyCache = key
        csrfValueCache = value
        return key
    }

    private fun csrfValue(): String = csrfValueCache ?: csrfKey().let { csrfValueCache.orEmpty() }

    private fun extractJsonField(json: String, field: String): String? {
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
            return if (end > start) htmlUnescape(json.substring(start + 1, end)) else null
        }
        var end = start
        while (end < json.length && json[end] != ',' && json[end] != '}') end++
        return if (end > start) json.substring(start, end).trim() else null
    }

    private fun escapeJson(value: String): String = buildString(value.length) {
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u").append(char.code.toString(16).padStart(4, '0')) else append(char)
            }
        }
    }

    private fun encodePathSegment(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val intValue = byte.toInt() and 0xFF
            val char = intValue.toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '-' || char == '_' || char == '.' || char == '~') {
                append(char)
            } else {
                append('%').append(HEX[intValue shr 4]).append(HEX[intValue and 0x0F])
            }
        }
    }

    private fun encodeQueryValue(value: String): String = encodePathSegment(value)

    companion object {
        private const val HEX = "0123456789ABCDEF"

        /**
         * 固定设备指纹：发送短信与校验必须一致（校方风控会比对），
         * 因此**不能**每次随机生成，也不能随设备变化——与 Android 端保持同一常量。
         */
        const val FIXED_DEVICE_ID = "a1b2c3d4e5f60718293a4b5c6d7e8f90"

        fun defaultDeviceId(): String = FIXED_DEVICE_ID
    }
}
