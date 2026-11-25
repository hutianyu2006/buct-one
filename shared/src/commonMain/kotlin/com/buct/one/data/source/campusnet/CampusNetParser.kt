package com.buct.one.data.source.campusnet

import com.buct.one.data.model.CampusNetCheckoutRecord
import com.buct.one.data.model.CampusNetOnlineDevice
import com.buct.one.data.model.CampusNetPayRecord
import com.buct.one.data.model.CampusNetSnapshot
import com.buct.one.data.json.JSONObject
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element

data class SelfServiceHomeData(
    val accountStatus: String = "",
    val walletBalance: Double? = null,
    val productsName: String = "",
    val billingName: String = "",
    val trafficText: String = "",
    val durationText: String = "",
    val userCharge: Double? = null,
    val productBalance: Double? = null,
    val onlineDevices: List<CampusNetOnlineDevice> = emptyList()
)

/**
 * 北化校园网（tree.buct.edu.cn & :8800 自服务平台）纯函数解析器。
 */
object CampusNetParser {

    const val PARSER_VERSION = 3

    private val NUMBER_RE = Regex("""[-+]?\d+(?:,\d{3})*(?:\.\d+)?""")

    fun stripJsonp(raw: String): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return JSONObject(trimmed)
        }
        val firstParen = trimmed.indexOf('(')
        val lastParen = trimmed.lastIndexOf(')')
        if (firstParen >= 0 && lastParen > firstParen) {
            val inner = trimmed.substring(firstParen + 1, lastParen).trim()
            return JSONObject(inner)
        }
        throw IllegalArgumentException("无法解析深澜响应：$trimmed")
    }

    fun parseUserInfo(raw: String): CampusNetSnapshot {
        val obj = stripJsonp(raw)
        val error = obj.optString("error", "").trim()
        val isOnline = error.equals("ok", ignoreCase = true)
        if (!isOnline) {
            return CampusNetSnapshot(
                isOnline = false,
                onlineIp = obj.optString("online_ip", "").ifBlank { obj.optString("client_ip", "") }.trim()
            )
        }
        val onlineDevices = obj.optString("online_device_total", "")
            .trim()
            .toIntOrNull()
            ?: obj.optInt("online_device_total", 0)
        return CampusNetSnapshot(
            isOnline = true,
            username = obj.optString("user_name", "").trim(),
            onlineIp = obj.optString("online_ip", "").trim(),
            accountStatus = "已在线",
            productsName = obj.optString("products_name", "").trim(),
            billingName = obj.optString("billing_name", "").trim(),
            sumBytes = obj.optLong("sum_bytes", 0L),
            allBytes = obj.optLong("all_bytes", 0L),
            sumSeconds = obj.optLong("sum_seconds", 0L),
            userBalance = obj.optDouble("user_balance", 0.0).takeIf { it.isFinite() } ?: 0.0,
            walletBalance = obj.optDouble("wallet_balance", 0.0).takeIf { it.isFinite() } ?: 0.0,
            userCharge = obj.optDouble("user_charge", 0.0).takeIf { it.isFinite() } ?: 0.0,
            onlineDeviceTotal = onlineDevices,
            keepaliveTimeSec = obj.optLong("keepalive_time", 0L)
        )
    }

    fun parseSelfServiceHome(html: String): SelfServiceHomeData? {
        if (!html.contains("电子钱包") && !html.contains("产品信息") && !html.contains("w3-container")) {
            return null
        }
        val doc = Ksoup.parse(html)
        if (doc.selectFirst("#w3-container") == null && doc.select("li.list-group-item").isEmpty()) {
            return null
        }

        var accountStatus = ""
        var walletBalance: Double? = null
        for (item in doc.select("li.list-group-item")) {
            val labelText = item.selectFirst("label")?.text()?.trim().orEmpty()
            when {
                labelText.contains("状态") -> {
                    val clone = item.clone()
                    clone.select("label").remove()
                    accountStatus = clone.text().trim()
                }
                labelText.contains("电子钱包") -> {
                    val clone = item.clone()
                    clone.select("label, a").remove()
                    walletBalance = extractNumber(clone.text())
                }
            }
        }

        val devices = ArrayList<CampusNetOnlineDevice>()
        for (tr in doc.select("#w1-container table tbody tr")) {
            if (isEmptyRow(tr)) continue
            val ip = cellText(tr, colSeq = 1, fallbackIndex = 1)
            val loginTime = cellText(tr, colSeq = 3, fallbackIndex = 2)
            val billingName = cellText(tr, colSeq = 11, fallbackIndex = 3)
            if (ip.isNotBlank() || loginTime.isNotBlank()) {
                devices.add(
                    CampusNetOnlineDevice(
                        ip = ip,
                        loginTime = loginTime,
                        billingName = billingName
                    )
                )
            }
        }

        var productsName = ""
        var billingName = ""
        var trafficText = ""
        var durationText = ""
        var userCharge: Double? = null
        var productBalance: Double? = null
        val productRow = doc.select("#w3-container table tbody tr").firstOrNull { !isEmptyRow(it) }
        if (productRow != null) {
            productsName = cellText(productRow, colSeq = 1, fallbackIndex = 1)
            billingName = cellText(productRow, colSeq = 2, fallbackIndex = 2)
            trafficText = cellText(productRow, colSeq = 3, fallbackIndex = 3)
            durationText = cellText(productRow, colSeq = 4, fallbackIndex = 4)
            userCharge = extractNumber(cellText(productRow, colSeq = 6, fallbackIndex = 6))
            productBalance = extractNumber(cellText(productRow, colSeq = 7, fallbackIndex = 7))
        }

        return SelfServiceHomeData(
            accountStatus = accountStatus,
            walletBalance = walletBalance,
            productsName = productsName,
            billingName = billingName,
            trafficText = trafficText,
            durationText = durationText,
            userCharge = userCharge,
            productBalance = productBalance,
            onlineDevices = devices
        )
    }

    fun parsePayRecords(html: String): List<CampusNetPayRecord> {
        val doc = Ksoup.parse(html)
        val out = ArrayList<CampusNetPayRecord>()
        for (tr in doc.select("table.kv-grid-table tbody tr")) {
            if (isEmptyRow(tr)) continue
            val amount = cellText(tr, colSeq = 2, fallbackIndex = 1)
            val payType = cellText(tr, colSeq = 4, fallbackIndex = 2)
            val payTime = cellText(tr, colSeq = 6, fallbackIndex = 3)
            if (amount.isNotBlank() || payTime.isNotBlank()) {
                out.add(
                    CampusNetPayRecord(
                        amountText = amount,
                        payType = payType,
                        payTime = payTime
                    )
                )
            }
            if (out.size >= 20) break
        }
        return out
    }

    fun parseCheckoutRecords(html: String): List<CampusNetCheckoutRecord> {
        val doc = Ksoup.parse(html)
        val out = ArrayList<CampusNetCheckoutRecord>()
        for (tr in doc.select("table.kv-grid-table tbody tr")) {
            if (isEmptyRow(tr)) continue
            val realtimeFee = cellText(tr, colSeq = 3, fallbackIndex = 2)
                .ifBlank { cellText(tr, colSeq = 2, fallbackIndex = 1) }
            val productName = cellText(tr, colSeq = 4, fallbackIndex = 3)
            val traffic = cellText(tr, colSeq = 7, fallbackIndex = 5)
            val duration = cellText(tr, colSeq = 9, fallbackIndex = 7)
            val checkoutTime = cellText(tr, colSeq = 12, fallbackIndex = 8)
            if (checkoutTime.isNotBlank() || productName.isNotBlank() || traffic.isNotBlank()) {
                out.add(
                    CampusNetCheckoutRecord(
                        feeText = realtimeFee,
                        productName = productName,
                        trafficText = traffic,
                        durationText = duration,
                        checkoutTime = checkoutTime
                    )
                )
            }
            if (out.size >= 12) break
        }
        return out
    }

    fun mergeSnapshot(
        rad: CampusNetSnapshot?,
        home: SelfServiceHomeData?,
        payRecords: List<CampusNetPayRecord>,
        checkoutRecords: List<CampusNetCheckoutRecord>,
        fallbackUsername: String
    ): CampusNetSnapshot {
        val devices = home?.onlineDevices.orEmpty()
        val isOnline = rad?.isOnline == true || devices.isNotEmpty()
        val resolvedUser = rad?.username?.takeIf { it.isNotBlank() } ?: fallbackUsername.trim()
        val resolvedIp = rad?.onlineIp?.takeIf { it.isNotBlank() }
            ?: devices.firstOrNull()?.ip.orEmpty()
        val status = home?.accountStatus?.takeIf { it.isNotBlank() }
            ?: if (isOnline) "正常 · 在线" else "未在线"

        return CampusNetSnapshot(
            isOnline = isOnline,
            username = resolvedUser,
            onlineIp = resolvedIp,
            accountStatus = status,
            productsName = home?.productsName?.takeIf { it.isNotBlank() } ?: rad?.productsName.orEmpty(),
            billingName = home?.billingName?.takeIf { it.isNotBlank() } ?: rad?.billingName.orEmpty(),
            sumBytes = rad?.sumBytes ?: 0L,
            allBytes = rad?.allBytes ?: 0L,
            sumSeconds = rad?.sumSeconds ?: 0L,
            trafficText = home?.trafficText.orEmpty(),
            durationText = home?.durationText.orEmpty(),
            userBalance = home?.productBalance ?: rad?.userBalance ?: 0.0,
            walletBalance = home?.walletBalance ?: rad?.walletBalance ?: 0.0,
            userCharge = home?.userCharge ?: rad?.userCharge ?: 0.0,
            onlineDeviceTotal = maxOf(rad?.onlineDeviceTotal ?: 0, devices.size),
            keepaliveTimeSec = rad?.keepaliveTimeSec ?: 0L,
            onlineDevices = devices,
            payRecords = payRecords,
            checkoutRecords = checkoutRecords
        )
    }

    private fun isEmptyRow(tr: Element): Boolean {
        if (tr.hasClass("empty")) return true
        val tds = tr.select("td")
        if (tds.isEmpty()) return true
        if (tds.size == 1 && tds.first()?.hasAttr("colspan") == true) return true
        return false
    }

    private fun cellText(tr: Element, colSeq: Int, fallbackIndex: Int): String {
        val bySeq = tr.selectFirst("td[data-col-seq=$colSeq]")?.text()?.trim()
        if (!bySeq.isNullOrBlank()) return bySeq
        val visibleTds = tr.select("td")
        return visibleTds.getOrNull(fallbackIndex)?.text()?.trim().orEmpty()
    }

    private fun extractNumber(text: String): Double? {
        val m = NUMBER_RE.find(text.replace(",", "")) ?: return null
        return m.value.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
