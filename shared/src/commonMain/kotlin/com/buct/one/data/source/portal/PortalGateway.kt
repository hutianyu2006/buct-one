package com.buct.one.data.source.portal

import com.buct.one.data.model.PortalCalendarSnapshot
import com.buct.one.data.model.PortalLibrarySnapshot
import com.buct.one.data.model.PortalNewsColumn
import com.buct.one.data.model.PortalNewsColumnPage
import com.buct.one.data.model.PortalNewsSnapshot
import com.buct.one.data.text.resolveUrl
import com.buct.one.data.text.urlHostOf
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

/** 信息门户会话失效（跳到统一认证或返回登录页）。 */
class PortalAuthExpiredException(message: String) : Exception(message)

/**
 * i北化信息门户（i.buct.edu.cn）与校内资讯（mnews.buct.edu.cn）网关，双端共用。
 *
 * 关键协议点（与 Android 实现一致，改动前先看 `PortalClient`）：
 * - 所有门户接口都必须带 `_p` 上下文参数，缺省返回 **HTTP 403**；
 * - 校历接口 `calendar/mgr/api/ids/list.rst` 的 `fromDate`/`endDate` 必须是 ISO-8601 UTC；
 * - 会话由注入的 [HttpClient] 持有；校外访问依赖调用方已配置的 WebVPN 改写。
 *
 * 解析全部复用 [PortalParser]。
 */
