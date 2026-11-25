package com.buct.one.data.source.theol

import com.buct.one.data.model.TheolCourseInfo
import com.buct.one.data.model.TheolCourseUnit
import com.buct.one.data.model.TheolHomeworkAttachment
import com.buct.one.data.model.TheolHomeworkDetail
import com.buct.one.data.model.TheolHomeworkItem
import com.buct.one.data.model.TheolResourceItem
import com.buct.one.data.model.TheolResourcePage
import com.buct.one.data.model.TheolTestDetail
import com.buct.one.data.model.TheolTestItem
import com.buct.one.data.text.htmlUnescape
import com.buct.one.data.text.parseDateTimeLoose
import com.buct.one.data.text.replaceUrlPath
import com.buct.one.data.text.resolveUrl
import com.buct.one.data.text.urlEncode
import com.buct.one.data.text.urlHostOf
import com.buct.one.data.text.urlPathOf
import com.buct.one.data.text.urlQueryOf
import com.buct.one.data.text.urlSchemeOf
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element

/**
 * 北化在线（THEOL / MEOL）HTML 纯函数解析器。
 * 移植自 Ling0727-ai/go-buct-course (MIT)。
 * 改动解析逻辑时必须将 PARSER_VERSION + 1。
 */
object TheolParser {
    const val PARSER_VERSION = 8
    const val BASE_URL = "https://course.buct.edu.cn"
    private const val COURSE_HOME_PATH = "/meol/jpk/course/layout/newpage/index.jsp"
    private val COURSE_ONCLICK_ID_REGEX = Regex("""(?:courseId|lid)\s*=\s*(\d+)""")

    private val testStartRegex = Regex("""gotostart\('(\d+)'""")
    data class SidPortalPage(
        val execution: String,
        val croypto: String,
        val vendor: String,
        val risk: String,
        val rule: String,
        val second: String,
        val errorCode: String,
        val captchaUrl: String
    )

    fun parseSidPortalPage(html: String): SidPortalPage {
        val doc = Ksoup.parse(html)
        fun text(id: String) = doc.selectFirst("#$id")?.text()?.trim().orEmpty()
        val code = text("login-error-msg").ifEmpty { text("login-error-code") }
        return SidPortalPage(
            execution = text("login-page-flowkey"),
            croypto = text("login-croypto"),
            vendor = text("recaptchaVendor"),
            risk = text("riskSystemSwitch"),
            rule = text("login-rule-type"),
            second = text("sso-second"),
            errorCode = code,
            captchaUrl = text("captcha-url")
        )
    }

    /** 解析 interaction_reminder_v8.jsp 中的待办作业与测验课程 LID */
    fun parsePendingCourses(html: String, baseUrl: String = BASE_URL): Pair<List<TheolCourseInfo>, List<TheolCourseInfo>> {
        val doc = Ksoup.parse(html, baseUrl)
        val homeworks = mutableListOf<TheolCourseInfo>()
        val tests = mutableListOf<TheolCourseInfo>()
        val seenHw = mutableSetOf<String>()
        val seenTest = mutableSetOf<String>()

        for (a in doc.select("a[onclick]")) {
            val onclick = a.attr("onclick")
            if (onclick.contains("&t=hw")) {
                extractPendingCourse(a, "homework", baseUrl)?.let {
                    if (seenHw.add(it.lid)) homeworks.add(it)
                }
            }
            if (onclick.contains("&t=test")) {
                extractPendingCourse(a, "test", baseUrl)?.let {
                    if (seenTest.add(it.lid)) tests.add(it)
                }
            }
        }
        return homeworks to tests
    }

    /**
     * 解析全量课程目录页（/meol/welcomepage/student/course_list.jsp 的 init_course.jsp?lid=...，
     * 以及 /meol/lesson/blen.student.lesson.list.jsp 的 onclick course_index.jsp?courseId=... 或主页链接）。
     * 用于补齐“所有作业均已交完、不再出现在 interaction_reminder_v8.jsp 待办提醒中”的在修课程。
     */
    fun parseCatalogCourses(html: String, sourceUrl: String = BASE_URL): List<TheolCourseInfo> {
        val doc = Ksoup.parse(html, sourceUrl)
        val courses = mutableListOf<TheolCourseInfo>()
        val seen = mutableSetOf<String>()
        val cleanBase = BASE_URL.trimEnd('/')

        fun addCourse(lid: String, rawName: String) {
            val cleanLid = lid.trim()
            val name = rawName.trim()
            if (cleanLid.isEmpty() || !cleanLid.all(Char::isDigit) || cleanLid.all { it == '0' }) return
            if (name.isEmpty() || !seen.add(cleanLid)) return
            courses.add(
                TheolCourseInfo(
                    lid = cleanLid,
                    courseName = name,
                    type = "homework",
                    url = "$cleanBase$COURSE_HOME_PATH?courseId=$cleanLid"
                )
            )
        }

        for (a in doc.select("a[href]")) {
            val resolved = resolveHttpUrl(a.attr("href"), sourceUrl)
            if (resolved.isNotEmpty()) {
                val path = urlPathOf(resolved)
                val isCourseLink = path.equals(COURSE_HOME_PATH, ignoreCase = true) ||
                    path.endsWith("/lesson/init_course.jsp", ignoreCase = true) ||
                    path.endsWith("/homepage/course/course_index.jsp", ignoreCase = true)
                if (isCourseLink) {
                    val lid = extractPositiveIdParam(resolved, "lid")
                        .ifEmpty { extractPositiveIdParam(resolved, "courseId") }
                    if (lid.isNotEmpty()) {
                        addCourse(lid, a.text())
                        continue
                    }
                }
            }
            val onclick = a.attr("onclick")
            if (onclick.contains("courseId=") || onclick.contains("lid=")) {
                val match = COURSE_ONCLICK_ID_REGEX.find(onclick)
                val lid = match?.groupValues?.getOrNull(1).orEmpty()
                if (lid.isNotEmpty()) {
                    addCourse(lid, a.text())
                }
            }
        }
        return courses
    }

