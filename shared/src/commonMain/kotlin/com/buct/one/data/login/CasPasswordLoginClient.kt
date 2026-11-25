package com.buct.one.data.login

import com.buct.one.data.text.urlEncode
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters

/** CAS 密码登录的结果分类（不含网络异常，网络异常由调用方处理）。 */
sealed interface CasLoginOutcome {
    /** 登录成功：302 已下发会话 Cookie（由注入的 [HttpClient] 的 Cookie 存储持有）。 */
    data object Success : CasLoginOutcome

    /** 需要输入/刷新图形验证码（wrongCode=true 表示本次验证码错误）。 */
    data class CaptchaRequired(val wrongCode: Boolean, val message: String) : CasLoginOutcome

    /** 触发风控，进入短信二次验证（phoneMask 为脱敏手机号）。 */
    data class SmsVerificationRequired(val phoneMask: String) : CasLoginOutcome

    data class Failed(val message: String) : CasLoginOutcome
}

/**
 * 北化统一身份认证（sidportal，金智 linkid）的密码登录流程，双端共用。
 *
 * 流程：
 * 1. GET `/login?service=<教务服务>` 拿登录页，解析 `login-page-flowkey` / `login-croypto`；
 * 2. POST 表单（密码 AES-ECB/PKCS7 加密，见 [CasLoginForm]）；
 * 3. 302 视为成功（Cookie 由注入的客户端保存），200 则按错误码/验证码/短信二次验证分类。
 *
 * [httpClient] 必须配置 `followRedirects = false`（见 `buildHttpClient(followRedirects = false)`），
 * 否则拿不到 302 与 `Set-Cookie` 时机；Cookie 需要开启 HttpCookies。
 */
class CasPasswordLoginClient(
    private val httpClient: HttpClient,
    private val userAgent: String,
    private val portalBaseUrl: String = DEFAULT_PORTAL,
    private val serviceUrl: String = DEFAULT_SERVICE,
    private val maxRedirects: Int = 5,
) {
    suspend fun login(
        username: String,
        password: String,
        captchaCode: String = "",
    ): CasLoginOutcome {
        val loginUrl = "$portalBaseUrl/login?service=${urlEncode(serviceUrl)}"
        val pageHtml = fetchLoginPage(loginUrl)
        val page = CasLoginPage.parse(pageHtml)
        val form = try {
            CasLoginForm.build(page, username, password, captchaCode)
        } catch (error: CasLoginFormMissingFieldException) {
            return CasLoginOutcome.Failed(error.message ?: "统一认证登录页解析失败")
        }

        val response = httpClient.submitForm(
            url = loginUrl,
            formParameters = Parameters.build {
                form.fields.forEach { (key, value) -> append(key, value) }
            },
            encodeInQuery = false,
        ) {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Referrer, loginUrl)
            header(HttpHeaders.Origin, portalBaseUrl)
        }

        val code = response.status.value
        if (code in 300..399) return CasLoginOutcome.Success
        return classify(response.bodyAsText())
    }

    private suspend fun fetchLoginPage(loginUrl: String): String {
        var target = loginUrl
        repeat(maxRedirects + 1) {
            val response = httpClient.get(target) {
                header(HttpHeaders.UserAgent, userAgent)
            }
            val code = response.status.value
            if (code in 300..399) {
                val location = response.headers[HttpHeaders.Location] ?: return ""
                target = if (location.startsWith("http")) location
                else "$portalBaseUrl${if (location.startsWith("/")) location else "/$location"}"
                return@repeat
            }
            return response.bodyAsText()
        }
        return ""
    }

    private fun classify(html: String): CasLoginOutcome {
        val page = CasLoginPage.parse(html)
        if (page.smsRequired) return CasLoginOutcome.SmsVerificationRequired(page.phoneMask)
        return when (page.errorCode) {
            "1030027", "1030031" -> CasLoginOutcome.Failed("用户名或密码错误")
            "1030028" -> CasLoginOutcome.Failed("账号已被锁定，请联系学校处理")
            "1320007" -> CasLoginOutcome.CaptchaRequired(true, "验证码错误，请重新输入")
            else -> if (page.captchaUrl.isNotBlank() || page.errorCode == "1320006") {
                CasLoginOutcome.CaptchaRequired(false, "请输入验证码")
            } else {
                CasLoginOutcome.Failed("统一认证登录失败，请检查账号密码或稍后重试")
            }
        }
    }

    companion object {
        const val DEFAULT_PORTAL = "https://sidportal.buct.edu.cn"
        const val DEFAULT_SERVICE = "https://jwglxt.buct.edu.cn/sso/jziotlogin"
    }
}
