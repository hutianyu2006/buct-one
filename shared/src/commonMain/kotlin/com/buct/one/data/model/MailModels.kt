package com.buct.one.data.model

/**
 * 校园邮箱（mail.buct.edu.cn / 网易企业邮箱）领域模型。
 *
 * 严格约束：model/ 禁止任何 import（零依赖）。
 */
object MailFolderIds {
    const val INBOX = 1
    const val DRAFTS = 2
    const val SENT = 3
    const val TRASH = 4
    const val SPAM = 5
}

data class MailAccountInfo(
    val email: String = "",
    val displayEmail: String = "",
    val nickName: String = "",
    val orgName: String = ""
)

data class MailFolder(
    val id: Int,
    val name: String,
    val totalCount: Int = 0,
    val unreadCount: Int = 0,
    val isSystem: Boolean = true
)

data class MailAttachment(
    val partId: String,
    val filename: String,
    val contentType: String = "",
    val sizeBytes: Long = 0L,
    val inlined: Boolean = false,
    val contentId: String = ""
)

data class MailMessageSummary(
    val id: String,
    val folderId: Int = 1,
    val threadId: String = "",
    val subject: String = "",
    val fromRaw: String = "",
    val fromName: String = "",
    val fromAddress: String = "",
    val toRaw: String = "",
    val summary: String = "",
    val sentDate: String = "",
    val receivedDate: String = "",
    val sizeBytes: Long = 0L,
    val isRead: Boolean = true,
    val hasAttachments: Boolean = false,
    val isFlagged: Boolean = false,
    val attachments: List<MailAttachment> = emptyList()
)

data class MailMessageDetail(
    val id: String,
    val folderId: Int = 1,
    val subject: String = "",
    val fromList: List<String> = emptyList(),
    val toList: List<String> = emptyList(),
    val sentDate: String = "",
    val htmlContent: String = "",
    val textContent: String = "",
    val attachments: List<MailAttachment> = emptyList(),
    val fetchedAt: Long = 0L
)

data class MailSnapshot(
    val account: MailAccountInfo = MailAccountInfo(),
    val folders: List<MailFolder> = emptyList(),
    val inboxMessages: List<MailMessageSummary> = emptyList(),
    val inboxTotal: Int = 0,
    val unreadTotal: Int = 0
)

data class MailDownloadedFile(
    val filePath: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long
)
