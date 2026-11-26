package com.buct.one.data.source.tygl

import com.buct.one.data.model.Provenance
import com.buct.one.data.model.TyglSnapshot
import com.buct.one.data.sync.DomainSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 体测与体育页状态（双端共用）。 */
sealed interface TyglUiState {
    data object Idle : TyglUiState
    data object Loading : TyglUiState
    data class Loaded(val snapshot: TyglSnapshot, val provenance: Provenance) : TyglUiState
    data class AuthExpired(val message: String) : TyglUiState
    data class Failed(val message: String) : TyglUiState
}

/**
 * 体测与体育状态机：一次抓取四类页面（体测成绩 / 阳光跑 / 体育课 / 理论考试），
 * 失败保留旧快照（数据层铁律），会话失效单独成态以便上层触发重登。
 */
class TyglController(
    private val gateway: TyglGateway,
    parserVersion: Int = TyglParser.PARSER_VERSION,
) {

    private val sync = DomainSync<TyglSnapshot>(
        source = "tygl",
        parserVersion = parserVersion,
        isEmpty = { it.fitnessRecords.isEmpty() && it.peCourses.isEmpty() },
    )

    private val _state = MutableStateFlow<TyglUiState>(TyglUiState.Idle)
    val state: StateFlow<TyglUiState> = _state.asStateFlow()

    fun lastSnapshot(): TyglSnapshot? = sync.data()

    fun provenance(): Provenance = sync.current().provenance

    suspend fun refresh() {
        _state.value = TyglUiState.Loading
        _state.value = try {
            val snapshot = gateway.fetchFullSnapshot()
            val slice = sync.commitSuccess(snapshot)
            TyglUiState.Loaded(snapshot, slice.provenance)
        } catch (expired: TyglAuthExpiredException) {
            val message = expired.message?.takeIf { it.isNotBlank() } ?: "体测系统会话已失效，请重新登录"
            sync.commitFailure(message)
            TyglUiState.AuthExpired(message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: "体测数据获取失败"
            sync.commitFailure(message)
            TyglUiState.Failed(message)
        }
    }

    fun reset() {
        _state.value = TyglUiState.Idle
        sync.reset()
    }
}
