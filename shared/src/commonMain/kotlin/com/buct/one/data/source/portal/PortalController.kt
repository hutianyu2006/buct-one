package com.buct.one.data.source.portal

import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.PortalCalendarSnapshot
import com.buct.one.data.model.PortalLibrarySnapshot
import com.buct.one.data.model.PortalNewsColumnPage
import com.buct.one.data.model.PortalNewsSnapshot
import com.buct.one.data.store.PortalSnapshotStore
import com.buct.one.data.sync.DomainSync
import com.buct.one.data.sync.ReloginWrapper
import com.buct.one.data.text.formatDateTime
import com.buct.one.shared.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * i北化三份快照（校历 / 在借图书 / 校内资讯）的组合内容。
 *
 * 每份都是 [DomainSlice]：失败时 data 原样保留、只改 refreshState 与 lastError，
 * 界面据此区分「显示的是上次数据」与「服务端确认没有数据」（NEVER != EMPTY_CONFIRMED）。
 */
data class PortalContent(
    val calendar: DomainSlice<PortalCalendarSnapshot>,
    val library: DomainSlice<PortalLibrarySnapshot>,
    val news: DomainSlice<PortalNewsSnapshot>,
) {
    val isEmpty: Boolean
        get() = calendar.data == null && library.data == null && news.data == null
}

/** i北化页面状态：会话失效单独成态，失败一律保留旧快照。 */
sealed interface PortalUiState {
    data object Idle : PortalUiState

    /** 刷新中；携带上一次内容，避免刷新时整页空白。 */
    data class Loading(val content: PortalContent) : PortalUiState

    data class Loaded(val content: PortalContent) : PortalUiState

    /** 统一认证会话失效（三份快照同时受影响），旧数据仍可展示。 */
    data class AuthExpired(val message: String, val content: PortalContent) : PortalUiState

    /** 从未同步成功且本次也失败（NEVER 状态下的失败），没有可展示的旧数据。 */
    data class Failed(val message: String, val content: PortalContent) : PortalUiState
}

/**
 * i北化（信息门户 + 校内资讯）状态机，双端共用。
 *
 * 组合三个 [DomainSync]（校历 / 在借图书 / 四栏资讯）+ [PortalSnapshotStore]（冷启动缓存）：
 * - **失败永不产生新 data**：只有成功路径才写 sync 与缓存；
 * - **会话失效单独成态**：任一快照抛 [PortalAuthExpiredException] 即整页转 [PortalUiState.AuthExpired]，
 *   并停止继续请求（不刷屏打认证服务）；
 * - **部分失败不算整体失败**：单个接口失败只影响自己那份快照，其余照常刷新。
 *
 * 会话续期由调用方通过 [withRelogin] 注入（iOS 用 Keychain 里的密码自动重登一次）。
 */
