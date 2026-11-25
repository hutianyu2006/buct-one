package com.buct.one.data.source.theol

import android.util.Base64
import android.webkit.MimeTypeMap
import com.buct.course.login.BuctAesCrypto
import com.buct.course.login.BuctDns
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.login.BuctWebVpn
import com.buct.course.login.MatchingMemoryCookieJar
import com.buct.one.data.model.TheolCourseInfo
import com.buct.one.data.model.TheolCourseUnit
import com.buct.one.data.model.TheolDownloadedFile
import com.buct.one.data.model.TheolHomeworkDetail
import com.buct.one.data.model.TheolHomeworkItem
import com.buct.one.data.model.TheolResourcePage
import com.buct.one.data.model.TheolSnapshot
import com.buct.one.data.model.TheolTestDetail
import com.buct.one.data.model.TheolTestItem
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import com.fleeksoft.ksoup.Ksoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 北化在线（THEOL / MEOL）原生 HTTP 客户端。
 * 协议移植自 Ling0727-ai/go-buct-course 并融合 BUCT-Course-Companion 会话与路径修复，零 WebView，强制走 BuctTlsTrustManager。
 */
class TheolClient {
    private val cookieJar: MatchingMemoryCookieJar = MatchingMemoryCookieJar()

    @Volatile
    private var lastSessionVerifiedAt: Long = 0L

    @Volatile
    private var activeCourseLid: String = ""

    @Volatile
    private var activeCourseActivatedAt: Long = 0L

    companion object {
        const val SID_PORTAL_BASE = "https://sidportal.buct.edu.cn"
        const val MEOL_BASE = "https://course.buct.edu.cn"
        const val MEOL_SSO_SERVICE = "https://course.buct.edu.cn/meol/homepage/common/sso_login.jsp"
        const val MEOL_WELCOME_HOME = "https://course.buct.edu.cn/meol/welcomepage/student/index.jsp"
        const val MEOL_COURSE_CATALOG_URL = "https://course.buct.edu.cn/meol/welcomepage/student/course_list.jsp"
        const val MEOL_LESSON_LIST_URL = "https://course.buct.edu.cn/meol/lesson/blen.student.lesson.list.jsp"
        const val MEOL_LEGACY_HOME_PATH = "/meol/homepage/student/index.jsp"
        const val MEOL_LEGACY_HOME_URL = "https://course.buct.edu.cn/meol/homepage/student/index.jsp"
        const val MEOL_REMINDER_URL = "https://course.buct.edu.cn/meol/welcomepage/student/interaction_reminder_v8.jsp"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private const val UA = USER_AGENT
        private const val MAX_COURSE_PAGES = 40
        private const val MAX_COURSE_DEPTH = 6
        private const val SESSION_TTL_MILLIS = 5 * 60_000L
        private const val COURSE_CONTEXT_TTL_MILLIS = 2 * 60_000L
        private const val MAX_ATTACHMENT_BYTES = 200L * 1024L * 1024L
        private const val ATTACHMENT_CACHE_LIMIT_BYTES = 120L * 1024L * 1024L
        private const val ATTACHMENT_CACHE_TARGET_BYTES = 96L * 1024L * 1024L

        private val EXT_MIME_MAP = mapOf(
            "pdf" to "application/pdf",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "txt" to "text/plain",
            "csv" to "text/csv",
            "md" to "text/markdown",
            "zip" to "application/zip",
            "rar" to "application/x-rar-compressed",
            "7z" to "application/x-7z-compressed",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "mp4" to "video/mp4",
            "mp3" to "audio/mpeg"
        )
        private val FILENAME_STAR_REGEX = Regex("""(?i)filename\*\s*=\s*(?:([a-zA-Z0-9_-]+)'[^']*')?([^;\r\n]+)""")
        private val FILENAME_BASIC_REGEX = Regex("""(?i)filename\s*=\s*(?:"([^"]*)"|([^;\r\n]+))""")

        private val ALLOWED_HW_PATHS = setOf(
            "/meol/jpk/course/layout/newpage/index.jsp",
            "/meol/jpk/course/course_column_preview_transfer.jsp",
            "/meol/buildless/colUrlStuView.do",
            "/meol/common/hw/student/hwtask.jsp",
            "/meol/common/hw/student/hwtask_blended.jsp"
        )
        private val LOCATION_REGEX = Regex(
            """(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]|(?:window\.)?location\.replace\s*\(\s*['"]([^'"]+)['"]"""
        )
        private val QUOTED_URL_REGEX = Regex("""['"]([^'"]+)['"]""")
        private val REFRESH_REGEX = Regex("""(?i)url\s*=\s*(.+)""")
        private val CHARSET_REGEX = Regex("""(?i)charset\s*=\s*['"]?([a-zA-Z0-9_-]+)""")
    }

    private val trustManager = BuctTlsTrustManager()

