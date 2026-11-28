package com.buct.one.data.source.portal

import com.buct.one.data.model.PortalBookItem
import com.buct.one.data.model.PortalCalendarSnapshot
import com.buct.one.data.model.PortalHolidayItem
import com.buct.one.data.model.PortalLibrarySnapshot
import com.buct.one.data.model.PortalNewsArticle
import com.buct.one.data.model.PortalNewsColumn
import com.buct.one.data.model.PortalNewsColumnPage
import com.buct.one.data.model.PortalNewsSnapshot
import com.buct.one.data.model.PortalTermInfo
import com.buct.one.data.model.RefreshState
import com.buct.one.data.store.PortalSnapshotStore
import com.buct.one.platform.InMemoryKeyValueStore
import com.buct.one.platform.buildHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalControllerTest {

    private val calendarJson =
        """{"result":"1","data":{"beginDate":"2026-09-01","endDate":"2027-01-15","jxz":12,""" +
            """"retStr":[{"xn":2026,"xq":1,"ksrq":"2026-09-01","jsrq":"2027-01-15","sfdqxq":"1","wid":"1"}]}}"""

    private val libraryJson =
        """{"result":"1","data":{"count":1,"list":[{"tsmc":"高等数学","tstxm":"A001",""" +
            """"jsrq":"2026-11-01","hsrq":"2026-12-01"}]}}"""

    private val newsJson =
        """{"result":"1","data":{"count":1,"articles":[{"id":"a1","title":"通知公告","from":"学工办",""" +
            """"publishTimestamp":"1700000000000","summary":"摘要","content":"正文",""" +
            """"linkUrl":"https://i.buct.edu.cn/a","isTop":"0","isHot":"0","isNewest":"1","visitCount":"9"}]}}"""

    private fun gateway(engine: MockEngine): PortalGateway =
        PortalGateway(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA")

    private fun okEngine(): MockEngine = MockEngine { request ->
        val body = when {
            request.url.encodedPath.contains("getJxz") -> calendarJson
            request.url.encodedPath.contains("ids/list.rst") -> "[]"
            request.url.encodedPath.contains("getYktOrBook") -> libraryJson
            else -> newsJson
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test
    fun refresh_commitsAllThreeSnapshots() = runTest {
        val kv = InMemoryKeyValueStore()
        val controller = PortalController(gateway = gateway(okEngine()), cache = PortalSnapshotStore(kv))

        controller.refresh()

        val state = controller.state.value
        assertTrue(state is PortalUiState.Loaded, state.toString())
        val content = (state as PortalUiState.Loaded).content
        assertEquals("2026-09-01", content.calendar.data?.beginDate)
        assertEquals(12, content.calendar.data?.currentTeachingWeek)
        assertEquals("高等数学", content.library.data?.books?.first()?.title)
        assertEquals(4, content.news.data?.columns?.size)
        assertEquals(RefreshState.FRESH, content.calendar.provenance.refreshState)

        // 成功路径必须落缓存，冷启动能直接还原
        val cached = PortalSnapshotStore(kv)
        assertEquals("2026-09-01", cached.loadCalendar()?.beginDate)
        assertEquals("高等数学", cached.loadLibrary()?.books?.first()?.title)
        assertTrue(cached.newsCapturedAt() > 0L)
    }

    @Test
    fun failure_keepsOldSnapshot() = runTest {
        val kv = InMemoryKeyValueStore()
        val seed = PortalSnapshotStore(kv)
        seed.saveCalendar(
            PortalCalendarSnapshot(
                currentTermId = "2026-2027-1",
                beginDate = "2026-09-01",
                endDate = "2027-01-15",
                currentTeachingWeek = 11,
                terms = listOf(
                    PortalTermInfo(
                        wid = "1",
                        academicYear = 2026,
                        semester = 1,
                        termId = "2026-2027-1",
                        beginDate = "2026-09-01",
                        endDate = "2027-01-15",
                        isCurrent = true,
                    ),
                ),
                holidays = emptyList(),
            ),
            capturedAt = 1_700_000_000_000L,
        )
        seed.saveLibrary(
            PortalLibrarySnapshot(totalCount = 1, books = listOf(PortalBookItem("缓存图书", "B1", "2026-11-01", "2026-12-01"))),
            capturedAt = 1_700_000_000_000L,
        )

        val offline = MockEngine { error("offline") }
        val controller = PortalController(gateway = gateway(offline), cache = PortalSnapshotStore(kv))
        assertTrue(controller.restoreFromCache())
        assertEquals("2026-09-01", controller.lastCalendar()?.beginDate)

        controller.refresh()

        // 铁律 1：失败永不产生新 data —— 旧快照必须原样保留
        assertEquals("2026-09-01", controller.lastCalendar()?.beginDate)
        assertEquals("缓存图书", controller.lastLibrary()?.books?.first()?.title)
        val state = controller.state.value
        assertTrue(state is PortalUiState.Loaded, state.toString())
        val content = (state as PortalUiState.Loaded).content
        assertEquals(RefreshState.FAILED_FALLBACK, content.calendar.provenance.refreshState)
        assertTrue(content.calendar.lastError != null)
    }

    @Test
    fun authExpired_isSeparateStateAndKeepsSnapshot() = runTest {
        val kv = InMemoryKeyValueStore()
        val seed = PortalSnapshotStore(kv)
        seed.saveCalendar(
            PortalCalendarSnapshot(
                currentTermId = "2026-2027-1",
                beginDate = "2026-09-01",
                endDate = "2027-01-15",
                currentTeachingWeek = 11,
                terms = emptyList(),
                holidays = listOf(PortalHolidayItem("2026-10-01", "国庆节", true, 5)),
            ),
            capturedAt = 1_700_000_000_000L,
        )

        val forbidden = MockEngine {
            respond("", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "text/html"))
        }
        val controller = PortalController(gateway = gateway(forbidden), cache = PortalSnapshotStore(kv))
        controller.restoreFromCache()

        controller.refresh()

        val state = controller.state.value
        assertTrue(state is PortalUiState.AuthExpired, state.toString())
        // 会话失效也要保留旧数据，界面可以「旧数据 + 重新登录」并排展示
        assertEquals("2026-09-01", (state as PortalUiState.AuthExpired).content.calendar.data?.beginDate)
        assertEquals("2026-09-01", controller.lastCalendar()?.beginDate)
    }

    @Test
    fun newsOnlyFailure_doesNotWipeCalendarAndLibrary() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.contains("getJxz") ->
                    respond(calendarJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.encodedPath.contains("ids/list.rst") -> respond(
                    "[]",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.encodedPath.contains("getYktOrBook") -> respond(
                    libraryJson,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("", HttpStatusCode.InternalServerError)
            }
        }
        val controller = PortalController(gateway = gateway(engine))

        controller.refresh()

        val state = controller.state.value
        assertTrue(state is PortalUiState.Loaded, state.toString())
        val content = (state as PortalUiState.Loaded).content
        assertEquals("2026-09-01", content.calendar.data?.beginDate)
        assertEquals("高等数学", content.library.data?.books?.first()?.title)
        assertNull(content.news.data)
        assertTrue(content.news.lastError != null)
    }

    @Test
    fun snapshotStore_roundTripsAllThreeSnapshots() {
        val kv = InMemoryKeyValueStore()
        val store = PortalSnapshotStore(kv)
        store.saveCalendar(
            PortalCalendarSnapshot(
                currentTermId = "2026-2027-1",
                beginDate = "2026-09-01",
                endDate = "2027-01-15",
                currentTeachingWeek = 12,
                terms = listOf(
                    PortalTermInfo("9", 2026, 1, "2026-2027-1", "2026-09-01", "2027-01-15", true),
                ),
                holidays = listOf(PortalHolidayItem("2026-10-01", "国庆节", true, 5)),
            ),
            capturedAt = 111L,
        )
        store.saveNews(
            PortalNewsSnapshot(
                columns = mapOf(
                    PortalNewsColumn.NOTICE.columnId to PortalNewsColumnPage(
                        columnId = PortalNewsColumn.NOTICE.columnId,
                        totalCount = 1,
                        articles = listOf(
                            PortalNewsArticle(
                                id = "a1",
                                columnId = PortalNewsColumn.NOTICE.columnId,
                                title = "通知",
                                sourceOrg = "学工办",
                                publishTimestampMs = 1_700_000_000_000L,
                                summary = "摘要",
                                content = "正文",
                                publicUrl = "https://i.buct.edu.cn/a",
                                isTop = false,
                                isHot = true,
                                isNewest = true,
                                visitCount = "7",
                            ),
                        ),
                    ),
                ),
            ),
            capturedAt = 222L,
        )

        val loadedCalendar = store.loadCalendar()
        assertEquals("2026-2027-1", loadedCalendar?.currentTermId)
        assertEquals("国庆节", loadedCalendar?.holidays?.first()?.holidayName)
        assertEquals(111L, store.calendarCapturedAt())
        val loadedNews = store.loadNews()
        assertEquals("通知", loadedNews?.columns?.get(PortalNewsColumn.NOTICE.columnId)?.articles?.first()?.title)
        assertEquals(222L, store.newsCapturedAt())

        store.clear()
        assertNull(store.loadCalendar())
        assertNull(store.loadNews())
        assertEquals(0L, store.newsCapturedAt())
    }

    @Test
    fun corruptedCacheIsIgnored() {
        val kv = InMemoryKeyValueStore(
            mapOf(
                PortalSnapshotStore.DEFAULT_CALENDAR_KEY to "{not json",
                PortalSnapshotStore.DEFAULT_LIBRARY_KEY to "[]",
            ),
        )
        val store = PortalSnapshotStore(kv)
        assertNull(store.loadCalendar())
        assertNull(store.loadLibrary())
    }
}
