package com.buct.one.data.source.campusnet

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

class CampusNetGatewayTest {

    private val radJsonp =
        """jQuery123_45({"error":"ok","user_name":"20230001","online_ip":"10.20.30.40","products_name":"校园网","billing_name":"包月","sum_bytes":1073741824,"sum_seconds":7200,"user_balance":10.5,"wallet_balance":12.5,"user_charge":1.0,"online_device_total":"2","keepalive_time":60})"""

    private val homeHtml = """
        <html><body>
          <div id="w3-container">
            <ul>
              <li class="list-group-item"><label>状态</label><span>正常</span></li>
              <li class="list-group-item"><label>电子钱包</label><span>12.50</span></li>
            </ul>
            <table><tbody>
              <tr><td>-</td><td>校园网</td><td>包月</td><td>20GB</td><td>100小时</td><td>-</td><td>10.00</td><td>12.50</td></tr>
            </tbody></table>
          </div>
        </body></html>
    """.trimIndent()

    private val payHtml = """
        <html><body><table class="kv-grid-table"><tbody>
          <tr><td>-</td><td>20.00</td><td>支付宝</td><td>2026-11-01 10:00</td></tr>
        </tbody></table></body></html>
    """.trimIndent()

    private fun gateway(
        rad: String = radJsonp,
        radStatus: HttpStatusCode = HttpStatusCode.OK,
    ): CampusNetGateway {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/cgi-bin/rad_user_info" -> respond(
                    rad,
                    radStatus,
                    headersOf(HttpHeaders.ContentType, "application/javascript"),
                )
                "/site/sso" -> respond(homeHtml, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                "/log/pay" -> respond(payHtml, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                "/log/check-out" -> respond("<html></html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        return CampusNetGateway(buildHttpClient(engine), userAgent = "test-UA")
    }

    @Test
    fun fetchBillingSnapshot_mergesRadAndSelfService() = runTest {
        val snapshot = gateway().fetchBillingSnapshot("")
        assertTrue(snapshot.isOnline)
        assertEquals("20230001", snapshot.username)
        assertEquals("10.20.30.40", snapshot.onlineIp)
        assertEquals("正常", snapshot.accountStatus)
        assertEquals(12.5, snapshot.walletBalance)
        assertEquals("校园网", snapshot.productsName)
        assertEquals(1, snapshot.payRecords.size)
        assertEquals("20.00", snapshot.payRecords[0].amountText)
    }

    @Test
    fun fetchBillingSnapshot_offlineRadStillLoadsSelfServiceWithFallbackId() = runTest {
        val offlineRad = """jQuery1({"error":"not_online","online_ip":""})"""
        val snapshot = gateway(rad = offlineRad).fetchBillingSnapshot("20230001")
        assertTrue(!snapshot.isOnline)
        assertEquals("20230001", snapshot.username)
        assertEquals(12.5, snapshot.walletBalance)
    }

    @Test
    fun fetchBillingSnapshot_noDataRaises() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/cgi-bin/rad_user_info" -> respond("", HttpStatusCode.InternalServerError)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val gateway = CampusNetGateway(buildHttpClient(engine), userAgent = "test-UA")
        val result = runCatching { gateway.fetchBillingSnapshot("") }
        assertTrue(result.isFailure)
    }
}
