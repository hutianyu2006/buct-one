package com.buct.one.data.model

/**
 * 校园数据域的五种刷新状态。
 * 铁律：NEVER != EMPTY_CONFIRMED，未同步过不能显示「没有数据」。
 */
enum class RefreshState {
    /** 刚同步成功且有有效数据 */
    FRESH,
    /** 本地已有历史快照，但已过期 */
    STALE,
    /** 已成功同步且服务端明确返回空列表 */
    EMPTY_CONFIRMED,
    /** 本次同步失败，保留旧快照作为兜底 */
    FAILED_FALLBACK,
    /** 从未成功同步过 */
    NEVER
}

data class Completeness(
    val complete: Boolean,
    val reason: String? = null,
    val expectedCount: Int? = null,
    val actualCount: Int = 0
)

data class Provenance(
    val source: String,
    val capturedAt: Long,
    val parserVersion: Int,
    val refreshState: RefreshState,
    val complete: Boolean = true,
    val incompleteReason: String? = null
)

data class DomainSlice<T>(
    val data: T?,
    val provenance: Provenance,
    val lastError: String? = null
) {
    companion object {
        fun <T> never(source: String, parserVersion: Int): DomainSlice<T> = DomainSlice(
            data = null,
            provenance = Provenance(
                source = source,
                capturedAt = 0L,
                parserVersion = parserVersion,
                refreshState = RefreshState.NEVER,
                complete = true
            ),
            lastError = null
        )
    }
}
