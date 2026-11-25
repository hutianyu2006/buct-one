package com.buct.one.data.source.scs

import com.buct.one.data.model.ScsActivityItem
import com.buct.one.data.model.ScsCategoryDetail
import com.buct.one.data.model.ScsCurrentEvalWindow
import com.buct.one.data.model.ScsEvalDimension
import com.buct.one.data.model.ScsEvalItem
import com.buct.one.data.model.ScsTermScore
import com.buct.one.data.json.JSONArray
import com.buct.one.data.json.JSONObject
import com.fleeksoft.ksoup.Ksoup

/**
 * 第二课堂（scs.buct.edu.cn / eapi.buct.edu.cn）纯函数 JSON/HTML 解析器。
 * 改动解析逻辑时必须将 PARSER_VERSION + 1。
 */
object ScsParser {
    const val PARSER_VERSION = 2

    data class SidPortalFormParams(
        val execution: String,
        val croypto: String,
        val errorCode: String,
        val second: String,
        val captchaUrl: String
    )

    data class UserBasicInfo(
        val name: String,
        val studentId: String
    )

    data class StudentClassInfo(
        val collegeName: String,
        val className: String,
        val gradeName: String
    )

    fun parseSidPortalPage(html: String): SidPortalFormParams {
        val doc = Ksoup.parse(html)
        fun text(id: String) = doc.selectFirst("#$id")?.text()?.trim().orEmpty()
        return SidPortalFormParams(
            execution = text("login-page-flowkey"),
            croypto = text("login-croypto"),
            errorCode = text("login-error-msg").ifEmpty { text("login-error-code") },
            second = text("sso-second"),
            captchaUrl = text("captcha-url")
        )
    }

    fun isUnauthorized(code: Int, message: String): Boolean {
        val lower = message.lowercase()
        return code == 401 || code == 403 || lower.contains("token") || message.contains("未登录") || message.contains("过期")
    }

    fun parseStringDataResponse(json: String): String {
        val obj = JSONObject(json)
        val code = obj.optInt("code", -1)
        if (code != 200) return ""
        return obj.optString("data", "").takeIf { it != "null" }.orEmpty()
    }

    fun parseUserInfo(json: String): UserBasicInfo {
        val obj = JSONObject(json)
        if (obj.optInt("code", -1) != 200) {
            throw IllegalStateException(obj.optString("message", "获取用户信息失败"))
        }
        val data = obj.optJSONObject("data") ?: return UserBasicInfo("", "")
        return UserBasicInfo(
            name = data.optString("name", "").takeIf { it != "null" }.orEmpty(),
            studentId = data.optString("gh", "").takeIf { it != "null" }.orEmpty()
        )
    }

    fun parseCurrentEvalWindow(json: String): ScsCurrentEvalWindow? {
        return runCatching {
            val obj = JSONObject(json)
            if (obj.optInt("code", -1) != 200) return null
            val data = obj.optJSONObject("data") ?: return null
            ScsCurrentEvalWindow(
                yearId = data.optInt("yearId", 0),
                termId = data.optInt("termId", 0),
                yearName = optCleanString(data, "yearName"),
                termName = optCleanString(data, "termName"),
                isOpen = data.optBoolean("open", false),
                startTime = optCleanString(data, "selfStartTime"),
                endTime = optCleanString(data, "selfEndTime")
            )
        }.getOrNull()
    }

