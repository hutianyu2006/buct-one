package com.buct.one.data.source.scs

import com.buct.one.data.json.JSONObject
import com.buct.one.data.model.ScsCategoryDetail
import com.buct.one.data.model.ScsSnapshot
import com.buct.one.data.model.ScsTermScore
import com.buct.one.data.text.resolveUrl
import com.buct.one.data.text.urlEncode
import com.buct.one.data.text.urlHostOf
import com.buct.one.data.text.urlQueryOf
import com.buct.one.platform.network.WebVpnCodec
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

/** 第二课堂会话失效（App-Token 过期且 SSO 换票也失败）。 */
class ScsAuthExpiredException(message: String) : Exception(message)

/**
 * 第二课堂（scs.buct.edu.cn / eapi.buct.edu.cn）网关，双端共用。
 *
 * 协议链路（与 Android `ScsClient` 逐项对齐）：
 * 1. GET `eapi/cas/opLogin` 取 CAS 入口（失败回落到 `sidportal/login?service=scs/commonLogin`）；
 * 2. 跟随 302 到 `scs.buct.edu.cn/commonLogin?ticket=ST-...` 提取票据；
 * 3. GET `eapi/sys/casLogin?ticket=...` 换 JWT `App-Token`；
 * 4. 业务请求统一携带 `App-Token` / `App-source: yfdop` / `App-Version` / `Device-Id`。
 *
 * 解析全部复用 [ScsParser]；会话 Cookie 由注入的 [HttpClient] 持有，
 * 校外访问依赖调用方已配置的 WebVPN 改写（这两个域都在 `CAMPUS_ONLY_HOSTS` 里）。
 */