    /**
     * 从课程主页 HTML 中精确定位“课程作业 / 作业提交”相关栏目链接，避免无意义遍历所有教学栏目。
     */
    fun extractHomeworkColumnUrls(courseHomeHtml: String, sourceUrl: String): List<String> {
        val doc = Ksoup.parse(courseHomeHtml, sourceUrl)
        val urls = LinkedHashSet<String>()
        for (a in doc.select("a[href]")) {
            val rawHref = a.attr("href").trim()
            if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.startsWith("javascript:", true)) continue
            val text = a.text().trim()
            val hrefLower = rawHref.lowercase()
            val isHwText = text.contains("作业") && !text.contains("测试")
            val isHwHref = hrefLower.contains("hwtask")
            if (!isHwText && !isHwHref) continue
            val resolved = resolveHttpUrl(rawHref, sourceUrl)
            if (resolved.isNotEmpty()) {
                urls.add(resolved)
                if (urls.size >= 3) break
            }
        }
        return urls.toList()
    }

    /** 构造课程在线测验列表入口（MEOL 无独立的 test_start.jsp 页面，统一由列表页点击开始） */
    fun buildTestListUrl(lid: String, baseUrl: String = BASE_URL): String {
        val cleanBase = baseUrl.trimEnd('/')
        val encodedLid = urlEncode(lid.trim())
        return "$cleanBase/meol/common/question/test/student/list.jsp" +
            "?sortColumn=createTime&status=1&tagbug=client&sortDirection=-1&strStyle=new03" +
            "&cateId=$encodedLid&pagingPage=1&pagingNumberPer=30"
    }

    private fun extractPendingCourse(a: Element, expectedType: String, baseUrl: String): TheolCourseInfo? {
        val courseName = a.text().trim()
        val onclick = a.attr("onclick")
        if (!onclick.contains("lid=")) return null
        val lid = onclick.substringAfter("lid=").substringBefore("&").substringBefore("'").substringBefore("\"").trim()
        if (lid.isEmpty()) return null
        if (courseName.contains("门课程") && courseName.contains("待提交")) return null
        if (expectedType == "test" && (lid == "24199" || lid == "27215")) return null

        val url = if (expectedType == "test") {
            buildTestListUrl(lid, baseUrl)
        } else {
            "$baseUrl/meol/jpk/course/layout/newpage/index.jsp?courseId=$lid"
        }
        return TheolCourseInfo(lid = lid, courseName = courseName, type = expectedType, url = url)
    }

    /** 解析单个页面中的所有作业（支持横表与竖表两种结构） */
    fun parseHomeworkPage(
        html: String,
        sourceUrl: String,
        lid: String,
        courseName: String,
        nowMillis: Long
    ): List<TheolHomeworkItem> =
        parseHomeworkPage(Ksoup.parse(html, sourceUrl), sourceUrl, lid, courseName, nowMillis)

    /** 复用已构建的 Jsoup Document 解析作业列表，避免重复解析整页 HTML */
    fun parseHomeworkPage(
        doc: Document,
        sourceUrl: String,
        lid: String,
        courseName: String,
        nowMillis: Long
    ): List<TheolHomeworkItem> {
        val result = mutableListOf<TheolHomeworkItem>()
        val seen = mutableSetOf<String>()

        for (table in doc.select("table.valuelist")) {
            val vertical = parseVerticalHomeworkTable(table, sourceUrl, lid, courseName, nowMillis)
            if (vertical != null) {
                if (vertical.hwtid.isNotEmpty() && seen.add(vertical.hwtid)) {
                    result.add(vertical)
                }
                continue
            }
            for (row in table.select("tr")) {
                val item = parseHomeworkRow(row, sourceUrl, lid, courseName, nowMillis)
                if (item != null && seen.add(item.hwtid)) {
                    result.add(item)
                }
            }
        }
        return result
    }

    private fun parseVerticalHomeworkTable(
        table: Element,
        sourceUrl: String,
        lid: String,
        courseName: String,
        nowMillis: Long
    ): TheolHomeworkItem? {
        val rows = table.select("tr")
        val isVertical = rows.any { tr ->
            val ths = tr.select("> th")
            val tds = tr.select("> td")
            ths.size == 1 && tds.size == 1 && ths.text().contains("标题")
        }
        if (!isVertical) return null

        var title = ""
        var hwtid = ""
        var detailHref = ""
        var isGroup = false
        var deadline = ""
        var score = ""
        var publisher = ""
        var submitHref = ""
        var resultHref = ""

        for (tr in rows) {
            val ths = tr.select("> th")
            val tds = tr.select("> td")
            if (ths.size != 1 || tds.size != 1) continue
            val label = ths.text().trim()
            val value = tds.text().trim()
            val td = tds.first() ?: continue
            when {
                label.contains("标题") -> {
                    title = value
                    for (a in td.select("a[href]")) {
                        val href = resolveActiveLink(a, sourceUrl)
                        val id = extractHwtid(href)
                        if (id.isNotEmpty()) {
                            hwtid = id
                            detailHref = href
                            break
                        }
                    }
                    isGroup = td.select("img[title=分组作业]").isNotEmpty()
                }
                label.contains("截止") -> deadline = value
                label.contains("评分") || label.contains("分数") -> score = value
                label.contains("发布人") -> publisher = value
                label.contains("提交作业") -> submitHref = firstActiveLink(td.select("a[href]"), sourceUrl)
                label.contains("查看结果") -> resultHref = firstActiveLink(td.select("a[href]"), sourceUrl)
            }
        }

        if (hwtid.isEmpty()) {
            hwtid = listOf(submitHref, resultHref, sourceUrl)
                .firstNotNullOfOrNull { extractHwtid(it).takeIf(String::isNotEmpty) }
                .orEmpty()
        }
        if (hwtid.isEmpty()) return null

        return finalizeHomework(
            hwtid = hwtid,
            lid = lid,
            courseName = courseName,
            title = title,
            deadline = deadline,
            score = score,
            publisher = publisher,
            isGroup = isGroup,
            detailHref = detailHref.ifEmpty { "$BASE_URL/meol/common/hw/student/hwtask.view.jsp?hwtid=$hwtid" },
            submitHref = submitHref,
            resultHref = resultHref,
            nowMillis = nowMillis
        )
    }

    private fun parseHomeworkRow(
        row: Element,
        sourceUrl: String,
        lid: String,
        courseName: String,
        nowMillis: Long
    ): TheolHomeworkItem? {
        val cells = row.select("> td")
        if (cells.size < 4) return null

        var title = ""
        var hwtid = ""
        var detailHref = ""
        for (a in cells[0].select("a[href]")) {
            val href = resolveActiveLink(a, sourceUrl)
            val id = extractHwtid(href)
            if (id.isNotEmpty()) {
                title = a.text().trim()
                hwtid = id
                detailHref = href
                break
            }
        }
        if (hwtid.isEmpty()) return null

        return finalizeHomework(
            hwtid = hwtid,
            lid = lid,
            courseName = courseName,
            title = title,
            deadline = cells[1].text().trim(),
            score = cells[2].text().trim(),
            publisher = cells[3].text().trim(),
            isGroup = row.select("img[title=分组作业]").isNotEmpty(),
            detailHref = detailHref,
            submitHref = firstActiveLink(row.select("a.enter[href]"), sourceUrl),
            resultHref = firstActiveLink(row.select("a.view[href]"), sourceUrl),
            nowMillis = nowMillis
        )
    }

    private fun finalizeHomework(
        hwtid: String,
        lid: String,
        courseName: String,
        title: String,
        deadline: String,
        score: String,
        publisher: String,
        isGroup: Boolean,
        detailHref: String,
        submitHref: String,
        resultHref: String,
        nowMillis: Long
    ): TheolHomeworkItem {
        val hasResult = resultHref.isNotEmpty()
        val trimmedScore = score.trim()
        val completed = hasResult || (
            trimmedScore.isNotEmpty() &&
                trimmedScore != "暂无分数" &&
                trimmedScore != "未评分" &&
                trimmedScore != "暂无成绩"
            )
        val deadlineMillis = parseDeadlineMillis(deadline)
        val open = when {
            deadline.trim() in setOf("", "不限", "无限制", "无截止时间") -> true
            deadlineMillis == null -> false
            else -> deadlineMillis > nowMillis
        }
        val canSubmit = submitHref.isNotEmpty() && !completed && open
        val status = when {
            completed -> "已完成"
            deadline.trim() !in setOf("", "不限", "无限制", "无截止时间") && deadlineMillis == null -> "截止时间未知"
            !open -> "已截止"
            canSubmit -> "未提交"
            else -> "不可提交"
        }
        return TheolHomeworkItem(
            hwtid = hwtid,
            lid = lid,
            courseName = courseName,
            title = title.ifEmpty { "未命名作业 #$hwtid" },
            deadline = deadline,
            deadlineMillis = deadlineMillis,
            score = trimmedScore,
            publisher = publisher,
            canSubmit = canSubmit,
            hasResult = hasResult,
            isGroup = isGroup,
            status = status,
            detailHref = detailHref,
            submitHref = submitHref,
            resultHref = resultHref
        )
    }

    /** 判断页面是否因登录超时、权限拒绝或跳转统一认证页导致无法访问内容 */
    fun isPermissionDeniedOrExpired(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("permission denied") ||
            html.contains("登录时间超时") ||
            html.contains("请重新登录") ||
            html.contains("用户身份错误") ||
            html.contains("您尚未登录") ||
            html.contains("只有注册用户才可以提交错误报告") ||
            lower.contains("type=\"password\"") ||
            html.contains("login-page-flowkey")
    }

    /** 解析单个作业详情页（任务说明、截止时间、评分、发布人与附件链接） */
    fun parseHomeworkDetail(
        html: String,
        detailUrl: String,
        hwtid: String,
        nowMillis: Long
    ): TheolHomeworkDetail {
        val doc = Ksoup.parse(html, detailUrl)
        var title = ""
        var deadline = ""
        var score = ""
        var publisher = ""

        for (tr in doc.select("table.infotable tr, table.valuelist tr")) {
            val ths = tr.select("> th")
            val tds = tr.select("> td")
            if (ths.size != 1 || tds.size != 1) continue
            val label = ths.text().trim()
            val value = tds.text().trim()
            when {
                label.contains("标题") && title.isEmpty() -> title = value
                label.contains("截止") && deadline.isEmpty() -> deadline = value
                (label.contains("评分") || label.contains("分数")) && score.isEmpty() -> score = value
                label.contains("发布人") && publisher.isEmpty() -> publisher = value
            }
        }
        if (title.isEmpty()) {
            title = doc.selectFirst("h1, h2, h3, .title")?.text()?.trim().orEmpty()
        }

        val paragraphs = mutableListOf<String>()
        val attachments = linkedMapOf<String, TheolHomeworkAttachment>()

        fun normalizeDetailHref(rawHref: String): String = resolveHttpUrl(rawHref, detailUrl)

        fun collectFromFragment(container: Element) {
            val blocks = container.select("p, li, tr")
            if (blocks.isEmpty()) {
                val text = container.text().trim()
                if (text.isNotEmpty() && paragraphs.lastOrNull() != text) {
                    paragraphs.add(text)
                }
            } else {
                for (block in blocks) {
                    // 避免外层 tr/p 与内层重复收集
                    if (block.tagName() == "tr" && block.select("p, li").isNotEmpty()) continue
                    val text = block.text().trim()
                    if (text.isNotEmpty() && paragraphs.lastOrNull() != text) {
                        paragraphs.add(text)
                    }
                }
            }
            for (a in container.select("a[href]")) {
                val resolved = normalizeDetailHref(a.attr("href"))
                if (resolved.isEmpty()) continue
                val label = a.text().trim().ifEmpty {
                    resolved.substringAfterLast('/').substringBefore('?').ifEmpty { "附件" }
                }
                attachments.putIfAbsent(resolved, TheolHomeworkAttachment(name = label, url = resolved))
            }
        }

        for (hidden in doc.select("input[type=hidden]")) {
            val name = hidden.attr("name")
            if (!name.contains("content", ignoreCase = true)) continue
            val raw = hidden.attr("value").trim()
            if (raw.isEmpty()) continue
            val decoded = if (raw.contains("&lt;") || raw.contains("&gt;") || raw.contains("&amp;")) {
                htmlUnescape(raw)
            } else {
                raw
            }
            val fragment = Ksoup.parseBodyFragment(decoded, detailUrl)
            collectFromFragment(fragment.body())
        }

        if (paragraphs.isEmpty()) {
            val fallbackBlocks = doc.select("div.content, div.description, div#body, td.text, table.infotable td.text")
            for (block in fallbackBlocks) {
                collectFromFragment(block)
            }
        }

        for (a in doc.select("a[href]")) {
            val resolved = normalizeDetailHref(a.attr("href"))
            if (resolved.isEmpty()) continue
            val lower = resolved.lowercase()
            val isAttachment = lower.contains("download") ||
                lower.contains("attach") ||
                lower.contains("common/script/") ||
                lower.contains("upload") ||
                lower.contains("file=") ||
                lower.contains("preview")
            if (isAttachment) {
                val label = a.text().trim().ifEmpty {
                    resolved.substringAfterLast('/').substringBefore('?').ifEmpty { "附件" }
                }
                attachments.putIfAbsent(resolved, TheolHomeworkAttachment(name = label, url = resolved))
            }
        }

        return TheolHomeworkDetail(
            hwtid = hwtid,
            title = title,
            deadline = deadline,
            score = score,
            publisher = publisher,
            contentText = paragraphs.joinToString("\n\n").trim(),
            attachments = attachments.values.toList(),
            fetchedAt = nowMillis
        )
    }

    /** 解析在线测验列表页（入口统一指向 list.jsp，避免 test_start.jsp 404 白屏） */
    fun parseTestList(html: String, lid: String, fallbackCourseName: String, baseUrl: String = BASE_URL): List<TheolTestItem> {
        val doc: Document = Ksoup.parse(html, baseUrl)
        val courseName = fallbackCourseName.trim().ifEmpty {
            val titleText = doc.title().trim()
            val stripped = titleText
                .replace("在线测试", "")
                .replace("测试", "")
                .trim()
            if (stripped.isEmpty() || stripped == "在线") "" else stripped
        }

        val table = doc.selectFirst("table.valuelist")
            ?: doc.selectFirst("table[border=0][cellspacing=0][cellpadding=0]")
            ?: return emptyList()

        val listSourceUrl = "$baseUrl/meol/common/question/test/student/list.jsp"
        val resolvedTestListUrl = buildTestListUrl(lid, baseUrl)
        val result = mutableListOf<TheolTestItem>()
        val rows = table.select("tr")
        for (i in 1 until rows.size) {
            val cells = rows[i].select("td")
            if (cells.size < 8) continue

            val title = cells[0].text().trim()
            val startTime = cells[1].text().trim()
            val endTime = cells[2].text().trim()
            val allowedAttempts = cells[3].text().trim()
            val duration = cells[4].text().trim()

            val onclick = cells[5].selectFirst("a")?.attr("onclick").orEmpty()
            val onclickTestId = testStartRegex.find(onclick)?.groupValues?.getOrNull(1).orEmpty()
            val canStart = onclickTestId.isNotEmpty()
            val resultHref = cells[7].selectFirst("a")?.let { resolveActiveLink(it, listSourceUrl) }.orEmpty()
            val hasResult = resultHref.isNotEmpty()
            val resolvedTestId = onclickTestId.ifEmpty { extractPositiveIdParam(resultHref, "testId") }
            val status = when {
                hasResult -> "已完成"
                canStart -> "可进行"
                else -> "未开始"
            }

            result.add(
                TheolTestItem(
                    testId = resolvedTestId.ifEmpty { "${lid}_$i" },
                    lid = lid,
                    courseName = courseName,
                    title = title,
                    startTime = startTime,
                    endTime = endTime,
                    endTimeMillis = parseDeadlineMillis(endTime),
                    duration = duration,
                    allowedAttempts = allowedAttempts,
                    canStart = canStart,
                    hasResult = hasResult,
                    status = status,
                    testUrl = resolvedTestListUrl,
                    resultHref = resultHref
                )
            )
        }
        return result
    }

    /** 解析单个在线测验详情页（view.jsp?testId=...） */
    fun parseTestDetail(
        html: String,
        testId: String,
        nowMillis: Long
    ): TheolTestDetail {
        val doc = Ksoup.parse(html, BASE_URL)
        val title = doc.selectFirst("h1, h2, h3, .title")?.text()?.trim().orEmpty()
        val description = doc.selectFirst("div.content, div.description")?.text()?.trim().orEmpty()
            .ifEmpty { doc.selectFirst("div#body")?.text()?.trim().orEmpty() }

        var startTime = ""
        var endTime = ""
        var duration = ""
        var totalScore = ""
        var questionCount = ""

        for (tr in doc.select("table.info tr, table.infotable tr, table.valuelist tr")) {
            val cells = tr.select("> th, > td")
            if (cells.size < 2) continue
            val key = cells[0].text().trim()
            val value = cells[1].text().trim()
            when {
                key.contains("开始时间") && startTime.isEmpty() -> startTime = value
                key.contains("结束时间") && endTime.isEmpty() -> endTime = value
                (key.contains("持续时间") || key.contains("考试时长")) && duration.isEmpty() -> duration = value
                key.contains("总分") && totalScore.isEmpty() -> totalScore = value
                (key.contains("题目数") || key.contains("问题数")) && questionCount.isEmpty() -> questionCount = value
            }
        }

        return TheolTestDetail(
            testId = testId,
            title = title,
            description = description,
            startTime = startTime,
            endTime = endTime,
            duration = duration,
            totalScore = totalScore,
            questionCount = questionCount,
            fetchedAt = nowMillis
        )
    }

    fun parseDeadlineMillis(raw: String): Long? = parseDateTimeLoose(raw)

    private fun firstActiveLink(links: List<Element>, sourceUrl: String): String {
        for (a in links) {
            val resolved = resolveActiveLink(a, sourceUrl)
            if (resolved.isNotEmpty()) return resolved
        }
        return ""
    }

    private fun resolveActiveLink(a: Element, sourceUrl: String): String {
        if (a.hasAttr("disabled")) return ""
        if (a.attr("aria-disabled").equals("true", ignoreCase = true)) return ""
        if (a.attr("class").lowercase().contains("disabled")) return ""
        return resolveHttpUrl(a.attr("href"), sourceUrl)
    }

    private fun resolveHttpUrl(rawHref: String, sourceUrl: String): String {
        val href = rawHref.trim()
        if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:", true)) return ""
        return runCatching {
            val resolved = resolveUrl(sourceUrl, href)
            val scheme = urlSchemeOf(resolved)
            if (scheme == "http" || scheme == "https") upgradeCourseScheme(resolved) else ""
        }.getOrDefault("")
    }

    private fun upgradeCourseScheme(url: String): String =
        if (url.startsWith("http://course.buct.edu.cn", ignoreCase = true)) {
            "https://" + url.removePrefix("http://")
        } else {
            url
        }

    private val RESOURCE_QUOTED_URL_REGEX = Regex("""['"]([^'"]+)['"]""")
    private val RESOURCE_LOCATION_REGEX = Regex(
        """(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]|(?:window\.)?location\.replace\s*\(\s*['"]([^'"]+)['"]"""
    )
    private val RESOURCE_REFRESH_REGEX = Regex("""(?i)url\s*=\s*(.+)""")

    private val MENU_URL_PARENT_REGEX = Regex("""menuUrl\s*\(\s*['"]1['"]\s*,\s*['"](\d+)['"]""")

    /** 从课程首页（newpage/index.jsp）预渲染的 #menu.wrap-menu 中解析「单元学习」目录列表 */
    fun parseCourseUnits(courseHomeHtml: String, courseHomeUrl: String): List<TheolCourseUnit> {
        val doc = Ksoup.parse(courseHomeHtml, courseHomeUrl)
        val unitLinks = doc.select("#menu ul#ul_advance li a[href]").ifEmpty {
            val parentId = doc.select("a[onclick]").firstNotNullOfOrNull { a ->
                val text = a.text().trim()
                if (!text.contains("单元学习")) return@firstNotNullOfOrNull null
                MENU_URL_PARENT_REGEX.find(a.attr("onclick"))?.groupValues?.getOrNull(1)
            }
            if (!parentId.isNullOrBlank()) {
                doc.select("#menu ul.ul_$parentId li a[href]")
            } else {
                com.fleeksoft.ksoup.select.Elements()
            }
        }

        val seenIds = mutableSetOf<String>()
        val result = mutableListOf<TheolCourseUnit>()
        for (a in unitLinks) {
            val resolved = resolveHttpUrl(a.attr("href"), courseHomeUrl)
            if (resolved.isEmpty()) continue
            val columnId = extractPositiveIdParam(resolved, "columnId")
            val title = a.text().trim()
            if (columnId.isEmpty() || title.isEmpty()) continue
            if (seenIds.add(columnId)) {
                result.add(
                    TheolCourseUnit(
                        columnId = columnId,
                        title = title,
                        url = resolved
                    )
                )
            }
        }
        return result
    }

    /** 从课程首页 / 菜单 frame 中发现教学资源与课件栏目入口链接 */
    fun discoverResourceEntryUrls(doc: Document, baseUrl: String = BASE_URL): List<String> {
        val urls = linkedSetOf<String>()
        val textKeywords = listOf("教学资源", "课程资源", "教学课件", "课件", "课程资料", "教学资料", "教学材料", "参考资料")
        val urlKeywords = listOf("resourcelist", "courseware", "listview.jsp", "lessondoc", "resource")

        for (a in doc.select("a[href], a[onclick]")) {
            val text = a.text().trim()
            val textMatched = textKeywords.any { text.contains(it) }
            val resolvedHref = resolveHttpUrl(a.attr("href"), baseUrl)
            if (resolvedHref.isNotEmpty()) {
                val lower = resolvedHref.lowercase()
                if (textMatched || urlKeywords.any { lower.contains(it) }) {
                    urls.add(resolvedHref)
                }
            }
            val onclick = a.attr("onclick")
            if (onclick.isNotBlank()) {
                for (m in RESOURCE_QUOTED_URL_REGEX.findAll(onclick)) {
                    val candidate = m.groupValues[1]
                    val candidateLower = candidate.lowercase()
                    if (candidateLower.contains("course_column_preview_transfer.jsp") ||
                        candidateLower.contains("colurlstuview.do") ||
                        candidateLower.contains("listview.jsp") ||
                        candidateLower.contains("resourcelist") ||
                        textMatched
                    ) {
                        val resolvedOnclick = resolveHttpUrl(candidate, baseUrl)
                        if (resolvedOnclick.isNotEmpty()) {
                            urls.add(resolvedOnclick)
                        }
                    }
                }
            }
        }
        return urls.toList()
    }

    /** 提取 MEOL 栏目中间跳转页（transfer / iframe / JS location）内的真实目标地址（限定 course.buct.edu.cn） */
    fun extractResourceRedirectUrl(doc: Document, baseUrl: String): String? {
        fun acceptCourseUrl(raw: String): String? {
            val resolved = resolveHttpUrl(raw, baseUrl)
            if (resolved.isEmpty() || resolved == baseUrl) return null
            val host = urlHostOf(resolved)?.lowercase().orEmpty()
            return if (host == "course.buct.edu.cn") resolved else null
        }

        val frames = doc.select("iframe[src], frame[src]")
        var fallbackFrameUrl: String? = null
        for (frame in frames) {
            val resolved = acceptCourseUrl(frame.attr("src")) ?: continue
            val lower = resolved.lowercase()
            val nameOrId = (frame.attr("name") + " " + frame.attr("id")).lowercase()
            if (lower.contains("left.jsp") || nameOrId.contains("leftframe")) continue
            if (lower.contains("listview.jsp") || lower.contains("resourcelist") || nameOrId.contains("mainframe")) {
                return resolved
            }
            if (fallbackFrameUrl == null) fallbackFrameUrl = resolved
        }
        if (fallbackFrameUrl != null) return fallbackFrameUrl

        for (meta in doc.select("meta[http-equiv]")) {
            if (meta.attr("http-equiv").equals("refresh", ignoreCase = true)) {
                val target = RESOURCE_REFRESH_REGEX.find(meta.attr("content"))
                    ?.groupValues?.getOrNull(1)?.trim(' ', '"', '\'').orEmpty()
                if (target.isNotEmpty()) {
                    acceptCourseUrl(target)?.let { return it }
                }
            }
        }
        for (script in doc.select("script:not([src])")) {
            for (m in RESOURCE_LOCATION_REGEX.findAll(script.data())) {
                val target = m.groupValues[1].ifEmpty { m.groupValues[2] }
                if (target.isNotEmpty()) {
                    acceptCourseUrl(target)?.let { return it }
                }
            }
        }
        return null
    }

    /** 从 download_preview.jsp 等预览页 HTML 中提取真实文件下载直链（限定 *.buct.edu.cn） */
    fun extractDirectDownloadUrl(html: String, pageUrl: String): String? {
        val doc = Ksoup.parse(html, pageUrl)
        fun isDirectDownloadCandidate(raw: String): String? {
            val resolved = resolveHttpUrl(raw, pageUrl)
            if (resolved.isEmpty() || resolved == pageUrl) return null
            val host = urlHostOf(resolved)?.lowercase().orEmpty()
            val isTrusted = host == "buct.edu.cn" || host.endsWith(".buct.edu.cn")
            if (!isTrusted) return null
            val lower = resolved.lowercase()
            if (lower.contains("download_preview.jsp")) return null
            val looksDirect = lower.contains("download.jsp") ||
                lower.contains("download_file") ||
                lower.contains("file_download") ||
                lower.contains("attach")
            return if (looksDirect) resolved else null
        }

        doc.selectFirst("a.lv-download[href]")?.let { a ->
            isDirectDownloadCandidate(a.attr("href"))?.let { return it }
        }
        for (el in doc.select("a[href], iframe[src], frame[src]")) {
            val attr = if (el.hasAttr("href")) el.attr("href") else el.attr("src")
            isDirectDownloadCandidate(attr)?.let { return it }
        }
        for (script in doc.select("script:not([src])")) {
            for (m in RESOURCE_LOCATION_REGEX.findAll(script.data())) {
                val target = m.groupValues[1].ifEmpty { m.groupValues[2] }
                if (target.isNotEmpty()) {
                    isDirectDownloadCandidate(target)?.let { return it }
                }
            }
        }
        return null
    }

    /** 解析北化在线课程资源目录页（文件夹与可下载课件列表） */
    fun parseResourceList(
        html: String,
        pageUrl: String,
        lid: String,
        courseName: String,
        fallbackFolderTitle: String? = null
    ): TheolResourcePage {
        val doc = Ksoup.parse(html, pageUrl)
        val items = linkedMapOf<String, TheolResourceItem>()
        val knownExts = setOf(
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx",
            "zip", "rar", "7z", "txt", "md", "csv", "mp4", "mp3", "png", "jpg", "jpeg"
        )
        val ignoredTexts = setOf(
            "返回", "上一级", "返回上一级目录", "上一页", "下一页", "首页", "尾页",
            "下载", "预览", "排序", "刷新", "全部", "查询"
        )

        val valueListTable = doc.selectFirst("table.valuelist")
        val rows = if (valueListTable != null) {
            valueListTable.select("tr")
        } else {
            doc.select("table.infotable tr, table tr")
        }

        for (row in rows) {
            val links = row.select("a[href]")
            if (links.isEmpty()) continue
            val directDownloadUrl = links.firstNotNullOfOrNull { a ->
                val resolved = resolveHttpUrl(a.attr("href"), pageUrl)
                val lower = resolved.lowercase()
                if (resolved.isNotEmpty() &&
                    !lower.contains("download_preview.jsp") &&
                    (lower.contains("download.jsp") || lower.contains("download_file") || lower.contains("file_download"))
                ) resolved else null
            }
            val primaryLink = links.firstOrNull { a ->
                val text = a.text().trim()
                text.isNotEmpty() && text !in ignoredTexts && !text.startsWith("返回上一级")
            } ?: links[0]
            val item = parseResourceElement(
                link = primaryLink,
                row = row,
                pageUrl = pageUrl,
                knownExts = knownExts,
                ignoredTexts = ignoredTexts,
                preferredFileUrl = directDownloadUrl
            ) ?: continue
            items.putIfAbsent(item.url, item)
        }

        if (valueListTable == null && items.isEmpty()) {
            val previewRoot = doc.selectFirst("#dowload-preview")
            val downloadAnchor = previewRoot?.selectFirst("a.lv-download[href]")
            if (previewRoot != null && downloadAnchor != null) {
                val rawDownloadUrl = resolveHttpUrl(downloadAnchor.attr("href"), pageUrl)
                if (rawDownloadUrl.isNotEmpty()) {
                    val downloadUrl = if (lid.isNotBlank() && !rawDownloadUrl.contains("lid=", ignoreCase = true)) {
                        val sep = if ('?' in rawDownloadUrl) '&' else '?'
                        "$rawDownloadUrl${sep}lid=${urlEncode(lid)}"
                    } else {
                        rawDownloadUrl
                    }
                    val h2Text = previewRoot.selectFirst("h2")?.text().orEmpty()
                    val sizeMatch = Regex("""\(([^)]+)\)""").find(h2Text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                    val parsedFileName = h2Text
                        .substringAfter("文件名:", "")
                        .substringBefore("(")
                        .replace("下载", "")
                        .trim()
                        .ifEmpty {
                            previewRoot.selectFirst("h1")?.text()?.trim().orEmpty()
                        }
                        .ifEmpty {
                            fallbackFolderTitle?.trim().orEmpty().ifEmpty { "课程资料" }
                        }
                    val ext = if ('.' in parsedFileName) {
                        parsedFileName.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..6 }.orEmpty()
                    } else {
                        ""
                    }
                    items[downloadUrl] = TheolResourceItem(
                        id = downloadUrl,
                        name = parsedFileName,
                        isFolder = false,
                        url = downloadUrl,
                        sizeText = sizeMatch,
                        updatedAt = "",
                        fileExt = ext,
                        previewUrl = pageUrl
                    )
                }
            } else {
                for (link in doc.select("a[href]")) {
                    val item = parseResourceElement(link, null, pageUrl, knownExts, ignoredTexts, null) ?: continue
                    items.putIfAbsent(item.url, item)
                }
            }
        }

        val cleanFallbackTitle = fallbackFolderTitle?.trim()?.takeIf { it.isNotBlank() }
        val domTitle = doc.selectFirst(".subtitle > span:first-child, h2, h3, .title, .pos, .position")
            ?.text()?.trim()?.takeIf { it.isNotBlank() && it.length <= 40 }
        val folderTitle = cleanFallbackTitle ?: domTitle ?: courseName.ifBlank { "课程资料" }

        val sortedItems = items.values.toList().sortedBy { !it.isFolder }

        return TheolResourcePage(
            lid = lid,
            courseName = courseName,
            currentUrl = pageUrl,
            folderTitle = folderTitle,
            items = sortedItems
        )
    }

    private fun parseResourceElement(
        link: Element,
        row: Element?,
        pageUrl: String,
        knownExts: Set<String>,
        ignoredTexts: Set<String>,
        preferredFileUrl: String?
    ): TheolResourceItem? {
        val resolved = resolveHttpUrl(link.attr("href"), pageUrl)
        if (resolved.isEmpty() || resolved == pageUrl) return null
        val name = link.text().trim()
        if (name.isEmpty() || name in ignoredTexts || name.startsWith("返回上一级")) return null

        val lower = resolved.lowercase()
        val isFile = lower.contains("download_preview.jsp") ||
            lower.contains("download.jsp") ||
            lower.contains("download_file") ||
            lower.contains("file_download") ||
            lower.contains("fileid=") ||
            lower.contains("resid=") ||
            knownExts.any { name.lowercase().endsWith(".$it") }
        val isFolder = !isFile && (
            lower.contains("folderid=") ||
                lower.contains("dirid=") ||
                lower.contains("parentid=") ||
                lower.contains("listview.jsp") ||
                lower.contains("resourcelist") ||
                lower.contains("lessondoc") ||
                row?.selectFirst("img[src*=folder], img[src*=dir], .folder") != null
            )
        if (!isFile && !isFolder) return null

        val finalUrl = if (isFile) {
            when {
                !preferredFileUrl.isNullOrBlank() -> preferredFileUrl
                else -> rewritePreviewToDirectDownload(resolved)
            }
        } else {
            resolved
        }

        val cells = row?.select("td").orEmpty().map { it.text().trim() }
        val rawSize = cells.firstOrNull {
            it.matches(Regex("""\d+(\.\d+)?\s*(B|KB|MB|GB|字节)""", RegexOption.IGNORE_CASE))
        }.orEmpty()
        val typeLabel = if (rawSize.isEmpty() && cells.size >= 4) {
            cells[3].takeIf { it.isNotBlank() && it.length <= 8 && !it.all(Char::isDigit) }.orEmpty()
        } else {
            ""
        }
        val sizeText = rawSize.ifEmpty { typeLabel }
        val updatedAt = cells.firstOrNull {
            it.matches(Regex("""\d{4}[-/]\d{1,2}[-/]\d{1,2}.*"""))
        }.orEmpty()
        val nameExt = if (!isFolder && '.' in name) {
            name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..6 }.orEmpty()
        } else {
            ""
        }
        val ext = if (!isFolder) {
            nameExt.ifEmpty { inferExtFromIcon(row) }
        } else {
            ""
        }

        return TheolResourceItem(
            id = finalUrl,
            name = name,
            isFolder = isFolder,
            url = finalUrl,
            sizeText = sizeText,
            updatedAt = updatedAt,
            fileExt = ext,
            previewUrl = if (isFile) resolved else ""
        )
    }

    private fun rewritePreviewToDirectDownload(url: String): String {
        val path = urlPathOf(url)
        if (!path.endsWith("/preview/download_preview.jsp", ignoreCase = true)) return url
        val query = urlQueryOf(url).orEmpty()
        if (!query.contains("fileid=", ignoreCase = true) && !query.contains("resid=", ignoreCase = true)) {
            return url
        }
        val newPath = path.substringBeforeLast("/preview/download_preview.jsp") + "/download.jsp"
        return runCatching { replaceUrlPath(url, newPath) }.getOrDefault(url)
    }

    private fun inferExtFromIcon(row: Element?): String {
        val src = row?.selectFirst("img[src*=/icons/], img[src*=icon]")
            ?.attr("src")?.lowercase().orEmpty()
        if (src.isEmpty()) return ""
        return when {
            src.contains("pdf") -> "pdf"
            src.contains("powerpoint") || src.contains("ppt") -> "ppt"
            src.contains("word") || src.contains("doc") -> "doc"
            src.contains("excel") || src.contains("xls") -> "xls"
            src.contains("video") || src.contains("mp4") -> "mp4"
            src.contains("audio") || src.contains("mp3") -> "mp3"
            src.contains("zip") || src.contains("rar") -> "zip"
            else -> ""
        }
    }

    private fun extractHwtid(href: String): String = extractPositiveIdParam(href, "hwtid")

    private fun extractPositiveIdParam(href: String, paramName: String): String {
        if (href.isEmpty()) return ""
        val query = urlQueryOf(href).orEmpty()
        val id = query.split('&')
            .firstOrNull { it.startsWith("$paramName=") }
            ?.substringAfter("$paramName=")
            ?.trim()
            .orEmpty()
        if (id.isEmpty() || !id.all(Char::isDigit) || id.all { it == '0' }) return ""
        return id
    }
}
