package com.buct.one.data.source.campusnet

import com.buct.one.data.model.CampusNetSnapshot
import com.buct.one.data.model.Provenance
import com.buct.one.data.store.CampusNetSnapshotStore
import com.buct.one.data.sync.DomainSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 校园网网费界面状态（双端共用）。 */
sealed interface CampusNetUiState {
    data object Idle : CampusNetUiState
    data object Loading : CampusNetUiState
    data class Loaded(val snapshot: CampusNetSnapshot, val provenance: Provenance) : CampusNetUiState
    data class Failed(val message: String) : CampusNetUiState
}

class CampusNetController(
    private val gateway: CampusNetGateway,
    private val cache: CampusNetSnapshotStore? = null,
) {

    private val sync = DomainSync(
        source = "campusnet",
        parserVersion = com.buct.one.data.source.campusnet.CampusNetParser.PARSER_VERSION,
        isEmpty = { false },
    )

    private val _state = MutableStateFlow<CampusNetUiState>(CampusNetUiState.Idle)
    val state: StateFlow<CampusNetUiState> = _state.asStateFlow()

    fun lastSnapshot(): CampusNetSnapshot? = sync.data()

    fun provenance() = sync.current().provenance

    /** 冷启动先用缓存渲染（不产生网络请求）；返回是否命中缓存。 */
    fun restoreFromCache(): Boolean {
        val cached = cache?.load() ?: return false
        val slice = sync.restoreFromCache(cached, cache.capturedAt())
        if (_state.value !is CampusNetUiState.Loaded) {
            _state.value = CampusNetUiState.Loaded(cached, slice.provenance)
        }
        return true
    }

    suspend fun refresh(studentId: String) {
        _state.value = CampusNetUiState.Loading
        _state.value = try {
            val snapshot = gateway.fetchBillingSnapshot(studentId)
            val slice = sync.commitSuccess(snapshot)
            cache?.save(snapshot, slice.provenance.capturedAt)
            CampusNetUiState.Loaded(snapshot, slice.provenance)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: "校园网数据获取失败"
            sync.commitFailure(message)
            CampusNetUiState.Failed(message)
        }
    }
}
