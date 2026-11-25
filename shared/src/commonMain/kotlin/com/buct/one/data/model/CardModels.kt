package com.buct.one.data.model

/**
 * 校园卡账户概览信息（对应 ecard.buct.edu.cn 账户中心）。
 * 严禁新增任何 import，保持 model 包零依赖门禁。
 */
data class CardAccountInfo(
    val holderName: String,
    val maskedId: String,
    val balanceFen: Long,
    val statusCode: String,
    val statusLabel: String,
    val cardTypeName: String,
    val createdDate: String,
    val effectiveDate: String,
    val singleLimitFen: Long,
    val cumulativeLimitFen: Long
)

/**
 * 单条校园卡消费/充值/补助流水记录。
 */
data class CardTransaction(
    val id: String,
    val title: String,
    val txName: String,
    val txCode: String,
    val posCode: String,
    val timestampMillis: Long,
    val formattedTime: String,
    val amountFen: Long,
    val balanceAfterFen: Long
)

/**
 * 校园卡领域完整快照，统一纳入 DomainSlice 溯源模型。
 */
data class CardSnapshot(
    val account: CardAccountInfo,
    val transactions: List<CardTransaction>,
    val queryStartDate: String,
    val queryEndDate: String,
    val totalCount: Int,
    val hasMore: Boolean
)

/**
 * 将整数金额（分）格式化为保留两位小数的元字符串，例如 12345 -> "123.45"，-50 -> "-0.50"。
 */
fun formatFenToYuan(fen: Long): String {
    val negative = fen < 0L
    val absVal = if (fen == Long.MIN_VALUE) Long.MAX_VALUE else if (negative) -fen else fen
    val yuan = absVal / 100L
    val rem = absVal % 100L
    val dec = if (rem < 10L) "0$rem" else rem.toString()
    return if (negative) "-$yuan.$dec" else "$yuan.$dec"
}

/**
 * 对学工号做落盘与展示脱敏，例如 "2022012345" -> "2022****45"。
 */
fun maskCardId(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.length <= 4) return "*".repeat(trimmed.length)
    val prefix = trimmed.take(4.coerceAtMost(trimmed.length - 2))
    val suffix = trimmed.takeLast(2)
    val stars = "*".repeat((trimmed.length - prefix.length - suffix.length).coerceAtLeast(2))
    return "$prefix$stars$suffix"
}
