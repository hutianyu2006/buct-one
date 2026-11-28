package com.buct.course.login

import android.os.SystemClock
import com.buct.one.platform.network.WebVpnCodec
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 校外访问校内系统时无法建立或刷新 WebVPN（w.buct.edu.cn）统一身份认证会话。
 */
class BuctWebVpnAuthRequiredException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * 北化 WebVPN（w.buct.edu.cn，Wengine 网关）URL 编解码、按需认证与降级状态管理。
 *
 * 仅用于仅限校内/VPN 访问的业务域（scs / eapi / ecard / gmis）：
 * - 直连失败（连接/超时/DNS/路由不可达）后，自动通过统一身份认证（sidportal）换取
 *   wengine_vpn_ticketw_buct_edu_cn 并改写请求走 https://w.buct.edu.cn/{scheme}/{hexHost}/...；
 * - 不直接依赖上层 store/sync/ui 或 data.session，由 Application 注入 sidportal 读写回调。
 */
object BuctWebVpn {
    const val VPN_HOST = "w.buct.edu.cn"
    const val VPN_BASE = "https://w.buct.edu.cn"
    const val VPN_COOKIE_NAME = "wengine_vpn_ticketw_buct_edu_cn"
    const val HEADER_VIA_WEBVPN = "X-Buct-Via-WebVpn"
    internal const val HEADER_VPN_TICKET_INTERNAL = "X-Buct-Vpn-Ticket"

    private const val SID_PORTAL_HOST = "sidportal.buct.edu.cn"
    private const val SID_PORTAL_BASE = "https://sidportal.buct.edu.cn"

    private const val PREFER_VPN_TTL_MS = 300_000L
    private const val TICKET_SOFT_TTL_MS = 1_800_000L
    private const val RELOGIN_FAILURE_COOLDOWN_MS = 30_000L
    const val SID_PORTAL_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/139.0.0.0 Mobile Safari/537.36"

    val CAMPUS_ONLY_HOSTS: Set<String> = setOf(
        "scs.buct.edu.cn",
        "eapi.buct.edu.cn",
        "ecard.buct.edu.cn",
        "gmis.buct.edu.cn",
        "i.buct.edu.cn",
        "mnews.buct.edu.cn",
        "tree.buct.edu.cn"
    )

    @Volatile
    private var readSidCookiesFn: (() -> String)? = null

    @Volatile
    private var saveSidCookiesFn: ((String) -> Unit)? = null

    @Volatile
    private var readSavedCredentialsFn: (() -> Pair<String, CharArray>?)? = null

    @Volatile
    private var readVpnTicketFn: (() -> String)? = null

    @Volatile
    private var saveVpnTicketFn: ((String) -> Unit)? = null

    private val ticketLock = Any()

    @Volatile
    private var cachedTicket: String = ""

    @Volatile
    private var ticketFetchedAtMs: Long = 0L

    private val preferVpnUntilByHost = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastReloginFailureMs: Long = 0L

    @Volatile
    private var lastReloginFailureReason: String = ""

    @Volatile
    var lastFailureReason: String = ""
        private set