class PortalController(
    private val gateway: PortalGateway,
    private val cache: PortalSnapshotStore? = null,
    /**
     * 会话失效时的静默重登包装，语义与其它域 Controller 一致。
     * 门户有三份不同类型的快照，这里用 `Any?` 承载结果，取回后再转成具体快照类型。
     */
    private val withRelogin: ReloginWrapper<Any?> = ReloginWrapper.passthrough(),
    private val clock: () -> Long = { nowMillis() },
) {

    private val calendarSync = DomainSync<PortalCalendarSnapshot>(
        source = PortalGateway.PORTAL_SOURCE_ID,
        parserVersion = PortalParser.PARSER_VERSION,
        isEmpty = { it.beginDate.isBlank() && it.terms.isEmpty() },
        clock = clock,
    )

    private val librarySync = DomainSync<PortalLibrarySnapshot>(
        source = PortalGateway.PORTAL_SOURCE_ID,
        parserVersion = PortalParser.PARSER_VERSION,
        isEmpty = { it.totalCount == 0 && it.books.isEmpty() },
        clock = clock,
    )

    private val newsSync = DomainSync<PortalNewsSnapshot>(
        source = PortalGateway.MNEWS_SOURCE_ID,
        parserVersion = PortalParser.PARSER_VERSION,
        isEmpty = { snapshot -> snapshot.columns.values.all { it.articles.isEmpty() } },
        clock = clock,
    )

    private val _state = MutableStateFlow<PortalUiState>(PortalUiState.Idle)
    val state: StateFlow<PortalUiState> = _state.asStateFlow()

    fun currentContent(): PortalContent = PortalContent(
        calendar = calendarSync.current(),
        library = librarySync.current(),
        news = newsSync.current(),
    )

    fun lastCalendar(): PortalCalendarSnapshot? = calendarSync.data()

    fun lastLibrary(): PortalLibrarySnapshot? = librarySync.data()

    fun lastNews(): PortalNewsSnapshot? = newsSync.data()

    /** 冷启动先用缓存渲染（不产生网络请求）；返回是否命中任意一份缓存。 */
    fun restoreFromCache(): Boolean {
        val store = cache ?: return false
        var hit = false
        store.loadCalendar()?.let { snapshot ->
            calendarSync.restoreFromCache(snapshot, store.calendarCapturedAt())
            hit = true
        }
        store.loadLibrary()?.let { snapshot ->
            librarySync.restoreFromCache(snapshot, store.libraryCapturedAt())
            hit = true
        }
        store.loadNews()?.let { snapshot ->
            newsSync.restoreFromCache(snapshot, store.newsCapturedAt())
            hit = true
        }
        if (hit && _state.value is PortalUiState.Idle) {
            _state.value = PortalUiState.Loaded(currentContent())
        }
        return hit
    }

    /**
     * 依次刷新三份快照。任一快照会话失效即停止后续请求并转 [PortalUiState.AuthExpired]；
     * 其余失败只影响自己那份数据，旧快照原样保留。
     */
    suspend fun refresh() {
        _state.value = PortalUiState.Loading(currentContent())
        var authMessage: String? = null
        val failures = mutableListOf<String>()

        // 会话一旦失效就不再打后续认证服务，避免连撞 403 / 登录页
        suspend fun step(fallback: String, onFailure: (String) -> Unit, block: suspend () -> Unit) {
            if (authMessage != null) return
            try {
                block()
            } catch (expired: PortalAuthExpiredException) {
                authMessage = expired.message?.takeIf { it.isNotBlank() } ?: DEFAULT_AUTH_EXPIRED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.message?.takeIf { it.isNotBlank() } ?: fallback
                onFailure(message)
                failures += message
            }
        }

        step("官方校历获取失败", { message -> calendarSync.commitFailure(message) }) {
            val snapshot = withRelogin.fetch { gateway.fetchCalendarSnapshot(todayDate()) } as PortalCalendarSnapshot
            val slice = calendarSync.commitSuccess(snapshot)
            cache?.saveCalendar(snapshot, slice.provenance.capturedAt)
        }

        step("图书借阅获取失败", { message -> librarySync.commitFailure(message) }) {
            val snapshot = withRelogin.fetch { gateway.fetchBorrowedBooks() } as PortalLibrarySnapshot
            val slice = librarySync.commitSuccess(snapshot)
            cache?.saveLibrary(snapshot, slice.provenance.capturedAt)
        }

        step("校内资讯获取失败", { message -> newsSync.commitFailure(message) }) {
            val snapshot = withRelogin.fetch { gateway.fetchAllNewsSnapshot() } as PortalNewsSnapshot
            val slice = newsSync.commitSuccess(snapshot)
            cache?.saveNews(snapshot, slice.provenance.capturedAt)
        }

        val content = currentContent()
        val expired = authMessage
        _state.value = when {
            expired != null -> PortalUiState.AuthExpired(expired, content)
            content.isEmpty && failures.isNotEmpty() -> PortalUiState.Failed(failures.first(), content)
            else -> PortalUiState.Loaded(content)
        }
    }

    /** 单个资讯栏目翻页（「加载更多」）：只更新该栏目的缓存与状态，失败不动旧数据。 */
    suspend fun loadMoreNews(columnId: String, beginIndex: Int, pageSize: Int = 15): Result<PortalNewsColumnPage> = try {
        val page = withRelogin.fetch { gateway.fetchNewsColumn(columnId, beginIndex, pageSize) } as PortalNewsColumnPage
        val merged = LinkedHashMap(newsSync.data()?.columns.orEmpty())
        val previous = merged[columnId]
        val known = previous?.articles.orEmpty().mapTo(HashSet()) { it.id }
        val appended = previous?.articles.orEmpty() + page.articles.filter { it.id !in known }
        merged[columnId] = page.copy(articles = appended)
        val snapshot = PortalNewsSnapshot(columns = merged)
        val slice = newsSync.commitSuccess(snapshot)
        cache?.saveNews(snapshot, slice.provenance.capturedAt)
        _state.value = PortalUiState.Loaded(currentContent())
        Result.success(page)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    fun reset() {
        _state.value = PortalUiState.Idle
        calendarSync.reset()
        librarySync.reset()
        newsSync.reset()
    }

    /** 校历接口的 `currentDate` 必须是 yyyy-MM-dd（按北京时间）。 */
    private fun todayDate(): String = formatDateTime(clock()).take(10)

    private companion object {
        const val DEFAULT_AUTH_EXPIRED = "信息门户会话已失效，请重新登录"
    }
}