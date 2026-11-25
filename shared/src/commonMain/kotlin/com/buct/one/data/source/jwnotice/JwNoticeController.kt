package com.buct.one.data.source.jwnotice

import com.buct.one.data.model.JwAnnouncementDetail
import com.buct.one.data.model.JwAnnouncementItem
import com.buct.one.data.model.JwNoticeSnapshot
import com.buct.one.data.store.JwNoticeSnapshotStore
import com.buct.one.data.sync.DomainSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 教务消息界面状态（双端共用）。 */
sealed interface JwNoticeUiState {
    data object Idle : JwNoticeUiState
    data object Loading : JwNoticeUiState
    data class Loaded(
        val snapshot: JwNoticeSnapshot,
        val provenance: com.buct.one.data.model.Provenance,
    ) : JwNoticeUiState
    data class AuthExpired(val message: String) : JwNoticeUiState
    data class Failed(val message: String) : JwNoticeUiState
}

/**
 * 教务消息状态机：把 [JwNoticeGateway] 的结果映射成 UI 状态。
 * 数据层铁律：失败只改状态，不清空已有数据（界面保留上次快照）。
 */
class JwNoticeController(
    private val gateway: JwNoticeGateway,
    private val cache: JwNoticeSnapshotStore? = null,
) {

    /** 四条数据层铁律由共享 [DomainSync] 统一执行（失败保留旧数据、capturedAt 单调、NEVER≠EMPTY）。 */
    private val sync = DomainSync(
        source = "jwnotice",
        parserVersion = JwNoticeParser.PARSER_VERSION,
        isEmpty = { it.reminders.isEmpty() && it.announcements.isEmpty() },
    )

    private val _state = MutableStateFlow<JwNoticeUiState>(JwNoticeUiState.Idle)
    val state: StateFlow<JwNoticeUiState> = _state.asStateFlow()

    fun lastSnapshot(): JwNoticeSnapshot? = sync.data()

    fun provenance() = sync.current().provenance

    suspend fun refresh() {
        _state.value = JwNoticeUiState.Loading
        _state.value = try {
            val snapshot = gateway.fetchFullSnapshot()
            val slice = sync.commitSuccess(snapshot)
            cache?.save(snapshot, slice.provenance.capturedAt)
            JwNoticeUiState.Loaded(snapshot, slice.provenance)
        } catch (expired: JwAuthExpiredException) {
            sync.commitFailure(expired.message ?: "教务会话已失效，请重新登录")
            JwNoticeUiState.AuthExpired(expired.message ?: "教务会话已失效，请重新登录")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: "网络异常，请稍后重试"
            sync.commitFailure(message)
            JwNoticeUiState.Failed(message)
        }
    }

    /** 冷启动先用缓存渲染（不产生网络请求）；返回是否命中缓存。 */
    fun restoreFromCache(): Boolean {
        val cached = cache?.load() ?: return false
        val slice = sync.restoreFromCache(cached, cache.capturedAt())
        if (_state.value !is JwNoticeUiState.Loaded) {
            _state.value = JwNoticeUiState.Loaded(cached, slice.provenance)
        }
        return true
    }

    suspend fun fetchDetail(item: JwAnnouncementItem): Result<JwAnnouncementDetail> =
        try {
            Result.success(gateway.fetchAnnouncementDetail(item))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

    fun reset() {
        _state.value = JwNoticeUiState.Idle
        sync.reset()
    }
}
