package com.buct.one.data.source.portal

import com.buct.one.platform.buildHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PortalGatewayTest {

    private fun gateway(handler: (String) -> Pair<String, HttpStatusCode>): PortalGateway {
        val engine = MockEngine { request ->
            val (body, status) = handler(request.url.encodedPath + "?" + request.url.encodedQuery.orEmpty())
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return PortalGateway(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA")
    }

    @Test
    fun calendarRequestCarriesContextParameter() = runTest {
        val seenQueries = mutableListOf<String>()
        val engine = MockEngine { request ->
            val query = request.url.encodedQuery.orEmpty()
            seenQueries += query
            // 校历接口的真实响应是 {result, data{beginDate,endDate,jxz,retStr}}，
            // 解析器按 result=1 判定成功，缺字段会抛异常。
            val body = if (request.url.encodedPath.contains("getJxz")) {
                """{"result":"1","data":{"beginDate":"2026-09-01","endDate":"2027-01-15","jxz":12,""" +
                    """"retStr":[{"xn":2026,"xq":1,"ksrq":"2026-09-01","jsrq":"2027-01-15","sfdqxq":"1","wid":"1"}]}}"""
            } else {
                """{"result":"1","data":{"data":[]}}"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val gateway = PortalGateway(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA")
        gateway.fetchCalendarSnapshot("2026-11-26")
        // _p 上下文参数缺失会 403，必须始终携带
        assertTrue(seenQueries.all { it.contains("_p=") }, seenQueries.toString())
        assertTrue(seenQueries.first().contains("currentDate=2026-11-26"), seenQueries.toString())
    }

    @Test
    fun forbiddenRaisesAuthExpired() = runTest {
        val gateway = gateway { "" to HttpStatusCode.Forbidden }
        assertFailsWith<PortalAuthExpiredException> {
            gateway.fetchBorrowedBooks()
        }
    }

    @Test
    fun loginPageRaisesAuthExpired() = runTest {
        val gateway = gateway {
            "<html><p id=\"login-page-flowkey\">x</p></html>" to HttpStatusCode.OK
        }
        assertFailsWith<PortalAuthExpiredException> {
            gateway.fetchBorrowedBooks()
        }
    }

    @Test
    fun newsColumnsAggregateAndToleratePartialFailure() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            val columnId = request.url.parameters["columnId"].orEmpty()
            if (columnId == com.buct.one.data.model.PortalNewsColumn.NOTICE.columnId) {
                respond(
                    """{"result":"1","data":{"count":"1","articles":[{"id":"a1","title":"通知",""" +
                        """"from":"学工办","publishTimestamp":"1700000000000","summary":"s","content":"c",""" +
                        """"linkUrl":"https://i.buct.edu.cn/a","isTop":"0","isHot":"0",""" +
                        """"isNewest":"1","visitCount":"10"}]}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond("", HttpStatusCode.InternalServerError)
            }
        }
        val gateway = PortalGateway(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA")
        val snapshot = gateway.fetchAllNewsSnapshot()
        // 部分栏目失败不应整体失败
        assertTrue(snapshot.columns.isNotEmpty())
        assertTrue(calls >= 2)
    }

    @Test
    fun allNewsColumnsFailingPropagates() = runTest {
        val gateway = gateway { "" to HttpStatusCode.InternalServerError }
        val result = runCatching { gateway.fetchAllNewsSnapshot() }
        assertTrue(result.isFailure)
    }

    @Test
    fun foreignHostRedirectRejected() = runTest {
        val engine = MockEngine {
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location to listOf("https://evil.example.com/x")),
            )
        }
        val gateway = PortalGateway(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA")
        assertFailsWith<PortalAuthExpiredException> {
            gateway.fetchBorrowedBooks()
        }
    }
}
