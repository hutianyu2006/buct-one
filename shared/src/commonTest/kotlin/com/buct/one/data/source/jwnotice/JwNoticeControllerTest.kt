package com.buct.one.data.source.jwnotice

import com.buct.one.data.model.JwAnnouncementItem
import com.buct.one.data.model.JwNoticeSnapshot
import com.buct.one.data.model.JwReminderCategory
import com.buct.one.data.model.JwReminderItem
import com.buct.one.data.store.JwNoticeSnapshotStore
import com.buct.one.data.sync.ReloginWrapper
import com.buct.one.data.sync.SsoCredentials
import com.buct.one.data.sync.SsoReloginRunner
import com.buct.one.data.sync.SsoSilentReloginCoordinator
import com.buct.one.platform.InMemoryKeyValueStore
import com.buct.one.platform.buildHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JwNoticeControllerTest {

    private val remindersJson =
        """{"totalResult":"1","items":[{"xxbt":"停课提醒","xxnr":"周三停课","cjsj":"2026-11-20 10:00"}]}"""
    private val announcementsJson =
        """{"totalResult":"1","items":[{"xwbh":"1","xwbt":"选课通知","fbr":"教务处","fbsj":"2026-11-19"}]}"""

    private fun controller(
        reminders: String = remindersJson,
        announcements: String = announcementsJson,
    ): JwNoticeController {
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath.contains("cxDbsy")) reminders else announcements
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return JwNoticeController(JwNoticeGateway(buildHttpClient(engine), userAgent = "test-UA"))
    }

    @Test
    fun refresh_loadsSnapshot() = runTest {
        val controller = controller()
        controller.refresh()
        val state = controller.state.value
        assertTrue(state is JwNoticeUiState.Loaded)
        val snapshot = (state as JwNoticeUiState.Loaded).snapshot
        assertEquals(1, snapshot.reminders.size)
        assertEquals(1, snapshot.announcements.size)
        assertEquals("停课提醒", snapshot.reminders[0].title)
    }

    @Test
    fun refresh_authExpired() = runTest {
        val controller = controller(reminders = "<html><body><p id=\"login-page-flowkey\">x</p></body></html>")
        controller.refresh()
        assertTrue(controller.state.value is JwNoticeUiState.AuthExpired)
    }

    @Test
    fun restoreFromCache_rendersCachedSnapshotWithoutNetwork() = runTest {
        val cached = JwNoticeSnapshot(
            reminders = listOf(
                JwReminderItem("r1", "缓存提醒", "内容", "2026-11-20", false, JwReminderCategory.OTHER),
            ),
            reminderTotal = 1,
            announcements = listOf(
                JwAnnouncementItem("a1", "缓存公告", "教务处", "2026-11-19", false, false, "", ""),
            ),
            announcementTotal = 1,
        )
        val kv = InMemoryKeyValueStore()
        JwNoticeSnapshotStore(kv).save(cached)
        val failing = JwNoticeController(
            JwNoticeGateway(buildHttpClient(MockEngine { error("offline") }), userAgent = "test-UA"),
            cache = JwNoticeSnapshotStore(kv),
        )
        assertTrue(failing.restoreFromCache())
        val state = failing.state.value
        assertTrue(state is JwNoticeUiState.Loaded)
        assertEquals(1, (state as JwNoticeUiState.Loaded).snapshot.reminders.size)
        assertEquals("缓存提醒", state.snapshot.reminders[0].title)
    }

    @Test
    fun refresh_usesReloginWrapperOnceThenSucceeds() = runTest {
        var wrapperCalls = 0
        var engineCalls = 0
        var loginCalls = 0
        val engine = MockEngine { request ->
            engineCalls++
            if (engineCalls == 1) {
                // 第一次请求：会话已失效（登录页 HTML）
                respond(
                    "<html><body><p id=\"login-page-flowkey\">x</p></body></html>",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                val body = if (request.url.encodedPath.contains("cxDbsy")) remindersJson else announcementsJson
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val coordinator = SsoSilentReloginCoordinator(
            credentialsProvider = { SsoCredentials("20230001", "p@ss".toCharArray()) },
        )
        val runner = SsoReloginRunner(coordinator) { _, _ -> loginCalls++; true }
        val controller = JwNoticeController(
            JwNoticeGateway(buildHttpClient(engine), userAgent = "test-UA"),
            withRelogin = ReloginWrapper { block ->
                wrapperCalls++
                runner.run { block() }
            },
        )
        controller.refresh()
        assertTrue(controller.state.value is JwNoticeUiState.Loaded)
        assertEquals(1, wrapperCalls)
        // 会话失效 → 静默重登一次 → 重试成功（不再落到 AuthExpired）
        assertEquals(1, loginCalls)
        assertTrue(engineCalls >= 3, "expected retry, got $engineCalls requests")
    }

    @Test
    fun refresh_reloginWrapperFailure_fallsBackToAuthExpired() = runTest {
        val controller = JwNoticeController(
            JwNoticeGateway(
                buildHttpClient(MockEngine { error("expired") }),
                userAgent = "test-UA",
            ),
            withRelogin = ReloginWrapper { throw JwAuthExpiredException("教务会话已失效，请重新登录") },
        )
        controller.refresh()
        val state = controller.state.value as? JwNoticeUiState.AuthExpired
        assertTrue(state != null)
        assertEquals("教务会话已失效，请重新登录", state!!.message)
    }

    @Test
    fun refresh_networkFailureKeepsPreviousSnapshotAvailable() = runTest {
        val controller = controller()
        controller.refresh()
        assertTrue(controller.lastSnapshot() != null)

        // 之后请求全部抛异常：状态变为 Failed，但 lastSnapshot 仍可用于界面保留旧数据
        val failingEngine = MockEngine { error("network down") }
        val failing = JwNoticeController(JwNoticeGateway(buildHttpClient(failingEngine), userAgent = "test-UA"))
        failing.refresh()
        assertTrue(failing.state.value is JwNoticeUiState.Failed)
        assertEquals(null, failing.lastSnapshot())
    }
}