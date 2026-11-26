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
import com.buct.one.data.model.FitnessItemEval
import com.buct.one.data.model.FitnessSimulationResult
import com.buct.one.data.model.NationalFitnessScorer
import com.buct.one.data.model.TyglFitnessRecord
import com.buct.one.data.model.TyglSnapshot
import com.buct.one.data.source.tygl.TyglUiState

/**
 * iOS 体测与体育页：体测成绩（多学年）+ 2014 国标算分 + 体育课成绩 + 阳光跑 + 理论考试。
 * 算分完全复用共享 [NationalFitnessScorer]。
 */
@Composable
internal fun TyglScreen(
    state: TyglUiState,
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
                text = "体测与体育",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
        }

        when (state) {
            TyglUiState.Idle, TyglUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is TyglUiState.AuthExpired -> MessageBlock(
                title = "体测系统会话已失效",
                message = state.message,
                actionLabel = "重新加载",
                onAction = onRefresh,
            )

            is TyglUiState.Failed -> MessageBlock(
                title = "体测数据加载失败",
                message = state.message,
                actionLabel = "重试",
                onAction = onRefresh,
            )

            is TyglUiState.Loaded -> SnapshotContent(
                snapshot = state.snapshot,
                refreshState = state.provenance.refreshState,
                onRefresh = onRefresh,
            )
        }
    }
}

@Composable
private fun MessageBlock(title: String, message: String, actionLabel: String, onAction: () -> Unit) {
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
private fun SnapshotContent(
    snapshot: TyglSnapshot,
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
                        text = snapshot.profile.studentName.ifBlank { "体测成绩" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onBackground,
                    )
                    FreshnessHint(refreshState)
                }
                TextButton(onClick = onRefresh) { Text("刷新") }
            }
        }

        if (snapshot.fitnessRecords.isNotEmpty()) {
            item { SectionTitle("体测成绩") }
            items(snapshot.fitnessRecords) { record ->
                FitnessRecordCard(profile = snapshot.profile, record = record)
            }
        }

        if (snapshot.peCourses.isNotEmpty()) {
            item { SectionTitle("体育课成绩") }
            items(snapshot.peCourses) { course ->
                InfoCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = course.courseName.ifBlank { "体育课" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface,
                        )
                        Text(
                            text = course.termScore.ifBlank { course.actualScore },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.primary,
                        )
                    }
                    val subtitle = listOf(course.academicYear, course.term, course.teacher)
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        snapshot.runPlan?.let { plan ->
            item { SectionTitle("阳光跑计划") }
            item {
                InfoCard {
                    InfoLine("项目", plan.projectName.ifBlank { plan.projectCode.ifBlank { "-" } })
                    if (plan.dateRange.isNotBlank()) InfoLine("日期", plan.dateRange)
                    if (plan.timeRange.isNotBlank()) InfoLine("时段", plan.timeRange)
                    if (plan.distanceLimit.isNotBlank()) InfoLine("距离", plan.distanceLimit)
                    if (plan.paceLimit.isNotBlank()) InfoLine("配速", plan.paceLimit)
                    if (plan.weekDays.isNotBlank()) InfoLine("周次", plan.weekDays)
                }
            }
        }

        snapshot.examInfo?.let { exam ->
            item { SectionTitle("理论考试") }
            item {
                InfoCard {
                    if (exam.currentTerm.isNotBlank()) InfoLine("学期", exam.currentTerm)
                    if (exam.peClass.isNotBlank()) InfoLine("班级", exam.peClass)
                    exam.remainingAttempts?.let { InfoLine("剩余次数", it.toString()) }
                    if (exam.bestScore.isNotBlank()) InfoLine("最好成绩", exam.bestScore)
                    if (exam.notice.isNotBlank()) InfoLine("说明", exam.notice)
                }
            }
        }
    }
}

@Composable
private fun FitnessRecordCard(profile: com.buct.one.data.model.TyglProfile, record: TyglFitnessRecord) {
    val colors = MaterialTheme.colorScheme
    // 用共享算分器复算总分（与 Android 端同一份国标实现）
    val simulation: FitnessSimulationResult? = runCatching {
        NationalFitnessScorer.evaluate(NationalFitnessScorer.inputFromRecord(profile, record))
    }.getOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = record.yearLabel.ifBlank { record.yearKey.ifBlank { "体测" } },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                simulation?.let {
                    Text(
                        text = "${it.finalTotal} 分 · ${it.level}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                    )
                }
            }
            if (simulation != null) {
                Spacer(Modifier.height(8.dp))
                InfoLine("BMI", simulation.bmi?.toString() ?: "-")
                InfoLine("标准分", simulation.standardTotal.toString())
                if (simulation.bonusTotal > 0) InfoLine("加分", simulation.bonusTotal.toString())
                Spacer(Modifier.height(6.dp))
                for (item in simulation.items.take(6)) {
                    ItemRow(item)
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: FitnessItemEval) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${item.name}（${item.weightPercent}%）",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        Text(
            text = "${item.score}${if (item.bonus > 0) " +${item.bonus}" else ""} · ${item.level}",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurface,
        )
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
private fun InfoLine(label: String, value: String) {
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
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
