package com.buct.one.data.login

import com.buct.one.data.text.htmlUnescape
import com.buct.one.platform.crypto.AesLoginCrypto

/** CAS 登录页里的关键字段（金智 linkid 平台：<p id=...> 隐藏域）。 */
data class CasLoginPage(
    val execution: String?,
    val aesKey: String?,
    val errorCode: String,
    val captchaUrl: String,
    val smsRequired: Boolean,
    val phoneMask: String,
) {
    companion object {
        private val P_TAG_PATTERN = Regex("""<p[^>]*id="([^"]+)"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        private val INPUT_TAG_PATTERN = Regex("""<input[^>]*>""", RegexOption.IGNORE_CASE)

        /** 解析登录页；缺失字段返回 null 值，由调用方决定报错文案。 */
        fun parse(html: String): CasLoginPage {
            val fields = extractPTagFields(html)
            val execution = fields["login-page-flowkey"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: extractInputValue(html, "execution")
            val aesKey = fields["login-croypto"]?.trim()?.takeIf { it.isNotEmpty() }
            val smsRequired = fields["sso-second"]?.trim() == "true" ||
                fields["current-login-type"]?.trim() == "smsLogin" ||
                html.contains("smsNoPhoneloginForm") ||
                html.contains("二次身份验证")
            return CasLoginPage(
                execution = execution,
                aesKey = aesKey,
                errorCode = fields["login-error-code"]?.trim().orEmpty(),
                captchaUrl = fields["captcha-url"]?.trim().orEmpty(),
                smsRequired = smsRequired,
                phoneMask = fields["phone-number"]?.trim().orEmpty(),
            )
        }

        private fun extractPTagFields(html: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for (match in P_TAG_PATTERN.findAll(html)) {
                val id = match.groupValues[1]
                val raw = match.groupValues[2]
                result[id] = htmlUnescape(raw).replace(Regex("<[^>]+>"), "")
            }
            return result
        }

        private fun extractInputValue(html: String, name: String): String? {
            val namePattern = Regex("""name\s*=\s*["']""" + Regex.escape(name) + """["']""", RegexOption.IGNORE_CASE)
            val valuePattern = Regex("""value\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            for (tag in INPUT_TAG_PATTERN.findAll(html)) {
                if (namePattern.containsMatchIn(tag.value)) {
                    return valuePattern.find(tag.value)?.groupValues?.getOrNull(1)?.let(::htmlUnescape)?.trim()
                }
            }
            return null
        }
    }
}

/** 登录页缺少必要字段（execution / croypto）或加密失败。 */
class CasLoginFormMissingFieldException(message: String) : Exception(message)

/** CAS 密码登录表单（顺序与校方前端一致，勿随意调整）。 */
data class CasLoginForm(val fields: Map<String, String>) {
    companion object {
        fun build(
            page: CasLoginPage,
            username: String,
            password: String,
            captchaCode: String = "",
            eventId: String = "submit",
        ): CasLoginForm {
            val aesKey = page.aesKey ?: throw CasLoginFormMissingFieldException("统一认证密钥缺失，请重新登录")
            val execution = page.execution ?: throw CasLoginFormMissingFieldException("统一认证会话失效，请重新登录")
            val encryptedPassword = try {
                AesLoginCrypto.encrypt(aesKey, password)
            } catch (error: AesLoginCrypto.CryptoException) {
                throw CasLoginFormMissingFieldException(error.message ?: "统一认证密码加密失败")
            }
            val captchaPayload = try {
                AesLoginCrypto.encrypt(aesKey, "{}")
            } catch (error: AesLoginCrypto.CryptoException) {
                throw CasLoginFormMissingFieldException(error.message ?: "统一认证参数加密失败")
            }
            return CasLoginForm(
                linkedMapOf(
                    "type" to "UsernamePassword",
                    "_eventId" to eventId,
                    "username" to username,
                    "password" to encryptedPassword,
                    "croypto" to aesKey,
                    "execution" to execution,
                    "captcha_code" to captchaCode.trim(),
                    "captcha_payload" to captchaPayload,
                    "geolocation" to "",
                ),
            )
        }
    }
}
