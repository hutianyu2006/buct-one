package com.buct.one.data.source.jwnotice

import com.buct.course.login.BuctAesCrypto
import com.buct.course.login.BuctDns
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.login.BuctWebVpn
import com.buct.course.login.MatchingMemoryCookieJar
import com.buct.one.data.model.JwAnnouncementDetail
import com.buct.one.data.model.JwAnnouncementItem
import com.buct.one.data.model.JwNoticeSnapshot
import com.buct.one.data.model.JwReminderItem
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 教务系统（jwglxt.buct.edu.cn）消息（调停课提醒）与通知公告原生 HTTP 客户端。
 *
 * 复用当前账号已有 jwglxt Cookie 或 sidportal TGT Cookie 无感建立教务会话；
 * 仅使用 BuctTlsTrustManager + BuctDns。
 */
class JwNoticeClient {
    private val cookieJar = MatchingMemoryCookieJar()

    @Volatile
    private var authenticated: Boolean = false

    private val httpClient: OkHttpClient by lazy {
        val trustManager = BuctTlsTrustManager()
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
            .dns(BuctDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val SID_PORTAL_BASE = "https://sidportal.buct.edu.cn"
        const val JW_BASE = "https://jwglxt.buct.edu.cn"
        const val JW_SSO_SERVICE = "https://jwglxt.buct.edu.cn/sso/jziotlogin"
        private const val REFERER_MAIN = "$JW_BASE/jwglxt/xtgl/index_initMenu.html"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }

    private fun importCookiesForHost(
        hostUrl: HttpUrl,
        defaultDomain: String,
        rawCookieHeader: String,
        useBuctCidDomain: Boolean = false
    ): Boolean {
        if (rawCookieHeader.isBlank()) return false
        val parsed = rawCookieHeader.split(';').mapNotNull { segment ->
            val trimmed = segment.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return@mapNotNull null
            val domain = if (useBuctCidDomain && name == "_zte_cid_") "buct.edu.cn" else defaultDomain
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
            cookieJar.saveFromResponse(hostUrl, parsed)
            return true
        }
        return false
    }

    /** 导出当前 jwglxt 会话 Cookie 头，供共享层网关复用同一登录态。 */
    fun exportJwCookieHeader(): String = cookieJar.cookieHeaderFor(JW_BASE.toHttpUrl())

    /** 与客户端内部一致的 User-Agent，供共享层网关使用。 */
    fun userAgent(): String = UA

    fun importJwCookies(rawCookieHeader: String) {
        if (importCookiesForHost(
                hostUrl = "$JW_BASE/jwglxt/".toHttpUrl(),
                defaultDomain = "jwglxt.buct.edu.cn",
                rawCookieHeader = rawCookieHeader
            )
        ) {
            authenticated = true
        }
    }

    fun importSidPortalCookies(rawCookieHeader: String) {
        importCookiesForHost(
            hostUrl = "$SID_PORTAL_BASE/login".toHttpUrl(),
            defaultDomain = "sidportal.buct.edu.cn",
            rawCookieHeader = rawCookieHeader,
            useBuctCidDomain = true
        )
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
        authenticated = false
    }

    fun authenticateWithSso(): Boolean {
        val encodedService = URLEncoder.encode(JW_SSO_SERVICE, "UTF-8")
        return followSsoToJw("$SID_PORTAL_BASE/login?service=$encodedService")
    }

    fun loginWithSidCredentials(username: String, passwordChars: CharArray): Result<Unit> {
        return try {
            val encodedService = URLEncoder.encode(JW_SSO_SERVICE, "UTF-8")
            var loginUrl: HttpUrl? = null
            var entryHtml = ""
            var parsed: JwNoticeParser.SidPortalPageInfo? = null
            var entrySecond = ""

            for (attempt in 0..1) {
                if (attempt == 1) cookieJar.clear()
                var currentUrl: HttpUrl = "$SID_PORTAL_BASE/login?service=$encodedService".toHttpUrl()
                loginUrl = null
                entryHtml = ""

                var hops = 0
                while (hops < 12) {
                    hops++
                    val safeUrl = upgradeHttpToHttps(currentUrl)
                    val req = Request.Builder()
                        .url(safeUrl)
                        .header("User-Agent", BuctWebVpn.userAgentFor(safeUrl, UA))
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .get()
                        .build()

                    var redirectNext: HttpUrl? = null
                    var statusCode = 0
                    var body = ""
                    httpClient.newCall(req).execute().use { resp ->
                        statusCode = resp.code
                        if (statusCode in 300..399) {
                            val loc = resp.header("Location").orEmpty()
                            if (loc.isNotBlank()) redirectNext = safeUrl.resolve(loc)
                        } else {
                            body = resp.body?.string().orEmpty()
                        }
                    }

                    if (redirectNext != null) {
                        if (!isAllowedAuthHost(redirectNext!!.host.lowercase())) break
                        currentUrl = redirectNext!!
                        continue
                    }

                    if (statusCode in 200..299) {
                        val host = safeUrl.host.lowercase()
                        if (host == "jwglxt.buct.edu.cn" && !JwNoticeParser.isJwLoginHtml(body)) {
                            authenticated = true
                            return Result.success(Unit)
                        }
                        if (host == "sidportal.buct.edu.cn") {
                            loginUrl = safeUrl
                            entryHtml = body
                            break
                        }
                    }
                    break
                }

                if (loginUrl == null || entryHtml.isBlank()) {
                    return Result.failure(IOException("无法获取统一身份认证登录页面"))
                }

                val currentParsed = JwNoticeParser.parseSidPortalPage(entryHtml)
                parsed = currentParsed
                entrySecond = Jsoup.parse(entryHtml).selectFirst("#sso-second")?.text()?.trim().orEmpty()
                if (currentParsed.errorCode.isNotEmpty()) {
                    return Result.failure(IOException("统一身份认证页面异常（${currentParsed.errorCode}）"))
                }
                if (attempt == 0 && (currentParsed.execution.isEmpty() || currentParsed.croypto.isEmpty())) {
                    continue
                }
                break
            }

            val resolvedParams = parsed ?: return Result.failure(IOException("无法获取统一身份认证登录页面"))
            if (entrySecond.equals("true", ignoreCase = true) ||
                (entryHtml.contains("sso-second") && entrySecond.isNotEmpty())
            ) {
                return Result.failure(IOException("统一身份认证需要短信二次验证，请重新登录统一身份认证"))
            }
            if (resolvedParams.execution.isEmpty() || resolvedParams.croypto.isEmpty()) {
                return Result.failure(IOException("统一认证页面缺少 execution 或 croypto 参数"))
            }

            val pwdStr = String(passwordChars)
            val encryptedPassword = BuctAesCrypto.encrypt(resolvedParams.croypto, pwdStr)
            val encryptedCaptchaPayload = BuctAesCrypto.encrypt(resolvedParams.croypto, "{}")

            val form = FormBody.Builder()
                .add("type", "UsernamePassword")
                .add("_eventId", "submit")
                .add("username", username.trim())
                .add("password", encryptedPassword)
                .add("croypto", resolvedParams.croypto)
                .add("execution", resolvedParams.execution)
                .add("captcha_code", "")
                .add("captcha_payload", encryptedCaptchaPayload)
                .add("geolocation", "")
                .build()

            val postReq = Request.Builder()
                .url(loginUrl!!)
                .header("User-Agent", BuctWebVpn.SID_PORTAL_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Origin", SID_PORTAL_BASE)
                .header("Referer", loginUrl.toString())
                .post(form)
                .build()

            val nextUrl = httpClient.newCall(postReq).execute().use { resp ->
                if (resp.code !in 300..399) {
                    val postBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    val postSecond = if (postBody.isNotBlank()) {
                        Jsoup.parse(postBody).selectFirst("#sso-second")?.text()?.trim().orEmpty()
                    } else ""
                    val need2fa = postSecond.equals("true", ignoreCase = true) ||
                        (postBody.contains("sso-second") && postSecond.isNotEmpty()) ||
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

            if (nextUrl.isBlank() || !followSsoToJw(nextUrl)) {
                return Result.failure(IOException("统一认证换取教务系统登录凭证失败"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun followSsoToJw(startUrl: String): Boolean {
        var currentUrl: HttpUrl = upgradeHttpToHttps(startUrl.toHttpUrl())
        var hops = 0
        while (hops < 12) {
            hops++
            val safeUrl = upgradeHttpToHttps(currentUrl)
            val req = Request.Builder()
                .url(safeUrl)
                .header("User-Agent", BuctWebVpn.userAgentFor(safeUrl, UA))
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            var redirectNext: HttpUrl? = null
            var statusCode = 0
            var body = ""
            httpClient.newCall(req).execute().use { resp ->
                statusCode = resp.code
                if (statusCode in 300..399) {
                    val loc = resp.header("Location").orEmpty()
                    if (loc.isNotBlank()) redirectNext = safeUrl.resolve(loc)
                } else {
                    body = resp.body?.string().orEmpty()
                }
            }

            if (redirectNext != null) {
                val next = redirectNext!!
                if (!isAllowedAuthHost(next.host.lowercase())) {
                    authenticated = false
                    return false
                }
                currentUrl = upgradeHttpToHttps(next)
                continue
            }

            if (statusCode in 200..299 &&
                safeUrl.host.equals("jwglxt.buct.edu.cn", ignoreCase = true) &&
                !JwNoticeParser.isJwLoginHtml(body)
            ) {
                authenticated = true
                return true
            }
            authenticated = false
            return false
        }
        authenticated = false
        return false
    }

    private fun isAllowedAuthHost(host: String): Boolean =
        host == "jwglxt.buct.edu.cn" ||
            host == "sidportal.buct.edu.cn" ||
            host == "portal.buct.edu.cn"

    private fun upgradeHttpToHttps(url: HttpUrl): HttpUrl {
        if ((url.host.equals("jwglxt.buct.edu.cn", ignoreCase = true) ||
                url.host.equals("sidportal.buct.edu.cn", ignoreCase = true)) &&
            url.scheme == "http"
        ) {
            return url.newBuilder().scheme("https").build()
        }
        return url
    }

    private fun executeJwRequest(
        buildRequest: () -> Request,
        allowReauth: Boolean = true
    ): String {
        if (!authenticated && !authenticateWithSso()) {
            throw JwAuthExpiredException()
        }

        val req = buildRequest()
        httpClient.newCall(req).execute().use { resp ->
            val loc = resp.header("Location").orEmpty()
            val isAuthRedirect = resp.code in 300..399 &&
                (loc.contains("login", ignoreCase = true) ||
                    loc.contains("sidportal", ignoreCase = true) ||
                    loc.contains("cas", ignoreCase = true) ||
                    loc.isBlank())

            if (resp.code == 401 || resp.code == 403 || isAuthRedirect) {
                authenticated = false
                if (allowReauth && authenticateWithSso()) {
                    return executeJwRequest(buildRequest, allowReauth = false)
                }
                throw JwAuthExpiredException()
            }

            val body = resp.body?.string().orEmpty()
            if (resp.code in 300..399) {
                // 非登录跳转也视为异常会话边界，尝试一次 SSO 后重试
                authenticated = false
                if (allowReauth && authenticateWithSso()) {
                    return executeJwRequest(buildRequest, allowReauth = false)
                }
                throw JwAuthExpiredException()
            }

            if (!resp.isSuccessful) {
                throw IOException("教务系统请求失败（HTTP ${resp.code}）")
            }

            if (JwNoticeParser.isJwLoginHtml(body)) {
                authenticated = false
                if (allowReauth && authenticateWithSso()) {
                    return executeJwRequest(buildRequest, allowReauth = false)
                }
                throw JwAuthExpiredException()
            }

            return body
        }
    }

    fun fetchRemindersPage(
        page: Int = 1,
        pageSize: Int = 30,
        keyword: String = ""
    ): Pair<Int, List<JwReminderItem>> {
        val raw = executeJwRequest(
            buildRequest = {
                val formBuilder = FormBody.Builder()
                    .add("flag", "1")
                    .add("queryModel.showCount", pageSize.toString())
                    .add("queryModel.currentPage", page.toString())
                    .add("queryModel.sortName", "cjsj")
                    .add("queryModel.sortOrder", "desc")
                    .add("time", "0")
                if (keyword.isNotBlank()) {
                    formBuilder.add("gnmkmc", keyword.trim())
                }
                Request.Builder()
                    .url("$JW_BASE/jwglxt/xtgl/index_cxDbsy.html?doType=query")
                    .header("User-Agent", UA)
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("Origin", JW_BASE)
                    .header("Referer", REFERER_MAIN)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(formBuilder.build())
                    .build()
            }
        )
        return JwNoticeParser.parseRemindersPage(raw)
    }

    fun fetchAnnouncementsPage(
        page: Int = 1,
        pageSize: Int = 25,
        keyword: String = ""
    ): Pair<Int, List<JwAnnouncementItem>> {
        val raw = executeJwRequest(
            buildRequest = {
                val formBuilder = FormBody.Builder()
                    .add("queryModel.showCount", pageSize.toString())
                    .add("queryModel.currentPage", page.toString())
                    .add("queryModel.sortName", "fbsj")
                    .add("queryModel.sortOrder", "desc")
                    .add("time", "0")
                if (keyword.isNotBlank()) {
                    formBuilder.add("xwbt", keyword.trim())
                }
                Request.Builder()
                    .url("$JW_BASE/jwglxt/xtgl/xwck_cxMoreXwList.html?doType=query")
                    .header("User-Agent", UA)
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("Origin", JW_BASE)
                    .header("Referer", "$JW_BASE/jwglxt/xtgl/xwck_cxMoreXwList.html")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(formBuilder.build())
                    .build()
            }
        )
        return JwNoticeParser.parseAnnouncementsPage(raw)
    }

    fun fetchFullSnapshot(
        reminderPageSize: Int = 30,
        announcementPageSize: Int = 25
    ): JwNoticeSnapshot {
        val remRes = runCatching { fetchRemindersPage(page = 1, pageSize = reminderPageSize) }
        val remErr = remRes.exceptionOrNull()
        if (remErr is JwAuthExpiredException) throw remErr

        val annRes = runCatching { fetchAnnouncementsPage(page = 1, pageSize = announcementPageSize) }
        val annErr = annRes.exceptionOrNull()
        if (annErr is JwAuthExpiredException) throw annErr

        if (remRes.isFailure && annRes.isFailure) {
            throw (remErr ?: annErr ?: IOException("获取教务消息失败"))
        }

        return JwNoticeParser.mergeSnapshots(
            remindersPair = remRes.getOrDefault(0 to emptyList()),
            announcementsPair = annRes.getOrDefault(0 to emptyList())
        )
    }

    fun fetchAnnouncementDetail(item: JwAnnouncementItem): JwAnnouncementDetail {
        val encodedId = URLEncoder.encode(item.id, "UTF-8")
        val html = executeJwRequest(
            buildRequest = {
                Request.Builder()
                    .url("$JW_BASE/jwglxt/xtgl/xwck_ckXw.html?xwbh=$encodedId&doType=save")
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Referer", "$JW_BASE/jwglxt/xtgl/xwck_cxMoreXwList.html")
                    .get()
                    .build()
            }
        )
        return JwNoticeParser.parseAnnouncementDetail(
            xwbh = item.id,
            html = html,
            fallbackTitle = item.title,
            fallbackPublisher = item.publisher,
            fallbackTime = item.publishedAt,
            fallbackInlineHtml = item.inlineHtml
        )
    }

    private class JwAuthExpiredException : IOException("教务系统统一认证会话已过期，请重新登录")
}
