package com.buct.one.data.model

/**
 * 校园网在线终端信息。
 * 保持 model 层零外部 import。
 */
data class CampusNetOnlineDevice(
    val ip: String,
    val loginTime: String,
    val billingName: String
)

/**
 * 校园网缴费记录（来自自服务 /log/pay）。
 */
data class CampusNetPayRecord(
    val amountText: String,
    val payType: String,
    val payTime: String
)

/**
 * 校园网月度结算清单记录（来自自服务 /log/check-out）。
 */
data class CampusNetCheckoutRecord(
    val feeText: String,
    val productName: String,
    val trafficText: String,
    val durationText: String,
    val checkoutTime: String
)

/**
 * 北化校园网（tree.buct.edu.cn & :8800 自服务平台）只读网费、流量、在线设备与账单快照。
 * 保持 model 层零外部 import。
 */
data class CampusNetSnapshot(
    val isOnline: Boolean,
    val username: String = "",
    val onlineIp: String = "",
    val accountStatus: String = "",
    val productsName: String = "",
    val billingName: String = "",
    val sumBytes: Long = 0L,
    val allBytes: Long = 0L,
    val sumSeconds: Long = 0L,
    val trafficText: String = "",
    val durationText: String = "",
    val userBalance: Double = 0.0,
    val walletBalance: Double = 0.0,
    val userCharge: Double = 0.0,
    val onlineDeviceTotal: Int = 0,
    val keepaliveTimeSec: Long = 0L,
    val onlineDevices: List<CampusNetOnlineDevice> = emptyList(),
    val payRecords: List<CampusNetPayRecord> = emptyList(),
    val checkoutRecords: List<CampusNetCheckoutRecord> = emptyList()
)

private fun formatFixedTwoDecimals(value: Double): String {
    if (!value.isFinite() || value <= 0.0) return "0.00"
    val hundredths = kotlin.math.round(value * 100.0).toLong()
    val whole = hundredths / 100L
    val frac = hundredths % 100L
    val fracStr = if (frac < 10L) "0$frac" else "$frac"
    return "$whole.$fracStr"
}

/**
 * 与北化深澜 Portal 配置（TrafficCarry = 1000）保持一致的十进制流量格式化。
 */
fun formatCampusNetTraffic(bytes: Long): String {
    if (bytes <= 0L) return "0.00 MB"
    val tb = 1_000_000_000_000.0
    val gb = 1_000_000_000.0
    val mb = 1_000_000.0
    val kb = 1_000.0
    return when {
        bytes >= tb -> "${formatFixedTwoDecimals(bytes / tb)} TB"
        bytes >= gb -> "${formatFixedTwoDecimals(bytes / gb)} GB"
        bytes >= mb -> "${formatFixedTwoDecimals(bytes / mb)} MB"
        bytes >= kb -> "${formatFixedTwoDecimals(bytes / kb)} KB"
        else -> "$bytes B"
    }
}

fun formatCampusNetDuration(seconds: Long): String {
    if (seconds <= 0L) return "0 分钟"
    val totalMinutes = seconds / 60L
    val days = totalMinutes / (24L * 60L)
    val hours = (totalMinutes % (24L * 60L)) / 60L
    val mins = totalMinutes % 60L
    return when {
        days > 0L -> "${days}天${hours}小时"
        hours > 0L -> "${hours}小时${mins}分钟"
        else -> "${mins}分钟"
    }
}

fun formatCampusNetYuan(amount: Double): String = formatFixedTwoDecimals(amount)

fun displayCampusNetTraffic(snapshot: CampusNetSnapshot): String =
    snapshot.trafficText.ifBlank { formatCampusNetTraffic(snapshot.sumBytes) }

fun displayCampusNetDuration(snapshot: CampusNetSnapshot): String =
    snapshot.durationText.ifBlank { formatCampusNetDuration(snapshot.sumSeconds) }
