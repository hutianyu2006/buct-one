package com.buct.one.data.source.tygl

import com.buct.course.login.AndroidHttpClients
import com.buct.course.login.BuctAesCrypto
import com.buct.course.login.BuctDns
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.login.BuctWebVpn
import com.buct.course.login.MatchingMemoryCookieJar
import com.buct.one.data.model.TyglSnapshot
import com.buct.one.data.source.tygl.TyglAuthExpiredException
import com.buct.one.data.source.tygl.TyglGateway
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 体质测试与体育管理平台（tygl.buct.edu.cn）原生 HTTP 客户端。
 *
 * 完整实测认证与数据链路：
 * 1. 请求 https://tygl.buct.edu.cn/（`followRedirects(false)` 保活 PHP `PHPSESSID`）
 *    → `302` https://portal.buct.edu.cn/cas/login?service=http%3A%2F%2Ftygl.buct.edu.cn%2F
 *    → `302` https://sidportal.buct.edu.cn/login?service=http%3A%2F%2Ftygl.buct.edu.cn%2F
 * 2. 若 `sidportal` TGT Cookie 有效，签发 `?ticket=ST-...` 重定向至 `http://tygl.buct.edu.cn/?ticket=...`。
 *    **关键兼容细节**：送往 CAS 的 `service` 参数不可改写（保持 `http://` 以通过 phpCAS `serviceValidate`）；
 *    对回跳目标 `http://tygl.buct.edu.cn/...` 升级为 `https://tygl.buct.edu.cn/...` 后经 HTTPS 提交给 Caddy 反代。
 * 3. 登录态建立后直接读取 PC 端服务端渲染页面（`/app/main.php` 与 PC 会话不互通）：
 *    - `?module=stu&title=stu_ht_score`（含多学年 `&year=...` 页签）
 *    - `?module=stu&title=stu_sun_score`（跑步计划与学期阳光跑/越野统计）
 *    - `?module=stu&title=stu_pe_score`（体育课成绩）
 *    - `?module=stu&title=stu_exam`（体育理论在线考试）
 */
class TyglClient {
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
        const val TYGL_BASE = "https://tygl.buct.edu.cn"
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
     * 尝试通过已有 sidportal TGT 无感换票建立 tygl 会话。
     */
    fun authenticateWithSso(): Boolean {
        return followCasToMain("$TYGL_BASE/")
    }

