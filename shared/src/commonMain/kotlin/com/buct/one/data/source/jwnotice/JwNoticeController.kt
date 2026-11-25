package com.buct.one.data.source.jwnotice

import com.buct.one.data.model.JwAnnouncementDetail
import com.buct.one.data.model.JwAnnouncementItem
import com.buct.one.data.model.JwNoticeSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 教务消息界面状态（双端共用）。 */
sealed interface JwNoticeUiState {
    data object Idle : JwNoticeUiState
    data object Loading : JwNoticeUiState
    data class Loaded(val snapshot: JwNoticeSnapshot) : JwNoticeUiState
    data class AuthExpired(val message: String) : JwNoticeUiState
    data class Failed(val message: String) : JwNoticeUiState
}

/**
 * 教务消息状态机：把 [JwNoticeGateway] 的结果映射成 UI 状态。
 * 数据层铁律：失败只改状态，不清空已有数据（界面保留上次快照）。
 */
class JwNoticeController(private val gateway: JwNoticeGateway) {

    private val _state = MutableStateFlow<JwNoticeUiState>(JwNoticeUiState.Idle)
    val state: StateFlow<JwNoticeUiState> = _state.asStateFlow()

    private var lastLoaded: JwNoticeSnapshot? = null

    suspend fun refresh() {
        _state.value = JwNoticeUiState.Loading
        _state.value = try {
            val snapshot = gateway.fetchFullSnapshot()
            lastLoaded = snapshot
            JwNoticeUiState.Loaded(snapshot)
        } catch (expired: JwAuthExpiredException) {
            JwNoticeUiState.AuthExpired(expired.message ?: "教务会话已失效，请重新登录")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            JwNoticeUiState.Failed(error.message?.takeIf { it.isNotBlank() } ?: "网络异常，请稍后重试")
        }
    }

    fun lastSnapshot(): JwNoticeSnapshot? = lastLoaded

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
        lastLoaded = null
    }
}
