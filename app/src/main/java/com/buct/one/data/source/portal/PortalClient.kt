package com.buct.one.data.source.portal

import com.buct.course.login.BuctAesCrypto
import com.buct.course.login.BuctDns
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.login.BuctWebVpn
import com.buct.course.login.BuctWebVpnInterceptor
import com.buct.course.login.BuctWebVpnNetworkInterceptor
import com.buct.course.login.AndroidHttpClients
import com.buct.course.login.MatchingMemoryCookieJar
import com.buct.one.data.model.PortalCalendarSnapshot
import com.buct.one.data.model.PortalLibrarySnapshot
import com.buct.one.data.model.PortalNewsColumnPage
import com.buct.one.data.model.PortalNewsSnapshot
import com.buct.one.data.source.portal.PortalAuthExpiredException
import com.buct.one.data.source.portal.PortalGateway
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * i北化信息门户（i.buct.edu.cn）原生 HTTP 客户端。
 *
 * 完整实测协议链路：
 * 1. 请求 https://i.buct.edu.cn/（`followRedirects(false)`）
 *    -> `302` https://portal.buct.edu.cn/cas/login?service=...
 *    -> `302` https://sidportal.buct.edu.cn/login?service=...
 * 2. 若 sidportal TGT Cookie 有效，自动签发 `?ticket=ST-...` 建立门户会话。
 * 3. `getJxz.jsp` 实测必须携带 `_p` 上下文参数（缺省返回 HTTP 403）；
 *    `calendar/mgr/api/ids/list.rst` 的 `fromDate`/`endDate` 必须为 ISO-8601 UTC（`T00:00:00.000Z`）。
 */
class PortalClient {
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
            .addInterceptor(BuctWebVpnInterceptor())
            .addNetworkInterceptor(BuctWebVpnNetworkInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val SID_PORTAL_BASE = "https://sidportal.buct.edu.cn"
        const val PORTAL_BASE = "https://i.buct.edu.cn"
        const val MNEWS_BASE = "https://mnews.buct.edu.cn"
        const val MNEWS_SITE_ID = "100000251"
        private const val DEFAULT_PORTAL_CTX = "YXM9MiZ0PTYmZD0xMTYmcD0xJmY9MzAmbT1OJg__"
        private const val REFERER_MAIN = "$PORTAL_BASE/_s2/students_sy/main.psp"
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
        authenticated = false
    }

    fun hasActiveSession(): Boolean = authenticated

    /**
     * 尝试通过已有 sidportal TGT 无感换票建立 i.buct.edu.cn 会话。
     */
    fun authenticateWithSso(): Boolean {
        return followCasToMain("$PORTAL_BASE/")
    }

