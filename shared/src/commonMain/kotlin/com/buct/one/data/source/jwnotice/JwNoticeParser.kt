package com.buct.one.data.source.jwnotice

import com.buct.one.data.model.JwAnnouncementDetail
import com.buct.one.data.model.JwAnnouncementItem
import com.buct.one.data.model.JwNoticeSnapshot
import com.buct.one.data.model.JwReminderItem
import com.buct.one.data.model.classifyJwReminder
import com.buct.one.data.json.JSONArray
import com.buct.one.data.json.JSONObject
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document

/**
 * 教务系统（jwglxt.buct.edu.cn）消息与通知公告纯函数解析器。
 * 遵循数据层铁律 3：改动解析逻辑必须递增 PARSER_VERSION。
 */
object JwNoticeParser {
    const val PARSER_VERSION: Int = 2

    const val DETAIL_BASE_URL: String = "https://jwglxt.buct.edu.cn/jwglxt/xtgl/"

    data class SidPortalPageInfo(
        val execution: String,
        val croypto: String,
        val errorCode: String
    )

    private fun JSONObject.optCleanString(key: String, fallback: String = ""): String {
        if (isNull(key)) return fallback
        val v = optString(key, fallback).trim()
        return if (v.equals("null", ignoreCase = true)) fallback else v
    }

    fun parseSidPortalPage(html: String): SidPortalPageInfo {
        val doc = Ksoup.parse(html)
        fun text(id: String): String = doc.selectFirst("#$id")?.text()?.trim().orEmpty()
        return SidPortalPageInfo(
            execution = text("login-page-flowkey"),
            croypto = text("login-croypto"),
            errorCode = text("login-error-msg").ifEmpty { text("login-error-code") }
        )
    }

    fun isJwLoginHtml(html: String): Boolean {
        if (html.isBlank() || !html.trimStart().startsWith("<")) return false
        return html.contains("login-page-flowkey") ||
            html.contains("login-croypto") ||
            html.contains("slogin.html") ||
            html.contains("id=\"pwd\"") ||
            html.contains("name=\"yhm\"") ||
            html.contains("用户登录") ||
            html.contains("统一身份认证")
    }

    fun parseRemindersPage(rawJson: String): Pair<Int, List<JwReminderItem>> {
        val root = JSONObject(rawJson.trim())
        val itemsArr = root.optJSONArray("items") ?: JSONArray()
        val parsedTotal = root.optCleanString("totalResult").toIntOrNull()
            ?: root.optInt("totalCount", itemsArr.length())

        val result = ArrayList<JwReminderItem>(itemsArr.length())
        val seenKeys = HashSet<String>(itemsArr.length())
        for (i in 0 until itemsArr.length()) {
            val obj = itemsArr.optJSONObject(i) ?: continue
            val rawId = obj.optCleanString("id")
                .ifBlank { obj.optCleanString("zjxx") }
                .ifBlank { obj.optCleanString("w_id") }
            val title = obj.optCleanString("xxbt").ifBlank { obj.optCleanString("xxbtjc") }
            val content = obj.optCleanString("xxnr").ifBlank { title }
            val createdAt = obj.optCleanString("cjsj")
            if (title.isBlank() && content.isBlank()) continue

            val effectiveId = rawId.ifBlank { "$createdAt|$title" }
            if (!seenKeys.add(effectiveId)) continue

            val isRead = obj.optCleanString("clzt") == "1" || obj.optCleanString("sfyd") == "1"
            result.add(
                JwReminderItem(
                    id = effectiveId,
                    title = title.ifBlank { "教务提醒" },
                    content = content,
                    createdAt = createdAt,
                    isRead = isRead,
                    category = classifyJwReminder(title, content)
                )
            )
        }
        return parsedTotal.coerceAtLeast(result.size) to result
    }

