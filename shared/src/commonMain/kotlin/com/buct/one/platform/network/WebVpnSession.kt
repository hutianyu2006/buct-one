package com.buct.one.platform.network

import com.buct.one.data.login.CasLoginForm
import com.buct.one.data.login.CasLoginPage
import com.buct.one.data.text.resolveUrl
import com.buct.one.data.text.urlHostOf
import com.buct.one.data.text.urlPathOf
import com.buct.one.data.text.urlQueryOf
import com.buct.one.platform.security.SecureVault
import com.buct.one.shared.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters

/** 直连受限的判定结果（决定是否降级到 WebVPN）。 */
enum class DirectAccessVerdict {
    /** 响应正常，可以直连。 */
    OK,

    /** 被校园防火墙拦截（403/451/502/503 或"请使用 VPN"提示页），应立即降级。 */
    BLOCKED,

    /** 被重定向到 WebVPN / 统一认证登录页，需要票据。 */
    NEEDS_TICKET,
}

/**
 * 北化 WebVPN 会话管理，双端共用。
 *
 * 职责：
 * 1. **降级状态**：某 host 直连失败后在 [PREFER_VPN_TTL_MILLIS] 内优先走 WebVPN，成功即清除；
 * 2. **票据换取**：`GET w.buct.edu.cn/login` 拿初始 Cookie → CAS 登录取 `ticket` → 激活，
 *    票据加密存 [SecureVault]（Android Keystore / iOS Keychain），软过期 [TICKET_SOFT_TTL_MILLIS]；
 * 3. **受限判定**：[classifyResponse] 复刻 Android 的"403/451/502/503 + 业务标记 + VPN 提示"判断。
 *
 * 网络请求通过注入的 [HttpClient]（已挂 TLS/DNS/Cookie 存储），凭据由 [credentialsProvider] 提供，
 * 用后立即 `fill('\u0000')` 清零。
 */
