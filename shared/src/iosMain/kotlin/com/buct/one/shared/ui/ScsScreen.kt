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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.ScsActivityItem
import com.buct.one.data.model.ScsSnapshot
import com.buct.one.data.model.ScsTermScore
import com.buct.one.data.source.scs.ScsUiState

/**
 * iOS 第二课堂页：五育综测总分 + 各学期明细 + 自评窗口 + 活动清单。
 * 数据与状态机全部来自共享 [com.buct.one.data.source.scs.ScsController]。
 */
@Composable
internal fun ScsScreen(
    state: ScsUiState,
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
                text = "第二课堂",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
        }

        when (state) {
            ScsUiState.Idle, ScsUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is ScsUiState.AuthExpired -> ScsMessageBlock(
                title = "第二课堂会话已失效",
                message = state.message,
                actionLabel = "重新加载",
                onAction = onRefresh,
            )

            is ScsUiState.Failed -> ScsMessageBlock(
                title = "第二课堂数据加载失败",
                message = state.message,
                actionLabel = "重试",
                onAction = onRefresh,
            )

            is ScsUiState.Loaded -> ScsSnapshotContent(
                snapshot = state.snapshot,
                refreshState = state.provenance.refreshState,
                onRefresh = onRefresh,
            )
        }
    }
}

@Composable
private fun ScsMessageBlock(title: String, message: String, actionLabel: String, onAction: () -> Unit) {
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
private fun ScsSnapshotContent(
    snapshot: ScsSnapshot,
    refreshState: RefreshState,
    onRefresh: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScsCard {
                Text(
                    text = snapshot.studentName.ifBlank { "第二课堂" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (snapshot.className.isNotBlank() || snapshot.collegeName.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = listOf(snapshot.collegeName, snapshot.className)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                val window = snapshot.currentWindow
                if (window != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (window.isOpen) {
                            "自评开放中：${window.yearName} ${window.termName}"
                        } else {
                            "自评窗口：${window.yearName} ${window.termName}（未开放）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (window.isOpen) colors.primary else colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = freshnessLabel(refreshState),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRefresh) { Text("刷新") }
                }
            }
        }

        if (snapshot.terms.isEmpty() && snapshot.activities.isEmpty()) {
            item {
                ScsCard {
                    Text(
                        text = "暂无第二课堂成绩（NEVER：还没同步过，不代表学校没有记录）",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }

        items(snapshot.terms) { term ->
            ScsCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${term.yearName} ${term.termName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (term.isFullYear) "全年" else "学期",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = term.totalScore?.let { formatScore(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ScsDimensionScore("德", term.moral)
                    ScsDimensionScore("智", term.academic)
                    ScsDimensionScore("体", term.physical)
                    ScsDimensionScore("美", term.aesthetics)
                    ScsDimensionScore("劳", term.labor)
                }
                if (term.details.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    for (detail in term.details) {
                        Text(
                            text = "${detail.title}：${detail.totalScore?.let { formatScore(it) } ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (snapshot.activities.isNotEmpty()) {
            item {
                Text(
                    text = "校园二课活动",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(snapshot.activities) { activity -> ScsActivityRow(activity) }
        }
    }
}

@Composable
private fun ScsDimensionScore(label: String, score: Double?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = score?.let { formatScore(it) } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ScsActivityRow(activity: ScsActivityItem) {
    val colors = MaterialTheme.colorScheme
    ScsCard {
        Text(
            text = activity.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        val meta = listOf(
            activity.categoryName,
            activity.startTime,
            activity.location,
            activity.status,
        ).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(text = meta, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        if (activity.score.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "学时/积分：${activity.score}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.primary,
            )
        }
    }
}

@Composable
private fun ScsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

/** 分数统一保留一位小数（与 Android 侧展示口径一致）。 */
private fun formatScore(value: Double): String {
    val rounded = (value * 10).toInt() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

/** 数据新鲜度文案（复用体测/电费页的同一套口径）。 */
private fun freshnessLabel(state: RefreshState): String = when (state) {
    RefreshState.FRESH -> "刚刚更新"
    RefreshState.STALE -> "来自本地缓存"
    RefreshState.FAILED_FALLBACK -> "刷新失败，显示上次数据"
    RefreshState.NEVER -> "尚未同步"
    RefreshState.EMPTY_CONFIRMED -> "服务端确认无记录"
}
