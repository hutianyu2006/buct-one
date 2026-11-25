package com.buct.one.data.source.card

import com.buct.one.data.json.JSONArray
import com.buct.one.data.json.JSONObject
import com.buct.one.data.model.CardAccountInfo
import com.buct.one.data.model.CardTransaction
import com.buct.one.data.model.maskCardId
import com.buct.one.data.text.formatDateTime
import com.buct.one.data.text.parseDateTime
import com.buct.one.platform.crypto.BuiltinCrypto
import com.buct.one.platform.crypto.base64Decode

/**
 * 新中新校园卡查询平台（ecard.buct.edu.cn）响应解密与数据解析器。
 */
object EcardParser {
    const val PARSER_VERSION = 3

    private val RESULT_AES_KEY = "abcdef0123456789".toByteArray(Charsets.UTF_8)
    private val RESULT_AES_IV = "0123456789abcdef".toByteArray(Charsets.UTF_8)

    private val PUBLIC_KEY_REGEX = Regex("""rasPublicKey['"]\s*,\s*['"]([^'"]+)['"]""")

    fun extractRsaPublicKey(indexHtml: String): String {
        val raw = PUBLIC_KEY_REGEX.find(indexHtml)?.groupValues?.getOrNull(1).orEmpty()
        return raw.replace("\\/", "/").trim()
    }

    /**
     * 解密 /core/getCardUserInfoByIdserial 返回的 AES-128-CBC-ZeroPadding Base64 字符串。
     */
    fun decryptResultData(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed

        val cipherBytes = try {
            base64Decode(trimmed)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("校园卡响应数据格式异常", e)
        }
        val plainBytes = BuiltinCrypto.aesCbcDecrypt(cipherBytes, RESULT_AES_KEY, RESULT_AES_IV)
        var end = plainBytes.size
        while (end > 0 && plainBytes[end - 1] == 0.toByte()) {
            end--
        }
        return String(plainBytes, 0, end, Charsets.UTF_8).trim()
    }

