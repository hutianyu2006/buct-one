package com.buct.one.data.source.mail

import com.buct.one.data.model.MailAccountInfo
import com.buct.one.data.model.MailAttachment
import com.buct.one.data.model.MailFolder
import com.buct.one.data.model.MailMessageDetail
import com.buct.one.data.model.MailMessageSummary
import com.buct.one.data.json.JSONArray
import com.buct.one.data.json.JSONObject
import com.fleeksoft.ksoup.Ksoup

/**
 * 校园邮箱（网易企业邮箱 `/js6/s` JSON RPC 与 sidportal 登录页）纯函数解析器。
 * 改动解析逻辑时必须将 PARSER_VERSION + 1。
 */
object MailParser {
    const val PARSER_VERSION = 3

    data class SidLoginPage(
        val execution: String,
        val croypto: String,
        val errorCode: String
    )

    data class MailListPage(
        val messages: List<MailMessageSummary>,
        val total: Int
    )

    fun parseSidPortalPage(html: String): SidLoginPage {
        val doc = Ksoup.parse(html)
        fun text(id: String) = doc.selectFirst("#$id")?.text()?.trim().orEmpty()
        val execution = text("login-page-flowkey").ifBlank {
            doc.selectFirst("input[name=execution]")?.attr("value")?.trim().orEmpty()
        }
        val croypto = text("login-croypto")
        val errorCode = text("login-error-msg").ifBlank { text("login-error-code") }
        return SidLoginPage(
            execution = execution,
            croypto = croypto,
            errorCode = errorCode
        )
    }

    fun checkRpcCode(json: String): Pair<Boolean, String> {
        val start = json.indexOf('{')
        if (start < 0) return false to "NON_JSON"
        return runCatching {
            val code = JSONObject(json.substring(start)).optString("code", "")
            (code == "S_OK") to code
        }.getOrDefault(false to "PARSE_ERROR")
    }

    fun isSessionErrorCode(code: String): Boolean {
        if (code.isBlank()) return false
        return listOf("INVALID_SESSION", "UNAUTHORIZED", "EXPIRED", "FORBIDDEN", "FA_NEED_LOGIN", "401", "403")
            .any { code.contains(it, ignoreCase = true) }
    }

    fun parseAccountInfo(json: String): MailAccountInfo {
        val root = JSONObject(json)
        val data = root.optJSONObject("data") ?: return MailAccountInfo()
        val defaultSender = data.optJSONObject("defaultSender")
        val email = data.optString("email", "").ifBlank {
            defaultSender?.optString("email", "").orEmpty()
        }.ifBlank { data.optString("accountName", "") }
        val displayEmail = data.optString("displayEmail", "").ifBlank { email }
        val nickName = data.optString("nickName", "").ifBlank {
            data.optString("senderName", "")
        }.ifBlank {
            defaultSender?.optString("nickName", "").orEmpty()
        }
        val orgName = data.optString("orgName", "")
        return MailAccountInfo(
            email = email.trim(),
            displayEmail = displayEmail.trim(),
            nickName = nickName.trim(),
            orgName = orgName.trim()
        )
    }

