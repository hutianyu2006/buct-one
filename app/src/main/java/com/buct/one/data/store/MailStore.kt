package com.buct.one.data.store

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.MailAccountInfo
import com.buct.one.data.model.MailAttachment
import com.buct.one.data.model.MailFolder
import com.buct.one.data.model.MailMessageDetail
import com.buct.one.data.model.MailMessageSummary
import com.buct.one.data.model.MailSnapshot
import com.buct.one.data.model.Provenance
import com.buct.one.data.model.RefreshState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 校园邮箱（mail.buct.edu.cn）本地快照与正文缓存存储。
 *
 * 严格遵循数据层四条铁律：
 * 1. 失败永不产生新 data
 * 2. NEVER != EMPTY_CONFIRMED
 * 3. parserVersion 只增不减
 * 4. capturedAt 单调不减
 */
class MailStore(context: Context) {

    private val baseDir: File = File(context.applicationContext.filesDir, "buct_one_store").apply {
        if (!exists()) mkdirs()
    }

    private val sliceFile = File(baseDir, "mail_slice.json")
    private val detailsFile = File(baseDir, "mail_details.json")

    @Synchronized
    fun read(currentParserVersion: Int): DomainSlice<MailSnapshot> {
        if (!sliceFile.exists()) {
            return DomainSlice.never(source = SOURCE_ID, parserVersion = currentParserVersion)
        }
        return runCatching {
            val root = JSONObject(sliceFile.readText(Charsets.UTF_8))
            val provObj = root.getJSONObject("provenance")
            val savedParserVersion = provObj.optInt("parserVersion", 1)
            val savedState = RefreshState.valueOf(
                provObj.optString("refreshState", RefreshState.NEVER.name)
            )
            val dataObj = root.optJSONObject("data")
            val snapshot = dataObj?.let { deserializeSnapshot(it) }
            val effectiveState = if (snapshot != null && savedParserVersion < currentParserVersion) {
                RefreshState.STALE
            } else {
                savedState
            }
            val provenance = Provenance(
                source = provObj.optString("source", SOURCE_ID),
                capturedAt = provObj.optLong("capturedAt", 0L),
                parserVersion = max(savedParserVersion, currentParserVersion),
                complete = provObj.optBoolean("complete", true),
                incompleteReason = provObj.optString("incompleteReason", "").ifBlank { null },
                refreshState = effectiveState
            )
            DomainSlice(
                data = snapshot,
                provenance = provenance,
                lastError = root.optString("lastError", "").ifBlank { null }
            )
        }.getOrElse {
            DomainSlice.never(source = SOURCE_ID, parserVersion = currentParserVersion)
        }
    }

    @Synchronized
    fun commitSuccess(
        snapshot: MailSnapshot,
        parserVersion: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): DomainSlice<MailSnapshot> {
        val prev = read(parserVersion)
        val monotonicTime = max(nowMillis, prev.provenance.capturedAt)
        val state = if (snapshot.inboxMessages.isEmpty() && snapshot.inboxTotal == 0) {
            RefreshState.EMPTY_CONFIRMED
        } else {
            RefreshState.FRESH
        }
        val next = DomainSlice(
            data = snapshot,
            provenance = Provenance(
                source = SOURCE_ID,
                capturedAt = monotonicTime,
                parserVersion = max(parserVersion, prev.provenance.parserVersion),
                complete = true,
                incompleteReason = null,
                refreshState = state
            ),
            lastError = null
        )
        writeSlice(next)
        return next
    }

    @Synchronized
    fun commitFailure(errorMessage: String, parserVersion: Int): DomainSlice<MailSnapshot> {
        val prev = read(parserVersion)
        val nextState = if (prev.data != null) RefreshState.FAILED_FALLBACK else RefreshState.NEVER
        val next = DomainSlice(
            data = prev.data,
            provenance = prev.provenance.copy(
                parserVersion = max(parserVersion, prev.provenance.parserVersion),
                refreshState = nextState
            ),
            lastError = errorMessage.ifBlank { "校园邮箱同步失败" }
        )
        writeSlice(next)
        return next
    }

