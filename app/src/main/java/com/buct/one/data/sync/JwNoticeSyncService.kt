package com.buct.one.data.sync

import android.content.Context
import com.buct.course.login.AndroidHttpClients
import com.buct.course.manager.UserManager
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.JwAnnouncementDetail
import com.buct.one.data.model.JwAnnouncementItem
import com.buct.one.data.model.JwNoticeSnapshot
import com.buct.one.data.model.JwReminderItem
import com.buct.one.data.model.RefreshState
import com.buct.one.data.session.SidPortalSessionVault
import com.buct.one.data.source.jwnotice.JwNoticeClient
import com.buct.one.data.source.jwnotice.JwNoticeGateway
import com.buct.one.data.source.jwnotice.JwNoticeParser
import com.buct.one.data.store.JwNoticeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 教务系统消息（调停课提醒）与通知公告（jwglxt.buct.edu.cn）同步服务。
 * 包结构约束：sync -> source, store, session, model；严禁依赖 ui。
 */
class JwNoticeSyncService private constructor(
    private val appContext: Context,
    private val store: JwNoticeStore,
    private val client: JwNoticeClient
) {
    private val syncMutex = Mutex()

    @Volatile
    private var lastRestoredSidCookie: String = ""

    @Volatile
    private var lastImportedJwCookie: String = ""

    private val _noticeState = MutableStateFlow(store.read(JwNoticeParser.PARSER_VERSION))
    val noticeState: StateFlow<DomainSlice<JwNoticeSnapshot>> = _noticeState.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    init {
        restoreSessionCookies()
    }

    private fun currentJwCookie(): String {
        val user = UserManager.getInstance().apply { init(appContext) }
        if (user.isDemoMode) return ""
        if (user.currentSchool?.id != "buct") return ""
        return user.savedCookie.orEmpty()
    }

    fun hasSavedSession(): Boolean =
        currentJwCookie().isNotBlank() || SidPortalSessionVault.hasAnyCookie(appContext)

    fun canAutoSync(): Boolean =
        hasSavedSession() || SsoSilentRelogin.hasSavedCredentials(appContext)

    private fun restoreSessionCookies() {
        val (sidCookies, _) = SidPortalSessionVault.readCookies(appContext)
        if (sidCookies.isNotBlank() && sidCookies != lastRestoredSidCookie) {
            client.importSidPortalCookies(sidCookies)
            client.invalidateSession()
            lastRestoredSidCookie = sidCookies
        }
        val jwCookie = currentJwCookie()
        if (jwCookie.isNotBlank() && jwCookie != lastImportedJwCookie) {
            client.importJwCookies(jwCookie)
            lastImportedJwCookie = jwCookie
        }
    }

    /**
     * 构造共享层网关：把 OkHttp 会话 Cookie 种入共享 CookieStore，
     * 之后教务消息的请求/解析全部走 `JwNoticeGateway`（与 iOS 同一份实现）。
     */
    private fun sharedGateway(): JwNoticeGateway {
        AndroidHttpClients.seedCookies("jwglxt.buct.edu.cn", client.exportJwCookieHeader())
        return JwNoticeGateway(AndroidHttpClients.session, userAgent = client.userAgent())
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
        restoreSessionCookies()
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
            restoreSessionCookies()
            block().also { persistSidPortalCookies() }
        }
    }

    /**
     * 刷新首屏教务消息与教务通知公告；非手动触发且 30 分钟内已 FRESH 时直接复用缓存。
     */
    suspend fun refresh(userInitiated: Boolean = false): Result<DomainSlice<JwNoticeSnapshot>> =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                val cached = _noticeState.value
                val ageMs = System.currentTimeMillis() - cached.provenance.capturedAt
                if (!userInitiated &&
                    cached.provenance.refreshState == RefreshState.FRESH &&
                    cached.data != null &&
                    ageMs in 0 until CACHE_TTL_MS
                ) {
                    return@withLock Result.success(cached)
                }

                _syncing.value = true
                try {
                    val snapshot = executeWithAutoRelogin(userInitiated = userInitiated) {
                        sharedGateway().fetchFullSnapshot()
                    }
                    val committed = store.commitSuccess(
                        snapshot = snapshot,
                        parserVersion = JwNoticeParser.PARSER_VERSION
                    )
                    _noticeState.value = committed
                    Result.success(committed)
                } catch (e: Exception) {
                    val failed = store.commitFailure(
                        errorMessage = e.message ?: "获取教务消息与公告失败",
                        parserVersion = JwNoticeParser.PARSER_VERSION
                    )
                    _noticeState.value = failed
                    Result.failure(e)
                } finally {
                    _syncing.value = false
                }
            }
        }

    suspend fun loadMoreReminders(
        page: Int,
        keyword: String = ""
    ): Result<Pair<Int, List<JwReminderItem>>> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val (total, items) = executeWithAutoRelogin(userInitiated = true) {
                    sharedGateway().fetchRemindersPage(page = page, keyword = keyword)
                }
                Result.success(total to store.applyReadToReminders(items, JwNoticeParser.PARSER_VERSION))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun loadMoreAnnouncements(
        page: Int,
        keyword: String = ""
    ): Result<Pair<Int, List<JwAnnouncementItem>>> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val (total, items) = executeWithAutoRelogin(userInitiated = true) {
                    sharedGateway().fetchAnnouncementsPage(page = page, keyword = keyword)
                }
                Result.success(total to store.applyReadToAnnouncements(items, JwNoticeParser.PARSER_VERSION))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun fetchAnnouncementDetail(item: JwAnnouncementItem): Result<JwAnnouncementDetail> =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                try {
                    val detail = executeWithAutoRelogin(userInitiated = true) {
                        sharedGateway().fetchAnnouncementDetail(item)
                    }
                    _noticeState.value = store.markAnnouncementRead(item.id, JwNoticeParser.PARSER_VERSION)
                    Result.success(detail)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }

    fun markReminderRead(id: String) {
        _noticeState.value = store.markReminderRead(id, JwNoticeParser.PARSER_VERSION)
    }

    fun markAllRemindersRead() {
        _noticeState.value = store.markAllRemindersRead(JwNoticeParser.PARSER_VERSION)
    }

    fun markAnnouncementRead(id: String) {
        _noticeState.value = store.markAnnouncementRead(id, JwNoticeParser.PARSER_VERSION)
    }

    suspend fun loginAndSync(
        username: String,
        passwordChars: CharArray
    ): Result<DomainSlice<JwNoticeSnapshot>> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _syncing.value = true
            try {
                val loginRes = client.loginWithSidCredentials(username, passwordChars)
                if (loginRes.isFailure) {
                    val msg = loginRes.exceptionOrNull()?.message ?: "统一身份认证登录失败"
                    val failed = store.commitFailure(msg, JwNoticeParser.PARSER_VERSION)
                    _noticeState.value = failed
                    return@withContext Result.failure(loginRes.exceptionOrNull() ?: IllegalStateException(msg))
                }
                SsoSilentRelogin.resetCooldown()
                persistSidPortalCookies()
                val snapshot = sharedGateway().fetchFullSnapshot()
                val committed = store.commitSuccess(
                    snapshot = snapshot,
                    parserVersion = JwNoticeParser.PARSER_VERSION
                )
                _noticeState.value = committed
                Result.success(committed)
            } catch (e: Exception) {
                val failed = store.commitFailure(
                    errorMessage = e.message ?: "获取教务消息与公告失败",
                    parserVersion = JwNoticeParser.PARSER_VERSION
                )
                _noticeState.value = failed
                Result.failure(e)
            } finally {
                passwordChars.fill('\u0000')
                _syncing.value = false
            }
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 30L * 60L * 1000L

        @Volatile
        private var instance: JwNoticeSyncService? = null

        fun getInstance(context: Context): JwNoticeSyncService {
            return instance ?: synchronized(this) {
                instance ?: JwNoticeSyncService(
                    appContext = context.applicationContext,
                    store = JwNoticeStore(context.applicationContext),
                    client = JwNoticeClient()
                ).also { instance = it }
            }
        }
    }
}
