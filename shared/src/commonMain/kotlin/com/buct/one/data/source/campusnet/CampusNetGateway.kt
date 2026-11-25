package com.buct.one.data.source.campusnet

import com.buct.one.data.model.CampusNetCheckoutRecord
import com.buct.one.data.model.CampusNetPayRecord
import com.buct.one.data.model.CampusNetSnapshot
import com.buct.one.data.text.resolveUrl
import com.buct.one.data.text.urlEncode
import com.buct.one.data.text.urlHostOf
import com.buct.one.platform.crypto.base64Encode
import com.buct.one.shared.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

/**
 * 校园网网费/账单网关，双端共用。
 *
 * 1. `tree.buct.edu.cn/cgi-bin/rad_user_info`（JSONP）：实时在线、流量、余额；
 * 2. `tree.buct.edu.cn:8800/site/sso?data=Base64(zh-CN:学号)` 免密进入自服务平台，
 *    再读 `/home`、`/log/pay`、`/log/check-out`。
 *
 * 解析全部复用 [CampusNetParser]；只依赖调用方注入的 [HttpClient]（负责 TLS/DNS/WebVPN）。
 */
class CampusNetGateway(
    private val httpClient: HttpClient,
    private val userAgent: String,
) {

    suspend fun fetchBillingSnapshot(fallbackStudentId: String): CampusNetSnapshot {
        var radError: Exception? = null
        val rad: CampusNetSnapshot? = try {
            fetchRadUserInfo()
        } catch (error: Exception) {
            radError = error
            null
        }
        val resolvedStudentId = rad?.username?.takeIf { it.isNotBlank() } ?: fallbackStudentId.trim()

        var home: SelfServiceHomeData? = null
        var payRecords: List<CampusNetPayRecord> = emptyList()
        var checkoutRecords: List<CampusNetCheckoutRecord> = emptyList()
        var selfServiceError: Exception? = null

        if (resolvedStudentId.isNotBlank()) {
            val ssoData = urlEncode(base64Encode("zh-CN:$resolvedStudentId".encodeToByteArray()))
            val ssoUrl = "$SELF_SERVICE_BASE_URL/site/sso?data=$ssoData"
            home = try {
                val homeHtml = getFollowing(ssoUrl, referer = "$PORTAL_BASE_URL/")
                CampusNetParser.parseSelfServiceHome(homeHtml)
                    ?: throw IllegalStateException("校园网自服务平台返回了未登录页面")
            } catch (error: Exception) {
                selfServiceError = error
                null
            }

            if (home != null) {
                payRecords = try {
                    CampusNetParser.parsePayRecords(
                        getFollowing("$SELF_SERVICE_BASE_URL/log/pay", referer = "$SELF_SERVICE_BASE_URL/home"),
                    )
                } catch (_: Exception) {
                    emptyList()
                }
                checkoutRecords = try {
                    CampusNetParser.parseCheckoutRecords(
                        getFollowing("$SELF_SERVICE_BASE_URL/log/check-out", referer = "$SELF_SERVICE_BASE_URL/home"),
                    )
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

        if (rad == null && home == null) {
            throw (selfServiceError ?: radError ?: IllegalStateException("未能获取到校园网网费数据"))
        }

        return CampusNetParser.mergeSnapshot(
            rad = rad,
            home = home,
            payRecords = payRecords,
            checkoutRecords = checkoutRecords,
            fallbackUsername = resolvedStudentId,
        )
    }

    private suspend fun fetchRadUserInfo(): CampusNetSnapshot {
        val now = nowMillis()
        val callback = "jQuery${now}_${(now % 997)}"
        val url = "$PORTAL_BASE_URL/cgi-bin/rad_user_info?callback=${urlEncode(callback)}&_=$now"
        return CampusNetParser.parseUserInfo(getFollowing(url, referer = "$PORTAL_BASE_URL/"))
    }

    private suspend fun getFollowing(url: String, referer: String): String {
        var current = url
        var currentReferer = referer
        repeat(MAX_REDIRECTS) {
            if (!isAllowedHost(current)) throw IllegalStateException("校园网网关跳转到了非预期域名")
            val response = httpClient.get(current) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Referrer, currentReferer)
            }
            val code = response.status.value
            if (code in 300..399) {
                val location = response.headers[HttpHeaders.Location] ?: return@repeat
                currentReferer = current
                current = resolveUrl(current, location)
                return@repeat
            }
            val body = response.bodyAsText()
            if (code >= 400 && body.isBlank()) {
                throw IllegalStateException("校园网服务响应异常（HTTP $code）")
            }
            return body
        }
        throw IllegalStateException("校园网网关重定向次数过多")
    }

    private fun isAllowedHost(url: String): Boolean {
        val host = urlHostOf(url)?.lowercase() ?: return false
        return host == "buct.edu.cn" || host.endsWith(".buct.edu.cn")
    }

    companion object {
        const val PORTAL_BASE_URL = "https://tree.buct.edu.cn"
        const val SELF_SERVICE_BASE_URL = "http://tree.buct.edu.cn:8800"
        private const val MAX_REDIRECTS = 6
    }
}
