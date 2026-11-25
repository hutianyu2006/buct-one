package com.buct.one.data.source.jwnotice

import com.buct.one.data.model.JwAnnouncementDetail
import com.buct.one.data.model.JwAnnouncementItem
import com.buct.one.data.model.JwNoticeSnapshot
import com.buct.one.data.model.JwReminderItem
import com.buct.one.data.text.urlEncode
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters

/** 教务会话失效（返回登录页或票据过期）。 */
class JwAuthExpiredException(message: String) : Exception(message)

/**
 * 教务系统消息（调停课提醒 + 通知公告）的网络客户端，双端共用。
 *
 * 只依赖调用方注入的 [HttpClient]（已挂好会话 Cookie），解析全部复用 [JwNoticeParser]。
 * Android 侧的 `JwNoticeClient` 后续会收敛到这一份实现。
 */
class JwNoticeGateway(
    private val httpClient: HttpClient,
    private val userAgent: String,
) {

    suspend fun fetchRemindersPage(
        page: Int = 1,
        pageSize: Int = 30,
        keyword: String = "",
    ): Pair<Int, List<JwReminderItem>> {
        val fields = linkedMapOf(
            "flag" to "1",
            "queryModel.showCount" to pageSize.toString(),
            "queryModel.currentPage" to page.toString(),
            "queryModel.sortName" to "cjsj",
            "queryModel.sortOrder" to "desc",
            "time" to "0",
        )
        if (keyword.isNotBlank()) fields["gnmkmc"] = keyword.trim()
        val body = postForm(
            url = "$JW_BASE/jwglxt/xtgl/index_cxDbsy.html?doType=query",
            referer = REFERER_MAIN,
            fields = fields,
        )
        return JwNoticeParser.parseRemindersPage(body)
    }

    suspend fun fetchAnnouncementsPage(
        page: Int = 1,
        pageSize: Int = 25,
        keyword: String = "",
    ): Pair<Int, List<JwAnnouncementItem>> {
        val fields = linkedMapOf(
            "queryModel.showCount" to pageSize.toString(),
            "queryModel.currentPage" to page.toString(),
            "queryModel.sortName" to "fbsj",
            "queryModel.sortOrder" to "desc",
            "time" to "0",
        )
        if (keyword.isNotBlank()) fields["xwbt"] = keyword.trim()
        val body = postForm(
            url = "$JW_BASE/jwglxt/xtgl/xwck_cxMoreXwList.html?doType=query",
            referer = "$JW_BASE/jwglxt/xtgl/xwck_cxMoreXwList.html",
            fields = fields,
        )
        return JwNoticeParser.parseAnnouncementsPage(body)
    }

    suspend fun fetchFullSnapshot(
        reminderPageSize: Int = 30,
        announcementPageSize: Int = 25,
    ): JwNoticeSnapshot {
        var reminderError: Exception? = null
        var announcementError: Exception? = null

        val reminders: Pair<Int, List<JwReminderItem>> = try {
            fetchRemindersPage(pageSize = reminderPageSize)
        } catch (expired: JwAuthExpiredException) {
            throw expired
        } catch (error: Exception) {
            reminderError = error
            0 to emptyList()
        }

        val announcements: Pair<Int, List<JwAnnouncementItem>> = try {
            fetchAnnouncementsPage(pageSize = announcementPageSize)
        } catch (expired: JwAuthExpiredException) {
            throw expired
        } catch (error: Exception) {
            announcementError = error
            0 to emptyList()
        }

        if (reminderError != null && announcementError != null) throw reminderError
        return JwNoticeParser.mergeSnapshots(reminders, announcements)
    }

    suspend fun fetchAnnouncementDetail(item: JwAnnouncementItem): JwAnnouncementDetail {
        val encodedId = urlEncode(item.id)
        val response = httpClient.get(
            "$JW_BASE/jwglxt/xtgl/xwck_ckXw.html?xwbh=$encodedId&doType=save",
        ) {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header(HttpHeaders.Referrer, "$JW_BASE/jwglxt/xtgl/xwck_cxMoreXwList.html")
        }
        val html = response.bodyAsText()
        if (JwNoticeParser.isJwLoginHtml(html)) throw JwAuthExpiredException("教务会话已失效，请重新登录")
        return JwNoticeParser.parseAnnouncementDetail(
            xwbh = item.id,
            html = html,
            fallbackTitle = item.title,
            fallbackPublisher = item.publisher,
            fallbackTime = item.publishedAt,
            fallbackInlineHtml = item.inlineHtml,
        )
    }

    private suspend fun postForm(
        url: String,
        referer: String,
        fields: Map<String, String>,
    ): String {
        val response = httpClient.submitForm(
            url = url,
            formParameters = Parameters.build {
                fields.forEach { (key, value) -> append(key, value) }
            },
            encodeInQuery = false,
        ) {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "application/json, text/javascript, */*; q=0.01")
            header(HttpHeaders.Origin, JW_BASE)
            header(HttpHeaders.Referrer, referer)
            header("X-Requested-With", "XMLHttpRequest")
        }
        val body = response.bodyAsText()
        if (JwNoticeParser.isJwLoginHtml(body)) throw JwAuthExpiredException("教务会话已失效，请重新登录")
        return body
    }

    companion object {
        const val JW_BASE = "https://jwglxt.buct.edu.cn"
        val REFERER_MAIN = "$JW_BASE/jwglxt/xtgl/index_initMenu.html"
    }
}