    fun parseAnnouncementsPage(rawJson: String): Pair<Int, List<JwAnnouncementItem>> {
        val root = JSONObject(rawJson.trim())
        val itemsArr = root.optJSONArray("items") ?: JSONArray()
        val parsedTotal = root.optCleanString("totalResult").toIntOrNull()
            ?: root.optInt("totalCount", itemsArr.length())

        val result = ArrayList<JwAnnouncementItem>(itemsArr.length())
        val seenKeys = HashSet<String>(itemsArr.length())
        for (i in 0 until itemsArr.length()) {
            val obj = itemsArr.optJSONObject(i) ?: continue
            val rawId = obj.optCleanString("xwbh")
            val title = obj.optCleanString("xwbtqc").ifBlank { obj.optCleanString("xwbt") }
            if (rawId.isBlank() && title.isBlank()) continue

            val publisher = obj.optCleanString("fbr")
            val publishedAt = obj.optCleanString("fbsj")
            val effectiveId = rawId.ifBlank { "$publishedAt|$title" }
            if (!seenKeys.add(effectiveId)) continue

            result.add(
                JwAnnouncementItem(
                    id = effectiveId,
                    title = title.ifBlank { "教务公告" },
                    publisher = publisher,
                    publishedAt = publishedAt,
                    isPinned = obj.optCleanString("sfzd") == "1",
                    isRead = obj.optCleanString("sfyd") != "0",
                    externalUrl = obj.optCleanString("fbdz"),
                    inlineHtml = obj.optCleanString("fbnr")
                )
            )
        }
        return parsedTotal.coerceAtLeast(result.size) to result
    }

    fun parseAnnouncementDetail(
        xwbh: String,
        html: String,
        fallbackTitle: String = "",
        fallbackPublisher: String = "",
        fallbackTime: String = "",
        fallbackInlineHtml: String = ""
    ): JwAnnouncementDetail {
        val doc: Document = Ksoup.parse(html, DETAIL_BASE_URL)
        val container = doc.selectFirst("div.newsdisp") ?: doc.body()

        val title = container.selectFirst("h3.text-center")?.text()?.trim().orEmpty()
            .ifBlank { fallbackTitle }

        var publisher = ""
        var publishedAt = ""
        var viewCount = ""
        for (span in container.select("h5.news_title1 span")) {
            val text = span.text().trim()
            when {
                text.startsWith("发布人：") -> publisher = text.removePrefix("发布人：").trim()
                text.startsWith("发布人:") -> publisher = text.removePrefix("发布人:").trim()
                text.startsWith("发布时间：") -> publishedAt = text.removePrefix("发布时间：").trim()
                text.startsWith("发布时间:") -> publishedAt = text.removePrefix("发布时间:").trim()
                text.startsWith("浏览人数：") -> viewCount = text.removePrefix("浏览人数：").trim()
                text.startsWith("浏览人数:") -> viewCount = text.removePrefix("浏览人数:").trim()
            }
        }

        val rawBodyHtml = container.selectFirst("div.news_con")?.html().orEmpty()
        val (sanitizedHtml, plainText) = if (rawBodyHtml.isNotBlank()) {
            sanitizeBodyHtml(rawBodyHtml)
        } else if (fallbackInlineHtml.isNotBlank()) {
            sanitizeBodyHtml(fallbackInlineHtml)
        } else {
            "" to ""
        }

        return JwAnnouncementDetail(
            id = xwbh,
            title = title,
            publisher = publisher.ifBlank { fallbackPublisher },
            publishedAt = publishedAt.ifBlank { fallbackTime },
            viewCount = viewCount,
            bodyHtml = sanitizedHtml,
            plainText = plainText
        )
    }

    fun sanitizeBodyHtml(rawHtml: String): Pair<String, String> {
        if (rawHtml.isBlank()) return "" to ""
        val frag = Ksoup.parseBodyFragment(rawHtml, DETAIL_BASE_URL)
        frag.select("script, iframe, object, embed, form, style").remove()
        for (el in frag.getAllElements()) {
            val attrsToRemove = el.attributes().asList()
                .map { it.key }
                .filter { key ->
                    val lower = key.lowercase()
                    lower.startsWith("on") ||
                        ((lower == "href" || lower == "src") &&
                            el.attr(key).trim().lowercase().let { v ->
                                v.startsWith("javascript:") || v.startsWith("vbscript:")
                            })
                }
            for (key in attrsToRemove) {
                el.removeAttr(key)
            }
        }
        for (el in frag.select("a[href]")) {
            val abs = el.absUrl("href")
            if (abs.isNotBlank()) el.attr("href", abs)
        }
        for (el in frag.select("img[src]")) {
            val abs = el.absUrl("src")
            if (abs.isNotBlank()) el.attr("src", abs)
        }
        val bodyHtml = frag.body().html().trim()
        val plain = frag.body().text().trim()
        return bodyHtml to plain
    }

    fun mergeSnapshots(
        remindersPair: Pair<Int, List<JwReminderItem>>,
        announcementsPair: Pair<Int, List<JwAnnouncementItem>>
    ): JwNoticeSnapshot = JwNoticeSnapshot(
        reminders = remindersPair.second,
        reminderTotal = remindersPair.first,
        announcements = announcementsPair.second,
        announcementTotal = announcementsPair.first
    )
}