    fun parseAcctypeMap(root: JSONObject): Map<String, String> {
        val dataObj = root.optJSONObject("resultData") ?: return emptyMap()
        val arr = dataObj.optJSONArray("acctype") ?: return emptyMap()
        val map = LinkedHashMap<String, String>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val code = item.optString("acctypecode", "").trim()
            val name = item.optString("acctypename", "").trim()
            if (code.isNotBlank() && name.isNotBlank()) {
                map[code] = name
            }
        }
        return map
    }

    fun parseAccountInfo(decryptedJson: String, acctypeMap: Map<String, String>): CardAccountInfo {
        val root = try {
            JSONObject(decryptedJson)
        } catch (e: Exception) {
            throw IllegalArgumentException("解析校园卡账户 JSON 失败", e)
        }
        val accountObj = root.optJSONObject("accountinfo")
            ?: throw IllegalArgumentException("未获取到校园卡账户信息")
        val userObj = accountObj.optJSONObject("cardUserinfo")

        val username = userObj?.optString("username", "").orEmpty().trim()
        val rawId = userObj?.optString("idserial", "").orEmpty().trim()
        val balanceFen = optLongFlexible(accountObj, "balance")
        val createdDate = accountObj.optString("createdate", "").trim().substringBefore(' ')

        val cards = mutableListOf<JSONObject>()
        val cardsArray = root.optJSONArray("cardinfos")
        if (cardsArray != null) {
            for (i in 0 until cardsArray.length()) {
                cardsArray.optJSONObject(i)?.let { cards.add(it) }
            }
        } else {
            root.optJSONObject("cardinfos")?.let { single ->
                // cardinfos 可能是单卡对象，也可能是以下标/卡号为 key 的 JSONObject
                if (single.has("accstatus") || single.has("cardtype")) {
                    cards.add(single)
                } else {
                    for (key in single.keys()) {
                        single.optJSONObject(key)?.let { cards.add(it) }
                    }
                }
            }
        }

        val activeCard = cards.firstOrNull { it.optString("accstatus", "").trim() == "1" }
            ?: cards.firstOrNull()

        val statusCode = activeCard?.optString("accstatus", "")?.trim().orEmpty()
        val statusLabel = mapStatusLabel(statusCode)
        val cardTypeCode = activeCard?.optString("cardtype", "")?.trim().orEmpty()
        val cardTypeName = acctypeMap[cardTypeCode]?.takeIf { it.isNotBlank() }
            ?: if (cardTypeCode.isNotBlank()) "校园卡($cardTypeCode)" else "校园卡"
        val effectiveDate = activeCard?.optString("effectdate", "")?.trim()?.substringBefore(' ').orEmpty()
        val singleLimitFen = optLongFlexible(activeCard, "maxconsamt")
        val cumulativeLimitFen = optLongFlexible(activeCard, "maxconstolamt")

        return CardAccountInfo(
            holderName = username.ifBlank { "持卡人" },
            maskedId = maskCardId(rawId),
            balanceFen = balanceFen,
            statusCode = statusCode,
            statusLabel = statusLabel,
            cardTypeName = cardTypeName,
            createdDate = createdDate,
            effectiveDate = effectiveDate,
            singleLimitFen = singleLimitFen,
            cumulativeLimitFen = cumulativeLimitFen
        )
    }

    fun parseTransactions(
        rowsArray: JSONArray?,
        totalCount: Int,
        pageNumber: Int,
        pageSize: Int
    ): Triple<List<CardTransaction>, Int, Boolean> {
        if (rowsArray == null || rowsArray.length() == 0) {
            return Triple(emptyList(), totalCount.coerceAtLeast(0), false)
        }
        val list = ArrayList<CardTransaction>(rowsArray.length())
        for (i in 0 until rowsArray.length()) {
            val row = rowsArray.optJSONObject(i) ?: continue
            val txCode = row.optString("txcode", "").trim()
            val txName = row.optString("txname", "").trim()
            val merName = row.optString("mername", "").trim()
            val summary = row.optString("summary", "").trim()

            val baseName = merName.ifBlank { txName }
            val title = when (txCode) {
                "2239", "2240" -> summary.ifBlank { baseName }.ifBlank { "补助发放" }
                "2241" -> "发放补助"
                else -> baseName.ifBlank { summary }.ifBlank { "校园卡交易" }
            }

            val posCode = row.optString("poscode", "").trim()
                .ifBlank { row.optString("stationcode", "").trim() }
                .ifBlank { "0000" }
            val amountFen = optLongFlexible(row, "txamt")
            val balanceAfterFen = optLongFlexible(row, "balance")
            val (tsMillis, formattedTime) = parseTxDate(row.opt("txdate"))

            val serverId = row.optString("journo", "").trim()
                .ifBlank { row.optString("id", "").trim() }
            val id = if (serverId.isNotBlank()) {
                "${serverId}_${txCode}_${amountFen}_${balanceAfterFen}_${posCode}"
            } else {
                "${tsMillis}_${txCode}_${amountFen}_${balanceAfterFen}_${posCode}"
            }

            list.add(
                CardTransaction(
                    id = id,
                    title = title,
                    txName = txName,
                    txCode = txCode,
                    posCode = posCode,
                    timestampMillis = tsMillis,
                    formattedTime = formattedTime,
                    amountFen = amountFen,
                    balanceAfterFen = balanceAfterFen
                )
            )
        }
        val safeTotal = maxOf(totalCount, list.size)
        val hasMore = (pageNumber + 1) * pageSize < safeTotal
        return Triple(list, safeTotal, hasMore)
    }

    fun mapStatusLabel(code: String): String = when (code.trim()) {
        "1" -> "正常"
        "2" -> "已挂失"
        "3" -> "已冻结"
        "4" -> "已销户"
        "5" -> "预销户"
        "6" -> "手工冻结"
        "" -> "状态未知"
        else -> "状态($code)"
    }

    fun mapErrorMessage(codeOrMsg: String): String {
        val raw = codeOrMsg.trim()
        return when (raw.uppercase()) {
            "CORE20003", "WEB10001" -> "校园卡密码错误（默认身份证后六位，有字母用 1 代替）"
            "CORE20004", "WEB20001", "WEB20002" -> "未找到该学工号对应的校园卡账户"
            "CORE21120" -> "登录失败次数过多，请稍后再试"
            "WEB10003" -> "初始密码禁用登录，请先在企业微信或圈存机修改卡密码"
            "CORE20005" -> "校园卡账户状态异常"
            "CORE50006" -> "该校园卡已销户"
            "CORE50009" -> "该校园卡已预销户"
            "CORE80030" -> "此卡已上交遗失卡"
            "WEB20004" -> "校园卡会话已过期，请重新登录"
            "WEB20005" -> "请求签名校验失败，请重试"
            "WEB20003", "CORE20107" -> "请求参数校验失败"
            "CORE20222" -> "存在多条校园卡档案，请联系学校信息中心处理"
            "CORE20131" -> "未查询到相关信息"
            "CORE10003", "CORE10004", "CORE10005" -> "校园卡服务暂时异常，请稍后重试（$raw）"
            else -> raw.ifBlank { "校园卡请求失败，请稍后重试" }
        }
    }

    private fun parseTxDate(raw: Any?): Pair<Long, String> = when (raw) {
        is Number -> {
            val ms = raw.toLong()
            ms to if (ms > 0L) formatDateTime(ms) else "-"
        }
        is String -> {
            val trimmed = raw.trim()
            val asLong = trimmed.toLongOrNull()
            when {
                asLong != null -> asLong to if (asLong > 0L) formatDateTime(asLong) else "-"
                trimmed.isNotBlank() -> {
                    val parsedMs = parseDateTime(trimmed) ?: 0L
                    parsedMs to trimmed
                }
                else -> 0L to "-"
            }
        }
        else -> 0L to "-"
    }

    private fun optLongFlexible(obj: JSONObject?, key: String): Long {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return 0L
        return when (val v = obj.opt(key)) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong() ?: 0L
            else -> 0L
        }
    }
}
