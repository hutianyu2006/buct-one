package com.buct.one.data.source.campusnet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampusNetParserTest {

    @Test
    fun parseUserInfo_handlesOnlineAndOfflineJsonp() {
        val onlineRaw = """
            cb({"billing_name":"60元封顶不计费","error":"ok","online_device_total":"2","online_ip":"10.0.0.12","products_name":"50GB免费流量","sum_bytes":157000000000,"all_bytes":5200000000,"sum_seconds":7200,"user_balance":12.5,"user_charge":45.0,"user_name":"2099000001"})
        """.trimIndent()
        val online = CampusNetParser.parseUserInfo(onlineRaw)
        assertTrue(online.isOnline)
        assertEquals("2099000001", online.username)
        assertEquals("10.0.0.12", online.onlineIp)
        assertEquals("50GB免费流量", online.productsName)
        assertEquals(2, online.onlineDeviceTotal)
        assertEquals(12.5, online.userBalance, 0.001)
        assertEquals(45.0, online.userCharge, 0.001)

        val offlineRaw = """cb({"error":"not_online_error","client_ip":"10.0.0.99"})"""
        val offline = CampusNetParser.parseUserInfo(offlineRaw)
        assertFalse(offline.isOnline)
        assertEquals("10.0.0.99", offline.onlineIp)
    }

    @Test
    fun parseSelfServiceHome_extractsWalletDevicesAndBilling() {
        val html = """
            <html><body>
              <ul class="list-group">
                <li class="list-group-item"><label class="list-group-label">姓名</label>测试同学</li>
                <li class="list-group-item"><label class="list-group-label">状态</label><a class="btn btn-xs btn-success">正常</a></li>
                <li class="list-group-item"><label class="list-group-label">电子钱包</label>20.50<a class="btn" href="/home/deposit">产品充值</a></li>
              </ul>
              <div id="w1-container">
                <table class="kv-grid-table"><tbody>
                  <tr><td data-col-seq="0">2099000001</td><td data-col-seq="1">10.0.0.12</td><td data-col-seq="3">2026-11-23 09:00:00</td><td data-col-seq="11">60元封顶不计费</td></tr>
                </tbody></table>
              </div>
              <div id="w3-container">
                <table class="kv-grid-table"><tbody>
                  <tr>
                    <td data-col-seq="0">44</td>
                    <td data-col-seq="1">50GB免费流量</td>
                    <td data-col-seq="2">教学无线网/50GB免费流量/0.0005元每M/60元封顶不计费</td>
                    <td data-col-seq="3">120.500G</td>
                    <td data-col-seq="4">88时12分05秒</td>
                    <td data-col-seq="5">9</td>
                    <td data-col-seq="6">32.40</td>
                    <td data-col-seq="7">17.60</td>
                  </tr>
                </tbody></table>
              </div>
            </body></html>
        """.trimIndent()

        val parsed = CampusNetParser.parseSelfServiceHome(html)
        assertNotNull(parsed)
        assertEquals("正常", parsed!!.accountStatus)
        assertEquals(20.5, parsed.walletBalance!!, 0.001)
        assertEquals("50GB免费流量", parsed.productsName)
        assertEquals("120.500G", parsed.trafficText)
        assertEquals("88时12分05秒", parsed.durationText)
        assertEquals(32.4, parsed.userCharge!!, 0.001)
        assertEquals(17.6, parsed.productBalance!!, 0.001)
        assertEquals(1, parsed.onlineDevices.size)
        assertEquals("10.0.0.12", parsed.onlineDevices[0].ip)

        assertNull(CampusNetParser.parseSelfServiceHome("<html><title>登录</title></html>"))
    }

    @Test
    fun parsePayAndCheckoutRecords_extractsRows() {
        val payHtml = """
            <table class="kv-grid-table"><tbody>
              <tr><td data-col-seq="1">2099000001</td><td data-col-seq="2">60.00</td><td data-col-seq="4">一卡通(card)</td><td data-col-seq="6">2026-11-02 08:00:00</td></tr>
            </tbody></table>
        """.trimIndent()
        val payRows = CampusNetParser.parsePayRecords(payHtml)
        assertEquals(1, payRows.size)
        assertEquals("60.00", payRows[0].amountText)
        assertEquals("一卡通(card)", payRows[0].payType)
        assertEquals("2026-11-02 08:00:00", payRows[0].payTime)

        val checkoutHtml = """
            <table class="kv-grid-table"><tbody>
              <tr>
                <td data-col-seq="1">2099000001</td>
                <td data-col-seq="2">0.00</td>
                <td data-col-seq="3">42.50</td>
                <td data-col-seq="4">50GB免费流量</td>
                <td data-col-seq="6" style="display:none;">135000000000</td>
                <td data-col-seq="7">135.000G</td>
                <td data-col-seq="8" style="display:none;">360000</td>
                <td data-col-seq="9">100时00分00秒</td>
                <td data-col-seq="12">2026-11-01 00:03</td>
              </tr>
            </tbody></table>
        """.trimIndent()
        val checkoutRows = CampusNetParser.parseCheckoutRecords(checkoutHtml)
        assertEquals(1, checkoutRows.size)
        assertEquals("42.50", checkoutRows[0].feeText)
        assertEquals("50GB免费流量", checkoutRows[0].productName)
        assertEquals("135.000G", checkoutRows[0].trafficText)
        assertEquals("2026-11-01 00:03", checkoutRows[0].checkoutTime)

        val merged = CampusNetParser.mergeSnapshot(
            rad = null,
            home = CampusNetParser.parseSelfServiceHome(
                """<ul class="list-group"><li class="list-group-item"><label>电子钱包</label>20.50</li></ul><div id="w3-container"></div>"""
            ),
            payRecords = payRows,
            checkoutRecords = checkoutRows,
            fallbackUsername = "2099000001"
        )
        assertEquals("2099000001", merged.username)
        assertEquals(20.5, merged.walletBalance, 0.001)
        assertEquals(1, merged.payRecords.size)
        assertEquals(1, merged.checkoutRecords.size)
    }
}
