package com.buct.one.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buct.one.data.model.JwAnnouncementDetail
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.JwAnnouncementItem
import com.buct.one.data.model.JwReminderItem
import com.buct.one.data.source.jwnotice.JwNoticeUiState

private sealed interface JwDetailState {
    data object Idle : JwDetailState
    data object Loading : JwDetailState
    data class Loaded(val detail: JwAnnouncementDetail) : JwDetailState
    data class Failed(val message: String) : JwDetailState
}

/** iOS 教务消息页：调停课提醒 + 通知公告 + 公告详情。 */
@Composable
internal fun JwNoticeScreen(
    state: JwNoticeUiState,
    onRefresh: () -> Unit,
    loadDetail: suspend (JwAnnouncementItem) -> Result<JwAnnouncementDetail>,
) {
    var selected by remember { mutableStateOf<JwAnnouncementItem?>(null) }
    var detailState by remember { mutableStateOf<JwDetailState>(JwDetailState.Idle) }

    LaunchedEffect(selected) {
        val item = selected ?: return@LaunchedEffect
        detailState = JwDetailState.Loading
        detailState = loadDetail(item).fold(
            onSuccess = { JwDetailState.Loaded(it) },
            onFailure = { JwDetailState.Failed(it.message?.takeIf { message -> message.isNotBlank() } ?: "加载失败") },
        )
    }

    val current = selected
    if (current != null) {
        JwDetailPage(
            item = current,
            detailState = detailState,
            onBack = {
                selected = null
                detailState = JwDetailState.Idle
            },
            onRetry = {
                selected = null
                selected = current
            },
        )
        return
    }

    val colors = MaterialTheme.colorScheme
    when (state) {
        JwNoticeUiState.Idle, JwNoticeUiState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        is JwNoticeUiState.AuthExpired -> MessagePage(
            title = "教务会话已失效",
            message = state.message,
            actionLabel = "重新登录",
            onAction = onRefresh,
        )

        is JwNoticeUiState.Failed -> MessagePage(
            title = "教务消息加载失败",
            message = state.message,
            actionLabel = "重试",
            onAction = onRefresh,
        )

        is JwNoticeUiState.Loaded -> LazyColumn(
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
                    Column {
                        Text(
                            text = "教务消息",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onBackground,
                        )
                        FreshnessHint(state.provenance.refreshState)
                    }
                    TextButton(onClick = onRefresh) { Text("刷新") }
                }
            }
            item { SectionHeader("调停课提醒 · ${state.snapshot.reminderTotal}") }
            if (state.snapshot.reminders.isEmpty()) {
                item { EmptyHint("暂无调停课提醒") }
            } else {
                items(state.snapshot.reminders) { reminder -> ReminderCard(reminder) }
            }
            item { SectionHeader("通知公告 · ${state.snapshot.announcementTotal}") }
            if (state.snapshot.announcements.isEmpty()) {
                item { EmptyHint("暂无通知公告") }
            } else {
                items(state.snapshot.announcements) { announcement ->
                    AnnouncementCard(announcement) { selected = announcement }
                }
            }
        }
    }
}

/** 数据新鲜度提示：只有「缓存/旧数据/失败兜底」才提示，正常新鲜时保持界面干净。 */
@Composable
internal fun FreshnessHint(state: RefreshState) {
    val text = when (state) {
        RefreshState.STALE -> "正在刷新，先显示上次缓存"
        RefreshState.FAILED_FALLBACK -> "本次同步失败，显示的是上次数据"
        RefreshState.EMPTY_CONFIRMED -> "服务端确认暂无数据"
        RefreshState.FRESH, RefreshState.NEVER -> null
    } ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReminderCard(item: JwReminderItem) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    text = when (item.category.name) {
                        "RESCHEDULE" -> "调课"
                        "MAKEUP" -> "补课"
                        "CANCEL" -> "停课"
                        else -> "提醒"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.content,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            if (item.createdAt.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.createdAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AnnouncementCard(item: JwAnnouncementItem, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = listOf(item.publisher, item.publishedAt).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessagePage(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
private fun JwDetailPage(
    item: JwAnnouncementItem,
    detailState: JwDetailState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
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
                text = "教务公告",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
        }
        when (detailState) {
            JwDetailState.Idle, JwDetailState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is JwDetailState.Failed -> MessagePage(
                title = "公告加载失败",
                message = detailState.message,
                actionLabel = "重试",
                onAction = onRetry,
            )

            is JwDetailState.Loaded -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            ) {
                item {
                    Text(
                        text = detailState.detail.title.ifBlank { item.title },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = listOf(detailState.detail.publisher, detailState.detail.publishedAt)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = detailState.detail.plainText.ifBlank { "正文为空" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                    )
                }
            }
        }
    }
}
