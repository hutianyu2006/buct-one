package com.buct.one.data.source.scs

import com.buct.course.login.AndroidHttpClients
import com.buct.course.login.BuctAesCrypto
import com.buct.course.login.BuctDns
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.login.BuctWebVpn
import com.buct.course.login.BuctWebVpnAuthRequiredException
import com.buct.course.login.BuctWebVpnInterceptor
import com.buct.course.login.BuctWebVpnNetworkInterceptor
import com.buct.course.login.MatchingMemoryCookieJar
import com.buct.one.data.model.ScsSnapshot
import com.buct.one.data.source.scs.ScsAuthExpiredException
import com.buct.one.data.source.scs.ScsGateway
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 第二课堂（scs.buct.edu.cn / eapi.buct.edu.cn）原生 HTTP 客户端。
 *
 * 完整实测认证与协议链路：
 * 1. GET https://eapi.buct.edu.cn/cas/opLogin -> 获取 portal CAS 登录入口 URL
 * 2. 携带统一身份认证（sidportal.buct.edu.cn）TGT Cookie 访问 CAS 入口，经 302 跳转到
 *    https://scs.buct.edu.cn/commonLogin?ticket=ST-... 并提取 ticket
 * 3. GET https://eapi.buct.edu.cn/sys/casLogin?ticket=<ticket> -> 换取 JWT App-Token
 * 4. 后续业务请求统一携带 App-Token / App-source: yfdop / App-Version: 1.0.0 / Device-Id
 *    并强制走 BuctTlsTrustManager。
 */
class ScsClient {
    private val cookieJar = MatchingMemoryCookieJar()

    @Volatile
    private var appToken: String = ""

    private val httpClient: OkHttpClient by lazy {
        val trustManager = BuctTlsTrustManager()
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
            .dns(BuctDns)
            .addInterceptor(BuctWebVpnInterceptor())
            .addNetworkInterceptor(BuctWebVpnNetworkInterceptor())
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val SID_PORTAL_BASE = "https://sidportal.buct.edu.cn"
        const val SCS_API_BASE = "https://eapi.buct.edu.cn"
        const val SCS_COMMON_LOGIN_SERVICE = "https://scs.buct.edu.cn/commonLogin"
        private const val APP_SOURCE = "yfdop"
        private const val APP_VERSION = "1.0.0"
        private const val DEVICE_ID = "buct-one-android"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }

    fun importSidPortalCookies(rawCookieHeader: String) {
        if (rawCookieHeader.isBlank()) return
        val url = "$SID_PORTAL_BASE/login".toHttpUrl()
        val parsed = rawCookieHeader.split(';').mapNotNull { segment ->
            val trimmed = segment.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return@mapNotNull null
            val domain = if (name == "_zte_cid_") "buct.edu.cn" else "sidportal.buct.edu.cn"
            Cookie.Builder()
                .name(name)
                .value(value)
                .domain(domain)
                .path("/")
                .secure()
                .expiresAt(Long.MAX_VALUE)
                .build()
        }
        if (parsed.isNotEmpty()) {
            cookieJar.saveFromResponse(url, parsed)
        }
    }

