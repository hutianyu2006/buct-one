package com.buct.one.data.sync

import android.content.Context
import com.buct.course.login.AndroidAppConfig
import com.buct.course.manager.UserManager
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.ElectricityReading
import com.buct.one.data.model.ElectricityRoomCatalog
import com.buct.one.data.model.ElectricityRoomTarget
import com.buct.one.data.model.ElectricitySnapshot
import com.buct.one.data.source.electricity.ElectricityClient
import com.buct.one.data.source.electricity.ElectricityGatewayException
import com.buct.one.data.source.electricity.ElectricityParser
import com.buct.one.data.source.electricity.defaultElectricityRoomCatalogSource
import com.buct.one.data.store.ElectricityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 宿舍电费（ele.buct.one）独立同步服务。
 * 与学校统一身份认证 / WebVPN 生命周期解耦，仅随查询请求上传学号与请求签名，
 * 严格遵循数据层四条铁律（失败永不改写已有 data）。
 */
class ElectricitySyncService private constructor(
    private val appContext: Context,
    private val store: ElectricityStore,
    private val client: ElectricityClient
) {
    private val syncMutex = Mutex()

    /** 宿舍字典读取器（共享层资源，双端同一份）。 */
    private val electricityCatalogSource = defaultElectricityRoomCatalogSource()
    private val catalogMutex = Mutex()

    @Volatile
    private var cachedCatalog: ElectricityRoomCatalog? = null

    private val _electricityState = MutableStateFlow(store.read(ElectricityParser.PARSER_VERSION))
    val electricityState: StateFlow<DomainSlice<ElectricitySnapshot>> = _electricityState.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _consentAccepted = MutableStateFlow(store.isConsentAccepted())
    val consentAccepted: StateFlow<Boolean> = _consentAccepted.asStateFlow()

    private val _lowBalanceThreshold = MutableStateFlow(store.getLowBalanceThreshold())
    val lowBalanceThreshold: StateFlow<Double> = _lowBalanceThreshold.asStateFlow()

    fun currentStudentId(): String {
        val user = UserManager.getInstance().apply { init(appContext) }
        if (user.isDemoMode) return ""
        val sid = user.studentId?.trim()?.takeIf { it.isNotBlank() }
            ?: user.username?.trim().orEmpty()
        return if (STUDENT_ID_RE.matches(sid)) sid else ""
    }

    fun isDemoAccount(): Boolean {
        val user = UserManager.getInstance().apply { init(appContext) }
        return user.isDemoMode
    }

    fun acceptConsent() {
        store.setConsentAccepted(true)
        _consentAccepted.value = true
    }

    fun revokeConsentAndClear() {
        store.setConsentAccepted(false)
        _consentAccepted.value = false
        _electricityState.value = store.clearAll(ElectricityParser.PARSER_VERSION)
    }

    fun updateLowBalanceThreshold(threshold: Double) {
        val clean = threshold.coerceIn(1.0, 500.0)
        store.setLowBalanceThreshold(clean)
        _lowBalanceThreshold.value = clean
        _electricityState.value = store.updateThreshold(clean, ElectricityParser.PARSER_VERSION)
    }

    fun canAutoSync(): Boolean {
        if (!_consentAccepted.value) return false
        if (currentStudentId().isBlank()) return false
        return !_electricityState.value.data?.targets.isNullOrEmpty()
    }

    suspend fun loadCatalog(): Result<ElectricityRoomCatalog> = withContext(Dispatchers.IO) {
        cachedCatalog?.let { return@withContext Result.success(it) }
        runCatching {
            catalogMutex.withLock {
                cachedCatalog?.let { return@withLock it }
                // 字典已下沉到共享层资源（双端同一份），这里走共享读取器
                val raw = electricityCatalogSource.readCatalogJson()
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("宿舍房间字典缺失")
                ElectricityParser.parseRoomCatalog(raw).also { cachedCatalog = it }
            }
        }
    }

    suspend fun bindTargetsAndRefresh(
        targets: List<ElectricityRoomTarget>,
        threshold: Double = _lowBalanceThreshold.value
    ): Result<ElectricitySnapshot> {
        val cleanTargets = targets.distinctBy { "${it.schoolid}:${it.roomid}" }
        val cleanThreshold = threshold.coerceIn(1.0, 500.0)
        withContext(Dispatchers.IO) {
            store.setLowBalanceThreshold(cleanThreshold)
            _lowBalanceThreshold.value = cleanThreshold
            _electricityState.value = store.updateTargets(
                targets = cleanTargets,
                parserVersion = ElectricityParser.PARSER_VERSION,
                threshold = cleanThreshold
            )
        }
        if (cleanTargets.isEmpty()) {
            return Result.success(_electricityState.value.data ?: ElectricitySnapshot(emptyList(), emptyList(), cleanThreshold))
        }
        return if (_consentAccepted.value) {
            refresh(userInitiated = true)
        } else {
            Result.success(_electricityState.value.data ?: ElectricitySnapshot(cleanTargets, emptyList(), cleanThreshold))
        }
    }

    suspend fun refresh(userInitiated: Boolean = false): Result<ElectricitySnapshot> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            if (!_consentAccepted.value) {
                return@withContext Result.failure(IllegalStateException("需先同意共享学号说明后才可查询电费"))
            }

            val studentId = currentStudentId()
            if (studentId.isBlank()) {
                val msg = if (isDemoAccount()) {
                    "当前处于演示模式，不支持查询真实宿舍电费"
                } else {
                    "未获取到有效学号，请先完成统一身份认证登录"
                }
                if (userInitiated && !_electricityState.value.data?.targets.isNullOrEmpty()) {
                    _electricityState.value = store.commitFailure(msg, ElectricityParser.PARSER_VERSION)
                }
                return@withContext Result.failure(IllegalStateException(msg))
            }

            val currentSnapshot = store.read(ElectricityParser.PARSER_VERSION).data
            val targets = currentSnapshot?.targets.orEmpty()
            if (targets.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("请先选择要绑定的宿舍房间"))
            }

            val clientId = store.getOrCreateClientId()
            // 密钥统一由平台配置注入（Android BuildConfig / iOS Info.plist），共享层不硬编码
            val signSecret = AndroidAppConfig.electricitySignSecret

            _syncing.value = true
            try {
                val readings = ArrayList<ElectricityReading>(targets.size)
                for (target in targets) {
                    val reading = client.queryBalance(
                        target = target,
                        clientId = clientId,
                        studentId = studentId,
                        signSecret = signSecret
                    )
                    readings += reading
                }
                val committed = store.commitSuccess(
                    targets = targets,
                    readings = readings,
                    parserVersion = ElectricityParser.PARSER_VERSION
                )
                _electricityState.value = committed
                runCatching { com.buct.course.widget.WidgetProviders.refreshBalance(appContext) }
                val snap = committed.data
                    ?: return@withContext Result.failure(IllegalStateException("电费快照提交失败"))
                Result.success(snap)
            } catch (e: Throwable) {
                val message = mapErrorMessage(e)
                _electricityState.value = store.commitFailure(
                    errorMessage = message,
                    parserVersion = ElectricityParser.PARSER_VERSION
                )
                Result.failure(if (e is Exception) e else RuntimeException(message, e))
            } finally {
                _syncing.value = false
            }
        }
    }

    private fun mapErrorMessage(t: Throwable): String {
        if (t is ElectricityGatewayException) {
            return t.message?.takeIf { it.isNotBlank() } ?: "电费服务请求失败（HTTP ${t.httpCode}）"
        }
        return when (t) {
            is UnknownHostException -> "无法连接到 ele.buct.one，请检查当前网络"
            is SocketTimeoutException -> "电费查询超时，请稍后再试"
            is IOException -> t.message?.takeIf { it.isNotBlank() } ?: "网络通信异常，请稍后再试"
            else -> t.message?.takeIf { it.isNotBlank() } ?: "电费查询失败，请稍后重试"
        }
    }

    companion object {
        private const val CATALOG_ASSET_NAME = "electricity_rooms.json"
        private val STUDENT_ID_RE = Regex("^[A-Za-z0-9]{6,20}$")

        @Volatile
        private var instance: ElectricitySyncService? = null

        fun getInstance(context: Context): ElectricitySyncService {
            return instance ?: synchronized(this) {
                instance ?: ElectricitySyncService(
                    appContext = context.applicationContext,
                    store = ElectricityStore(context.applicationContext),
                    client = ElectricityClient()
                ).also { instance = it }
            }
        }
    }
}