class PortalGateway(
    private val httpClient: HttpClient,
    private val userAgent: String,
) {

    /** 校历 + 教学周 + 节假日（供课表校准开学日期）。 */
    suspend fun fetchCalendarSnapshot(todayDate: String): PortalCalendarSnapshot {
        val jxzUrl = "$PORTAL_BASE/_web/fusionportal/getJxz.jsp" +
            "?currentDate=${encodeQuery(todayDate)}&_p=$DEFAULT_PORTAL_CTX"
        val base = PortalParser.parseJxzResponse(fetchAuthenticated(jxzUrl))

        val holidays = if (base.beginDate.length == 10 && base.endDate.length == 10) {
            runCatching {
                val idsUrl = "$PORTAL_BASE/calendar/mgr/api/ids/list.rst" +
                    "?queryType=2&categoryIds=-2" +
                    "&fromDate=${base.beginDate}T00:00:00.000Z" +
                    "&endDate=${base.endDate}T00:00:00.000Z" +
                    "&type=0&_p=$DEFAULT_PORTAL_CTX"
                PortalParser.parseCalendarDays(fetchAuthenticated(idsUrl))
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return base.copy(holidays = holidays)
    }

    /** 在借图书（首页「借阅图书」卡片）。 */
    suspend fun fetchBorrowedBooks(page: Int = 1, rows: Int = 50): PortalLibrarySnapshot {
        val url = "$PORTAL_BASE/_web/fusionportal/getYktOrBook.jsp" +
            "?page=$page&rows=$rows&type=book&_p=$DEFAULT_PORTAL_CTX"
        return PortalParser.parseBorrowedBooks(fetchAuthenticated(url))
    }

    /** 单个资讯栏目分页。 */
    suspend fun fetchNewsColumn(
        columnId: String,
        beginIndex: Int = 0,
        pageSize: Int = 15,
    ): PortalNewsColumnPage {
        val url = "$MNEWS_BASE/mobile/getPortalArticleList16ByUser.rst" +
            "?siteId=$MNEWS_SITE_ID&columnId=$columnId&pageSize=$pageSize&beginIndex=$beginIndex&scope=0"
        return PortalParser.parseNewsArticlesPage(columnId, fetchMnews(url))
    }

    /** 依次拉取全部 4 个资讯栏目；单个栏目失败不影响其他栏目，全部失败才抛错。 */
    suspend fun fetchAllNewsSnapshot(pageSize: Int = 15): PortalNewsSnapshot {
        val columns = LinkedHashMap<String, PortalNewsColumnPage>()
        var firstError: Exception? = null
        for (column in PortalNewsColumn.entries) {
            try {
                columns[column.columnId] = fetchNewsColumn(column.columnId, beginIndex = 0, pageSize = pageSize)
            } catch (expired: PortalAuthExpiredException) {
                throw expired
            } catch (error: Exception) {
                if (firstError == null) firstError = error
            }
        }
        if (columns.isEmpty() && firstError != null) throw firstError
        return PortalNewsSnapshot(columns = columns)
    }

    private suspend fun fetchAuthenticated(fullUrl: String): String {
        var current = upgradeToHttps(fullUrl)
        repeat(MAX_REDIRECT_HOPS) {
            if (!isPortalHost(current)) throw PortalAuthExpiredException("信息门户跳转到了非预期域名")
            val response = httpClient.get(current) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "application/json, text/javascript, */*; q=0.01")
                header(HttpHeaders.Referrer, REFERER_MAIN)
                header("X-Requested-With", "XMLHttpRequest")
            }
            val status = response.status.value
            if (status in 300..399) {
                val location = response.headers[HttpHeaders.Location]
                    ?: throw PortalAuthExpiredException("信息门户重定向缺少地址")
                val next = upgradeToHttps(resolveUrl(current, location))
                val host = urlHostOf(next)?.lowercase().orEmpty()
                if (host == PORTAL_HOST && !next.contains("/login", ignoreCase = true)) {
                    current = next
                    return@repeat
                }
                throw PortalAuthExpiredException("信息门户统一认证会话已失效，请重新登录")
            }
            if (status == 401 || status == 403) {
                throw PortalAuthExpiredException("信息门户认证失效或拒绝访问（HTTP $status）")
            }
            if (status !in 200..299) throw IllegalStateException("信息门户请求失败（HTTP $status）")
            val body = response.bodyAsText()
            val trimmed = body.trimStart()
            if (trimmed.startsWith("<") &&
                (body.contains("login-page-flowkey") || body.contains("统一身份认证"))
            ) {
                throw PortalAuthExpiredException("信息门户统一认证会话已失效，请重新登录")
            }
            return body
        }
        throw IllegalStateException("信息门户接口重定向次数过多")
    }

    private suspend fun fetchMnews(fullUrl: String): String {
        var current = upgradeToHttps(fullUrl)
        repeat(MAX_MNEWS_HOPS) {
            val response = httpClient.get(current) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "application/json, text/javascript, */*; q=0.01")
                header(HttpHeaders.Referrer, REFERER_MAIN)
            }
            val status = response.status.value
            if (status in 300..399) {
                val location = response.headers[HttpHeaders.Location]
                    ?: throw PortalAuthExpiredException("校内资讯重定向缺少地址")
                val next = upgradeToHttps(resolveUrl(current, location))
                if (!isMnewsOrPortalHost(next)) throw PortalAuthExpiredException("校内资讯跳转到了非预期域名")
                current = next
                return@repeat
            }
            if (status == 401 || status == 403) throw PortalAuthExpiredException("校内资讯认证失效")
            if (status !in 200..299) throw IllegalStateException("校内资讯请求失败（HTTP $status）")
            val body = response.bodyAsText()
            val host = urlHostOf(current)?.lowercase().orEmpty()
            val trimmed = body.trimStart()
            if (host == PORTAL_HOST_LEGACY ||
                (trimmed.startsWith("<") &&
                    (body.contains("login-page-flowkey") || body.contains("统一身份认证")))
            ) {
                throw PortalAuthExpiredException("校内资讯会话已失效，请重新登录")
            }
            return body
        }
        throw IllegalStateException("校内资讯重定向次数过多")
    }

    private fun isPortalHost(url: String): Boolean =
        urlHostOf(url)?.equals(PORTAL_HOST, ignoreCase = true) == true

    private fun isMnewsOrPortalHost(url: String): Boolean {
        val host = urlHostOf(url)?.lowercase() ?: return false
        return host == MNEWS_HOST || host == PORTAL_HOST || host == PORTAL_HOST_LEGACY
    }

    private fun upgradeToHttps(url: String): String = when {
        url.startsWith("http://$PORTAL_HOST") -> url.replaceFirst("http://", "https://")
        url.startsWith("http://$MNEWS_HOST") -> url.replaceFirst("http://", "https://")
        else -> url
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

    companion object {
        const val PORTAL_BASE = "https://i.buct.edu.cn"
        const val MNEWS_BASE = "https://mnews.buct.edu.cn"
        const val MNEWS_SITE_ID = "100000251"

        private const val PORTAL_HOST = "i.buct.edu.cn"
        private const val PORTAL_HOST_LEGACY = "sidportal.buct.edu.cn"
        private const val MNEWS_HOST = "mnews.buct.edu.cn"
        private const val DEFAULT_PORTAL_CTX = "YXM9MiZ0PTYmZD0xMTYmcD0xJmY9MzAmbT1OJg__"
        private const val REFERER_MAIN = "$PORTAL_BASE/_s2/students_sy/main.psp"
        private const val MAX_REDIRECT_HOPS = 4
        private const val MAX_MNEWS_HOPS = 14
        private const val HEX = "0123456789ABCDEF"
    }
}
