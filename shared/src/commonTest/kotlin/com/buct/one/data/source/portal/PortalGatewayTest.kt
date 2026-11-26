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
            val (body, status) = handler(request.url.encodedPath + "?" + (request.url.encodedQuery ?: ""))
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return PortalGateway(buildHttpClient(engine, followRedirects = false), userAgent = "test-UA")
    }

    @Test
    fun calendarRequestCarriesContextParameter() = runTest {
        var seenQuery = ""
        val gateway = gateway { query ->
            seenQuery = query
            val json = """{"wid":"1","academicYear":2026,"semester":1,"termId":"2026-1",""" +
                """"beginDate":"2026-09-01","endDate":"2027-01-15","currentTeachingWeek":12,"isCurrent":true}"""
            json to HttpStatusCode.OK
        }
        val snapshot = gateway.fetchCalendarSnapshot("2026-11-26")
        // _p 上下文参数缺失会 403，必须始终携带
        assertTrue(seenQuery.contains("_p="), seenQuery)
        assertTrue(seenQuery.contains("currentDate=2026-11-26"), seenQuery)
        assertEquals(12, snapshot.currentTeachingWeek)
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
        var callIndex = 0
        val gateway = gateway { _ ->
            callIndex++
            if (callIndex == 1) {
                """{"totalCount":"1","articles":[{"id":"a1","title":"通知","sourceOrg":"学工办",""" +
                    """"publishTimestampMs":1700000000000,"summary":"s","content":"c",""" +
                    """"publicUrl":"https://i.buct.edu.cn/a","isTop":false,"isHot":false,""" +
                    """"isNewest":true,"visitCount":"10"}]}""" to HttpStatusCode.OK
            } else {
                "" to HttpStatusCode.InternalServerError
            }
        }
        val snapshot = gateway.fetchAllNewsSnapshot()
        // 部分栏目失败不应整体失败
        assertTrue(snapshot.columns.isNotEmpty())
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