    private val noRedirectClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
        .dns(BuctDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val meolClient: OkHttpClient = noRedirectClient.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val downloadClient: OkHttpClient = noRedirectClient.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** 从持久化存储注入历史 Cookie（MEOL 的 JSESSIONID 默认作用域为 /meol，避免与根路径冲突） */
    fun importCookies(host: String, cookieHeader: String) {
        val pathForHost = if (host.equals("course.buct.edu.cn", ignoreCase = true)) "/meol" else "/"
        val url = "https://$host$pathForHost".toHttpUrl()
        val parsed = cookieHeader.split(';').mapNotNull { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (value.isEmpty()) return@mapNotNull null
            val domain = if (name == "_zte_cid_") "buct.edu.cn" else host
            val path = if (name == "_zte_cid_") "/" else pathForHost
            Cookie.Builder()
                .name(name)
                .value(value)
                .domain(domain)
                .path(path)
                .expiresAt(Long.MAX_VALUE)
                .build()
        }
        if (parsed.isNotEmpty()) {
            cookieJar.saveFromResponse(url, parsed)
        }
    }

    /** 导出指定域名的 Cookie 字符串以便加密保存（course.buct.edu.cn 按 /meol/ 路径检索） */
    fun exportCookieHeader(host: String): String {
        val probeUrl = if (host.equals("course.buct.edu.cn", ignoreCase = true)) {
            MEOL_WELCOME_HOME.toHttpUrl()
        } else {
            "https://$host/login".toHttpUrl()
        }
        val merged = LinkedHashMap<String, String>()
        cookieJar.loadForRequest(probeUrl).forEach { cookie ->
            merged[cookie.name] = cookie.value
        }
        cookieJar.cookiesSetBy(host, 443).forEach { cookie ->
            if (!merged.containsKey(cookie.name) && cookie.value.isNotBlank()) {
                merged[cookie.name] = cookie.value
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 重置内存中的会话校验与课程上下文缓存（切换账号或外部重新登录后调用） */
    fun invalidateSessionCache() {
        lastSessionVerifiedAt = 0L
        activeCourseLid = ""
        activeCourseActivatedAt = 0L
    }

    fun isSessionRecentlyVerified(ttlMillis: Long = SESSION_TTL_MILLIS): Boolean =
        lastSessionVerifiedAt > 0L &&
            (System.currentTimeMillis() - lastSessionVerifiedAt) in 0L..ttlMillis &&
            hasMeolSessionCookie()

    fun isCourseContextFresh(courseLid: String, ttlMillis: Long = COURSE_CONTEXT_TTL_MILLIS): Boolean {
        val lid = courseLid.trim()
        return lid.isNotEmpty() &&
            activeCourseLid == lid &&
            activeCourseActivatedAt > 0L &&
            (System.currentTimeMillis() - activeCourseActivatedAt) in 0L..ttlMillis
    }

    private fun hasMeolSessionCookie(): Boolean =
        cookieJar.loadForRequest(MEOL_REMINDER_URL.toHttpUrl()).any {
            it.name.equals("JSESSIONID", ignoreCase = true) && it.value.isNotBlank()
        }

    /**
     * 快速探针：仅请求一次待办索引页验证已有 JSESSIONID 是否存活。
     * 成功时返回响应 HTML 并刷新会话校验时间戳（可直接复用于列表解析，避免多余重定向）；失效则返回 null。
     */
    fun probeReminderHtml(): String? {
        val req = Request.Builder()
            .url(MEOL_REMINDER_URL)
            .header("User-Agent", UA)
            .header("Referer", MEOL_WELCOME_HOME)
            .get()
            .build()
        noRedirectClient.newCall(req).execute().use { resp ->
            if (resp.code != 200) return null
            val finalUrl = resp.request.url
            if (!finalUrl.host.equals("course.buct.edu.cn", ignoreCase = true)) return null
            val html = decodeResponseBody(resp)
            if (TheolParser.isPermissionDeniedOrExpired(html)) return null
            lastSessionVerifiedAt = System.currentTimeMillis()
            return html
        }
    }

    /** 检查当前会话是否可直接访问北化在线（优先快速探针，失效时再通过 TGT 自动换票） */
    fun ensureSessionActive(forceSso: Boolean = false): Boolean {
        if (!forceSso && hasMeolSessionCookie()) {
            if (probeReminderHtml() != null) return true
        }
        val probe = followAuthRedirects(
            Request.Builder()
                .url(MEOL_SSO_SERVICE)
                .header("User-Agent", UA)
                .get()
                .build(),
            allowedHosts = setOf("course.buct.edu.cn", "sidportal.buct.edu.cn", "portal.buct.edu.cn")
        )
        val landed = probe.use { resp ->
            val finalUrl = resp.request.url
            val body = if (resp.isSuccessful) decodeResponseBody(resp) else ""
            resp.isSuccessful &&
                finalUrl.host.equals("course.buct.edu.cn", ignoreCase = true) &&
                finalUrl.encodedPath.startsWith("/meol/") &&
                !finalUrl.encodedPath.contains("login", ignoreCase = true) &&
                !TheolParser.isPermissionDeniedOrExpired(body)
        }
        if (!landed) return false
        return probeReminderHtml() != null
    }

    /**
     * 使用学号与密码（CharArray，用完立即清零）原生登录 sidportal 并建立 MEOL 会话。
     */
    fun loginWithCredentials(username: String, passwordChars: CharArray): Result<Unit> {
        return try {
            val encodedService = URLEncoder.encode(MEOL_SSO_SERVICE, "UTF-8")
            val loginEntry = "$SID_PORTAL_BASE/login?service=$encodedService"

            val entryResp = followAuthRedirects(
                Request.Builder()
                    .url(loginEntry)
                    .header("User-Agent", BuctWebVpn.SID_PORTAL_UA)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .get()
                    .build(),
                allowedHosts = setOf("course.buct.edu.cn", "sidportal.buct.edu.cn", "portal.buct.edu.cn")
            )

            var (entryUrl, entryHtml) = entryResp.use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(IOException("统一认证页面响应异常（HTTP ${resp.code}）"))
                }
                val finalUrl = resp.request.url
                if (finalUrl.host.equals("course.buct.edu.cn", ignoreCase = true) &&
                    finalUrl.encodedPath.startsWith("/meol/") &&
                    !finalUrl.encodedPath.contains("login", ignoreCase = true)
                ) {
                    lastSessionVerifiedAt = System.currentTimeMillis()
                    activeCourseLid = ""
                    activeCourseActivatedAt = 0L
                    return Result.success(Unit)
                }
                finalUrl to decodeResponseBody(resp)
            }

            if (!entryUrl.host.equals("sidportal.buct.edu.cn", ignoreCase = true)) {
                return Result.failure(IOException("统一认证入口发生非预期跳转"))
            }

            var page = TheolParser.parseSidPortalPage(entryHtml)
            if (page.errorCode.isEmpty() && (page.execution.isEmpty() || page.croypto.isEmpty())) {
                cookieJar.clear()
                val retryResp = followAuthRedirects(
                    Request.Builder()
                        .url(loginEntry)
                        .header("User-Agent", BuctWebVpn.SID_PORTAL_UA)
                        .header("Accept", "text/html,application/xhtml+xml")
                        .get()
                        .build(),
                    allowedHosts = setOf("course.buct.edu.cn", "sidportal.buct.edu.cn", "portal.buct.edu.cn")
                )
                val retryPair = retryResp.use { resp ->
                    if (!resp.isSuccessful) {
                        return Result.failure(IOException("统一认证页面响应异常（HTTP ${resp.code}）"))
                    }
                    val finalUrl = resp.request.url
                    if (finalUrl.host.equals("course.buct.edu.cn", ignoreCase = true) &&
                        finalUrl.encodedPath.startsWith("/meol/") &&
                        !finalUrl.encodedPath.contains("login", ignoreCase = true)
                    ) {
                        lastSessionVerifiedAt = System.currentTimeMillis()
                        activeCourseLid = ""
                        activeCourseActivatedAt = 0L
                        return Result.success(Unit)
                    }
                    finalUrl to decodeResponseBody(resp)
                }
                entryUrl = retryPair.first
                entryHtml = retryPair.second
                if (!entryUrl.host.equals("sidportal.buct.edu.cn", ignoreCase = true)) {
                    return Result.failure(IOException("统一认证入口发生非预期跳转"))
                }
                page = TheolParser.parseSidPortalPage(entryHtml)
            }
            if (page.errorCode.isNotEmpty()) {
                return Result.failure(IOException(mapSidError(page.errorCode)))
            }
            if (page.second.equals("true", ignoreCase = true) ||
                (entryHtml.contains("sso-second") && page.second.isNotEmpty())
            ) {
                return Result.failure(IOException("统一身份认证需要短信二次验证，请重新登录统一身份认证"))
            }
            if (page.execution.isEmpty() || page.croypto.isEmpty()) {
                return Result.failure(IOException("认证页面缺少 execution 或 croypto 参数"))
            }

            val pwdString = String(passwordChars)
            val encryptedPassword = BuctAesCrypto.encrypt(page.croypto, pwdString)
            val encryptedCaptchaPayload = BuctAesCrypto.encrypt(page.croypto, "{}")

            val form = FormBody.Builder()
                .add("type", "UsernamePassword")
                .add("_eventId", "submit")
                .add("username", username)
                .add("password", encryptedPassword)
                .add("croypto", page.croypto)
                .add("execution", page.execution)
                .add("captcha_code", "")
                .add("captcha_payload", encryptedCaptchaPayload)
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

            val loginResp = followAuthRedirects(
                postReq,
                allowedHosts = setOf("course.buct.edu.cn", "sidportal.buct.edu.cn", "portal.buct.edu.cn")
            )

            loginResp.use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(IOException("统一认证登录失败（HTTP ${resp.code}）"))
                }
                val finalUrl = resp.request.url
                val body = decodeResponseBody(resp)
                val parsed = TheolParser.parseSidPortalPage(body)
                if (parsed.second.equals("true", ignoreCase = true) ||
                    (body.contains("sso-second") && parsed.second.isNotEmpty()) ||
                    body.contains("smsNoPhoneloginForm")
                ) {
                    return Result.failure(IOException("账号触发了短信二次验证，请先在「我的 → 重新登录」完成教务登录，北化在线会自动复用凭证"))
                }
                if (parsed.errorCode.isNotEmpty()) {
                    return Result.failure(IOException(mapSidError(parsed.errorCode)))
                }
                if (parsed.captchaUrl.isNotEmpty()) {
                    return Result.failure(IOException("需要图形验证码，请先在教务登录页完成登录，北化在线会自动复用凭证"))
                }
                val landedInMeol = finalUrl.host.equals("course.buct.edu.cn", ignoreCase = true) &&
                    finalUrl.encodedPath.startsWith("/meol/") &&
                    resp.request.method.equals("GET", ignoreCase = true) &&
                    parsed.execution.isEmpty() &&
                    parsed.croypto.isEmpty() &&
                    (!finalUrl.encodedPath.contains("login", ignoreCase = true) || finalUrl.encodedPath == "/meol/homepage/common/sso_login.jsp")

                if (!landedInMeol) {
                    return Result.failure(IOException("认证未完成，请检查学号密码或在教务登录页重新登录后点击刷新"))
                }
            }
            lastSessionVerifiedAt = System.currentTimeMillis()
            activeCourseLid = ""
            activeCourseActivatedAt = 0L
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            Arrays.fill(passwordChars, '\u0000')
        }
    }

    private class SessionExpiredSignal(message: String) : IOException(message)

    /** 在服务端 Tomcat HttpSession 中激活当前课程上下文，防止直达作业详情/提交页报 Permission Denied */
    fun enterCourseContext(
        courseLid: String,
        force: Boolean = false,
        touchHomework: Boolean = true
    ): Boolean {
        val lid = courseLid.trim()
        if (lid.isEmpty()) return true
        if (!force && isCourseContextFresh(lid)) return true

        val courseHome = "$MEOL_BASE/meol/jpk/course/layout/newpage/index.jsp?courseId=${URLEncoder.encode(lid, "UTF-8")}"
        val courseHtml = fetchHtml(courseHome, MEOL_WELCOME_HOME)
        if (TheolParser.isPermissionDeniedOrExpired(courseHtml)) {
            activeCourseLid = ""
            activeCourseActivatedAt = 0L
            return false
        }
        if (touchHomework) {
            // 部分课程使用自定义栏目，默认 hwtask.jsp 失败时仍可继续读取单条详情，因此忽略此步错误
            val hwEntry = "$MEOL_BASE/meol/common/hw/student/hwtask.jsp?tagbug=client&strStyle=new03"
            runCatching {
                val req = Request.Builder()
                    .url(hwEntry)
                    .header("User-Agent", UA)
                    .header("Referer", courseHome)
                    .get()
                    .build()
                meolClient.newCall(req).execute().use { /* 同步激活作业栏目上下文 */ }
            }
        }
        activeCourseLid = lid
        activeCourseActivatedAt = System.currentTimeMillis()
        return true
    }

