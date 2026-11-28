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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.PortalBookItem
import com.buct.one.data.model.PortalCalendarSnapshot
import com.buct.one.data.model.PortalLibrarySnapshot
import com.buct.one.data.model.PortalNewsArticle
import com.buct.one.data.model.PortalNewsColumn
import com.buct.one.data.model.PortalNewsSnapshot
import com.buct.one.data.source.portal.PortalContent
import com.buct.one.data.source.portal.PortalUiState
import com.buct.one.data.text.daysFromCivil
import com.buct.one.data.text.htmlUnescape
import com.buct.one.shared.nowMillis

private enum class PortalSection(val label: String) {
    Calendar("校历"),
    Library("在借"),
    News("资讯"),
}

/**
 * iOS「i北化」页：校历与当前教学周、图书馆在借清单与应还提醒、校内综合资讯四栏与正文阅读。
 *
 * 数据与状态机全部来自共享 [com.buct.one.data.source.portal.PortalController]：
 * 失败保留旧快照、会话失效单独提示，界面只负责渲染与分栏切换。
 */
@Composable
internal fun PortalScreen(
    state: PortalUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    var section by remember { mutableStateOf(PortalSection.Calendar) }
    var selectedArticle by remember { mutableStateOf<PortalNewsArticle?>(null) }

    val article = selectedArticle
    if (article != null) {
        PortalArticlePage(article = article, onBack = { selectedArticle = null })
        return
    }

    val colors = MaterialTheme.colorScheme
    val content = state.contentOrNull()
    val loading = state is PortalUiState.Idle || state is PortalUiState.Loading

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(
                text = "i北化",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) { Text("刷新") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (item in PortalSection.entries) {
                PortalSectionTab(
                    label = item.label,
                    selected = section == item,
                    onSelect = { section = item },
                )
            }
        }

        when (state) {
            is PortalUiState.AuthExpired -> PortalNotice(
                title = "统一认证会话已失效",
                message = state.message,
                tone = Tone.Danger,
            )
            is PortalUiState.Failed -> PortalNotice(
                title = "i北化数据加载失败",
                message = state.message,
                tone = Tone.Danger,
            )
            else -> Unit
        }

        if (loading && content == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        when (section) {
            PortalSection.Calendar -> PortalCalendarTab(
                slice = content?.calendar,
                onRefresh = onRefresh,
            )
            PortalSection.Library -> PortalLibraryTab(
                slice = content?.library,
                onRefresh = onRefresh,
            )
            PortalSection.News -> PortalNewsTab(
                slice = content?.news,
                onRefresh = onRefresh,
                onSelectArticle = { selectedArticle = it },
            )
        }
    }
}

// ---------- 校历 ----------

