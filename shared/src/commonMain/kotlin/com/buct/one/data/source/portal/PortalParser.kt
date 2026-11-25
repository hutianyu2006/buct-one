package com.buct.one.data.source.portal

import com.buct.one.data.model.PortalBookItem
import com.buct.one.data.model.PortalCalendarSnapshot
import com.buct.one.data.model.PortalHolidayItem
import com.buct.one.data.model.PortalLibrarySnapshot
import com.buct.one.data.model.PortalNewsArticle
import com.buct.one.data.model.PortalNewsColumnPage
import com.buct.one.data.model.PortalTermInfo
import com.buct.one.data.json.JSONArray
import com.buct.one.data.json.JSONObject
import com.fleeksoft.ksoup.Ksoup
import com.buct.one.data.text.urlDecode

/**
 * i北化信息门户（i.buct.edu.cn & mnews.buct.edu.cn）纯函数响应解析器。
 * 遵循数据层铁律 3：改动解析逻辑必须递增 PARSER_VERSION。
 */
object PortalParser {
    const val PARSER_VERSION: Int = 3

    private val DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}")

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

    fun isPortalAuthenticatedHtml(html: String): Boolean {
        if (html.isBlank()) return false
        if (html.contains("login-page-flowkey") || html.contains("login-croypto")) {
            return false
        }
        return html.contains("fusionportal") ||
            html.contains("students_sy") ||
            html.contains("i北化") ||
            html.contains("_web/portal")
    }

    fun parseJxzResponse(rawJson: String): PortalCalendarSnapshot {
        val root = JSONObject(rawJson.trim())
        val result = root.optCleanString("result")
        if (result != "1") {
            val reason = root.optCleanString("reason")
            throw IllegalStateException(reason.ifBlank { "信息门户校历响应异常（result=$result）" })
        }
        val data = root.optJSONObject("data")
            ?: throw IllegalStateException("信息门户校历响应缺少 data")

        val rawBeginDate = data.optCleanString("beginDate").take(10)
        val rawEndDate = data.optCleanString("endDate").take(10)
        val jxz = data.optCleanString("jxz").toIntOrNull() ?: 0

        val terms = ArrayList<PortalTermInfo>()
        val retArr = data.optJSONArray("retStr") ?: JSONArray()
        for (i in 0 until retArr.length()) {
            val obj = retArr.optJSONObject(i) ?: continue
            val xn = obj.optCleanString("xn").toIntOrNull() ?: continue
            val xq = obj.optCleanString("xq").toIntOrNull()?.takeIf { it in 1..3 } ?: continue
            val ksrq = obj.optCleanString("ksrq").take(10)
            if (!DATE_REGEX.matches(ksrq)) continue
            val jsrq = obj.optCleanString("jsrq").take(10)
            val wid = obj.optCleanString("wid")
            val isCurrent = obj.optCleanString("sfdqxq") == "1"
            terms.add(
                PortalTermInfo(
                    wid = wid,
                    academicYear = xn,
                    semester = xq,
                    termId = "$xn-${xn + 1}-$xq",
                    beginDate = ksrq,
                    endDate = jsrq,
                    isCurrent = isCurrent
                )
            )
        }

        val currentTerm = terms.firstOrNull { it.isCurrent }
            ?: terms.firstOrNull { it.beginDate == rawBeginDate && rawBeginDate.isNotBlank() }
        val currentTermId = currentTerm?.termId.orEmpty()
        val beginDate = rawBeginDate.ifBlank { currentTerm?.beginDate.orEmpty() }
        val endDate = rawEndDate.ifBlank { currentTerm?.endDate.orEmpty() }

        return PortalCalendarSnapshot(
            currentTermId = currentTermId,
            beginDate = beginDate,
            endDate = endDate,
            currentTeachingWeek = jxz,
            terms = terms,
            holidays = emptyList()
        )
    }

    fun parseCalendarDays(rawJson: String): List<PortalHolidayItem> {
        val arr = JSONArray(rawJson.trim())
        val byDate = LinkedHashMap<String, PortalHolidayItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val dayRaw = obj.optCleanString("day")
            val date = if (dayRaw.length >= 10) dayRaw.substring(0, 10) else ""
            if (!DATE_REGEX.matches(date)) continue
            val holidayName = obj.optCleanString("holidayName")
            val isHoliday = obj.optBoolean("isHoliday", false)
            if (!isHoliday && holidayName.isBlank()) continue
            val teachingWeek = obj.optInt("teachingWeek", 0)
            val candidate = PortalHolidayItem(
                date = date,
                holidayName = holidayName,
                isHoliday = isHoliday,
                teachingWeek = teachingWeek
            )
            val existing = byDate[date]
            if (existing == null || (existing.holidayName.isBlank() && candidate.holidayName.isNotBlank())) {
                byDate[date] = candidate
            }
        }
        return byDate.values.sortedBy { it.date }
    }

    fun parseBorrowedBooks(rawJson: String): PortalLibrarySnapshot {
        val root = JSONObject(rawJson.trim())
        val result = root.optCleanString("result")
        if (result != "1") {
            val reason = root.optCleanString("reason")
            throw IllegalStateException(reason.ifBlank { "信息门户图书借阅响应异常（result=$result）" })
        }
        val data = root.optJSONObject("data")
            ?: throw IllegalStateException("信息门户图书借阅响应缺少 data")

        val totalCount = data.optInt("count", 0).coerceAtLeast(0)
        val arr = data.optJSONArray("list") ?: JSONArray()
        val books = ArrayList<PortalBookItem>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val title = obj.optCleanString("tsmc")
            val barcode = obj.optCleanString("tstxm")
            if (title.isBlank() && barcode.isBlank()) continue
            books.add(
                PortalBookItem(
                    title = title.ifBlank { "未知书名" },
                    barcode = barcode,
                    borrowDate = obj.optCleanString("jsrq"),
                    dueDate = obj.optCleanString("hsrq")
                )
            )
        }
        return PortalLibrarySnapshot(
            totalCount = maxOf(totalCount, books.size),
            books = books
        )
    }

    fun parseNewsArticlesPage(columnId: String, rawJson: String): PortalNewsColumnPage {
        val trimmed = rawJson.trim()
        val objStart = trimmed.indexOf('{')
        val objEnd = trimmed.lastIndexOf('}')
        val jsonPayload = if (objStart >= 0 && objEnd > objStart) {
            trimmed.substring(objStart, objEnd + 1)
        } else {
            trimmed
        }

        val root = JSONObject(jsonPayload)
        val result = root.optCleanString("result")
        if (result != "1") {
            val reason = root.optCleanString("reason")
            throw IllegalStateException(reason.ifBlank { "校内资讯响应异常（result=$result）" })
        }
        val data = root.optJSONObject("data")
            ?: throw IllegalStateException("校内资讯响应缺少 data")

        val totalCount = data.optInt("count", 0).coerceAtLeast(0)
        val arr = data.optJSONArray("articles") ?: JSONArray()
        val articles = ArrayList<PortalNewsArticle>(arr.length())
        val seenIds = HashSet<String>(arr.length())

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val title = obj.optCleanString("title")
            val rawId = obj.optCleanString("id")
                .ifBlank { obj.optLong("id", 0L).takeIf { it > 0L }?.toString().orEmpty() }
            if (rawId.isBlank() && title.isBlank()) continue

            val sourceOrg = obj.optCleanString("from").ifBlank { obj.optCleanString("createOrg") }
            val publishTs = obj.optCleanString("publishTimestamp").toLongOrNull()
                ?: obj.optLong("publishTimestamp", 0L)
            val effectiveId = rawId.ifBlank { "$columnId|$publishTs|$title" }
            if (!seenIds.add(effectiveId)) continue

            val rawLink = obj.optCleanString("linkUrl")
                .ifBlank { obj.optCleanString("articleUrl") }
                .ifBlank { obj.optCleanString("mobileArticleUrl") }

            articles.add(
                PortalNewsArticle(
                    id = effectiveId,
                    columnId = columnId,
                    title = title.ifBlank { "校内资讯" },
                    sourceOrg = sourceOrg,
                    publishTimestampMs = publishTs,
                    summary = obj.optCleanString("summary"),
                    content = obj.optCleanString("content"),
                    publicUrl = extractPublicArticleUrl(rawLink),
                    isTop = obj.optCleanString("isTop") == "1",
                    isHot = obj.optCleanString("isHot") == "1",
                    isNewest = obj.optCleanString("isNewest") == "1",
                    visitCount = obj.optCleanString("visitCount")
                )
            )
        }

        return PortalNewsColumnPage(
            columnId = columnId,
            totalCount = maxOf(totalCount, articles.size),
            articles = articles
        )
    }

    fun extractPublicArticleUrl(rawLink: String): String {
        val clean = rawLink.trim()
        if (clean.isEmpty()) return ""
        val key = "linkUrl="
        val idx = clean.indexOf(key)
        if (idx < 0) return clean
        val encoded = clean.substring(idx + key.length).substringBefore('&').trim()
        if (encoded.isEmpty()) return clean
        return runCatching { urlDecode(encoded) }.getOrDefault(encoded).trim()
    }
}
