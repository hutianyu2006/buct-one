package com.buct.one.data.source.electricity

import com.buct.one.data.model.ElectricityReading
import com.buct.one.data.model.ElectricityRoomCatalog
import com.buct.one.data.model.ElectricityRoomTarget
import com.buct.one.data.model.Provenance
import com.buct.one.data.store.ElectricitySnapshotStore
import com.buct.one.data.sync.DomainSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 电费页状态（双端共用）。 */
sealed interface ElectricityUiState {
    data object Idle : ElectricityUiState
    data object LoadingCatalog : ElectricityUiState
    data object CatalogUnavailable : ElectricityUiState
    data class CatalogReady(val catalog: ElectricityRoomCatalog) : ElectricityUiState
    data class Loading(val targets: List<ElectricityRoomTarget>) : ElectricityUiState
    data class Loaded(val readings: List<ElectricityReading>, val provenance: Provenance) : ElectricityUiState
    data class Banned(val message: String, val retryAfterSeconds: Int?) : ElectricityUiState
    data class Failed(val message: String) : ElectricityUiState
}

/**
 * 宿舍电费状态机：字典加载 → 绑定房间 → 查询余额，双端共用。
 *
 * 失败语义（数据层铁律）：查询失败只改状态与 lastError，**保留上次读数**；
 * 字典读取失败不清空用户已选房间（只在界面提示"字典不可用"）。
 */
class ElectricityController(
    private val gateway: ElectricityGatewayClient,
    private val catalogSource: ElectricityRoomCatalogSource = defaultElectricityRoomCatalogSource(),
    private val cache: ElectricitySnapshotStore? = null,
    parserVersion: Int = ElectricityParser.PARSER_VERSION,
) {

    private val sync = DomainSync<List<ElectricityReading>>(
        source = "electricity",
        parserVersion = parserVersion,
        isEmpty = { it.isEmpty() },
    )

    private val _state = MutableStateFlow<ElectricityUiState>(ElectricityUiState.Idle)
    val state: StateFlow<ElectricityUiState> = _state.asStateFlow()

    private var cachedCatalog: ElectricityRoomCatalog? = null
    private var boundTargets: List<ElectricityRoomTarget> = emptyList()

    fun lastReadings(): List<ElectricityReading>? = sync.data()

    fun targets(): List<ElectricityRoomTarget> = boundTargets

    fun provenance(): Provenance = sync.current().provenance

    /** 冷启动先用缓存渲染；返回是否命中。 */
    fun restoreFromCache(): Boolean {
        val store = cache ?: return false
        val cached = store.load() ?: return false
        boundTargets = cached.targets
        val slice = sync.restoreFromCache(cached.readings, store.capturedAt())
        _state.value = ElectricityUiState.Loaded(cached.readings, slice.provenance)
        return true
    }

    suspend fun loadCatalog() {
        cachedCatalog?.let {
            _state.value = ElectricityUiState.CatalogReady(it)
            return
        }
        _state.value = ElectricityUiState.LoadingCatalog
        val catalog = catalogSource.loadCatalog()
        if (catalog == null) {
            _state.value = ElectricityUiState.CatalogUnavailable
            return
        }
        cachedCatalog = catalog
        _state.value = ElectricityUiState.CatalogReady(catalog)
    }

    /** 查询绑定房间的电费；studentId / clientId 由调用方提供，签名密钥由平台注入。 */
    suspend fun refresh(
        targets: List<ElectricityRoomTarget>,
        clientId: String,
        studentId: String,
        signSecret: String,
    ) {
        boundTargets = targets
        _state.value = ElectricityUiState.Loading(targets)
        val readings = mutableListOf<ElectricityReading>()
        var lastError: Exception? = null
        for (target in targets) {
            try {
                readings += gateway.queryBalance(target, clientId, studentId, signSecret)
            } catch (banned: ElectricityGatewayException) {
                if (banned.isBanned) {
                    val message = banned.message?.takeIf { it.isNotBlank() } ?: "电费查询已被限制"
                    sync.commitFailure(message)
                    _state.value = ElectricityUiState.Banned(message, banned.retryAfterSeconds)
                    return
                }
                lastError = banned
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
            }
        }

        if (readings.isEmpty()) {
            val message = lastError?.message?.takeIf { it.isNotBlank() } ?: "电费查询失败"
            sync.commitFailure(message)
            _state.value = ElectricityUiState.Failed(message)
            return
        }
        val slice = sync.commitSuccess(readings)
        cache?.save(readings, targets, slice.provenance.capturedAt)
        _state.value = ElectricityUiState.Loaded(readings, slice.provenance)
    }

    fun reset() {
        _state.value = ElectricityUiState.Idle
        boundTargets = emptyList()
        sync.reset()
    }
}
