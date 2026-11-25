package com.buct.one.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buct.one.data.model.CampusNetSnapshot
import com.buct.one.data.model.displayCampusNetDuration
import com.buct.one.data.model.displayCampusNetTraffic
import com.buct.one.data.model.formatCampusNetYuan
import com.buct.one.data.source.campusnet.CampusNetUiState

/** iOS 校园网网费页：在线状态、流量、钱包余额、在线设备与账单。 */
@Composable
internal fun CampusNetScreen(
    state: CampusNetUiState,
    onRefresh: () -> Unit,
) {
    when (state) {
        CampusNetUiState.Idle, CampusNetUiState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        is CampusNetUiState.Failed -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "校园网数据获取失败",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onRefresh) { Text("重试") }
            }
        }

        is CampusNetUiState.Loaded -> BillingContent(snapshot = state.snapshot, onRefresh = onRefresh)
    }
}

@Composable
private fun BillingContent(snapshot: CampusNetSnapshot, onRefresh: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "校园网",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground,
                )
                TextButton(onClick = onRefresh) { Text("刷新") }
            }
        }
        item {
            InfoCard {
                InfoRow("状态", snapshot.accountStatus.ifBlank { if (snapshot.isOnline) "在线" else "未在线" })
                if (snapshot.onlineIp.isNotBlank()) InfoRow("在线 IP", snapshot.onlineIp)
                InfoRow("电子钱包", "¥ " + formatCampusNetYuan(snapshot.walletBalance))
                InfoRow("账户余额", "¥ " + formatCampusNetYuan(snapshot.userBalance))
                if (snapshot.productsName.isNotBlank()) InfoRow("套餐", snapshot.productsName)
                if (snapshot.billingName.isNotBlank()) InfoRow("计费", snapshot.billingName)
                InfoRow(
                    "本月流量",
                    if (snapshot.trafficText.isNotBlank()) snapshot.trafficText else displayCampusNetTraffic(snapshot),
                )
                InfoRow(
                    "本月时长",
                    if (snapshot.durationText.isNotBlank()) snapshot.durationText else displayCampusNetDuration(snapshot),
                )
                if (snapshot.onlineDeviceTotal > 0 || snapshot.onlineDevices.isNotEmpty()) {
                    InfoRow("在线设备", "${maxOf(snapshot.onlineDeviceTotal, snapshot.onlineDevices.size)} 台")
                }
            }
        }
        if (snapshot.onlineDevices.isNotEmpty()) {
            item { SectionTitle("在线设备") }
            items(snapshot.onlineDevices) { device ->
                InfoCard {
                    InfoRow(device.ip.ifBlank { "未知 IP" }, device.loginTime.ifBlank { "-" })
                    if (device.billingName.isNotBlank()) {
                        Text(
                            text = device.billingName,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (snapshot.payRecords.isNotEmpty()) {
            item { SectionTitle("缴费记录") }
            items(snapshot.payRecords.take(10)) { record ->
                InfoCard {
                    InfoRow(record.payTime.ifBlank { "-" }, "¥ " + record.amountText, emphasize = true)
                    if (record.payType.isNotBlank()) {
                        Text(
                            text = record.payType,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (snapshot.checkoutRecords.isNotEmpty()) {
            item { SectionTitle("结算清单") }
            items(snapshot.checkoutRecords.take(10)) { record ->
                InfoCard {
                    InfoRow(record.checkoutTime.ifBlank { "-" }, record.feeText.ifBlank { "-" }, emphasize = true)
                    Text(
                        text = listOf(record.productName, record.trafficText, record.durationText)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun InfoRow(label: String, value: String, emphasize: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
