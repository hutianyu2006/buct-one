package com.buct.one.platform.network

import com.buct.one.data.text.urlHostOf
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

/** 一次 WebVPN 感知请求的响应快照（body 已读成文本，便于分类与二次解析）。 */
data class WebVpnResponse(
    val status: Int,
    val contentType: String?,
    val location: String?,
    val url: String,
    val body: String,
) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * 「直连优先 → 受阻自动降级 WebVPN」的请求执行器，双端共用。
 *
 * 判定与记忆全部交给 [WebVpnSession]：
 * - [WebVpnCodec.CAMPUS_ONLY_HOSTS] 之外的域名**永不改写**（安全边界，forceVpn 也不能突破）；
 * - 直连命中 403/451/502/503、"请使用 VPN" 提示页或跳转 WebVPN 登录页 → 记 5 分钟优先代理并重试一次；
 * - 代理成功后保留 5 分钟记忆（避免每个请求都先撞一次墙），票据轮换时写回 Vault。
 *
 * 之所以做成"包装请求函数"而不是 Ktor 插件：降级需要**先读到响应体**才能判定受限页，
 * 插件层拿不到已消费的 body；包装函数让调用方只写一次 try/retry。
 */
class WebVpnRequestExecutor(
    private val client: HttpClient,
    private val session: WebVpnSession,
    private val userAgent: String? = null,
) {

    /**
     * 执行一次请求；受阻时自动改走 WebVPN 重试一次。
     *
     * @param forceVpn 强制走代理（仅对校内限定域生效；其它域名仍直连）。
     * @param block 响应处理（只调用一次：要么直连成功，要么代理重试成功）。
     */
    suspend fun <T> execute(
        url: String,
        forceVpn: Boolean = false,
        block: suspend (WebVpnResponse) -> T,
    ): T {
        val host = urlHostOf(url)?.lowercase().orEmpty()
        val campusOnly = WebVpnCodec.isCampusOnlyHost(host)
        val preferVpn = campusOnly && (forceVpn || session.shouldPreferVpn(host))

        if (preferVpn) {
            val proxyUrl = session.proxyUrlIfNeeded(url, forceVpn = true)
            val proxied = perform(proxyUrl)
            if (proxied.isSuccess) return block(proxied)
            // 代理也失败：直连再试一次（例如用户其实在校内网）
            val direct = perform(url)
            if (direct.isSuccess) {
                session.markDirectSucceeded(host)
                return block(direct)
            }
            return block(direct)
        }

        val direct = perform(url)
        if (!campusOnly) return block(direct)

        val verdict = session.classifyResponse(
            status = direct.status,
            contentType = direct.contentType,
            location = direct.location,
            requestUrl = url,
            body = direct.body,
        )
        if (verdict == DirectAccessVerdict.OK) {
            session.markDirectSucceeded(host)
            return block(direct)
        }

        // 直连受阻：记住 5 分钟，并尝试换取票据后改走代理
        session.markDirectFailed(host)
        session.ensureTicket()
        val retried = perform(session.proxyUrlIfNeeded(url, forceVpn = true))
        return block(retried)
    }

    private suspend fun perform(target: String): WebVpnResponse {
        val response = client.get(target) {
            if (userAgent != null) header(HttpHeaders.UserAgent, userAgent)
        }
        // 网关会定期轮换票据 Cookie：见到就写回 Vault，避免过期后整页失效
        session.updateTicketFromCookie(response.headers.getAll(HttpHeaders.SetCookie).orEmpty())
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        return WebVpnResponse(
            status = response.status.value,
            contentType = response.headers[HttpHeaders.ContentType],
            location = response.headers[HttpHeaders.Location],
            url = target,
            body = body,
        )
    }
}
