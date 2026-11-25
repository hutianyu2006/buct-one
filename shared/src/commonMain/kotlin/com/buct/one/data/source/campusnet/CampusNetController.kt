package com.buct.one.data.source.campusnet

import com.buct.one.data.model.CampusNetSnapshot
import com.buct.one.data.store.CampusNetSnapshotStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 校园网网费界面状态（双端共用）。 */
sealed interface CampusNetUiState {
    data object Idle : CampusNetUiState
    data object Loading : CampusNetUiState
    data class Loaded(val snapshot: CampusNetSnapshot) : CampusNetUiState
    data class Failed(val message: String) : CampusNetUiState
}

class CampusNetController(
    private val gateway: CampusNetGateway,
    private val cache: CampusNetSnapshotStore? = null,
) {

    private val _state = MutableStateFlow<CampusNetUiState>(CampusNetUiState.Idle)
    val state: StateFlow<CampusNetUiState> = _state.asStateFlow()

    private var lastLoaded: CampusNetSnapshot? = null

    /** 冷启动先用缓存渲染（不产生网络请求）；返回是否命中缓存。 */
    fun restoreFromCache(): Boolean {
        val cached = cache?.load() ?: return false
        lastLoaded = cached
        if (_state.value !is CampusNetUiState.Loaded) {
            _state.value = CampusNetUiState.Loaded(cached)
        }
        return true
    }

    fun lastSnapshot(): CampusNetSnapshot? = lastLoaded

    suspend fun refresh(studentId: String) {
        _state.value = CampusNetUiState.Loading
        _state.value = try {
            val snapshot = gateway.fetchBillingSnapshot(studentId)
            lastLoaded = snapshot
            cache?.save(snapshot)
            CampusNetUiState.Loaded(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CampusNetUiState.Failed(error.message?.takeIf { it.isNotBlank() } ?: "校园网数据获取失败")
        }
    }
}