    fun exportSidPortalCookieHeader(): String {
        val probeUrl = "$SID_PORTAL_BASE/login".toHttpUrl()
        val merged = LinkedHashMap<String, String>()
        cookieJar.loadForRequest(probeUrl).forEach { c ->
            merged[c.name] = c.value
        }
        cookieJar.cookiesSetBy("sidportal.buct.edu.cn", 443).forEach { c ->
            if (!merged.containsKey(c.name) && c.value.isNotBlank()) {
                merged[c.name] = c.value
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun invalidateSession() {
        appToken = ""
    }

    fun hasActiveToken(): Boolean = appToken.isNotBlank()

    fun authenticateWithSso(): Boolean {
        val casUrl = try {
            val req = Request.Builder()
                .url("$SCS_API_BASE/cas/opLogin")
                .header("User-Agent", UA)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) ""
                else ScsParser.parseStringDataResponse(resp.body?.string().orEmpty())
            }
        } catch (e: BuctWebVpnAuthRequiredException) {
            throw e
        } catch (_: Throwable) {
            ""
        }
            .ifBlank {
                "$SID_PORTAL_BASE/login?service=${URLEncoder.encode(SCS_COMMON_LOGIN_SERVICE, "UTF-8")}"
            }

        return followCasAndLogin(casUrl)
    }

    /**
     * 使用学号和密码（CharArray，用完立即清零）原生登录 sidportal 并换取第二课堂 App-Token。
     */
    fun loginWithSidCredentials(username: String, passwordChars: CharArray): Result<Unit> {
        return try {
            val encodedService = URLEncoder.encode(SCS_COMMON_LOGIN_SERVICE, "UTF-8")
            val loginEntry = "$SID_PORTAL_BASE/login?service=$encodedService"

            val entryReq = Request.Builder()
                .url(loginEntry)
                .header("User-Agent", BuctWebVpn.SID_PORTAL_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()

            var entryHtml = httpClient.newCall(entryReq).execute().use { resp ->
                if (resp.code in 300..399) {
                    val loc = resp.header("Location").orEmpty()
                    val resolved = if (loc.isNotBlank()) resp.request.url.resolve(loc)?.toString() ?: loc else ""
                    if (resolved.isNotBlank() && followCasAndLogin(resolved)) {
                        return Result.success(Unit)
                    }
                }
                if (!resp.isSuccessful) {
                    return Result.failure(IOException("统一身份认证响应异常（HTTP ${resp.code}）"))
                }
                resp.body?.string().orEmpty()
            }

            var parsed = ScsParser.parseSidPortalPage(entryHtml)
            if (parsed.errorCode.isEmpty() && (parsed.execution.isEmpty() || parsed.croypto.isEmpty())) {
                cookieJar.clear()
                entryHtml = httpClient.newCall(entryReq).execute().use { retryResp ->
                    if (retryResp.code in 300..399) {
                        val loc = retryResp.header("Location").orEmpty()
                        val resolved = if (loc.isNotBlank()) retryResp.request.url.resolve(loc)?.toString() ?: loc else ""
                        if (resolved.isNotBlank() && followCasAndLogin(resolved)) {
                            return Result.success(Unit)
                        }
                    }
                    if (!retryResp.isSuccessful) {
                        return Result.failure(IOException("统一身份认证响应异常（HTTP ${retryResp.code}）"))
                    }
                    retryResp.body?.string().orEmpty()
                }
                parsed = ScsParser.parseSidPortalPage(entryHtml)
            }
            if (parsed.errorCode.isNotEmpty()) {
                return Result.failure(IOException("统一身份认证页面异常（${parsed.errorCode}）"))
            }
            if (parsed.second.equals("true", ignoreCase = true) ||
                (entryHtml.contains("sso-second") && parsed.second.isNotEmpty())
            ) {
                return Result.failure(IOException("统一身份认证需要短信二次验证，请重新登录统一身份认证"))
            }
            if (parsed.execution.isEmpty() || parsed.croypto.isEmpty()) {
                val pageTitle = runCatching { Jsoup.parse(entryHtml).title().trim() }
                    .getOrDefault("")
                    .take(40)
                    .ifBlank { "无" }
                return Result.failure(
                    IOException("认证页面缺少 execution 或 croypto 参数（title=$pageTitle, len=${entryHtml.length}）")
                )
            }

            val pwdStr = String(passwordChars)
            val encPwd = BuctAesCrypto.encrypt(parsed.croypto, pwdStr)
            val encCaptcha = BuctAesCrypto.encrypt(parsed.croypto, "{}")

            val form = FormBody.Builder()
                .add("type", "UsernamePassword")
                .add("_eventId", "submit")
                .add("username", username.trim())
                .add("password", encPwd)
                .add("croypto", parsed.croypto)
                .add("execution", parsed.execution)
                .add("captcha_code", "")
                .add("captcha_payload", encCaptcha)
                .add("geolocation", "")
                .build()

            val postReq = Request.Builder()
                .url(loginEntry)
                .header("User-Agent", BuctWebVpn.SID_PORTAL_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Origin", SID_PORTAL_BASE)
                .header("Referer", loginEntry)
                .post(form)
                .build()

            val nextUrl = httpClient.newCall(postReq).execute().use { resp ->
                if (resp.code !in 300..399) {
                    val postBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    val postParsed = if (postBody.isNotBlank()) ScsParser.parseSidPortalPage(postBody) else null
                    val need2fa = postParsed?.second?.equals("true", ignoreCase = true) == true ||
                        (postBody.contains("sso-second") && !postParsed?.second.isNullOrEmpty()) ||
                        postBody.contains("smsNoPhoneloginForm")
                    return Result.failure(
                        IOException(
                            if (need2fa) "统一身份认证需要短信二次验证，请重新登录统一身份认证"
                            else "统一身份认证登录失败，请检查学号或密码"
                        )
                    )
                }
                val loc = resp.header("Location").orEmpty()
                resp.request.url.resolve(loc)?.toString() ?: loc
            }

            if (nextUrl.isBlank() || !followCasAndLogin(nextUrl)) {
                return Result.failure(IOException("统一认证换取第二课堂登录凭证失败"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(mapCampusError(e))
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun mapCampusError(e: Exception): Exception = when (e) {
        is BuctWebVpnAuthRequiredException -> e
        is SocketTimeoutException, is ConnectException, is UnknownHostException,
        is NoRouteToHostException, is SocketException ->
            IOException("无法连接第二课堂（scs.buct.edu.cn），直连及 WebVPN 均不可达，请检查网络或先登录统一身份认证后重试", e)
        else -> e
    }

    private inline fun <T> runCampusCall(block: () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            throw mapCampusError(e)
        }
    }

    private fun followCasAndLogin(startUrl: String): Boolean {
        var currentUrl: HttpUrl = startUrl.toHttpUrl()
        var extractedTicket = extractTicketFromUrl(currentUrl)

        var hops = 0
        while (extractedTicket.isBlank() && hops < 10) {
            hops++
            val req = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", BuctWebVpn.userAgentFor(currentUrl, UA))
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            val nextLoc = httpClient.newCall(req).execute().use { resp ->
                if (resp.code in 300..399) resp.header("Location").orEmpty() else ""
            }
            if (nextLoc.isBlank()) break
            val resolved = currentUrl.resolve(nextLoc) ?: break
            val ticket = extractTicketFromUrl(resolved)
            if (ticket.isNotBlank()) {
                extractedTicket = ticket
                break
            }
            val host = resolved.host.lowercase()
            if (host != "sidportal.buct.edu.cn" && host != "portal.buct.edu.cn" && host != "scs.buct.edu.cn") {
                break
            }
            currentUrl = resolved
        }

        if (extractedTicket.isBlank()) return false

        BuctWebVpn.updateSidCookies(exportSidPortalCookieHeader())

        val loginUrl = "$SCS_API_BASE/sys/casLogin?ticket=${URLEncoder.encode(extractedTicket, "UTF-8")}"
        val loginReq = Request.Builder()
            .url(loginUrl)
            .header("User-Agent", UA)
            .header("App-source", APP_SOURCE)
            .header("App-Version", APP_VERSION)
            .header("Device-Id", DEVICE_ID)
            .get()
            .build()

        val token = try {
            httpClient.newCall(loginReq).execute().use { resp ->
                if (!resp.isSuccessful) ""
                else ScsParser.parseStringDataResponse(resp.body?.string().orEmpty())
            }
        } catch (e: BuctWebVpnAuthRequiredException) {
            throw e
        } catch (_: Throwable) {
            ""
        }

        if (token.isBlank()) return false
        appToken = token
        return true
    }

    private fun extractTicketFromUrl(url: HttpUrl): String {
        if (!url.host.equals("scs.buct.edu.cn", ignoreCase = true)) return ""
        return url.queryParameter("ticket")?.trim().orEmpty()
    }

    /**
     * 拉取第二课堂完整快照（个人信息、自评窗口、五育维度满分、历年各学期明细、历史记录、校园二课活动）。
     *
     * 请求编排与解析全部复用共享 `ScsGateway`（与 iOS 同一份实现）；
     * OkHttp 的 cookieJar 仍是登录/换票的事实来源，调用前把会话 Cookie 种进共享 CookieStore。
     */
    suspend fun fetchFullSnapshot(): ScsSnapshot = runCampusCall {
        AndroidHttpClients.seedCookies("sidportal.buct.edu.cn", exportSidPortalCookieHeader())
        AndroidHttpClients.seedCookies("scs.buct.edu.cn", cookieHeaderFor("https://scs.buct.edu.cn/"))
        AndroidHttpClients.seedCookies("eapi.buct.edu.cn", cookieHeaderFor("$SCS_API_BASE/"))
        val gateway = ScsGateway(AndroidHttpClients.session, userAgent = UA, deviceId = DEVICE_ID)
        try {
            gateway.fetchFullSnapshot()
        } catch (expired: ScsAuthExpiredException) {
            appToken = ""
            throw IOException(expired.message ?: "第二课堂会话已过期，请重新登录")
        }
    }

    private fun cookieHeaderFor(url: String): String = cookieJar.cookieHeaderFor(url.toHttpUrl())
}