    private val vpnHttpClient: OkHttpClient by lazy {
        val trustManager = BuctTlsTrustManager()
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
            .dns(BuctDns)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun configure(
        readSidCookies: () -> String,
        saveSidCookies: (String) -> Unit,
        readSavedCredentials: () -> Pair<String, CharArray>?,
        readVpnTicket: () -> String = { "" },
        saveVpnTicket: (String) -> Unit = {}
    ) {
        readSidCookiesFn = readSidCookies
        saveSidCookiesFn = saveSidCookies
        readSavedCredentialsFn = readSavedCredentials
        readVpnTicketFn = readVpnTicket
        saveVpnTicketFn = saveVpnTicket
    }

    private fun saveSidCookiesIfAuthenticated(rawCookies: String) {
        val trimmed = rawCookies.trim()
        if (trimmed.isNotBlank() && trimmed.contains("SOURCEID_TGC=", ignoreCase = true)) {
            saveSidCookiesFn?.invoke(trimmed)
        }
    }

    fun updateSidCookies(cookieHeader: String) {
        val trimmed = cookieHeader.trim()
        if (trimmed.isNotBlank() && trimmed.contains("SOURCEID_TGC=", ignoreCase = true)) {
            saveSidCookiesFn?.invoke(trimmed)
            lastReloginFailureMs = 0L
            lastReloginFailureReason = ""
        }
    }

    fun updateTicket(ticket: String) {
        val trimmed = ticket.trim()
        if (trimmed.isEmpty() || trimmed.equals("deleteMe", ignoreCase = true)) return
        synchronized(ticketLock) {
            cachedTicket = trimmed
            ticketFetchedAtMs = SystemClock.elapsedRealtime()
            lastReloginFailureMs = 0L
            lastReloginFailureReason = ""
            lastFailureReason = ""
        }
        saveVpnTicketFn?.invoke(trimmed)
    }

    fun hasCachedTicket(): Boolean = synchronized(ticketLock) {
        cachedTicket.isNotBlank() || readVpnTicketFn?.invoke().orEmpty().isNotBlank()
    }

    fun isCampusOnlyHost(host: String): Boolean =
        host.trim().lowercase(Locale.ROOT) in CAMPUS_ONLY_HOSTS

    fun userAgentFor(url: HttpUrl, businessUa: String): String =
        if (url.host.equals(SID_PORTAL_HOST, ignoreCase = true) ||
            url.host.equals("portal.buct.edu.cn", ignoreCase = true)
        ) {
            SID_PORTAL_UA
        } else {
            businessUa
        }

    fun isCampusOnlyUrl(url: HttpUrl): Boolean = isCampusOnlyHost(url.host)

    fun shouldPreferVpn(host: String): Boolean {
        val until = preferVpnUntilByHost[host.trim().lowercase(Locale.ROOT)] ?: return false
        return SystemClock.elapsedRealtime() < until
    }

    fun markDirectFailed(host: String) {
        preferVpnUntilByHost[host.trim().lowercase(Locale.ROOT)] =
            SystemClock.elapsedRealtime() + PREFER_VPN_TTL_MS
    }

    fun markDirectSucceeded(host: String) {
        preferVpnUntilByHost.remove(host.trim().lowercase(Locale.ROOT))
    }

    fun clearPreferVpn(host: String? = null) {
        if (host == null) {
            preferVpnUntilByHost.clear()
        } else {
            preferVpnUntilByHost.remove(host.trim().lowercase(Locale.ROOT))
        }
    }

    fun clearSession() {
        synchronized(ticketLock) {
            cachedTicket = ""
            ticketFetchedAtMs = 0L
            preferVpnUntilByHost.clear()
            lastReloginFailureMs = 0L
            lastReloginFailureReason = ""
            lastFailureReason = ""
        }
    }

    fun isDirectNetworkFailure(error: Throwable): Boolean {
        var cur: Throwable? = error
        var depth = 0
        while (cur != null && depth < 5) {
            if (cur is ConnectException ||
                cur is SocketTimeoutException ||
                cur is UnknownHostException ||
                cur is NoRouteToHostException ||
                cur is SocketException
            ) {
                return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    /** 主机名 → WebVPN 加密 token（委托共享 [WebVpnCodec]，消除双份 AES-CFB 实现漂移）。 */
    fun encryptHost(host: String): String = WebVpnCodec.encryptHost(host)

    fun decryptHost(hexToken: String): String? = WebVpnCodec.decryptHost(hexToken)

    fun toVpnUrl(original: HttpUrl): HttpUrl {
        if (original.host.equals(VPN_HOST, ignoreCase = true)) return original
        // 共享编解码负责改写规则；这里保留 OkHttp 的编码语义（path/query/fragment 原样透传）。
        return WebVpnCodec.toVpnUrl(original.toString()).toHttpUrlOrNull() ?: original
    }

    fun fromVpnUrl(vpnUrl: HttpUrl): HttpUrl? {
        if (!vpnUrl.host.equals(VPN_HOST, ignoreCase = true)) return null
        // 共享编解码负责解析；解出的原始 URL 再交回 OkHttp 保证编码语义一致
        return WebVpnCodec.fromVpnUrl(vpnUrl.toString())?.toHttpUrlOrNull()
    }

    fun isVpnLoginRedirect(response: Response): Boolean {
        if (response.code !in 300..399) return false
        val loc = response.header("Location")?.trim().orEmpty()
        if (loc.isEmpty()) return false
        val resolved = response.request.url.resolve(loc) ?: return false
        if (resolved.host.equals(VPN_HOST, ignoreCase = true) &&
            resolved.encodedPath.startsWith("/login")
        ) {
            return true
        }
        val unwrapped = fromVpnUrl(resolved) ?: resolved
        if (unwrapped.host.equals(SID_PORTAL_HOST, ignoreCase = true) &&
            unwrapped.encodedPath.startsWith("/login")
        ) {
            val service = unwrapped.queryParameter("service").orEmpty()
            return service.isEmpty() || service.contains(VPN_HOST, ignoreCase = true)
        }
        return false
    }

    /**
     * 校外直连校内域时，校园防火墙可能直接返回 HTTP 403/451/502/503（如校园卡“请使用 VPN”页面）
     * 或 HTTP 200 的校内访问提示页，而非 TCP 超时/拒绝。命中时视为直连受限，立即降级走 WebVPN。
     */
    fun isDirectCampusBlock(response: Response): Boolean {
        val code = response.code
        val contentType = response.header("Content-Type")?.lowercase(Locale.ROOT).orEmpty()
        if (code in setOf(403, 451, 502, 503)) {
            if (contentType.contains("json")) {
                val preview = runCatching { response.peekBody(2048).string().trim() }.getOrDefault("")
                if (preview.startsWith("{")) return false
            }
            return true
        }
        if (code == 200 && contentType.contains("html")) {
            val lower = runCatching { response.peekBody(8192).string() }
                .getOrDefault("")
                .lowercase(Locale.ROOT)
            if (lower.isEmpty()) return false
            val businessMarkers = listOf(
                "一卡通查询平台",
                "raspublickey",
                "研究生管理信息系统",
                "graduate management",
                "login-page-flowkey"
            )
            if (businessMarkers.any { lower.contains(it) }) return false
            val blockMarkers = listOf(
                "请使用vpn",
                "请使用 vpn",
                "请连接vpn",
                "请连接校园网",
                "仅限校内",
                "w.buct.edu.cn"
            )
            return blockMarkers.any { lower.contains(it) }
        }
        return false
    }

    fun ensureTicket(forceRefresh: Boolean = false): String? {
        synchronized(ticketLock) {
            val now = SystemClock.elapsedRealtime()
            if (!forceRefresh && cachedTicket.isNotBlank() && now - ticketFetchedAtMs < TICKET_SOFT_TTL_MS) {
                return cachedTicket
            }
            if (!forceRefresh && cachedTicket.isBlank()) {
                val persisted = readVpnTicketFn?.invoke().orEmpty().trim()
                if (persisted.isNotBlank()) {
                    cachedTicket = persisted
                    ticketFetchedAtMs = now
                    return persisted
                }
            }
            if (forceRefresh) {
                cachedTicket = ""
                ticketFetchedAtMs = 0L
            }
            lastFailureReason = ""

            return try {
                var vpnTicket = fetchInitialVpnTicket() ?: return null
                val casTicket = fetchCasTicketForVpn() ?: return null
                vpnTicket = activateVpnTicket(vpnTicket, casTicket) ?: return null
                cachedTicket = vpnTicket
                ticketFetchedAtMs = SystemClock.elapsedRealtime()
                lastFailureReason = ""
                saveVpnTicketFn?.invoke(vpnTicket)
                vpnTicket
            } catch (e: IOException) {
                if (lastFailureReason.isBlank()) {
                    lastFailureReason = "net-${e.javaClass.simpleName}"
                }
                null
            }
        }
    }

    fun activateWithCasTicket(casTicket: String): String? {
        val cleanCas = casTicket.trim()
        if (cleanCas.isEmpty()) return null
        synchronized(ticketLock) {
            lastFailureReason = ""
            return try {
                val initTicket = fetchInitialVpnTicket() ?: return null
                val activeTicket = activateVpnTicket(initTicket, cleanCas) ?: return null
                cachedTicket = activeTicket
                ticketFetchedAtMs = SystemClock.elapsedRealtime()
                lastReloginFailureMs = 0L
                lastReloginFailureReason = ""
                lastFailureReason = ""
                saveVpnTicketFn?.invoke(activeTicket)
                activeTicket
            } catch (e: IOException) {
                if (lastFailureReason.isBlank()) {
                    lastFailureReason = "net-${e.javaClass.simpleName}"
                }
                null
            }
        }
    }

    internal fun updateTicketFromResponse(response: Response) {
        val rotated = extractCookieValue(response.headers("Set-Cookie"), VPN_COOKIE_NAME)
        if (rotated.isNotBlank()) {
            synchronized(ticketLock) {
                cachedTicket = rotated
                ticketFetchedAtMs = SystemClock.elapsedRealtime()
            }
            saveVpnTicketFn?.invoke(rotated)
        }
    }

    internal fun mergeTicketCookie(existingHeader: String?, ticket: String): String {
        val parts = ArrayList<String>()
        parts += "$VPN_COOKIE_NAME=$ticket"
        if (!existingHeader.isNullOrBlank()) {
            for (segment in existingHeader.split(';')) {
                val trimmed = segment.trim()
                if (trimmed.isEmpty()) continue
                val eq = trimmed.indexOf('=')
                val name = if (eq > 0) trimmed.substring(0, eq).trim() else trimmed
                if (!name.equals(VPN_COOKIE_NAME, ignoreCase = true)) {
                    parts += trimmed
                }
            }
        }
        return parts.joinToString("; ")
    }

    private fun cookieKeysSummary(rawCookies: String): String {
        if (rawCookies.isBlank()) return "none"
        val keys = rawCookies.split(';')
            .mapNotNull { seg ->
                val eq = seg.indexOf('=')
                if (eq > 0) seg.substring(0, eq).trim().takeIf { it.isNotEmpty() } else null
            }
            .distinct()
        return if (keys.isEmpty()) "none" else keys.joinToString(",")
    }

    private fun fetchInitialVpnTicket(): String? {
        val req = Request.Builder()
            .url("$VPN_BASE/login")
            .header("User-Agent", SID_PORTAL_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        return vpnHttpClient.newCall(req).execute().use { resp ->
            val ticket = extractCookieValue(resp.headers("Set-Cookie"), VPN_COOKIE_NAME)
            if (ticket.isBlank()) {
                lastFailureReason = "init-http-${resp.code}-no-cookie"
                null
            } else {
                ticket
            }
        }
    }

    private fun fetchCasTicketForVpn(): String? {
        val casService = "$VPN_BASE/login?cas_login=true"
        val casLoginUrl = "$SID_PORTAL_BASE/login?service=${URLEncoder.encode(casService, "UTF-8")}"
        val jar = MatchingMemoryCookieJar()
        val rawSidCookies = readSidCookiesFn?.invoke().orEmpty().trim()
        val keyInfo = cookieKeysSummary(rawSidCookies)
        if (rawSidCookies.isNotBlank()) {
            jar.seed(rawSidCookies, SID_PORTAL_HOST)
        }
        val sidClient = vpnHttpClient.newBuilder().cookieJar(jar).build()

        var entryHtml = ""
        var probeReason = ""
        if (rawSidCookies.isNotBlank()) {
            val probe = followCasToVpnTicket(sidClient, casLoginUrl)
            if (probe.ticket != null) {
                val refreshed = exportSidCookieHeader(jar)
                if (refreshed.isNotBlank() && refreshed != rawSidCookies) {
                    saveSidCookiesIfAuthenticated(refreshed)
                }
                return probe.ticket
            }
            entryHtml = probe.html
            probeReason = probe.reason
        }

        val now = SystemClock.elapsedRealtime()
        if (lastReloginFailureMs > 0L && now - lastReloginFailureMs < RELOGIN_FAILURE_COOLDOWN_MS) {
            val prevRelogin = lastReloginFailureReason.ifBlank { "unknown" }
            lastFailureReason = if (probeReason.isNotBlank()) {
                "$probeReason(keys=$keyInfo),cooldown($prevRelogin)"
            } else {
                "cooldown($prevRelogin,keys=$keyInfo)"
            }
            return null
        }

        val creds = readSavedCredentialsFn?.invoke()
        if (creds == null) {
            lastFailureReason = when {
                rawSidCookies.isBlank() -> "sid-empty,no-creds"
                probeReason.isNotBlank() -> "$probeReason(keys=$keyInfo),no-creds"
                else -> "cas-probe-fail(keys=$keyInfo),no-creds"
            }
            return null
        }
        val username = creds.first.trim()
        val passwordChars = creds.second
        if (username.isEmpty() || passwordChars.isEmpty()) {
            passwordChars.fill('\u0000')
            lastFailureReason = "empty-creds(keys=$keyInfo)"
            return null
        }

        return try {
            if (entryHtml.isBlank()) {
                val probe = followCasToVpnTicket(sidClient, casLoginUrl)
                if (probe.ticket != null) {
                    val refreshed = exportSidCookieHeader(jar)
                    saveSidCookiesIfAuthenticated(refreshed)
                    lastReloginFailureMs = 0L
                    lastReloginFailureReason = ""
                    return probe.ticket
                }
                entryHtml = probe.html
                probeReason = probe.reason
            }
            if (entryHtml.isBlank()) {
                recordReloginFailure(probeReason.ifBlank { "cas-entry-empty(keys=$keyInfo)" })
                return null
            }

            var doc = Jsoup.parse(entryHtml)
            var execution = doc.selectFirst("#login-page-flowkey")?.text()?.trim().orEmpty()
            var croypto = doc.selectFirst("#login-croypto")?.text()?.trim().orEmpty()
            var errorCode = doc.selectFirst("#login-error-msg")?.text()?.trim().orEmpty()
                .ifEmpty { doc.selectFirst("#login-error-code")?.text()?.trim().orEmpty() }
            var second = doc.selectFirst("#sso-second")?.text()?.trim().orEmpty()

            if (rawSidCookies.isNotBlank() && errorCode.isEmpty() && (execution.isEmpty() || croypto.isEmpty())) {
                jar.clear()
                val cleanProbe = followCasToVpnTicket(sidClient, casLoginUrl)
                if (cleanProbe.ticket != null) {
                    val refreshed = exportSidCookieHeader(jar)
                    saveSidCookiesIfAuthenticated(refreshed)
                    lastReloginFailureMs = 0L
                    lastReloginFailureReason = ""
                    return cleanProbe.ticket
                }
                if (cleanProbe.html.isNotBlank()) {
                    entryHtml = cleanProbe.html
                    doc = Jsoup.parse(entryHtml)
                    execution = doc.selectFirst("#login-page-flowkey")?.text()?.trim().orEmpty()
                    croypto = doc.selectFirst("#login-croypto")?.text()?.trim().orEmpty()
                    errorCode = doc.selectFirst("#login-error-msg")?.text()?.trim().orEmpty()
                        .ifEmpty { doc.selectFirst("#login-error-code")?.text()?.trim().orEmpty() }
                    second = doc.selectFirst("#sso-second")?.text()?.trim().orEmpty()
                }
            }

            if (errorCode.isNotEmpty()) {
                recordReloginFailure("relogin-err($errorCode)")
                return null
            }
            if (second.isNotEmpty()) {
                recordReloginFailure("relogin-2fa")
                return null
            }
            if (execution.isEmpty() || croypto.isEmpty()) {
                recordReloginFailure("relogin-no-params(len=${entryHtml.length})")
                return null
            }

            val encPwd = BuctAesCrypto.encrypt(croypto, String(passwordChars))
            val encCaptcha = BuctAesCrypto.encrypt(croypto, "{}")

            val form = FormBody.Builder()
                .add("type", "UsernamePassword")
                .add("_eventId", "submit")
                .add("username", username)
                .add("password", encPwd)
                .add("croypto", croypto)
                .add("execution", execution)
                .add("captcha_code", "")
                .add("captcha_payload", encCaptcha)
                .add("geolocation", "")
                .build()

            val postReq = Request.Builder()
                .url(casLoginUrl)
                .header("User-Agent", SID_PORTAL_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Origin", SID_PORTAL_BASE)
                .header("Referer", casLoginUrl)
                .post(form)
                .build()

            var postFailReason = ""
            val nextUrl = sidClient.newCall(postReq).execute().use { resp ->
                if (resp.code !in 300..399) {
                    val postBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    val postDoc = if (postBody.isNotBlank()) Jsoup.parse(postBody) else null
                    val postErr = postDoc?.let { d ->
                        d.selectFirst("#login-error-msg")?.text()?.trim().orEmpty()
                            .ifEmpty { d.selectFirst("#login-error-code")?.text()?.trim().orEmpty() }
                    }.orEmpty()
                    val postSecond = postDoc?.selectFirst("#sso-second")?.text()?.trim().orEmpty()
                    postFailReason = buildString {
                        append("relogin-post-http${resp.code}")
                        if (postSecond.isNotEmpty()) append("-2fa")
                        else if (postErr.isNotEmpty()) append("-err($postErr)")
                    }
                    null
                } else {
                    val loc = resp.header("Location").orEmpty()
                    if (loc.isNotBlank()) resp.request.url.resolve(loc)?.toString() ?: loc
                    else {
                        postFailReason = "relogin-3xx-no-loc"
                        null
                    }
                }
            }
            if (nextUrl.isNullOrBlank()) {
                recordReloginFailure(postFailReason.ifBlank { "relogin-post-empty" })
                return null
            }

            val resolvedUrl = nextUrl.toHttpUrlOrNull()
            val directTicket = resolvedUrl?.let(::extractTicketIfVpnCallback)
            val followResult = if (directTicket == null) followCasToVpnTicket(sidClient, nextUrl) else null
            val ticket = directTicket ?: followResult?.ticket
            if (ticket.isNullOrBlank()) {
                recordReloginFailure(followResult?.reason?.ifBlank { "relogin-no-ticket" } ?: "relogin-no-ticket")
                return null
            }

            lastReloginFailureMs = 0L
            lastReloginFailureReason = ""
            val exported = exportSidCookieHeader(jar)
            saveSidCookiesIfAuthenticated(exported)
            ticket
        } catch (e: Exception) {
            recordReloginFailure("relogin-exc-${e.javaClass.simpleName}")
            null
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun recordReloginFailure(reason: String) {
        lastReloginFailureMs = SystemClock.elapsedRealtime()
        lastReloginFailureReason = reason
        lastFailureReason = reason
    }

    private data class CasProbeResult(val ticket: String?, val html: String = "", val reason: String = "")

    private fun followCasToVpnTicket(sidClient: OkHttpClient, startUrl: String): CasProbeResult {
        var current = startUrl.toHttpUrlOrNull() ?: return CasProbeResult(null, "", "cas-bad-url")
        extractTicketIfVpnCallback(current)?.let { return CasProbeResult(it) }

        var hops = 0
        while (hops < 6) {
            hops++
            val req = Request.Builder()
                .url(current)
                .header("User-Agent", SID_PORTAL_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            var statusReason = ""
            val outcome = sidClient.newCall(req).execute().use { resp ->
                when {
                    resp.code in 300..399 -> {
                        val loc = resp.header("Location").orEmpty()
                        val resolved = if (loc.isNotBlank()) current.resolve(loc) else null
                        if (resolved == null) statusReason = "cas-3xx-bad-loc"
                        resolved to ""
                    }
                    resp.isSuccessful -> {
                        val html = resp.body?.string().orEmpty()
                        val fk = if (html.contains("login-page-flowkey")) 1 else 0
                        val err = if (html.isNotBlank()) {
                            runCatching {
                                val d = Jsoup.parse(html)
                                d.selectFirst("#login-error-msg")?.text()?.trim().orEmpty()
                                    .ifEmpty { d.selectFirst("#login-error-code")?.text()?.trim().orEmpty() }
                            }.getOrDefault("")
                        } else ""
                        statusReason = buildString {
                            append("cas-http${resp.code}(fk=$fk")
                            if (err.isNotEmpty()) append(",err=$err")
                            append(")")
                        }
                        null to html
                    }
                    else -> {
                        statusReason = "cas-http${resp.code}"
                        null to ""
                    }
                }
            }
            val nextUrl = outcome.first
            if (nextUrl == null) {
                return CasProbeResult(null, outcome.second, statusReason)
            }
            extractTicketIfVpnCallback(nextUrl)?.let { return CasProbeResult(it) }
            val host = nextUrl.host.lowercase(Locale.ROOT)
            if (host != SID_PORTAL_HOST && host != "portal.buct.edu.cn") {
                return CasProbeResult(null, "", "cas-redir-$host")
            }
            current = nextUrl
        }
        return CasProbeResult(null, "", "cas-max-hops")
    }

    private fun extractTicketIfVpnCallback(url: HttpUrl): String? {
        if (!url.host.equals(VPN_HOST, ignoreCase = true)) return null
        return url.queryParameter("ticket")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun activateVpnTicket(initialVpnTicket: String, casTicket: String): String? {
        var ticket = initialVpnTicket
        val activateUrl = "$VPN_BASE/login?cas_login=true&ticket=${URLEncoder.encode(casTicket, "UTF-8")}"
        val req = Request.Builder()
            .url(activateUrl)
            .header("User-Agent", SID_PORTAL_UA)
            .header("Cookie", "$VPN_COOKIE_NAME=$ticket")
            .get()
            .build()
        vpnHttpClient.newCall(req).execute().use { resp ->
            val rotated = extractCookieValue(resp.headers("Set-Cookie"), VPN_COOKIE_NAME)
            if (rotated.isNotBlank()) {
                ticket = rotated
            }
            if (resp.code in 300..399) {
                if (isVpnLoginRedirect(resp)) {
                    lastFailureReason = "activate-302-login"
                    return null
                }
                return ticket
            }
            if (!resp.isSuccessful) {
                lastFailureReason = "activate-http-${resp.code}"
                return null
            }
            val body = resp.body?.string().orEmpty()
            if (body.contains("login-page-flowkey")) {
                lastFailureReason = "activate-200-login"
                return null
            }
            return ticket
        }
    }

    private fun exportSidCookieHeader(jar: MatchingMemoryCookieJar): String {
        val probeUrl = "$SID_PORTAL_BASE/login".toHttpUrl()
        val merged = LinkedHashMap<String, String>()
        jar.loadForRequest(probeUrl).forEach { cookie ->
            merged[cookie.name] = cookie.value
        }
        jar.cookiesSetBy(SID_PORTAL_HOST, 443).forEach { cookie ->
            if (!merged.containsKey(cookie.name) && cookie.value.isNotBlank()) {
                merged[cookie.name] = cookie.value
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun extractCookieValue(setCookieHeaders: List<String>, targetName: String): String {
        var latest = ""
        for (header in setCookieHeaders) {
            val pair = header.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            if (name.equals(targetName, ignoreCase = true)) {
                val value = pair.substring(eq + 1).trim()
                if (value.isNotBlank() && !value.equals("deleteMe", ignoreCase = true)) {
                    latest = value
                }
            }
        }
        return latest
    }

}

/**
 * 校内域 OkHttp 应用层拦截器：直连优先（3s 快速连接超时），失败后自动降级走 WebVPN（w.buct.edu.cn）。
 */
class BuctWebVpnInterceptor : Interceptor {
    companion object {
        private const val DIRECT_CONNECT_TIMEOUT_MS = 3_000
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalReq = chain.request()
        if (!BuctWebVpn.isCampusOnlyUrl(originalReq.url)) {
            return chain.proceed(originalReq)
        }
        val host = originalReq.url.host

        var directAttempted = false
        var directError: IOException? = null

        if (!BuctWebVpn.shouldPreferVpn(host)) {
            directAttempted = true
            try {
                val resp = chain.withConnectTimeout(DIRECT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .proceed(originalReq)
                if (originalReq.body?.isOneShot() != true && BuctWebVpn.isDirectCampusBlock(resp)) {
                    resp.close()
                    BuctWebVpn.markDirectFailed(host)
                } else {
                    BuctWebVpn.markDirectSucceeded(host)
                    return resp
                }
            } catch (e: IOException) {
                if (!BuctWebVpn.isDirectNetworkFailure(e)) {
                    throw e
                }
                directError = e
                BuctWebVpn.markDirectFailed(host)
            }
        }

        val ticket = BuctWebVpn.ensureTicket(forceRefresh = false)
        if (ticket.isNullOrBlank()) {
            if (!directAttempted) {
                BuctWebVpn.clearPreferVpn(host)
                try {
                    val resp = chain.withConnectTimeout(DIRECT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .proceed(originalReq)
                    if (BuctWebVpn.isDirectCampusBlock(resp)) {
                        resp.close()
                        BuctWebVpn.markDirectFailed(host)
                        throw authRequiredException(
                            "校外访问校内系统需要通过 WebVPN，请先登录统一身份认证或切换至校园网"
                        )
                    }
                    BuctWebVpn.markDirectSucceeded(host)
                    return resp
                } catch (e: IOException) {
                    if (e is BuctWebVpnAuthRequiredException) throw e
                    if (BuctWebVpn.isDirectNetworkFailure(e)) {
                        BuctWebVpn.markDirectFailed(host)
                        throw authRequiredException(
                            "校外访问校内系统需要通过 WebVPN，请先登录统一身份认证或切换至校园网",
                            e
                        )
                    }
                    throw e
                }
            }
            throw authRequiredException(
                "校外访问校内系统需要通过 WebVPN，请先登录统一身份认证或切换至校园网",
                directError
            )
        }

        val vpnReq = buildVpnRequest(originalReq, ticket)
        val resp = try {
            chain.proceed(vpnReq)
        } catch (e: IOException) {
            if (!directAttempted && BuctWebVpn.isDirectNetworkFailure(e)) {
                BuctWebVpn.clearPreferVpn(host)
                return chain.withConnectTimeout(DIRECT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .proceed(originalReq)
            }
            throw e
        }

        if (!BuctWebVpn.isVpnLoginRedirect(resp)) {
            BuctWebVpn.updateTicketFromResponse(resp)
            return normalizeVpnResponse(resp, originalReq)
        }

        resp.close()
        if (originalReq.body?.isOneShot() == true) {
            throw authRequiredException(
                "WebVPN 统一身份认证会话已失效，请重新登录统一身份认证"
            )
        }

        val refreshedTicket = BuctWebVpn.ensureTicket(forceRefresh = true)
        if (refreshedTicket.isNullOrBlank()) {
            throw authRequiredException(
                "WebVPN 统一身份认证会话已失效，请重新登录统一身份认证"
            )
        }

        val retryReq = buildVpnRequest(originalReq, refreshedTicket)
        val retryResp = chain.proceed(retryReq)
        if (BuctWebVpn.isVpnLoginRedirect(retryResp)) {
            retryResp.close()
            throw authRequiredException(
                "WebVPN 统一身份认证会话已失效，请重新登录统一身份认证"
            )
        }
        BuctWebVpn.updateTicketFromResponse(retryResp)
        return normalizeVpnResponse(retryResp, originalReq)
    }

    private fun authRequiredException(prefix: String, cause: Throwable? = null): BuctWebVpnAuthRequiredException {
        val reason = BuctWebVpn.lastFailureReason
        val msg = if (reason.isNotBlank()) "$prefix（原因：$reason）" else prefix
        return BuctWebVpnAuthRequiredException(msg, cause)
    }

    private fun buildVpnRequest(originalReq: Request, ticket: String): Request {
        val builder = originalReq.newBuilder()
            .url(BuctWebVpn.toVpnUrl(originalReq.url))
            .header(BuctWebVpn.HEADER_VPN_TICKET_INTERNAL, ticket)
            .header("Cookie", BuctWebVpn.mergeTicketCookie(originalReq.header("Cookie"), ticket))

        if (originalReq.header("Origin") != null) {
            builder.header("Origin", BuctWebVpn.VPN_BASE)
        }
        originalReq.header("Referer")?.toHttpUrlOrNull()?.let { ref ->
            val refHost = ref.host.lowercase(Locale.ROOT)
            if (refHost == "buct.edu.cn" || refHost.endsWith(".buct.edu.cn")) {
                builder.header("Referer", BuctWebVpn.toVpnUrl(ref).toString())
            }
        }
        return builder.build()
    }

    private fun normalizeVpnResponse(resp: Response, originalReq: Request): Response {
        val builder = resp.newBuilder()
            .request(originalReq)
            .header(BuctWebVpn.HEADER_VIA_WEBVPN, "1")
        if (resp.code in 300..399) {
            val loc = resp.header("Location")?.trim().orEmpty()
            if (loc.isNotEmpty()) {
                val resolved = resp.request.url.resolve(loc)
                val restored = resolved?.let { BuctWebVpn.fromVpnUrl(it) }
                if (restored != null) {
                    builder.header("Location", restored.toString())
                }
            }
        }
        return builder.build()
    }
}

/**
 * 网络层 Cookie 兜底拦截器：在 OkHttp BridgeInterceptor 之后运行，防止客户端 CookieJar
 * （如 .buct.edu.cn 全局 Cookie 或旧缓存）覆盖当前请求的 WebVPN 票据，同时剥离内部传递头。
 */
class BuctWebVpnNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val ticket = req.header(BuctWebVpn.HEADER_VPN_TICKET_INTERNAL)
            ?: return chain.proceed(req)
        val mergedCookie = BuctWebVpn.mergeTicketCookie(req.header("Cookie"), ticket)
        val cleanReq = req.newBuilder()
            .removeHeader(BuctWebVpn.HEADER_VPN_TICKET_INTERNAL)
            .header("Cookie", mergedCookie)
            .build()
        val resp = chain.proceed(cleanReq)
        BuctWebVpn.updateTicketFromResponse(resp)
        return resp
    }
}