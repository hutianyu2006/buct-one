package com.buct.one.data.sync

import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.Provenance
import com.buct.one.data.model.RefreshState
import com.buct.one.shared.nowMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 双端共用的数据域同步状态机，把「数据层四条铁律」固化在一处：
 *
 * 1. 失败永不产生新 data：失败只改 `refreshState` / `lastError`，旧数据原样保留；
 * 2. NEVER ≠ EMPTY_CONFIRMED：从未同步过是 [RefreshState.NEVER]，服务端明确返回空才是
 *    [RefreshState.EMPTY_CONFIRMED]；
 * 3. `capturedAt` 单调不减：时钟回拨也不会让快照看起来"更新"；
 * 4. `parserVersion` 只增不减：由调用方传入，本类只负责记录。
 */
class DomainSync<T>(
    private val source: String,
    private val parserVersion: Int,
    private val isEmpty: (T) -> Boolean = { false },
    private val freshTtlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = { nowMillis() },
) {

    private val _state = MutableStateFlow(DomainSlice.never<T>(source, parserVersion))
    val state: StateFlow<DomainSlice<T>> = _state.asStateFlow()

    fun current(): DomainSlice<T> = _state.value

    fun data(): T? = _state.value.data

    /** 数据新鲜且在 TTL 内，可用于「非手动刷新时跳过网络请求」。 */
    fun isFresh(): Boolean {
        val slice = _state.value
        return slice.data != null &&
            slice.provenance.refreshState == RefreshState.FRESH &&
            clock() - slice.provenance.capturedAt in 0 until freshTtlMillis
    }

    fun commitSuccess(data: T): DomainSlice<T> {
        val previous = _state.value.provenance.capturedAt
        val now = clock()
        val capturedAt = if (now < previous) previous else now
        val state = if (isEmpty(data)) RefreshState.EMPTY_CONFIRMED else RefreshState.FRESH
        val committed = DomainSlice(
            data = data,
            provenance = Provenance(
                source = source,
                capturedAt = capturedAt,
                parserVersion = parserVersion,
                refreshState = state,
                complete = true,
            ),
            lastError = null,
        )
        _state.value = committed
        return committed
    }

    /** 失败：保留旧数据；已有数据时标记 [RefreshState.FAILED_FALLBACK]，否则保持 [RefreshState.NEVER]。 */
    fun commitFailure(message: String): DomainSlice<T> {
        val previous = _state.value
        val hasData = previous.data != null
        val committed = DomainSlice(
            data = previous.data,
            provenance = previous.provenance.copy(
                parserVersion = parserVersion,
                refreshState = if (hasData) RefreshState.FAILED_FALLBACK else RefreshState.NEVER,
            ),
            lastError = message,
        )
        _state.value = committed
        return committed
    }

    /** 从本地缓存恢复（冷启动）：标记为 [RefreshState.STALE]，真实刷新后才会变 FRESH。 */
    fun restoreFromCache(data: T, capturedAt: Long): DomainSlice<T> {
        val previous = _state.value.provenance.capturedAt
        val committed = DomainSlice(
            data = data,
            provenance = Provenance(
                source = source,
                capturedAt = maxOf(capturedAt, previous),
                parserVersion = parserVersion,
                refreshState = RefreshState.STALE,
                complete = true,
            ),
            lastError = null,
        )
        _state.value = committed
        return committed
    }

    fun reset() {
        _state.value = DomainSlice.never(source, parserVersion)
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 30L * 60L * 1000L
    }
}