    private fun normalizeSecureCourseUrl(rawUrl: String): String {
        val normalized = normalizeUrl(MEOL_BASE, rawUrl) ?: rawUrl
        return if (normalized.startsWith("http://course.buct.edu.cn", ignoreCase = true)) {
            "https://" + normalized.removePrefix("http://")
        } else {
            normalized
        }
    }

    private fun fetchCourseSubpageHtml(courseLid: String, secureUrl: String, referer: String): String {
        enterCourseContext(courseLid)
        var html = fetchHtml(secureUrl, referer)
        if (TheolParser.isPermissionDeniedOrExpired(html)) {
            if (!ensureSessionActive(forceSso = true) || !enterCourseContext(courseLid, force = true)) {
                throw IOException("北化在线会话已过期，请刷新或重新登录")
            }
            html = fetchHtml(secureUrl, referer)
            if (TheolParser.isPermissionDeniedOrExpired(html)) {
                throw IOException("北化在线提示权限不足或登录超时，请重新登录后重试")
            }
        }
        return html
    }

    /** 拉取单条作业详情（要求、附件等），自动建立课程上下文并在失效时尝试换票重试一次 */
    fun fetchHomeworkDetail(
        item: TheolHomeworkItem,
        nowMillis: Long = System.currentTimeMillis()
    ): TheolHomeworkDetail {
        val rawDetailUrl = item.detailHref.ifBlank { "$MEOL_BASE/meol/common/hw/student/hwtask.view.jsp?hwtid=${item.hwtid}" }
        val secureUrl = normalizeSecureCourseUrl(rawDetailUrl)
        val referer = "$MEOL_BASE/meol/common/hw/student/hwtask.jsp?tagbug=client&strStyle=new03"
        val html = fetchCourseSubpageHtml(item.lid, secureUrl, referer)
        return TheolParser.parseHomeworkDetail(html, secureUrl, item.hwtid, nowMillis)
    }

    /** 拉取单条在线测验详情（view.jsp?testId=...），无法直接定位数字编号时平滑回退列表摘要 */
    fun fetchTestDetail(
        item: TheolTestItem,
        nowMillis: Long = System.currentTimeMillis()
    ): TheolTestDetail {
        val cleanId = item.testId.trim()
        if (cleanId.isEmpty() || !cleanId.all(Char::isDigit) || cleanId.all { it == '0' }) {
            return TheolTestDetail(
                testId = item.testId,
                title = item.title,
                startTime = item.startTime,
                endTime = item.endTime,
                duration = item.duration,
                fetchedAt = nowMillis
            )
        }
        val detailUrl = "$MEOL_BASE/meol/common/question/test/student/view.jsp?testId=${URLEncoder.encode(cleanId, "UTF-8")}"
        val referer = TheolParser.buildTestListUrl(item.lid, MEOL_BASE)
        val html = fetchCourseSubpageHtml(item.lid, detailUrl, referer)
        return TheolParser.parseTestDetail(html, cleanId, nowMillis)
    }

    /**
     * 原生带会话下载作业/课程附件（支持仅限 *.buct.edu.cn 的跳转、失效重登自愈、文件名编码修正及 HTML 网页回退）。
     */
    fun downloadAttachment(
        url: String,
        suggestedName: String,
        courseLid: String,
        cacheRootDir: File,
        allowHtmlDirectRetry: Boolean = true,
        referer: String? = null
    ): TheolDownloadedFile {
        val secureUrl = normalizeSecureCourseUrl(url.trim())
        val parsed = secureUrl.toHttpUrlOrNull() ?: throw IOException("附件链接无效")
        if (!parsed.isHttps || !isTrustedBuctHost(parsed.host)) {
            throw IOException("仅允许通过北化校园域名下载附件")
        }

        val effectiveReferer = referer?.trim()?.takeIf { it.isNotEmpty() }
            ?: "$MEOL_BASE/meol/common/hw/student/hwtask.jsp?tagbug=client&strStyle=new03"
        val baseAttachmentDir = File(cacheRootDir, "theol_attachments")
        if (!baseAttachmentDir.exists()) baseAttachmentDir.mkdirs()

        val isPreviewReferer = effectiveReferer.contains("download_preview.jsp", ignoreCase = true)
        for (attempt in 0..1) {
            enterCourseContext(courseLid, force = attempt > 0, touchHomework = !isPreviewReferer)
            var needsReauth = false
            var htmlDirectRetryUrl: String? = null
            if (isPreviewReferer && allowHtmlDirectRetry) {
                val previewHtml = runCatching {
                    val courseHome = "$MEOL_BASE/meol/jpk/course/layout/newpage/index.jsp?courseId=${URLEncoder.encode(courseLid.trim(), "UTF-8")}"
                    fetchHtml(effectiveReferer, courseHome)
                }.getOrNull()
                if (previewHtml != null && TheolParser.isPermissionDeniedOrExpired(previewHtml)) {
                    if (attempt == 0 && ensureSessionActive(forceSso = true)) {
                        continue
                    }
                    throw IOException("北化在线会话已过期，请刷新或重新登录后重试")
                }
            }
            val downloaded: TheolDownloadedFile? = try {
                followAttachmentRedirects(secureUrl, effectiveReferer).use { resp ->
                    if (resp.code == 401 || resp.code == 403 || isMeolLoginUrl(resp.request.url)) {
                        needsReauth = true
                        return@use null
                    }
                    if (!resp.isSuccessful) {
                        throw IOException("附件下载失败（HTTP ${resp.code}）")
                    }

                    val body = resp.body ?: throw IOException("附件响应内容为空")
                    val contentDisposition = resp.header("Content-Disposition").orEmpty()
                    val contentTypeRaw = resp.header("Content-Type").orEmpty()
                    val mimeHeader = contentTypeRaw.substringBefore(';').trim().lowercase(Locale.ROOT)
                    val isExplicitAttachment = contentDisposition.contains("attachment", ignoreCase = true) ||
                        contentDisposition.contains("filename", ignoreCase = true)

                    if (!isExplicitAttachment && (mimeHeader.contains("text/html") || mimeHeader.contains("application/xhtml"))) {
                        val html = decodeBytes(readBoundedPrefix(body.byteStream(), 128 * 1024), contentTypeRaw)
                        if (TheolParser.isPermissionDeniedOrExpired(html)) {
                            needsReauth = true
                            return@use null
                        }
                        extractJsAlertMessage(html)?.let { alertMsg ->
                            throw IOException(alertMsg)
                        }
                        if (allowHtmlDirectRetry) {
                            val directUrl = TheolParser.extractDirectDownloadUrl(html, resp.request.url.toString())
                            if (!directUrl.isNullOrBlank() && directUrl != secureUrl) {
                                htmlDirectRetryUrl = directUrl
                                return@use null
                            }
                        }
                        return@use TheolDownloadedFile(
                            filePath = "",
                            fileName = suggestedName.ifBlank { "page.html" },
                            mimeType = "text/html",
                            sizeBytes = 0L,
                            isHtmlPage = true
                        )
                    }

                    val stream = body.byteStream()
                    val sniff = ByteArray(4096)
                    var sniffLen = 0
                    if (!isExplicitAttachment && mimeHeader.isEmpty()) {
                        while (sniffLen < sniff.size) {
                            val read = stream.read(sniff, sniffLen, sniff.size - sniffLen)
                            if (read <= 0) break
                            sniffLen += read
                        }
                        val sniffBytes = sniff.copyOf(sniffLen)
                        val probeText = decodeBytes(sniffBytes, contentTypeRaw)
                        if (looksLikeHtmlSnippet(probeText)) {
                            val fullPrefix = readBoundedPrefix(stream, 128 * 1024 - sniffLen, initialBytes = sniffBytes)
                            val fullHtml = decodeBytes(fullPrefix, contentTypeRaw)
                            if (TheolParser.isPermissionDeniedOrExpired(fullHtml)) {
                                needsReauth = true
                                return@use null
                            }
                            extractJsAlertMessage(fullHtml)?.let { alertMsg ->
                                throw IOException(alertMsg)
                            }
                            if (allowHtmlDirectRetry) {
                                val directUrl = TheolParser.extractDirectDownloadUrl(fullHtml, resp.request.url.toString())
                                if (!directUrl.isNullOrBlank() && directUrl != secureUrl) {
                                    htmlDirectRetryUrl = directUrl
                                    return@use null
                                }
                            }
                            return@use TheolDownloadedFile(
                                filePath = "",
                                fileName = suggestedName.ifBlank { "page.html" },
                                mimeType = "text/html",
                                sizeBytes = 0L,
                                isHtmlPage = true
                            )
                        }
                    }

                    val rawName = extractDownloadFileName(contentDisposition, suggestedName, resp.request.url)
                    val safeName = sanitizeAttachmentFileName(rawName, suggestedName)
                    val itemDir = File(baseAttachmentDir, sha256Hex(secureUrl).take(12))
                    if (!itemDir.exists()) itemDir.mkdirs()
                    val targetFile = File(itemDir, safeName)
                    val tmpFile = File(itemDir, "$safeName.download.tmp")

                    var writtenBytes = 0L
                    var success = false
                    try {
                        FileOutputStream(tmpFile).use { fos ->
                            if (sniffLen > 0) {
                                fos.write(sniff, 0, sniffLen)
                                writtenBytes += sniffLen
                            }
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read == -1) break
                                writtenBytes += read
                                if (writtenBytes > MAX_ATTACHMENT_BYTES) {
                                    throw IOException("附件超过 200MB 限制，已中止下载")
                                }
                                fos.write(buffer, 0, read)
                            }
                            fos.flush()
                        }
                        if (writtenBytes <= 0L) {
                            throw IOException("服务器返回的附件内容为空")
                        }
                        if (targetFile.exists()) targetFile.delete()
                        if (!tmpFile.renameTo(targetFile)) {
                            tmpFile.copyTo(targetFile, overwrite = true)
                            tmpFile.delete()
                        }
                        success = true
                    } finally {
                        if (!success) {
                            tmpFile.delete()
                        }
                    }

                    trimAttachmentCacheDir(baseAttachmentDir, excludeDir = itemDir)
                    TheolDownloadedFile(
                        filePath = targetFile.absolutePath,
                        fileName = safeName,
                        mimeType = resolveAttachmentMimeType(safeName, mimeHeader),
                        sizeBytes = writtenBytes,
                        isHtmlPage = false
                    )
                }
            } catch (_: SessionExpiredSignal) {
                needsReauth = true
                null
            }

