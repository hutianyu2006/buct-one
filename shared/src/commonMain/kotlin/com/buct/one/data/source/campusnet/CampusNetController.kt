package com.buct.one.data.source.campusnet

import com.buct.one.data.model.CampusNetSnapshot
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

class CampusNetController(private val gateway: CampusNetGateway) {

    private val _state = MutableStateFlow<CampusNetUiState>(CampusNetUiState.Idle)
    val state: StateFlow<CampusNetUiState> = _state.asStateFlow()

    suspend fun refresh(studentId: String) {
        _state.value = CampusNetUiState.Loading
        _state.value = try {
            CampusNetUiState.Loaded(gateway.fetchBillingSnapshot(studentId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CampusNetUiState.Failed(error.message?.takeIf { it.isNotBlank() } ?: "校园网数据获取失败")
        }
    }
}