class WebVpnSession(
    private val httpClient: HttpClient,
    private val userAgent: String,
    private val portalUserAgent: String = userAgent,
    private val vault: SecureVault? = null,
    private val portalBaseUrl: String = "https://sidportal.buct.edu.cn",
    private val credentialsProvider: (() -> Pair<String, CharArray>?)? = null,
    private val clock: () -> Long = { nowMillis() },
) {

    private val preferVpnUntilByHost = mutableMapOf<String, Long>()

    private var cachedTicket: String = ""
    private var ticketFetchedAt: Long = 0L
    private var lastFailureReason: String = ""

    fun lastFailure(): String = lastFailureReason

    // ---------- 降级状态 ----------

    fun shouldPreferVpn(host: String): Boolean {
        val until = preferVpnUntilByHost[normalize(host)] ?: return false
        return clock() < until
    }

    fun markDirectFailed(host: String) {
        preferVpnUntilByHost[normalize(host)] = clock() + PREFER_VPN_TTL_MILLIS
    }

    fun markDirectSucceeded(host: String) {
        preferVpnUntilByHost.remove(normalize(host))
    }

    fun clearPreferVpn(host: String? = null) {
        if (host == null) preferVpnUntilByHost.clear() else preferVpnUntilByHost.remove(normalize(host))
    }

    /** 退出登录：清空票据、降级状态与落盘密文。 */
    fun clearSession() {
        cachedTicket = ""
        ticketFetchedAt = 0L
        lastFailureReason = ""
        preferVpnUntilByHost.clear()
        vault?.clearAlias(SecureVault.ALIAS_WEBVPN)
    }

    /**
     * 把 URL 改写为 WebVPN 代理地址。
     *
     * **只有 [WebVpnCodec.CAMPUS_ONLY_HOSTS] 里的站点才会被改写**——这是安全边界：
     * 避免把普通校内站点（教务、邮箱等）误送进代理，[forceVpn] 也不能突破。
     */
    fun proxyUrlIfNeeded(url: String, forceVpn: Boolean = false): String {
        val host = urlHostOf(url) ?: return url
        if (!WebVpnCodec.isCampusOnlyHost(host)) return url
        val needed = forceVpn || shouldPreferVpn(host)
        return if (needed) WebVpnCodec.toVpnUrl(url) else url
    }

    // ---------- 直连受限判定 ----------

    fun classifyResponse(status: Int, contentType: String?, location: String?, requestUrl: String, body: String?): DirectAccessVerdict {
        if (status in 300..399 && location != null) {
            val resolved = resolveUrl(requestUrl, location)
            if (isVpnLoginRedirect(resolved)) return DirectAccessVerdict.NEEDS_TICKET
        }
        if (status in setOf(403, 451, 502, 503)) {
            if (contentType?.contains("json", ignoreCase = true) == true && body?.trimStart()?.startsWith("{") == true) {
                return DirectAccessVerdict.OK
            }
            return DirectAccessVerdict.BLOCKED
        }
        if (status == 200 && contentType?.contains("html", ignoreCase = true) == true) {
            val lower = body.orEmpty().lowercase()
            if (lower.isEmpty()) return DirectAccessVerdict.OK
            if (BUSINESS_MARKERS.any { lower.contains(it) }) return DirectAccessVerdict.OK
            if (BLOCK_MARKERS.any { lower.contains(it) }) return DirectAccessVerdict.BLOCKED
        }
        return DirectAccessVerdict.OK
    }

    private fun isVpnLoginRedirect(resolved: String): Boolean {
        val host = urlHostOf(resolved)?.lowercase() ?: return false
        val path = urlPathOf(resolved)
        if (host == WebVpnCodec.VPN_HOST && path.startsWith("/login")) return true
        val unwrapped = WebVpnCodec.fromVpnUrl(resolved) ?: resolved
        val unwrappedHost = urlHostOf(unwrapped)?.lowercase() ?: return false
        if (unwrappedHost == PORTAL_HOST && urlPathOf(unwrapped).startsWith("/login")) {
            val service = urlQueryOf(unwrapped).orEmpty()
            return service.isEmpty() || service.contains(WebVpnCodec.VPN_HOST, ignoreCase = true)
        }
        return false
    }

    // ---------- 票据换取 ----------

    /** 取有效票据：先内存 → 再 [SecureVault] 落盘 → 最后重新换取。 */
    suspend fun ensureTicket(forceRefresh: Boolean = false): String? {
        val now = clock()
        if (!forceRefresh && cachedTicket.isNotBlank() && now - ticketFetchedAt < TICKET_SOFT_TTL_MILLIS) {
            return cachedTicket
        }
        if (!forceRefresh && cachedTicket.isBlank()) {
            val persisted = vault?.get(SecureVault.ALIAS_WEBVPN, TICKET_KEY)?.trim().orEmpty()
            if (persisted.isNotBlank()) {
                cachedTicket = persisted
                ticketFetchedAt = now
                return persisted
            }
        }
        if (forceRefresh) {
            cachedTicket = ""
            ticketFetchedAt = 0L
        }
        lastFailureReason = ""
        return try {
            val initial = fetchInitialTicket() ?: return null
            val casTicket = fetchCasTicket() ?: return null
            val activated = activateTicket(initial, casTicket) ?: return null
            cachedTicket = activated
            ticketFetchedAt = clock()
            vault?.put(SecureVault.ALIAS_WEBVPN, TICKET_KEY, activated)
            activated
        } catch (error: Exception) {
            if (lastFailureReason.isBlank()) lastFailureReason = "exception-${error::class.simpleName}"
            null
        }
    }

    /** 响应里轮换了票据 Cookie 时更新缓存（网关会定期轮换）。 */
    fun updateTicketFromCookie(setCookieHeaders: List<String>) {
        val rotated = extractCookieValue(setCookieHeaders, WebVpnCodec.VPN_COOKIE_NAME)
        if (rotated.isBlank()) return
        cachedTicket = rotated
        ticketFetchedAt = clock()
        vault?.put(SecureVault.ALIAS_WEBVPN, TICKET_KEY, rotated)
    }

    private suspend fun fetchInitialTicket(): String? {
        val response = httpClient.get("${WebVpnCodec.VPN_BASE}/login") {
            header(HttpHeaders.UserAgent, portalUserAgent)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
        }
        val ticket = extractCookieValue(response.headers.getAll(HttpHeaders.SetCookie).orEmpty(), WebVpnCodec.VPN_COOKIE_NAME)
        if (ticket.isBlank()) {
            lastFailureReason = "init-http-${response.status.value}-no-cookie"
            return null
        }
        return ticket
    }

    /**
     * 走 CAS 换取 WebVPN 票据：`sidportal/login?service=w.buct.edu.cn/login?cas_login=true`。
     * 若已有 sidportal 会话，通常 302 直达；否则用保存的凭据登录一次（成功后清零密码数组）。
     */
    private suspend fun fetchCasTicket(): String? {
        val casService = "${WebVpnCodec.VPN_BASE}/login?cas_login=true"
        val casLoginUrl = "$portalBaseUrl/login?service=${encodeQuery(casService)}"

        // 1) 已有会话：直接跟随跳转
        val probe = followToVpnTicket(casLoginUrl)
        probe.ticket?.let { return it }

        // 2) 无会话：用保存的凭据登录
        val creds = credentialsProvider?.invoke() ?: run {
            lastFailureReason = probe.reason.ifBlank { "no-credentials" }
            return null
        }
        val username = creds.first.trim()
        val passwordChars = creds.second
        if (username.isEmpty() || passwordChars.isEmpty()) {
            passwordChars.fill('\u0000')
            lastFailureReason = "empty-credentials"
            return null
        }
        return try {
            val html = probe.html.ifBlank {
                httpClient.get(casLoginUrl) {
                    header(HttpHeaders.UserAgent, portalUserAgent)
                }.bodyAsText()
            }
            val page = CasLoginPage.parse(html)
            if (page.smsRequired) {
                lastFailureReason = "relogin-2fa"
                return null
            }
            if (page.errorCode.isNotEmpty()) {
                lastFailureReason = "relogin-err(${page.errorCode})"
                return null
            }
            val form = try {
                CasLoginForm.build(page, username, String(passwordChars))
            } catch (error: Exception) {
                lastFailureReason = "relogin-no-params"
                return null
            }
            val response = httpClient.submitForm(
                url = casLoginUrl,
                formParameters = Parameters.build { form.fields.forEach { (k, v) -> append(k, v) } },
                encodeInQuery = false,
            ) {
                header(HttpHeaders.UserAgent, portalUserAgent)
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
                header(HttpHeaders.Origin, portalBaseUrl)
                header(HttpHeaders.Referrer, casLoginUrl)
            }
            val status = response.status.value
            if (status !in 300..399) {
                lastFailureReason = "relogin-post-http$status"
                return null
            }
            val location = response.headers[HttpHeaders.Location] ?: run {
                lastFailureReason = "relogin-3xx-no-loc"
                return null
            }
            val next = resolveUrl(casLoginUrl, location)
            extractTicketFromVpnCallback(next)?.let { return it }
            val followed = followToVpnTicket(next)
            if (followed.ticket == null) {
                lastFailureReason = followed.reason.ifBlank { "relogin-no-ticket" }
            }
            followed.ticket
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private data class ProbeResult(val ticket: String?, val html: String = "", val reason: String = "")

    private suspend fun followToVpnTicket(startUrl: String): ProbeResult {
        var current = startUrl
        extractTicketFromVpnCallback(current)?.let { return ProbeResult(it) }
        var hops = 0
        while (hops < MAX_REDIRECT_HOPS) {
            hops++
            val response = httpClient.get(current) {
                header(HttpHeaders.UserAgent, portalUserAgent)
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            }
            val status = response.status.value
            if (status in 300..399) {
                val location = response.headers[HttpHeaders.Location]
                    ?: return ProbeResult(null, "", "cas-3xx-bad-loc")
                val next = resolveUrl(current, location)
                extractTicketFromVpnCallback(next)?.let { return ProbeResult(it) }
                val host = urlHostOf(next)?.lowercase() ?: return ProbeResult(null, "", "cas-bad-url")
                if (host != PORTAL_HOST && host != "portal.buct.edu.cn") {
                    return ProbeResult(null, "", "cas-redir-$host")
                }
                current = next
                continue
            }
            if (status in 200..299) {
                val html = response.bodyAsText()
                val fk = if (html.contains("login-page-flowkey")) 1 else 0
                return ProbeResult(null, html, "cas-http$status(fk=$fk)")
            }
            return ProbeResult(null, "", "cas-http$status")
        }
        return ProbeResult(null, "", "cas-max-hops")
    }

    private fun extractTicketFromVpnCallback(url: String): String? {
        val host = urlHostOf(url)?.lowercase() ?: return null
        if (host != WebVpnCodec.VPN_HOST) return null
        val query = urlQueryOf(url) ?: return null
        for (segment in query.split("&")) {
            val eq = segment.indexOf('=')
            if (eq <= 0) continue
            if (segment.substring(0, eq) == "ticket") {
                return segment.substring(eq + 1).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private suspend fun activateTicket(initialTicket: String, casTicket: String): String? {
        val url = "${WebVpnCodec.VPN_BASE}/login?cas_login=true&ticket=${encodeQuery(casTicket)}"
        val response = httpClient.get(url) {
            header(HttpHeaders.UserAgent, portalUserAgent)
            header(HttpHeaders.Cookie, "${WebVpnCodec.VPN_COOKIE_NAME}=$initialTicket")
        }
        val rotated = extractCookieValue(
            response.headers.getAll(HttpHeaders.SetCookie).orEmpty(),
            WebVpnCodec.VPN_COOKIE_NAME,
        )
        val ticket = rotated.ifBlank { initialTicket }
        val status = response.status.value
        if (status in 300..399) {
            val location = response.headers[HttpHeaders.Location]
            if (location != null && isVpnLoginRedirect(resolveUrl(url, location))) {
                lastFailureReason = "activate-302-login"
                return null
            }
            return ticket
        }
        if (status !in 200..299) {
            lastFailureReason = "activate-http$status"
            return null
        }
        if (response.bodyAsText().contains("login-page-flowkey")) {
            lastFailureReason = "activate-200-login"
            return null
        }
        return ticket
    }

    private fun extractCookieValue(setCookieHeaders: List<String>, targetName: String): String {
        var latest = ""
        for (header in setCookieHeaders) {
            val pair = header.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            if (!name.equals(targetName, ignoreCase = true)) continue
            val value = pair.substring(eq + 1).trim()
            if (value.isNotBlank() && !value.equals("deleteMe", ignoreCase = true)) latest = value
        }
        return latest
    }

    private fun encodeQuery(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val intValue = byte.toInt() and 0xFF
            val char = intValue.toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-_.~") {
                append(char)
            } else {
                append('%').append(HEX[intValue shr 4]).append(HEX[intValue and 0x0F])
            }
        }
    }

    private fun normalize(host: String): String = host.trim().lowercase()

    companion object {
        private const val HEX = "0123456789ABCDEF"
        private const val PORTAL_HOST = "sidportal.buct.edu.cn"
        private const val TICKET_KEY = "ticket"
        private const val MAX_REDIRECT_HOPS = 6

        /** 直连失败后优先走 WebVPN 的时长（与 Android 一致：5 分钟）。 */
        const val PREFER_VPN_TTL_MILLIS: Long = 300_000L

        /** 票据软过期（与 Android 一致：30 分钟），过期后重新换取。 */
        const val TICKET_SOFT_TTL_MILLIS: Long = 1_800_000L

        private val BUSINESS_MARKERS = listOf(
            "一卡通查询平台",
            "raspublickey",
            "研究生管理信息系统",
            "graduate management",
            "login-page-flowkey",
        )

        private val BLOCK_MARKERS = listOf(
            "请使用vpn",
            "请使用 vpn",
            "请连接vpn",
            "请连接校园网",
            "仅限校内",
            "w.buct.edu.cn",
        )
    }
}