    /**
     * 使用学号与密码（CharArray 用完立即清零）原生登录 sidportal 并建立 i.buct.edu.cn 会话。
     */
    fun loginWithSidCredentials(username: String, passwordChars: CharArray): Result<Unit> {
        return try {
            var loginUrl: HttpUrl? = null
            var entryHtml = ""
            var parsed: PortalParser.SidPortalPageInfo? = null
            var entrySecond = ""

            for (attempt in 0..1) {
                if (attempt == 1) cookieJar.clear()
                var currentUrl: HttpUrl = "$PORTAL_BASE/".toHttpUrl()
                loginUrl = null
                entryHtml = ""

                var hops = 0
                while (hops < 14) {
                    hops++
                    val safeUrl = upgradePortalHttpToHttps(currentUrl)
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
                            if (loc.isNotBlank()) {
                                redirectNext = safeUrl.resolve(loc)
                            }
                        } else {
                            body = resp.body?.string().orEmpty()
                        }
                    }

                    if (redirectNext != null) {
                        val host = redirectNext!!.host.lowercase()
                        if (!isAllowedAuthHost(host)) break
                        currentUrl = redirectNext!!
                        continue
                    }

                    if (statusCode in 200..299) {
                        val host = safeUrl.host.lowercase()
                        if (host == "i.buct.edu.cn" && PortalParser.isPortalAuthenticatedHtml(body)) {
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

                val currentParsed = PortalParser.parseSidPortalPage(entryHtml)
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

            if (nextUrl.isBlank() || !followCasToMain(nextUrl)) {
                return Result.failure(IOException("统一认证换取信息门户登录凭证失败"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun followCasToMain(startUrl: String): Boolean {
        var currentUrl: HttpUrl = upgradePortalHttpToHttps(startUrl.toHttpUrl())
        var hops = 0

        while (hops < 14) {
            hops++
            val safeUrl = upgradePortalHttpToHttps(currentUrl)
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
                    if (loc.isNotBlank()) {
                        redirectNext = safeUrl.resolve(loc)
                    }
                } else {
                    body = resp.body?.string().orEmpty()
                }
            }

            if (redirectNext != null) {
                val next = redirectNext!!
                val host = next.host.lowercase()
                if (!isAllowedAuthHost(host)) {
                    authenticated = false
                    return false
                }
                currentUrl = upgradePortalHttpToHttps(next)
                continue
            }

            if (statusCode in 200..299 && safeUrl.host.equals("i.buct.edu.cn", ignoreCase = true)) {
                if (PortalParser.isPortalAuthenticatedHtml(body)) {
                    authenticated = true
                    return true
                }
            }
            authenticated = false
            return false
        }

        authenticated = false
        return false
    }

    private fun isAllowedAuthHost(host: String): Boolean =
        host == "i.buct.edu.cn" ||
            host == "mnews.buct.edu.cn" ||
            host == "portal.buct.edu.cn" ||
            host == "sidportal.buct.edu.cn"

    private fun upgradePortalHttpToHttps(url: HttpUrl): HttpUrl {
        val host = url.host.lowercase()
        if ((host == "i.buct.edu.cn" || host == "mnews.buct.edu.cn") && url.scheme == "http") {
            return url.newBuilder().scheme("https").build()
        }
        return url
    }

    /**
     * 共享层网关：请求与解析全部复用 `shared/commonMain` 的 [PortalGateway]（与 iOS 同一份实现）。
     *
     * OkHttp 的 `cookieJar` 是登录/换票的事实来源，这里在每次调用前把门户与资讯域下的 Cookie
     * 种进共享 CookieStore，保证两个 HTTP 栈共享同一登录态；共享网关抛出的
     * [PortalAuthExpiredException] 统一映射回 Android 侧既有的 `IOException` 语义。
     */
    private fun sharedGateway(): PortalGateway {
        AndroidHttpClients.seedCookies("i.buct.edu.cn", cookieHeaderFor("$PORTAL_BASE/"))
        AndroidHttpClients.seedCookies("mnews.buct.edu.cn", cookieHeaderFor("$MNEWS_BASE/"))
        AndroidHttpClients.seedCookies("sidportal.buct.edu.cn", exportSidPortalCookieHeader())
        return PortalGateway(AndroidHttpClients.session, userAgent = UA)
    }

    private fun cookieHeaderFor(url: String): String = cookieJar.cookieHeaderFor(url.toHttpUrl())

    /**
     * 拉取 i北化官方学期校历、当前教学周与法定节假日。
     */
    suspend fun fetchCalendarSnapshot(todayDate: String): PortalCalendarSnapshot = try {
        sharedGateway().fetchCalendarSnapshot(todayDate)
    } catch (expired: PortalAuthExpiredException) {
        authenticated = false
        throw IOException(expired.message ?: "信息门户统一认证会话已失效，请重新登录")
    }

    /**
     * 拉取 i北化首页「借阅图书」总本数与在借明细（getYktOrBook.jsp?type=book）。
     */
    suspend fun fetchBorrowedBooks(page: Int = 1, rows: Int = 50): PortalLibrarySnapshot = try {
        sharedGateway().fetchBorrowedBooks(page = page, rows = rows)
    } catch (expired: PortalAuthExpiredException) {
        authenticated = false
        throw IOException(expired.message ?: "信息门户统一认证会话已失效，请重新登录")
    }

    /**
     * 拉取 mnews.buct.edu.cn 单个栏目的校内资讯列表（共享 [PortalGateway]）。
     */
    suspend fun fetchNewsColumn(
        columnId: String,
        beginIndex: Int = 0,
        pageSize: Int = 15
    ): PortalNewsColumnPage = try {
        sharedGateway().fetchNewsColumn(columnId = columnId, beginIndex = beginIndex, pageSize = pageSize)
    } catch (expired: PortalAuthExpiredException) {
        authenticated = false
        throw IOException(expired.message ?: "校内资讯统一认证会话已过期，请重新登录")
    }

    /**
     * 依次拉取全部 4 个校内综合资讯栏位（通知公告 / 活动公告 / 招聘信息 / 北化新闻）。
     * 单栏目失败不影响其他栏目、全部失败才抛错——该语义已固化在共享网关里。
     */
    suspend fun fetchAllNewsSnapshot(pageSize: Int = 15): PortalNewsSnapshot = try {
        sharedGateway().fetchAllNewsSnapshot(pageSize = pageSize)
    } catch (expired: PortalAuthExpiredException) {
        authenticated = false
        throw IOException(expired.message ?: "校内资讯统一认证会话已过期，请重新登录")
    }
}