    /**
     * 使用学号和密码（CharArray，用完立即清零）原生登录 sidportal 并建立 tygl 会话。
     */
    fun loginWithSidCredentials(username: String, passwordChars: CharArray): Result<Unit> {
        return try {
            var loginUrl: HttpUrl? = null
            var entryHtml = ""
            var parsed: TyglParser.SidPortalFormParams? = null

            for (attempt in 0..1) {
                if (attempt == 1) cookieJar.clear()
                var currentUrl: HttpUrl = "$TYGL_BASE/".toHttpUrl()
                loginUrl = null
                entryHtml = ""

                var hops = 0
                while (hops < 14) {
                    hops++
                    val safeUrl = upgradeTyglHttpToHttps(currentUrl)
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
                        if (host == "tygl.buct.edu.cn" && TyglParser.isAuthenticatedPage(body)) {
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

                val currentParsed = TyglParser.parseSidPortalPage(entryHtml)
                parsed = currentParsed
                if (currentParsed.errorCode.isNotEmpty()) {
                    return Result.failure(IOException("统一身份认证页面异常（${currentParsed.errorCode}）"))
                }
                if (attempt == 0 && (currentParsed.execution.isEmpty() || currentParsed.croypto.isEmpty())) {
                    continue
                }
                break
            }

            val resolvedParams = parsed ?: return Result.failure(IOException("无法获取统一身份认证登录页面"))
            if (resolvedParams.second.equals("true", ignoreCase = true) ||
                (entryHtml.contains("sso-second") && resolvedParams.second.isNotEmpty())
            ) {
                return Result.failure(IOException("统一身份认证需要短信二次验证，请重新登录统一身份认证"))
            }
            if (resolvedParams.execution.isEmpty() || resolvedParams.croypto.isEmpty()) {
                return Result.failure(IOException("认证页面缺少 execution 或 croypto 参数"))
            }

            val pwdStr = String(passwordChars)
            val encPwd = BuctAesCrypto.encrypt(resolvedParams.croypto, pwdStr)
            val encCaptcha = BuctAesCrypto.encrypt(resolvedParams.croypto, "{}")

            val form = FormBody.Builder()
                .add("type", "UsernamePassword")
                .add("_eventId", "submit")
                .add("username", username.trim())
                .add("password", encPwd)
                .add("croypto", resolvedParams.croypto)
                .add("execution", resolvedParams.execution)
                .add("captcha_code", "")
                .add("captcha_payload", encCaptcha)
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
                    val postParsed = if (postBody.isNotBlank()) TyglParser.parseSidPortalPage(postBody) else null
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

            if (nextUrl.isBlank() || !followCasToMain(nextUrl)) {
                return Result.failure(IOException("统一认证换取体测系统登录凭证失败"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun followCasToMain(startUrl: String): Boolean {
        var currentUrl: HttpUrl = upgradeTyglHttpToHttps(startUrl.toHttpUrl())
        var hops = 0

        while (hops < 14) {
            hops++
            val safeUrl = upgradeTyglHttpToHttps(currentUrl)
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
                val encodedPath = next.encodedPath
                if (encodedPath.contains("login.php") || next.queryParameter("reason") == "accessDenied") {
                    authenticated = false
                    return false
                }
                if (!isAllowedAuthHost(host)) {
                    authenticated = false
                    return false
                }
                currentUrl = upgradeTyglHttpToHttps(next)
                continue
            }

            if (statusCode in 200..299 && safeUrl.host.equals("tygl.buct.edu.cn", ignoreCase = true)) {
                if (TyglParser.isAuthenticatedPage(body)) {
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
        host == "tygl.buct.edu.cn" ||
            host == "portal.buct.edu.cn" ||
            host == "sidportal.buct.edu.cn"

    private fun upgradeTyglHttpToHttps(url: HttpUrl): HttpUrl {
        if (url.host.equals("tygl.buct.edu.cn", ignoreCase = true) && url.scheme == "http") {
            return url.newBuilder().scheme("https").build()
        }
        return url
    }

    private fun fetchAuthenticatedHtml(pathAndQuery: String, allowReauth: Boolean = true): String {
        if (!authenticated && !authenticateWithSso()) {
            throw IOException("体测系统统一认证会话已失效，请重新登录")
        }

        var currentUrl = "$TYGL_BASE$pathAndQuery".toHttpUrl()
        var hops = 0
        while (hops < 4) {
            hops++
            val safeUrl = upgradeTyglHttpToHttps(currentUrl)
            val req = Request.Builder()
                .url(safeUrl)
                .header("User-Agent", BuctWebVpn.userAgentFor(safeUrl, UA))
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", "$TYGL_BASE/main.php")
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
                if (host == "tygl.buct.edu.cn" &&
                    !next.encodedPath.contains("login.php") &&
                    next.queryParameter("reason") != "accessDenied"
                ) {
                    currentUrl = upgradeTyglHttpToHttps(next)
                    continue
                }
                authenticated = false
                if (allowReauth && authenticateWithSso()) {
                    return fetchAuthenticatedHtml(pathAndQuery, allowReauth = false)
                }
                throw IOException("体测系统统一认证会话已失效，请重新登录")
            }

            if (statusCode !in 200..299) {
                throw IOException("体测系统请求失败（HTTP $statusCode）")
            }

            if (!TyglParser.isAuthenticatedPage(body)) {
                authenticated = false
                if (allowReauth && authenticateWithSso()) {
                    return fetchAuthenticatedHtml(pathAndQuery, allowReauth = false)
                }
                throw IOException("体测系统统一认证会话已失效，请重新登录")
            }

            return body
        }

        throw IOException("体测系统页面重定向次数过多")
    }

    /**
     * 抓取体测四类页面并解析——请求与解析全部复用共享 `TyglGateway`（与 iOS 同一份实现）。
     *
     * OkHttp 的 `cookieJar` 仍是登录/换票的事实来源：调用前把 tygl 与 sidportal 的 Cookie
     * 种进共享 CookieStore，保证两个 HTTP 栈共享同一会话。
     */
    suspend fun fetchFullSnapshot(): TyglSnapshot {
        if (!authenticated && !authenticateWithSso()) {
            throw IOException("体测系统统一认证会话已失效，请重新登录")
        }
        AndroidHttpClients.seedCookies("tygl.buct.edu.cn", cookieHeaderFor("$TYGL_BASE/main.php"))
        AndroidHttpClients.seedCookies("sidportal.buct.edu.cn", exportSidPortalCookieHeader())
        return try {
            TyglGateway(AndroidHttpClients.session, userAgent = UA).fetchFullSnapshot()
        } catch (expired: TyglAuthExpiredException) {
            authenticated = false
            throw IOException(expired.message ?: "体测系统统一认证会话已失效，请重新登录")
        }
    }

    private fun cookieHeaderFor(url: String): String = cookieJar.cookieHeaderFor(url.toHttpUrl())
}