class ScsGateway(
    private val httpClient: HttpClient,
    private val userAgent: String,
    private val deviceId: String = "buct-one",
    private val appVersion: String = "1.0.0",
) {

    @kotlin.concurrent.Volatile
    private var appToken: String = ""

    fun hasActiveToken(): Boolean = appToken.isNotBlank()

    fun invalidateSession() {
        appToken = ""
    }

    /** 拉取第二课堂完整快照（个人信息、自评窗口、五育维度、历年学期明细、历史记录、活动清单）。 */
    suspend fun fetchFullSnapshot(): ScsSnapshot {
        val userJson = apiGet("/sysAccount/getLoginUserInfo")
        val userInfo = ScsParser.parseUserInfo(userJson)

        val windowJson = runCatching { apiGet("/scSelfEvalTerm/getDefaultYearAndTerm") }.getOrDefault("")
        val currentWindow = if (windowJson.isNotBlank()) ScsParser.parseCurrentEvalWindow(windowJson) else null

        val dimensionsJson = runCatching { apiGet("/scStudentEvaluationType/getEvaluationType") }.getOrDefault("")
        val dimensions = if (dimensionsJson.isNotBlank()) {
            ScsParser.parseEvaluationDimensions(dimensionsJson)
        } else {
            emptyList()
        }

        var terms = ScsParser.parseAllTermsScores(apiGet("/tStudent/getLoginStudentAllScore"))

        // 自评开放但当前学期没有五育明细时，补拉自评评定细则（与 Android 行为一致）
        if (currentWindow != null) {
            val index = terms.indexOfFirst { it.termId == currentWindow.termId && it.yearId == currentWindow.yearId }
            if (index >= 0 && terms[index].details.isEmpty()) {
                val body = """{"yearId":${currentWindow.yearId},"termId":${currentWindow.termId}}"""
                val evalJson = runCatching {
                    apiPost("/scStudentEvaluationItem/getAppointTermEvaluationItems", body)
                }.getOrDefault("")
                if (evalJson.isNotBlank()) {
                    val parsed = ScsParser.parseCurrentTermEvalDetails(evalJson)
                    if (parsed.isNotEmpty()) {
                        val mutable = terms.toMutableList()
                        val old = mutable[index]
                        mutable[index] = old.copy(
                            isOpenSelfEva = currentWindow.isOpen || old.isOpenSelfEva,
                            details = parsed,
                        )
                        terms = mutable
                    }
                }
            }
        }

        val classInfo = if (userInfo.studentId.isNotBlank()) {
            val historyJson = runCatching {
                apiPost(
                    "/scStudentScore/listStudentHistoryScore?xh=${urlEncode(userInfo.studentId)}",
                    "{}",
                )
            }.getOrDefault("")
            if (historyJson.isNotBlank()) {
                ScsParser.parseStudentClassInfo(historyJson)
            } else {
                ScsParser.StudentClassInfo("", "", "")
            }
        } else {
            ScsParser.StudentClassInfo("", "", "")
        }

        val activitiesJson = runCatching {
            apiPost("/scActivity/listByCondition?currentPage=1&pageSize=30", "{}")
        }.getOrDefault("")
        val activities = if (activitiesJson.isNotBlank()) ScsParser.parseActivities(activitiesJson) else emptyList()

        return ScsSnapshot(
            studentName = userInfo.name,
            studentId = userInfo.studentId,
            collegeName = classInfo.collegeName,
            className = classInfo.className,
            gradeName = classInfo.gradeName,
            currentWindow = currentWindow,
            dimensions = dimensions,
            terms = terms,
            activities = activities,
        )
    }

    /** 单个学期的五育明细（供"按学期展开"按需加载）。 */
    suspend fun fetchTermDetails(term: ScsTermScore): List<ScsCategoryDetail> {
        val body = """{"yearId":${term.yearId},"termId":${term.termId}}"""
        val json = runCatching {
            apiPost("/scStudentEvaluationItem/getAppointTermEvaluationItems", body)
        }.getOrNull().orEmpty()
        return if (json.isBlank()) emptyList() else ScsParser.parseCurrentTermEvalDetails(json)
    }

    // ---------- 认证 ----------

    /** 用现有 sidportal 会话无感换票；成功返回 true。 */
    suspend fun authenticateWithSso(): Boolean {
        val casUrl = runCatching {
            ScsParser.parseStringDataResponse(getText("$API_BASE/cas/opLogin"))
        }.getOrNull().orEmpty()
            .ifBlank { "$SID_PORTAL_BASE/login?service=${urlEncode(COMMON_LOGIN_SERVICE)}" }
        return followCasAndLogin(casUrl)
    }

    /** 用学号密码登录 sidportal 并换取 App-Token；密码数组用后清零。 */
    suspend fun loginWithSidCredentials(username: String, passwordChars: CharArray): Result<Unit> = try {
        val entryUrl = "$SID_PORTAL_BASE/login?service=${urlEncode(COMMON_LOGIN_SERVICE)}"
        val entryHtml = getText(entryUrl)
        val page = ScsParser.parseSidPortalPage(entryHtml)
        if (page.errorCode.isNotEmpty()) {
            Result.failure(ScsAuthExpiredException("统一身份认证页面异常（${page.errorCode}）"))
        } else if (page.second.equals("true", ignoreCase = true)) {
            Result.failure(ScsAuthExpiredException("统一身份认证需要短信二次验证，请重新登录统一身份认证"))
        } else if (page.execution.isEmpty() || page.croypto.isEmpty()) {
            Result.failure(ScsAuthExpiredException("认证页面缺少 execution 或 croypto 参数"))
        } else {
            val password = passwordChars.concatToString()
            val form = try {
                com.buct.one.data.login.CasLoginForm.build(
                    page = com.buct.one.data.login.CasLoginPage(
                        execution = page.execution,
                        aesKey = page.croypto,
                        errorCode = "",
                        captchaUrl = "",
                        smsRequired = false,
                        phoneMask = "",
                    ),
                    username = username,
                    password = password,
                )
            } catch (error: Exception) {
                return Result.failure(ScsAuthExpiredException(error.message ?: "统一认证密码加密失败"))
            }
            val response = httpClient.post(entryUrl) {
                header(HttpHeaders.UserAgent, portalUserAgent())
                header(HttpHeaders.Referrer, entryUrl)
                header(HttpHeaders.Origin, SID_PORTAL_BASE)
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    form.fields.entries.joinToString("&") { (key, value) ->
                        "${urlEncode(key)}=${urlEncode(value)}"
                    },
                )
            }
            val status = response.status.value
            if (status !in 300..399) {
                Result.failure(ScsAuthExpiredException("统一身份认证登录失败，请检查学号或密码"))
            } else {
                val next = resolveUrl(entryUrl, response.headers[HttpHeaders.Location].orEmpty())
                if (followCasAndLogin(next)) Result.success(Unit) else {
                    Result.failure(ScsAuthExpiredException("统一认证换取第二课堂登录凭证失败"))
                }
            }
        }
    } finally {
        passwordChars.fill('\u0000')
    }

    private suspend fun followCasAndLogin(startUrl: String): Boolean {
        var current = startUrl
        var ticket = extractTicket(current)
        var hops = 0
        while (ticket.isBlank() && hops < MAX_CAS_HOPS) {
            hops++
            if (!isAllowedAuthHost(current)) break
            val response = httpClient.get(current) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            }
            if (response.status.value !in 300..399) break
            val location = response.headers[HttpHeaders.Location] ?: break
            current = resolveUrl(current, location)
            ticket = extractTicket(current)
        }
        if (ticket.isBlank()) return false

        val token = runCatching {
            ScsParser.parseStringDataResponse(
                getText("$API_BASE/sys/casLogin?ticket=${urlEncode(ticket)}", apiHeaders = true),
            )
        }.getOrNull().orEmpty()
        if (token.isBlank()) return false
        appToken = token
        return true
    }

    private fun extractTicket(url: String): String {
        val host = urlHostOf(url)?.lowercase() ?: return ""
        if (host != SCS_HOST) return ""
        val query = urlQueryOf(url) ?: return ""
        for (segment in query.split("&")) {
            val eq = segment.indexOf('=')
            if (eq <= 0) continue
            if (segment.substring(0, eq) == "ticket") return segment.substring(eq + 1).trim()
        }
        return ""
    }

    // ---------- API ----------

    private suspend fun apiGet(path: String): String {
        ensureToken()
        return try {
            doApiGet(path)
        } catch (_: TokenExpired) {
            appToken = ""
            if (!authenticateWithSso()) throw ScsAuthExpiredException("第二课堂会话已过期，请重新登录")
            doApiGet(path)
        }
    }

    private suspend fun apiPost(path: String, jsonBody: String): String {
        ensureToken()
        return try {
            doApiPost(path, jsonBody)
        } catch (_: TokenExpired) {
            appToken = ""
            if (!authenticateWithSso()) throw ScsAuthExpiredException("第二课堂会话已过期，请重新登录")
            doApiPost(path, jsonBody)
        }
    }

    private suspend fun ensureToken() {
        if (appToken.isBlank() && !authenticateWithSso()) {
            throw ScsAuthExpiredException("第二课堂会话已过期，请重新登录")
        }
    }

    private suspend fun doApiGet(path: String): String {
        val response = httpClient.get("$API_BASE$path") {
            header(HttpHeaders.UserAgent, userAgent)
            header("App-Token", appToken)
            header("App-source", APP_SOURCE)
            header("App-Version", appVersion)
            header("Device-Id", deviceId)
        }
        return checkApiResponse(response.status.value, response.bodyAsText())
    }

    private suspend fun doApiPost(path: String, jsonBody: String): String {
        val response = httpClient.post("$API_BASE$path") {
            header(HttpHeaders.UserAgent, userAgent)
            header("App-Token", appToken)
            header("App-source", APP_SOURCE)
            header("App-Version", appVersion)
            header("Device-Id", deviceId)
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        return checkApiResponse(response.status.value, response.bodyAsText())
    }

    private fun checkApiResponse(status: Int, body: String): String {
        if (status == 401 || status == 403) throw TokenExpired()
        if (status !in 200..299) throw IllegalStateException("第二课堂接口请求失败（HTTP $status）")
        val root = runCatching { JSONObject(body) }.getOrNull()
        val code = root?.optInt("code", 200) ?: 200
        val message = root?.optString("message", "").orEmpty()
        if (ScsParser.isUnauthorized(code, message)) throw TokenExpired()
        return body
    }

    private suspend fun getText(url: String, apiHeaders: Boolean = false): String {
        val response = httpClient.get(url) {
            header(HttpHeaders.UserAgent, if (apiHeaders) userAgent else portalUserAgent())
            if (apiHeaders) {
                header("App-source", APP_SOURCE)
                header("App-Version", appVersion)
                header("Device-Id", deviceId)
            }
        }
        return response.bodyAsText()
    }

    private fun portalUserAgent(): String = userAgent

    private fun isAllowedAuthHost(url: String): Boolean {
        val host = urlHostOf(url)?.lowercase() ?: return false
        return host == SCS_HOST || host == PORTAL_HOST || host == WebVpnCodec.VPN_HOST
    }

    /** App-Token 失效（HTTP 401/403 或业务 code 表示未授权）。 */
    private class TokenExpired : Exception("第二课堂 App-Token 失效")

    companion object {
        const val SID_PORTAL_BASE = "https://sidportal.buct.edu.cn"
        const val API_BASE = "https://eapi.buct.edu.cn"
        const val COMMON_LOGIN_SERVICE = "https://scs.buct.edu.cn/commonLogin"

        private const val SCS_HOST = "scs.buct.edu.cn"
        private const val PORTAL_HOST = "sidportal.buct.edu.cn"
        private const val APP_SOURCE = "yfdop"
        private const val MAX_CAS_HOPS = 10
    }
}