@Composable
private fun PortalCalendarTab(
    slice: DomainSlice<PortalCalendarSnapshot>?,
    onRefresh: () -> Unit,
) {
    val snapshot = slice?.data
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (snapshot == null) {
            item {
                PortalCard {
                    Text(
                        text = "暂无校历数据（尚未同步，不代表学校没有发布校历）",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRefresh) { Text("同步校历") }
                }
            }
            return@LazyColumn
        }

        item {
            PortalCard {
                Text(
                    text = snapshot.termLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "当前教学周：第 ${snapshot.currentTeachingWeek} 周",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (snapshot.beginDate.isNotBlank() && snapshot.endDate.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "学期起止：${snapshot.beginDate} ~ ${snapshot.endDate}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                slice?.let { FreshnessHint(it.provenance.refreshState) }
                slice?.lastError?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.error,
                    )
                }
            }
        }

        if (snapshot.holidays.isNotEmpty()) {
            item {
                Text(
                    text = "法定节假日",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(snapshot.holidays) { holiday ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = holiday.holidayName.ifBlank { "假期" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                    )
                    Text(
                        text = if (holiday.teachingWeek > 0) {
                            "${holiday.date} · 第 ${holiday.teachingWeek} 周"
                        } else {
                            holiday.date
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }

        if (snapshot.terms.isNotEmpty()) {
            item {
                Text(
                    text = "历次学期",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(snapshot.terms) { term ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${term.academicYear}-${term.academicYear + 1} 第 ${term.semester} 学期" +
                            if (term.isCurrent) "（当前）" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (term.isCurrent) colors.primary else colors.onSurfaceVariant,
                    )
                    Text(
                        text = "${term.beginDate} ~ ${term.endDate}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun PortalCalendarSnapshot.termLabel(): String {
    val current = terms.firstOrNull { it.isCurrent } ?: terms.firstOrNull()
    if (current != null) {
        return "${current.academicYear}-${current.academicYear + 1} 学年第 ${current.semester} 学期"
    }
    return if (currentTermId.isNotBlank()) currentTermId else "i北化校历"
}

// ---------- 图书馆在借 ----------

@Composable
private fun PortalLibraryTab(
    slice: DomainSlice<PortalLibrarySnapshot>?,
    onRefresh: () -> Unit,
) {
    val snapshot = slice?.data
    val colors = MaterialTheme.colorScheme
    val dueWarnings = remember(snapshot) { snapshot?.books.orEmpty().map { it to dueDays(it.dueDate) } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (snapshot == null) {
            item {
                PortalCard {
                    Text(
                        text = "暂无借阅数据（尚未同步，不代表没有在借图书）",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRefresh) { Text("同步借阅") }
                }
            }
            return@LazyColumn
        }

        item {
            PortalCard {
                Text(
                    text = "在借 ${snapshot.totalCount} 本",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                slice?.let { FreshnessHint(it.provenance.refreshState) }
                slice?.lastError?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(text = message, style = MaterialTheme.typography.labelSmall, color = colors.error)
                }
            }
        }

        if (snapshot.books.isEmpty()) {
            item {
                PortalCard {
                    Text(
                        text = "图书馆没有在借图书",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }

        items(dueWarnings) { pair ->
            val (book, days) = pair
            PortalCard {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOf(book.barcode, book.borrowDate.ifBlank { "—" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = dueText(book, days),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (days != null && days <= 3) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        days == null -> colors.onSurfaceVariant
                        days < 0 -> colors.error
                        days <= 3 -> WarningOrange
                        else -> colors.primary
                    },
                )
            }
        }
    }
}

private fun dueText(book: PortalBookItem, days: Int?): String {
    if (book.dueDate.isBlank()) return "应还日期未知"
    return when {
        days == null -> "应还：${book.dueDate}"
        days < 0 -> "已逾期 ${-days} 天（应还 ${book.dueDate}）"
        days == 0 -> "今天到期（${book.dueDate}）"
        else -> "距离应还还有 $days 天（${book.dueDate}）"
    }
}

/** 应还日期距今天数：逾期为负；日期非法返回 null（界面只显示原始日期）。 */
private fun dueDays(dueDate: String): Int? {
    val clean = dueDate.trim()
    if (clean.length < 10) return null
    val year = clean.substring(0, 4).toIntOrNull() ?: return null
    val month = clean.substring(5, 7).toIntOrNull() ?: return null
    val day = clean.substring(8, 10).toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31) return null
    val today = formatNoticeDate(nowMillis())
    if (today.length < 10) return null
    val todayDay = daysFromCivil(
        today.substring(0, 4).toIntOrNull() ?: return null,
        today.substring(5, 7).toIntOrNull() ?: return null,
        today.substring(8, 10).toIntOrNull() ?: return null,
    )
    return (daysFromCivil(year, month, day) - todayDay).toInt()
}

// ---------- 校内资讯 ----------

@Composable
private fun PortalNewsTab(
    slice: DomainSlice<PortalNewsSnapshot>?,
    onRefresh: () -> Unit,
    onSelectArticle: (PortalNewsArticle) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var column by remember { mutableStateOf(PortalNewsColumn.NOTICE) }
    val page = slice?.data?.columns?.get(column.columnId)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (item in PortalNewsColumn.entries) {
                PortalSectionTab(
                    label = item.label,
                    selected = column == item,
                    onSelect = { column = item },
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (page == null) {
                item {
                    PortalCard {
                        Text(
                            text = if (slice?.data == null) {
                                "暂无资讯数据（尚未同步，不代表学校没有发布）"
                            } else {
                                "${column.label}暂时没有内容"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        slice?.lastError?.let { message ->
                            Spacer(Modifier.height(6.dp))
                            Text(text = message, style = MaterialTheme.typography.labelSmall, color = colors.error)
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRefresh) { Text("同步资讯") }
                    }
                }
                return@LazyColumn
            }

            item {
                Text(
                    text = "${column.label} · 共 ${page.totalCount} 条（来源：${column.sourceHint}）",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                slice?.let { FreshnessHint(it.provenance.refreshState) }
            }

            items(page.articles) { article ->
                PortalCard(onClick = { onSelectArticle(article) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = article.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (article.isTop) {
                            Text(
                                text = "置顶",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = listOf(article.sourceOrg, formatNoticeDate(article.publishTimestampMs))
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                    if (article.summary.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = article.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PortalArticlePage(article: PortalNewsArticle, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val body = remember(article.content) { htmlToPlainText(article.content) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(
                text = "校内资讯",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            item {
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = listOf(
                        article.sourceOrg,
                        formatNoticeDate(article.publishTimestampMs),
                        if (article.visitCount.isNotBlank()) "阅读 ${article.visitCount}" else "",
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                if (body.isBlank()) {
                    Text(
                        text = "正文为空，可在浏览器打开原文阅读",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    MarkdownContent(markdown = body)
                }
            }
        }
    }
}

// ---------- 通用组件 ----------

private enum class Tone { Danger }

/** 页内分栏切换（校历 / 在借 / 资讯、资讯四栏）：用文字按钮保持与其它页一致的视觉语言。 */
@Composable
private fun PortalSectionTab(label: String, selected: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    TextButton(onClick = onSelect) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun PortalNotice(title: String, message: String, tone: Tone) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.error.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (tone == Tone.Danger) colors.error else colors.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PortalCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) { content() }
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) { content() }
        }
    }
}

/** 从 [PortalUiState] 里取出当前内容（含旧快照），Idle 返回 null。 */
private fun PortalUiState.contentOrNull(): PortalContent? = when (this) {
    PortalUiState.Idle -> null
    is PortalUiState.Loading -> content
    is PortalUiState.Loaded -> content
    is PortalUiState.AuthExpired -> content
    is PortalUiState.Failed -> content
}

/** 正文是 CMS 下发的 HTML：转成轻量 Markdown 文本交给共享 [MarkdownContent] 渲染。 */
private fun htmlToPlainText(html: String): String {
    if (html.isBlank()) return ""
    val cleaned = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p\\s*>"), "\n\n")
        .replace(Regex("(?i)</div\\s*>"), "\n\n")
        .replace(Regex("<[^>]+>"), "")
    return htmlUnescape(cleaned)
        .replace(Regex("[ \\t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}