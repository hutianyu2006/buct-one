package com.buct.one.shared.notice

import com.buct.one.shared.createHttpClient
import com.buct.one.shared.nowMillis
import com.buct.one.shared.sharedJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText

/**
 * 公告通道：iOS 预览版唯一已接通的业务，复用 Android 版同一份只读 JSON 协议。
 * 失败只向上抛异常，不产出半成品数据，由 UI 层决定如何提示。
 */
class NoticeRepository(
    private val client: HttpClient = createHttpClient(),
) {
    suspend fun load(): List<AppNotice> {
        val payload = client.get("$API_BASE?app=$APP_ID") {
            header("Accept", "application/json")
        }.bodyAsText()

        val envelope = sharedJson.decodeFromString(NoticeEnvelope.serializer(), payload)
        val now = nowMillis()
        return envelope.notices
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .filter { notice -> notice.expiresAt?.let { it > now } ?: true }
            .sortedWith(compareByDescending<AppNotice> { it.pinned }.thenByDescending { it.publishedAt })
    }

    fun close() {
        client.close()
    }

    private companion object {
        const val API_BASE = "https://notice.buct.one/api/notices"
        const val APP_ID = "buct-one"
    }
}
