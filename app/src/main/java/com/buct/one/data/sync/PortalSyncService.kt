package com.buct.one.data.sync

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.PortalCalendarSnapshot
import com.buct.one.data.model.PortalLibrarySnapshot
import com.buct.one.data.model.PortalNewsColumnPage
import com.buct.one.data.model.PortalNewsSnapshot
import com.buct.one.data.model.RefreshState
import com.buct.one.data.session.SidPortalSessionVault
import com.buct.one.data.source.portal.PortalClient
import com.buct.one.data.source.portal.PortalParser
import com.buct.one.data.store.PortalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * i北化官方校历（i.buct.edu.cn）同步服务。
 * 包结构约束：sync -> source, store, session, model；严禁依赖 ui。
 */
class PortalSyncService private constructor(
    private val appContext: Context,
    private val store: PortalStore,
    private val client: PortalClient
) {
    private val syncMutex = Mutex()

    @Volatile
    private var lastRestoredSidCookie: String = ""

    private val _calendarState = MutableStateFlow(store.read(PortalParser.PARSER_VERSION))
    val calendarState: StateFlow<DomainSlice<PortalCalendarSnapshot>> = _calendarState.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _libraryState = MutableStateFlow(store.readLibrary(PortalParser.PARSER_VERSION))
    val libraryState: StateFlow<DomainSlice<PortalLibrarySnapshot>> = _libraryState.asStateFlow()

    private val _librarySyncing = MutableStateFlow(false)
    val librarySyncing: StateFlow<Boolean> = _librarySyncing.asStateFlow()

    private val _newsState = MutableStateFlow(store.readNews(PortalParser.PARSER_VERSION))
    val newsState: StateFlow<DomainSlice<PortalNewsSnapshot>> = _newsState.asStateFlow()

    private val _newsSyncing = MutableStateFlow(false)
    val newsSyncing: StateFlow<Boolean> = _newsSyncing.asStateFlow()

    init {
        restoreSidPortalCookies()
    }

    fun hasSavedSsoSession(): Boolean = SidPortalSessionVault.hasAnyCookie(appContext)

    fun canAutoSync(): Boolean = hasSavedSsoSession() || SsoSilentRelogin.hasSavedCredentials(appContext)

    private fun restoreSidPortalCookies() {
        val (sidCookies, _) = SidPortalSessionVault.readCookies(appContext)
        if (sidCookies.isNotBlank() && sidCookies != lastRestoredSidCookie) {
            client.importSidPortalCookies(sidCookies)
            client.invalidateSession()
            lastRestoredSidCookie = sidCookies
        }
    }

    private fun persistSidPortalCookies() {
        val currentSid = client.exportSidPortalCookieHeader()
        if (currentSid.isNotBlank() && currentSid != lastRestoredSidCookie) {
            SidPortalSessionVault.saveSidPortalCookies(appContext, currentSid)
            lastRestoredSidCookie = currentSid
        }
    }

    private suspend fun <T> executeWithAutoRelogin(
        userInitiated: Boolean = false,
        block: suspend () -> T
    ): T {
        val sidBefore = SidPortalSessionVault.readCookies(appContext).first
        restoreSidPortalCookies()
        return try {
            block().also { persistSidPortalCookies() }
        } catch (e: Exception) {
            if (!SsoSilentRelogin.isAuthFailure(e)) throw e
            val relogined = SsoSilentRelogin.attemptRelogin(
                context = appContext,
                sidSnapshotBefore = sidBefore,
                bypassCooldown = userInitiated
            ) { username, passwordChars ->
                val ok = client.loginWithSidCredentials(username, passwordChars).isSuccess
                if (ok) persistSidPortalCookies()
                ok
            }
            if (!relogined) throw e
            restoreSidPortalCookies()
            block().also { persistSidPortalCookies() }
        }
    }

    /**
     * 刷新官方校历与节假日快照；非手动触发且 12 小时内已刷新成功时直接复用缓存。
     */
    suspend fun refreshCalendar(userInitiated: Boolean = false): Result<DomainSlice<PortalCalendarSnapshot>> =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                val cached = _calendarState.value
                val ageMs = System.currentTimeMillis() - cached.provenance.capturedAt
                if (!userInitiated &&
                    cached.provenance.refreshState == RefreshState.FRESH &&
                    cached.data?.beginDate?.isNotBlank() == true &&
                    ageMs in 0 until CACHE_TTL_MS
                ) {
                    return@withLock Result.success(cached)
                }

                _syncing.value = true
                try {
                    val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                    val snapshot = executeWithAutoRelogin(userInitiated = userInitiated) {
                        client.fetchCalendarSnapshot(todayDate)
                    }
                    val committed = store.commitSuccess(
                        snapshot = snapshot,
                        parserVersion = PortalParser.PARSER_VERSION
                    )
                    _calendarState.value = committed
                    Result.success(committed)
                } catch (e: Exception) {
                    val failed = store.commitFailure(
                        errorMessage = e.message ?: "官方校历同步失败",
                        parserVersion = PortalParser.PARSER_VERSION
                    )
                    _calendarState.value = failed
                    Result.failure(e)
                } finally {
                    _syncing.value = false
                }
            }
        }

    companion object {
        private const val CACHE_TTL_MS = 12L * 3600L * 1000L
        private const val DOMAIN_CACHE_TTL_MS = 30L * 60L * 1000L

        @Volatile
        private var instance: PortalSyncService? = null

        fun getInstance(context: Context): PortalSyncService {
            return instance ?: synchronized(this) {
                instance ?: PortalSyncService(
                    appContext = context.applicationContext,
                    store = PortalStore(context.applicationContext),
                    client = PortalClient()
                ).also { instance = it }
            }
        }
    }

    /**
     * 刷新图书馆在借清单；非手动触发且 30 分钟内已刷新成功时直接复用缓存。
     */
    suspend fun refreshLibrary(userInitiated: Boolean = false): Result<DomainSlice<PortalLibrarySnapshot>> =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                val cached = _libraryState.value
                val state = cached.provenance.refreshState
                val ageMs = System.currentTimeMillis() - cached.provenance.capturedAt
                if (!userInitiated &&
                    (state == RefreshState.FRESH || state == RefreshState.EMPTY_CONFIRMED) &&
                    cached.data != null &&
                    ageMs in 0 until DOMAIN_CACHE_TTL_MS
                ) {
                    return@withLock Result.success(cached)
                }

                _librarySyncing.value = true
                try {
                    val snapshot = executeWithAutoRelogin(userInitiated = userInitiated) {
                        client.fetchBorrowedBooks()
                    }
                    val committed = store.commitLibrarySuccess(
                        snapshot = snapshot,
                        parserVersion = PortalParser.PARSER_VERSION
                    )
                    _libraryState.value = committed
                    runCatching { com.buct.course.reminder.SmartReminderScheduler.reschedule(appContext) }
                    Result.success(committed)
                } catch (e: Exception) {
                    val failed = store.commitLibraryFailure(
                        errorMessage = e.message ?: "图书借阅同步失败",
                        parserVersion = PortalParser.PARSER_VERSION
                    )
                    _libraryState.value = failed
                    Result.failure(e)
                } finally {
                    _librarySyncing.value = false
                }
            }
        }

    /**
     * 刷新校内综合资讯四栏；非手动触发且 30 分钟内已刷新成功时直接复用缓存。
     */
    suspend fun refreshNews(userInitiated: Boolean = false): Result<DomainSlice<PortalNewsSnapshot>> =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                val cached = _newsState.value
                val state = cached.provenance.refreshState
                val ageMs = System.currentTimeMillis() - cached.provenance.capturedAt
                if (!userInitiated &&
                    (state == RefreshState.FRESH || state == RefreshState.EMPTY_CONFIRMED) &&
                    cached.data != null &&
                    ageMs in 0 until DOMAIN_CACHE_TTL_MS
                ) {
                    return@withLock Result.success(cached)
                }

                _newsSyncing.value = true
                try {
                    val snapshot = executeWithAutoRelogin(userInitiated = userInitiated) {
                        client.fetchAllNewsSnapshot()
                    }
                    val committed = store.commitNewsSuccess(
                        snapshot = snapshot,
                        parserVersion = PortalParser.PARSER_VERSION
                    )
                    _newsState.value = committed
                    Result.success(committed)
                } catch (e: Exception) {
                    val failed = store.commitNewsFailure(
                        errorMessage = e.message ?: "校内资讯同步失败",
                        parserVersion = PortalParser.PARSER_VERSION
                    )
                    _newsState.value = failed
                    Result.failure(e)
                } finally {
                    _newsSyncing.value = false
                }
            }
        }

    suspend fun loadMoreNewsColumn(
        columnId: String,
        beginIndex: Int,
        pageSize: Int = 15
    ): Result<PortalNewsColumnPage> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val page = executeWithAutoRelogin(userInitiated = true) {
                    client.fetchNewsColumn(columnId = columnId, beginIndex = beginIndex, pageSize = pageSize)
                }
                Result.success(page)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 原生 sidportal 登录后同时刷新图书在借与校内资讯。
     */
    suspend fun loginAndSyncPortal(
        username: String,
        passwordChars: CharArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _librarySyncing.value = true
            _newsSyncing.value = true
            try {
                val loginRes = client.loginWithSidCredentials(username, passwordChars)
                if (loginRes.isFailure) {
                    val err = loginRes.exceptionOrNull() ?: IllegalStateException("统一身份认证登录失败")
                    val msg = err.message ?: "统一身份认证登录失败"
                    _libraryState.value = store.commitLibraryFailure(msg, PortalParser.PARSER_VERSION)
                    _newsState.value = store.commitNewsFailure(msg, PortalParser.PARSER_VERSION)
                    return@withLock Result.failure(err)
                }
                SsoSilentRelogin.resetCooldown()
                persistSidPortalCookies()

                val libRes = runCatching { client.fetchBorrowedBooks() }
                libRes.onSuccess { snap ->
                    _libraryState.value = store.commitLibrarySuccess(snap, PortalParser.PARSER_VERSION)
                    runCatching { com.buct.course.reminder.SmartReminderScheduler.reschedule(appContext) }
                }.onFailure { err ->
                    _libraryState.value = store.commitLibraryFailure(
                        err.message ?: "图书借阅同步失败",
                        PortalParser.PARSER_VERSION
                    )
                }

                val newsRes = runCatching { client.fetchAllNewsSnapshot() }
                newsRes.onSuccess { snap ->
                    _newsState.value = store.commitNewsSuccess(snap, PortalParser.PARSER_VERSION)
                }.onFailure { err ->
                    _newsState.value = store.commitNewsFailure(
                        err.message ?: "校内资讯同步失败",
                        PortalParser.PARSER_VERSION
                    )
                }

                if (libRes.isFailure && newsRes.isFailure) {
                    Result.failure(libRes.exceptionOrNull() ?: newsRes.exceptionOrNull() ?: IllegalStateException("同步失败"))
                } else {
                    Result.success(Unit)
                }
            } finally {
                passwordChars.fill('\u0000')
                _librarySyncing.value = false
                _newsSyncing.value = false
            }
        }
    }
}