    fun parseFolders(json: String): List<MailFolder> {
        val root = JSONObject(json)
        val code = root.optString("code", "")
        if (code != "S_OK") {
            throw IllegalArgumentException("获取邮箱文件夹失败（$code）")
        }
        val arr = root.optJSONArray("var") ?: return emptyList()
        val result = ArrayList<MailFolder>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optInt("id", Int.MIN_VALUE)
            if (id == Int.MIN_VALUE) continue
            val name = obj.optString("name", "").trim()
            if (name.isEmpty()) continue
            val stats = obj.optJSONObject("stats")
            val flags = obj.optJSONObject("flags")
            result.add(
                MailFolder(
                    id = id,
                    name = name,
                    totalCount = stats?.optInt("messageCount", 0) ?: 0,
                    unreadCount = stats?.optInt("unreadMessageCount", 0) ?: 0,
                    isSystem = flags?.optBoolean("system", true) ?: true
                )
            )
        }
        return result
    }

    fun parseMessageList(json: String, fallbackFolderId: Int = 1): MailListPage {
        val root = JSONObject(json)
        val code = root.optString("code", "")
        if (code != "S_OK") {
            throw IllegalArgumentException("获取邮件列表失败（$code）")
        }
        val arr = root.optJSONArray("var") ?: JSONArray()
        val total = root.optInt("total", arr.length())
        val messages = ArrayList<MailMessageSummary>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").trim()
            if (id.isEmpty()) continue
            val fromRaw = extractAddressText(obj.opt("from"))
            val (fromName, fromAddress) = splitSender(fromRaw)
            val toRaw = extractAddressText(obj.opt("to"))
            val flags = obj.optJSONObject("flags")
            val isRead = flags?.optBoolean("read", false) == true
            val hasAttFlag = (flags?.optBoolean("attached", false) ?: false) ||
                (flags?.optBoolean("realAttached", false) ?: false)
            val attachments = parseAttachments(obj.optJSONArray("attachments"))
            val subject = obj.optString("subject", "").trim().ifBlank { "(无主题)" }
            messages.add(
                MailMessageSummary(
                    id = id,
                    folderId = obj.optInt("fid", fallbackFolderId),
                    threadId = obj.optString("tid", ""),
                    subject = subject,
                    fromRaw = fromRaw,
                    fromName = fromName,
                    fromAddress = fromAddress,
                    toRaw = toRaw,
                    summary = obj.optString("summary", "").trim(),
                    sentDate = obj.optString("sentDate", "").trim(),
                    receivedDate = obj.optString("receivedDate", "").trim(),
                    sizeBytes = obj.optLong("size", 0L),
                    isRead = isRead,
                    hasAttachments = hasAttFlag || attachments.any { !it.inlined },
                    isFlagged = obj.optInt("label0", 0) == 1,
                    attachments = attachments
                )
            )
        }
        return MailListPage(messages = messages, total = total)
    }

    fun parseMessageDetail(messageId: String, json: String, nowMillis: Long): MailMessageDetail {
        val root = JSONObject(json)
        val code = root.optString("code", "")
        if (code != "S_OK") {
            throw IllegalArgumentException("读取邮件详情失败（$code）")
        }
        val body = root.optJSONObject("var")
            ?: throw IllegalArgumentException("邮件正文响应缺少 var 字段")
        val htmlObj = body.optJSONObject("html")
        val textObj = body.optJSONObject("text")
        val htmlContent = htmlObj?.optString("content", "").orEmpty()
        val textContent = textObj?.optString("content", "").orEmpty()
        val attachments = parseAttachments(body.optJSONArray("attachments"))
        return MailMessageDetail(
            id = messageId,
            folderId = body.optInt("fid", 1),
            subject = body.optString("subject", "").trim().ifBlank { "(无主题)" },
            fromList = parseStringList(body.opt("from")),
            toList = parseStringList(body.opt("to")),
            sentDate = body.optString("sentDate", "").trim(),
            htmlContent = htmlContent,
            textContent = textContent,
            attachments = attachments,
            fetchedAt = nowMillis
        )
    }

    fun splitSender(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "" to ""
        val lt = trimmed.lastIndexOf('<')
        val gt = trimmed.lastIndexOf('>')
        if (lt in 0 until gt) {
            val addr = trimmed.substring(lt + 1, gt).trim()
            val name = trimmed.substring(0, lt).trim().trim('"', '\'')
            return (name.ifBlank { addr.substringBefore('@') }) to addr
        }
        val clean = trimmed.trim('"', '\'')
        return if (clean.contains('@')) {
            clean.substringBefore('@') to clean
        } else {
            clean to ""
        }
    }

    private fun extractAddressText(value: Any?): String = when (value) {
        is String -> value.trim()
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                val item = value.optString(i, "").trim()
                if (item.isNotEmpty()) {
                    if (isNotEmpty()) append(", ")
                    append(item)
                }
            }
        }
        else -> ""
    }

    private fun parseStringList(value: Any?): List<String> = when (value) {
        is JSONArray -> buildList {
            for (i in 0 until value.length()) {
                val item = value.optString(i, "").trim()
                if (item.isNotEmpty()) add(item)
            }
        }
        is String -> value.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
        else -> emptyList()
    }

    private fun parseAttachments(arr: JSONArray?): List<MailAttachment> {
        if (arr == null || arr.length() == 0) return emptyList()
        val result = ArrayList<MailAttachment>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val partId = obj.optString("id", "").trim()
            if (partId.isEmpty()) continue
            val rawName = obj.optString("filename", "").trim().ifBlank { "attachment_$partId" }
            val size = obj.optLong("contentLength", -1L).let {
                if (it >= 0L) it else obj.optLong("estimateSize", 0L).coerceAtLeast(0L)
            }
            result.add(
                MailAttachment(
                    partId = partId,
                    filename = rawName,
                    contentType = obj.optString("contentType", "").trim(),
                    sizeBytes = size,
                    inlined = obj.optBoolean("inlined", false),
                    contentId = obj.optString("contentId", "").trim()
                )
            )
        }
        return result
    }
}
