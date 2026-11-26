package com.buct.one.data.source.tygl

import com.buct.one.data.model.TyglExamInfo
import com.buct.one.data.model.TyglFitnessRecord
import com.buct.one.data.model.TyglPeCourseScore
import com.buct.one.data.model.TyglProfile
import com.buct.one.data.model.TyglSnapshot
import com.buct.one.data.text.resolveUrl
import com.buct.one.data.text.urlEncode
import com.buct.one.data.text.urlHostOf
import com.buct.one.data.text.urlPathOf
import com.buct.one.data.text.urlQueryOf
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

/** 体测系统会话失效（返回登录页或跳转到认证）。 */
class TyglAuthExpiredException(message: String) : Exception(message)

/**
 * 体测与体育管理平台（tygl.buct.edu.cn）网关，双端共用。
 *
 * 会话由调用方注入的 [HttpClient] 持有（CAS 登录后已写入 Cookie），本类只负责：
 * 1. 跟随站内重定向（`http` 自动升级 `https`，与 Android 一致——phpCAS 的 `service` 参数必须保持 http）；
 * 2. 识别登录页并抛 [TyglAuthExpiredException]，让上层触发重登；
 * 3. 多学年体测成绩、阳光跑、体育课成绩、理论考试四类页面抓取与解析（解析全部复用 [TyglParser]）。
 */
class TyglGateway(
    private val httpClient: HttpClient,
    private val userAgent: String,
) {

    suspend fun fetchFullSnapshot(maxYears: Int = 6): TyglSnapshot {
        val defaultHtml = fetchAuthenticatedHtml("/main.php?module=stu&title=stu_ht_score")
        val yearTabs = TyglParser.parseFitnessYearTabs(defaultHtml)
        val (firstProfile, firstRecord) = TyglParser.parseFitnessPage(defaultHtml)

        var profile: TyglProfile = firstProfile
        val records = ArrayList<TyglFitnessRecord>()
        val parsedYearKeys = HashSet<String>()
        firstRecord?.let {
            records.add(it)
            parsedYearKeys.add(it.yearKey)
        }

        if (yearTabs.isNotEmpty()) {
            val ordered = ArrayList<TyglFitnessRecord>()
            for ((tabKey, tabLabel) in yearTabs.take(maxYears)) {
                if (tabKey in parsedYearKeys) {
                    records.firstOrNull { it.yearKey == tabKey }?.let(ordered::add)
                    continue
                }
                val yearHtml = runCatching {
                    fetchAuthenticatedHtml(
                        "/main.php?module=stu&title=stu_ht_score&year=${urlEncode(tabKey)}",
                    )
                }.getOrNull() ?: continue
                val (yearProfile, yearRecord) = TyglParser.parseFitnessPage(
                    html = yearHtml,
                    fallbackYearKey = tabKey,
                    fallbackYearLabel = tabLabel,
                )
                if (profile.studentName.isBlank() && yearProfile.studentName.isNotBlank()) {
                    profile = yearProfile
                }
                yearRecord?.let {
                    ordered.add(it)
                    parsedYearKeys.add(it.yearKey)
                }
            }
            for (record in records) {
                if (ordered.none { it.yearKey == record.yearKey }) ordered.add(record)
            }
            records.clear()
            records.addAll(ordered)
        }

        val sunTriple = runCatching {
            TyglParser.parseSunScorePage(fetchAuthenticatedHtml("/main.php?module=stu&title=stu_sun_score"))
        }.getOrNull()

        val peCourses: List<TyglPeCourseScore> = runCatching {
            TyglParser.parsePeScorePage(fetchAuthenticatedHtml("/main.php?module=stu&title=stu_pe_score"))
        }.getOrDefault(emptyList())

        val examInfo: TyglExamInfo? = runCatching {
            TyglParser.parseExamPage(fetchAuthenticatedHtml("/main.php?module=stu&title=stu_exam"))
        }.getOrNull()

        return TyglSnapshot(
            profile = profile,
            fitnessRecords = records,
            runPlan = sunTriple?.first,
            exerciseColumns = sunTriple?.second.orEmpty(),
            exerciseTerms = sunTriple?.third.orEmpty(),
            peCourses = peCourses,
            examInfo = examInfo,
        )
    }

    /** 抓取并校验页面；站内重定向自动跟随，跳到登录页则抛会话失效。 */
    suspend fun fetchAuthenticatedHtml(pathAndQuery: String): String {
        var current = TYGL_BASE + pathAndQuery
        repeat(MAX_REDIRECT_HOPS) {
            val safeUrl = upgradeToHttps(current)
            if (!isTyglHost(safeUrl)) throw TyglAuthExpiredException("体测系统跳转到了非预期域名")
            val response = httpClient.get(safeUrl) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                header(HttpHeaders.Referrer, "$TYGL_BASE/main.php")
            }
            val status = response.status.value
            if (status in 300..399) {
                val location = response.headers[HttpHeaders.Location]
                    ?: throw TyglAuthExpiredException("体测系统重定向缺少地址")
                val next = resolveUrl(safeUrl, location)
                val nextPath = urlPathOf(next)
                val nextQuery = urlQueryOf(next).orEmpty()
                if (isTyglHost(next) && !nextPath.contains("login.php") && !nextQuery.contains("accessDenied")) {
                    current = upgradeToHttps(next)
                    return@repeat
                }
                throw TyglAuthExpiredException("体测系统统一认证会话已失效，请重新登录")
            }
            if (status !in 200..299) {
                throw IllegalStateException("体测系统请求失败（HTTP $status）")
            }
            val body = response.bodyAsText()
            if (!TyglParser.isAuthenticatedPage(body)) {
                throw TyglAuthExpiredException("体测系统统一认证会话已失效，请重新登录")
            }
            return body
        }
        throw IllegalStateException("体测系统页面重定向次数过多")
    }

    private fun isTyglHost(url: String): Boolean =
        urlHostOf(url)?.equals("tygl.buct.edu.cn", ignoreCase = true) == true

    /** phpCAS 的 service 参数必须保持 http，但实际请求升级为 https（与 Android 一致）。 */
    private fun upgradeToHttps(url: String): String =
        if (url.startsWith("http://tygl.buct.edu.cn")) url.replaceFirst("http://", "https://") else url

    companion object {
        const val TYGL_BASE = "https://tygl.buct.edu.cn"
        private const val MAX_REDIRECT_HOPS = 4
    }
}
