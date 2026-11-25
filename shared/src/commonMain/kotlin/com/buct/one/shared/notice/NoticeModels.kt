package com.buct.one.shared.notice

import kotlinx.serialization.Serializable

/** https://notice.buct.one/api/notices 的响应体，字段与 Android 版 AppNoticeManager 一致。 */
@Serializable
internal data class NoticeEnvelope(
    val notices: List<AppNotice> = emptyList(),
    val serverTime: Long = 0L,
)

@Serializable
data class AppNotice(
    val id: String = "",
    val appId: String = "buct-one",
    val title: String = "",
    val summary: String = "",
    val markdown: String = "",
    val level: String = "info",
    val popup: Boolean = false,
    val pinned: Boolean = false,
    val dismissible: Boolean = true,
    val actionText: String = "",
    val actionUrl: String = "",
    val minVersionCode: Int = 0,
    val maxVersionCode: Int = 0,
    val publishedAt: Long = 0L,
    val expiresAt: Long? = null,
)
