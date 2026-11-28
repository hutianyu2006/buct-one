package com.buct.one.data.source.scs

import com.buct.one.data.model.Provenance
import com.buct.one.data.model.ScsSnapshot
import com.buct.one.data.store.ScsSnapshotStore
import com.buct.one.data.sync.DomainSync
import com.buct.one.data.sync.ReloginWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 第二课堂页面状态（双端共用）。 */
sealed interface ScsUiState {
    data object Idle : ScsUiState
    data object Loading : ScsUiState
    data class Loaded(val snapshot: ScsSnapshot, val provenance: Provenance) : ScsUiState
    data class AuthExpired(val message: String) : ScsUiState
    data class Failed(val message: String) : ScsUiState
}

/**
 * 第二课堂状态机：组合 [DomainSync]（数据层四条铁律）+ [ScsSnapshotStore]（冷启动缓存）。
 *
 * 会话失效单独成态（[ScsUiState.AuthExpired]），方便上层决定是否触发静默重登；
 * 失败保留旧快照，绝不产生新 data。
 */
class ScsController(
    private val gateway: ScsGateway,
    private val cache: ScsSnapshotStore? = null,
    /** 会话失效时的静默重登包装（可选），语义与 JwNoticeController 一致。 */
    private val withRelogin: ReloginWrapper<ScsSnapshot> = ReloginWrapper.passthrough(),
) {

    private val sync = DomainSync<ScsSnapshot>(
        source = "scs",
        parserVersion = ScsParser.PARSER_VERSION,
        isEmpty = { it.terms.isEmpty() && it.activities.isEmpty() },
    )

    private val _state = MutableStateFlow<ScsUiState>(ScsUiState.Idle)
    val state: StateFlow<ScsUiState> = _state.asStateFlow()

    fun lastSnapshot(): ScsSnapshot? = sync.data()

    fun provenance(): Provenance = sync.current().provenance

    /** 冷启动先用缓存渲染（不产生网络请求）；返回是否命中缓存。 */
    fun restoreFromCache(): Boolean {
        val store = cache ?: return false
        val cached = store.load() ?: return false
        val slice = sync.restoreFromCache(cached, store.capturedAt())
        if (_state.value !is ScsUiState.Loaded) {
            _state.value = ScsUiState.Loaded(cached, slice.provenance)
        }
        return true
    }

    suspend fun refresh() {
        _state.value = ScsUiState.Loading
        _state.value = try {
            val snapshot = withRelogin.fetch { gateway.fetchFullSnapshot() }
            val slice = sync.commitSuccess(snapshot)
            cache?.save(snapshot, slice.provenance.capturedAt)
            ScsUiState.Loaded(snapshot, slice.provenance)
        } catch (expired: ScsAuthExpiredException) {
            val message = expired.message?.takeIf { it.isNotBlank() } ?: "第二课堂会话已失效，请重新登录"
            sync.commitFailure(message)
            ScsUiState.AuthExpired(message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: "第二课堂数据获取失败"
            sync.commitFailure(message)
            ScsUiState.Failed(message)
        }
    }

    fun reset() {
        _state.value = ScsUiState.Idle
        sync.reset()
    }
}