    fun parseEvaluationDimensions(json: String): List<ScsEvalDimension> {
        return runCatching {
            val obj = JSONObject(json)
            if (obj.optInt("code", -1) != 200) return emptyList()
            val arr = obj.optJSONArray("data") ?: return emptyList()
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optBoolean("deleted", false)) continue
                    add(
                        ScsEvalDimension(
                            typeId = o.optInt("id", i + 1),
                            name = optCleanString(o, "description"),
                            totalMax = o.optDouble("totalScore", 0.0),
                            basicMax = o.optDouble("basicScore", 0.0),
                            extMax = o.optDouble("extScore", 0.0)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun parseStudentClassInfo(json: String): StudentClassInfo {
        return runCatching {
            val obj = JSONObject(json)
            if (obj.optInt("code", -1) != 200) return StudentClassInfo("", "", "")
            val arr = obj.optJSONArray("data") ?: return StudentClassInfo("", "", "")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val college = optCleanString(o, "courtName")
                val clazz = optCleanString(o, "className")
                val grade = optCleanString(o, "gradeName")
                if (college.isNotBlank() || clazz.isNotBlank()) {
                    return StudentClassInfo(college, clazz, grade)
                }
            }
            StudentClassInfo("", "", "")
        }.getOrDefault(StudentClassInfo("", "", ""))
    }

    fun parseAllTermsScores(json: String): List<ScsTermScore> {
        val obj = JSONObject(json)
        if (obj.optInt("code", -1) != 200) {
            throw IllegalStateException(obj.optString("message", "获取第二课堂成绩失败"))
        }
        val arr = obj.optJSONArray("data") ?: return emptyList()
        val result = ArrayList<ScsTermScore>()
        for (i in 0 until arr.length()) {
            val yearObj = arr.optJSONObject(i) ?: continue
            val yearName = optCleanString(yearObj, "mc")
            val yearId = yearObj.optInt("year", 0)
            val termsArr = yearObj.optJSONArray("terms") ?: continue
            for (j in 0 until termsArr.length()) {
                val t = termsArr.optJSONObject(j) ?: continue
                val termId = t.optInt("id", 0)
                val termName = optCleanString(t, "mc")
                val studentScoreObj = t.optJSONObject("studentScore")
                val detailsArr = t.optJSONArray("details")
                result.add(
                    ScsTermScore(
                        termId = termId,
                        yearId = t.optInt("yearId", yearId),
                        yearName = yearName,
                        termName = termName,
                        isFullYear = termId == 0 || termName == "全年",
                        status = t.optInt("status", 0),
                        totalScore = optNullableDouble(t, "totalScore"),
                        moral = studentScoreObj?.let { optNullableDouble(it, "moral") },
                        academic = studentScoreObj?.let { optNullableDouble(it, "academic") },
                        physical = studentScoreObj?.let { optNullableDouble(it, "physical") },
                        aesthetics = studentScoreObj?.let { optNullableDouble(it, "aesthetics") },
                        labor = studentScoreObj?.let { optNullableDouble(it, "labor") },
                        isOpenSelfEva = t.optBoolean("isOpenSelfEva", false),
                        details = parseCategoryDetails(detailsArr)
                    )
                )
            }
        }
        return result.sortedWith(
            compareByDescending<ScsTermScore> { it.yearName }
                .thenBy { if (it.isFullYear) 0 else it.termId }
        )
    }

    fun parseCurrentTermEvalDetails(json: String): List<ScsCategoryDetail> {
        return runCatching {
            val obj = JSONObject(json)
            if (obj.optInt("code", -1) != 200) return emptyList()
            val data = obj.optJSONObject("data") ?: return emptyList()
            val allItems = mutableListOf<ScsEvalItem>()
            appendEvalItems(data.optJSONArray("basic"), isBasic = true, out = allItems)
            appendEvalItems(data.optJSONArray("ext"), isBasic = false, out = allItems)
            val byType = allItems.groupBy { it.typeId }
            val names = mapOf(
                1 to "道德与思想素质评定",
                2 to "学术与科技创新评定",
                3 to "体育与身心健康评定",
                4 to "美学与人文素养评定",
                5 to "劳动与社会实践评定"
            )
            byType.entries.sortedBy { it.key }.map { (typeId, items) ->
                val scoredSum = items.mapNotNull { it.score }.sum().takeIf { items.any { it.score != null } }
                ScsCategoryDetail(
                    typeId = typeId,
                    title = names[typeId] ?: "维度 $typeId",
                    basicScore = items.filter { it.isBasic }.mapNotNull { it.score }.sum().takeIf { items.any { it.isBasic && it.score != null } },
                    extScore = items.filter { !it.isBasic }.mapNotNull { it.score }.sum().takeIf { items.any { !it.isBasic && it.score != null } },
                    totalScore = scoredSum,
                    maxTotalScore = when (typeId) { 1 -> 180.0; 2 -> 120.0; else -> 100.0 },
                    items = items
                )
            }
        }.getOrDefault(emptyList())
    }

    fun parseActivities(json: String): List<ScsActivityItem> {
        return runCatching {
            val obj = JSONObject(json)
            if (obj.optInt("code", -1) != 200) return emptyList()
            val data = obj.optJSONObject("data") ?: return emptyList()
            val records = data.optJSONArray("records") ?: return emptyList()
            buildList(records.length()) {
                for (i in 0 until records.length()) {
                    val r = records.optJSONObject(i) ?: continue
                    val title = optCleanString(r, "activityName", "title", "name")
                    if (title.isBlank()) continue
                    add(
                        ScsActivityItem(
                            id = optCleanString(r, "id").ifBlank { i.toString() },
                            title = title,
                            categoryName = optCleanString(r, "typeName", "categoryName", "plateName"),
                            startTime = optCleanString(r, "startTime", "activityStartTime"),
                            endTime = optCleanString(r, "endTime", "activityEndTime"),
                            location = optCleanString(r, "address", "location", "place"),
                            score = optCleanString(r, "score", "maxScore"),
                            status = optCleanString(r, "statusName", "status")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseCategoryDetails(detailsArr: JSONArray?): List<ScsCategoryDetail> {
        if (detailsArr == null) return emptyList()
        val list = ArrayList<ScsCategoryDetail>(detailsArr.length())
        for (i in 0 until detailsArr.length()) {
            val d = detailsArr.optJSONObject(i) ?: continue
            val items = mutableListOf<ScsEvalItem>()
            appendEvalItems(d.optJSONArray("basic"), isBasic = true, out = items)
            appendEvalItems(d.optJSONArray("ext"), isBasic = false, out = items)
            list.add(
                ScsCategoryDetail(
                    typeId = d.optInt("typeId", i + 1),
                    title = optCleanString(d, "desc"),
                    basicScore = optNullableDouble(d, "basicScore"),
                    extScore = optNullableDouble(d, "extScore"),
                    totalScore = optNullableDouble(d, "studentTypeScore")
                        ?: optNullableDouble(d, "totalScore"),
                    maxTotalScore = optNullableDouble(d, "typeTotalScore"),
                    items = items
                )
            )
        }
        return list.sortedBy { it.typeId }
    }

    private fun appendEvalItems(arr: JSONArray?, isBasic: Boolean, out: MutableList<ScsEvalItem>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("deleted", false)) continue
            out.add(
                ScsEvalItem(
                    id = o.optLong("id", i.toLong()),
                    typeId = o.optInt("typeId", 0),
                    isBasic = o.optBoolean("basic", isBasic),
                    shortDesc = optCleanString(o, "shortDesc"),
                    description = optCleanString(o, "description"),
                    score = optNullableDouble(o, "score"),
                    mark = optCleanString(o, "mark"),
                    teacherComments = optCleanString(o, "teacherComments"),
                    laborHours = optNullableDouble(o, "laborHours"),
                    stuAble = o.optBoolean("stuAble", false)
                )
            )
        }
    }

    private fun optNullableDouble(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val v = obj.optDouble(key, Double.NaN)
        return if (v.isNaN()) null else v
    }

    private fun optCleanString(obj: JSONObject, vararg keys: String): String {
        for (k in keys) {
            if (!obj.has(k) || obj.isNull(k)) continue
            val s = obj.optString(k, "").trim()
            if (s.isNotEmpty() && !s.equals("null", ignoreCase = true)) {
                return s
            }
        }
        return ""
    }
}
