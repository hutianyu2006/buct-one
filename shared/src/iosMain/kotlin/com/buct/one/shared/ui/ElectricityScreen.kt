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
import com.buct.one.data.model.ElectricityReading
import com.buct.one.data.model.ElectricityRoomTarget
import com.buct.one.data.model.formatElectricityPrimaryValue
import com.buct.one.data.model.isLowElectricityBalance
import com.buct.one.data.source.electricity.ElectricityUiState

/**
 * iOS 宿舍电费页：展示绑定房间的余额与查询状态。
 *
 * 房间选择（三级联动：校区 → 楼栋 → 楼层 → 房间）留待下一轮与 Android 的选择器一起做，
 * 本轮先展示"已绑定房间 + 余额 + 低余额提醒"，数据与状态机全部来自共享层。
 */
@Composable
internal fun ElectricityScreen(
    state: ElectricityUiState,
    lowBalanceThreshold: Double,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(
                text = "宿舍电费",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
        }

        when (state) {
            ElectricityUiState.Idle, ElectricityUiState.LoadingCatalog ->
                CenteredLoading()

            ElectricityUiState.CatalogUnavailable -> CenteredMessage(
                title = "宿舍字典不可用",
                message = "未能读取宿舍房间字典，请检查安装包完整性或稍后重试。",
                actionLabel = "重试",
                onAction = onRefresh,
            )

            is ElectricityUiState.CatalogReady -> CenteredMessage(
                title = "还没有绑定房间",
                message = "共 ${state.catalog.campuses.size} 个校区、${
                    state.catalog.campuses.sumOf { it.buildings.size }
                } 栋楼可选。房间选择器正在开发中，Android 端已可绑定。",
                actionLabel = "刷新",
                onAction = onRefresh,
            )

            is ElectricityUiState.Loading -> CenteredLoading()

            is ElectricityUiState.Banned -> CenteredMessage(
                title = "查询已被限制",
                message = state.message + (state.retryAfterSeconds?.let { "（约 $it 秒后可重试）" } ?: ""),
                actionLabel = "重试",
                onAction = onRefresh,
            )

            is ElectricityUiState.Failed -> CenteredMessage(
                title = "电费查询失败",
                message = state.message,
                actionLabel = "重试",
                onAction = onRefresh,
            )

            is ElectricityUiState.Loaded -> ReadingsContent(
                readings = state.readings,
                lowBalanceThreshold = lowBalanceThreshold,
                refreshState = state.provenance.refreshState,
                onRefresh = onRefresh,
            )
        }
    }
}

@Composable
private fun CenteredLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ReadingsContent(
    readings: List<ElectricityReading>,
    lowBalanceThreshold: Double,
    refreshState: com.buct.one.data.model.RefreshState,
    onRefresh: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "余额",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onBackground,
                    )
                    FreshnessHint(refreshState)
                }
                TextButton(onClick = onRefresh) { Text("刷新") }
            }
        }
        items(readings) { reading ->
            val low = isLowElectricityBalance(reading, lowBalanceThreshold)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (low) colors.errorContainer else colors.surfaceVariant,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = roomLabel(reading.target),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (low) colors.onErrorContainer else colors.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = formatElectricityPrimaryValue(reading),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (low) colors.onErrorContainer else colors.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            if (reading.accountStatus.isNotBlank()) append(reading.accountStatus)
                            if (low) {
                                if (isNotEmpty()) append(" · ")
                                append("低于提醒阈值 ${lowBalanceThreshold.toInt()} 元")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (low) colors.onErrorContainer else colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun roomLabel(target: ElectricityRoomTarget): String = buildString {
    if (target.buildingName.isNotBlank()) append(target.buildingName)
    if (target.roomName.isNotBlank()) {
        if (isNotEmpty()) append(" ")
        append(target.roomName)
    }
    if (target.meterType.isNotBlank()) append(" · ").append(target.meterType)
}