    @Synchronized
    fun readDetails(): Map<String, MailMessageDetail> {
        if (!detailsFile.exists()) return emptyMap()
        return runCatching {
            val root = JSONObject(detailsFile.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("items") ?: return emptyMap()
            val out = LinkedHashMap<String, MailMessageDetail>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                if (id.isEmpty()) continue
                out[id] = deserializeDetail(obj)
            }
            out
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    fun writeDetails(details: Map<String, MailMessageDetail>) {
        val trimmed = details.values
            .sortedByDescending { it.fetchedAt }
            .take(MAX_DETAIL_CACHE)
        val root = JSONObject().apply {
            val arr = JSONArray()
            trimmed.forEach { arr.put(serializeDetail(it)) }
            put("items", arr)
        }
        writeAtomic(detailsFile, root.toString())
    }

    private fun writeSlice(slice: DomainSlice<MailSnapshot>) {
        val root = JSONObject().apply {
            put(
                "provenance",
                JSONObject().apply {
                    put("source", slice.provenance.source)
                    put("capturedAt", slice.provenance.capturedAt)
                    put("parserVersion", slice.provenance.parserVersion)
                    put("complete", slice.provenance.complete)
                    if (slice.provenance.incompleteReason != null) {
                        put("incompleteReason", slice.provenance.incompleteReason)
                    }
                    put("refreshState", slice.provenance.refreshState.name)
                }
            )
            slice.data?.let { put("data", serializeSnapshot(it)) }
            if (slice.lastError != null) {
                put("lastError", slice.lastError)
            }
        }
        writeAtomic(sliceFile, root.toString())
    }

    private fun writeAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun serializeSnapshot(s: MailSnapshot): JSONObject = JSONObject().apply {
        put(
            "account",
            JSONObject().apply {
                put("email", s.account.email)
                put("displayEmail", s.account.displayEmail)
                put("nickName", s.account.nickName)
                put("orgName", s.account.orgName)
            }
        )
        put("inboxTotal", s.inboxTotal)
        put("unreadTotal", s.unreadTotal)
        put(
            "folders",
            JSONArray().apply {
                s.folders.forEach { f ->
                    put(
                        JSONObject().apply {
                            put("id", f.id)
                            put("name", f.name)
                            put("totalCount", f.totalCount)
                            put("unreadCount", f.unreadCount)
                            put("isSystem", f.isSystem)
                        }
                    )
                }
            }
        )
        put(
            "inboxMessages",
            JSONArray().apply {
                s.inboxMessages.forEach { m ->
                    put(
                        JSONObject().apply {
                            put("id", m.id)
                            put("folderId", m.folderId)
                            put("threadId", m.threadId)
                            put("subject", m.subject)
                            put("fromRaw", m.fromRaw)
                            put("fromName", m.fromName)
                            put("fromAddress", m.fromAddress)
                            put("toRaw", m.toRaw)
                            put("summary", m.summary)
                            put("sentDate", m.sentDate)
                            put("receivedDate", m.receivedDate)
                            put("sizeBytes", m.sizeBytes)
                            put("isRead", m.isRead)
                            put("hasAttachments", m.hasAttachments)
                            put("isFlagged", m.isFlagged)
                            put("attachments", serializeAttachments(m.attachments))
                        }
                    )
                }
            }
        )
    }

    private fun deserializeSnapshot(obj: JSONObject): MailSnapshot {
        val accObj = obj.optJSONObject("account")
        val account = if (accObj != null) {
            MailAccountInfo(
                email = accObj.optString("email", ""),
                displayEmail = accObj.optString("displayEmail", ""),
                nickName = accObj.optString("nickName", ""),
                orgName = accObj.optString("orgName", "")
            )
        } else {
            MailAccountInfo()
        }

        val foldersArr = obj.optJSONArray("folders") ?: JSONArray()
        val folders = ArrayList<MailFolder>(foldersArr.length())
        for (i in 0 until foldersArr.length()) {
            val f = foldersArr.optJSONObject(i) ?: continue
            folders.add(
                MailFolder(
                    id = f.optInt("id", 0),
                    name = f.optString("name", ""),
                    totalCount = f.optInt("totalCount", 0),
                    unreadCount = f.optInt("unreadCount", 0),
                    isSystem = f.optBoolean("isSystem", true)
                )
            )
        }

        val msgArr = obj.optJSONArray("inboxMessages") ?: JSONArray()
        val messages = ArrayList<MailMessageSummary>(msgArr.length())
        for (i in 0 until msgArr.length()) {
            val m = msgArr.optJSONObject(i) ?: continue
            messages.add(
                MailMessageSummary(
                    id = m.optString("id", ""),
                    folderId = m.optInt("folderId", 1),
                    threadId = m.optString("threadId", ""),
                    subject = m.optString("subject", ""),
                    fromRaw = m.optString("fromRaw", ""),
                    fromName = m.optString("fromName", ""),
                    fromAddress = m.optString("fromAddress", ""),
                    toRaw = m.optString("toRaw", ""),
                    summary = m.optString("summary", ""),
                    sentDate = m.optString("sentDate", ""),
                    receivedDate = m.optString("receivedDate", ""),
                    sizeBytes = m.optLong("sizeBytes", 0L),
                    isRead = m.optBoolean("isRead", true),
                    hasAttachments = m.optBoolean("hasAttachments", false),
                    isFlagged = m.optBoolean("isFlagged", false),
                    attachments = deserializeAttachments(m.optJSONArray("attachments"))
                )
            )
        }
        return MailSnapshot(
            account = account,
            folders = folders,
            inboxMessages = messages,
            inboxTotal = obj.optInt("inboxTotal", messages.size),
            unreadTotal = obj.optInt("unreadTotal", messages.count { !it.isRead })
        )
    }

    private fun serializeDetail(d: MailMessageDetail): JSONObject = JSONObject().apply {
        put("id", d.id)
        put("folderId", d.folderId)
        put("subject", d.subject)
        put("fromList", JSONArray(d.fromList))
        put("toList", JSONArray(d.toList))
        put("sentDate", d.sentDate)
        put("htmlContent", d.htmlContent)
        put("textContent", d.textContent)
        put("attachments", serializeAttachments(d.attachments))
        put("fetchedAt", d.fetchedAt)
    }

    private fun deserializeDetail(obj: JSONObject): MailMessageDetail {
        val fromArr = obj.optJSONArray("fromList") ?: JSONArray()
        val fromList = buildList {
            for (i in 0 until fromArr.length()) {
                add(fromArr.optString(i, ""))
            }
        }
        val toArr = obj.optJSONArray("toList") ?: JSONArray()
        val toList = buildList {
            for (i in 0 until toArr.length()) {
                add(toArr.optString(i, ""))
            }
        }
        return MailMessageDetail(
            id = obj.optString("id", ""),
            folderId = obj.optInt("folderId", 1),
            subject = obj.optString("subject", ""),
            fromList = fromList,
            toList = toList,
            sentDate = obj.optString("sentDate", ""),
            htmlContent = obj.optString("htmlContent", ""),
            textContent = obj.optString("textContent", ""),
            attachments = deserializeAttachments(obj.optJSONArray("attachments")),
            fetchedAt = obj.optLong("fetchedAt", 0L)
        )
    }

    private fun serializeAttachments(items: List<MailAttachment>): JSONArray = JSONArray().apply {
        items.forEach { a ->
            put(
                JSONObject().apply {
                    put("partId", a.partId)
                    put("filename", a.filename)
                    put("contentType", a.contentType)
                    put("sizeBytes", a.sizeBytes)
                    put("inlined", a.inlined)
                    put("contentId", a.contentId)
                }
            )
        }
    }

    private fun deserializeAttachments(arr: JSONArray?): List<MailAttachment> {
        if (arr == null || arr.length() == 0) return emptyList()
        val list = ArrayList<MailAttachment>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            list.add(
                MailAttachment(
                    partId = a.optString("partId", ""),
                    filename = a.optString("filename", ""),
                    contentType = a.optString("contentType", ""),
                    sizeBytes = a.optLong("sizeBytes", 0L),
                    inlined = a.optBoolean("inlined", false),
                    contentId = a.optString("contentId", "")
                )
            )
        }
        return list
    }

    companion object {
        private const val SOURCE_ID = "mail.buct.edu.cn"
        private const val MAX_DETAIL_CACHE = 40
    }
}