            if (downloaded != null) {
                return downloaded
            }
            if (!htmlDirectRetryUrl.isNullOrBlank()) {
                return downloadAttachment(
                    url = htmlDirectRetryUrl!!,
                    suggestedName = suggestedName,
                    courseLid = courseLid,
                    cacheRootDir = cacheRootDir,
                    allowHtmlDirectRetry = false,
                    referer = effectiveReferer
                )
            }
            if (needsReauth) {
                if (attempt == 0 && ensureSessionActive(forceSso = true)) {
                    continue
                }
                throw IOException("北化在线会话已过期，请刷新或重新登录后重试")
            }
        }
        throw IOException("附件下载未完成，请稍后重试")
    }

    /** 强制清空服务端激活课程上下文记录（切换课程扫描后调用，防止后续点详情时误用旧上下文） */
    fun invalidateCourseContext() {
        activeCourseLid = ""
        activeCourseActivatedAt = 0L
    }

    /** Phase-1 快照与 Phase-2 待补齐的在修课程目录 */
    data class PendingPhaseResult(
        val snapshot: TheolSnapshot,
        val remainingHwCourses: List<TheolCourseInfo>,
        val remainingTestCourses: List<TheolCourseInfo>
    )

    /**
     * 拉取学生当前在修课程目录（优先 /meol/welcomepage/student/course_list.jsp 约 20 门在修课；
     * 若为空再依次回退 blen.student.lesson.list.jsp 与旧版首页）。
     */
    fun fetchAllCatalogCourses(): List<TheolCourseInfo> {
        val primary = runCatching {
            val html = fetchHtml(MEOL_COURSE_CATALOG_URL, MEOL_WELCOME_HOME)
            if (TheolParser.isPermissionDeniedOrExpired(html)) emptyList()
            else TheolParser.parseCatalogCourses(html, MEOL_COURSE_CATALOG_URL)
        }.getOrDefault(emptyList())
        if (primary.isNotEmpty()) return primary

        val lessonList = runCatching {
            val html = fetchHtml(MEOL_LESSON_LIST_URL, MEOL_WELCOME_HOME)
            if (TheolParser.isPermissionDeniedOrExpired(html)) emptyList()
            else TheolParser.parseCatalogCourses(html, MEOL_LESSON_LIST_URL).take(30)
        }.getOrDefault(emptyList())
        if (lessonList.isNotEmpty()) return lessonList

        return runCatching {
            val html = fetchHtml(MEOL_LEGACY_HOME_URL, MEOL_WELCOME_HOME)
            if (TheolParser.isPermissionDeniedOrExpired(html)) emptyList()
            else TheolParser.parseCatalogCourses(html, MEOL_LEGACY_HOME_URL)
        }.getOrDefault(emptyList())
    }

    /**
     * 拉取学生全部在修/历史课程目录（优先 /meol/lesson/blen.student.lesson.list.jsp 全量无分页列表，
     * 若为空再依次回退 course_list.jsp 与旧版首页）。
     */
    fun fetchFullLessonCatalog(): List<TheolCourseInfo> {
        val fullList = runCatching {
            val html = fetchHtml(MEOL_LESSON_LIST_URL, MEOL_WELCOME_HOME)
            if (TheolParser.isPermissionDeniedOrExpired(html)) emptyList()
            else TheolParser.parseCatalogCourses(html, MEOL_LESSON_LIST_URL)
        }.getOrDefault(emptyList())
        if (fullList.isNotEmpty()) return fullList

        return fetchAllCatalogCourses()
    }

    private fun extractJsAlertMessage(html: String): String? {
        val alertRegex = Regex("""alert\s*\(\s*['"]([^'"]+)['"]\s*\)""")
        val doc = Ksoup.parse(html)
        for (script in doc.select("script:not([src])")) {
            val msg = alertRegex.find(script.data())?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (msg.isNotBlank() && !msg.contains("只有注册用户才可以提交错误报告")) {
                return msg
            }
        }
        return null
    }

    /**
     * 第一阶段（前台秒出）：仅抓取 reminder 页中「待办作业/测验」所属课程，同时拿到待补齐的其余在修课程目录。
     */
    fun fetchPendingPhaseSnapshot(
        nowMillis: Long = System.currentTimeMillis(),
        prefetchedReminderHtml: String? = null
    ): PendingPhaseResult {
        val reminderHtml = prefetchedReminderHtml
            ?.takeIf { !TheolParser.isPermissionDeniedOrExpired(it) }
            ?: fetchHtml(MEOL_REMINDER_URL, MEOL_WELCOME_HOME).also { html ->
                if (TheolParser.isPermissionDeniedOrExpired(html)) {
                    throw IOException("北化在线会话已过期，请重新登录")
                }
                lastSessionVerifiedAt = System.currentTimeMillis()
            }

        val (pendingHwCourses, pendingTestCourses) = TheolParser.parsePendingCourses(reminderHtml, MEOL_BASE)
        val catalogCourses = fetchAllCatalogCourses()
        val fullCourseMap = LinkedHashMap<String, TheolCourseInfo>()
        for (c in fetchFullLessonCatalog()) fullCourseMap.putIfAbsent(c.lid, c)
        for (c in catalogCourses) fullCourseMap.putIfAbsent(c.lid, c)
        val allCourses = fullCourseMap.values.toList()

        val allHomeworks = mutableListOf<TheolHomeworkItem>()
        val seenHwIds = mutableSetOf<String>()
        val allTests = mutableListOf<TheolTestItem>()
        val seenTestIds = mutableSetOf<String>()
        var failedCourses = 0

        invalidateCourseContext()
        for (course in pendingHwCourses) {
            try {
                val items = walkHomeworkPages(course.url, course.lid, course.courseName, nowMillis)
                for (hw in items) {
                    if (seenHwIds.add(hw.hwtid)) {
                        allHomeworks.add(hw)
                    }
                }
            } catch (e: SessionExpiredSignal) {
                throw e
            } catch (_: Exception) {
                failedCourses++
            }
        }
        invalidateCourseContext()

        if (pendingTestCourses.size <= 1) {
            for (course in pendingTestCourses) {
                try {
                    for (t in fetchCourseTests(course.lid, course.courseName, course.url)) {
                        if (seenTestIds.add("${t.lid}:${t.testId}")) {
                            allTests.add(t)
                        }
                    }
                } catch (e: SessionExpiredSignal) {
                    throw e
                } catch (_: Exception) {
                    failedCourses++
                }
            }
        } else {
            val pool = Executors.newFixedThreadPool(minOf(3, pendingTestCourses.size))
            try {
                val futures = pendingTestCourses.map { course ->
                    pool.submit(Callable {
                        fetchCourseTests(course.lid, course.courseName, course.url)
                    })
                }
                for (future in futures) {
                    try {
                        for (t in future.get()) {
                            if (seenTestIds.add("${t.lid}:${t.testId}")) {
                                allTests.add(t)
                            }
                        }
                    } catch (e: java.util.concurrent.ExecutionException) {
                        val cause = e.cause
                        if (cause is SessionExpiredSignal) throw cause
                        failedCourses++
                    }
                }
            } finally {
                pool.shutdown()
            }
        }

        val pendingHwLids = pendingHwCourses.mapTo(HashSet()) { it.lid }
        val pendingTestLids = pendingTestCourses.mapTo(HashSet()) { it.lid }

        val remainingHwCourses = catalogCourses.filter { it.lid !in pendingHwLids }
        val remainingTestCourses = catalogCourses
            .filter { it.lid !in pendingTestLids && it.lid != "24199" && it.lid != "27215" }
            .map {
                TheolCourseInfo(
                    lid = it.lid,
                    courseName = it.courseName,
                    type = "test",
                    url = TheolParser.buildTestListUrl(it.lid, MEOL_BASE)
                )
            }

        return PendingPhaseResult(
            snapshot = TheolSnapshot(
                homeworks = sortHomeworks(allHomeworks),
                tests = sortTests(allTests),
                failedCourseCount = failedCourses,
                courses = allCourses
            ),
            remainingHwCourses = remainingHwCourses,
            remainingTestCourses = remainingTestCourses
        )
    }

    /**
     * 快速扫描单门目录课程的作业列表：
     * 1) 打开课程首页激活 Tomcat 上下文并提取「课程作业」专属栏目链接；
     * 2) 只走作业栏目链接 + 默认 hwtask.jsp 兜底（最多 8 页，不漫游其它无关栏目），单科约 0.3s。
     */
    fun fetchSingleCatalogCourseHomeworksFast(
        course: TheolCourseInfo,
        nowMillis: Long = System.currentTimeMillis()
    ): List<TheolHomeworkItem> {
        val courseHomeUrl = "$MEOL_BASE/meol/jpk/course/layout/newpage/index.jsp?courseId=${URLEncoder.encode(course.lid, "UTF-8")}"
        val courseHtml = fetchHtml(courseHomeUrl, MEOL_WELCOME_HOME)
        if (TheolParser.isPermissionDeniedOrExpired(courseHtml)) {
            throw IOException("北化在线会话已过期，请重新登录")
        }

        val columnUrls = TheolParser.extractHomeworkColumnUrls(courseHtml, courseHomeUrl)
        val fallbackHwUrl = "$MEOL_BASE/meol/common/hw/student/hwtask.jsp?tagbug=client&strStyle=new03"
        val targets = buildList {
            addAll(columnUrls)
            if (columnUrls.none { it.contains("hwtask.jsp", ignoreCase = true) }) {
                add(fallbackHwUrl)
            }
        }

        val found = mutableListOf<TheolHomeworkItem>()
        val seenIds = mutableSetOf<String>()
        for (targetUrl in targets) {
            val items = try {
                walkHomeworkPages(targetUrl, course.lid, course.courseName, nowMillis, maxPages = 8, followOnlyHomeworkAndPaging = true)
            } catch (e: SessionExpiredSignal) {
                throw IOException(e.message ?: "北化在线会话已过期，请重新登录", e)
            }
            for (hw in items) {
                if (seenIds.add(hw.hwtid)) {
                    found.add(hw)
                }
            }
            if (found.isNotEmpty() && columnUrls.isNotEmpty()) break
        }
        invalidateCourseContext()
        return found
    }

    fun fetchSingleCourseTestsSafe(course: TheolCourseInfo): List<TheolTestItem> {
        return try {
            fetchCourseTests(course.lid, course.courseName, course.url)
        } catch (e: SessionExpiredSignal) {
            throw IOException(e.message ?: "北化在线会话已过期，请重新登录", e)
        }
    }

    fun sortHomeworks(list: List<TheolHomeworkItem>): List<TheolHomeworkItem> =
        list.sortedWith(
            compareBy<TheolHomeworkItem> {
                when {
                    it.canSubmit -> 0
                    it.hasResult || it.status == "已完成" -> 1
                    else -> 2
                }
            }.thenComparator { a, b ->
                if (a.canSubmit && b.canSubmit) {
                    compareValues(a.deadlineMillis ?: Long.MAX_VALUE, b.deadlineMillis ?: Long.MAX_VALUE)
                } else {
                    compareValues(b.deadlineMillis ?: 0L, a.deadlineMillis ?: 0L)
                }
            }
        )

    fun sortTests(list: List<TheolTestItem>): List<TheolTestItem> =
        list.sortedWith(
            compareBy<TheolTestItem> {
                when {
                    it.canStart -> 0
                    it.hasResult || it.status == "已完成" -> 1
                    else -> 2
                }
            }.thenBy { it.endTimeMillis ?: Long.MAX_VALUE }
        )

    /** 兼容同步完整抓取（先执行 Phase-1，再顺序快速补齐 Phase-2 在修课程） */
    fun fetchPendingSnapshot(
        nowMillis: Long = System.currentTimeMillis(),
        prefetchedReminderHtml: String? = null
    ): TheolSnapshot {
        val phase1 = fetchPendingPhaseSnapshot(nowMillis, prefetchedReminderHtml)
        val mergedHw = phase1.snapshot.homeworks.toMutableList()
        val seenHw = mergedHw.mapTo(HashSet()) { it.hwtid }
        val mergedTests = phase1.snapshot.tests.toMutableList()
        val seenTests = mergedTests.mapTo(HashSet()) { "${it.lid}:${it.testId}" }
        var failed = phase1.snapshot.failedCourseCount

        for (c in phase1.remainingHwCourses) {
            try {
                for (hw in fetchSingleCatalogCourseHomeworksFast(c, nowMillis)) {
                    if (seenHw.add(hw.hwtid)) mergedHw.add(hw)
                }
            } catch (e: IOException) {
                if (e.message?.contains("过期") == true) throw e
                failed++
            } catch (_: Exception) {
                failed++
            }
        }
        for (c in phase1.remainingTestCourses) {
            try {
                for (t in fetchSingleCourseTestsSafe(c)) {
                    if (seenTests.add("${t.lid}:${t.testId}")) mergedTests.add(t)
                }
            } catch (e: IOException) {
                if (e.message?.contains("过期") == true) throw e
                failed++
            } catch (_: Exception) {
                failed++
            }
        }
        return TheolSnapshot(
            homeworks = sortHomeworks(mergedHw),
            tests = sortTests(mergedTests),
            failedCourseCount = failed,
            courses = phase1.snapshot.courses
        )
    }

    private fun fetchCourseTests(lid: String, courseName: String, url: String): List<TheolTestItem> {
        val referer = "$MEOL_BASE/meol/jpk/course/layout/newpage/index.jsp?courseId=$lid"
        val testHtml = fetchHtml(url, referer)
        if (TheolParser.isPermissionDeniedOrExpired(testHtml)) {
            throw SessionExpiredSignal("课程会话已过期")
        }
        return TheolParser.parseTestList(testHtml, lid, courseName, MEOL_BASE)
    }

    /** 只读拉取指定课程的「单元学习」目录列表 */
    fun fetchCourseUnits(lid: String): List<TheolCourseUnit> {
        val cleanLid = lid.trim()
        val encodedLid = URLEncoder.encode(cleanLid, "UTF-8")
        val courseHomeUrl = "$MEOL_BASE/meol/jpk/course/layout/newpage/index.jsp?courseId=$encodedLid"
        val courseHtml = fetchHtml(courseHomeUrl, MEOL_WELCOME_HOME)
        if (TheolParser.isPermissionDeniedOrExpired(courseHtml)) {
            activeCourseLid = ""
            activeCourseActivatedAt = 0L
            throw SessionExpiredSignal("课程会话已过期")
        }
        activeCourseLid = cleanLid
        activeCourseActivatedAt = System.currentTimeMillis()
        return TheolParser.parseCourseUnits(courseHtml, courseHomeUrl)
    }

    /** 只读拉取指定「单元学习」节点下的资源 / 课件内容 */
    fun fetchUnitResources(
        lid: String,
        courseName: String,
        unit: TheolCourseUnit
    ): TheolResourcePage {
        val secureUnitUrl = normalizeSecureCourseUrl(unit.url.trim())
        val parsed = secureUnitUrl.toHttpUrlOrNull() ?: throw IOException("单元链接无效")
        if (!parsed.isHttps || !isTrustedBuctHost(parsed.host)) {
            throw IOException("仅允许访问北化校园域名资源")
        }
        val cleanLid = lid.trim()
        val encodedLid = URLEncoder.encode(cleanLid, "UTF-8")
        val courseHomeUrl = "$MEOL_BASE/meol/jpk/course/layout/newpage/index.jsp?courseId=$encodedLid"
        if (!isCourseContextFresh(cleanLid)) {
            val courseHtml = fetchHtml(courseHomeUrl, MEOL_WELCOME_HOME)
            if (TheolParser.isPermissionDeniedOrExpired(courseHtml)) {
                activeCourseLid = ""
                activeCourseActivatedAt = 0L
                throw SessionExpiredSignal("课程会话已过期")
            }
            activeCourseLid = cleanLid
            activeCourseActivatedAt = System.currentTimeMillis()
        }
        return fetchResourcePageWithUnwrap(
            startUrl = secureUnitUrl,
            referer = courseHomeUrl,
            lid = cleanLid,
            courseName = courseName,
            fallbackFolderTitle = unit.title
        ) ?: TheolResourcePage(
            lid = cleanLid,
            courseName = courseName,
            currentUrl = secureUnitUrl,
            folderTitle = unit.title.ifBlank { "单元学习" },
            items = emptyList()
        )
    }

    /** 只读拉取指定课程的教学资源 / 课件目录页 */
    fun fetchCourseResources(
        lid: String,
        courseName: String,
        folderUrl: String? = null,
        folderTitle: String? = null
    ): TheolResourcePage {
        val secureFolderUrl = folderUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val normalized = normalizeSecureCourseUrl(raw)
            val parsed = normalized.toHttpUrlOrNull() ?: throw IOException("课程资源链接无效")
            if (!parsed.isHttps || !isTrustedBuctHost(parsed.host)) {
                throw IOException("仅允许访问北化校园域名资源")
            }
            normalized
        }

        val cleanLid = lid.trim()
        val encodedLid = URLEncoder.encode(cleanLid, "UTF-8")
        val courseHomeUrl = "$MEOL_BASE/meol/jpk/course/layout/newpage/index.jsp?courseId=$encodedLid"
        var cachedCourseHtml: String? = null

        fun ensureActiveCourseHtml(): String {
            cachedCourseHtml?.let { return it }
            val html = fetchHtml(courseHomeUrl, MEOL_WELCOME_HOME)
            if (TheolParser.isPermissionDeniedOrExpired(html)) {
                activeCourseLid = ""
                activeCourseActivatedAt = 0L
                throw SessionExpiredSignal("课程会话已过期")
            }
            activeCourseLid = cleanLid
            activeCourseActivatedAt = System.currentTimeMillis()
            cachedCourseHtml = html
            return html
        }

        if (!isCourseContextFresh(cleanLid)) {
            ensureActiveCourseHtml()
        }

        if (secureFolderUrl != null) {
            return fetchResourcePageWithUnwrap(
                startUrl = secureFolderUrl,
                referer = courseHomeUrl,
                lid = cleanLid,
                courseName = courseName,
                fallbackFolderTitle = folderTitle
            ) ?: TheolResourcePage(
                lid = cleanLid,
                courseName = courseName,
                currentUrl = secureFolderUrl,
                folderTitle = folderTitle?.takeIf { it.isNotBlank() } ?: courseName.ifBlank { "课程资料" },
                items = emptyList()
            )
        }

        // 首选实测确认的根目录直达地址（groupid=4&lid=<courseId>&folderid=0）
        val directRootUrl = "$MEOL_BASE/meol/common/script/listview.jsp?groupid=4&lid=$encodedLid&folderid=0"
        val directHtml = runCatching { fetchHtml(directRootUrl, courseHomeUrl) }.getOrNull()
        if (directHtml != null) {
            if (TheolParser.isPermissionDeniedOrExpired(directHtml)) {
                throw SessionExpiredSignal("课程会话已过期")
            }
            val directParsed = TheolParser.parseResourceList(
                html = directHtml,
                pageUrl = directRootUrl,
                lid = cleanLid,
                courseName = courseName,
                fallbackFolderTitle = folderTitle
            )
            if (directParsed.items.isNotEmpty() || directHtml.contains("class=\"valuelist\"", ignoreCase = true)) {
                return directParsed
            }
        }

        val courseHtml = ensureActiveCourseHtml()
        val homeDoc = Ksoup.parse(courseHtml, courseHomeUrl)
        val candidates = linkedSetOf<String>()
        candidates.addAll(TheolParser.discoverResourceEntryUrls(homeDoc, courseHomeUrl))

        for (frame in homeDoc.select("frame[src], iframe[src]")) {
            val frameUrl = normalizeUrl(courseHomeUrl, frame.attr("src")) ?: continue
            val frameHtml = runCatching { fetchHtml(frameUrl, courseHomeUrl) }.getOrNull() ?: continue
            val frameDoc = Ksoup.parse(frameHtml, frameUrl)
            candidates.addAll(TheolParser.discoverResourceEntryUrls(frameDoc, frameUrl))
        }

        candidates += "$MEOL_BASE/meol/common/script/listview.jsp?lid=$encodedLid"
        candidates += "$MEOL_BASE/meol/common/script/listview.jsp?courseId=$encodedLid"

        var firstValidPage: TheolResourcePage? = null
        for (candidateUrl in candidates.take(6)) {
            val parsed = runCatching {
                fetchResourcePageWithUnwrap(
                    startUrl = candidateUrl,
                    referer = courseHomeUrl,
                    lid = cleanLid,
                    courseName = courseName,
                    fallbackFolderTitle = folderTitle
                )
            }.getOrElse { err ->
                if (err is SessionExpiredSignal) throw err
                null
            } ?: continue
            if (firstValidPage == null) firstValidPage = parsed
            if (parsed.items.isNotEmpty()) return parsed
        }
        return firstValidPage ?: TheolResourcePage(
            lid = cleanLid,
            courseName = courseName,
            currentUrl = courseHomeUrl,
            folderTitle = folderTitle?.takeIf { it.isNotBlank() } ?: courseName.ifBlank { "课程资料" },
            items = emptyList()
        )
    }

    private fun fetchResourcePageWithUnwrap(
        startUrl: String,
        referer: String,
        lid: String,
        courseName: String,
        fallbackFolderTitle: String? = null
    ): TheolResourcePage? {
        var currentUrl = startUrl
        var currentReferer = referer
        val visited = mutableSetOf(startUrl)
        var lastPage: TheolResourcePage? = null

        for (hop in 0..2) {
            val html = fetchHtml(currentUrl, currentReferer)
            if (TheolParser.isPermissionDeniedOrExpired(html)) {
                throw SessionExpiredSignal("课程会话已过期")
            }
            val parsed = TheolParser.parseResourceList(
                html = html,
                pageUrl = currentUrl,
                lid = lid,
                courseName = courseName,
                fallbackFolderTitle = fallbackFolderTitle
            )
            lastPage = parsed
            if (parsed.items.isNotEmpty()) {
                return parsed
            }
            val doc = Ksoup.parse(html, currentUrl)
            val nextUrl = TheolParser.extractResourceRedirectUrl(doc, currentUrl)
            if (nextUrl.isNullOrBlank() || !visited.add(nextUrl)) {
                break
            }
            currentReferer = currentUrl
            currentUrl = nextUrl
        }
        return lastPage
    }

    private fun walkHomeworkPages(
        startUrl: String,
        lid: String,
        courseName: String,
        nowMillis: Long,
        maxPages: Int = MAX_COURSE_PAGES,
        followOnlyHomeworkAndPaging: Boolean = false
    ): List<TheolHomeworkItem> {
        data class QueueEntry(val url: String, val referer: String, val depth: Int)

        val queue = ArrayDeque<QueueEntry>()
        val visited = mutableSetOf<String>()
        val seenTableSignatures = mutableSetOf<String>()
        val found = mutableListOf<TheolHomeworkItem>()
        val seenIds = mutableSetOf<String>()

        val normalizedStart = normalizeUrl(MEOL_BASE, startUrl) ?: return emptyList()
        if (!isAllowedHomeworkUrl(normalizedStart)) return emptyList()
        queue.addLast(QueueEntry(normalizedStart, "$MEOL_BASE/", 0))
        visited.add(normalizedStart)

        var fetched = 0
        while (queue.isNotEmpty() && fetched < maxPages) {
            val current = queue.removeFirst()
            if (current.depth > MAX_COURSE_DEPTH) continue
            fetched++

            val req = Request.Builder()
                .url(current.url)
                .header("User-Agent", UA)
                .header("Referer", current.referer)
                .get()
                .build()

            noRedirectClient.newCall(req).execute().use { resp ->
                if (resp.code in 300..399) {
                    val location = resp.header("Location").orEmpty()
                    val next = normalizeUrl(current.url, location) ?: return@use
                    if (next.contains("login", ignoreCase = true)) {
                        throw SessionExpiredSignal("课程会话已过期")
                    }
                    if (isAllowedHomeworkUrl(next) && visited.add(next)) {
                        queue.addFirst(QueueEntry(next, current.url, current.depth + 1))
                    }
                    return@use
                }
                if (!resp.isSuccessful) return@use
                val html = decodeResponseBody(resp)
                if (TheolParser.isPermissionDeniedOrExpired(html)) {
                    throw SessionExpiredSignal("课程会话已过期")
                }

                // 单次 Jsoup 解析同时供作业抽取与子栏目发现使用，避免重复 parse
                val doc = Ksoup.parse(html, current.url)
                for (hw in TheolParser.parseHomeworkPage(doc, current.url, lid, courseName, nowMillis)) {
                    if (seenIds.add(hw.hwtid)) found.add(hw)
                }

                val parsedUrl = runCatching { current.url.toHttpUrl() }.getOrNull()
                val currentPath = parsedUrl?.encodedPath.orEmpty()
                val pagingPageStr = parsedUrl?.queryParameter("pagingPage").orEmpty()
                val table = doc.selectFirst("table.valuelist")
                var repeated = false
                if (table != null && parsedUrl != null && pagingPageStr.isNotEmpty()) {
                    val seriesUrl = parsedUrl.newBuilder().removeAllQueryParameters("pagingPage").build().toString()
                    val signature = sha256Hex("$seriesUrl\u0000${table.html()}")
                    repeated = !seenTableSignatures.add(signature)
                }

                fun enqueue(raw: String, sameDepth: Boolean = false) {
                    val next = normalizeUrl(current.url, raw) ?: return
                    if (isAllowedHomeworkUrl(next) && visited.add(next)) {
                        val nextDepth = if (sameDepth) current.depth else current.depth + 1
                        queue.addLast(QueueEntry(next, current.url, nextDepth))
                    }
                }

                doc.select("iframe[src], frame[src]").forEach { enqueue(it.attr("src")) }
                doc.select("meta[http-equiv]").forEach {
                    if (it.attr("http-equiv").equals("refresh", ignoreCase = true)) {
                        val match = REFRESH_REGEX.find(it.attr("content"))
                        if (match != null) enqueue(match.groupValues[1].trim(' ', '"', '\''))
                    }
                }
                doc.select("script:not([src])").forEach { script ->
                    LOCATION_REGEX.findAll(script.data()).forEach { m ->
                        val target = m.groupValues[1].ifEmpty { m.groupValues[2] }
                        if (target.isNotEmpty()) enqueue(target)
                    }
                }
                doc.select("a[href], a[onclick]").forEach { a ->
                    val href = a.attr("href")
                    if (href.isNotEmpty()) {
                        val targetPath = normalizeUrl(current.url, href)
                            ?.let { u -> runCatching { u.toHttpUrl().encodedPath }.getOrNull() }
                        val allowLink = !followOnlyHomeworkAndPaging ||
                            href.contains("pagingPage=", ignoreCase = true) ||
                            href.contains("hwtask", ignoreCase = true) ||
                            (a.text().contains("作业") && !a.text().contains("测试"))
                        if (allowLink) {
                            if (targetPath == currentPath) {
                                if (!repeated) enqueue(href, sameDepth = true)
                            } else {
                                enqueue(href)
                            }
                        }
                    }
                    if (!followOnlyHomeworkAndPaging) {
                        val onclick = a.attr("onclick")
                        if (onclick.isNotEmpty()) {
                            LOCATION_REGEX.findAll(onclick).forEach { m ->
                                val target = m.groupValues[1].ifEmpty { m.groupValues[2] }
                                if (target.isNotEmpty()) enqueue(target)
                            }
                            QUOTED_URL_REGEX.findAll(onclick).forEach { m ->
                                val quoted = m.groupValues[1]
                                if (quoted.contains("columnId=") || quoted.contains("pagingPage=")) {
                                    if (!repeated) enqueue(quoted, sameDepth = true)
                                } else {
                                    enqueue(quoted)
                                }
                            }
                        }
                    }
                }
                val currentPage = pagingPageStr.toIntOrNull() ?: 0
                val perPage = parsedUrl?.queryParameter("pagingNumberPer")?.toIntOrNull() ?: 0
                if (!repeated && parsedUrl != null && currentPage > 0 && perPage > 0 && table != null) {
                    val dataRows = table.select("tr").count { it.select("> td").isNotEmpty() }
                    if (dataRows >= perPage) {
                        val nextUrl = parsedUrl.newBuilder()
                            .setQueryParameter("pagingPage", (currentPage + 1).toString())
                            .build()
                            .toString()
                        enqueue(nextUrl, sameDepth = true)
                    }
                }
            }
        }
        return found
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun checkCaptchaPreflight(username: String, referer: String) {
        val randomBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val csrfKey = randomBytes.joinToString("") { "%02x".format(it) }
        val encoded = Base64.encodeToString(csrfKey.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val half = encoded.length / 2
        val combined = encoded.substring(0, half) + encoded + encoded.substring(half)
        val md5 = MessageDigest.getInstance("MD5")
            .digest(combined.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val encodedUser = URLEncoder.encode(username, "UTF-8")
        val req = Request.Builder()
            .url("$SID_PORTAL_BASE/api/protected/user/findCaptchaCount/$encodedUser")
            .header("User-Agent", UA)
            .header("Csrf-Key", csrfKey)
            .header("Csrf-Value", md5)
            .header("Referer", referer)
            .get()
            .build()

        noRedirectClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("验证码预检失败（HTTP ${resp.code}）")
            }
            val json = JSONObject(resp.body?.string().orEmpty())
            val code = json.optInt("code", -1)
            val data = json.optJSONObject("data")
            if (code != 200 || data == null || !data.has("captchaInvisible")) {
                throw IOException("无法确认验证码要求")
            }
            if (data.optBoolean("captchaInvisible", false)) {
                throw IOException(mapSidError("1320007"))
            }
        }
    }

    /** 仅跟随可信域名的 GET 重定向，拒绝 307/308 重放带凭据的 POST */
    private fun followAuthRedirects(initial: Request, allowedHosts: Set<String>): Response {
        var currentReq = initial
        var hops = 0
        while (hops < 10) {
            val resp = noRedirectClient.newCall(currentReq).execute()
            if (resp.code !in 300..399) {
                return resp
            }
            val location = resp.header("Location")
            val code = resp.code
            resp.close()
            if (location.isNullOrBlank()) {
                throw IOException("认证重定向缺少 Location")
            }
            if (code == 307 || code == 308) {
                throw IOException("拒绝在 307/308 重定向中重放凭据")
            }
            val resolved = currentReq.url.resolve(location)
                ?: throw IOException("非法的重定向地址")
            val hostLower = resolved.host.lowercase()
            if (hostLower !in allowedHosts) {
                throw IOException("拒绝跳转到非信任域名: ${resolved.host}")
            }
            val upgradedUrl = if (!resolved.isHttps && hostLower == "course.buct.edu.cn") {
                resolved.newBuilder().scheme("https").build()
            } else if (!resolved.isHttps) {
                throw IOException("拒绝非 HTTPS 重定向: ${resolved.host}")
            } else {
                resolved
            }
            val safeUrl = if (
                upgradedUrl.host.equals("course.buct.edu.cn", ignoreCase = true) &&
                upgradedUrl.encodedPath.equals(MEOL_LEGACY_HOME_PATH, ignoreCase = true)
            ) {
                MEOL_WELCOME_HOME.toHttpUrl()
            } else {
                upgradedUrl
            }
            currentReq = Request.Builder()
                .url(safeUrl)
                .header("User-Agent", BuctWebVpn.userAgentFor(safeUrl, UA))
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()
            hops++
        }
        throw IOException("认证重定向次数过多")
    }

    private fun fetchHtml(url: String, referer: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .get()
            .build()
        meolClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("北化在线请求失败（HTTP ${resp.code}）")
            }
            return decodeResponseBody(resp)
        }
    }

    private fun decodeResponseBody(resp: Response): String {
        val bytes = resp.body?.bytes() ?: return ""
        return decodeBytes(bytes, resp.header("Content-Type").orEmpty())
    }

    private fun decodeBytes(bytes: ByteArray, contentType: String): String {
        if (bytes.isEmpty()) return ""
        val headerCharset = CHARSET_REGEX.find(contentType)?.groupValues?.getOrNull(1)
        val probeHead = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val metaCharset = CHARSET_REGEX.find(probeHead)?.groupValues?.getOrNull(1)
        val declared = headerCharset ?: metaCharset
        if (declared != null) {
            val charsetName = declared.uppercase(Locale.ROOT)
            val charset = runCatching {
                when {
                    charsetName.contains("GB") -> Charset.forName("GBK")
                    else -> Charset.forName(charsetName)
                }
            }.getOrDefault(Charsets.UTF_8)
            return String(bytes, charset)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            Charset.forName("GBK").decode(ByteBuffer.wrap(bytes)).toString()
        }
    }

    private fun followAttachmentRedirects(initialUrl: String, referer: String): Response {
        var currentReq = Request.Builder()
            .url(initialUrl)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .get()
            .build()
        var hops = 0
        while (hops < 6) {
            val resp = downloadClient.newCall(currentReq).execute()
            if (resp.code !in 300..399) {
                return resp
            }
            val location = resp.header("Location")
            resp.close()
            if (location.isNullOrBlank()) {
                throw IOException("附件跳转缺少 Location")
            }
            val resolved = currentReq.url.resolve(location)
                ?: throw IOException("非法的附件跳转地址")
            if (!isTrustedBuctHost(resolved.host)) {
                throw IOException("拒绝跳转到非校园域名: ${resolved.host}")
            }
            val safeUrl = when {
                !resolved.isHttps && resolved.host.equals("course.buct.edu.cn", ignoreCase = true) ->
                    resolved.newBuilder().scheme("https").build()
                !resolved.isHttps ->
                    throw IOException("拒绝非 HTTPS 下载地址: ${resolved.host}")
                else -> resolved
            }
            if (isMeolLoginUrl(safeUrl)) {
                throw SessionExpiredSignal("北化在线会话已过期，请刷新或重新登录")
            }
            currentReq = Request.Builder()
                .url(safeUrl)
                .header("User-Agent", UA)
                .header("Referer", currentReq.url.toString())
                .get()
                .build()
            hops++
        }
        throw IOException("附件重定向次数过多")
    }

    private fun isTrustedBuctHost(host: String): Boolean {
        val lower = host.lowercase(Locale.ROOT)
        return lower == "buct.edu.cn" || lower.endsWith(".buct.edu.cn")
    }

    private fun isMeolLoginUrl(url: HttpUrl): Boolean {
        val host = url.host.lowercase(Locale.ROOT)
        val path = url.encodedPath.lowercase(Locale.ROOT)
        return host == "sidportal.buct.edu.cn" ||
            host == "portal.buct.edu.cn" ||
            (path.contains("login") && path != "/meol/homepage/common/sso_login.jsp")
    }

    private fun readBoundedPrefix(
        stream: java.io.InputStream,
        maxAdditionalBytes: Int,
        initialBytes: ByteArray = ByteArray(0)
    ): ByteArray {
        val out = ByteArrayOutputStream(initialBytes.size + minOf(maxAdditionalBytes.coerceAtLeast(0), 16384))
        if (initialBytes.isNotEmpty()) out.write(initialBytes)
        val buf = ByteArray(4096)
        var remaining = maxAdditionalBytes.coerceAtLeast(0)
        while (remaining > 0) {
            val read = stream.read(buf, 0, minOf(buf.size, remaining))
            if (read <= 0) break
            out.write(buf, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    private fun looksLikeHtmlSnippet(text: String): Boolean {
        val trimmed = text.trimStart().lowercase(Locale.ROOT)
        return trimmed.startsWith("<!doctype html") ||
            trimmed.startsWith("<html") ||
            TheolParser.isPermissionDeniedOrExpired(text)
    }

    private fun extractDownloadFileName(
        contentDisposition: String,
        suggestedName: String,
        finalUrl: HttpUrl
    ): String {
        if (contentDisposition.isNotBlank()) {
            val starMatch = FILENAME_STAR_REGEX.find(contentDisposition)
            if (starMatch != null) {
                val charset = starMatch.groupValues[1].ifBlank { "UTF-8" }
                val raw = starMatch.groupValues[2].trim().trim('"', '\'')
                val decoded = runCatching { URLDecoder.decode(raw, charset) }.getOrDefault(raw)
                if (decoded.isNotBlank()) return decoded
            }
            val basicMatch = FILENAME_BASIC_REGEX.find(contentDisposition)
            if (basicMatch != null) {
                val raw = basicMatch.groupValues[1].ifBlank { basicMatch.groupValues[2] }.trim().trim('"', '\'')
                val fixed = recoverHeaderFilename(raw)
                if (fixed.isNotBlank()) return fixed
            }
        }
        if (suggestedName.isNotBlank()) return suggestedName.trim()
        val lastSeg = finalUrl.pathSegments.lastOrNull { it.isNotBlank() && !it.endsWith(".jsp", ignoreCase = true) }
        return lastSeg ?: "attachment.bin"
    }

    private fun recoverHeaderFilename(raw: String): String {
        if (raw.isBlank()) return ""
        val urlDecoded = if (raw.contains('%')) {
            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        } else {
            raw
        }
        val onlyLatin1Range = urlDecoded.any { it.code in 0x80..0xFF } && urlDecoded.none { it.code > 0xFF }
        if (!onlyLatin1Range) return urlDecoded
        val rawBytes = urlDecoded.toByteArray(Charsets.ISO_8859_1)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(rawBytes))
                .toString()
        } catch (_: Exception) {
            Charset.forName("GBK").decode(ByteBuffer.wrap(rawBytes)).toString()
        }
    }

    private fun sanitizeAttachmentFileName(rawName: String, suggestedName: String): String {
        var cleaned = rawName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim(' ', '.')
        if (cleaned.isEmpty()) cleaned = "attachment"
        val suggestedExt = suggestedName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it.length <= 10 && it.all(Char::isLetterOrDigit) }
            .orEmpty()
        val currentExt = cleaned.substringAfterLast('.', "")
            .takeIf { cleaned.contains('.') && it.length <= 10 && it.all(Char::isLetterOrDigit) }
            .orEmpty()
        if (currentExt.isEmpty() && suggestedExt.isNotEmpty()) {
            cleaned = "$cleaned.$suggestedExt"
        }
        if (cleaned.length > 120) {
            val extPart = if (currentExt.isNotEmpty()) ".$currentExt" else if (suggestedExt.isNotEmpty()) ".$suggestedExt" else ""
            cleaned = cleaned.take(120 - extPart.length) + extPart
        }
        return cleaned
    }

    private fun resolveAttachmentMimeType(fileName: String, headerMime: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isNotEmpty()) {
            EXT_MIME_MAP[ext]?.let { return it }
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        if (headerMime.isNotBlank() && headerMime != "application/octet-stream") {
            return headerMime
        }
        return "application/octet-stream"
    }

    private fun trimAttachmentCacheDir(baseDir: File, excludeDir: File) {
        val children = baseDir.listFiles()?.toList().orEmpty()
        var total = children.sumOf { dirSize(it) }
        if (total <= ATTACHMENT_CACHE_LIMIT_BYTES) return
        val candidates = children
            .filter { it.absolutePath != excludeDir.absolutePath }
            .sortedBy { it.lastModified() }
        for (dir in candidates) {
            if (total <= ATTACHMENT_CACHE_TARGET_BYTES) break
            val size = dirSize(dir)
            if (dir.deleteRecursively()) {
                total -= size
            }
        }
    }

    private fun dirSize(file: File): Long =
        if (file.isDirectory) file.listFiles()?.sumOf { dirSize(it) } ?: 0L else file.length()

    private fun normalizeUrl(base: String, raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("javascript:", true)) return null
        return runCatching {
            val resolved = URL(URL(base), trimmed)
            val uri = URI(resolved.protocol, resolved.authority, resolved.path, resolved.query, null).normalize()
            uri.toString()
        }.getOrNull()
    }

    private fun isAllowedHomeworkUrl(urlStr: String): Boolean {
        val parsed = runCatching { urlStr.toHttpUrl() }.getOrNull() ?: return false
        return parsed.isHttps &&
            parsed.host.equals("course.buct.edu.cn", ignoreCase = true) &&
            parsed.encodedPath in ALLOWED_HW_PATHS
    }

    private fun mapSidError(code: String): String = when (code) {
        "1030027", "1030031" -> "学号或密码错误"
        "1030028" -> "账号已锁定，请稍后再试"
        "1410040", "1410041" -> "当前账号不可用"
        "1320007" -> "需要图形验证码，请先在教务登录完成验证"
        "1030007" -> "验证码不正确"
        "1330001" -> "触发风险验证"
        else -> "北化在线统一认证未完成（$code）"
    }
}
