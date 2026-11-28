package com.buct.one.data.source.scs

import com.buct.one.data.model.ScsSnapshot
import com.buct.one.data.store.ScsSnapshotStore
import com.buct.one.platform.InMemoryKeyValueStore
import com.buct.one.platform.buildHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScsGatewayTest {

    private val userInfoJson =
        """{"code":200,"data":{"name":"张三","gh":"20230001"}}"""
    private val allScoreJson = """
        {"code":200,"data":[{"year":2025,"mc":"2025-2026","terms":[
          {"id":1,"mc":"第一学期","yearId":2025,"status":1,"totalScore":88.5,
           "studentScore":{"moral":90,"academic":88,"physical":87,"aesthetics":89,"labor":88}}]}]}
    """.trimIndent()

    /** 模拟 eapi：casLogin 换票 + 四个业务接口。 */
    private fun gateway(handler: (String) -> String): ScsGateway {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path == "/cas/opLogin" -> respond(
                    """{"code":200,"data":"https://scs.buct.edu.cn/commonLogin?ticket=ST-1"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                path == "/sys/casLogin" -> respond(
                    """{"code":200,"data":"app-token-1"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond(handler(path), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        return ScsGateway(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA")
    }

    @Test
    fun fetchFullSnapshot_parsesTermsAndUser() = runTest {
        val gateway = gateway { path ->
            when (path) {
                "/sysAccount/getLoginUserInfo" -> userInfoJson
                "/tStudent/getLoginStudentAllScore" -> allScoreJson
                else -> """{"code":200,"data":[]}"""
            }
        }
        val snapshot = gateway.fetchFullSnapshot()
        assertEquals("张三", snapshot.studentName)
        assertEquals("20230001", snapshot.studentId)
        assertEquals(1, snapshot.terms.size)
        assertEquals(88.5, snapshot.terms.first().totalScore)
    }

    @Test
    fun tokenExpiry_triggersSsoReloginOnce() = runTest {
        var apiCalls = 0
        var casLoginCalls = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path == "/cas/opLogin" -> respond(
                    """{"code":200,"data":"https://scs.buct.edu.cn/commonLogin?ticket=ST-1"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                path == "/sys/casLogin" -> {
                    casLoginCalls++
                    respond(
                        """{"code":200,"data":"app-token-$casLoginCalls"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                path == "/sysAccount/getLoginUserInfo" -> {
                    apiCalls++
                    if (apiCalls == 1) {
                        // 第一次：Token 失效
                        respond("", HttpStatusCode.Unauthorized)
                    } else {
                        respond(userInfoJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
                path == "/tStudent/getLoginStudentAllScore" ->
                    respond(allScoreJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond("""{"code":200,"data":[]}""", HttpStatusCode.OK)
            }
        }
        val gateway = ScsGateway(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA")
        val snapshot = gateway.fetchFullSnapshot()
        assertEquals("张三", snapshot.studentName)
        assertTrue(casLoginCalls >= 1)
        assertTrue(apiCalls >= 2)
    }

    @Test
    fun unauthorizedBusinessCode_raisesAuthExpired() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/cas/opLogin" -> respond("", HttpStatusCode.OK)
                else -> respond(
                    """{"code":401,"message":"登录已失效"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val gateway = ScsGateway(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA")
        val result = runCatching { gateway.fetchFullSnapshot() }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ScsAuthExpiredException)
    }

    @Test
    fun controller_restoresFromCacheAndKeepsSnapshotOnFailure() = runTest {
        val kv = InMemoryKeyValueStore()
        val store = ScsSnapshotStore(kv)
        val cached = ScsSnapshot(studentName = "缓存同学", studentId = "20230001")
        store.save(cached, capturedAt = 1_700_000_000_000L)

        val engine = MockEngine { error("offline") }
        val controller = ScsController(
            gateway = ScsGateway(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA"),
            cache = ScsSnapshotStore(kv),
        )
        assertTrue(controller.restoreFromCache())
        assertEquals("缓存同学", controller.lastSnapshot()?.studentName)

        controller.refresh()
        // 失败只改状态，旧快照必须保留
        assertTrue(controller.state.value is ScsUiState.AuthExpired || controller.state.value is ScsUiState.Failed)
        assertEquals("缓存同学", controller.lastSnapshot()?.studentName)
    }

    @Test
    fun snapshotStore_roundTripsNestedDetails() {
        val kv = InMemoryKeyValueStore()
        val store = ScsSnapshotStore(kv)
        val snapshot = ScsSnapshot(
            studentName = "李四",
            studentId = "20230002",
            terms = listOf(
                com.buct.one.data.model.ScsTermScore(
                    termId = 1,
                    yearId = 2025,
                    yearName = "2025-2026",
                    termName = "第一学期",
                    isFullYear = false,
                    status = 1,
                    totalScore = 90.0,
                    moral = 91.0,
                    academic = 92.0,
                    physical = 93.0,
                    aesthetics = 94.0,
                    labor = 95.0,
                    details = listOf(
                        com.buct.one.data.model.ScsCategoryDetail(
                            typeId = 1,
                            title = "德育",
                            basicScore = 10.0,
                            extScore = 5.0,
                            totalScore = 15.0,
                            maxTotalScore = 20.0,
                        ),
                    ),
                ),
            ),
        )
        store.save(snapshot, capturedAt = 123L)
        val loaded = store.load()
        assertEquals("李四", loaded?.studentName)
        assertEquals(1, loaded?.terms?.size)
        assertEquals("德育", loaded?.terms?.first()?.details?.first()?.title)
        assertEquals(123L, store.capturedAt())
    }
}